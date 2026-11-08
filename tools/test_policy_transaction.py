"""Binary/dependency checks complement the executable host policy tests."""
import importlib.util
import hashlib
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(os.environ.get("CHIRORU_MODULE_WORK", ROOT))
HEADERS = WORK / "headers" if "CHIRORU_MODULE_WORK" in os.environ else ROOT / "linux-txn"


def undefined(obj):
    output = subprocess.check_output(
        ["aarch64-linux-gnu-nm", "-u", str(WORK / obj)], text=True)
    return {line.split()[-1] for line in output.splitlines() if line.strip()}


class TransactionBinaryTest(unittest.TestCase):
    def test_no_preemption_counter_stubs(self):
        output = subprocess.check_output(
            ["aarch64-linux-gnu-nm", str(WORK / "third_party/kernelsu/kernel/kernelsu.ko")], text=True)
        names = {line.split()[-1] for line in output.splitlines() if line.strip()}
        self.assertFalse(names & {"preempt_count_add", "preempt_count_sub"})
        header = (HEADERS / "include/generated/autoconf.h").read_text()
        self.assertNotIn("#define CONFIG_DEBUG_PREEMPT 1", header)

    def test_module_uses_vendor_selinuxfs_offset(self):
        module = subprocess.check_output(
            ["aarch64-linux-gnu-objdump", "-d", str(WORK / "third_party/kernelsu/kernel/selinux/policy_transaction.o")],
            text=True)
        self.assertIn("#968]", module)
        self.assertNotIn("#952]", module)

    def test_vendor_selinuxfs_accessor_matches_binary(self):
        if "CHIRORU_KERNEL_ELF" not in os.environ:
            self.skipTest("Set CHIRORU_KERNEL_ELF for the optional full vendor ELF check")
        elf = Path(os.environ["CHIRORU_KERNEL_ELF"])
        self.assertIn(hashlib.sha256(elf.read_bytes()).hexdigest(), {
            "013dd6aee9724e6f1f6de4f459a60568445c585e28920aae1fc8fec5579cf0da",
            # 1926931523, reconstructed by vmlinux-to-elf 1.4.0 from verified boot.
            "46d40725e00651cbbc36b7abde977b16340ccd2ce4f01da0ed38a2818b45fd6a",
        })
        disasm = subprocess.check_output(
            ["aarch64-linux-gnu-objdump", "-d", "--disassemble=sel_write_load", str(elf)], text=True)
        # Native file -> inode -> superblock -> private info -> mutex/state.
        for instruction in ("ldr\tx8, [x19, #32]", "ldr\tx8, [x8, #40]",
                            "ldr\tx22, [x8, #968]", "add\tx19, x22, #0x40",
                            "ldr\tx0, [x22, #104]"):
            self.assertIn(instruction, disasm)
        isids = subprocess.check_output(
            ["aarch64-linux-gnu-objdump", "-d", "--disassemble=policydb_load_isids", str(elf)], text=True)
        for instruction in ("ldr\tw8, [x22, #40]!", "ldr\tw8, [x23, #200]",
                            "ldr\tx23, [x23, #208]"):
            self.assertIn(instruction, isids)
        hashing = subprocess.check_output(
            ["aarch64-linux-gnu-objdump", "-d", "--disassemble=context_add_hash", str(elf)], text=True)
        self.assertIn("str\tw8, [x19, #72]", hashing)

    def test_writer_has_no_unchecked_kernel_writer_dependency(self):
        names = undefined("third_party/kernelsu/kernel/selinux/policy_writer.o")
        self.assertFalse(names & {"policydb_write", "avtab_write", "avtab_write_item",
                                  "ebitmap_write", "cond_write_list", "cond_write_bool"})
        # Native hashtab iteration is read-only; all output callbacks are local.
        self.assertIn("hashtab_map", names)
        self.assertFalse(names & {"vmalloc", "__kmalloc", "kmem_cache_alloc_trace",
                                  "mutex_lock", "schedule", "__cond_resched"})

    def test_native_publication_replaces_manual_swap_and_fallback(self):
        names = undefined("third_party/kernelsu/kernel/selinux/policy_transaction.o")
        self.assertIn("security_load_policy", names)
        self.assertIn("mutex_lock", names)
        self.assertIn("mutex_unlock", names)
        self.assertNotIn("security_read_policy", names)
        self.assertFalse(any("write_lock" in name for name in names))
        rules = undefined("third_party/kernelsu/kernel/selinux/rules.o")
        self.assertIn("ksu_policy_transaction", rules)
        self.assertFalse(rules & {"stop_machine", "avc_ss_reset", "policydb_write",
                                  "sched_setscheduler_nocheck", "preempt_schedule"})
        self.assertFalse(any("write_lock" in name for name in rules))

    def test_generated_sources_match_reviewed_vendor(self):
        subprocess.run(["python3", str(ROOT / "tools/gen_policy_writer.py"),
                        str(ROOT / "linux-txn/source"),
                        str(ROOT / "third_party/kernelsu/kernel/selinux"), "--check"], check=True)

    def test_preemption_config_consistent(self):
        spec = importlib.util.spec_from_file_location(
            "preempt_check", ROOT / "tools/check-kernel-preempt-config.py")
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        tree = HEADERS
        self.assertEqual(checker.check((tree / "include/config/auto.conf").read_text(),
                                      (tree / "include/generated/autoconf.h").read_text()), [])


if __name__ == "__main__":
    unittest.main()
