#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIRMWARE="${1:?usage: build.sh FIRMWARE PREPARED_HEADERS NEW_OUTPUT_DIR}"
HEADERS="${2:?provide a prepared kernel tree}"
OUT="${3:?provide a new output directory}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
case "$FIRMWARE" in
  1982735225) CC="$BIN/armv7a-linux-androideabi30-clang"; LINK=(-static) ;;
  5000001307|5000001245|5000001248|5000001289|5000001321) CC="$BIN/aarch64-linux-android30-clang"; LINK=(-fPIE -pie) ;;
  *) echo "Unsupported firmware: $FIRMWARE" >&2; exit 1 ;;
esac
test -x "$CC"
python3 "$ROOT/tools/build-kernel-modules.py" "$FIRMWARE" "$HEADERS" "$OUT" --module adb
"$CC" -O2 -Wall -Wextra -Werror "${LINK[@]}" \
    "-ffile-prefix-map=$ROOT=/src/Chiroru" "$ROOT/adb-root/client.c" -o "$OUT/chiroru-su"
if [[ "$FIRMWARE" == 1982735225 ]]; then
  "$BIN/llvm-strip" --strip-debug "$OUT/chiroru-su"
fi
python3 "$ROOT/tools/check-public-files.py" "$OUT/chiroru-su"
(cd "$OUT" && sha256sum chiroru-su >> SHA256SUMS)
