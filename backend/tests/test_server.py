from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen


BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
import server  # noqa: E402


class AdminApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.previous_store = server.STORE
        self.previous_auth = server.AUTH
        server.STORE = server.StateStore(Path(self.temp_dir.name) / "state.json")
        server.AUTH = server.AdminAuth(Path(self.temp_dir.name) / "admin.json")
        server.AUTH.set_password("admin", "test-password-123")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.RequestHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"
        self.cookies = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))
        status, _ = self.request(
            "/api/v1/admin/login", method="POST",
            body={"username": "admin", "password": "test-password-123"},
        )
        self.assertEqual(status, 200)

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        server.STORE = self.previous_store
        server.AUTH = self.previous_auth
        self.temp_dir.cleanup()

    def request(self, path: str, *, method: str = "GET", body: object | None = None, authenticated: bool = True, headers=None):
        headers = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(f"{self.base_url}{path}", data=data, headers=headers, method=method)
        open_request = self.opener.open if authenticated else urlopen
        try:
            with open_request(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read().decode("utf-8"))

    def test_device_state_starts_with_fifty_questions_and_default_rules(self) -> None:
        status, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(status, 200)
        self.assertEqual(len(state["questions"]), 50)
        self.assertEqual(state["settings"], server.DEFAULT_SETTINGS)

    def test_admin_login_is_required(self) -> None:
        status, body = self.request("/api/v1/admin/state", authenticated=False)
        self.assertEqual(status, 401)
        self.assertIn("error", body)

    def test_wrong_password_and_old_bearer_are_rejected(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "wrong-password"},
        )
        self.assertEqual(status, 401)
        status, _ = self.request(
            "/api/v1/admin/settings", method="POST", authenticated=False,
            body={"dailyLimitMinutes": 100}, headers={"Authorization": "Bearer old-token"},
        )
        self.assertEqual(status, 401)

    def test_session_cookie_is_httponly_and_logout_invalidates_it(self) -> None:
        cookie = next(iter(self.cookies))
        self.assertTrue(cookie.has_nonstandard_attr("HttpOnly"))
        self.assertEqual(cookie.get_nonstandard_attr("SameSite"), "Strict")
        saved_cookie = f"{cookie.name}={cookie.value}"
        status, _ = self.request("/api/v1/admin/state")
        self.assertEqual(status, 200)
        status, _ = self.request("/api/v1/admin/logout", method="POST")
        self.assertEqual(status, 200)
        status, _ = self.request("/api/v1/admin/state", authenticated=False, headers={"Cookie": saved_cookie})
        self.assertEqual(status, 401)

    def test_https_proxy_login_sets_secure_cookie(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/login", method="POST",
            body={"username": "admin", "password": "test-password-123"},
            headers={"X-Forwarded-Proto": "https"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(next(iter(self.cookies)).secure)

    def test_session_expires_and_password_reset_revokes_sessions(self) -> None:
        with patch("server.time.monotonic", return_value=10**12):
            status, _ = self.request("/api/v1/admin/state")
        self.assertEqual(status, 401)
        self.request("/api/v1/admin/login", method="POST", body={"username": "admin", "password": "test-password-123"})
        # A separate CLI process resets the password in the same credentials file.
        server.AdminAuth(server.AUTH.path).set_password("admin", "replacement-password")
        status, _ = self.request("/api/v1/admin/state")
        self.assertEqual(status, 401)
        contents = server.AUTH.path.read_text(encoding="utf-8")
        self.assertNotIn("replacement-password", contents)

    def test_cross_origin_writes_are_rejected(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/settings", method="POST", body={"dailyLimitMinutes": 100},
            headers={"Origin": "https://another-site.example"},
        )
        self.assertEqual(status, 403)

    def test_repeated_failed_logins_are_throttled(self) -> None:
        for _ in range(5):
            status, _ = self.request(
                "/api/v1/admin/login", method="POST", authenticated=False,
                body={"username": "admin", "password": "wrong-password"},
            )
            self.assertEqual(status, 401)
        status, _ = self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "test-password-123"},
        )
        self.assertEqual(status, 429)
        with patch("server.time.monotonic", return_value=10**12):
            status, _ = self.request(
                "/api/v1/admin/login", method="POST", authenticated=False,
                body={"username": "admin", "password": "test-password-123"},
            )
        self.assertEqual(status, 200)

    def test_device_sync_works_before_admin_is_configured(self) -> None:
        server.AUTH = server.AdminAuth(Path(self.temp_dir.name) / "not-configured.json")
        status, _ = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(status, 200)
        status, _ = self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "test-password-123"},
        )
        self.assertEqual(status, 503)

    def test_admin_can_update_settings_and_add_questions(self) -> None:
        settings = {
            "dailyLimitMinutes": 75,
            "questionIntervalMinutes": 12,
            "wrongLockMinutes": 10,
            "syncIntervalMinutes": 3,
        }
        status, body = self.request("/api/v1/admin/settings", method="POST", body=settings)
        self.assertEqual(status, 200)
        self.assertEqual(body["settings"], settings)

        question = {
            "category": "数学",
            "difficulty": "初中",
            "prompt": "若 3x = 12，则 x 等于多少？",
            "options": ["2", "3", "4", "5"],
            "answer": "4",
        }
        status, created = self.request("/api/v1/admin/questions", method="POST", body=question)
        self.assertEqual(status, 201)
        self.assertTrue(created["id"].startswith("q-"))
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 51)
        self.assertEqual(state["questions"][-1]["prompt"], question["prompt"])

    def test_invalid_settings_and_question_shapes_are_rejected(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/settings",
            method="POST",
            body={"dailyLimitMinutes": 0},
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "/api/v1/admin/settings",
            method="POST",
            body={"syncIntervalMinutes": 0},
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "/api/v1/admin/questions",
            method="POST",
            body={"prompt": "缺少选项", "options": ["A"], "answer": "A"},
        )
        self.assertEqual(status, 400)

    def test_ai_generation_requires_login_and_valid_input(self) -> None:
        with patch("server.generate_poem_questions") as generate:
            status, _ = self.request(
                "/api/v1/admin/questions/generate", method="POST", authenticated=False,
                body={"title": "静夜思", "count": 3},
            )
            self.assertEqual(status, 401)
            for payload in ({"title": "", "count": 3}, {"title": "静夜思", "count": True}, {"title": "静夜思", "count": 11}):
                status, _ = self.request("/api/v1/admin/questions/generate", method="POST", body=payload)
                self.assertEqual(status, 400)
            generate.assert_not_called()

    def test_ai_preview_does_not_save_and_selected_batch_is_imported_once(self) -> None:
        generated = {
            "title": "静夜思", "author": "李白",
            "questions": [
                {"prompt": "诗中哪一项表达了作者的感情？", "options": ["思乡", "喜悦", "愤怒", "赞美"], "answer": "思乡"},
                {"prompt": "《静夜思》的作者是哪位诗人？", "options": ["李白", "杜甫", "王维", "白居易"], "answer": "李白"},
            ],
        }
        with patch("server.generate_poem_questions", return_value=generated):
            status, preview = self.request("/api/v1/admin/questions/generate", method="POST", body={"title": "静夜思", "count": 2})
        self.assertEqual(status, 200)
        self.assertEqual(preview["questions"][0]["category"], "古诗词")
        self.assertIn("静夜思", preview["questions"][0]["prompt"])
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 50)
        selected = {"questions": preview["questions"][:1]}
        status, imported = self.request("/api/v1/admin/questions/batch", method="POST", body=selected)
        self.assertEqual(status, 200)
        self.assertEqual(imported["added"], 1)
        status, retried = self.request("/api/v1/admin/questions/batch", method="POST", body=selected)
        self.assertEqual(status, 200)
        self.assertEqual(retried["added"], 0)
        self.assertEqual(retried["skipped"], 1)
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 51)

    def test_invalid_ai_questions_and_batches_do_not_partially_save(self) -> None:
        question = {"prompt": "《静夜思》测试题", "options": ["甲", "乙", "丙", "丁"], "answer": "甲"}
        invalid = {**question, "answer": "不在选项中"}
        for questions in ([question, invalid], [question, question]):
            with patch("server.generate_poem_questions", return_value={"title": "静夜思", "author": "李白", "questions": questions}):
                status, _ = self.request("/api/v1/admin/questions/generate", method="POST", body={"title": "静夜思", "count": 2})
            self.assertEqual(status, 502)
        for payload in ({"questions": []}, {"questions": [question, invalid]}):
            status, _ = self.request("/api/v1/admin/questions/batch", method="POST", body=payload)
            self.assertEqual(status, 400)
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 50)

    def test_ai_provider_errors_reach_admin_without_leaking_key(self) -> None:
        from deepseek import DeepSeekError
        with patch("server.generate_poem_questions", side_effect=DeepSeekError("AI 出题尚未配置", status=503)):
            status, body = self.request("/api/v1/admin/questions/generate", method="POST", body={"title": "静夜思", "count": 3})
        self.assertEqual(status, 503)
        self.assertIn("error", body)

    def test_concurrent_question_writes_are_not_lost(self) -> None:
        def add(index: int) -> int:
            question = {
                "category": "数学",
                "difficulty": "初中",
                "prompt": f"并发测试题 {index}",
                "options": ["A", "B", "C", "D"],
                "answer": "A",
            }
            status, _ = self.request("/api/v1/admin/questions", method="POST", body=question)
            return status

        with ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(add, range(12)))
        self.assertEqual(statuses, [201] * 12)
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 62)


if __name__ == "__main__":
    unittest.main()
