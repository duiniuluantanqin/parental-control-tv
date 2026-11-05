from __future__ import annotations

import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deepseek  # noqa: E402


class DeepSeekTests(unittest.TestCase):
    def completion(self, content, *, finish_reason="stop"):
        return io.BytesIO(json.dumps({"choices": [{"finish_reason": finish_reason, "message": {"content": content}}]}).encode())

    def test_sends_server_key_and_requests_json_for_one_poem(self):
        poem = {"title": "静夜思", "author": "李白", "questions": [{"prompt": "第一题"}, {"prompt": "第二题"}]}
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=self.completion(json.dumps(poem))) as request:
            result = deepseek.generate_poem_questions("静夜思（李白）", 2)
        self.assertEqual(result, poem)
        outbound = request.call_args.args[0]
        self.assertEqual(outbound.full_url, "https://api.deepseek.com/chat/completions")
        self.assertEqual(outbound.get_header("Authorization"), "Bearer test-key")
        payload = json.loads(outbound.data)
        self.assertEqual(payload["model"], "deepseek-flash")
        self.assertEqual(payload["response_format"], {"type": "json_object"})
        self.assertEqual(payload["thinking"], {"type": "disabled"})
        self.assertIn("静夜思（李白）", payload["messages"][1]["content"])
        self.assertEqual(json.loads(payload["messages"][1]["content"])["难度"], "中")
        system_prompt = payload["messages"][0]["content"]
        self.assertIn("询问上一句", system_prompt)
        self.assertIn("每批下一句题最多一道", system_prompt)
        self.assertNotIn("test-key", json.dumps(payload))

    def test_selected_difficulty_is_sent_to_provider(self):
        poem = {"title": "静夜思", "author": "李白", "questions": [{}]}
        for difficulty in ("低", "中", "高"):
            with self.subTest(difficulty=difficulty), patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=self.completion(json.dumps(poem))) as request:
                deepseek.generate_poem_questions("静夜思", 1, difficulty)
                payload = json.loads(request.call_args.args[0].data)
                self.assertEqual(json.loads(payload["messages"][1]["content"])["难度"], difficulty)

    def test_missing_key_does_not_call_provider(self):
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": ""}), patch("deepseek.urlopen") as request:
            with self.assertRaises(deepseek.DeepSeekError) as error:
                deepseek.generate_poem_questions("静夜思", 2)
        self.assertEqual(error.exception.status, 503)
        request.assert_not_called()

    def test_random_questions_include_categories_difficulty_and_excluded_prompts(self):
        questions = [{"category": "数学", "prompt": "测试题"}]
        with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=self.completion(json.dumps({"questions": questions}))) as request:
            result = deepseek.generate_random_questions(1, "中", ["已有题干"])
        self.assertEqual(result, questions)
        payload = json.loads(request.call_args.args[0].data)
        user = json.loads(payload["messages"][1]["content"])
        self.assertEqual(user, {"出题数量": 1, "难度": "中", "已生成题干": ["已有题干"]})
        for category in ("古诗词", "数学", "科学", "地理历史"):
            self.assertIn(category, payload["messages"][0]["content"])
        for result in ({}, {"questions": [{}]}, {"questions": "错误格式"}):
            with patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=self.completion(json.dumps(result))):
                with self.assertRaises(deepseek.DeepSeekError):
                    deepseek.generate_random_questions(10, "中", [])

    def test_provider_errors_are_sanitized_and_not_retried(self):
        for status in (401, 402, 429, 500):
            with self.subTest(status=status), patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", side_effect=HTTPError("https://api.deepseek.com", status, "test-key", {}, io.BytesIO(b"test-key"))) as request:
                with self.assertRaises(deepseek.DeepSeekError) as error:
                    deepseek.generate_poem_questions("静夜思", 2)
                self.assertNotIn("test-key", str(error.exception))
                self.assertEqual(request.call_count, 1)

    def test_timeout_and_bad_or_incomplete_output_are_rejected(self):
        valid = {"title": "静夜思", "author": "李白", "questions": [{}, {}]}
        cases = [
            ("", "stop"), ("   ", "stop"), ("not json", "stop"),
            (json.dumps(valid), "length"), ("[]", "stop"),
            (json.dumps({**valid, "questions": [{}]}), "stop"),
            (json.dumps({**valid, "author": ""}), "stop"),
            (json.dumps({"error": "unknown poem"}), "stop"),
        ]
        for content, reason in cases:
            with self.subTest(content=content, reason=reason), patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=self.completion(content, finish_reason=reason)):
                with self.assertRaises(deepseek.DeepSeekError):
                    deepseek.generate_poem_questions("静夜思", 2)
        for error in (TimeoutError(), URLError("network unavailable")):
            with self.subTest(error=error), patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", side_effect=error):
                with self.assertRaises(deepseek.DeepSeekError):
                    deepseek.generate_poem_questions("静夜思", 2)
        for raw in (b"{}", b'{"choices":[]}', b'{"choices":[1]}'):
            with self.subTest(raw=raw), patch.dict("os.environ", {"DEEPSEEK_API_KEY": "test-key"}), patch("deepseek.urlopen", return_value=io.BytesIO(raw)):
                with self.assertRaises(deepseek.DeepSeekError):
                    deepseek.generate_poem_questions("静夜思", 2)


if __name__ == "__main__":
    unittest.main()
