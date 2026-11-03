#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HEADERS="${1:?usage: build-module.sh PREPARED_HEADERS NEW_OUTPUT_DIR}"
OUT="${2:?provide a new output directory}"
python3 "$ROOT/tools/build-kernel-modules.py" 1982735225 "$HEADERS" "$OUT" --module ksu
# Preserve the original entry point's output filename.
cp "$OUT/kernelsu.ko" "$OUT/kernelsu-factory.ko"
(cd "$OUT" && sha256sum kernelsu-factory.ko >> SHA256SUMS)
