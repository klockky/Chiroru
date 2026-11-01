#!/usr/bin/env bash
set -euo pipefail

CHIRORU_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$CHIRORU_DIR"

# Gradle 8: prefer an installed JDK 17 if JAVA_HOME is unset.
if [ -z "${JAVA_HOME:-}" ]; then
    for CHIRORU_JDK in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64; do
        if [ -x "$CHIRORU_JDK/bin/java" ]; then
            export JAVA_HOME="$CHIRORU_JDK"
            break
        fi
    done
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

CHIRORU_VARIANT=Release
CHIRORU_APK=Chiroru.apk
CHIRORU_OUTPUT=app/build/outputs/apk/release/app-release.apk
if [ "${1:-}" = "--debug" ]; then
    shift
    CHIRORU_VARIANT=Debug
    CHIRORU_APK=Chiroru-debug.apk
    CHIRORU_OUTPUT=app/build/outputs/apk/debug/app-debug.apk
fi

./gradlew ":app:assemble$CHIRORU_VARIANT" --console=plain "$@"
cp "$CHIRORU_OUTPUT" "$CHIRORU_APK"
printf '\nAPK: %s/%s\n' "$CHIRORU_DIR" "$CHIRORU_APK"
