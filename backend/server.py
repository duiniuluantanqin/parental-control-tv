#!/usr/bin/env python3
"""Small, dependency-free admin and device API for TV Parental Control."""

from __future__ import annotations

import argparse
import getpass
import hashlib
import hmac
import json
import os
import re
import sqlite3
from datetime import date, datetime, time as datetime_time, timedelta, timezone
import secrets
import threading
import time
import uuid
from contextlib import closing
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from deepseek import (
    ENGLISH_WORD_PATTERN,
    RANDOM_QUESTION_CATEGORIES,
    DeepSeekError,
    generate_category_questions,
    generate_english_questions,
    generate_poem_for_question,
    generate_poem_questions,
    generate_random_questions,
    validate_poem,
)


ROOT = Path(__file__).resolve().parents[1]
SEED_FILE = ROOT / "app" / "src" / "main" / "assets" / "questions.json"
DATA_FILE = Path(os.environ.get("TV_PARENTAL_DATA_FILE", ROOT / "backend" / "data.sqlite3"))
PORT = int(os.environ.get("PORT", "8765"))
MAX_BODY_BYTES = 1024 * 1024
SESSION_SECONDS = 12 * 60 * 60
SESSION_COOKIE = "tvparental_session"
PASSWORD_ITERATIONS = 600_000
BACKEND_VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
DEVICE_ONLINE_SECONDS = 180

WEEKDAYS = tuple(str(day) for day in range(1, 8))
DEFAULT_SETTINGS = {
    "weeklyDailyLimitMinutes": {day: 60 for day in WEEKDAYS},
    "usageTimeWindows": {day: [] for day in WEEKDAYS},
    "disabledTimeWindows": {day: [] for day in WEEKDAYS},
    "questionIntervalMinutes": 10,
    "wrongLockMinutes": 10,
    "syncIntervalMinutes": 5,
}


def validate_settings(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("使用规则格式不正确")
    limits = {
        "questionIntervalMinutes": (1, 240),
        "wrongLockMinutes": (1, 1440),
        "syncIntervalMinutes": (1, 60),
    }
    daily_limits = value.get("weeklyDailyLimitMinutes", DEFAULT_SETTINGS["weeklyDailyLimitMinutes"])
    if not isinstance(daily_limits, dict) or set(daily_limits) != set(WEEKDAYS):
        raise ValueError("每周每日时长必须包含周一至周日")
    normalized_limits: dict[str, int] = {}
    for day in WEEKDAYS:
        number = daily_limits[day]
        if isinstance(number, bool) or not isinstance(number, int) or not 0 <= number <= 1440:
            raise ValueError(f"第 {day} 天的每日时长必须是 0 到 1440 之间的整数")
        normalized_limits[day] = number

    normalized_windows = validate_weekly_windows(
        value.get("usageTimeWindows", DEFAULT_SETTINGS["usageTimeWindows"]),
        "允许时段",
    )
    normalized_disabled_windows = validate_weekly_windows(
        value.get("disabledTimeWindows", DEFAULT_SETTINGS["disabledTimeWindows"]),
        "禁用时段",
    )

    result: dict[str, object] = {
        "weeklyDailyLimitMinutes": normalized_limits,
        "usageTimeWindows": normalized_windows,
        "disabledTimeWindows": normalized_disabled_windows,
    }
    for name, (minimum, maximum) in limits.items():
        number = value.get(name, DEFAULT_SETTINGS[name])
        if isinstance(number, bool) or not isinstance(number, int) or not minimum <= number <= maximum:
            raise ValueError(f"{name} 必须是 {minimum} 到 {maximum} 之间的整数")
        result[name] = number
    return result


def validate_weekly_windows(value: object, name: str) -> dict[str, list[dict[str, str]]]:
    if not isinstance(value, dict) or set(value) != set(WEEKDAYS):
        raise ValueError(f"{name} 必须包含周一至周日")
    normalized: dict[str, list[dict[str, str]]] = {}
    for day in WEEKDAYS:
        day_windows = value[day]
        if not isinstance(day_windows, list) or len(day_windows) > 8:
            raise ValueError(f"{name} 的第 {day} 天最多可设置 8 个时段")
        normalized_day: list[dict[str, str]] = []
        for window in day_windows:
            if not isinstance(window, dict):
                raise ValueError("时段格式不正确")
            start = window.get("start")
            end = window.get("end")
            try:
                start_time = datetime_time.fromisoformat(start) if isinstance(start, str) else None
                end_time = datetime_time.fromisoformat(end) if isinstance(end, str) else None
            except ValueError:
                start_time = end_time = None
            if (
                start_time is None or end_time is None
                or start_time.isoformat(timespec="minutes") != start
                or end_time.isoformat(timespec="minutes") != end
            ):
                raise ValueError("时段时间必须使用 HH:MM 格式")
            if start == end:
                raise ValueError("时段开始和结束时间不能相同")
            normalized_day.append({"start": start, "end": end})
        normalized[day] = normalized_day
    return normalized


def merge_usage_intervals(value: object) -> list[dict[str, int]]:
    if not isinstance(value, list):
        return []
    intervals = []
    for item in value:
        if not isinstance(item, dict):
            continue
        start, end = item.get("start"), item.get("end")
        if type(start) is int and type(end) is int and 0 <= start < end:
            intervals.append((start, end))
    merged: list[dict[str, int]] = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1]["end"] + 120_000:
            merged[-1]["end"] = max(merged[-1]["end"], end)
        else:
            merged.append({"start": start, "end": end})
    return merged


def validate_question(value: object, *, require_id: bool = True) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("题目格式不正确")
    prompt = value.get("prompt")
    options = value.get("options")
    answer = value.get("answer")
    category = value.get("category", "其他")
    difficulty = value.get("difficulty", "中")
    priority = value.get("priority", False)
    if not isinstance(priority, bool):
        raise ValueError("优先选项必须为布尔值")
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt.strip()) > 240:
        raise ValueError("题目内容长度必须为 1 到 240 个字符")
    if not isinstance(options, list) or len(options) != 4:
        raise ValueError("每道题必须包含四个选项")
    if any(not isinstance(item, str) or not item.strip() or len(item.strip()) > 120 for item in options):
        raise ValueError("选项长度必须为 1 到 120 个字符")
    normalized_options = [item.strip() for item in options]
    if len(set(normalized_options)) != 4:
        raise ValueError("四个选项必须各不相同")
    if not isinstance(answer, str) or answer not in normalized_options:
        raise ValueError("正确答案必须与其中一个选项完全一致")
    if not isinstance(category, str) or not category.strip() or len(category.strip()) > 40:
        raise ValueError("类别长度必须为 1 到 40 个字符")
    if not isinstance(difficulty, str) or not difficulty.strip() or len(difficulty.strip()) > 40:
        raise ValueError("难度长度必须为 1 到 40 个字符")
    difficulty = difficulty.strip()
    difficulty = {"小学": "低", "初中": "中", "高中": "高"}.get(difficulty, difficulty)
    question_id = value.get("id")
    if require_id and (not isinstance(question_id, str) or not question_id.strip()):
        raise ValueError("题目 ID 不能为空")
    question = {
        "id": question_id.strip() if isinstance(question_id, str) and question_id.strip() else f"q-{uuid.uuid4().hex[:12]}",
        "category": category.strip(),
        "difficulty": difficulty,
        "priority": priority,
        "prompt": prompt.strip(),
        "options": normalized_options,
        "answer": answer,
    }
    if value.get("poem") is not None:
        question["poem"] = validate_poem(value["poem"])
    return question


