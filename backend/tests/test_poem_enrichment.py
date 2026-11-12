from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server  # noqa: E402
from deepseek import DeepSeekError  # noqa: E402


class PoemEnrichmentTests(unittest.TestCase):
    poem = {"title": "静夜思", "author": "李白", "content": "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。"}

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = server.StateStore(Path(self.temp_dir.name) / "state.sqlite3")
        self.question = server.validate_question({
            "id": "old-poetry", "category": "古诗词", "prompt": "《静夜思》的作者是谁？",
            "options": ["李白", "杜甫", "王维", "白居易"], "answer": "李白",
        })
        state = self.store.load()
        state["questions"] = [self.question]
        self.store.save(state)
        key_patch = patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"})
        key_patch.start()
        self.addCleanup(key_patch.stop)

    def tearDown(self) -> None:
        if self.store.poem_thread is not None:
            self.store.poem_thread.join(timeout=2)
        self.temp_dir.cleanup()

    def finish_worker(self) -> None:
        self.assertIsNotNone(self.store.poem_thread)
        self.store.poem_thread.join(timeout=2)
        self.assertFalse(self.store.poem_thread.is_alive())

    def test_success_is_saved_by_question_and_cached_across_store_restart(self) -> None:
        with patch("server.generate_poem_for_question", return_value=self.poem) as generate:
            self.store.snapshot()
            self.finish_worker()
            self.assertEqual(self.store.load()["questions"], [{**self.question, "poem": self.poem}])
            self.assertEqual(self.store.snapshot()["poemEnrichment"], {"running": False, "pending": 0, "error": None})
            reopened = server.StateStore(self.store.path)
            self.assertEqual(reopened.snapshot()["questions"][0]["poem"], self.poem)
            self.assertIsNone(reopened.poem_thread)
        generate.assert_called_once_with(self.question["prompt"], self.question["answer"])

    def test_snapshot_stays_nonblocking_and_concurrent_requests_share_one_worker(self) -> None:
        entered, release = threading.Event(), threading.Event()

        def slow_generation(*_):
            entered.set()
            self.assertTrue(release.wait(timeout=5))
            return self.poem

        with patch("server.generate_poem_for_question", side_effect=slow_generation) as generate:
            try:
                started = time.monotonic()
                first = self.store.snapshot()
                self.assertLess(time.monotonic() - started, 1)
                self.assertTrue(entered.wait(timeout=2))
                with ThreadPoolExecutor(max_workers=6) as executor:
                    results = list(executor.map(lambda _: self.store.snapshot(), range(6)))
                self.assertTrue(all(item["questions"] == [self.question] for item in [first, *results]))
                self.assertTrue(all(item["poemEnrichment"]["running"] for item in [first, *results]))
                self.assertEqual(generate.call_count, 1)
            finally:
                release.set()
                self.finish_worker()

    def test_changed_or_deleted_question_is_not_overwritten_by_inflight_result(self) -> None:
        for deleted in (False, True):
            with self.subTest(deleted=deleted):
                state = self.store.load()
                state["questions"] = [self.question]
                self.store.save(state)
                entered, release = threading.Event(), threading.Event()

                def slow_generation(*_):
                    entered.set()
                    self.assertTrue(release.wait(timeout=5))
                    return self.poem

                with patch("server.generate_poem_for_question", side_effect=slow_generation):
                    try:
                        self.store.snapshot()
                        self.assertTrue(entered.wait(timeout=2))
                        state = self.store.load()
                        state["questions"] = [] if deleted else [{**self.question, "prompt": "家长刚修改的新题干"}]
                        self.store.save(state)
                    finally:
                        release.set()
                        self.finish_worker()
                self.assertEqual(self.store.load()["questions"], state["questions"])

    def test_missing_key_keeps_bank_untouched_without_starting_provider(self) -> None:
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": ""}), patch("server.generate_poem_for_question") as generate:
            state = self.store.snapshot()
        self.assertIsNone(self.store.poem_thread)
        self.assertEqual(state["questions"], [self.question])
        self.assertEqual(state["poemEnrichment"]["pending"], 1)
        self.assertFalse(state["poemEnrichment"]["running"])
        self.assertIn("DeepSeek Key", state["poemEnrichment"]["error"])
        generate.assert_not_called()

    def test_failure_is_sanitized_has_backoff_and_stops_after_three_attempts(self) -> None:
        with patch("server.generate_poem_for_question", side_effect=DeepSeekError("secret-provider-key")) as generate:
            for attempt in range(1, 4):
                self.store.next_poem_attempt = 0
                self.store.snapshot()
                self.finish_worker()
                state = self.store.snapshot()
                self.assertEqual(generate.call_count, attempt)
                self.assertGreater(self.store.next_poem_attempt, time.monotonic())
                self.assertEqual(state["questions"], [self.question])
                self.assertNotIn("secret-provider-key", state["poemEnrichment"]["error"])
                self.assertEqual(state["poemEnrichment"]["pending"], 1)
            self.store.next_poem_attempt = 0
            self.store.snapshot()
            self.assertEqual(generate.call_count, 3)

    def test_invalid_model_metadata_is_not_persisted_and_batch_is_bounded(self) -> None:
        with patch("server.generate_poem_for_question", return_value={**self.poem, "content": []}):
            self.store.snapshot()
            self.finish_worker()
        self.assertEqual(self.store.load()["questions"], [self.question])
        self.store.next_poem_attempt = 0
        self.store.poem_failures.clear()
        state = self.store.load()
        state["questions"] = [{**self.question, "id": f"poem-{index}", "prompt": f"诗词题{index}"} for index in range(12)]
        self.store.save(state)
        with patch("server.generate_poem_for_question", return_value=self.poem) as generate:
            self.store.snapshot()
            self.finish_worker()
            self.assertEqual(generate.call_count, 10)
            self.assertEqual(sum(bool(item.get("poem")) for item in self.store.load()["questions"]), 10)
            self.store.snapshot()
            self.finish_worker()
            self.assertEqual(generate.call_count, 12)


if __name__ == "__main__":
    unittest.main()
