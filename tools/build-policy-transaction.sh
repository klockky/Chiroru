#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FIRMWARE="${1:?usage: build-policy-transaction.sh FIRMWARE PREPARED_HEADERS NEW_OUTPUT_DIR [POLICY_SNAPSHOT]}"
HEADERS="${2:?provide a prepared kernel tree}"
OUT="${3:?provide a new output directory}"
SNAPSHOT="${4:-}"

# Check generated vendor dependencies instead of silently accepting drift.
python3 tools/gen_policy_writer.py linux-txn/source build-txn/selinux --check
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v tools/test_lifecycle_events.py
python3 tools/build-kernel-modules.py "$FIRMWARE" "$HEADERS" "$OUT" --module ksu

CHIRORU_MODULE_WORK="$(realpath "$OUT/work")" \
    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v tools/test_policy_transaction.py
if [[ -n "$SNAPSHOT" ]]; then
    PYTHONDONTWRITEBYTECODE=1 python3 tools/policy_host/build.py
    /tmp/opencode/policy-host/test-policy "$SNAPSHOT"
fi
