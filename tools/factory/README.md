# Factory: KernelSU Next

This profile supports `chiron-1982735225`, Android 11, kernel
`5.4.125-android11-2-g85fe5498964f-ab1982735225`, 32-bit ARM userspace.
The loader checks the full firmware identity before running.

Profile `1926931523` uses kernel
`5.4.125-android11-2-g01f6e99e580b-ab1926931523` and distinct modules with the same
ARMv7 client, daemon and manager. KernelSU and autoload are user-reported working;
In-app and ADB root remain unverified. See [evidence](../ota/1926931523.md). Build it with
`tools/build-kernel-modules.py 1926931523 HEADERS NEW_OUTPUT`.

## Use

1. Install the universal Chiroru APK. On factory, use **Install matching manager**
   to install its bundled armv7 KernelSU Next manager (`com.rifsxd.ksunext`).
   Allow installation from Chiroru if Android asks, then press the button again.
   The official 64-bit APK cannot run on this userspace.
2. Return to Chiroru. After a fresh boot, select KernelSU.
3. The loader authenticates the manager certificate, loads the module, installs
   the matching `ksud`, and runs post-fs-data, services and boot-completed.

The module stays in RAM until reboot. Userspace and module files live under
`/data/adb`. This late-load setup does not flash boot images or generate
preinit/initrc files.

The factory manager has a separate signing certificate. Its SHA-256 is
`93ef8c5f1fe906ffc294d5091c75b194dc64967cd53649aecbeda6b41ec96a3a`, DER size
`804` (`0x324`). The private signing key is not part of the repository.
Install the manager before loading KSU. Updating it to an official APK is not
a compatible update. A manager signed with your own key requires rebuilding
the factory module with that certificate's size and digest.

## ABI requirements

- Separate AArch32 syscall-table hooks, including `setresuid32` (208).
- AArch32 stack pointer from `pt_regs.regs[13]`; execveat flags from argument 5.
- Manager UID resolved from Android's package database after certificate
  verification; no device-specific UID seed in the factory module.
- One-shot vendor SHA-256 function avoids incompatible `crypto_shash` layouts.
- Native ELF64 relocation and direct `init_module` from the 32-bit app.
- `kptr_restrict` is restored after relocation; lifecycle runs in `u:r:ksu:s0`.
- Module, manager and daemon use **UAPI 5**. A UAPI 6 daemon returns success
  while skipping lifecycle on this driver, so it must not be substituted.

Factory stext-relative symbols: `sys_call_table=0xEEE548`,
`compat_sys_call_table=0xEF2FD0`, `init_mm=0x12BE860`,
`selinux_state=0x147CB10`, `selinux_blob_sizes=0xFE253C`.
Runtime KASLR addresses are derived afresh; none is persisted.

The kernel module is built against prepared 5.4.125 headers; the exact factory
source revision is unavailable. Its vermagic and
`module_layout=0x8395960f` were checked against factory modules.

## Rebuild

### Kernel module

Provide a verified prepared module header tree (see
[kernel module builds](../KERNEL_MODULES.md) and [factory ADB module](../../adb-root/FACTORY-MODULE.md)),
then run from the repository root:

```sh
bash tools/factory/build-module.sh /path/to/factory-headers /tmp/chiroru-factory-module-new
```

The script delegates to the shared profile builder: it copies the prepared
headers and tracked source, sets the factory release in that copy, applies
`kernel.patch`, builds, and strips debug information while verifying runtime
sections and relocations. Results include `kernelsu.ko`, its compatibility
alias `kernelsu-factory.ko`, `SHA256SUMS` and `build-info.json`.
Set `KSU_NEXT_MANAGER_SIZE` and `KSU_NEXT_MANAGER_HASH` when using your own
manager signing certificate. Re-pin the resulting asset in `profiles.json`,
including the manager APK hash, certificate and version in `kernelSu.manager`.
Reboot between module revisions; live unloading is not a tested rollback path.

### Daemon and manager

Use KernelSU-Next commit
`204f007164905395e280e9290cc7774f57af8c61` (the UAPI 5 source snapshot):

```sh
git clone https://github.com/KernelSU-Next/KernelSU-Next.git /path/to/ksu-factory
git -C /path/to/ksu-factory checkout --detach 204f007164905395e280e9290cc7774f57af8c61
git -C /path/to/ksu-factory apply /path/to/Chiroru/tools/factory/userspace.patch
bash tools/factory/build-userspace.sh /path/to/ksu-factory
```

Requires Rust with the `armv7-linux-androideabi` target and NDK r28c.
The script remaps build-machine paths, copies the resulting daemon into the
manager's armv7 JNI assets, and includes upstream's **static AArch64 BusyBox**.
The factory kernel can execute that binary without `linker64`; the dynamically
linked AArch64 `bootctl` and `waitsys` are deliberately not bundled. Boot-image
installation and early-boot/preinit features are not supported.

Build the manager with JDK 21, Android SDK 36 and Gradle from that checkout:

```sh
export CHIRORU_NDK=28.2.13676358
# Set these externally; never put signing secrets in tracked gradle.properties:
# ORG_GRADLE_PROJECT_KEYSTORE_FILE, ORG_GRADLE_PROJECT_KEYSTORE_PASSWORD,
# ORG_GRADLE_PROJECT_KEY_ALIAS, ORG_GRADLE_PROJECT_KEY_PASSWORD
cd /path/to/ksu-factory/manager
./gradlew :app:assembleRelease
```

The public Chiroru APK build uses the pinned factory payloads and does not need
Rust or kernel headers. Source patches retain KernelSU's GPL licenses.

## Limitations

App seccomp can block Chiroru's runtime query. Use the manager or
`ksud debug info` to check the driver. Zygisk/LSPosed compatibility is untested.
