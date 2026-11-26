from __future__ import annotations

import json
import secrets
import sqlite3
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.cookiejar import CookieJar
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class AccountBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_store, self.old_auth = server.STORE, server.AUTH
        self.path = Path(self.temp.name) / "state.sqlite3"
        server.STORE = server.StateStore(self.path)
        server.STORE.load()
        server.AUTH = server.AdminAuth(self.path)
        self.patch = patch.object(server.StateStore, "ensure_poem_enrichment")
        self.patch.start()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.RequestHandler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.alice = self.register("alice")
        self.bob = self.register("bob")
        self.admin = build_opener(HTTPCookieProcessor(CookieJar()))
        self.assertEqual(self.request(self.admin, "/api/v1/admin/login", {"username": "admin", "password": "admin"})[0], 200)
        self.device = build_opener()
        self.headers = {"X-Device-Id": "test-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join()
        self.patch.stop()
        server.STORE, server.AUTH = self.old_store, self.old_auth
        self.temp.cleanup()

    def request(self, client, path, body=None, headers=None, method=None):
        data = json.dumps(body).encode() if body is not None else None
        request = Request(self.base + path, data=data, headers={"Content-Type": "application/json", **(headers or {})}, method=method)
        try:
            response = client.open(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.code, json.load(response)

    def register(self, name):
        client = build_opener(HTTPCookieProcessor(CookieJar()))
        body = {"username": name, "password": "long-password-123", "confirmPassword": "long-password-123"}
        self.assertEqual(self.request(client, "/api/v1/admin/register", body)[0], 201)
        self.assertEqual(self.request(client, "/api/v1/admin/login", body)[0], 200)
        return client

    def pairing(self):
        status, result = self.request(self.device, "/api/v1/device/pairing", {"deviceName": "Living room TV"}, self.headers)
        self.assertEqual(status, 200)
        return result

    def bind(self, client):
        code = self.pairing()["code"]
        status, device = self.request(client, "/api/v1/admin/devices/preview", {"code": code})
        self.assertEqual(status, 200)
        self.assertEqual(device["name"], "Living room TV")
        self.assertEqual(self.request(client, "/api/v1/admin/devices/bind", {"code": code, "deviceId": device["deviceId"]})[0], 200)
        status, binding = self.request(self.device, "/api/v1/device/binding", headers=self.headers)
        self.assertEqual(status, 200)
        self.headers["X-Binding-Id"] = binding["bindingId"]
        return code

    def test_admin_state_identifies_current_account(self):
        for client, username in ((self.admin, "admin"), (self.alice, "alice"), (self.bob, "bob")):
            with self.subTest(username=username):
                status, state = self.request(client, "/api/v1/admin/state")
                self.assertEqual(status, 200)
                self.assertEqual(state["username"], username)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/login", {
            "username": "ALICE", "password": "long-password-123",
        })[0], 200)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/state")[1]["username"], "alice")
        self.assertEqual(self.request(self.device, "/api/v1/admin/state")[0], 401)

    def test_pin_reset_is_scoped_to_the_selected_owned_device(self):
        path = "/api/v1/admin/password/reset"
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        self.assertEqual(self.request(self.device, path, {"deviceId": "test-tv"})[0], 401)
        self.assertEqual(self.request(self.bob, path, {"deviceId": "test-tv"})[0], 404)
        for device_id in (None, "", 1, [], "x" * 81):
            self.assertEqual(self.request(self.alice, path, {"deviceId": device_id})[0], 400)
        self.assertEqual(self.request(self.alice, path, {"deviceId": "missing-tv"})[0], 404)
        before = server.STORE.for_account(2).load()
        status, command = self.request(self.alice, path, {"deviceId": "test-tv"})
        self.assertEqual(status, 202)
        self.assertTrue(command["queued"])
        server.STORE = server.StateStore(self.path)
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=first_headers)[1]["passwordReset"], {"id": command["id"]})
        self.assertIsNone(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["passwordReset"])
        self.assertIsNone(self.request(self.bob, "/api/v1/admin/state")[1]["passwordReset"])
        after = server.STORE.for_account(2).load()
        after["devices"]["test-tv"].pop("passwordReset")
        self.assertEqual(after, before)
        state = server.STORE.for_account(2).load()
        state["passwordReset"] = {"id": "previous-account-reset"}
        server.STORE.for_account(2).save(state)
        status, replacement = self.request(self.alice, path, {"deviceId": "test-tv"})
        self.assertEqual(status, 202)
        self.assertNotEqual(replacement["id"], command["id"])
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=first_headers)[1]["passwordReset"], {"id": replacement["id"]})
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["passwordReset"], {"id": "previous-account-reset"})
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 200)
        self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv"})[0], 404)

    def test_advanced_history_device_filter_uses_the_selected_devices_snapshot(self):
        self.bind(self.alice)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        store = server.STORE.for_account(2)
        state = store.load()
        question = state["rewardQuestions"][0]
        updated = {**question, "prompt": "Latest question on the second TV", "numericAnswer": "052"}
        state["answerRecords"] = [
            {"questionKind": "reward", "deviceId": "test-tv", "questionId": question["id"], "question": question, "answeredAt": 1000},
            {"questionKind": "reward", "deviceId": "second-tv", "questionId": question["id"], "question": updated, "answeredAt": 2000},
        ]
        store.save(state)
        path = "/api/v1/admin/poems"
        shared_poems = self.request(self.alice, path)[1]["poems"]
        self.assertEqual(self.request(self.alice, path + "?deviceId=test-tv")[1], {
            "poems": shared_poems,
            "advancedQuestions": [{**question, "answeredDevices": [{"deviceId": "test-tv", "name": "Living room TV"}]}],
        })
        self.assertEqual(self.request(self.alice, path + "?deviceId=second-tv")[1]["advancedQuestions"], [
            {**updated, "answeredDevices": [{"deviceId": "second-tv", "name": "Living room TV"}]},
        ])
        self.assertEqual(self.request(self.alice, path)[1]["advancedQuestions"], [{**updated, "answeredDevices": [
            {"deviceId": "second-tv", "name": "Living room TV"}, {"deviceId": "test-tv", "name": "Living room TV"},
        ]}])
        self.assertEqual(self.request(self.bob, path + "?deviceId=test-tv")[0], 404)
        self.assertEqual(self.request(self.alice, path + "?deviceId=missing-tv")[0], 404)
        self.assertEqual(store.load()["answerRecords"], state["answerRecords"])

    def test_registration_validation_and_case_insensitive_uniqueness(self):
        for body in ({}, {"username": "new", "password": "", "confirmPassword": ""},
                     {"username": "new", "password": 1, "confirmPassword": 1},
                     {"username": "new", "password": "long-password-123", "confirmPassword": "different"}):
            self.assertEqual(self.request(self.device, "/api/v1/admin/register", body)[0], 400)
        self.assertEqual(self.request(self.device, "/api/v1/admin/register", {
            "username": "ALICE", "password": "long-password-123", "confirmPassword": "long-password-123",
        })[0], 409)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertNotEqual(db.execute("SELECT password_hash FROM accounts WHERE username = 'alice'").fetchone()[0], "long-password-123")

    def test_passwords_have_no_length_or_complexity_policy(self):
        for index, password in enumerate(("1", "x" * 1025)):
            with self.subTest(length=len(password)):
                username = f"simple{index}"
                client = build_opener(HTTPCookieProcessor(CookieJar()))
                self.assertEqual(self.request(client, "/api/v1/admin/register", {
                    "username": username, "password": password, "confirmPassword": password,
                })[0], 201)
                self.assertEqual(self.request(client, "/api/v1/admin/login", {"username": username, "password": password})[0], 200)
                self.assertEqual(self.request(client, "/api/v1/admin/password/change", {
                    "currentPassword": password, "newPassword": password, "confirmPassword": password,
                })[0], 200)
                self.assertEqual(self.request(client, "/api/v1/admin/password/change", {
                    "currentPassword": password, "newPassword": "2", "confirmPassword": "2",
                })[0], 200)
                self.assertEqual(self.request(client, "/api/v1/admin/login", {"username": username, "password": "2"})[0], 200)
                self.assertEqual(self.request(client, "/api/v1/admin/password/change", {
                    "currentPassword": "2", "newPassword": "", "confirmPassword": "",
                })[0], 400)
                server.AUTH.set_password(username, password)
                self.assertEqual(self.request(client, "/api/v1/admin/login", {"username": username, "password": password})[0], 200)

    def test_unbound_device_and_anonymous_legacy_endpoints_are_rejected(self):
        for path in ("state", "usage", "answers", "challenges"):
            self.assertEqual(self.request(self.device, "/api/v1/device/" + path, {} if path != "state" else None)[0], 401)
        self.pairing()
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[0], 403)
        fake = {**self.headers, "Authorization": "Bearer " + "x" * 43}
        self.assertEqual(self.request(self.device, "/api/v1/device/binding", headers=fake)[0], 401)

    def test_reinstall_before_binding_replaces_pending_credentials_and_code(self):
        previous = self.pairing()
        old_headers = dict(self.headers)
        self.headers["Authorization"] = "Bearer " + secrets.token_urlsafe(32)
        with patch("server.secrets.randbelow", return_value=(int(previous["code"]) + 1) % 1_000_000):
            current = self.pairing()
        self.assertFalse(current["bound"])
        self.assertEqual(self.request(self.device, "/api/v1/device/binding", headers=old_headers)[0], 401)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/preview", {"code": previous["code"]})[0], 400)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/preview", {"code": current["code"]})[0], 200)
        self.bind(self.alice)

    def test_binding_is_one_time_expires_and_cannot_be_stolen(self):
        code = self.bind(self.alice)
        self.assertEqual(self.request(self.bob, "/api/v1/admin/devices/bind", {"code": code, "deviceId": "test-tv"})[0], 400)
        self.assertEqual(self.request(self.bob, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 404)
        self.assertTrue(self.pairing()["bound"])
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 200)
        new = self.pairing()
        with patch("server.time.time", return_value=new["expiresAt"] / 1000 + 1):
            self.assertEqual(self.request(self.bob, "/api/v1/admin/devices/preview", {"code": new["code"]})[0], 400)

    def test_reward_grants_are_owned_persistent_and_spending_is_cumulative(self):
        grant = "/api/v1/admin/rewards/grant"
        usage = "/api/v1/device/usage"
        now = server.datetime.now(server.timezone(server.timedelta(hours=8)))
        first_day = now.date().isoformat()
        second_day = (now.date() + server.timedelta(days=1)).isoformat()
        minute = 60_000
        self.bind(self.alice)
        body = {"deviceId": "test-tv", "minutes": 45}
        self.assertEqual(self.request(self.device, grant, body)[0], 401)
        self.assertEqual(self.request(self.bob, grant, body)[0], 404)
        for invalid in (0, True, 1.5, 1441):
            self.assertEqual(self.request(self.alice, grant, {**body, "minutes": invalid})[0], 400)
        self.assertEqual(self.request(self.alice, grant, body), (200, {"rewardBalanceMillis": 45 * minute}))
        self.assertEqual(self.request(self.alice, grant, {**body, "minutes": 10}), (200, {"rewardBalanceMillis": 55 * minute}))
        server.STORE = server.StateStore(self.path)

        def state(day):
            status, payload = self.request(self.device, f"/api/v1/device/state?date={day}", headers=self.headers)
            self.assertEqual(status, 200)
            return payload["rewardTime"]

        self.assertEqual(state(first_day)["grantedMillis"], 55 * minute)
        report = {"deviceId": "test-tv", "date": first_day, "usageMillis": 60 * minute, "utcOffsetMinutes": 480}
        for spent in (30, 30, 20):
            telemetry = {**report, "rewardTime": {"spentMillis": spent * minute, "usedTodayMillis": spent * minute}}
            status, payload = self.request(self.device, usage, telemetry, self.headers)
            self.assertEqual(status, 200)
            self.assertEqual(payload["rewardTime"]["balanceMillis"], 25 * minute)
        self.assertEqual(self.request(self.device, usage, {
            **report, "rewardTime": {"spentMillis": 56 * minute, "usedTodayMillis": minute},
        }, self.headers)[0], 400)

        with patch.object(server.time, "time", return_value=now.timestamp()):
            status, reset = self.request(self.alice, "/api/v1/admin/usage/reset", {"deviceId": "test-tv"})
        self.assertEqual(status, 202)
        self.assertEqual(reset["date"], first_day)
        self.assertEqual(state(first_day)["usedTodayMillis"], 30 * minute)
        self.assertEqual(state(second_day)["usedTodayMillis"], 0)
        self.assertEqual(state(second_day)["balanceMillis"], 25 * minute)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices")[1]["devices"][0]["rewardBalanceMillis"], 25 * minute)

    def test_advanced_choice_attempts_are_recorded_deleted_and_restorable(self):
        bank = "/api/v1/admin/reward-questions"
        challenge = "/api/v1/device/reward-challenge"
        minute = server.MILLIS_PER_MINUTE
        question = {"prompt": "What is 12 squared?", "options": ["144", "124", "122", "142"], "answer": "144"}
        self.assertEqual(self.request(self.device, challenge)[0], 401)
        self.bind(self.alice)
        self.assertEqual(self.request(
            self.alice, bank + "/" + server.DEFAULT_REWARD_QUESTION["id"], method="DELETE",
        )[0], 200)
        self.assertEqual(self.request(self.alice, bank, {**question, "rewardMinutes": True})[0], 400)
        status, first = self.request(self.alice, bank, question)
        self.assertEqual(status, 201)
        self.assertEqual(first["rewardMinutes"], 10)
        status, second = self.request(self.alice, bank, {**question, "prompt": "Another challenge", "rewardMinutes": 45})
        self.assertEqual(status, 201)
        answered_at = int(server.time.time() * 1000)
        first_answer = {"questionId": first["id"], "selectedIndex": 0, "attemptId": "first-correct", "answeredAt": answered_at}
        second_answer = {"questionId": second["id"], "selectedIndex": 2, "attemptId": "second-wrong", "answeredAt": answered_at + 1000}
        self.assertEqual(self.request(self.bob, bank + "/" + first["id"], method="DELETE")[0], 404)
        bob_rewards = self.request(self.bob, "/api/v1/admin/state")[1]["rewardQuestions"]
        self.assertEqual([item["id"] for item in bob_rewards], [server.DEFAULT_REWARD_QUESTION["id"]])
        device_state = self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]
        self.assertNotIn("rewardQuestions", device_state)
        self.assertNotIn(first["id"], {q["id"] for q in device_state["questions"]})
        self.assertEqual(self.request(self.device, challenge, first_answer, self.headers)[0], 409)

        local_day = server.datetime.now(server.timezone.utc).date().isoformat()
        report = {"deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 0, "usageMillis": 60 * minute}
        self.assertEqual(self.request(self.device, "/api/v1/device/usage", report, self.headers)[0], 200)
        status, offer = self.request(self.device, challenge, headers=self.headers)
        self.assertEqual(status, 200)
        self.assertEqual(offer["question"], {key: first[key] for key in ("id", "prompt", "options", "rewardMinutes")})
        self.assertEqual(self.request(self.device, challenge, {**first_answer, "selectedIndex": True}, self.headers)[0], 400)
        with ThreadPoolExecutor(max_workers=2) as executor:
            answers = list(executor.map(lambda _: self.request(build_opener(), challenge, first_answer, self.headers), range(2)))
        self.assertEqual(answers[0], answers[1])
        status, result = answers[0]
        self.assertEqual(status, 200)
        self.assertTrue(result["correct"])
        self.assertEqual(result["earnedMinutes"], 10)
        self.assertEqual(result["rewardTime"]["balanceMillis"], 10 * minute)
        self.assertEqual(result["rewardTime"]["usedTodayMillis"], 0)
        self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["question"]["id"], second["id"])
        status, wrong = self.request(self.device, challenge, second_answer, self.headers)
        self.assertEqual(status, 200)
        self.assertFalse(wrong["correct"])
        self.assertEqual(wrong["earnedMinutes"], 0)
        self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["rewardChallenge"]["remainingAttempts"], 0)
        next_day = (server.datetime.fromtimestamp(answered_at / 1000, server.timezone.utc) + server.timedelta(days=1)).date().isoformat()
        with patch.object(server.time, "time", return_value=answered_at / 1000 + 86_400):
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {**report, "date": next_day}, self.headers)[0], 200)
            status, retried = self.request(self.device, challenge, {
                **second_answer, "selectedIndex": 0, "attemptId": "second-correct", "answeredAt": answered_at + 2000,
            }, self.headers)
            self.assertEqual(self.request(self.device, challenge, headers=self.headers),
                             (200, {"eligible": True, "question": None, "rewardChallenge": {"date": next_day, "remainingAttempts": 1}}))
        self.assertEqual(status, 200)
        self.assertTrue(retried["correct"])
        self.assertEqual(retried["earnedMinutes"], 45)
        self.assertEqual(retried["rewardTime"]["balanceMillis"], 55 * minute)
        self.assertEqual(self.request(self.alice, bank + "/" + first["id"], method="DELETE")[0], 404)
        server.STORE = server.StateStore(self.path)
        status, repeated = self.request(self.device, challenge, first_answer, self.headers)
        self.assertEqual(status, 200)
        self.assertTrue(repeated["correct"])
        self.assertEqual(repeated["earnedMinutes"], 10)
        self.assertEqual(repeated["rewardTime"]["balanceMillis"], 55 * minute)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices")[1]["devices"][0]["rewardBalanceMillis"], 55 * minute)

        records = self.request(self.alice, f"/api/v1/admin/records?date={local_day}")[1]["answers"]
        self.assertEqual([item["attemptId"] for item in records], ["second-correct", "second-wrong", "first-correct"])
        self.assertEqual([item["correct"] for item in records], [True, False, True])
        self.assertEqual([item["answeredAt"] for item in records], [answered_at + 2000, answered_at + 1000, answered_at])
        self.assertTrue(all(item["questionKind"] == "reward" and item["questionType"] == "choice" for item in records))
        self.assertTrue(all(item["restorable"] and not item["inQuestionBank"] for item in records))
        self.assertEqual(records[1]["selectedAnswer"], "122")
        self.assertEqual(records[1]["earnedMinutes"], 0)
        self.assertEqual(records[0]["earnedMinutes"], 45)
        self.assertEqual(self.request(self.bob, f"/api/v1/admin/records?date={local_day}")[1]["answers"], [])

        restore = "/api/v1/admin/answers/restore"
        self.assertEqual(self.request(self.bob, restore, {"attemptId": "second-correct"})[0], 404)
        self.assertEqual(self.request(self.alice, restore, {"attemptId": "second-wrong"})[0], 200)
        state = self.request(self.alice, "/api/v1/admin/state")[1]
        self.assertEqual(state["rewardQuestions"], [second])
        self.assertNotIn(second["id"], {item["id"] for item in state["questions"]})
        with patch.object(server.time, "time", return_value=answered_at / 1000 + 86_400):
            self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["question"]["id"], second["id"])
        repeated = self.request(self.alice, restore, {"attemptId": "second-correct"})
        self.assertEqual(repeated, (200, {"restored": False, "inQuestionBank": True}))

    def test_advanced_daily_limit_counts_answers_not_retries_and_resets_at_local_midnight(self):
        self.bind(self.alice)
        challenge = "/api/v1/device/reward-challenge"
        now = server.datetime(2026, 11, 26, 15, 59, tzinfo=server.timezone.utc)
        local_day = "2026-11-26"
        body = {"questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
                "attemptId": "daily-first", "answeredAt": 0}
        with patch.object(server.time, "time", return_value=now.timestamp()):
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                "deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 480,
                "usageMillis": 60 * server.MILLIS_PER_MINUTE,
            }, self.headers)[0], 200)
            expected = {"date": local_day, "remainingAttempts": 2}
            self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["rewardChallenge"], expected)
            self.assertEqual(self.request(self.device, "/api/v1/device/state?date=1970-01-01", headers=self.headers)[1]["rewardChallenge"], expected)
            status, first = self.request(self.device, challenge, body, self.headers)
            self.assertEqual(status, 200)
            self.assertFalse(first["correct"])
            self.assertEqual(first["rewardChallenge"], {"date": local_day, "remainingAttempts": 1})
            self.assertEqual(self.request(self.device, challenge, body, self.headers), (200, first))
            status, second = self.request(self.device, challenge, {
                **body, "attemptId": "daily-second", "numericAnswer": "495",
            }, self.headers)
            self.assertEqual(status, 200)
            self.assertTrue(second["correct"])
            exhausted = {"date": local_day, "remainingAttempts": 0}
            self.assertEqual(second["rewardChallenge"], exhausted)
            self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": "daily-third"}, self.headers)[0], 429)
            offer = self.request(self.device, challenge, headers=self.headers)[1]
            self.assertEqual(offer, {"eligible": False, "question": None,
                                     "errorCode": "daily_challenge_limit_reached", "rewardChallenge": exhausted})
            self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": "daily-second", "numericAnswer": "495"}, self.headers), (200, second))
            server.STORE.for_account(2).queue_usage_reset("test-tv")
            server.STORE = server.StateStore(self.path)
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["rewardChallenge"], exhausted)
            records = server.STORE.for_account(2).load()["answerRecords"]
            self.assertEqual(len(records), 2)
            self.assertEqual({item["challengeDate"] for item in records}, {local_day})
            self.assertEqual({item["date"] for item in records}, {"1970-01-01"})
        self.assertEqual(self.request(self.alice, "/api/v1/admin/answers/restore", {"attemptId": "daily-second"})[0], 200)
        with patch.object(server.time, "time", return_value=now.timestamp() + 120):
            expected = {"date": "2026-11-27", "remainingAttempts": 2}
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["rewardChallenge"], expected)
            self.assertEqual(self.request(self.device, challenge, body, self.headers)[1]["rewardChallenge"], expected)
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                "deviceId": "test-tv", "date": "2026-11-27", "utcOffsetMinutes": 480,
                "usageMillis": 60 * server.MILLIS_PER_MINUTE,
            }, self.headers)[0], 200)
            self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": "tomorrow-first"}, self.headers)[1]["rewardChallenge"],
                             {"date": "2026-11-27", "remainingAttempts": 1})

    def test_advanced_daily_limit_serializes_distinct_attempts_and_is_device_scoped(self):
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        challenge = "/api/v1/device/reward-challenge"
        now = server.datetime(2026, 11, 26, 8, 0, tzinfo=server.timezone.utc)
        with patch.object(server.time, "time", return_value=now.timestamp()):
            for headers in (first_headers, self.headers):
                self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                    "deviceId": headers["X-Device-Id"], "date": "2026-11-26", "utcOffsetMinutes": 480,
                    "usageMillis": 60 * server.MILLIS_PER_MINUTE,
                }, headers)[0], 200)
            def submit(index):
                return self.request(build_opener(), challenge, {
                    "questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
                    "attemptId": f"concurrent-{index}", "answeredAt": int(now.timestamp() * 1000),
                }, first_headers)
            with ThreadPoolExecutor(max_workers=3) as executor:
                results = list(executor.map(submit, range(3)))
            self.assertEqual(sorted(status for status, _ in results), [200, 200, 429])
            self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["rewardChallenge"],
                             {"date": "2026-11-26", "remainingAttempts": 2})
            self.assertEqual(len(server.STORE.for_account(2).load()["answerRecords"]), 2)

    def test_advanced_attempt_reset_restores_two_attempts_without_losing_history_or_rewards(self):
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        challenge = "/api/v1/device/reward-challenge"
        reset_path = "/api/v1/admin/reward-challenges/reset"
        now = server.datetime(2026, 11, 26, 8, 0, tzinfo=server.timezone.utc)
        body = {"questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
                "attemptId": "before-reset-wrong", "answeredAt": int(now.timestamp() * 1000)}
        with patch.object(server.time, "time", return_value=now.timestamp()):
            for headers in (first_headers, self.headers):
                self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                    "deviceId": headers["X-Device-Id"], "date": "2026-11-26", "utcOffsetMinutes": 480,
                    "usageMillis": 60 * server.MILLIS_PER_MINUTE,
                }, headers)[0], 200)
            for index in range(2):
                self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": f"other-{index}"}, self.headers)[0], 200)
            self.assertEqual(self.request(self.device, challenge, body, first_headers)[0], 200)
            correct_body = {**body, "numericAnswer": "495", "attemptId": "before-reset-correct"}
            self.assertTrue(self.request(self.device, challenge, correct_body, first_headers)[1]["correct"])
            self.assertEqual(self.request(self.alice, "/api/v1/admin/answers/restore", {"attemptId": correct_body["attemptId"]})[0], 200)
            before = server.STORE.for_account(2).load()
            status, reset = self.request(self.alice, reset_path, {"deviceId": "test-tv"})
            self.assertEqual((status, reset), (200, {"deviceId": "test-tv", "date": "2026-11-26", "remainingAttempts": 2, "resetVersion": 1}))
            after = server.STORE.for_account(2).load()
            after["devices"]["test-tv"].pop("rewardChallengeReset")
            self.assertEqual(after, before)
            server.STORE = server.StateStore(self.path)
            expected = {"date": "2026-11-26", "remainingAttempts": 2, "resetVersion": 1}
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=first_headers)[1]["rewardChallenge"], expected)
            self.assertEqual(self.request(self.device, challenge, headers=first_headers)[1]["rewardChallenge"], expected)
            old_retry = self.request(self.device, challenge, correct_body, first_headers)[1]
            self.assertTrue(old_retry["correct"])
            self.assertEqual(old_retry["earnedMinutes"], 10)
            self.assertEqual(old_retry["rewardTime"]["balanceMillis"], 10 * server.MILLIS_PER_MINUTE)
            self.assertEqual(old_retry["rewardChallenge"], expected)
            self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["rewardChallenge"],
                             {"date": "2026-11-26", "remainingAttempts": 0})
            for index in range(2):
                status, result = self.request(self.device, challenge, {**body, "attemptId": f"after-reset-{index}"}, first_headers)
                self.assertEqual(status, 200)
                self.assertEqual(result["rewardChallenge"], {**expected, "remainingAttempts": 1 - index})
            self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": "after-reset-third"}, first_headers)[0], 429)
            state = server.STORE.for_account(2).load()
            self.assertEqual(len(state["answerRecords"]), 6)
            self.assertEqual([record.get("challengeResetVersion") for record in state["answerRecords"][-2:]], [1, 1])
            self.assertEqual(state["dailyUsage"], before["dailyUsage"])
            self.assertEqual(state["devices"]["test-tv"]["rewardGrantedMillis"], 10 * server.MILLIS_PER_MINUTE)
            self.assertEqual(self.request(self.alice, reset_path, {"deviceId": "test-tv"})[1]["resetVersion"], 2)
            self.assertEqual(self.request(self.device, challenge, headers=first_headers)[1]["rewardChallenge"],
                             {**expected, "resetVersion": 2})
            self.assertEqual(server.STORE.for_account(2).load()["answerRecords"], state["answerRecords"])

    def test_advanced_attempt_reset_requires_owner_and_reported_timezone(self):
        path = "/api/v1/admin/reward-challenges/reset"
        self.assertEqual(self.request(self.device, path, {"deviceId": "test-tv"})[0], 401)
        self.bind(self.alice)
        self.assertEqual(self.request(self.bob, path, {"deviceId": "test-tv"})[0], 404)
        for device_id in (None, "", 1, [], "x" * 81):
            self.assertEqual(self.request(self.alice, path, {"deviceId": device_id})[0], 400)
        self.assertEqual(self.request(self.alice, path, {"deviceId": "missing-tv"})[0], 404)
        self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv"})[0], 400)
        store = server.STORE.for_account(2)
        for offset in (None, True, 841, -841, "480"):
            state = store.load()
            state["devices"].setdefault("test-tv", {})["utcOffsetMinutes"] = offset
            store.save(state)
            self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv"})[0], 400)
        self.assertNotIn("rewardChallengeReset", store.load()["devices"]["test-tv"])
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 200)
        self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv"})[0], 404)

    def test_advanced_attempt_reset_uses_server_date_in_device_timezone(self):
        self.bind(self.alice)
        path = "/api/v1/admin/reward-challenges/reset"
        now = server.datetime(2026, 11, 26, 16, 1, tzinfo=server.timezone.utc)
        store = server.STORE.for_account(2)
        state = store.load()
        state["devices"].setdefault("test-tv", {})["utcOffsetMinutes"] = 480
        store.save(state)
        with patch.object(server.time, "time", return_value=now.timestamp()):
            self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv", "date": "1970-01-01"}),
                             (200, {"deviceId": "test-tv", "date": "2026-11-27", "remainingAttempts": 2, "resetVersion": 1}))
        with patch.object(server.time, "time", return_value=now.timestamp() + 86_400):
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["rewardChallenge"],
                             {"date": "2026-11-28", "remainingAttempts": 2})
            self.assertEqual(self.request(self.alice, "/api/v1/admin/login", {"username": "alice", "password": "long-password-123"})[0], 200)
            self.assertEqual(self.request(self.alice, path, {"deviceId": "test-tv"})[1],
                             {"deviceId": "test-tv", "date": "2026-11-28", "remainingAttempts": 2, "resetVersion": 2})
        self.assertEqual(server.STORE.for_account(3).load()["devices"], {})

    def test_answered_advanced_history_is_unique_and_survives_removal(self):
        path = "/api/v1/admin/poems"
        self.assertEqual(self.request(self.device, path)[0], 401)
        self.bind(self.alice)
        history = self.request(self.alice, path)[1]
        poems = history["poems"]
        self.assertEqual(history["advancedQuestions"], [])
        seeded = self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][0]
        _, choice = self.request(self.alice, "/api/v1/admin/reward-questions", {
            "prompt": "Which number is prime?", "category": "数学", "options": ["2", "4", "6", "8"], "answer": "2",
        })
        now = server.datetime.now(server.timezone.utc)
        self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": now.date().isoformat(), "utcOffsetMinutes": 0,
            "usageMillis": 60 * server.MILLIS_PER_MINUTE,
        }, self.headers)
        answered_at = int(now.timestamp() * 1000)
        challenge = "/api/v1/device/reward-challenge"
        for attempt_id, question, answer, offset in (
            ("history-numeric-wrong", seeded, {"numericAnswer": "0"}, 0),
            ("history-choice-wrong", choice, {"selectedIndex": 1}, 3000),
            ("history-numeric-correct", seeded, {"numericAnswer": "495"}, 2000),
        ):
            receipt_time = now + server.timedelta(days=int(attempt_id == "history-numeric-correct"))
            with patch.object(server.time, "time", return_value=receipt_time.timestamp()):
                self.request(self.device, "/api/v1/device/usage", {
                    "deviceId": "test-tv", "date": receipt_time.date().isoformat(), "utcOffsetMinutes": 0,
                    "usageMillis": 60 * server.MILLIS_PER_MINUTE,
                }, self.headers)
                self.assertEqual(self.request(self.device, challenge, {
                    "questionId": question["id"], "attemptId": attempt_id, "answeredAt": answered_at + offset, **answer,
                }, self.headers)[0], 200)
        self.assertNotIn(seeded["id"], {item["id"] for item in self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"]})
        server.STORE = server.StateStore(self.path)
        devices = [{"deviceId": "test-tv", "name": "Living room TV"}]
        self.assertEqual(self.request(self.alice, path)[1], {
            "poems": poems, "advancedQuestions": [{**choice, "answeredDevices": devices}, {**seeded, "answeredDevices": devices}],
        })
        self.assertEqual(self.request(self.bob, path)[1]["advancedQuestions"], [])
        self.assertEqual(self.request(self.admin, path)[1]["advancedQuestions"], [])

    def test_answered_advanced_history_uses_the_latest_snapshot_without_polluting_poems(self):
        store = server.STORE.for_account(2)
        state = store.load()
        original_poems = store.poem_history()
        question = {**state["rewardQuestions"][0], "prompt": "《不是古诗词》的数列问题"}
        question.pop("category", None)
        latest = {**question, "prompt": "最新题目内容", "numericAnswer": "0"}
        state["answerRecords"] = [
            {"questionKind": "reward", "questionId": question["id"], "question": latest, "answeredAt": 2000},
            {"questionKind": "reward", "questionId": question["id"], "question": question, "answeredAt": 1000},
        ]
        store.save(state)
        _, history = self.request(self.alice, "/api/v1/admin/poems")
        self.assertEqual(history["poems"], original_poems)
        self.assertEqual(history["advancedQuestions"], [{**latest, "answeredDevices": []}])

    def test_regular_question_edits_preserve_identity_poem_priority_and_answer_history(self):
        self.bind(self.alice)
        poem = {"title": "相思", "author": "王维", "content": "红豆生南国，春来发几枝。"}
        _, original = self.request(self.alice, "/api/v1/admin/questions", {
            "prompt": "编辑前的题目", "category": "古诗词", "difficulty": "中", "priority": True,
            "options": ["甲", "乙", "丙", "丁"], "answer": "甲", "poem": poem,
        })
        self.assertEqual(self.request(self.device, "/api/v1/device/answers", {
            "deviceId": "test-tv", "attemptId": "before-question-edit", "date": "2026-11-25", "answeredAt": 1000,
            "questionId": original["id"], "selectedAnswer": "乙", "question": original,
        }, self.headers)[0], 200)
        store = server.STORE.for_account(2)
        before = store.load()
        endpoint = "/api/v1/admin/questions/" + original["id"]
        changes = {"id": "replacement-id", "prompt": "编辑后的题目", "category": "古诗词", "difficulty": "高",
                   "options": ["一", "二", "三", "四"], "answer": "四"}
        self.assertEqual(self.request(self.device, endpoint, changes)[0], 401)
        self.assertEqual(self.request(self.bob, endpoint, changes)[0], 404)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/questions/missing", changes)[0], 404)
        self.assertEqual(self.request(self.alice, endpoint, {**changes, "answer": "无效答案"})[0], 400)
        self.assertEqual(store.load(), before)
        status, result = self.request(self.alice, endpoint, changes)
        self.assertEqual(status, 200)
        expected = {**changes, "id": original["id"], "priority": True, "poem": poem}
        self.assertEqual(result, {"question": expected})
        server.STORE = server.StateStore(self.path)
        persisted = server.STORE.for_account(2).load()
        self.assertEqual([item["id"] for item in persisted["questions"]], [item["id"] for item in before["questions"]])
        self.assertEqual(persisted["questions"][-1], expected)
        self.assertEqual(persisted["answerRecords"], before["answerRecords"])
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["questions"][-1], expected)

    def test_advanced_question_edits_switch_answer_type_without_changing_past_attempts(self):
        self.bind(self.alice)
        bank = "/api/v1/admin/reward-questions"
        poem = {"title": "相思", "author": "王维", "content": "红豆生南国，春来发几枝。"}
        _, original = self.request(self.alice, bank, {
            "prompt": "Original question", "category": "数学", "options": ["A", "B", "C", "D"], "answer": "A", "poem": poem,
        })
        now = server.datetime.now(server.timezone.utc)
        self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": now.date().isoformat(), "utcOffsetMinutes": 0,
            "usageMillis": 60 * server.MILLIS_PER_MINUTE,
        }, self.headers)
        challenge = "/api/v1/device/reward-challenge"
        self.assertEqual(self.request(self.device, challenge, {
            "questionId": original["id"], "selectedIndex": 1, "attemptId": "before-advanced-edit",
            "answeredAt": int(now.timestamp() * 1000),
        }, self.headers)[0], 200)
        endpoint = bank + "/" + original["id"]
        numeric = {"prompt": "Numeric question", "type": "numeric", "category": "数学", "difficulty": "高",
                   "numericAnswer": "+0000", "rewardMinutes": 15}
        self.assertEqual(self.request(self.bob, endpoint, numeric)[0], 404)
        self.assertEqual(self.request(self.alice, endpoint, {**numeric, "numericAnswer": ""})[0], 400)
        status, result = self.request(self.alice, endpoint, numeric)
        self.assertEqual(status, 200)
        self.assertEqual(result["question"], {**numeric, "id": original["id"], "poem": poem})
        choice = {"prompt": "New choice question", "type": "choice", "category": "科学", "difficulty": "低",
                  "options": ["W", "X", "Y", "Z"], "answer": "X", "rewardMinutes": 6}
        status, result = self.request(self.alice, endpoint, choice)
        self.assertEqual(status, 200)
        self.assertNotIn("numericAnswer", result["question"])
        self.assertEqual(result["question"]["id"], original["id"])
        self.assertEqual(result["question"]["poem"], poem)
        self.assertEqual(server.STORE.for_account(2).load()["answerRecords"][0]["question"], original)
        self.assertEqual(self.request(self.device, challenge, {
            "questionId": original["id"], "selectedIndex": 1, "attemptId": "after-advanced-edit",
            "answeredAt": int(now.timestamp() * 1000) + 1000,
        }, self.headers)[1]["earnedMinutes"], 6)
        self.assertEqual(self.request(self.alice, endpoint, choice)[0], 404)
        self.assertEqual(server.STORE.for_account(2).load()["answerRecords"][0]["question"], original)

    def test_cached_answers_after_edit_use_the_shown_snapshot_without_deleting_new_content(self):
        self.bind(self.alice)
        original, priority_only = self.request(self.alice, "/api/v1/admin/state")[1]["questions"][:2]
        changes = {**original, "prompt": "Updated while the TV was offline", "options": ["W", "X", "Y", "Z"], "answer": "W"}
        self.assertEqual(self.request(self.alice, "/api/v1/admin/questions/" + original["id"], changes)[0], 200)
        body = {"deviceId": "test-tv", "attemptId": "cached-before-edit", "date": "2026-11-25", "answeredAt": 1000,
                "questionId": original["id"], "selectedAnswer": original["answer"], "question": original}
        for _ in range(2):
            self.assertEqual(self.request(self.device, "/api/v1/device/answers", body, self.headers),
                             (200, {"accepted": True, "correct": True, "removed": False}))
        state = server.STORE.for_account(2).load()
        self.assertEqual(state["questions"][0], changes)
        self.assertEqual(state["answerRecords"][0]["question"], original)
        self.assertEqual(len(state["answerRecords"]), 1)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/questions/" + priority_only["id"] + "/priority", {"priority": True})[0], 200)
        status, result = self.request(self.device, "/api/v1/device/answers", {
            **body, "attemptId": "cached-priority-only", "questionId": priority_only["id"],
            "selectedAnswer": priority_only["answer"], "question": priority_only,
        }, self.headers)
        self.assertEqual((status, result), (200, {"accepted": True, "correct": True, "removed": True}))

    def test_answered_advanced_history_lists_each_device_once_after_unbinding(self):
        self.bind(self.alice)
        store = server.STORE.for_account(2)
        state = store.load()
        question = state["rewardQuestions"][0]
        latest = {**question, "prompt": "Latest snapshot"}
        state["devices"]["second-tv"] = {"name": ""}
        state["answerRecords"] = [
            {"questionKind": "reward", "questionId": question["id"], "question": question,
             "deviceId": "test-tv", "answeredAt": 1000},
            {"questionKind": "reward", "questionId": question["id"], "question": question,
             "deviceId": "second-tv", "answeredAt": 2000},
            {"questionKind": "reward", "questionId": question["id"], "question": latest,
             "deviceId": "test-tv", "answeredAt": 3000},
        ]
        store.save(state)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 200)
        server.STORE = server.StateStore(self.path)
        history = self.request(self.alice, "/api/v1/admin/poems")[1]["advancedQuestions"]
        self.assertEqual(history, [{**latest, "answeredDevices": [
            {"deviceId": "test-tv", "name": "Living room TV"}, {"deviceId": "second-tv", "name": "second-tv"},
        ]}])
        self.assertEqual(self.request(self.bob, "/api/v1/admin/poems")[1]["advancedQuestions"], [])
        self.assertEqual(server.STORE.for_account(2).load()["answerRecords"], state["answerRecords"])

    def test_advanced_numeric_retries_are_recorded_and_transport_retries_are_idempotent(self):
        bank = "/api/v1/admin/reward-questions"
        challenge = "/api/v1/device/reward-challenge"
        minute = server.MILLIS_PER_MINUTE
        self.bind(self.alice)
        prompt = "有一列数：2, 5, 9, 14, 20, 27, 35, ...，这列数的第 30 个是多少？"
        seeded = self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][0]
        self.assertEqual(seeded["id"], server.DEFAULT_REWARD_QUESTION["id"])
        self.assertEqual(seeded["prompt"], prompt)
        self.assertEqual(seeded["type"], "numeric")
        self.assertEqual(seeded["numericAnswer"], "495")

        status, created = self.request(self.alice, bank, {
            "prompt": "Add these values", "type": "numeric", "numericAnswer": "+00042", "rewardMinutes": 7,
        })
        self.assertEqual(status, 201)
        self.assertEqual(created["numericAnswer"], "+00042")
        for invalid in (42, True, "", " ", "x" * 121):
            self.assertEqual(self.request(self.alice, bank, {
                "prompt": "Bad numeric answer", "type": "numeric", "numericAnswer": invalid,
            })[0], 400)

        local_day = server.datetime.now(server.timezone.utc).date().isoformat()
        self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 0,
            "usageMillis": 60 * minute,
        }, self.headers)[0], 200)
        status, offer = self.request(self.device, challenge, headers=self.headers)
        self.assertEqual(status, 200)
        self.assertEqual(offer["question"], {
            "id": seeded["id"], "prompt": prompt, "type": "numeric", "rewardMinutes": 10,
        })
        self.assertNotIn("numericAnswer", offer["question"])
        self.assertNotIn("options", offer["question"])
        for invalid in (495, True, "495.0", "x" * 121):
            self.assertEqual(self.request(self.device, challenge, {
                "questionId": seeded["id"], "numericAnswer": invalid,
            }, self.headers)[0], 400)

        answered_at = int(server.time.time() * 1000)
        wrong_body = {"questionId": seeded["id"], "numericAnswer": "0", "attemptId": "numeric-wrong", "answeredAt": answered_at}
        status, wrong = self.request(self.device, challenge, wrong_body, self.headers)
        self.assertEqual(status, 200)
        self.assertFalse(wrong["correct"])
        self.assertEqual(wrong["earnedMinutes"], 0)
        self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["question"]["id"], seeded["id"])

        self.assertEqual(self.request(self.device, challenge, wrong_body, self.headers), (200, wrong))
        next_wrong = {**wrong_body, "attemptId": "numeric-wrong-again", "answeredAt": answered_at + 1000}
        self.assertEqual(self.request(self.device, challenge, next_wrong, self.headers)[0], 200)
        body = {"questionId": seeded["id"], "numericAnswer": "495", "attemptId": "numeric-correct", "answeredAt": answered_at + 2000}
        next_day = (server.datetime.fromtimestamp(answered_at / 1000, server.timezone.utc) + server.timedelta(days=1)).date().isoformat()
        with patch.object(server.time, "time", return_value=answered_at / 1000 + 86_400):
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                "deviceId": "test-tv", "date": next_day, "utcOffsetMinutes": 0, "usageMillis": 60 * minute,
            }, self.headers)[0], 200)
            with ThreadPoolExecutor(max_workers=2) as executor:
                answers = list(executor.map(lambda _: self.request(build_opener(), challenge, body, self.headers), range(2)))
            self.assertEqual(self.request(self.device, challenge, body, self.headers), answers[0])
            self.assertEqual(self.request(self.device, challenge, headers=self.headers)[1]["question"]["id"], created["id"])
        self.assertEqual(answers[0], answers[1])
        status, result = answers[0]
        self.assertEqual(status, 200)
        self.assertTrue(result["correct"])
        self.assertEqual(result["earnedMinutes"], 10)
        self.assertEqual(result["rewardTime"]["balanceMillis"], 10 * minute)
        records = self.request(self.alice, f"/api/v1/admin/records?date={local_day}")[1]["answers"]
        self.assertEqual([item["correct"] for item in records], [True, False, False])
        self.assertEqual([item["selectedAnswer"] for item in records], ["495", "0", "0"])
        self.assertTrue(all(item["questionType"] == "numeric" for item in records))
        self.assertEqual(self.request(self.alice, "/api/v1/admin/answers/restore", {"attemptId": body["attemptId"]})[0], 200)
        restored = self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][-1]
        self.assertEqual(restored, seeded)
        with patch.object(server.time, "time", return_value=answered_at / 1000 + 86_400):
            self.assertEqual(self.request(self.device, challenge, {
                **body, "attemptId": "numeric-restored-correct", "answeredAt": answered_at + 3000,
            }, self.headers)[1]["rewardTime"]["balanceMillis"], 20 * minute)

    def test_advanced_direct_answers_preserve_strings_through_edit_history_and_restore(self):
        self.bind(self.alice)
        bank = "/api/v1/admin/reward-questions"
        challenge = "/api/v1/device/reward-challenge"
        status, created = self.request(self.alice, bank, {
            "prompt": "Enter the three-character answer", "type": "numeric", "numericAnswer": " 0052 ", "rewardMinutes": 9,
        })
        self.assertEqual(status, 201)
        self.assertEqual(created["numericAnswer"], "0052")
        status, updated = self.request(self.alice, bank + "/" + created["id"], {**created, "numericAnswer": " 052 "})
        self.assertEqual(status, 200)
        self.assertEqual(updated["question"]["numericAnswer"], "052")
        server.STORE = server.StateStore(self.path)
        question = self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][-1]
        self.assertEqual(question["numericAnswer"], "052")
        now = server.datetime.now(server.timezone.utc)
        local_day = now.date().isoformat()
        answered_at = int(now.timestamp() * 1000)
        self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 0,
            "usageMillis": 60 * server.MILLIS_PER_MINUTE,
        }, self.headers)
        body = {"questionId": question["id"], "numericAnswer": "52", "attemptId": "string-wrong", "answeredAt": answered_at}
        status, wrong = self.request(self.device, challenge, body, self.headers)
        self.assertEqual(status, 200)
        self.assertFalse(wrong["correct"])
        self.assertEqual(wrong["earnedMinutes"], 0)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][-1], question)
        correct_body = {**body, "numericAnswer": " 052 ", "attemptId": "string-correct", "answeredAt": answered_at + 1000}
        status, correct = self.request(self.device, challenge, correct_body, self.headers)
        self.assertEqual(status, 200)
        self.assertTrue(correct["correct"])
        self.assertEqual(correct["earnedMinutes"], 9)
        self.assertEqual(correct["rewardChallenge"]["remainingAttempts"], 0)
        self.assertNotIn(question["id"], {item["id"] for item in self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"]})
        self.assertEqual(self.request(self.device, challenge, correct_body, self.headers), (200, correct))
        self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": "string-third"}, self.headers)[0], 429)
        records = self.request(self.alice, f"/api/v1/admin/records?date={local_day}")[1]["answers"]
        self.assertEqual([item["selectedAnswer"] for item in records], ["052", "52"])
        history = self.request(self.alice, "/api/v1/admin/poems")[1]["advancedQuestions"]
        self.assertEqual(history[0]["numericAnswer"], "052")
        self.assertEqual(self.request(self.alice, "/api/v1/admin/answers/restore", {"attemptId": "string-correct"})[0], 200)
        server.STORE = server.StateStore(self.path)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/state")[1]["rewardQuestions"][-1], question)

    def test_advanced_direct_answers_accept_text_and_reject_empty_or_non_string_values(self):
        for answer in ("052", "+00042", "4.2", "A52", "甲 乙", "x" * 120):
            with self.subTest(answer=answer):
                question = server.validate_reward_question({"prompt": "Text answer", "type": "numeric", "numericAnswer": answer})
                self.assertEqual(question["numericAnswer"], answer)
        for answer in (None, 52, True, [], {}, "", " ", "x" * 121):
            with self.subTest(answer=answer), self.assertRaises(ValueError):
                server.validate_reward_question({"prompt": "Text answer", "type": "numeric", "numericAnswer": answer})

    def test_advanced_attempt_validation_and_device_local_answer_date(self):
        self.bind(self.alice)
        challenge = "/api/v1/device/reward-challenge"
        answered_at = int(server.datetime(2026, 11, 25, 17, 30, tzinfo=server.timezone.utc).timestamp() * 1000)
        local_day = "2026-11-26"
        with patch.object(server.time, "time", return_value=answered_at / 1000):
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                "deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 480,
                "usageMillis": 60 * server.MILLIS_PER_MINUTE,
            }, self.headers)[0], 200)
            body = {
                "questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
                "attemptId": "local-date-attempt", "answeredAt": answered_at,
            }
            for invalid in (None, "", True, "x" * 81):
                self.assertEqual(self.request(self.device, challenge, {**body, "attemptId": invalid}, self.headers)[0], 400)
            for invalid in (None, True, -1, 1.5, 253_402_214_400_000):
                self.assertEqual(self.request(self.device, challenge, {**body, "answeredAt": invalid}, self.headers)[0], 400)
            self.assertEqual(self.request(self.device, challenge, body, self.headers)[0], 200)
        answer = self.request(self.alice, f"/api/v1/admin/records?date={local_day}")[1]["answers"][0]
        self.assertEqual(answer["date"], local_day)
        self.assertEqual(answer["answeredAt"], answered_at)
        self.assertFalse(answer["correct"])
        self.assertTrue(answer["inQuestionBank"])
        self.assertEqual(self.request(self.alice, "/api/v1/admin/records?date=2026-11-25")[1]["answers"], [])

    def test_advanced_and_regular_attempt_ids_do_not_hide_each_others_results(self):
        self.bind(self.alice)
        local_day = server.datetime.now(server.timezone.utc).date().isoformat()
        answered_at = int(server.time.time() * 1000)
        self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": local_day, "utcOffsetMinutes": 0,
            "usageMillis": 60 * server.MILLIS_PER_MINUTE,
        }, self.headers)
        question = self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["questions"][0]
        regular = {
            "deviceId": "test-tv", "date": local_day, "attemptId": "shared-attempt", "answeredAt": answered_at,
            "questionId": question["id"], "selectedAnswer": question["answer"],
        }
        advanced = {
            "questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
            "attemptId": "shared-attempt", "answeredAt": answered_at,
        }
        self.assertFalse(self.request(self.device, "/api/v1/device/reward-challenge", advanced, self.headers)[1]["correct"])
        self.assertTrue(self.request(self.device, "/api/v1/device/answers", regular, self.headers)[1]["correct"])
        self.assertFalse(self.request(self.device, "/api/v1/device/reward-challenge", advanced, self.headers)[1]["correct"])
        self.assertEqual(len(self.request(self.alice, f"/api/v1/admin/records?date={local_day}")[1]["answers"]), 2)

    def test_default_reward_question_migration_runs_once_and_preserves_deletion(self):
        store = server.STORE.for_account(1)
        state = store.load()
        self.assertEqual(state["rewardQuestions"][0]["id"], server.DEFAULT_REWARD_QUESTION["id"])
        state["rewardQuestions"] = []
        store.save(state)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("DELETE FROM state_migrations WHERE name = 'numeric_reward_questions'")
            db.commit()
        self.assertEqual(store.load()["rewardQuestions"][0]["id"], server.DEFAULT_REWARD_QUESTION["id"])
        state = store.load()
        state["rewardQuestions"] = []
        store.save(state)
        self.assertEqual(store.load()["rewardQuestions"], [])

    def test_reward_limit_defaults_can_be_disabled_and_validates_integer_bounds(self):
        self.assertEqual(self.request(self.alice, "/api/v1/admin/state")[1]["settings"]["dailyRewardLimitMinutes"], 30)
        self.bind(self.alice)
        for invalid in (-1, 1441, True, "30", 1.5):
            self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", {
                "deviceId": "test-tv", "settings": {"dailyRewardLimitMinutes": invalid},
            })[0], 400)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", {
            "deviceId": "test-tv", "settings": {"dailyRewardLimitMinutes": 0},
        })[0], 200)
        _, state = self.request(self.device, "/api/v1/device/state", headers=self.headers)
        self.assertEqual(state["settings"]["dailyRewardLimitMinutes"], 0)

    def test_accounts_isolate_settings_questions_records_commands_and_devices(self):
        self.bind(self.alice)
        _, state = self.request(self.alice, "/api/v1/admin/state")
        settings = {**state["settings"], "questionIntervalMinutes": 17}
        self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", {"deviceId": "test-tv", "settings": settings})[0], 200)
        question = {**state["questions"][0], "id": None, "prompt": "Alice only question", "category": "古诗词",
                    "poem": {"title": "独立作品", "author": "甲", "content": "甲乙丙丁"}}
        _, added = self.request(self.alice, "/api/v1/admin/questions", question)
        self.assertEqual(self.request(self.bob, f"/api/v1/admin/questions/{added['id']}", method="DELETE")[0], 404)
        self.request(self.alice, "/api/v1/admin/password/reset", {"deviceId": "test-tv"})
        status, synced = self.request(self.device, "/api/v1/device/state?deviceId=test-tv", headers=self.headers)
        self.assertEqual(status, 200)
        self.assertEqual(synced["settings"], settings)
        self.assertIsNotNone(synced["passwordReset"])
        self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "test-tv", "date": "2026-11-19", "usageMillis": 10000, "utcOffsetMinutes": 480,
        }, self.headers)[0], 200)
        self.assertEqual(self.request(self.device, "/api/v1/device/answers", {
            "deviceId": "test-tv", "attemptId": "alice-answer", "date": "2026-11-19", "answeredAt": 1,
            "questionId": added["id"], "selectedAnswer": added["answer"], "question": added,
        }, self.headers)[0], 200)
        self.assertEqual(self.request(self.bob, "/api/v1/admin/usage/reset", {"deviceId": "test-tv"})[0], 400)
        self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
            "deviceId": "another-tv", "date": "2026-11-19", "usageMillis": 10000,
        }, self.headers)[0], 400)
        self.assertEqual(self.request(self.device, "/api/v1/device/state?deviceId=another-tv", headers=self.headers)[0], 403)
        for client in (self.bob, self.admin):
            _, other = self.request(client, "/api/v1/admin/state")
            self.assertEqual(other["settings"]["questionIntervalMinutes"], 10)
            self.assertEqual(len(other["questions"]), 50)
            self.assertIsNone(other["passwordReset"])
            self.assertEqual(self.request(client, "/api/v1/admin/devices")[1]["devices"], [])
            self.assertEqual(self.request(client, "/api/v1/admin/records?date=2026-11-19")[1]["usage"], [])
            self.assertEqual(self.request(client, "/api/v1/admin/records?date=2026-11-19")[1]["answers"], [])
            self.assertNotIn({"title": "独立作品", "author": "甲"}, self.request(client, "/api/v1/admin/poems")[1]["poems"])
        persisted = server.StateStore(self.path).for_account(2).load()
        self.assertEqual(len(persisted["questions"]), 50)
        self.assertEqual(persisted["answerRecords"][0]["attemptId"], "alice-answer")

    def test_unbind_rebind_revokes_old_scope_but_preserves_history(self):
        self.bind(self.alice)
        self.request(self.device, "/api/v1/device/usage", {"deviceId": "test-tv", "date": "2026-11-19", "usageMillis": 1000}, self.headers)
        old_headers = dict(self.headers)
        self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=old_headers)[0], 401)
        self.bind(self.bob)
        self.assertNotEqual(old_headers["X-Binding-Id"], self.headers["X-Binding-Id"])
        self.assertEqual(self.request(self.device, "/api/v1/device/usage", {"deviceId": "test-tv"}, old_headers)[0], 403)
        self.assertEqual(len(self.request(self.alice, "/api/v1/admin/records?date=2026-11-19")[1]["usage"]), 1)
        self.assertEqual(self.request(self.bob, "/api/v1/admin/records?date=2026-11-19")[1]["usage"], [])

    def test_password_change_revokes_only_own_sessions(self):
        other_alice = build_opener(HTTPCookieProcessor(CookieJar()))
        self.request(other_alice, "/api/v1/admin/login", {"username": "alice", "password": "long-password-123"})
        self.assertEqual(self.request(self.alice, "/api/v1/admin/password/change", {
            "currentPassword": "long-password-123", "newPassword": "alice-new-password", "confirmPassword": "alice-new-password",
        })[0], 200)
        self.assertEqual(self.request(self.bob, "/api/v1/admin/state")[0], 200)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/state")[0], 200)
        self.assertEqual(self.request(other_alice, "/api/v1/admin/state")[0], 401)

    def test_reinstalled_tv_needs_owner_unbind_before_new_credentials_can_enroll(self):
        self.bind(self.alice)
        old_headers = dict(self.headers)
        self.headers["Authorization"] = "Bearer " + secrets.token_urlsafe(32)
        status, error = self.request(self.device, "/api/v1/device/pairing", {}, self.headers)
        self.assertEqual(status, 409)
        self.assertEqual(error["errorCode"], "device_already_bound")
        self.assertEqual(self.request(self.device, "/api/v1/device/binding", headers=old_headers)[0], 200)
        self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})
        self.bind(self.bob)
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=old_headers)[0], 401)

    def test_binding_code_attempts_are_limited(self):
        for _ in range(6):
            self.request(self.alice, "/api/v1/admin/devices/preview", {"code": "invalid"})
        code = self.pairing()["code"]
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/preview", {"code": code})[0], 400)

    def test_concurrent_claims_have_exactly_one_owner(self):
        code = self.pairing()["code"]
        def claim(client):
            return self.request(client, "/api/v1/admin/devices/bind", {"code": code, "deviceId": "test-tv"})[0]
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(claim, (self.alice, self.bob)))
        self.assertEqual(sorted(results), [200, 400])
        owners = []
        for client in (self.alice, self.bob):
            owners.extend(device for device in self.request(client, "/api/v1/admin/devices")[1]["devices"] if device["bound"])
        self.assertEqual(len(owners), 1)

    def test_existing_admin_credentials_and_data_are_preserved(self):
        path = Path(self.temp.name) / "legacy.sqlite3"
        store = server.StateStore(path)
        state = store.load()
        state["settings"]["questionIntervalMinutes"] = 23
        store.save(state)
        salt = secrets.token_bytes(16).hex()
        saved_hash = server.AdminAuth._digest("original-password", salt, server.PASSWORD_ITERATIONS)
        with closing(sqlite3.connect(path)) as connection, connection:
            connection.execute("UPDATE admin_account SET username = ?, salt = ?, password_hash = ? WHERE id = 1",
                               ("original-parent", salt, saved_hash))
        auth = server.AdminAuth(path)
        status, session = auth.login("original-parent", "original-password")
        self.assertEqual(status, 200)
        self.assertEqual(auth.account_id(session), 1)
        self.assertEqual(store.load(), state)
        self.assertEqual(auth.login("admin", "admin")[0], 401)

    def test_one_account_can_bind_multiple_televisions(self):
        store = server.STORE.for_account(2)
        legacy = store.load()
        legacy["settings"]["questionIntervalMinutes"] = 23
        store.save(legacy)
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        for headers in (first_headers, self.headers):
            device_id = headers["X-Device-Id"]
            status, result = self.request(self.alice, f"/api/v1/admin/settings?deviceId={device_id}")
            self.assertEqual((status, result), (200, {"deviceId": device_id, "settings": legacy["settings"]}))

        first_settings = {**legacy["settings"], "wrongLockMinutes": 7, "syncIntervalMinutes": 2,
                          "dailyRewardLimitMinutes": 15, "weeklyDailyLimitMinutes": {str(day): 20 for day in range(1, 8)},
                          "usageTimeWindows": {str(day): [{"start": "08:00", "end": "20:00"}] for day in range(1, 8)}}
        status, result = self.request(self.alice, "/api/v1/admin/settings", {"deviceId": "test-tv", "settings": first_settings})
        self.assertEqual((status, result), (200, {"deviceId": "test-tv", "settings": first_settings}))
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=first_headers)[1]["settings"], first_settings)
        self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=self.headers)[1]["settings"], legacy["settings"])

        second_settings = {**legacy["settings"], "questionIntervalMinutes": 19, "dailyRewardLimitMinutes": 0,
                           "disabledTimeWindows": {str(day): [{"start": "19:00", "end": "09:00"}] for day in range(1, 8)}}
        self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", {
            "deviceId": "second-tv", "settings": second_settings,
        })[0], 200)
        server.STORE = server.StateStore(self.path)
        for headers, expected in ((first_headers, first_settings), (self.headers, second_settings)):
            device_id = headers["X-Device-Id"]
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=headers)[1]["settings"], expected)
            self.assertEqual(self.request(self.alice, f"/api/v1/admin/settings?deviceId={device_id}")[1]["settings"], expected)
        self.assertEqual(server.STORE.for_account(2).load()["settings"], legacy["settings"])
        self.assertEqual(len(self.request(self.alice, "/api/v1/admin/devices")[1]["devices"]), 2)
        health = self.request(self.device, "/api/v1/health")[1]
        self.assertEqual(health, {"ok": True, "version": server.BACKEND_VERSION})

    def test_device_settings_require_a_current_owned_binding(self):
        endpoint = "/api/v1/admin/settings"
        for invalid in ({}, {"deviceId": ""}, {"deviceId": True}, {"deviceId": "x" * 81}):
            self.assertEqual(self.request(self.alice, endpoint, {**invalid, "settings": server.DEFAULT_SETTINGS})[0], 400)
        self.assertEqual(self.request(self.alice, endpoint)[0], 400)
        self.assertEqual(self.request(self.alice, endpoint + "?deviceId=test-tv")[0], 404)
        self.pairing()
        self.assertEqual(self.request(self.alice, endpoint, {"deviceId": "test-tv", "settings": server.DEFAULT_SETTINGS})[0], 404)
        self.bind(self.alice)
        for client in (self.bob, self.admin, self.device):
            expected = 401 if client is self.device else 404
            self.assertEqual(self.request(client, endpoint + "?deviceId=test-tv")[0], expected)
            self.assertEqual(self.request(client, endpoint, {"deviceId": "test-tv", "settings": server.DEFAULT_SETTINGS})[0], expected)
        self.assertEqual(self.request(self.alice, endpoint, {"deviceId": "test-tv"})[0], 400)
        settings = {**server.DEFAULT_SETTINGS, "wrongLockMinutes": 7}
        self.assertEqual(self.request(self.alice, endpoint, {"deviceId": "test-tv", "settings": settings})[0], 200)
        self.assertEqual(self.request(self.alice, "/api/v1/admin/devices/unbind", {"deviceId": "test-tv"})[0], 200)
        self.assertEqual(self.request(self.alice, endpoint + "?deviceId=test-tv")[0], 404)
        self.assertEqual(self.request(self.alice, endpoint, {"deviceId": "test-tv", "settings": settings})[0], 404)
        self.bind(self.bob)
        self.assertEqual(self.request(self.bob, endpoint + "?deviceId=test-tv")[1]["settings"], server.DEFAULT_SETTINGS)

    def test_advanced_question_eligibility_uses_the_devices_weekly_limit(self):
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        now = server.datetime.now(server.timezone.utc)
        for headers, limit in ((first_headers, 20), (self.headers, 80)):
            device_id = headers["X-Device-Id"]
            settings = {**server.DEFAULT_SETTINGS, "weeklyDailyLimitMinutes": {str(day): limit for day in range(1, 8)}}
            self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", {"deviceId": device_id, "settings": settings})[0], 200)
            self.assertEqual(self.request(self.device, "/api/v1/device/usage", {
                "deviceId": device_id, "date": now.date().isoformat(), "utcOffsetMinutes": 0,
                "usageMillis": 60 * server.MILLIS_PER_MINUTE,
            }, headers)[0], 200)
        with patch.object(server.time, "time", return_value=now.timestamp()):
            for headers, eligible in ((first_headers, True), (self.headers, False)):
                self.assertEqual(self.request(self.device, "/api/v1/device/reward-challenge", headers=headers)[1]["eligible"], eligible)
                answer = {"questionId": server.DEFAULT_REWARD_QUESTION["id"], "numericAnswer": "0",
                          "attemptId": "per-device-limit", "answeredAt": int(now.timestamp() * 1000)}
                self.assertEqual(self.request(self.device, "/api/v1/device/reward-challenge", answer, headers)[0], 200 if eligible else 409)