def validate_question_batch(value: object) -> list[dict[str, object]]:
    if not isinstance(value, list) or not 1 <= len(value) <= 10:
        raise ValueError("一次请选择 1–10 道题目")
    result = []
    for item in value:
        if not isinstance(item, dict):
            raise ValueError("题目格式不正确")
        result.append(validate_question({**item, "id": None}, require_id=False))
    return result


def validate_password_reset(value: object) -> dict[str, str] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise RuntimeError("backend password reset command has an invalid format")
    command_id = value.get("id")
    if not isinstance(command_id, str) or not 1 <= len(command_id) <= 64:
        raise RuntimeError("backend password reset command has an invalid ID")
    return {"id": command_id}


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
        "passwordReset": None,
        "dailyUsage": [],
        "answerRecords": [],
        "challengeRecords": [],
        "devices": {},
    }


class StateStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.RLock()
        self.account_stores: dict[int, StateStore] = {}
        self.refill_thread: threading.Thread | None = None
        self.refill_running = False
        self.refill_error: str | None = None
        self.next_refill_attempt = 0.0
        self.poem_thread: threading.Thread | None = None
        self.poem_enrichment_running = False
        self.poem_enrichment_error: str | None = None
        self.next_poem_attempt = 0.0
        self.poem_failures: dict[str, int] = {}

    def for_account(self, account_id: int) -> StateStore:
        if account_id == 1:
            return self
        with self.lock:
            if account_id not in self.account_stores:
                path = self.path.parent / (self.path.name + ".accounts") / f"{account_id}.sqlite3"
                self.account_stores[account_id] = StateStore(path)
            return self.account_stores[account_id]

    def load(self) -> dict[str, object]:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with closing(sqlite3.connect(self.path, timeout=10)) as connection:
                self._create_schema(connection)
                row = connection.execute(
                    "SELECT settings, password_reset FROM app_state WHERE id = 1",
                ).fetchone()
                if row is None:
                    state = default_state()
                    self._write_state(connection, state)
                else:
                    state = {
                        "settings": json.loads(row[0]),
                        "passwordReset": json.loads(row[1]) if row[1] is not None else None,
                        "questions": [json.loads(item[0]) for item in connection.execute("SELECT payload FROM questions ORDER BY position")],
                        "dailyUsage": [
                            {
                                "deviceId": item[0], "date": item[1], "usageMillis": item[2],
                                "intervals": json.loads(item[3]),
                            }
                            for item in connection.execute("SELECT device_id, record_date, usage_millis, intervals FROM daily_usage")
                        ],
                        "answerRecords": [json.loads(item[0]) for item in connection.execute("SELECT payload FROM answer_records ORDER BY position")],
                        "challengeRecords": [json.loads(item[0]) for item in connection.execute("SELECT payload FROM challenge_records ORDER BY position")],
                        "devices": {
                            item[0]: json.loads(item[1])
                            for item in connection.execute("SELECT device_id, payload FROM devices")
                        },
                    }
            if not isinstance(state, dict) or not isinstance(state.get("questions"), list):
                raise RuntimeError("backend state database has an invalid format")
            return {
                "settings": validate_settings(state.get("settings", DEFAULT_SETTINGS)),
                "questions": [validate_question(item) for item in state["questions"]],
                "passwordReset": validate_password_reset(state.get("passwordReset")),
                "dailyUsage": state.get("dailyUsage", []),
                "answerRecords": state.get("answerRecords", []),
                "challengeRecords": state.get("challengeRecords", []),
                "devices": state.get("devices", {}),
            }

    def queue_password_reset(self) -> dict[str, str]:
        with self.lock:
            state = self.load()
            command = {"id": uuid.uuid4().hex}
            state["passwordReset"] = command
            self.save(state)
            return command

    def save(self, state: dict[str, object]) -> None:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with closing(sqlite3.connect(self.path, timeout=10)) as connection:
                self._create_schema(connection)
                self._write_state(connection, state)

    def queue_usage_reset(self, device_id: str) -> dict[str, str]:
        if not isinstance(device_id, str) or not 1 <= len(device_id) <= 80:
            raise ValueError("请选择要重置的设备")
        with self.lock:
            state = self.load()
            device = state["devices"].get(device_id)
            if device is None:
                raise ValueError("设备尚未上报使用时长，请先让电视同步")
            offset = device.get("utcOffsetMinutes")
            if isinstance(offset, bool) or not isinstance(offset, int) or not -840 <= offset <= 840:
                raise ValueError("尚未获取电视时区，请升级电视应用并同步后重试")
            record_date = datetime.fromtimestamp(time.time(), timezone(timedelta(minutes=offset))).date().isoformat()
            command = {"id": uuid.uuid4().hex, "date": record_date}
            device.setdefault("usageResets", {})[record_date] = command
            for record in state["dailyUsage"]:
                if record["deviceId"] == device_id and record["date"] == record_date:
                    record.update(usageMillis=0, intervals=[])
            if device.get("lastUsageDate", record_date) == record_date:
                device.update(lastUsageMillis=0, lastUsageAt=0)
            self.save(state)
            return command

    @staticmethod
    def _create_schema(connection: sqlite3.Connection) -> None:
        connection.executescript("""
            CREATE TABLE IF NOT EXISTS app_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                settings TEXT NOT NULL,
                password_reset TEXT
            );
            CREATE TABLE IF NOT EXISTS questions (
                position INTEGER PRIMARY KEY,
                question_id TEXT NOT NULL UNIQUE,
                payload TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS poem_history (
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                PRIMARY KEY (title, author)
            );
            CREATE TABLE IF NOT EXISTS daily_usage (
                device_id TEXT NOT NULL,
                record_date TEXT NOT NULL,
                usage_millis INTEGER NOT NULL,
                intervals TEXT NOT NULL,
                PRIMARY KEY (device_id, record_date)
            );
            CREATE TABLE IF NOT EXISTS answer_records (
                position INTEGER PRIMARY KEY,
                record_date TEXT NOT NULL,
                device_id TEXT NOT NULL,
                answered_at INTEGER NOT NULL,
                challenge_id TEXT NOT NULL,
                payload TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS answer_records_by_day ON answer_records(record_date, device_id, answered_at);
            CREATE TABLE IF NOT EXISTS challenge_records (
                position INTEGER PRIMARY KEY,
                record_date TEXT NOT NULL,
                device_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                session_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                UNIQUE (device_id, session_id)
            );
            CREATE INDEX IF NOT EXISTS challenge_records_by_day ON challenge_records(record_date, device_id, started_at);
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                payload TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS admin_account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                username TEXT NOT NULL,
                salt TEXT NOT NULL,
                iterations INTEGER NOT NULL,
                password_hash TEXT NOT NULL
            );
        """)
        if connection.execute("SELECT 1 FROM admin_account WHERE id = 1").fetchone() is None:
            salt = secrets.token_bytes(16)
            password_hash = hashlib.pbkdf2_hmac("sha256", b"admin", salt, PASSWORD_ITERATIONS).hex()
            connection.execute(
                "INSERT INTO admin_account(id, username, salt, iterations, password_hash) VALUES (1, ?, ?, ?, ?)",
                ("admin", salt.hex(), PASSWORD_ITERATIONS, password_hash),
            )
            connection.commit()

    @staticmethod
    def _remember_poems(connection: sqlite3.Connection, questions: list[dict[str, object]]) -> None:
        seed = {item["id"]: item for item in json.loads(SEED_FILE.read_text(encoding="utf-8"))}
        works = set()
        for question in questions:
            if question.get("category", "古诗词") != "古诗词":
                continue
            poem = question.get("poem")
            original = seed.get(question.get("id"))
            if not poem and original and original["prompt"] == question.get("prompt"):
                poem = original.get("poem")
            if poem:
                works.add((poem["title"].strip(), poem["author"].strip()))
            else:
                works.update((title.strip(), "") for title in re.findall(r"《([^《》]+)》", question.get("prompt", "")) if title.strip())
        connection.executemany("INSERT OR IGNORE INTO poem_history(title, author) VALUES (?, ?)", sorted(works))

    def poem_history(self) -> list[dict[str, str]]:
        with self.lock:
            state = self.load()
            with closing(sqlite3.connect(self.path, timeout=10)) as connection, connection:
                self._remember_poems(connection, state["questions"] + [
                    item.get("question") or {"prompt": item.get("prompt", ""), "id": item.get("questionId")}
                    for item in state["answerRecords"]
                ])
                rows = connection.execute("SELECT title, author FROM poem_history ORDER BY title, author").fetchall()
            known_titles = {title for title, author in rows if author}
            return [{"title": title, "author": author} for title, author in rows if author or title not in known_titles]

    @staticmethod
    def _write_state(connection: sqlite3.Connection, state: dict[str, object]) -> None:
        encoded = lambda value: json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        with connection:
            # Remember the previous bank before deletion or a correct answer removes a work.
            StateStore._remember_poems(connection, [
                json.loads(item[0]) for item in connection.execute("SELECT payload FROM questions")
            ] + state["questions"] + [
                item.get("question") or {"prompt": item.get("prompt", ""), "id": item.get("questionId")}
                for item in state["answerRecords"]
            ])
            connection.execute(
                "INSERT OR REPLACE INTO app_state(id, settings, password_reset) VALUES (1, ?, ?)",
                (encoded(state["settings"]), encoded(state["passwordReset"]) if state.get("passwordReset") is not None else None),
            )
            for table in ("questions", "daily_usage", "answer_records", "challenge_records", "devices"):
                connection.execute(f"DELETE FROM {table}")
            connection.executemany(
                "INSERT INTO questions(position, question_id, payload) VALUES (?, ?, ?)",
                [(index, item["id"], encoded(item)) for index, item in enumerate(state["questions"])],
            )
            connection.executemany(
                "INSERT INTO daily_usage(device_id, record_date, usage_millis, intervals) VALUES (?, ?, ?, ?)",
                [
                    (item["deviceId"], item["date"], item.get("usageMillis", 0), encoded(item.get("intervals", [])))
                    for item in state["dailyUsage"]
                ],
            )
            connection.executemany(
                "INSERT INTO answer_records(position, record_date, device_id, answered_at, challenge_id, payload) VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (index, item.get("date", ""), item.get("deviceId", ""), item.get("answeredAt", 0), item.get("challengeId", ""), encoded(item))
                    for index, item in enumerate(state["answerRecords"])
                ],
            )
            connection.executemany(
                "INSERT INTO challenge_records(position, record_date, device_id, started_at, session_id, payload) VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (index, item["date"], item["deviceId"], item["startedAt"], item["sessionId"], encoded(item))
                    for index, item in enumerate(state["challengeRecords"])
                ],
            )
            connection.executemany(
                "INSERT INTO devices(device_id, payload) VALUES (?, ?)",
                [(device_id, encoded(item)) for device_id, item in state["devices"].items()],
            )

    def snapshot(self, device_id: str = "", record_date: str = "") -> dict[str, object]:
        with self.lock:
            state = self.load()
            if not state["questions"]:
                self.ensure_refill()
            self.ensure_poem_enrichment()
            return {
                "settings": state["settings"],
                "questions": state["questions"],
                "passwordReset": state["passwordReset"],
                "usageReset": state["devices"].get(device_id, {}).get("usageResets", {}).get(record_date),
                "version": BACKEND_VERSION,
                "refill": {"running": self.refill_running, "error": self.refill_error},
                "poemEnrichment": {
                    "running": self.poem_enrichment_running,
                    "pending": sum(item["category"] == "古诗词" and not item.get("poem") for item in state["questions"]),
                    "error": self.poem_enrichment_error,
                },
            }

    def ensure_poem_enrichment(self) -> None:
        with self.lock:
            if self.poem_enrichment_running or time.monotonic() < self.next_poem_attempt:
                return
            pending = [item for item in self.load()["questions"] if item["category"] == "古诗词" and not item.get("poem")]
            if not pending:
                self.poem_enrichment_error = None
                return
            if not os.environ.get("DEEPSEEK_API_KEY", "").strip():
                self.poem_enrichment_error = "作品全文补全尚未配置，请设置 DeepSeek Key"
                return
            candidates = [item for item in pending if self.poem_failures.get(self.poem_source_key(item), 0) < 3]
            if not candidates:
                return
            self.poem_enrichment_running = True
            self.poem_thread = threading.Thread(target=self._enrich_poems, args=(candidates[:10],), daemon=True)
            self.poem_thread.start()

    @staticmethod
    def poem_source_key(question: dict[str, object]) -> str:
        return hashlib.sha256(json.dumps(question, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()

    def _enrich_poems(self, questions: list[dict[str, object]]) -> None:
        try:
            for source in questions:
                key = self.poem_source_key(source)
                try:
                    poem = validate_poem(generate_poem_for_question(source["prompt"], source["answer"]))
                    with self.lock:
                        state = self.load()
                        question = next((item for item in state["questions"] if item["id"] == source["id"]), None)
                        if question == source:
                            question["poem"] = poem
                            self.save(state)
                        self.poem_failures.pop(key, None)
                except (DeepSeekError, ValueError, OSError, sqlite3.Error):
                    with self.lock:
                        attempts = self.poem_failures.get(key, 0) + 1
                        self.poem_failures[key] = attempts
                        self.poem_enrichment_error = "作品全文补全失败，请检查题目中的作品名称和作者"
                        self.next_poem_attempt = time.monotonic() + min(300 * 2 ** (attempts - 1), 1800)
                    break
        finally:
            with self.lock:
                self.poem_enrichment_running = False

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
        self.sessions: dict[str, tuple[float, int, str]] = {}
        self.failures: dict[str, list[float]] = {}

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=10)
        StateStore._create_schema(connection)
        connection.executescript("""
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                salt TEXT NOT NULL,
                iterations INTEGER NOT NULL,
                password_hash TEXT NOT NULL
            );
            INSERT OR IGNORE INTO accounts(id, username, salt, iterations, password_hash)
                SELECT id, username, salt, iterations, password_hash FROM admin_account WHERE id = 1;
            CREATE TABLE IF NOT EXISTS device_bindings (
                device_id TEXT PRIMARY KEY,
                token_hash TEXT NOT NULL,
                name TEXT NOT NULL,
                account_id INTEGER,
                binding_id TEXT NOT NULL DEFAULT '',
                code TEXT UNIQUE,
                expires_at INTEGER NOT NULL DEFAULT 0,
                last_code_at INTEGER NOT NULL DEFAULT 0
            );
        """)
        return connection

    @staticmethod
    def _digest(password: str, salt: str, iterations: int) -> str:
        return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), iterations).hex()

    @staticmethod
    def validate_credentials(username: str, password: str) -> None:
        if not isinstance(username, str) or not 1 <= len(username.strip()) <= 64:
            raise ValueError("用户名需为 1–64 个字符")
        if not isinstance(password, str) or not password:
            raise ValueError("请输入密码")

    def throttled(self, key: str, limit: int = 5, seconds: int = 60) -> bool:
        now = time.monotonic()
        self.failures = {name: [stamp for stamp in stamps if now - stamp < seconds]
                         for name, stamps in self.failures.items() if any(now - stamp < seconds for stamp in stamps)}
        return len(self.failures.get(key, [])) >= limit

    def fail(self, key: str) -> None:
        self.failures.setdefault(key, []).append(time.monotonic())

    def set_password(self, username: str, password: str) -> None:
        self.validate_credentials(username, password)
        with self.lock, closing(self.connect()) as connection, connection:
            row = connection.execute("SELECT id FROM accounts WHERE username = ?", (username.strip(),)).fetchone()
            account_id = row[0] if row else 1
            salt = secrets.token_bytes(16).hex()
            connection.execute(
                "UPDATE accounts SET username = ?, salt = ?, iterations = ?, password_hash = ? WHERE id = ?",
                (username.strip(), salt, PASSWORD_ITERATIONS, self._digest(password, salt, PASSWORD_ITERATIONS), account_id),
            )
            self.sessions = {key: value for key, value in self.sessions.items() if value[1] != account_id}
            self.failures.clear()

    def register(self, username: str, password: str, confirm: str, client: str) -> tuple[int, str]:
        self.validate_credentials(username, password)
        if password != confirm:
            raise ValueError("两次输入的密码不一致")
        with self.lock:
            key = "register:" + client
            if self.throttled(key):
                return 429, "注册次数过多，请一分钟后再试"
            self.fail(key)
            salt = secrets.token_bytes(16).hex()
            try:
                with closing(self.connect()) as connection, connection:
                    cursor = connection.execute(
                        "INSERT INTO accounts(username, salt, iterations, password_hash) VALUES (?, ?, ?, ?)",
                        (username.strip(), salt, PASSWORD_ITERATIONS, self._digest(password, salt, PASSWORD_ITERATIONS)),
                    )
                    # Initialize an independent seed bank before making the account visible.
                    STORE.for_account(cursor.lastrowid).load()
            except sqlite3.IntegrityError:
                return 409, "用户名已存在"
            return 201, "注册成功，请登录"

    def login(self, username: str, password: str) -> tuple[int, str]:
        with self.lock:
            key = "login:" + username.casefold()
            if self.throttled(key):
                return 429, "尝试次数过多，请一分钟后再试"
            try:
                with closing(self.connect()) as connection:
                    credentials = connection.execute(
                        "SELECT id, salt, iterations, password_hash FROM accounts WHERE username = ?", (username,),
                    ).fetchone()
            except sqlite3.Error:
                return 503, "账号数据库不可用"
            if credentials is None:
                self.fail(key)
                return 401, "用户名或密码错误"
            account_id, salt, iterations, saved_hash = credentials
            if not hmac.compare_digest(self._digest(password, salt, iterations), saved_hash):
                self.fail(key)
                return 401, "用户名或密码错误"
            self.failures.pop(key, None)
            now = time.monotonic()
            self.sessions = {key: value for key, value in self.sessions.items() if value[0] > now}
            session = secrets.token_urlsafe(32)
            self.sessions[session] = (now + SESSION_SECONDS, account_id, saved_hash)
            return 200, session

    def account_id(self, session: str) -> int | None:
        with self.lock:
            expires, account_id, password_hash = self.sessions.get(session, (0, 0, ""))
            if expires > time.monotonic():
                try:
                    with closing(self.connect()) as connection:
                        row = connection.execute("SELECT password_hash FROM accounts WHERE id = ?", (account_id,)).fetchone()
                    if row and hmac.compare_digest(password_hash, row[0]):
                        return account_id
                except sqlite3.Error:
                    pass
            self.sessions.pop(session, None)
            return None

    def is_authenticated(self, session: str) -> bool:
        return self.account_id(session) is not None

    def change_password(self, session: str, current_password: str, new_password: str) -> tuple[int, str]:
        if not isinstance(new_password, str) or not new_password:
            return 400, "请输入新密码"
        with self.lock:
            account_id = self.account_id(session)
            if account_id is None:
                return 401, "登录已过期，请重新登录"
            try:
                with closing(self.connect()) as connection, connection:
                    salt, iterations, saved_hash = connection.execute(
                        "SELECT salt, iterations, password_hash FROM accounts WHERE id = ?", (account_id,),
                    ).fetchone()
                    if not hmac.compare_digest(self._digest(current_password, salt, iterations), saved_hash):
                        return 400, "当前密码不正确"
                    salt = secrets.token_bytes(16).hex()
                    new_hash = self._digest(new_password, salt, PASSWORD_ITERATIONS)
                    connection.execute("UPDATE accounts SET salt = ?, iterations = ?, password_hash = ? WHERE id = ?",
                                       (salt, PASSWORD_ITERATIONS, new_hash, account_id))
            except sqlite3.Error:
                return 503, "账号数据库不可用"
            expires = self.sessions[session][0]
            self.sessions = {key: value for key, value in self.sessions.items() if value[1] != account_id}
            self.sessions[session] = (expires, account_id, new_hash)
            return 200, ""

    def logout(self, session: str) -> None:
        with self.lock:
            self.sessions.pop(session, None)

    def device(self, device_id: str, token: str, *, enroll: bool = False, name: str = "") -> dict[str, object] | None:
        if not isinstance(device_id, str) or not 1 <= len(device_id) <= 80 or not re.fullmatch(r"[A-Za-z0-9_-]{40,128}", token):
            return None
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        with self.lock, closing(self.connect()) as connection, connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute("SELECT * FROM device_bindings WHERE device_id = ?", (device_id,)).fetchone()
            if row is None and enroll:
                connection.execute("INSERT INTO device_bindings(device_id, token_hash, name) VALUES (?, ?, ?)",
                                   (device_id, token_hash, name[:100] or device_id))
                row = connection.execute("SELECT * FROM device_bindings WHERE device_id = ?", (device_id,)).fetchone()
            elif row is not None and enroll and not hmac.compare_digest(row["token_hash"], token_hash):
                if row["account_id"] is not None:
                    raise PermissionError("设备已绑定账号，请先由原账号解绑")
                connection.execute(
                    "UPDATE device_bindings SET token_hash = ?, name = ?, code = NULL, expires_at = 0, last_code_at = 0 WHERE device_id = ?",
                    (token_hash, name[:100] or device_id, device_id),
                )
                row = connection.execute("SELECT * FROM device_bindings WHERE device_id = ?", (device_id,)).fetchone()
            if row is None or not hmac.compare_digest(row["token_hash"], token_hash):
                return None
            return dict(row)

    def binding_status(self, device: dict[str, object], *, refresh: bool = False) -> dict[str, object]:
        with self.lock, closing(self.connect()) as connection, connection:
            if device["account_id"] is not None:
                username = connection.execute("SELECT username FROM accounts WHERE id = ?", (device["account_id"],)).fetchone()[0]
                return {"bound": True, "accountId": str(device["account_id"]), "username": username, "bindingId": device["binding_id"]}
            now = int(time.time())
            if refresh and device["expires_at"] <= now:
                if now - device["last_code_at"] < 10:
                    raise ValueError("请稍后再刷新绑定码")
                connection.execute("UPDATE device_bindings SET code = NULL WHERE expires_at <= ?", (now,))
                while True:
                    code = f"{secrets.randbelow(1_000_000):06d}"
                    if connection.execute("SELECT 1 FROM device_bindings WHERE code = ?", (code,)).fetchone() is None:
                        break
                connection.execute("UPDATE device_bindings SET code = ?, expires_at = ?, last_code_at = ? WHERE device_id = ?",
                                   (code, now + 300, now, device["device_id"]))
                device.update(code=code, expires_at=now + 300)
            return {"bound": False, "code": device["code"] if device["expires_at"] > now else None,
                    "expiresAt": device["expires_at"] * 1000}

    def pairing_device(self, code: object, account_id: int) -> dict[str, object] | None:
        with self.lock:
            key = f"pair:{account_id}"
            if self.throttled(key):
                return None
            self.fail(key)
            if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
                return None
            with closing(self.connect()) as connection:
                connection.row_factory = sqlite3.Row
                row = connection.execute("SELECT * FROM device_bindings WHERE code = ? AND expires_at > ? AND account_id IS NULL",
                                         (code, int(time.time()))).fetchone()
                return dict(row) if row else None

    def bind(self, code: object, device_id: object, account_id: int) -> dict[str, object] | None:
        with self.lock:
            device = self.pairing_device(code, account_id)
            if device is None or device["device_id"] != device_id:
                return None
            with closing(self.connect()) as connection, connection:
                connection.execute("UPDATE device_bindings SET account_id = ?, binding_id = ?, code = NULL, expires_at = 0 WHERE device_id = ?",
                                   (account_id, uuid.uuid4().hex, device_id))
            store = STORE.for_account(account_id)
            with store.lock:
                state = store.load()
                state["devices"].setdefault(device_id, {"lastSeen": 0})["name"] = device["name"]
                store.save(state)
            return {"deviceId": device_id, "name": device["name"]}

    def unbind(self, device_id: object, account_id: int) -> bool:
        with self.lock, closing(self.connect()) as connection, connection:
            cursor = connection.execute("DELETE FROM device_bindings WHERE device_id = ? AND account_id = ?",
                                        (device_id, account_id))
            return cursor.rowcount == 1


