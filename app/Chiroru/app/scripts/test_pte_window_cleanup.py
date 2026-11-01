import re
import textwrap
import unittest
from pathlib import Path


ENFORCE_SLOT = "SELINUX_ENFORCING_WINDOW_SLOT"
RESTORE_SLOT = "SELINUX_RESTORE_WINDOW_SLOT"


def verify_source(source: str) -> None:
    expected_macros = {
        ENFORCE_SLOT: "(RS_ENTRIES - 2)",
        RESTORE_SLOT: "(RS_ENTRIES - 1)",
    }
    for name, value in expected_macros.items():
        if not re.search(rf"^#define\s+{name}\s+{re.escape(value)}\s*$", source, re.M):
            raise AssertionError(f"{name} must be defined as {value}")

    calls = re.findall(r"\b(win_open|win_close)\s*\(\s*table\s*,\s*([^,\s)]+)", source)
    expected = {
        ("win_open", ENFORCE_SLOT): 1,
        ("win_close", ENFORCE_SLOT): 1,
        ("win_open", RESTORE_SLOT): 1,
        ("win_close", RESTORE_SLOT): 1,
    }
    for pair, count in expected.items():
        actual = calls.count(pair)
        if actual != count:
            raise AssertionError(f"expected {count} {pair[0]}(table, {pair[1]}, ...) call(s), found {actual}")


class PteWindowCleanupTest(unittest.TestCase):
    def test_production_selinux_windows_are_closed_in_their_open_slots(self):
        source_path = Path(__file__).resolve().parents[1] / "src/main/cpp/backends/chiron_5000001307/root.c"
        verify_source(source_path.read_text())

    def test_rejects_wrong_close_slot(self):
        valid = textwrap.dedent("""
        #define SELINUX_ENFORCING_WINDOW_SLOT (RS_ENTRIES - 2)
        #define SELINUX_RESTORE_WINDOW_SLOT (RS_ENTRIES - 1)
        win_open(table, SELINUX_ENFORCING_WINDOW_SLOT, table0, SELINUX_PA, base);
        win_close(table, SELINUX_ENFORCING_WINDOW_SLOT);
        win_open(table, SELINUX_RESTORE_WINDOW_SLOT, table0, SELINUX_PA, base);
        win_close(table, SELINUX_RESTORE_WINDOW_SLOT);
        """)
        verify_source(valid)
        for slot in (ENFORCE_SLOT, RESTORE_SLOT):
            with self.subTest(slot=slot):
                broken = valid.replace(f"win_close(table, {slot});", "win_close(table, 20);")
                with self.assertRaisesRegex(AssertionError, rf"win_close\(table, {slot},"):
                    verify_source(broken)


if __name__ == "__main__":
    unittest.main()
