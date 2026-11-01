"""Bound the private ABI used by this module against the captured vendor ELF."""
import hashlib
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ELF = ROOT.parent / "duomax-cve-2022-36449/evidence/ondevice/chiron-vmlinux.elf"


def disasm(path, symbol):
    return subprocess.check_output(["aarch64-linux-gnu-objdump", "-dr",
                                   f"--disassemble={symbol}", str(path)], text=True)


class AbiTest(unittest.TestCase):
    def test_pinned_vendor_binary(self):
        self.assertEqual(hashlib.sha256(ELF.read_bytes()).hexdigest(),
                         "013dd6aee9724e6f1f6de4f459a60568445c585e28920aae1fc8fec5579cf0da")

    def test_no_prepared_task_namespace_access(self):
        native = disasm(ELF, "__arm64_sys_newuname")
        self.assertIn("[x22, #1784]", native)
        code = disasm(ROOT / "adb-root/module/main.o", "init_module")
        self.assertIn("init_uts_ns", code)
        self.assertNotIn("sp_el0", code)

    def test_file_and_device_offsets(self):
        code = disasm(ELF, "selinux_file_open")
        for fragment in ("[x19, #68]", "[x19, #144]", "[x8, #120]", "[x8, #4]"):
            self.assertIn(fragment, code)
        self.assertIn("[x8, #72]", disasm(ELF, "misc_devnode"))
        for offset in (24, 32, 40, 48, 56):
            self.assertIn(f"[x19, #{offset}]", disasm(ELF, "misc_register"))

    def test_no_kernel_su_runtime_dependency_or_hooks(self):
        obj = ROOT / "adb-root/out/chiroru_adb.ko"
        names = subprocess.check_output(["aarch64-linux-gnu-nm", "-u", str(obj)], text=True)
        for forbidden in ("ksu_", "sys_call_table", "register_kprobe", "stop_machine"):
            self.assertNotIn(forbidden, names)
        self.assertIn("security_load_policy", names)
        self.assertIn("commit_creds", names)


if __name__ == "__main__":
    unittest.main()
