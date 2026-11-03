#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HEADERS="$(realpath "${1:?usage: build-module.sh FACTORY_HEADERS NEW_OUTPUT_DIR}")"
OUT="$(realpath -m "${2:?provide a new output directory}")"
RELEASE=5.4.125-android11-2-g85fe5498964f-ab1982735225
grep -Fq "\"$RELEASE\"" "$HEADERS/include/generated/utsrelease.h"
test ! -e "$OUT" || { echo "Output directory already exists" >&2; exit 1; }
mkdir -p "$OUT"
# Copy only tracked source, excluding stale compiled objects.
git -C "$ROOT" ls-files -z build-txn | tar -C "$ROOT" --null -T - -cf - | tar -C "$OUT" --strip-components=1 -xf -
patch -d "$OUT" -p1 < "$ROOT/tools/factory/kernel.patch"
make -C "$HEADERS" M="$OUT" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  CONFIG_KSU=m CONFIG_KSU_SYSCALL_TABLE_HOOK=y KSU_VERSION_OVERRIDE=34000 \
  KSU_VERSION_TAG_OVERRIDE=v3.4.2-factory KBUILD_MODPOST_WARN=1 \
  KSU_MANAGER_PACKAGE=com.rifsxd.ksunext \
  KSU_NEXT_MANAGER_SIZE="${KSU_NEXT_MANAGER_SIZE:-0x324}" \
  KSU_NEXT_MANAGER_HASH="${KSU_NEXT_MANAGER_HASH:-93ef8c5f1fe906ffc294d5091c75b194dc64967cd53649aecbeda6b41ec96a3a}" \
  KCFLAGS="-include $ROOT/tools/ksu54-shim.h -DKSU_OPTIONAL_SECCOMP_FILTER_RELEASE -DKSU_FACTORY_COMPAT -ffile-prefix-map=$ROOT=/src/Chiroru -ffile-prefix-map=$OUT=/src/build-txn" modules
python3 "$ROOT/tools/sanitize-module.py" "$OUT/kernelsu.ko" "$OUT/kernelsu-factory.ko"
