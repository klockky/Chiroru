#!/usr/bin/env bash
# GPL-3.0-or-later: build the matching KernelSU-Next userspace.
set -euo pipefail
SOURCE="$(realpath "${1:?usage: build-userspace.sh PATCHED_UPSTREAM_SOURCE}")"
test "$(git -C "$SOURCE" rev-parse HEAD)" = 204f007164905395e280e9290cc7774f57af8c61
export PATH="$HOME/.cargo/bin:$PATH"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export LIBCLANG_PATH="$TOOL/lib"
export CC_armv7_linux_androideabi="$TOOL/bin/armv7a-linux-androideabi30-clang"
export CXX_armv7_linux_androideabi="$TOOL/bin/armv7a-linux-androideabi30-clang++"
export AR_armv7_linux_androideabi="$TOOL/bin/llvm-ar"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CC_armv7_linux_androideabi"
export BINDGEN_EXTRA_CLANG_ARGS_armv7_linux_androideabi="--sysroot=$TOOL/sysroot -I$TOOL/sysroot/usr/include/arm-linux-androideabi -I$TOOL/sysroot/usr/include"
export RUSTFLAGS="--remap-path-prefix=$HOME=/build --remap-path-prefix=$SOURCE=/src/KernelSU-Next"
export CFLAGS_armv7_linux_androideabi="-ffile-prefix-map=$HOME=/build -ffile-prefix-map=$SOURCE=/src/KernelSU-Next"
export CXXFLAGS_armv7_linux_androideabi="$CFLAGS_armv7_linux_androideabi"
cd "$SOURCE/userspace/ksud"
mkdir -p bin/armv7
# The factory kernel runs static AArch64 executables; this upstream busybox
# needs no linker64. Do not copy the dynamically linked bootctl or waitsys.
cp bin/aarch64/busybox bin/armv7/busybox
cargo build --locked --target armv7-linux-androideabi --release
mkdir -p "$SOURCE/manager/app/src/main/jniLibs/armeabi-v7a"
cp target/armv7-linux-androideabi/release/ksud \
  "$SOURCE/manager/app/src/main/jniLibs/armeabi-v7a/libksud.so"
