# Factory 1982735225 ADB module

The factory build has a 32-bit-only userspace and no `linker64`, so the bundled
aarch64 `ksud` cannot run. The factory ADB path therefore:

- patches the module's data symbols (`selinux_state`, `selinux_blob_sizes`) from
  the live `_stext`;
- resolves the remaining undefined text symbols from `/proc/kallsyms`;
- calls `init_module()` directly from the native loader;
- installs a **static 32-bit** `chiroru-su` client.

## Artifacts

| file | SHA-256 |
|---|---|
| `chiroru_adb.ko` | `f41bb8bd85f35895c7ec8bc9a7bc4be5d5bbe8e778ceb42023e8df0b2db517f7` |
| `chiroru-su` | `fbde93588765d2970056cb381ef28b61e6c12f013ef41e8c0308b00b458a1e85` |

`chiroru_adb.ko` has:

```
vermagic=5.4.125-android11-2-g85fe5498964f-ab1982735225 SMP preempt mod_unload modversions aarch64
__versions: module_layout = 0x8395960f
selinux_state, selinux_blob_sizes: UND (patched at runtime)
```

The factory kernel's `factory-mali.ko` carries the same `module_layout` value.

## Build

The module is a cross-build against the prepared 5.4.125 headers used for the
verified modules. See [kernel module builds](../tools/KERNEL_MODULES.md) for
the external inputs, checks and all three firmware profiles. From the root:

```sh
python3 tools/build-kernel-modules.py 1982735225 /path/to/prepared-kernel \
    /tmp/opencode/factory-adb --module adb
```

The builder selects the exact release guard at compile time through
`CHIRORU_KERNEL_RELEASE`, then verifies it in the output ELF along with
vermagic and `module_layout`. No post-build string replacement is required.

To build both the module and its static ARMv7 client with NDK r28c:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk \
    bash adb-root/build.sh 1982735225 /path/to/prepared-kernel /tmp/opencode/factory-adb-client
```

## Runtime addresses

`selinux_state` and `selinux_blob_sizes` are not exported and are not in the
text-only kallsyms. The factory backend patches them to:

```
selinux_state      = _stext + 0x147CB10
selinux_blob_sizes = _stext + 0xFE253C
```

`_stext` comes from `/proc/kallsyms` after the credential stage; the addresses
are not hard-coded to a boot.

## Verification

Cold-boot test on `Yandex/chiron/chiron:11/RQ3A.210805.001.A1/1982735225`:

```
/data/local/tmp/chiroru-su --status   -> Chiroru ADB root: available (API 1)
/data/local/tmp/chiroru-su -c 'id'    -> uid=0(root) ... context=u:r:chiroru_adb:s0
getenforce                            -> Enforcing
```

The module is not persistent; after a reboot ADB root must be enabled again.
