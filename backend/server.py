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
    difficulty = value.get("difficulty", "初中")
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
    question_id = value.get("id")
    if require_id and (not isinstance(question_id, str) or not question_id.strip()):
        raise ValueError("question id is required")
    return {
        "id": question_id.strip() if isinstance(question_id, str) and question_id.strip() else f"q-{uuid.uuid4().hex[:12]}",
        "category": category.strip(),
        "difficulty": difficulty.strip(),
        "prompt": prompt.strip(),
        "options": normalized_options,
        "answer": answer,
    }


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
            credentials = json.loads(self.path.read_text(encoding="utf-8"))
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
            credentials = json.loads(self.path.read_text(encoding="utf-8"))
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
            self.send_json(200, STORE.load())
        elif path == "/api/v1/admin/state":
            if self.require_admin():
                self.send_json(200, STORE.load())
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
            if not remaining:
                self.send_json(409, {"error": "at least one question must remain"})
                return
            state["questions"] = remaining
            STORE.save(state)
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
    STORE.load()
    server = ThreadingHTTPServer((os.environ.get("HOST", "0.0.0.0"), PORT), RequestHandler)
    print(f"TV Parental admin: http://127.0.0.1:{PORT}")
    print(f"Device API: http://<computer-LAN-IP>:{PORT}/api/v1/device/state")
    server.serve_forever()


if __name__ == "__main__":
    main()
