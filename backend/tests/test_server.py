from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
import server  # noqa: E402


class AdminApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.previous_store = server.STORE
        self.previous_token = server.ADMIN_TOKEN
        server.STORE = server.StateStore(Path(self.temp_dir.name) / "state.json")
        server.ADMIN_TOKEN = "test-admin-token-that-is-long-enough"
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.RequestHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        server.STORE = self.previous_store
        server.ADMIN_TOKEN = self.previous_token
        self.temp_dir.cleanup()

    def request(self, path: str, *, method: str = "GET", body: object | None = None, token: bool = True):
        headers = {}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {server.ADMIN_TOKEN}"
        request = Request(f"{self.base_url}{path}", data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            return error.code, json.loads(error.read().decode("utf-8"))

    def test_device_state_starts_with_fifty_questions_and_default_rules(self) -> None:
        status, state = self.request("/api/v1/device/state", token=False)
        self.assertEqual(status, 200)
        self.assertEqual(len(state["questions"]), 50)
        self.assertEqual(state["settings"], server.DEFAULT_SETTINGS)

    def test_admin_token_is_required(self) -> None:
        status, body = self.request("/api/v1/admin/state", token=False)
        self.assertEqual(status, 401)
        self.assertIn("error", body)

    def test_admin_can_update_settings_and_add_questions(self) -> None:
        settings = {
            "dailyLimitMinutes": 75,
            "questionIntervalMinutes": 12,
            "wrongLockMinutes": 10,
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
        _, state = self.request("/api/v1/device/state", token=False)
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
            "/api/v1/admin/questions",
            method="POST",
            body={"prompt": "缺少选项", "options": ["A"], "answer": "A"},
        )
        self.assertEqual(status, 400)

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
        _, state = self.request("/api/v1/device/state", token=False)
        self.assertEqual(len(state["questions"]), 62)


if __name__ == "__main__":
    unittest.main()
