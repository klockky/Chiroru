#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/tools/gen_policy_writer.py" "$ROOT/linux-txn/source" "$ROOT/build-txn/selinux" --check
python3 "$ROOT/tools/check-kernel-preempt-config.py" "$ROOT/linux-txn"
make -C "$ROOT/linux-txn" M="$ROOT/adb-root/module" ARCH=arm64 \
    CROSS_COMPILE=aarch64-linux-gnu- KBUILD_MODPOST_WARN=1 modules
mkdir -p "$ROOT/adb-root/out"
aarch64-linux-gnu-strip --strip-debug -o "$ROOT/adb-root/out/chiroru_adb.ko" "$ROOT/adb-root/module/chiroru_adb.ko"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang" \
    -O2 -Wall -Wextra -Werror -fPIE -pie "$ROOT/adb-root/client.c" -o "$ROOT/adb-root/out/chiroru-su"
sha256sum "$ROOT/adb-root/out/chiroru_adb.ko" "$ROOT/adb-root/out/chiroru-su"
