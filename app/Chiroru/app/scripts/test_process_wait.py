import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path


class ProcessWaitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app = Path(__file__).resolve().parents[1]
        directory = tempfile.TemporaryDirectory(prefix="chiroru-process-test-")
        cls.addClassCleanup(directory.cleanup)
        cls.binary = Path(directory.name) / "process-wait-test"
        subprocess.run([
            *shlex.split(os.environ.get("CC", "cc")),
            "-std=c11", "-Wall", "-Wextra", "-Werror", "-pthread",
            "-I", str(app / "src/main/cpp"),
            str(app / "src/main/cpp/process_wait.c"),
            str(app / "src/test/cpp/process_wait_test.c"),
            "-o", str(cls.binary),
        ], check=True, capture_output=True, text=True)

    def run_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_captures_stdout_stderr_and_success(self):
        self.run_case("success")

    def test_preserves_nonzero_exit(self):
        self.run_case("failure")

    def test_signal_is_failure(self):
        self.run_case("signal")

    def test_quiet_child_times_out(self):
        self.run_case("quiet")

    def test_closed_stdout_is_not_process_exit(self):
        self.run_case("closed_output")

    def test_full_capture_buffer_does_not_disable_timeout(self):
        self.run_case("full_buffer")

    def test_continuous_output_does_not_reset_deadline(self):
        self.run_case("continuous_output")

    def test_verbose_child_can_finish_after_capture_buffer_fills(self):
        self.run_case("verbose_success")

    def test_one_byte_capture_buffer_still_drains_output(self):
        self.run_case("tiny_buffer")

    def test_interrupted_poll_keeps_deadline(self):
        self.run_case("interrupted")

    def test_timeout_kills_child_process_group(self):
        self.run_case("descendant")

    def test_inherited_pipe_does_not_delay_completed_child(self):
        self.run_case("inherited_pipe")


if __name__ == "__main__":
    unittest.main()