AUTH = AdminAuth(DATA_FILE)


class RequestHandler(BaseHTTPRequestHandler):
    store: StateStore
    account: int
    server_version = f"TvParentalAdmin/{BACKEND_VERSION}"

    @staticmethod
    def device_summary(device_id: str, device: dict[str, object]) -> dict[str, object]:
        last_seen = device.get("lastSeen", 0)
        return {
            "deviceId": device_id,
            "name": device.get("name") or device_id,
            "lastSeen": last_seen,
            "online": isinstance(last_seen, int) and int(time.time() * 1000) - last_seen <= DEVICE_ONLINE_SECONDS * 1000,
        }

    def do_GET(self) -> None:
        with AUTH.lock:
            self.handle_get()

    def handle_get(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/health":
            self.send_json(200, {"ok": True, "version": BACKEND_VERSION})
        elif path == "/api/v1/device/binding":
            device = self.require_device(bound=False)
            if device:
                self.send_json(200, AUTH.binding_status(device))
        elif path == "/api/v1/device/state":
            if not self.require_device():
                return
            params = parse_qs(urlsplit(self.path).query)
            if params.get("deviceId", [self.device_id])[0] != self.device_id:
                self.send_json(403, {"error": "设备 ID 与凭证不匹配"})
                return
            self.send_json(200, self.store.snapshot(params.get("deviceId", [""])[0], params.get("date", [""])[0]))
        elif path == "/api/v1/admin/state":
            if self.require_admin():
                self.send_json(200, self.store.snapshot())
        elif path == "/api/v1/admin/poems":
            if self.require_admin():
                self.send_json(200, {"poems": self.store.poem_history()})
        elif path == "/api/v1/admin/records":
            if self.require_admin():
                params = parse_qs(urlsplit(self.path).query)
                record_date = params.get("date", [datetime.now().astimezone().date().isoformat()])[0]
                device_id = params.get("deviceId", [""])[0]
                try:
                    if date.fromisoformat(record_date).isoformat() != record_date:
                        raise ValueError
                except ValueError:
                    self.send_json(400, {"error": "日期格式必须为 YYYY-MM-DD"})
                    return
                with self.store.lock:
                    state = self.store.load()
                    device_ids = sorted({
                        item["deviceId"]
                        for key in ("dailyUsage", "answerRecords", "challengeRecords")
                        for item in state[key]
                        if isinstance(item.get("deviceId"), str)
                    } | set(state["devices"]))
                    device_details = [
                        self.device_summary(device_id, state["devices"].get(device_id, {}))
                        for device_id in device_ids
                    ]
                    daily_usage = {
                        item["deviceId"]: item for item in state["dailyUsage"]
                        if item.get("date") == record_date
                    }
                    selected_ids = sorted(value for value in daily_usage if not device_id or value == device_id)
                    usage = [
                        {
                            "deviceId": value,
                            "date": record_date,
                            "usageMillis": daily_usage.get(value, {}).get("usageMillis", 0),
                            "intervals": merge_usage_intervals(daily_usage.get(value, {}).get("intervals", [])),
                            "lastReportedAt": state["devices"].get(value, {}).get("lastSeen", 0),
                        }
                        for value in selected_ids
                    ]
                    challenges = [
                        item for item in state["challengeRecords"]
                        if item.get("date") == record_date and (not device_id or item.get("deviceId") == device_id)
                    ]
                    challenge_ids = {item["sessionId"] for item in challenges}
                    answers = [
                        item for item in state["answerRecords"]
                        if (item.get("date") == record_date or item.get("challengeId") in challenge_ids)
                        and (not device_id or item.get("deviceId") == device_id)
                    ]
                    question_ids = {item["id"] for item in state["questions"]}
                    seed_question_ids = {item["id"] for item in json.loads(SEED_FILE.read_text(encoding="utf-8"))}
                self.send_json(200, {
                    "date": record_date,
                    "devices": device_ids,
                    "deviceDetails": device_details,
                    "usage": sorted(usage, key=lambda item: item["deviceId"]),
                    "answers": [
                        {
                            **{key: value for key, value in item.items() if key not in ("correctAnswer", "question")},
                            "deviceName": state["devices"].get(item.get("deviceId"), {}).get("name", item["deviceId"]),
                            "restorable": isinstance(item.get("question"), dict) or item.get("questionId") in seed_question_ids,
                            "inQuestionBank": item.get("questionId") in question_ids,
                        }
                        for item in sorted(answers, key=lambda item: item["answeredAt"], reverse=True)
                    ],
                    "challenges": [
                        {
                            **item,
                            "deviceName": state["devices"].get(item["deviceId"], {}).get("name", item["deviceId"]),
                        }
                        for item in sorted(challenges, key=lambda item: item["startedAt"], reverse=True)
                    ],
                })
        elif path == "/api/v1/admin/devices":
            if self.require_admin():
                with self.store.lock:
                    state = self.store.load()
                    devices = state["devices"]
                    with closing(AUTH.connect()) as connection:
                        known_ids = {row[0] for row in connection.execute("SELECT device_id FROM device_bindings WHERE account_id = ?", (self.account,))}
                    bound_ids = set(known_ids)
                    known_ids.update(devices)
                    known_ids.update(
                        item["deviceId"]
                        for key in ("dailyUsage", "answerRecords", "challengeRecords")
                        for item in state[key]
                        if isinstance(item.get("deviceId"), str)
                    )
                self.send_json(200, {
                    "devices": [
                        {**self.device_summary(device_id, devices.get(device_id, {})), "bound": device_id in bound_ids}
                        for device_id in sorted(known_ids)
                    ]
                })
        elif path in ("/", "/index.html", "/login", "/register", "/settings", "/questions", "/poems", "/records", "/devices"):
            self.send_static("index.html", "text/html; charset=utf-8")
        elif path == "/app.js":
            self.send_static("app.js", "text/javascript; charset=utf-8")
        elif path == "/styles.css":
            self.send_static("styles.css", "text/css; charset=utf-8")
        else:
            self.send_json(404, {"error": "页面不存在"})

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        if path.startswith("/api/v1/device/"):
            with AUTH.lock:
                self.handle_post()
        elif path in ("/api/v1/admin/devices/bind", "/api/v1/admin/devices/unbind"):
            with AUTH.lock:
                self.handle_post()
        else:
            self.handle_post()

    def handle_post(self) -> None:
        path = urlsplit(self.path).path
        if path == "/api/v1/device/pairing":
            if not self.same_origin():
                return
            try:
                payload = self.read_json()
                name = payload.get("deviceName", "")
                if not isinstance(name, str):
                    raise ValueError("设备名称无效")
                device = self.require_device(bound=False, enroll=True, name=name)
                if device:
                    self.send_json(200, AUTH.binding_status(device, refresh=True))
            except ValueError as error:
                self.send_json(400, {"error": str(error)})
            return
        if path.startswith("/api/v1/device/") and not self.require_device():
            return
        if path == "/api/v1/device/usage":
            if not self.same_origin():
                return
            try:
                payload = self.read_json()
                device_id, record_date, usage_millis = payload.get("deviceId"), payload.get("date"), payload.get("usageMillis")
                if not isinstance(device_id, str) or not 1 <= len(device_id) <= 80:
                    raise ValueError("无效的设备 ID")
                if not isinstance(record_date, str) or date.fromisoformat(record_date).isoformat() != record_date:
                    raise ValueError("日期格式必须为 YYYY-MM-DD")
                if isinstance(usage_millis, bool) or not isinstance(usage_millis, int) or not 0 <= usage_millis <= 172_800_000:
                    raise ValueError("使用时长无效")
                offset = payload.get("utcOffsetMinutes")
                if offset is not None and (isinstance(offset, bool) or not isinstance(offset, int) or not -840 <= offset <= 840):
                    raise ValueError("电视时区偏移无效")
                with self.store.lock:
                    state = self.store.load()
                    usage = state["dailyUsage"]
                    record = next((item for item in usage if item["deviceId"] == device_id and item["date"] == record_date), None)
                    now_ms = int(time.time() * 1000)
                    devices = state["devices"]
                    device = devices.get(device_id, {"name": device_id, "lastSeen": 0, "lastUsageMillis": 0, "lastUsageAt": 0})
                    reset = device.get("usageResets", {}).get(record_date)
                    expected_reset_id = reset["id"] if reset else ""
                    if payload.get("usageResetId", "") != expected_reset_id:
                        self.send_json(409, {"accepted": False, "error": "使用时长已重置，请先同步电视"})
                        return
                    name = payload.get("deviceName")
                    if isinstance(name, str) and 1 <= len(name.strip()) <= 100:
                        device["name"] = name.strip()
                    same_day = device.get("lastUsageDate", record_date) == record_date
                    previous_total = device.get("lastUsageMillis", 0) if same_day else 0
                    previous_at = device.get("lastUsageAt", 0) if same_day else 0
                    delta = max(0, usage_millis - previous_total)
                    elapsed = now_ms - previous_at if isinstance(previous_at, int) and previous_at else 0
                    if delta and elapsed > 0:
                        interval_ms = min(delta, elapsed, 120_000)
                        interval = {"start": now_ms - interval_ms, "end": now_ms}
                        if record is None:
                            record = {"deviceId": device_id, "date": record_date, "usageMillis": 0, "intervals": []}
                            usage.append(record)
                        intervals = record.setdefault("intervals", [])
                        record["intervals"] = merge_usage_intervals([*intervals, interval])
                    device["lastSeen"] = now_ms
                    device["lastUsageAt"] = now_ms
                    device["lastUsageMillis"] = usage_millis
                    device["lastUsageDate"] = record_date
                    if offset is not None:
                        device["utcOffsetMinutes"] = offset
                    devices[device_id] = device
                    if record is None:
                        record = {"deviceId": device_id, "date": record_date, "usageMillis": usage_millis}
                        usage.append(record)
                    else:
                        record["usageMillis"] = max(record["usageMillis"], usage_millis)
                    self.store.save(state)
                self.send_json(200, {"accepted": True, "usageMillis": record["usageMillis"]})
            except (ValueError, TypeError) as error:
                self.send_json(400, {"error": str(error) or "使用时长上报格式无效"})
            return
        if path == "/api/v1/device/challenges":
            if not self.same_origin():
                return
            try:
                payload = self.read_json()
                session_id = payload.get("sessionId")
                device_id = payload.get("deviceId")
                record_date = payload.get("date")
                started_at = payload.get("startedAt")
                ended_at = payload.get("endedAt")
                status = payload.get("status")
                answered_count = payload.get("answeredCount")
                correct_count = payload.get("correctCount")
                questions = payload.get("questions")
                interrupted_question_id = payload.get("interruptedQuestionId", "")
                trigger = payload.get("trigger", "unknown")
                resumed_from_session_id = payload.get("resumedFromSessionId", "")
                if trigger not in ("unknown", "startup", "interval", "lockout_retry", "reboot_resume"):
                    raise ValueError("弹出类型无效")
                if not isinstance(resumed_from_session_id, str) or len(resumed_from_session_id) > 80:
                    raise ValueError("恢复来源会话 ID 无效")
                if not isinstance(session_id, str) or not 1 <= len(session_id) <= 80:
                    raise ValueError("答题会话 ID 无效")
                if not isinstance(device_id, str) or not 1 <= len(device_id) <= 80:
                    raise ValueError("无效的设备 ID")
                if not isinstance(record_date, str) or date.fromisoformat(record_date).isoformat() != record_date:
                    raise ValueError("答题日期格式必须为 YYYY-MM-DD")
                if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in (started_at, ended_at)):
                    raise ValueError("答题会话时间无效")
                if status not in ("active", "answered", "timeout", "reboot", "daily_limit", "parent_skip"):
                    raise ValueError("答题会话状态无效")
                if status != "active" and ended_at < started_at:
                    raise ValueError("答题会话结束时间无效")
                if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > 5 for value in (answered_count, correct_count)):
                    raise ValueError("答题数量无效")
                if correct_count > answered_count:
                    raise ValueError("答对数量不能超过已答数量")
                if not isinstance(questions, list) or len(questions) != 5:
                    raise ValueError("答题会话必须包含五道题")
                normalized_questions = []
                for question in questions:
                    if not isinstance(question, dict):
                        raise ValueError("答题会话题目格式无效")
                    question_id, prompt = question.get("questionId"), question.get("prompt")
                    if not isinstance(question_id, str) or not 1 <= len(question_id) <= 80:
                        raise ValueError("无效的题目 ID")
                    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 240:
                        raise ValueError("题目内容无效")
                    normalized_questions.append({"questionId": question_id, "prompt": prompt})
                if not isinstance(interrupted_question_id, str) or len(interrupted_question_id) > 80:
                    raise ValueError("中断题目 ID 无效")
                record = {
                    "sessionId": session_id,
                    "deviceId": device_id,
                    "date": record_date,
                    "startedAt": started_at,
                    "endedAt": ended_at,
                    "status": status,
                    "answeredCount": answered_count,
                    "correctCount": correct_count,
                    "questions": normalized_questions,
                    "interruptedQuestionId": interrupted_question_id,
                    "trigger": trigger,
                    "resumedFromSessionId": resumed_from_session_id,
                }
                with self.store.lock:
                    state = self.store.load()
                    device_name = payload.get("deviceName")
                    if isinstance(device_name, str) and 1 <= len(device_name.strip()) <= 100:
                        device = state["devices"].get(device_id, {"name": device_id, "lastSeen": 0, "lastUsageMillis": 0, "lastUsageAt": 0})
                        device["name"] = device_name.strip()
                        state["devices"][device_id] = device
                    records = state["challengeRecords"]
                    existing = next((item for item in records if item["deviceId"] == device_id and item["sessionId"] == session_id), None)
                    if existing is None:
                        records.append(record)
                    elif existing["status"] == "active" or existing["status"] == status == "active":
                        existing.update(record)
                    self.store.save(state)
                self.send_json(200, {"accepted": True, "sessionId": session_id})
            except (ValueError, TypeError) as error:
                self.send_json(400, {"error": str(error) or "答题会话格式无效"})
            return
        if path == "/api/v1/device/answers":
            if not self.same_origin():
                return
            try:
                payload = self.read_json()
                if "attemptId" in payload:
                    attempt_id, device_id = payload.get("attemptId"), payload.get("deviceId")
                    record_date, answered_at = payload.get("date"), payload.get("answeredAt")
                    question_id, selected_answer = payload.get("questionId"), payload.get("selectedAnswer")
                    challenge_id = payload.get("challengeId", "")
                    answered = payload.get("answered", True)
                    if not isinstance(attempt_id, str) or not 1 <= len(attempt_id) <= 80:
                        raise ValueError("无效的答题记录 ID")
                    if not isinstance(device_id, str) or not 1 <= len(device_id) <= 80:
                        raise ValueError("无效的设备 ID")
                    if not isinstance(record_date, str) or date.fromisoformat(record_date).isoformat() != record_date:
                        raise ValueError("日期格式必须为 YYYY-MM-DD")
                    if isinstance(answered_at, bool) or not isinstance(answered_at, int) or answered_at < 0:
                        raise ValueError("答题时间无效")
                    if not isinstance(question_id, str) or not 1 <= len(question_id) <= 80:
                        raise ValueError("无效的题目 ID")
                    if not isinstance(challenge_id, str) or len(challenge_id) > 80:
                        raise ValueError("答题会话 ID 无效")
                    if not isinstance(answered, bool):
                        raise ValueError("作答状态无效")
                    if not isinstance(selected_answer, str) or (answered and not 1 <= len(selected_answer) <= 120) or (not answered and selected_answer != ""):
                        raise ValueError("无效的作答内容")
                    question_snapshot = payload.get("question")
                    if question_snapshot is not None and (
                        not isinstance(question_snapshot, dict)
                        or question_snapshot.get("id") != question_id
                    ):
                        raise ValueError("题目快照无效")
                    with self.store.lock:
                        state = self.store.load()
                        records = state["answerRecords"]
                        existing = next((item for item in records if item["deviceId"] == device_id and item["attemptId"] == attempt_id), None)
                        if existing is not None:
                            self.send_json(200, {"accepted": True, "correct": existing["correct"], "removed": False})
                            return
                        question = next((item for item in state["questions"] if item["id"] == question_id), None)
                        if question is None and isinstance(question_snapshot, dict):
                            question = validate_question(question_snapshot)
                        if question is None and answered:
                            self.send_json(404, {"error": "题目不存在或已被使用"})
                            return
                        if answered and selected_answer not in question["options"]:
                            raise ValueError("作答内容不属于该题选项")
                        correct = answered and selected_answer == question["answer"]
                        records.append({
                            "attemptId": attempt_id,
                            "deviceId": device_id,
                            "date": record_date,
                            "answeredAt": answered_at,
                            "questionId": question_id,
                            "challengeId": challenge_id,
                            "prompt": question["prompt"] if question else "题目已从题库移除",
                            "selectedAnswer": selected_answer,
                            "answered": answered,
                            "correctAnswer": question["answer"] if question else "",
                            "correct": bool(correct),
                            "question": question,
                        })
                        if correct:
                            state["questions"] = [item for item in state["questions"] if item["id"] != question_id]
                        self.store.save(state)
                    if correct:
                        self.store.ensure_refill()
                    self.send_json(200, {"accepted": True, "correct": correct, "removed": correct})
                    return
                question_id, answer = payload.get("questionId"), payload.get("answer")
                if not isinstance(question_id, str) or not 1 <= len(question_id) <= 80 or not isinstance(answer, str) or not 1 <= len(answer) <= 120:
                    raise ValueError("请输入有效的题目 ID 和答案")
                with self.store.lock:
                    state = self.store.load()
                    question = next((item for item in state["questions"] if item["id"] == question_id), None)
                    if question is not None and answer != question["answer"]:
                        self.send_json(409, {"error": "答案不正确，题目已保留"})
                        return
                    if question is not None:
                        state["questions"] = [item for item in state["questions"] if item["id"] != question_id]
                        self.store.save(state)
                self.store.ensure_refill()
                # Unknown IDs are already gone; acknowledge retries without deleting other questions.
                self.send_json(200, {"removed": question is not None})
            except ValueError as error:
                self.send_json(400, {"error": str(error)})
            return
        if not path.startswith("/api/v1/admin/"):
            self.send_json(404, {"error": "请求地址不存在"})
            return
        if not self.same_origin():
            return
        if path == "/api/v1/admin/register":
            try:
                payload = self.read_json()
                status, message = AUTH.register(payload.get("username"), payload.get("password"), payload.get("confirmPassword"), self.client_address[0])
                self.send_json(status, {"message" if status == 201 else "error": message})
            except ValueError as error:
                self.send_json(400, {"error": str(error)})
            return
        if path == "/api/v1/admin/login":
            try:
                payload = self.read_json()
                username, password = payload.get("username"), payload.get("password")
                if not isinstance(username, str) or not isinstance(password, str) or len(username) > 64:
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
            if path in ("/api/v1/admin/devices/preview", "/api/v1/admin/devices/bind"):
                if path.endswith("/preview"):
                    device = AUTH.pairing_device(payload.get("code"), self.account)
                    result = {"deviceId": device["device_id"], "name": device["name"]} if device else None
                else:
                    result = AUTH.bind(payload.get("code"), payload.get("deviceId"), self.account)
                self.send_json(200 if result else 400, result or {"error": "绑定码无效、已过期或尝试次数过多，请在电视刷新后重试"})
                return
            if path == "/api/v1/admin/devices/unbind":
                removed = AUTH.unbind(payload.get("deviceId"), self.account)
                self.send_json(200 if removed else 404, {"unbound": True} if removed else {"error": "未找到当前账号绑定的设备"})
                return
            priority_prefix = "/api/v1/admin/questions/"
            if path.startswith(priority_prefix) and path.endswith("/priority"):
                question_id = unquote(path[len(priority_prefix):-len("/priority")])
                priority = payload.get("priority")
                if not isinstance(priority, bool):
                    raise ValueError("优先选项必须为布尔值")
                with self.store.lock:
                    state = self.store.load()
                    question = next((item for item in state["questions"] if item["id"] == question_id), None)
                    if question is None:
                        self.send_json(404, {"error": "题目不存在或已被答对移出题库"})
                        return
                    question["priority"] = priority
                    self.store.save(state)
                self.send_json(200, {"question": question})
                return
            if path == "/api/v1/admin/password/change":
                current_password = payload.get("currentPassword")
                new_password = payload.get("newPassword")
                confirm_password = payload.get("confirmPassword")
                if any(not isinstance(value, str) for value in (current_password, new_password, confirm_password)):
                    raise ValueError("请输入有效的当前密码和新密码")
                if new_password != confirm_password:
                    raise ValueError("两次输入的新密码不一致")
                status, message = AUTH.change_password(self.session_id(), current_password, new_password)
                self.send_json(status, {"changed": status == 200, **({} if status == 200 else {"error": message})})
                return
            if path == "/api/v1/admin/answers/restore":
                attempt_id = payload.get("attemptId")
                if not isinstance(attempt_id, str) or not 1 <= len(attempt_id) <= 80:
                    raise ValueError("无效的答题记录 ID")
                with self.store.lock:
                    state = self.store.load()
                    record = next((item for item in state["answerRecords"] if item.get("attemptId") == attempt_id), None)
                    if record is None or not record.get("correct"):
                        self.send_json(404, {"error": "找不到答对的题目记录"})
                        return
                    questions = state["questions"]
                    if any(item["id"] == record.get("questionId") for item in questions):
                        self.send_json(200, {"restored": False, "inQuestionBank": True})
                        return
                    snapshot = record.get("question")
                    if not isinstance(snapshot, dict):
                        snapshot = next(
                            (item for item in json.loads(SEED_FILE.read_text(encoding="utf-8")) if item.get("id") == record.get("questionId")),
                            None,
                        )
                    if not isinstance(snapshot, dict):
                        self.send_json(409, {"error": "该答题记录没有完整题目，无法恢复"})
                        return
                    question = validate_question(snapshot)
                    if question["id"] != record.get("questionId"):
                        raise ValueError("题目快照与答题记录不匹配")
                    questions.append(question)
                    self.store.save(state)
                self.send_json(200, {"restored": True, "inQuestionBank": False})
                return
            if path == "/api/v1/admin/questions/generate":
                category = payload.get("category", "古诗词")
                title, count = payload.get("title"), payload.get("count", 5)
                word = payload.get("word")
                difficulty = payload.get("difficulty", "中")
                if category not in ("古诗词", "英语", *RANDOM_QUESTION_CATEGORIES):
                    raise ValueError("类别需选择古诗词、英语、数学、地理历史或科学")
                if category == "古诗词" and (not isinstance(title, str) or not 1 <= len(title.strip()) <= 80):
                    raise ValueError("请输入 1–80 个字符的诗词名称，可附作者")
                if category == "英语" and (
                    not isinstance(word, str)
                    or len(word.strip()) > 80
                    or not ENGLISH_WORD_PATTERN.fullmatch(word.strip())
                ):
                    raise ValueError("请输入一个英文单词")
                if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 10:
                    raise ValueError("出题数量需为 1–10 道")
                if not isinstance(difficulty, str) or difficulty not in ("低", "中", "高"):
                    raise ValueError("难度需选择低、中或高")
                # Generate outside the state lock so TV sync and admin writes can continue.
                if category == "古诗词":
                    generated_result = generate_poem_questions(title.strip(), count, difficulty)
                elif category == "英语":
                    generated_result = generate_english_questions(word.strip(), count, difficulty)
                else:
                    generated_result = {
                        "title": category,
                        "questions": generate_category_questions(category, count, difficulty),
                    }
                try:
                    candidates = []
                    for item in generated_result["questions"]:
                        if not isinstance(item, dict):
                            raise ValueError("invalid question")
                        question = {**item, "category": category, "difficulty": difficulty}
                        prompt = question.get("prompt")
                        if category == "古诗词" and isinstance(prompt, str) and generated_result["title"] not in prompt:
                            question["prompt"] = f"《{generated_result['title']}》：{prompt}"
                        if category == "英语" and isinstance(prompt, str) and word.casefold() not in prompt.casefold():
                            question["prompt"] = f"单词“{word.strip()}”：{prompt}"
                        candidates.append(question)
                    generated = validate_question_batch(candidates)
                    if len({question["prompt"] for question in generated}) != len(generated):
                        raise ValueError("duplicate generated questions")
                except ValueError:
                    raise DeepSeekError("生成题目的选项、答案或题干不符合要求，请重新生成") from None
                self.send_json(200, {**generated_result, "category": category, "questions": generated})
                return
            if path == "/api/v1/admin/questions/batch":
                candidates = validate_question_batch(payload.get("questions"))
                with self.store.lock:
                    state = self.store.load()
                    existing = {question["prompt"] for question in state["questions"]}
                    added = 0
                    for question in candidates:
                        if question["prompt"] not in existing:
                            state["questions"].append(question)
                            existing.add(question["prompt"])
                            added += 1
                    if added:
                        self.store.save(state)
                self.send_json(200, {"added": added, "skipped": len(candidates) - added})
                return
            if path == "/api/v1/admin/password/reset":
                command = self.store.queue_password_reset()
                self.send_json(202, {"queued": True, "id": command["id"]})
                return
            if path == "/api/v1/admin/usage/reset":
                command = self.store.queue_usage_reset(payload.get("deviceId"))
                self.send_json(202, {"queued": True, **command})
                return
            with self.store.lock:
                state = self.store.load()
                if path == "/api/v1/admin/settings":
                    state["settings"] = validate_settings(payload)
                    self.store.save(state)
                    self.send_json(200, {"settings": state["settings"]})
                elif path == "/api/v1/admin/questions":
                    question = validate_question(payload, require_id=False)
                    questions = state["questions"]
                    assert isinstance(questions, list)
                    questions.append(question)
                    self.store.save(state)
                    self.send_json(201, question)
                else:
                    self.send_json(404, {"error": "请求地址不存在"})
        except DeepSeekError as error:
            self.send_json(error.status, {"error": str(error)})
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": str(error)})

    def do_DELETE(self) -> None:
        path = urlsplit(self.path).path
        prefix = "/api/v1/admin/questions/"
        if not path.startswith(prefix):
            self.send_json(404, {"error": "请求地址不存在"})
            return
        if not self.same_origin() or not self.require_admin():
            return
        question_id = path[len(prefix):]
        with self.store.lock:
            state = self.store.load()
            questions = state["questions"]
            assert isinstance(questions, list)
            remaining = [question for question in questions if question["id"] != question_id]
            if len(remaining) == len(questions):
                self.send_json(404, {"error": "题目不存在"})
                return
            state["questions"] = remaining
            self.store.save(state)
        self.store.ensure_refill()
        self.send_json(200, {"deleted": question_id, "count": len(remaining)})

    def read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("请求内容为空或超出大小限制")
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("请求内容必须是 JSON 对象")
        if hasattr(self, "device_id") and payload.get("deviceId", self.device_id) != self.device_id:
            raise ValueError("设备 ID 与凭证不匹配")
        return payload

    def require_device(self, *, bound: bool = True, enroll: bool = False, name: str = "") -> dict[str, object] | None:
        authorization = self.headers.get("Authorization", "")
        token = authorization.removeprefix("Bearer ") if authorization.startswith("Bearer ") else ""
        try:
            device = AUTH.device(self.headers.get("X-Device-Id", ""), token, enroll=enroll, name=name)
        except PermissionError as error:
            self.send_json(409, {"error": str(error), "errorCode": "device_already_bound"})
            return None
        if device is None:
            self.send_json(401, {"error": "设备凭证无效，请在电视上重新绑定"})
            return None
        if bound:
            if device["account_id"] is None or self.headers.get("X-Binding-Id", "") != device["binding_id"]:
                self.send_json(403, {"error": "设备尚未绑定账号或绑定已变更"})
                return None
            self.store = STORE.for_account(device["account_id"])
            self.device_id = device["device_id"]
        return device

    def require_admin(self) -> bool:
        account_id = AUTH.account_id(self.session_id())
        if account_id is None:
            self.send_json(401, {"error": "请先登录管理后台"})
            return False
        self.account = account_id
        self.store = STORE.for_account(account_id)
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
        password = getpass.getpass("密码: ")
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
