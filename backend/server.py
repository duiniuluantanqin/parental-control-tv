#!/usr/bin/env python3
"""Small, dependency-free admin and device API for TV Parental Control."""

from __future__ import annotations

import argparse
import getpass
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
import uuid
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from deepseek import DeepSeekError, generate_poem_questions, generate_random_questions


ROOT = Path(__file__).resolve().parents[1]
SEED_FILE = ROOT / "app" / "src" / "main" / "assets" / "questions.json"
DATA_FILE = Path(os.environ.get("TV_PARENTAL_DATA_FILE", ROOT / "backend" / "data.json"))
ADMIN_FILE = Path(os.environ.get("TV_PARENTAL_ADMIN_FILE", ROOT / "backend" / "data" / "admin.json"))
PORT = int(os.environ.get("PORT", "8765"))
MAX_BODY_BYTES = 64 * 1024
SESSION_SECONDS = 12 * 60 * 60
SESSION_COOKIE = "tvparental_session"

DEFAULT_SETTINGS = {
    "dailyLimitMinutes": 60,
    "questionIntervalMinutes": 10,
    "wrongLockMinutes": 10,
    "syncIntervalMinutes": 5,
}


def validate_settings(value: object) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("settings must be an object")
    limits = {
        "dailyLimitMinutes": (1, 1440),
        "questionIntervalMinutes": (1, 240),
        "wrongLockMinutes": (1, 1440),
        "syncIntervalMinutes": (1, 60),
    }
    result: dict[str, int] = {}
    for name, (minimum, maximum) in limits.items():
        number = value.get(name, DEFAULT_SETTINGS[name])
        if isinstance(number, bool) or not isinstance(number, int) or not minimum <= number <= maximum:
            raise ValueError(f"{name} must be an integer from {minimum} to {maximum}")
        result[name] = number
    return result


def validate_question(value: object, *, require_id: bool = True) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("question must be an object")
    prompt = value.get("prompt")
    options = value.get("options")
    answer = value.get("answer")
    category = value.get("category", "其他")
    difficulty = value.get("difficulty", "中")
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt.strip()) > 240:
        raise ValueError("prompt must contain 1 to 240 characters")
    if not isinstance(options, list) or len(options) != 4:
        raise ValueError("each question must have exactly four options")
    if any(not isinstance(item, str) or not item.strip() or len(item.strip()) > 120 for item in options):
        raise ValueError("each option must contain 1 to 120 characters")
    normalized_options = [item.strip() for item in options]
    if len(set(normalized_options)) != 4:
        raise ValueError("options must be distinct")
    if not isinstance(answer, str) or answer not in normalized_options:
        raise ValueError("answer must match one of the four options")
    if not isinstance(category, str) or not category.strip() or len(category.strip()) > 40:
        raise ValueError("category must contain 1 to 40 characters")
    if not isinstance(difficulty, str) or not difficulty.strip() or len(difficulty.strip()) > 40:
        raise ValueError("difficulty must contain 1 to 40 characters")
    difficulty = difficulty.strip()
    difficulty = {"小学": "低", "初中": "中", "高中": "高"}.get(difficulty, difficulty)
    question_id = value.get("id")
    if require_id and (not isinstance(question_id, str) or not question_id.strip()):
        raise ValueError("question id is required")
    return {
        "id": question_id.strip() if isinstance(question_id, str) and question_id.strip() else f"q-{uuid.uuid4().hex[:12]}",
        "category": category.strip(),
        "difficulty": difficulty,
        "prompt": prompt.strip(),
        "options": normalized_options,
        "answer": answer,
    }


def validate_question_batch(value: object) -> list[dict[str, object]]:
    if not isinstance(value, list) or not 1 <= len(value) <= 10:
        raise ValueError("一次请选择 1–10 道题目")
    result = []
    for item in value:
        if not isinstance(item, dict):
            raise ValueError("题目格式不正确")
        result.append(validate_question({**item, "id": None}, require_id=False))
    return result


def default_state() -> dict[str, object]:
    questions = json.loads(SEED_FILE.read_text(encoding="utf-8"))
    if not isinstance(questions, list) or len(questions) != 50:
        raise RuntimeError("the shared question bank must contain exactly 50 questions")
    normalized = [validate_question(question) for question in questions]
    if len({question["id"] for question in normalized}) != len(normalized):
        raise RuntimeError("the shared question bank must use unique question ids")
    return {
        "settings": dict(DEFAULT_SETTINGS),
        "questions": normalized,
    }


class StateStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.RLock()
        self.refill_thread: threading.Thread | None = None
        self.refill_running = False
        self.refill_error: str | None = None
        self.next_refill_attempt = 0.0

    def load(self) -> dict[str, object]:
        with self.lock:
            if not self.path.exists():
                state = default_state()
                self.save(state)
                return state
            state = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(state, dict) or not isinstance(state.get("questions"), list):
                raise RuntimeError("backend state file has an invalid format")
            return {
                "settings": validate_settings(state.get("settings", DEFAULT_SETTINGS)),
                "questions": [validate_question(item) for item in state["questions"]],
            }

    def save(self, state: dict[str, object]) -> None:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix(self.path.suffix + ".tmp")
            temporary.write_text(
                json.dumps(state, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            temporary.replace(self.path)

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            state = self.load()
            if not state["questions"]:
                self.ensure_refill()
            return {**state, "refill": {"running": self.refill_running, "error": self.refill_error}}

    def ensure_refill(self) -> None:
        with self.lock:
            if self.load()["questions"] or self.refill_running or time.monotonic() < self.next_refill_attempt:
                return
            if not os.environ.get("DEEPSEEK_API_KEY", "").strip():
                self.refill_error = "自动补题尚未配置，请设置 DeepSeek Key"
                self.next_refill_attempt = time.monotonic() + 300
                return
            self.refill_running = True
            self.refill_error = None
            self.refill_thread = threading.Thread(target=self._refill, daemon=True)
            self.refill_thread.start()

    def _refill(self) -> None:
        try:
            generated = []
            prompts: set[str] = set()
            for _ in range(5):
                batch = generate_random_questions(10, "中", sorted(prompts))
                if len(batch) != 10:
                    raise ValueError("incorrect batch size")
                for item in batch:
                    if not isinstance(item, dict) or item.get("category") not in ("古诗词", "数学", "科学", "地理历史"):
                        raise ValueError("invalid category")
                    question = validate_question({**item, "id": None, "difficulty": "中"}, require_id=False)
                    if question["prompt"] in prompts:
                        raise ValueError("duplicate generated question")
                    prompts.add(question["prompt"])
                    generated.append(question)
            if {question["category"] for question in generated} != {"古诗词", "数学", "科学", "地理历史"}:
                raise ValueError("missing question categories")
            with self.lock:
                state = self.load()
                # A parent may have added questions while generation was running.
                if not state["questions"]:
                    state["questions"] = generated
                    self.save(state)
        except (DeepSeekError, ValueError, OSError) as error:
            with self.lock:
                self.refill_error = str(error) if isinstance(error, DeepSeekError) else "自动补题失败，请检查后台日志"
            print(f"Automatic question refill failed: {type(error).__name__}", flush=True)
        finally:
            with self.lock:
                self.refill_running = False
                self.next_refill_attempt = time.monotonic() + 300 if self.refill_error else 0.0


STORE = StateStore(DATA_FILE)


class AdminAuth:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.RLock()
        self.sessions: dict[str, tuple[float, str]] = {}
        self.failures: list[float] = []

    def set_password(self, username: str, password: str) -> None:
        if not 1 <= len(username.strip()) <= 64:
            raise ValueError("用户名需为 1–64 个字符")
        if not 10 <= len(password) <= 1024:
            raise ValueError("密码需为 10–1024 个字符")
        salt = secrets.token_bytes(16)
        credentials = {
            "username": username.strip(),
            "salt": salt.hex(),
            "iterations": 600_000,
            "passwordHash": hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600_000).hex(),
        }
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix(".tmp")
            with temporary.open("w", encoding="utf-8") as output:
                os.chmod(temporary, 0o600)
                json.dump(credentials, output)
            if hasattr(os, "geteuid") and os.geteuid() == 0:
                owner = self.path.parent.stat()
                os.chown(temporary, owner.st_uid, owner.st_gid)
            temporary.replace(self.path)
            self.sessions.clear()
            self.failures.clear()

    def login(self, username: str, password: str) -> tuple[int, str]:
        with self.lock:
            if not self.path.exists():
                return 503, "管理员尚未设置，请联系维护者配置账号"
            now = time.monotonic()
            self.failures = [attempt for attempt in self.failures if now - attempt < 60]
            if len(self.failures) >= 5:
                return 429, "尝试次数过多，请一分钟后再试"
            try:
                credentials = json.loads(self.path.read_text(encoding="utf-8"))
            except PermissionError:
                return 503, "管理员账号文件无法读取，请联系维护者检查文件权限"
            digest = hashlib.pbkdf2_hmac(
                "sha256", password.encode(), bytes.fromhex(credentials["salt"]), credentials["iterations"],
            ).hex()
            if username != credentials["username"] or not hmac.compare_digest(digest, credentials["passwordHash"]):
                self.failures.append(now)
                return 401, "用户名或密码错误"
            self.failures.clear()
            self.sessions = {key: value for key, value in self.sessions.items() if value[0] > now}
            session = secrets.token_urlsafe(32)
            self.sessions[session] = (now + SESSION_SECONDS, credentials["passwordHash"])
            return 200, session

    def is_authenticated(self, session: str) -> bool:
        with self.lock:
            expires, password_hash = self.sessions.get(session, (0, ""))
            if expires <= time.monotonic() or not self.path.exists():
                self.sessions.pop(session, None)
                return False
            try:
                credentials = json.loads(self.path.read_text(encoding="utf-8"))
            except PermissionError:
                return False
            return hmac.compare_digest(password_hash, credentials["passwordHash"])

    def logout(self, session: str) -> None:
        with self.lock:
            self.sessions.pop(session, None)


AUTH = AdminAuth(ADMIN_FILE)


class RequestHandler(BaseHTTPRequestHandler):
    server_version = "TvParentalAdmin/1.0"

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/device/state":
            self.send_json(200, STORE.snapshot())
        elif path == "/api/v1/admin/state":
            if self.require_admin():
                self.send_json(200, STORE.snapshot())
        elif path in ("/", "/index.html", "/login", "/settings", "/questions"):
            self.send_static("index.html", "text/html; charset=utf-8")
        elif path == "/app.js":
            self.send_static("app.js", "text/javascript; charset=utf-8")
        elif path == "/styles.css":
            self.send_static("styles.css", "text/css; charset=utf-8")
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/device/answers":
            if not self.same_origin():
                return
            try:
                payload = self.read_json()
                question_id, answer = payload.get("questionId"), payload.get("answer")
                if not isinstance(question_id, str) or not 1 <= len(question_id) <= 80 or not isinstance(answer, str) or not 1 <= len(answer) <= 120:
                    raise ValueError("请输入有效的题目 ID 和答案")
                with STORE.lock:
                    state = STORE.load()
                    question = next((item for item in state["questions"] if item["id"] == question_id), None)
                    if question is not None and answer != question["answer"]:
                        self.send_json(409, {"error": "答案不正确，题目已保留"})
                        return
                    if question is not None:
                        state["questions"] = [item for item in state["questions"] if item["id"] != question_id]
                        STORE.save(state)
                STORE.ensure_refill()
                # Unknown IDs are already gone; acknowledge retries without deleting other questions.
                self.send_json(200, {"removed": question is not None})
            except ValueError as error:
                self.send_json(400, {"error": str(error)})
            return
        if not path.startswith("/api/v1/admin/"):
            self.send_json(404, {"error": "not found"})
            return
        if not self.same_origin():
            return
        if path == "/api/v1/admin/login":
            try:
                payload = self.read_json()
                username, password = payload.get("username"), payload.get("password")
                if not isinstance(username, str) or not isinstance(password, str) or len(username) > 64 or len(password) > 1024:
                    raise ValueError("请输入有效的用户名和密码")
                status, result = AUTH.login(username, password)
                if status == 200:
                    self.send_json(200, {"username": username}, cookie=self.session_cookie(result, SESSION_SECONDS))
                else:
                    self.send_json(status, {"error": result})
            except ValueError as error:
                self.send_json(400, {"error": str(error)})
            return
        if path == "/api/v1/admin/logout":
            AUTH.logout(self.session_id())
            self.send_json(200, {"ok": True}, cookie=self.session_cookie("", 0))
            return
        if not self.require_admin():
            return
        try:
            payload = self.read_json()
            if path == "/api/v1/admin/questions/generate":
                title, count = payload.get("title"), payload.get("count", 5)
                difficulty = payload.get("difficulty", "中")
                if not isinstance(title, str) or not 1 <= len(title.strip()) <= 80:
                    raise ValueError("请输入 1–80 个字符的诗词名称，可附作者")
                if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 10:
                    raise ValueError("出题数量需为 1–10 道")
                if not isinstance(difficulty, str) or difficulty not in ("低", "中", "高"):
                    raise ValueError("难度需选择低、中或高")
                # Generate outside the state lock so TV sync and admin writes can continue.
                poem = generate_poem_questions(title.strip(), count, difficulty)
                try:
                    candidates = []
                    for item in poem["questions"]:
                        if not isinstance(item, dict):
                            raise ValueError("invalid question")
                        question = {**item, "category": "古诗词", "difficulty": difficulty}
                        prompt = question.get("prompt")
                        if isinstance(prompt, str) and poem["title"] not in prompt:
                            question["prompt"] = f"《{poem['title']}》：{prompt}"
                        candidates.append(question)
                    generated = validate_question_batch(candidates)
                    if len({question["prompt"] for question in generated}) != len(generated):
                        raise ValueError("duplicate generated questions")
                except ValueError:
                    raise DeepSeekError("生成题目的选项、答案或题干不符合要求，请重新生成") from None
                self.send_json(200, {**poem, "questions": generated})
                return
            if path == "/api/v1/admin/questions/batch":
                candidates = validate_question_batch(payload.get("questions"))
                with STORE.lock:
                    state = STORE.load()
                    existing = {question["prompt"] for question in state["questions"]}
                    added = 0
                    for question in candidates:
                        if question["prompt"] not in existing:
                            state["questions"].append(question)
                            existing.add(question["prompt"])
                            added += 1
                    if added:
                        STORE.save(state)
                self.send_json(200, {"added": added, "skipped": len(candidates) - added})
                return
            with STORE.lock:
                state = STORE.load()
                if path == "/api/v1/admin/settings":
                    state["settings"] = validate_settings(payload)
                    STORE.save(state)
                    self.send_json(200, {"settings": state["settings"]})
                elif path == "/api/v1/admin/questions":
                    question = validate_question(payload, require_id=False)
                    questions = state["questions"]
                    assert isinstance(questions, list)
                    questions.append(question)
                    STORE.save(state)
                    self.send_json(201, question)
                else:
                    self.send_json(404, {"error": "not found"})
        except DeepSeekError as error:
            self.send_json(error.status, {"error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": str(error)})

    def do_DELETE(self) -> None:
        prefix = "/api/v1/admin/questions/"
        path = urlsplit(self.path).path
        if not path.startswith(prefix):
            self.send_json(404, {"error": "not found"})
            return
        if not self.same_origin() or not self.require_admin():
            return
        question_id = path[len(prefix):]
        with STORE.lock:
            state = STORE.load()
            questions = state["questions"]
            assert isinstance(questions, list)
            remaining = [question for question in questions if question["id"] != question_id]
            if len(remaining) == len(questions):
                self.send_json(404, {"error": "question not found"})
                return
            state["questions"] = remaining
            STORE.save(state)
        STORE.ensure_refill()
        self.send_json(200, {"deleted": question_id, "count": len(remaining)})

    def read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("request body is empty or too large")
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("request body must be a JSON object")
        return payload

    def require_admin(self) -> bool:
        if not AUTH.is_authenticated(self.session_id()):
            self.send_json(401, {"error": "请先登录管理后台"})
            return False
        return True

    def same_origin(self) -> bool:
        origin = self.headers.get("Origin")
        if self.headers.get("Sec-Fetch-Site") == "cross-site" or (origin and urlsplit(origin).netloc != self.headers.get("Host")):
            self.send_json(403, {"error": "请求来源不受信任，请从管理页面操作"})
            return False
        return True

    def session_id(self) -> str:
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
        except CookieError:
            return ""
        return cookie[SESSION_COOKIE].value if SESSION_COOKIE in cookie else ""

    def session_cookie(self, value: str, max_age: int) -> str:
        cookie = SimpleCookie()
        cookie[SESSION_COOKIE] = value
        cookie[SESSION_COOKIE]["path"] = "/"
        cookie[SESSION_COOKIE]["httponly"] = True
        cookie[SESSION_COOKIE]["samesite"] = "Strict"
        cookie[SESSION_COOKIE]["max-age"] = max_age
        if self.client_address[0] in ("127.0.0.1", "::1") and self.headers.get("X-Forwarded-Proto") == "https":
            cookie[SESSION_COOKIE]["secure"] = True
        return cookie.output(header="").strip()

    def send_static(self, filename: str, content_type: str) -> None:
        content = (Path(__file__).parent / "web" / filename).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def send_json(self, status: int, payload: object, *, cookie: str | None = None) -> None:
        content = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        if cookie is not None:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, format: str, *args: object) -> None:
        print(f"{self.address_string()} - {format % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--set-admin", action="store_true", help="创建管理员或重设密码")
    args = parser.parse_args()
    if args.set_admin:
        username = input("用户名 [admin]: ").strip() or "admin"
        password = getpass.getpass("密码（至少 10 个字符）: ")
        if password != getpass.getpass("再次输入密码: "):
            raise SystemExit("两次密码不一致，未修改账号")
        try:
            AUTH.set_password(username, password)
        except ValueError as error:
            raise SystemExit(str(error)) from error
        print(f"管理员 {username} 已保存至 {AUTH.path}")
        return
    STORE.snapshot()
    server = ThreadingHTTPServer((os.environ.get("HOST", "0.0.0.0"), PORT), RequestHandler)
    print(f"TV Parental admin: http://127.0.0.1:{PORT}")
    print(f"Device API: http://<computer-LAN-IP>:{PORT}/api/v1/device/state")
    server.serve_forever()


if __name__ == "__main__":
    main()
