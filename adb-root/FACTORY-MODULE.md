# Factory ADB module

Firmware `1982735225` uses an ARM64 kernel with 32-bit userspace and no `linker64`.
The factory loader resolves ELF64 symbols and calls `init_module()` directly
from the 32-bit app. Its `chiroru-su` client is a static ARMv7 executable.

## ABI and symbols

```text
vermagic=5.4.125-android11-2-g85fe5498964f-ab1982735225 SMP preempt mod_unload modversions aarch64
module_layout CRC = 0x8395960f
selinux_state      = _stext + 0x147CB10
selinux_blob_sizes = _stext + 0xFE253C
```

The two data symbols are patched from the live `_stext`; other undefined symbols
are resolved through `/proc/kallsyms`. The CRC matches the factory `mali.ko`.
Asset paths and hashes are in
[`profiles.json`](../app/Chiroru/app/src/main/assets/firmware/profiles.json).

## Build

The module uses prepared Linux 5.4.125 headers. Requirements and ABI checks:
[KERNEL_MODULES.md](../tools/KERNEL_MODULES.md).

From the repository root:

```sh
python3 tools/build-kernel-modules.py 1982735225 /path/to/prepared-kernel \
  /tmp/opencode/factory-adb --module adb
```

To build the module and static ARMv7 client:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk \
  bash adb-root/build.sh 1982735225 /path/to/prepared-kernel /tmp/opencode/factory-adb-client
```

The builder sets `CHIRORU_KERNEL_RELEASE` at compile time and verifies the
release guard, vermagic and CRC in the resulting ELF.
