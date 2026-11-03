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

The exact factory source commit (`g85fe5498964f`) is not available on the
station. The module is a cross-build against the prepared 5.4.125 headers used
for the verified `5000001307` modules:

```sh
cp -a <prepared-linux-txn> /tmp/linux-txn-factory
printf '5.4.125-android11-2-g85fe5498964f-ab1982735225\n' \
    > /tmp/linux-txn-factory/include/config/kernel.release
printf '#define UTS_RELEASE "5.4.125-android11-2-g85fe5498964f-ab1982735225"\n' \
    > /tmp/linux-txn-factory/include/generated/utsrelease.h
make -C /tmp/linux-txn-factory M="$PWD/adb-root/module" ARCH=arm64 \
    CROSS_COMPILE=aarch64-linux-gnu- KBUILD_MODPOST_WARN=1 modules
```

`main.c` hard-codes the `5000001307` release guard. For the factory artifact the
same-length string in the built `.ko` is replaced with
`5.4.125-android11-2-g85fe5498964f-ab1982735225`, then the debug info is
stripped. The runtime checks (`module_layout`, `BUILD_BUG_ON` for `cred`,
`task_struct`, `file`) are unchanged.

Client:

```sh
armv7a-linux-androideabi30-clang -static -O2 adb-root/client.c -o chiroru-su
llvm-strip --strip-debug chiroru-su
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
