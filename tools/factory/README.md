# Factory 1982735225: KernelSU Next

This branch targets exactly `chiron-1982735225`, Android 11, kernel
`5.4.125-android11-2-g85fe5498964f-ab1982735225`, 32-bit ARM userspace.
The existing catalog and native identity gates remain mandatory.

## Use

1. Install the factory armv7 KernelSU Next manager built below
   (`com.rifsxd.ksunext`). The official 64-bit APK cannot run on this userspace.
2. Install this branch's Chiroru APK. After a fresh boot, select KernelSU.
3. The loader authenticates the manager certificate, loads the module, installs
   the matching `ksud`, and runs post-fs-data, services and boot-completed.

The module is loaded into RAM. After reboot, run Chiroru again. Userspace and
module files live under `/data/adb`; no boot image, bootloader or OTA flashing
is part of this procedure. Preinit/initrc generation is disabled in late-load
mode. Existing boot settings are not changed by installing these APKs.

The factory manager has a separate signing certificate. Its SHA-256 is
`93ef8c5f1fe906ffc294d5091c75b194dc64967cd53649aecbeda6b41ec96a3a`, DER size
`804` (`0x324`). The private signing key is not part of the repository.
Install the manager before loading KSU. Updating it to an official APK is not
a compatible update. A manager signed with your own key requires rebuilding
the factory module with that certificate's size and digest.

## Compatibility changes

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

The kernel module is a cross-build against prepared 5.4.125 headers, not a
claim to possess the exact factory kernel source. Its vermagic and
`module_layout=0x8395960f` were checked against factory modules.

## Rebuild

### Kernel module

Provide the prepared factory module header tree (see also
`adb-root/FACTORY-MODULE.md`), then run from the repository root:

```sh
bash tools/factory/build-module.sh /path/to/factory-headers /tmp/opencode/factory-module-new
```

The script copies tracked `build-txn` source, applies `kernel.patch`, builds,
and strips debug information while verifying runtime sections and relocations.
Set `KSU_NEXT_MANAGER_SIZE` and `KSU_NEXT_MANAGER_HASH` when using your own
manager signing certificate. Re-pin the resulting asset in `profiles.json`.
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
installation and early-boot/preinit features are not supported by this port.

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

## Hardware verification

- Factory KSU loaded through Chiroru's exact-profile path after reboot.
- `su -c 'id; getenforce'`: UID 0, `u:r:ksu:s0`, Enforcing.
- Matching manager shows Working / LKM / Jailbreak, UAPI 5.
- A temporary app was denied root, granted it through the manager, then denied
  again after revocation. The temporary APK was removed.
- A temporary module installed through `ksud` ran all three scripts after a
  reboot and loading KSU through Chiroru alone: `post-fs-data`, `service` and
  `boot-completed` each reported UID 0 and Enforcing. The test module and its
  ZIP/log files were removed afterwards.
- Chiroru reports the successful load, but its independent runtime query can
  show unavailable: app seccomp blocks the read-only driver query. The manager
  and `ksud debug info` confirm the running driver.
- Zygisk/LSPosed compatibility is not established on factory.

Raw device logs and personal identifiers are kept outside this repository.
