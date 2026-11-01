"""Exercise the real supervisor: streaming, exit, cancellation and app death."""
import os
from pathlib import Path
import select
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "src/main/cpp/console_runner.c"


class ConsoleRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="chiroru-console-")
        cls.binary = str(Path(cls.tmp.name) / "runner")
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2",
                        '-DCONSOLE_SHELL="/bin/sh"', str(SOURCE), "-o", cls.binary], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def start(self, command):
        p = subprocess.Popen([self.binary, "--user", command], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.addCleanup(lambda: p.poll() is None and p.kill())
        self.addCleanup(p.stdout.close)
        self.addCleanup(p.stdin.close)
        return p

    def test_streams_before_completion_and_preserves_exit_code(self):
        p = self.start("printf 'ready\\n'; sleep .2; printf 'error\\n' >&2; exit 7")
        self.assertTrue(select.select([p.stdout], [], [], 2)[0])
        self.assertEqual(p.stdout.readline(), b"ready\n")
        self.assertIsNone(p.poll())
        self.assertEqual(p.wait(timeout=3), 7)
        self.assertEqual(p.stdout.read(), b"error\n")

    def test_eof_cancels_shell_and_pipeline(self):
        p = self.start("echo ready; sleep 60 | cat")
        self.assertEqual(p.stdout.readline(), b"ready\n")
        p.stdin.close()
        self.assertEqual(p.wait(timeout=3), 130)
        self.assertEqual(p.stdout.read(), b"")

    def test_finishing_shell_cleans_background_children(self):
        p = self.start("sleep 60 & echo done")
        self.assertEqual(p.wait(timeout=3), 0)
        self.assertEqual(p.stdout.read(), b"done\n")

    def test_stdin_does_not_hang_and_unicode_output_is_preserved(self):
        p = self.start("cat; printf 'Привет 🌸'")
        self.assertEqual(p.wait(timeout=3), 0)
        self.assertEqual(p.stdout.read().decode(), "Привет 🌸")

    def test_root_failure_never_runs_command_as_user(self):
        p = subprocess.run([self.binary, "--root", "echo should-not-run"], capture_output=True, timeout=3)
        self.assertEqual(p.returncode, 126)
        self.assertEqual(p.stdout, b"")


if __name__ == "__main__":
    unittest.main()
