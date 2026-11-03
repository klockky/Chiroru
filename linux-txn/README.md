# Vendor policy sources

This directory contains the SELinux subset of the prepared Linux 5.4 source
tree used by the verified chiron build, plus its generated `flask.h`. It is
vendored as ordinary files so an APK checkout does not follow local symlinks.
Original copyright and license notices are retained in the source files.

The original prepared tree had no Git metadata. Its upstream revision cannot
be independently established from that tree. `SHA256SUMS` pins this imported
subset; generated adapters also record the source file hashes and are checked
by `tools/gen_policy_writer.py` on every native build.

These are build inputs for the userspace policy parser/writer, not a complete
kernel build tree. The Android context hash backport is applied by the checked
`build-txn/selinux/chiron_context.h` adapter. Exact runtime compatibility is
limited to the firmware profile in the app's catalog.

For the external prepared tree requirements and profile-specific module build
commands, see [KERNEL_MODULES.md](../tools/KERNEL_MODULES.md).
