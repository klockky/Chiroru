# License scope and third-party components

The root [MIT license](LICENSE) applies to original Chiroru application code,
including its Kotlin/Compose UI, tests, project configuration and documentation,
except where a file or component carries another license. It does not replace
upstream licenses or relicense GPL-derived code.

| Component | License / notices |
| --- | --- |
| `build-txn/` and bundled `kernelsu.ko` | GPL-2.0; see `build-txn/LICENSE` and individual headers |
| `linux-txn/` | Linux licenses as indicated by SPDX headers; license texts under `linux-txn/source/LICENSES/` |
| `root-console/`, `adb-root/`, bundled `chiroru_adb.ko` and `chiroru-su` | GPL-2.0-only; existing headers retained; text in `build-txn/LICENSE` |
| `app/Chiroru/app/src/main/cpp/` | Native components include GPL-2.0-only console code and GPL-2.0 Arm Mali headers; the root MIT grant does not relicense this native subtree |
| Generated policy adapters and policy-derived code in `tools/` | Retain the applicable Linux/KernelSU GPL licenses; generated output carries source notices |
| Bundled `ksud-3.4.2` | KernelSU-Next userspace, GPL-3.0; see `LICENSES/KernelSU-Next-GPL-3.0.txt` and upstream source at https://github.com/KernelSU-Next/KernelSU-Next |
| Factory `ksud`, manager and `tools/factory/userspace.patch` | KernelSU-Next userspace, GPL-3.0; pinned upstream source and modifications are documented in `tools/factory/README.md` |
| `tools/factory/kernel.patch` and factory `kernelsu.ko` | GPL-2.0; derived from `build-txn/`, whose notices remain applicable |
| BusyBox embedded in factory `ksud` | GPL-2.0; the unmodified static AArch64 asset from the pinned KernelSU-Next source; https://github.com/KernelSU-Next/KernelSU-Next and https://github.com/topjohnwu/BusyBox |
| Gradle Wrapper | Apache-2.0; notices in wrapper scripts and JAR retained |
| AndroidX, Kotlin and other downloaded dependencies | Their respective upstream licenses; resolved by Gradle |

The APK contains components under multiple licenses; the root MIT license is
not a claim that the complete APK or every bundled native component is MIT-only.
Existing copyright notices and attribution emails belong to upstream authors.
