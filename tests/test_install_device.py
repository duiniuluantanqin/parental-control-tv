import subprocess
import sys
import unittest
from unittest.mock import patch

import install_device


class RunAdbTests(unittest.TestCase):
    def test_utf8_connection_error_is_reported_without_a_decoder_crash(self):
        message = "无法连接电视：目标主机不可达。"
        command = f"import os; os.write(2, {message.encode('utf-8')!r}); raise SystemExit(1)"
        with self.assertRaisesRegex(RuntimeError, message):
            install_device.run_adb(sys.executable, "-c", command)

    def test_successful_utf8_output_is_returned(self):
        message = "已连接电视"
        command = f"import os; os.write(1, {message.encode('utf-8')!r})"
        self.assertEqual(message, install_device.run_adb(sys.executable, "-c", command))

    def test_unexpected_bytes_do_not_hide_the_command_error(self):
        with self.assertRaisesRegex(RuntimeError, "connection failed"):
            install_device.run_adb(sys.executable, "-c", "import os; os.write(2, b'connection failed: \\xff'); raise SystemExit(1)")

    def test_errors_written_to_stdout_are_preserved(self):
        with self.assertRaisesRegex(RuntimeError, "connection refused"):
            install_device.run_adb(sys.executable, "-c", "print('connection refused'); raise SystemExit(1)")

    def test_adb_timeout_has_an_actionable_error(self):
        with patch("install_device.subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 30)):
            with self.assertRaisesRegex(RuntimeError, "timed out after 30 seconds: connect 10.0.0.8:5555"):
                install_device.run_adb("adb", "connect", "10.0.0.8:5555")


if __name__ == "__main__":
    unittest.main()
