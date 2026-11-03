"""KernelSU loading must not leave kptr_restrict relaxed.

ksud reads /proc/kallsyms to relocate the module, which needs kptr_restrict
disabled.  The loader has to put the previous value back, otherwise every app
on the station can read kernel pointers after a KernelSU boot.
"""
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "src/main/cpp/backends/chiron_5000001307/root.c"


def section(source: str, name: str) -> str:
    start = source.index(f"static int {name}(")
    depth = 0
    for index in range(source.index("{", start), len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unbalanced braces in {name}")


def verify_source(source: str) -> None:
    wrapper = section(source, "load_module")
    relaxed = section(source, "load_module_relaxed")

    if "kptr_restrict" in relaxed:
        raise AssertionError("load_module_relaxed must not touch kptr_restrict")
    for fragment in ('open("/proc/sys/kernel/kptr_restrict", O_RDWR',
                     'write(sysctl, "0", 1)',
                     'write(sysctl, old_kptr, old_len)',
                     "restore_kptr_as_ksu(",
                     "load_module_relaxed("):
        if fragment not in wrapper:
            raise AssertionError(f"load_module is missing {fragment}")

    helper = section(source, "restore_kptr_as_ksu")
    if "ksu_grant_root(" not in helper:
        raise AssertionError("the ksu-domain restore must request the KSU root domain")

    relax = wrapper.index('write(sysctl, "0", 1)')
    call = wrapper.rindex("load_module_relaxed(")
    restore = wrapper.index("write(sysctl, old_kptr, old_len)")
    if not relax < call < restore:
        raise AssertionError("kptr_restrict must be relaxed before the load and restored after it")

    for line in wrapper[call:restore].splitlines():
        if line.strip().startswith("return "):
            raise AssertionError("load_module must not return before restoring kptr_restrict")

    for delegation in ("load_adb_module(", "load_factory_ksu("):
        if delegation not in wrapper:
            raise AssertionError(f"load_module must keep the {delegation} path")


class KptrRestoreTest(unittest.TestCase):
    def test_kernelsu_load_restores_kptr_restrict(self):
        verify_source(SOURCE.read_text())

    def test_rejects_a_load_that_leaves_kptr_restrict_relaxed(self):
        source = SOURCE.read_text()
        valid = section(source, "load_module")
        for broken in (
            valid.replace('write(sysctl, old_kptr, old_len)', "0"),
            valid.replace("load_module_relaxed(", "load_module_skipped("),
            valid.replace('write(sysctl, "0", 1)', "0"),
        ):
            with self.subTest(broken=broken[:40]):
                with self.assertRaises(AssertionError):
                    verify_source(source.replace(valid, broken, 1))


if __name__ == "__main__":
    unittest.main()
