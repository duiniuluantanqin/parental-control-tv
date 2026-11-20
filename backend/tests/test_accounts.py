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

    def test_registration_validation_and_case_insensitive_uniqueness(self):
        for body in ({}, {"username": "new", "password": "short", "confirmPassword": "short"},
                     {"username": "new", "password": "long-password-123", "confirmPassword": "different"}):
            self.assertEqual(self.request(self.device, "/api/v1/admin/register", body)[0], 400)
        self.assertEqual(self.request(self.device, "/api/v1/admin/register", {
            "username": "ALICE", "password": "long-password-123", "confirmPassword": "long-password-123",
        })[0], 409)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertNotEqual(db.execute("SELECT password_hash FROM accounts WHERE username = 'alice'").fetchone()[0], "long-password-123")

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

    def test_accounts_isolate_settings_questions_records_commands_and_devices(self):
        self.bind(self.alice)
        _, state = self.request(self.alice, "/api/v1/admin/state")
        settings = {**state["settings"], "questionIntervalMinutes": 17}
        self.assertEqual(self.request(self.alice, "/api/v1/admin/settings", settings)[0], 200)
        question = {**state["questions"][0], "id": None, "prompt": "Alice only question", "category": "古诗词",
                    "poem": {"title": "独立作品", "author": "甲", "content": "甲乙丙丁"}}
        _, added = self.request(self.alice, "/api/v1/admin/questions", question)
        self.assertEqual(self.request(self.bob, f"/api/v1/admin/questions/{added['id']}", method="DELETE")[0], 404)
        self.request(self.alice, "/api/v1/admin/password/reset", {})
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
        self.bind(self.alice)
        first_headers = dict(self.headers)
        self.headers = {"X-Device-Id": "second-tv", "Authorization": "Bearer " + secrets.token_urlsafe(32)}
        self.bind(self.alice)
        _, state = self.request(self.alice, "/api/v1/admin/state")
        self.request(self.alice, "/api/v1/admin/settings", {**state["settings"], "wrongLockMinutes": 7})
        for headers in (first_headers, self.headers):
            self.assertEqual(self.request(self.device, "/api/v1/device/state", headers=headers)[1]["settings"]["wrongLockMinutes"], 7)
        self.assertEqual(len(self.request(self.alice, "/api/v1/admin/devices")[1]["devices"]), 2)
        health = self.request(self.device, "/api/v1/health")[1]
        self.assertEqual(health, {"ok": True, "version": server.BACKEND_VERSION})
