from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from contextlib import closing
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
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
        enrichment_patch = patch.object(server.StateStore, "ensure_poem_enrichment")
        enrichment_patch.start()
        self.addCleanup(enrichment_patch.stop)
        self.temp_dir = tempfile.TemporaryDirectory()
        self.previous_store = server.STORE
        self.previous_auth = server.AUTH
        database_path = Path(self.temp_dir.name) / "state.sqlite3"
        server.STORE = server.StateStore(database_path)
        server.AUTH = server.AdminAuth(database_path)
        server.STORE.snapshot()
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
        self.device_headers("tv-01")

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        if server.STORE.refill_thread is not None:
            server.STORE.refill_thread.join(timeout=2)
        if server.STORE.poem_thread is not None:
            server.STORE.poem_thread.join(timeout=2)
        server.STORE = self.previous_store
        server.AUTH = self.previous_auth
        self.temp_dir.cleanup()

    def request(self, path: str, *, method: str = "GET", body: object | None = None, authenticated: bool = True, headers=None):
        headers = dict(headers or {})
        if path.startswith("/api/v1/device/"):
            device_id = (body or {}).get("deviceId") or parse_qs(urlsplit(path).query).get("deviceId", ["tv-01"])[0]
            headers.update(self.device_headers(device_id))
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

    def device_headers(self, device_id):
        token = "test-device-credential-" + "a" * 32
        device = server.AUTH.device(device_id, token, enroll=True, name=device_id)
        if device["account_id"] is None:
            with closing(server.AUTH.connect()) as connection, connection:
                connection.execute("UPDATE device_bindings SET account_id = 1, binding_id = 'test-binding' WHERE device_id = ?", (device_id,))
        return {"X-Device-Id": device_id, "Authorization": "Bearer " + token, "X-Binding-Id": "test-binding"}

    def test_device_state_starts_with_fifty_questions_and_default_rules(self) -> None:
        status, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(status, 200)
        self.assertEqual(server.BACKEND_VERSION, (server.ROOT / "VERSION").read_text(encoding="utf-8").strip())
        self.assertEqual(state["version"], server.BACKEND_VERSION)
        self.assertEqual(len(state["questions"]), 50)
        self.assertEqual(state["settings"], server.DEFAULT_SETTINGS)
        self.assertIsNone(state["passwordReset"])

    def test_question_priority_requires_admin_and_survives_sync_and_reload(self) -> None:
        original = server.STORE.load()["questions"][0]
        self.assertFalse(original["priority"])
        path = f"/api/v1/admin/questions/{original['id']}/priority"
        status, _ = self.request(path, method="POST", authenticated=False)
        self.assertEqual(status, 401)
        for enabled in (True, True, False):
            status, result = self.request(path, method="POST", body={"priority": enabled})
            self.assertEqual(status, 200)
            self.assertEqual(result["question"], {**original, "priority": enabled})
            reopened = server.StateStore(server.STORE.path)
            self.assertEqual(reopened.load()["questions"][0]["priority"], enabled)
            _, synced = self.request("/api/v1/device/state", authenticated=False)
            self.assertEqual(synced["questions"][0]["priority"], enabled)
            self.assertEqual(len(synced["questions"]), 50)
            self.assertTrue(all(not item["priority"] for item in synced["questions"][1:]))

    def test_full_poem_survives_ai_preview_batch_sync_and_answer_restore(self) -> None:
        poem = {"title": "静夜思", "author": "李白", "content": "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。"}
        generated = {**poem, "questions": [{"prompt": "《静夜思》全文预览测试", "options": ["甲", "乙", "丙", "丁"], "answer": "甲", "poem": poem}]}
        with patch("server.generate_poem_questions", return_value=generated):
            status, preview = self.request("/api/v1/admin/questions/generate", method="POST", body={"title": "静夜思", "count": 1})
        self.assertEqual(status, 200)
        self.assertEqual(preview["questions"][0]["poem"], poem)
        self.assertEqual(len(server.STORE.load()["questions"]), 50)
        _, history = self.request("/api/v1/admin/poems")
        self.assertNotIn({"title": "静夜思", "author": "李白"}, history["poems"])
        status, _ = self.request("/api/v1/admin/questions/batch", method="POST", body={"questions": preview["questions"]})
        self.assertEqual(status, 200)
        _, history = self.request("/api/v1/admin/poems")
        self.assertIn({"title": "静夜思", "author": "李白"}, history["poems"])
        _, state = self.request("/api/v1/device/state", authenticated=False)
        question = state["questions"][-1]
        self.assertEqual(question["poem"], poem)
        self.assertEqual(server.StateStore(server.STORE.path).load()["questions"][-1]["poem"], poem)
        status, _ = self.request("/api/v1/device/answers", method="POST", authenticated=False, body={
            "attemptId": "poem-answer", "deviceId": "tv-01", "date": "2026-11-12", "answeredAt": 1794441600000,
            "questionId": question["id"], "selectedAnswer": question["answer"], "question": question,
        })
        self.assertEqual(status, 200)
        status, _ = self.request("/api/v1/admin/answers/restore", method="POST", body={"attemptId": "poem-answer"})
        self.assertEqual(status, 200)
        self.assertEqual(server.STORE.load()["questions"][-1]["poem"], poem)

    def test_poem_history_is_admin_only_and_survives_question_removal(self) -> None:
        path = "/api/v1/admin/poems"
        status, _ = self.request(path, authenticated=False)
        self.assertEqual(status, 401)
        _, history = self.request(path)
        self.assertEqual(len(history["poems"]), 10)
        poem = {"title": "静夜思", "author": "李白", "content": "床前明月光，疑是地上霜。"}
        base = {"category": "古诗词", "options": ["甲", "乙", "丙", "丁"], "answer": "甲", "poem": poem}
        for prompt in ("诗词历史测试一", "诗词历史测试二"):
            status, question = self.request("/api/v1/admin/questions", method="POST", body={**base, "prompt": prompt})
            self.assertEqual(status, 201)
            status, _ = self.request(f"/api/v1/admin/questions/{question['id']}", method="DELETE")
            self.assertEqual(status, 200)
        server.STORE = server.StateStore(server.STORE.path)
        _, history = self.request(path)
        self.assertEqual(history["poems"].count({"title": "静夜思", "author": "李白"}), 1)
        question = server.STORE.load()["questions"][0]
        self.request("/api/v1/device/answers", method="POST", authenticated=False, body={
            "questionId": question["id"], "answer": question["answer"],
        })
        _, history = self.request(path)
        self.assertIn({"title": question["poem"]["title"], "author": question["poem"]["author"]}, history["poems"])
        _, synced = self.request("/api/v1/device/state", authenticated=False)
        self.assertNotIn("poems", synced)

    def test_poem_history_backfills_existing_questions_and_answer_snapshots(self) -> None:
        # Simulate a database created before the poem history feature.
        state = server.STORE.load()
        poem = {"title": "相思", "author": "王维", "content": "红豆生南国，春来发几枝。"}
        question = {**state["questions"][0], "id": "old-poem", "prompt": "旧诗词题", "poem": poem}
        state["answerRecords"].append({"question": question})
        state["questions"].append({key: value for key, value in {
            **question, "id": "legacy-title", "prompt": "《山行》的作者是谁？",
        }.items() if key != "poem"})
        server.STORE.save(state)
        with closing(sqlite3.connect(server.STORE.path)) as connection:
            connection.execute("DELETE FROM poem_history")
            connection.commit()
        _, history = self.request("/api/v1/admin/poems")
        self.assertIn({"title": "相思", "author": "王维"}, history["poems"])
        self.assertIn({"title": "山行", "author": ""}, history["poems"])
        # Full metadata later replaces the title-only entry in the displayed list.
        state = server.STORE.load()
        state["questions"][-1]["poem"] = {"title": "山行", "author": "杜牧", "content": "远上寒山石径斜，白云生处有人家。"}
        server.STORE.save(state)
        _, history = self.request("/api/v1/admin/poems")
        self.assertNotIn({"title": "山行", "author": ""}, history["poems"])
        self.assertIn({"title": "山行", "author": "杜牧"}, history["poems"])

    def test_full_work_batches_allow_long_prose_and_reject_invalid_poem_atomically(self) -> None:
        base = {"category": "古诗词", "options": ["甲", "乙", "丙", "丁"], "answer": "甲"}
        poem = {"title": "长篇古文", "author": "作者", "content": "原文段落。\n" * 1_000}
        questions = [{**base, "prompt": f"全文导入测试{index}", "poem": poem} for index in range(10)]
        self.assertGreater(len(json.dumps({"questions": questions}).encode()), 64 * 1024)
        status, result = self.request("/api/v1/admin/questions/batch", method="POST", body={"questions": questions})
        self.assertEqual((status, result["added"]), (200, 10))
        for invalid in ({}, "诗", {**poem, "content": ""}, {**poem, "author": []}, {**poem, "content": "诗" * 20_001}):
            with self.subTest(invalid=invalid):
                before = server.STORE.load()
                status, _ = self.request("/api/v1/admin/questions/batch", method="POST", body={"questions": [
                    {**base, "prompt": "合法题目", "poem": poem}, {**base, "prompt": "非法全文", "poem": invalid},
                ]})
                self.assertEqual(status, 400)
                self.assertEqual(server.STORE.load(), before)

    def test_question_priority_rejects_invalid_values_and_missing_questions(self) -> None:
        before = server.STORE.load()
        question = before["questions"][0]
        path = f"/api/v1/admin/questions/{question['id']}/priority"
        for value in (None, 0, 1, "true", [], {}):
            status, _ = self.request(path, method="POST", body={"priority": value})
            self.assertEqual(status, 400)
            with self.assertRaises(ValueError):
                server.validate_question({**question, "priority": value})
            self.assertEqual(server.STORE.load(), before)
        status, _ = self.request("/api/v1/admin/questions/missing/priority", method="POST", body={"priority": True})
        self.assertEqual(status, 404)
        self.assertEqual(server.STORE.load(), before)
        legacy = {key: value for key, value in question.items() if key != "priority"}
        self.assertFalse(server.validate_question(legacy)["priority"])

    def test_question_priority_is_preserved_when_correct_answer_is_restored(self) -> None:
        question = server.STORE.load()["questions"][0]
        self.request(f"/api/v1/admin/questions/{question['id']}/priority", method="POST", body={"priority": True})
        status, _ = self.request("/api/v1/device/answers", method="POST", authenticated=False, body={
            "attemptId": "priority-answer", "deviceId": "tv-01", "date": "2026-11-08",
            "answeredAt": 1794096000000, "questionId": question["id"], "selectedAnswer": question["answer"],
        })
        self.assertEqual(status, 200)
        self.assertNotIn(question["id"], {item["id"] for item in server.STORE.load()["questions"]})
        status, _ = self.request("/api/v1/admin/answers/restore", method="POST", body={"attemptId": "priority-answer"})
        self.assertEqual(status, 200)
        restored = next(item for item in server.STORE.load()["questions"] if item["id"] == question["id"])
        self.assertTrue(restored["priority"])

    def test_device_usage_reports_are_daily_cumulative_and_admin_only(self) -> None:
        payload = {"deviceId": "tv-01", "date": "2026-11-05", "usageMillis": 60_000}
        for amount in (60_000, 120_000, 90_000):
            payload["usageMillis"] = amount
            status, result = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
            self.assertEqual(status, 200)
            self.assertEqual(result["usageMillis"], max(amount, 120_000) if amount == 90_000 else amount)
        _, device_state = self.request("/api/v1/device/state", authenticated=False)
        self.assertNotIn("dailyUsage", device_state)
        self.assertNotIn("answerRecords", device_state)

        status, _ = self.request("/api/v1/admin/records?date=2026-11-05", authenticated=False)
        self.assertEqual(status, 401)
        status, records = self.request("/api/v1/admin/records?date=2026-11-05")
        self.assertEqual(status, 200)
        self.assertEqual(records["usage"][0]["deviceId"], "tv-01")
        self.assertEqual(records["usage"][0]["usageMillis"], 120_000)
        self.assertTrue(records["usage"][0]["intervals"])
        self.assertGreater(records["usage"][0]["lastReportedAt"], 0)

    def test_usage_reset_is_scoped_persistent_and_rejects_stale_reports(self) -> None:
        today_date = server.datetime.now(server.timezone.utc).date()
        today, yesterday = today_date.isoformat(), (today_date - server.timedelta(days=1)).isoformat()
        for device_id, day, amount in (("tv-01", yesterday, 40_000), ("tv-02", today, 90_000), ("tv-01", today, 120_000)):
            self.request("/api/v1/device/usage", method="POST", authenticated=False,
                         body={"deviceId": device_id, "date": day, "usageMillis": amount, "utcOffsetMinutes": 0})
        before = server.STORE.load()
        payload = {"deviceId": "tv-01"}
        status, _ = self.request("/api/v1/admin/usage/reset", method="POST", authenticated=False, body=payload)
        self.assertEqual(status, 401)
        status, command = self.request("/api/v1/admin/usage/reset", method="POST", body=payload)
        self.assertEqual(status, 202)
        self.assertTrue(command["queued"])
        query = f"/api/v1/device/state?deviceId=tv-01&date={today}"
        _, synced = self.request(query, authenticated=False)
        self.assertEqual(synced["usageReset"], {"id": command["id"], "date": today})
        reopened = server.StateStore(server.STORE.path)
        self.assertEqual(reopened.snapshot("tv-01", today)["usageReset"], synced["usageReset"])
        self.assertIsNone(reopened.snapshot("tv-02", today)["usageReset"])
        self.assertIsNone(reopened.snapshot("tv-01", yesterday)["usageReset"])
        after = reopened.load()
        totals = {(item["deviceId"], item["date"]): item["usageMillis"] for item in after["dailyUsage"]}
        self.assertEqual(totals, {("tv-01", today): 0, ("tv-01", yesterday): 40_000, ("tv-02", today): 90_000})
        for key in ("passwordReset", "settings", "questions", "answerRecords", "challengeRecords"):
            self.assertEqual(after[key], before[key])
        report = {**payload, "date": today, "usageMillis": 150_000}
        status, result = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=report)
        self.assertEqual(status, 409)
        self.assertFalse(result["accepted"])
        self.assertEqual(server.STORE.load(), after)
        report.update(usageMillis=15_000, usageResetId=command["id"])
        status, result = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=report)
        self.assertEqual(status, 200)
        self.assertEqual(result["usageMillis"], 15_000)
        # Reading the same command does not zero accepted reports again.
        for _ in range(2):
            self.request(query, authenticated=False)
        current = next(item for item in server.STORE.load()["dailyUsage"] if item["deviceId"] == "tv-01" and item["date"] == today)
        self.assertEqual(current["usageMillis"], 15_000)
        _, second = self.request("/api/v1/admin/usage/reset", method="POST", body=payload)
        self.assertNotEqual(second["id"], command["id"])
        status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=report)
        self.assertEqual(status, 409)
        report.update(usageMillis=0, usageResetId=second["id"])
        status, result = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=report)
        self.assertEqual((status, result["usageMillis"]), (200, 0))
        # An old command cannot reset the next calendar day's allowance.
        status, result = self.request("/api/v1/device/usage", method="POST", authenticated=False,
                                     body={"deviceId": "tv-01", "date": (today_date + server.timedelta(days=1)).isoformat(), "usageMillis": 30_000})
        self.assertEqual((status, result["usageMillis"]), (200, 30_000))

    def test_usage_reset_validates_device_and_requires_its_timezone(self) -> None:
        self.request("/api/v1/device/usage", method="POST", authenticated=False,
                     body={"deviceId": "tv-01", "date": "2026-11-07", "usageMillis": 60_000})
        before = server.STORE.load()
        for payload in ({}, {"deviceId": "unknown"}, {"deviceId": "tv-01"}):
            status, _ = self.request("/api/v1/admin/usage/reset", method="POST", body=payload)
            self.assertEqual(status, 400)
            self.assertEqual(server.STORE.load(), before)

    def test_usage_reset_uses_current_device_timezone_instead_of_browser_date(self) -> None:
        # At 16:30 UTC, UTC+8 is already the next calendar day.
        timestamp = server.datetime(2026, 11, 7, 16, 30, tzinfo=server.timezone.utc).timestamp()
        for offset, expected_day in ((480, "2026-11-08"), (-480, "2026-11-07")):
            with self.subTest(offset=offset):
                self.request("/api/v1/device/usage", method="POST", authenticated=False,
                             body={"deviceId": "tv-01", "date": "2026-11-06", "usageMillis": 60_000, "utcOffsetMinutes": offset})
                with patch("server.time.time", return_value=timestamp):
                    status, command = self.request("/api/v1/admin/usage/reset", method="POST",
                                                   body={"deviceId": "tv-01", "date": "2099-01-01"})
                self.assertEqual(status, 202)
                self.assertEqual(command["date"], expected_day)
                self.assertIsNone(server.STORE.snapshot("tv-01", "2099-01-01")["usageReset"])
                self.assertIsNotNone(server.STORE.snapshot("tv-01", expected_day)["usageReset"])

    def test_usage_report_rejects_invalid_timezone_offset(self) -> None:
        before = server.STORE.load()
        for offset in (True, "480", 1.5, -841, 841):
            status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False,
                                     body={"deviceId": "tv-01", "date": "2026-11-07", "usageMillis": 1, "utcOffsetMinutes": offset})
            self.assertEqual(status, 400)
            self.assertEqual(server.STORE.load(), before)

    def test_device_registry_includes_name_and_online_status(self) -> None:
        payload = {
            "deviceId": "device-uuid-123", "deviceName": "TCL Thunderbird TV",
            "date": "2026-11-05", "usageMillis": 60_000,
        }
        status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        self.assertEqual(status, 200)
        status, result = self.request("/api/v1/admin/devices")
        self.assertEqual(status, 200)
        self.assertEqual(result["devices"][0]["name"], "TCL Thunderbird TV")
        self.assertTrue(result["devices"][0]["online"])
        self.assertEqual(result["devices"][0]["deviceId"], "device-uuid-123")
        _, no_usage_day = self.request("/api/v1/admin/records?date=2026-11-04")
        self.assertEqual(no_usage_day["usage"], [])
        self.assertGreater(no_usage_day["deviceDetails"][0]["lastSeen"], 0)

    def test_usage_detail_groups_incremental_reports_into_time_intervals(self) -> None:
        payload = {"deviceId": "tv-01", "deviceName": "Family TV", "date": "2026-11-05", "usageMillis": 60_000}
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        time.sleep(0.02)
        payload["usageMillis"] = 120_000
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        interval = records["usage"][0]["intervals"][0]
        self.assertGreater(interval["end"], interval["start"])
        self.assertEqual(len(records["usage"][0]["intervals"]), 1)
        self.assertEqual(records["deviceDetails"][0]["name"], "Family TV")

    def test_usage_detail_sorts_and_merges_gaps_up_to_two_minutes(self) -> None:
        state = server.STORE.load()
        state["dailyUsage"] = [{
            "deviceId": "tv-01",
            "date": "2026-11-05",
            "usageMillis": 300_000,
            "intervals": [
                {"start": 300, "end": 400},
                {"start": 100, "end": 200},
                {"start": 200, "end": 250},
                {"start": 250, "end": 300},
                {"start": 450, "end": 500},
                {"start": 490, "end": 600},
                {"start": 120_600, "end": 121_000},
            ],
        }]
        server.STORE.save(state)

        _, records = self.request("/api/v1/admin/records?date=2026-11-05")

        self.assertEqual(records["usage"][0]["intervals"], [
            {"start": 100, "end": 121_000, "usageMillis": 850},
        ])

    def test_usage_record_delete_endpoint_is_removed(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/records/usage?deviceId=tv-01&date=2026-11-05",
            method="DELETE",
        )
        self.assertEqual(status, 404)

    def test_first_offline_usage_snapshot_retains_all_device_intervals(self) -> None:
        intervals = [
            {"start": 1795326000000, "end": 1795327200000, "usageMillis": 1200000},
            {"start": 1795351200000, "end": 1795353600000, "usageMillis": 2400000},
        ]
        payload = {"deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 3600000, "usageIntervals": intervals}
        for _ in range(2):
            status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
            self.assertEqual(status, 200)
        stale = {**payload, "usageMillis": 1200000, "usageIntervals": intervals[:1]}
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=stale)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        record = records["usage"][0]
        self.assertEqual(sum(item["usageMillis"] for item in record["intervals"]), 3600000)
        self.assertEqual(record["unlocatedUsageMillis"], 0)

    def test_usage_detail_does_not_count_pauses_inside_a_continuous_period(self) -> None:
        payload = {
            "deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 120000,
            "usageIntervals": [
                {"start": 1795326000000, "end": 1795326060000, "usageMillis": 60000},
                {"start": 1795326120000, "end": 1795326180000, "usageMillis": 60000},
            ],
        }
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        intervals = records["usage"][0]["intervals"]
        self.assertEqual(len(intervals), 1)
        self.assertEqual(intervals[0]["end"] - intervals[0]["start"], 180000)
        self.assertEqual(intervals[0]["usageMillis"], 120000)

    def test_historical_usage_details_explicitly_account_for_unlocated_time(self) -> None:
        state = server.STORE.load()
        state["dailyUsage"] = [{
            "deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 3600000,
            "intervals": [
                {"start": 1795326078161, "end": 1795326139653},
                {"start": 1795337154692, "end": 1795337215166},
                {"start": 1795351342272, "end": 1795352848894},
            ],
        }]
        server.STORE.save(state)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        record = records["usage"][0]
        self.assertEqual(record["unlocatedUsageMillis"], 1971412)
        self.assertEqual(sum(item["usageMillis"] for item in record["intervals"]) + record["unlocatedUsageMillis"], record["usageMillis"])

    def test_usage_interval_snapshot_rejects_invalid_or_excess_duration(self) -> None:
        for intervals in (None, [{"start": 100, "end": 200, "usageMillis": 101}],
                          [{"start": 100, "end": 200, "usageMillis": True}],
                          [{"start": 100, "end": 200, "usageMillis": 100}]):
            with self.subTest(intervals=intervals):
                status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body={
                    "deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 50, "usageIntervals": intervals,
                })
                self.assertEqual(status, 400)

    def test_device_interval_snapshots_preserve_pre_upgrade_details_without_duplicates(self) -> None:
        state = server.STORE.load()
        state["dailyUsage"] = [{
            "deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 60000,
            "intervals": [{"start": 1000000, "end": 1060000}],
        }]
        server.STORE.save(state)
        payload = {
            "deviceId": "tv-01", "date": "2026-11-22", "usageMillis": 120000,
            "usageIntervals": [{"start": 2000000, "end": 2060000, "usageMillis": 60000}],
        }
        for _ in range(2):
            self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        record = records["usage"][0]
        self.assertEqual(sum(item["usageMillis"] for item in record["intervals"]), 120000)
        self.assertEqual(record["unlocatedUsageMillis"], 0)

    def test_usage_reset_clears_device_intervals_and_rejects_a_pre_reset_snapshot(self) -> None:
        today = server.datetime.now(server.timezone.utc).date().isoformat()
        payload = {
            "deviceId": "tv-01", "date": today, "usageMillis": 60000, "utcOffsetMinutes": 0,
            "usageIntervals": [{"start": 1000000, "end": 1060000, "usageMillis": 60000}],
        }
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        command = server.STORE.queue_usage_reset("tv-01")
        status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        self.assertEqual(status, 409)
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body={
            **payload, "usageMillis": 0, "usageIntervals": [], "usageResetId": command["id"],
        })
        _, records = self.request(f"/api/v1/admin/records?date={today}")
        self.assertEqual(records["usage"][0]["intervals"], [])
        self.assertEqual(records["usage"][0]["unlocatedUsageMillis"], 0)

    def test_answering_parent_and_reward_time_are_separate_from_the_daily_quota(self) -> None:
        minute = 60000
        self.request("/api/v1/admin/rewards/grant", method="POST", body={"deviceId": "tv-01", "minutes": 10})
        periods = [
            {"start": 1000000, "end": 1060000, "usageMillis": minute},
            {"start": 1060000, "end": 1180000, "usageMillis": 2 * minute, "kind": "answering"},
            {"start": 1180000, "end": 1480000, "usageMillis": 5 * minute, "kind": "parent_unlocked"},
            {"start": 1480000, "end": 1540000, "usageMillis": minute, "kind": "reward"},
        ]
        payload = {"deviceId": "tv-01", "date": "2026-11-22", "usageMillis": minute, "usageIntervals": periods,
                   "rewardTime": {"spentMillis": minute, "usedTodayMillis": minute}}
        status, _ = self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        self.assertEqual(status, 200)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        record = records["usage"][0]
        self.assertEqual((record["usageMillis"], record["answeringMillis"], record["parentUnlockedMillis"], record["rewardUsedMillis"]),
                         (minute, 2 * minute, 5 * minute, minute))
        self.assertEqual(len(record["intervals"]), 4)
        self.assertEqual(record["unlocatedUsageMillis"], 0)
        self.assertEqual(record["unlocatedRewardMillis"], 0)
        # The quota is unchanged while parent viewing grows. An older snapshot must not erase it.
        newer = [{**item, "end": item["end"] + minute, "usageMillis": item["usageMillis"] + minute}
                 if item.get("kind") == "parent_unlocked" else item for item in periods]
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body={**payload, "usageIntervals": newer})
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        _, records = self.request("/api/v1/admin/records?date=2026-11-22")
        self.assertEqual(records["usage"][0]["parentUnlockedMillis"], 6 * minute)

    def test_quota_reset_preserves_answering_and_parent_viewing_details(self) -> None:
        today = server.datetime.now(server.timezone.utc).date().isoformat()
        periods = [
            {"start": 1000000, "end": 1060000, "usageMillis": 60000},
            {"start": 1060000, "end": 1120000, "usageMillis": 60000, "kind": "answering"},
            {"start": 1120000, "end": 1180000, "usageMillis": 60000, "kind": "parent_unlocked"},
        ]
        payload = {"deviceId": "tv-01", "date": today, "utcOffsetMinutes": 0, "usageMillis": 60000, "usageIntervals": periods}
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body=payload)
        command = server.STORE.queue_usage_reset("tv-01")
        self.request("/api/v1/device/usage", method="POST", authenticated=False, body={
            **payload, "usageMillis": 0, "usageIntervals": periods[1:], "usageResetId": command["id"],
        })
        _, records = self.request(f"/api/v1/admin/records?date={today}")
        self.assertEqual(records["usage"][0]["usageMillis"], 0)
        self.assertEqual(records["usage"][0]["answeringMillis"], 60000)
        self.assertEqual(records["usage"][0]["parentUnlockedMillis"], 60000)

    def test_answer_attempts_record_correctness_and_retry_without_duplicates(self) -> None:
        question = server.STORE.load()["questions"][0]
        payload = {
            "attemptId": "attempt-wrong-1", "deviceId": "tv-01", "date": "2026-11-05",
            "answeredAt": 1793836800000, "questionId": question["id"],
            "selectedAnswer": next(option for option in question["options"] if option != question["answer"]),
        }
        invalid_payload = {**payload, "attemptId": "attempt-invalid", "selectedAnswer": "not an option"}
        status, _ = self.request("/api/v1/device/answers", method="POST", authenticated=False, body=invalid_payload)
        self.assertEqual(status, 400)
        for _ in range(2):
            status, result = self.request("/api/v1/device/answers", method="POST", authenticated=False, body=payload)
            self.assertEqual(status, 200)
            self.assertFalse(result["correct"])
        self.assertEqual(len(server.STORE.load()["questions"]), 50)

        payload["attemptId"] = "attempt-correct-1"
        payload["answeredAt"] += 1000
        payload["selectedAnswer"] = question["answer"]
        status, result = self.request("/api/v1/device/answers", method="POST", authenticated=False, body=payload)
        self.assertEqual(status, 200)
        self.assertTrue(result["correct"])
        self.assertTrue(result["removed"])

        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        self.assertEqual(len(records["answers"]), 2)
        self.assertEqual([item["correct"] for item in records["answers"]], [True, False])
        self.assertTrue(all("correctAnswer" not in item for item in records["answers"]))
        self.assertEqual(len(server.STORE.load()["questions"]), 49)

    def test_admin_can_restore_a_correct_answer_to_the_question_bank(self) -> None:
        question = server.STORE.load()["questions"][0]
        self.request(
            "/api/v1/device/answers", method="POST", authenticated=False,
            body={
                "attemptId": "restore-attempt", "deviceId": "tv-01", "date": "2026-11-05",
                "answeredAt": 1793836800000, "questionId": question["id"],
                "challengeId": "restore-challenge", "selectedAnswer": question["answer"],
                "question": question,
            },
        )
        state = server.STORE.load()
        state["answerRecords"][0].pop("question")
        server.STORE.save(state)
        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        answer = records["answers"][0]
        self.assertTrue(answer["restorable"])
        self.assertFalse(answer["inQuestionBank"])
        self.assertNotIn("question", answer)

        path = "/api/v1/admin/answers/restore"
        status, _ = self.request(path, method="POST", authenticated=False, body={"attemptId": "restore-attempt"})
        self.assertEqual(status, 401)
        status, result = self.request(path, method="POST", body={"attemptId": "restore-attempt"})
        self.assertEqual(status, 200)
        self.assertTrue(result["restored"])
        self.assertIn(question["id"], {item["id"] for item in server.STORE.load()["questions"]})
        status, result = self.request(path, method="POST", body={"attemptId": "restore-attempt"})
        self.assertEqual(status, 200)
        self.assertFalse(result["restored"])
        self.assertTrue(result["inQuestionBank"])

    def test_admin_records_filter_usage_and_answers_by_device(self) -> None:
        question = server.STORE.load()["questions"][0]
        wrong_answer = next(option for option in question["options"] if option != question["answer"])
        for device_id in ("tv-01", "tv-02"):
            self.request(
                "/api/v1/device/usage", method="POST", authenticated=False,
                body={"deviceId": device_id, "date": "2026-11-05", "usageMillis": 60_000},
            )
            status, _ = self.request(
                "/api/v1/device/answers", method="POST", authenticated=False,
                body={
                    "attemptId": f"attempt-{device_id}", "deviceId": device_id,
                    "date": "2026-11-05", "answeredAt": 1793836800000,
                    "questionId": question["id"], "selectedAnswer": wrong_answer,
                },
            )
            self.assertEqual(status, 200)

        status, all_devices = self.request("/api/v1/admin/records?date=2026-11-05")
        self.assertEqual(status, 200)
        self.assertEqual(all_devices["devices"], ["tv-01", "tv-02"])
        for device_id in ("tv-01", "tv-02"):
            status, records = self.request(
                f"/api/v1/admin/records?date=2026-11-05&deviceId={device_id}",
            )
            self.assertEqual(status, 200)
            self.assertEqual({item["deviceId"] for item in records["usage"]}, {device_id})
            self.assertEqual({item["deviceId"] for item in records["answers"]}, {device_id})

    def test_unanswered_attempt_is_recorded_as_incorrect_without_consuming_question(self) -> None:
        question = server.STORE.load()["questions"][0]
        status, result = self.request(
            "/api/v1/device/answers", method="POST", authenticated=False,
            body={
                "attemptId": "attempt-unanswered", "deviceId": "tv-01",
                "date": "2026-11-05", "answeredAt": 1793836800000,
                "questionId": question["id"], "selectedAnswer": "", "answered": False,
            },
        )
        self.assertEqual(status, 200)
        self.assertFalse(result["correct"])
        self.assertEqual(len(server.STORE.load()["questions"]), 50)
        _, records = self.request("/api/v1/admin/records?date=2026-11-05&deviceId=tv-01")
        self.assertFalse(records["answers"][0]["answered"])
        self.assertFalse(records["answers"][0]["correct"])

    def test_challenge_sessions_group_five_questions_and_update_exit_reason(self) -> None:
        questions = server.STORE.load()["questions"][:5]
        started_at = 1793836800000
        session = {
            "sessionId": "challenge-tv-01-1",
            "deviceId": "tv-01",
            "date": "2026-11-05",
            "startedAt": started_at,
            "endedAt": 0,
            "status": "active",
            "answeredCount": 1,
            "correctCount": 0,
            "questions": [{"questionId": item["id"], "prompt": item["prompt"]} for item in questions],
            "interruptedQuestionId": "",
            "trigger": "startup",
            "resumedFromSessionId": "",
        }
        status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=session)
        self.assertEqual(status, 200)

        question = questions[0]
        wrong_answer = next(option for option in question["options"] if option != question["answer"])
        status, _ = self.request(
            "/api/v1/device/answers", method="POST", authenticated=False,
            body={
                "attemptId": "challenge-tv-01-answer-1", "challengeId": session["sessionId"],
                "deviceId": "tv-01", "date": "2026-11-05", "answeredAt": started_at + 10_000,
                "questionId": question["id"], "selectedAnswer": wrong_answer,
            },
        )
        self.assertEqual(status, 200)

        session.update({
            "endedAt": started_at + 60_000,
            "status": "timeout",
            "interruptedQuestionId": questions[1]["id"],
        })
        status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=session)
        self.assertEqual(status, 200)

        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        self.assertEqual(len(records["challenges"]), 1)
        challenge = records["challenges"][0]
        self.assertEqual(challenge["status"], "timeout")
        self.assertEqual(challenge["trigger"], "startup")
        self.assertEqual(challenge["deviceName"], "tv-01")
        self.assertEqual(len(challenge["questions"]), 5)
        self.assertEqual(challenge["interruptedQuestionId"], questions[1]["id"])
        self.assertEqual(records["answers"][0]["challengeId"], session["sessionId"])

    def test_challenge_triggers_survive_updates_retries_and_database_reload(self) -> None:
        questions = server.STORE.load()["questions"][:5]
        session = {
            "sessionId": "trigger-test", "deviceId": "tv-01", "date": "2026-11-05",
            "startedAt": 1793836800000, "endedAt": 0, "status": "active",
            "answeredCount": 0, "correctCount": 0,
            "questions": [{"questionId": item["id"], "prompt": item["prompt"]} for item in questions],
        }
        for trigger in ("startup", "interval", "lockout_retry", "reboot_resume", "unknown"):
            with self.subTest(trigger=trigger):
                report = dict(session, sessionId=trigger, trigger=trigger,
                              resumedFromSessionId="previous-round" if trigger == "reboot_resume" else "")
                status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=report)
                self.assertEqual(status, 200)
                finished = dict(report, status="parent_skip", endedAt=1793836860000)
                status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=finished)
                self.assertEqual(status, 200)
                self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=report)
                server.STORE = server.StateStore(server.STORE.path)
                _, records = self.request("/api/v1/admin/records?date=2026-11-05")
                saved = next(item for item in records["challenges"] if item["sessionId"] == trigger)
                self.assertEqual(saved["trigger"], trigger)
                self.assertEqual(saved["status"], "parent_skip")
                self.assertEqual(saved["resumedFromSessionId"], report["resumedFromSessionId"])

        for field, value in (("trigger", "invalid"), ("trigger", []), ("resumedFromSessionId", 123), ("resumedFromSessionId", "x" * 81)):
            status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False,
                                     body=dict(session, **{field: value}))
            self.assertEqual(status, 400)

        status, _ = self.request("/api/v1/device/challenges", method="POST", authenticated=False, body=session)
        self.assertEqual(status, 200)
        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        legacy = next(item for item in records["challenges"] if item["sessionId"] == "trigger-test")
        self.assertEqual(legacy["trigger"], "unknown")

    def test_cached_question_answer_is_accepted_after_question_was_removed(self) -> None:
        question = server.STORE.load()["questions"][0]
        state = server.STORE.load()
        state["questions"] = state["questions"][1:]
        server.STORE.save(state)
        status, result = self.request(
            "/api/v1/device/answers", method="POST", authenticated=False,
            body={
                "attemptId": "cached-question-answer-1", "challengeId": "cached-challenge-1",
                "deviceId": "tv-01", "date": "2026-11-05", "answeredAt": 1793836810000,
                "questionId": question["id"], "selectedAnswer": question["answer"], "answered": True,
                "question": question,
            },
        )
        self.assertEqual(status, 200)
        self.assertTrue(result["correct"])
        _, records = self.request("/api/v1/admin/records?date=2026-11-05")
        self.assertEqual(records["answers"][0]["prompt"], question["prompt"])

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

    def test_new_database_seeds_admin_admin_in_sqlite(self) -> None:
        database_path = Path(self.temp_dir.name) / "fresh.sqlite3"
        server.StateStore(database_path).snapshot()
        auth = server.AdminAuth(database_path)
        status, session = auth.login("admin", "admin")
        self.assertEqual(status, 200)
        self.assertTrue(session)
        with closing(sqlite3.connect(database_path)) as connection:
            username, salt, iterations, password_hash = connection.execute(
                "SELECT username, salt, iterations, password_hash FROM accounts WHERE id = 1",
            ).fetchone()
        self.assertEqual(username, "admin")
        self.assertNotEqual(password_hash, "admin")
        self.assertEqual(len(bytes.fromhex(salt)), 16)
        self.assertEqual(iterations, server.PASSWORD_ITERATIONS)

    def test_admin_can_change_password_and_revoke_other_sessions(self) -> None:
        other_cookies = CookieJar()
        other_opener = build_opener(HTTPCookieProcessor(other_cookies))
        request = Request(
            f"{self.base_url}/api/v1/admin/login",
            data=json.dumps({"username": "admin", "password": "test-password-123"}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with other_opener.open(request, timeout=3) as response:
            self.assertEqual(response.status, 200)
        other_cookie = next(iter(other_cookies))

        status, result = self.request(
            "/api/v1/admin/password/change", method="POST",
            body={
                "currentPassword": "test-password-123",
                "newPassword": "replacement-password",
                "confirmPassword": "replacement-password",
            },
        )
        self.assertEqual(status, 200)
        self.assertTrue(result["changed"])
        self.assertEqual(self.request("/api/v1/admin/state")[0], 200)
        self.assertEqual(self.request(
            "/api/v1/admin/state", authenticated=False,
            headers={"Cookie": f"{other_cookie.name}={other_cookie.value}"},
        )[0], 401)
        self.assertEqual(self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "test-password-123"},
        )[0], 401)
        self.assertEqual(self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "replacement-password"},
        )[0], 200)

    def test_password_change_requires_current_password_and_matching_confirmation(self) -> None:
        status, _ = self.request(
            "/api/v1/admin/password/change", method="POST",
            body={
                "currentPassword": "wrong-password",
                "newPassword": "replacement-password",
                "confirmPassword": "replacement-password",
            },
        )
        self.assertEqual(status, 400)
        status, _ = self.request(
            "/api/v1/admin/password/change", method="POST",
            body={
                "currentPassword": "test-password-123",
                "newPassword": "replacement-password",
                "confirmPassword": "different-password",
            },
        )
        self.assertEqual(status, 400)
        self.assertEqual(self.request("/api/v1/admin/state")[0], 200)

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
        # A separate CLI process resets the password in the same SQLite database.
        server.AdminAuth(server.AUTH.path).set_password("admin", "replacement-password")
        status, _ = self.request("/api/v1/admin/state")
        self.assertEqual(status, 401)
        self.assertEqual(server.AUTH.login("admin", "replacement-password")[0], 200)
        with closing(sqlite3.connect(server.AUTH.path)) as connection:
            password_hash = connection.execute("SELECT password_hash FROM accounts WHERE id = 1").fetchone()[0]
        self.assertNotEqual(password_hash, "replacement-password")

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

    def test_device_sync_works_with_default_admin_on_new_database(self) -> None:
        database_path = Path(self.temp_dir.name) / "new-state.sqlite3"
        server.STORE = server.StateStore(database_path)
        server.AUTH = server.AdminAuth(database_path)
        status, _ = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(status, 200)
        status, _ = self.request(
            "/api/v1/admin/login", method="POST", authenticated=False,
            body={"username": "admin", "password": "admin"},
        )
        self.assertEqual(status, 200)

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
            "dailyRewardLimitMinutes": 25,
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
        with patch("server.generate_poem_questions") as generate_poem, patch("server.generate_category_questions") as generate_category, patch("server.generate_english_questions") as generate_english:
            status, _ = self.request(
                "/api/v1/admin/questions/generate", method="POST", authenticated=False,
                body={"title": "静夜思", "count": 3},
            )
            self.assertEqual(status, 401)
            invalid = [
                {"title": "", "count": 3}, {"title": "静夜思", "count": True}, {"title": "静夜思", "count": 11},
                {"category": "其他", "count": 2},
                *({"category": "英语", "word": value} for value in ("", "apple pie", "苹果", "hello!")),
                *({"title": "静夜思", "difficulty": value} for value in ("", "初中", None, 1, [])),
            ]
            for payload in invalid:
                status, _ = self.request("/api/v1/admin/questions/generate", method="POST", body=payload)
                self.assertEqual(status, 400)
            generate_poem.assert_not_called()
            generate_category.assert_not_called()
            generate_english.assert_not_called()

    def test_english_ai_generation_uses_word_and_tags_preview_questions(self) -> None:
        generated = {
            "title": "apple",
            "questions": [{
                "prompt": "常见的中文释义是什么？", "options": ["苹果", "香蕉", "橙子", "梨"], "answer": "苹果",
            }],
        }
        with patch("server.generate_english_questions", return_value=generated) as generate:
            status, preview = self.request(
                "/api/v1/admin/questions/generate", method="POST",
                body={"category": "英语", "word": "apple", "count": 1, "difficulty": "高"},
            )
        self.assertEqual(status, 200)
        generate.assert_called_once_with("apple", 1, "高")
        self.assertEqual(preview["category"], "英语")
        self.assertEqual(preview["title"], "apple")
        self.assertEqual(preview["questions"][0]["category"], "英语")
        self.assertEqual(preview["questions"][0]["difficulty"], "高")
        self.assertIn("apple", preview["questions"][0]["prompt"])

    def test_random_ai_categories_need_no_title_and_are_forced_on_questions(self) -> None:
        generated = [{
            "category": "古诗词", "difficulty": "低", "prompt": "随机生成题目",
            "options": ["甲", "乙", "丙", "丁"], "answer": "甲",
        }]
        for category in ("数学", "地理历史", "科学"):
            with self.subTest(category=category), patch("server.generate_category_questions", return_value=generated) as generate:
                status, preview = self.request(
                    "/api/v1/admin/questions/generate", method="POST",
                    body={"category": category, "count": 1, "difficulty": "高"},
                )
            self.assertEqual(status, 200)
            generate.assert_called_once_with(category, 1, "高")
            self.assertEqual(preview["category"], category)
            self.assertEqual(preview["title"], category)
            self.assertEqual(preview["questions"][0]["category"], category)
            self.assertEqual(preview["questions"][0]["difficulty"], "高")
            self.assertEqual(preview["questions"][0]["prompt"], "随机生成题目")
        _, state = self.request("/api/v1/device/state", authenticated=False)
        self.assertEqual(len(state["questions"]), 50)

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


if __name__ == "__main__":
    unittest.main()
