#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
SNAPSHOT="${1:-/tmp/opencode/policy-now.bin}"

# Check generated vendor dependencies instead of silently accepting drift.
python3 tools/gen_policy_writer.py linux-txn/source build-txn/selinux --check
python3 tools/check-kernel-preempt-config.py linux-txn
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v tools/test_lifecycle_events.py
# Generated configuration was corrected outside Kconfig. A clean module build
# avoids stale objects whose dependency stamps still describe the old headers.
make -C linux-txn M="$ROOT/build-txn" ARCH=arm64 \
    CROSS_COMPILE=aarch64-linux-gnu- CONFIG_KSU=m \
    CONFIG_KSU_SYSCALL_TABLE_HOOK=y clean
make -C linux-txn M="$ROOT/build-txn" ARCH=arm64 \
    CROSS_COMPILE=aarch64-linux-gnu- CONFIG_KSU=m \
    CONFIG_KSU_SYSCALL_TABLE_HOOK=y KSU_VERSION_OVERRIDE=34000 \
    KSU_VERSION_TAG_OVERRIDE=v3.4.0-legacy KBUILD_MODPOST_WARN=1 \
    KCFLAGS="-include $ROOT/tools/ksu54-shim.h -DKSU_OPTIONAL_SECCOMP_FILTER_RELEASE" \
    modules

PYTHONDONTWRITEBYTECODE=1 python3 tools/policy_host/build.py
/tmp/opencode/policy-host/test-policy "$SNAPSHOT"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v tools/test_policy_transaction.py
python3 tools/sanitize-module.py build-txn/kernelsu.ko kernelsu-policy-transaction.ko
sha256sum kernelsu-policy-transaction.ko
