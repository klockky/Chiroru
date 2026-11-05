# Vendor policy sources

This directory contains SELinux sources and generated `flask.h` from the
prepared Linux 5.4 tree used for chiron. Original copyright and license
notices are retained in the source files.

The original prepared tree had no Git metadata. Its upstream revision cannot
be independently established from that tree. `SHA256SUMS` pins this imported
subset; generated adapters also record the source file hashes and are checked
by `tools/gen_policy_writer.py` on every native build.

The userspace policy parser/writer uses these files with the Android context
hash adapter in `third_party/kernelsu/kernel/selinux/chiron_context.h`. This is a source subset;
kernel module builds require a separate full tree.

For the external prepared tree requirements and profile-specific module build
commands, see [KERNEL_MODULES.md](../tools/KERNEL_MODULES.md).
