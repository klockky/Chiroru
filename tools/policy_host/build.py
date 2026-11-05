#!/usr/bin/env python3
"""Compile real vendor policy code with host alloc/lock shims and sanitizers."""
import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from gen_policy_writer import function


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path(tempfile.gettempdir()) / "chiroru-policy-host")
    parser.add_argument("--test-source", type=Path, default=Path(__file__).with_name("test.c"))
    parser.add_argument("--prepare-only", action="store_true", help="Generate userspace compatibility headers without compiling tests")
    args = parser.parse_args()
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    source = ROOT / "linux-txn/source/security/selinux"
    mod = ROOT / "third_party/kernelsu/kernel"
    # Stub only the Linux environment; policy structures/algorithms are the
    # original vendor sources, not libsepol's different in-memory layout.
    for name in ("linux/kernel.h", "linux/slab.h", "linux/sched.h", "linux/string.h",
                 "linux/audit.h", "linux/spinlock.h", "linux/types.h", "linux/version.h",
                 "linux/printk.h", "linux/gfp.h", "linux/uaccess.h", "net/netlabel.h",
                 "linux/fs.h", "linux/namei.h", "linux/magic.h", "linux/mutex.h",
                 "linux/vmalloc.h", "compat/kernel_compat.h", "klog.h"):
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#include "compat.h"\n')
    # glibc errno.h itself includes linux/errno.h; use asm-generic definitions.
    (out / "linux/errno.h").write_text('#include <asm-generic/errno.h>\n')
    security = (source / "include/security.h").read_text()
    defines = re.findall(r"^#define (?:POLICYDB_|TYPEDATUM_|SEC(?:SID|CLASS)_)[^\n]+", security, re.M)
    defines += re.findall(r"^#define SECURITY_FS_USE_[^\n]+", security, re.M)
    (out / "security.h").write_text('#include "compat.h"\n#include "flask.h"\n' + '\n'.join(defines) + '\n')
    # policydb_load_isids (not used) needs initial_sid_to_string; extract from
    # the vendor generated flask header rather than inventing SID numbering.
    flask = ROOT / "linux-txn/security/selinux/flask.h"
    (out / "flask.h").write_text(flask.read_text())
    mls = (source / "ss/mls.c").read_text()
    (out / "mls_validate.c").write_text(
        '#include "ss/policydb.h"\n' + '\n'.join(function(mls, name) for name in
        ("mls_level_isvalid", "mls_range_isvalid", "mls_context_isvalid")))
    if args.prepare_only:
        return
    flags = ["cc", "-std=gnu11", "-g", "-O1", "-fno-omit-frame-pointer",
             "-fsanitize=address,undefined", "-fno-sanitize-recover=all",
             "-ffunction-sections", "-fdata-sections", "-Werror=implicit-function-declaration",
             "-pthread", "-include", str(Path(__file__).with_name("compat.h")),
             "-include", str(mod / "selinux/chiron_context.h"),
             "-I" + str(out), "-I" + str(Path(__file__).parent),
             "-I" + str(source), "-I" + str(source / "ss"),
               "-I" + str(mod), "-I" + str(mod / "include"), "-I" + str(mod / "selinux"),
               "-I" + str(mod.parent)]
    sources = [source / "ss" / (name + ".c") for name in
               ("policydb", "ebitmap", "avtab", "hashtab", "symtab", "conditional")]
    sources += [out / "mls_validate.c", mod / "selinux/sepolicy.c",
                mod / "selinux/policy_writer.c",
                mod / "selinux/policy_transaction.c", args.test_source]
    subprocess.run(flags + list(map(str, sources)) +
                   ["-Wl,--gc-sections", "-o", str(out / "test-policy")], check=True)


if __name__ == "__main__":
    main()
