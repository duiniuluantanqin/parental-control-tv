from __future__ import annotations

import json
import os
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
        key_patch = patch.dict("os.environ", {"DEEPSEEK_API_KEY": ""})
        key_patch.start()
        self.addCleanup(key_patch.stop)
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
        if server.STORE.refill_thread is not None:
            server.STORE.refill_thread.join(timeout=2)
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
        self.assertEqual(state["version"], server.BACKEND_VERSION)
        self.assertEqual(len(state["questions"]), 50)
        self.assertEqual(state["settings"], server.DEFAULT_SETTINGS)
        self.assertIsNone(state["passwordReset"])

    def test_password_reset_command_requires_admin_and_survives_device_sync(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/password/reset", method="POST", authenticated=False, body={},
        )
        self.assertEqual(status, 401)

        status, queued = self.request("/api/v1/admin/password/reset", method="POST", body={})
        self.assertEqual(status, 202)
        self.assertTrue(queued["queued"])
        self.assertRegex(queued["id"], r"^[0-9a-f]{32}$")

        status, device_state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(status, 200)
        self.assertEqual(device_state["passwordReset"], {"id": queued["id"]})

        state = server.STORE.load()
        state["settings"]["syncIntervalMinutes"] = 7
        server.STORE.save(state)
        self.assertEqual(server.STORE.snapshot()["passwordReset"], {"id": queued["id"]})

        _, replaced = self.request("/api/v1/admin/password/reset", method="POST", body={})
        self.assertNotEqual(replaced["id"], queued["id"])
        self.assertEqual(server.STORE.snapshot()["passwordReset"], {"id": replaced["id"]})

    def test_existing_settings_without_disabled_windows_load_with_empty_windows(self) -> None:
        state = server.STORE.load()
        state["settings"].pop("disabledTimeWindows")
        server.STORE.save(state)
        self.assertEqual(server.STORE.load()["settings"]["disabledTimeWindows"], server.DEFAULT_SETTINGS["disabledTimeWindows"])

    def random_batch(self, count, difficulty, exclude_prompts):
        categories = ["古诗词", "数学", "科学", "地理历史"]
        return [
            {"category": categories[index % 4], "prompt": f"综合测试题 {len(exclude_prompts) + index}",
             "options": ["甲", "乙", "丙", "丁"], "answer": "甲"}
            for index in range(count)
        ]

    def empty_bank(self):
        state = server.STORE.load()
        state["questions"] = []
        server.STORE.save(state)

    def test_device_only_removes_correct_answer_and_acknowledges_retries(self) -> None:
        question = server.STORE.load()["questions"][0]
        for payload, expected in (({}, 400), ({"questionId": [], "answer": "甲"}, 400),
                                  ({"questionId": question["id"], "answer": "错误答案"}, 409)):
            status, _ = self.request("/api/v1/device/answers", method="POST", body=payload, authenticated=False)
            self.assertEqual(status, expected)
        self.assertEqual(len(server.STORE.load()["questions"]), 50)
        payload = {"questionId": question["id"], "answer": question["answer"]}
        for removed in (True, False):
            status, result = self.request("/api/v1/device/answers", method="POST", body=payload, authenticated=False)
            self.assertEqual(status, 200)
            self.assertEqual(result, {"removed": removed})
            state = server.StateStore(server.STORE.path).load()
            self.assertEqual(len(state["questions"]), 49)
            self.assertNotIn(question["id"], [item["id"] for item in state["questions"]])

    def test_last_correct_answer_refills_fifty_mixed_questions(self) -> None:
        state = server.STORE.load()
        question = state["questions"][0]
        state["questions"] = [question]
        server.STORE.save(state)
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=self.random_batch) as generate:
            status, _ = self.request("/api/v1/device/answers", method="POST", authenticated=False,
                                     body={"questionId": question["id"], "answer": question["answer"]})
            self.assertEqual(status, 200)
            server.STORE.refill_thread.join(timeout=2)
            self.assertEqual(generate.call_count, 5)
            self.assertEqual([len(call.args[2]) for call in generate.call_args_list], [0, 10, 20, 30, 40])
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 50)
        self.assertEqual(len({item["prompt"] for item in state["questions"]}), 50)
        self.assertEqual({item["category"] for item in state["questions"]}, {"古诗词", "数学", "科学", "地理历史"})
        self.assertEqual({item["difficulty"] for item in state["questions"]}, {"中"})
        self.assertEqual(state["settings"]["weeklyDailyLimitMinutes"]["1"], 60)
        self.assertNotIn(question["id"], [item["id"] for item in state["questions"]])

    def test_empty_bank_stays_empty_without_key_and_admin_can_delete_last_question(self) -> None:
        state = server.STORE.load()
        question = state["questions"][0]
        state["questions"] = [question]
        server.STORE.save(state)
        with patch("server.generate_random_questions") as generate:
            status, _ = self.request(f"/api/v1/admin/questions/{question['id']}", method="DELETE")
            self.assertEqual(status, 200)
            for endpoint in ("/api/v1/device/state", "/api/v1/admin/state"):
                status, state = self.request(endpoint)
                self.assertEqual(status, 200)
                self.assertEqual(state["questions"], [])
                self.assertIn("DeepSeek Key", state["refill"]["error"])
            generate.assert_not_called()

    def test_concurrent_empty_syncs_start_only_one_nonblocking_refill(self) -> None:
        self.empty_bank()
        release = threading.Event()
        entered = threading.Event()

        def slow_batch(*args):
            entered.set()
            self.assertTrue(release.wait(timeout=5))
            return self.random_batch(*args)

        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=slow_batch) as generate:
            try:
                with ThreadPoolExecutor(max_workers=6) as executor:
                    results = list(executor.map(lambda _: self.request("/api/v1/device/state", authenticated=False), range(6)))
                self.assertTrue(entered.is_set())
                self.assertEqual(generate.call_count, 1)
                self.assertTrue(all(status == 200 and state["questions"] == [] and state["refill"]["running"] for status, state in results))
            finally:
                release.set()
                server.STORE.refill_thread.join(timeout=2)
            self.assertEqual(generate.call_count, 5)
        self.assertEqual(len(server.STORE.load()["questions"]), 50)

    def test_failed_or_duplicate_refill_never_partially_saves_and_waits_before_retry(self) -> None:
        from deepseek import DeepSeekError
        for failure in (DeepSeekError("模型暂不可用"), self.random_batch(10, "中", [])):
            server.STORE = server.StateStore(server.STORE.path)
            self.empty_bank()
            first = self.random_batch(10, "中", [])
            with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=[first, failure]) as generate:
                self.request("/api/v1/device/state", authenticated=False)
                server.STORE.refill_thread.join(timeout=2)
                _, state = self.request("/api/v1/device/state", authenticated=False)
                self.assertEqual(state["questions"], [])
                self.assertFalse(state["refill"]["running"])
                self.assertIsNotNone(state["refill"]["error"])
                self.assertEqual(generate.call_count, 2)
            server.STORE.next_refill_attempt = 0
            with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=self.random_batch):
                self.request("/api/v1/device/state", authenticated=False)
                server.STORE.refill_thread.join(timeout=2)
                self.assertEqual(len(server.STORE.load()["questions"]), 50)

    def test_refill_does_not_overwrite_questions_added_by_parent(self) -> None:
        self.empty_bank()
        release = threading.Event()
        entered = threading.Event()

        def slow_batch(*args):
            entered.set()
            self.assertTrue(release.wait(timeout=5))
            return self.random_batch(*args)

        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=slow_batch):
            try:
                self.request("/api/v1/device/state", authenticated=False)
                self.assertTrue(entered.wait(timeout=2))
                status, added = self.request("/api/v1/admin/questions", method="POST", body=self.random_batch(1, "中", ["占位"])[0])
                self.assertEqual(status, 201)
            finally:
                release.set()
                server.STORE.refill_thread.join(timeout=2)
        self.assertEqual(server.STORE.load()["questions"], [added])

    def test_successful_refill_can_start_again_as_soon_as_bank_is_empty(self) -> None:
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("server.generate_random_questions", side_effect=self.random_batch) as generate:
            for _ in range(2):
                self.empty_bank()
                self.request("/api/v1/device/state", authenticated=False)
                server.STORE.refill_thread.join(timeout=2)
                self.assertEqual(len(server.STORE.load()["questions"]), 50)
            self.assertEqual(generate.call_count, 10)

    def test_admin_login_is_required(self) -> None:
        status, body = self.request("/api/v1/admin/state", authenticated=False)
        self.assertEqual(status, 401)
        self.assertIn("error", body)

    def test_unreadable_admin_file_returns_json_error(self) -> None:
        with patch("server.Path.read_text", side_effect=PermissionError("admin.json unreadable")):
            status, body = self.request(
                "/api/v1/admin/login", method="POST",
                body={"username": "admin", "password": "test-password-123"},
            )
            self.assertEqual(status, 503)
            self.assertIn("error", body)
            status, _ = self.request("/api/v1/admin/state")
            self.assertEqual(status, 401)

    def test_wrong_password_and_old_bearer_are_rejected(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "wrong-password"},
        )
        self.assertEqual(status, 401)
        status, _ = self.request(
            "/api/v1/admin/settings", method="POST", authenticated=False,
            body={}, headers={"Authorization": "Bearer old-token"},
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
            "/api/v1/admin/settings", method="POST", body={},
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
            "weeklyDailyLimitMinutes": {str(day): 75 + day for day in range(1, 8)},
            "usageTimeWindows": {
                "1": [{"start": "08:00", "end": "20:00"}],
                "2": [{"start": "22:00", "end": "02:00"}],
                "3": [], "4": [], "5": [], "6": [], "7": [],
            },
            "disabledTimeWindows": {
                "1": [{"start": "12:00", "end": "13:00"}],
                "2": [{"start": "01:00", "end": "01:30"}],
                "3": [], "4": [], "5": [], "6": [], "7": [],
            },
            "questionIntervalMinutes": 12,
            "wrongLockMinutes": 10,
            "syncIntervalMinutes": 3,
        }
        status, body = self.request("/api/v1/admin/settings", method="POST", body=settings)
        self.assertEqual(status, 200)
        self.assertEqual(body["settings"], settings)
        _, device_state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(device_state["settings"], settings)

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
            body={"weeklyDailyLimitMinutes": {**server.DEFAULT_SETTINGS["weeklyDailyLimitMinutes"], "1": -1}},
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "/api/v1/admin/settings",
            method="POST",
            body={"usageTimeWindows": {**server.DEFAULT_SETTINGS["usageTimeWindows"], "1": [{"start": "08:00", "end": "08:00"}]}},
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "/api/v1/admin/settings",
            method="POST",
            body={"disabledTimeWindows": {**server.DEFAULT_SETTINGS["disabledTimeWindows"], "1": [{"start": "08:00", "end": "08:00"}]}},
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

    def test_manual_questions_keep_selected_difficulty(self) -> None:
        for difficulty in ("低", "中", "高"):
            question = {
                "prompt": f"手动出题难度测试：{difficulty}", "difficulty": difficulty,
                "options": ["甲", "乙", "丙", "丁"], "answer": "甲",
            }
            status, created = self.request("/api/v1/admin/questions", method="POST", body=question)
            self.assertEqual(status, 201)
            self.assertEqual(created["difficulty"], difficulty)
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual([question["difficulty"] for question in state["questions"][-3:]], ["低", "中", "高"])

    def test_legacy_difficulty_is_normalized_on_read_without_rewriting_bank(self) -> None:
        state = server.STORE.load()
        for question, difficulty in zip(state["questions"], ("小学", "初中", "高中")):
            question["difficulty"] = difficulty
        server.STORE.save(state)
        original = server.STORE.path.read_bytes()
        for endpoint in ("/api/v1/admin/state", "/api/v1/device/state"):
            status, loaded = self.request(endpoint)
            self.assertEqual(status, 200)
            self.assertEqual([question["difficulty"] for question in loaded["questions"][:3]], ["低", "中", "高"])
        self.assertEqual(server.STORE.path.read_bytes(), original)

    def test_ai_generation_requires_login_and_valid_input(self) -> None:
        with patch("server.generate_poem_questions") as generate:
            status, _ = self.request(
                "/api/v1/admin/questions/generate", method="POST", authenticated=False,
                body={"title": "静夜思", "count": 3},
            )
            self.assertEqual(status, 401)
            invalid = [
                {"title": "", "count": 3}, {"title": "静夜思", "count": True}, {"title": "静夜思", "count": 11},
                *({"title": "静夜思", "difficulty": value} for value in ("", "初中", None, 1, [])),
            ]
            for payload in invalid:
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
        with patch("server.generate_poem_questions", return_value=generated) as generate:
            status, preview = self.request("/api/v1/admin/questions/generate", method="POST", body={"title": "静夜思", "count": 2})
        self.assertEqual(status, 200)
        generate.assert_called_once_with("静夜思", 2, "中")
        self.assertEqual(preview["questions"][0]["category"], "古诗词")
        self.assertEqual(preview["questions"][0]["difficulty"], "中")
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

    def test_ai_difficulty_reaches_provider_and_survives_import(self) -> None:
        for difficulty in ("低", "中", "高"):
            generated = {
                "title": "静夜思", "author": "李白",
                "questions": [{
                    "prompt": f"《静夜思》难度测试：{difficulty}", "difficulty": "小学",
                    "options": ["甲", "乙", "丙", "丁"], "answer": "甲",
                }],
            }
            with patch("server.generate_poem_questions", return_value=generated) as generate:
                status, preview = self.request(
                    "/api/v1/admin/questions/generate", method="POST",
                    body={"title": "静夜思", "count": 1, "difficulty": difficulty},
                )
            self.assertEqual(status, 200)
            generate.assert_called_once_with("静夜思", 1, difficulty)
            self.assertEqual(preview["questions"][0]["difficulty"], difficulty)
            status, imported = self.request("/api/v1/admin/questions/batch", method="POST", body={"questions": preview["questions"]})
            self.assertEqual(status, 200)
            self.assertEqual(imported["added"], 1)
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual([question["difficulty"] for question in state["questions"][-3:]], ["低", "中", "高"])

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


class AdminCredentialFileTests(unittest.TestCase):
    @unittest.skipUnless(hasattr(os, "geteuid") and os.geteuid() == 0, "requires POSIX root")
    def test_root_setup_keeps_credentials_owned_by_data_directory_user(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "data"
            data.mkdir()
            os.chown(data, 65534, 65534)
            auth = server.AdminAuth(data / "admin.json")
            auth.set_password("admin", "test-password-123")
            self.assertEqual(auth.path.stat().st_uid, 65534)
            self.assertEqual(auth.path.stat().st_gid, 65534)
            self.assertEqual(auth.path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(auth.login("admin", "test-password-123")[0], 200)


if __name__ == "__main__":
    unittest.main()
