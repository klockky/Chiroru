# Сборка kernel-модулей Chiroru

`build-kernel-modules.py` собирает `chiroru_adb.ko` и `kernelsu.ko` для каждого
из четырёх точных профилей (включая OTA-кандидат `5000001248`). Исходники модулей находятся в `adb-root/module/` и
`build-txn/`; factory-изменения KernelSU — в `tools/factory/kernel.patch`.
Все kernel-модули — **AArch64**, включая factory с 32-битным userspace.

## Зависимости

- Linux, Python 3.10+, Git, GNU Make, `patch`.
- AArch64 GCC и binutils: `aarch64-linux-gnu-gcc`, `objcopy`, `nm`, `objdump`.
  Проверенная локальная сборка использует GCC **16.2.0**, GNU Make **4.4.1**.
- Полный Linux 5.4.125 source tree и **подготовленное vendor-совместимое
  дерево сборки**. В публичном `linux-txn/` находится только SELinux-подмножество
  для userspace policy builder, а не полное дерево для сборки `.ko`.
- Для отдельного ADB-клиента: Android NDK **28.2.13676358**, API 30.

Точный vendor source commit не восстановлен: исходное подготовленное дерево
было без Git metadata. Поэтому здесь документирована сборка с этим внешним
входом, а не получение идентичного ядра из произвольного upstream checkout.
Один `make modules_prepare` не восстанавливает vendor ABI и `Module.symvers`.

### Подготовленное дерево

Передайте каталог kernel **output**, содержащий:

```text
Makefile                         # Kbuild wrapper для полного source tree
source -> /path/to/kernel-source  # рабочая ссылка на полные исходники
.config
Module.symvers
include/config/{auto.conf,kernel.release}
include/generated/{autoconf.h,utsrelease.h,...}
arch/arm64/include/...
scripts/...                      # подготовленные Kbuild host tools
security/selinux/flask.h
```

После переноса дерева восстановите `source` и путь к исходному Makefile в
Kbuild wrapper. Пути сборки должны быть без пробелов. Дерево должно быть уже
подготовлено под ARM64, 4 KiB pages, modules, modversions и preemption;
`auto.conf` и `autoconf.h` должны согласовываться. Для `module_layout` требуется
CRC **`0x8395960f`**, проверенный по штатным модулям всех трёх прошивок.
В использованном дереве `Module.symvers` содержит эту единственную запись;
остальные undefined symbols разрешает загрузчик Chiroru. Поэтому ожидаются
предупреждения modpost об undefined symbols (`KBUILD_MODPOST_WARN=1`).

Контрольные суммы конфигурации дерева, использованного для проверки скрипта:

| Вход | SHA-256 |
|---|---|
| `.config` | `1f0d989dfca069ffc1a869f8a01970777aef718bf9746c1bc6e4ce3b239f46e9` |
| `include/config/auto.conf` | `7fa43a27c0b46f359560592be5588a52a3ddecd46accd16fdc3491354a1b7ee1` |
| `include/generated/autoconf.h` | `a90cb5e2f72f8ba45b50778e153fa0557fb2509372f5b585797f6671f98a1e81` |

Эти хеши фиксируют конфигурацию, а не все исходники и заголовки ядра.
Компиляционные `BUILD_BUG_ON` дополнительно проверяют используемые ABI offsets.

## Оба модуля одной командой

Из корня репозитория, с новым каталогом результата для каждого запуска:

```sh
python3 tools/build-kernel-modules.py 5000001307 /path/to/prepared-kernel /tmp/opencode/modules-1307
python3 tools/build-kernel-modules.py 5000001245 /path/to/prepared-kernel /tmp/opencode/modules-1245
python3 tools/build-kernel-modules.py 1982735225 /path/to/prepared-kernel /tmp/opencode/modules-factory
python3 tools/build-kernel-modules.py 5000001248 /path/to/prepared-kernel /tmp/opencode/modules-1248
```

Можно использовать одно проверенное подготовленное дерево для всех четырёх команд.
Скрипт копирует его в `OUTPUT/work/headers`, устанавливает release выбранного
профиля в этой копии и собирает из чистой копии отслеживаемых исходников.
Изменения существующих файлов working tree учитываются; новые исходники должны
быть добавлены в Git index. Полные исходники ядра остаются внешней зависимостью.

| Прошивка | Kernel release | KernelSU |
|---|---|---|
| `5000001307` | `5.4.125-android11-2-g601513d4b115-ab5000001307` | `v3.4.0-legacy`, version 34000 |
| `5000001245` | `5.4.125-android11-2-g601513d4b115-ab5000001245` | `v3.4.0-legacy`, version 34000 |
| `1982735225` | `5.4.125-android11-2-g85fe5498964f-ab1982735225` | factory patch, `v3.4.2-factory`, version 34000 |
| `5000001248` | `5.4.125-android11-2-g601513d4b115-ab5000001248` | OTA candidate, `v3.4.0-legacy`, version 34000 |

ADB release guard задаётся через `CHIRORU_KERNEL_RELEASE` при компиляции.
Замена строк в готовом ELF больше не нужна. Для factory включаются compat hooks
и проверка сертификата менеджера; `KSU_NEXT_MANAGER_HASH` и
`KSU_NEXT_MANAGER_SIZE` позволяют собрать модуль для своего сертификата
(SHA-256 DER и его размер в hex). По умолчанию используется сертификат из
factory-профиля. Приватный ключ для сборки модулей не требуется.

Выбор одного модуля: `--module adb` или `--module ksu`. Альтернативный префикс
GCC/binutils задаётся через `--cross-compile`.

### Результат

В `OUTPUT/` находятся:

- `chiroru_adb.ko` и/или `kernelsu.ko` — очищенные от debug-секций модули;
- `build-info.json` — профиль, compiler version, хеши сборщика, каталога,
  исходников и подготовленной конфигурации, параметры сертификата factory;
- `SHA256SUMS` — хеши модулей и манифеста;
- `work/` — локальные исходники, заголовки, объекты и журналы зависимостей Kbuild.

Для публикации предназначены `.ko`, `build-info.json` и `SHA256SUMS`.
`work/` содержит локальные пути и внешние build inputs. Скрипт проверяет
AArch64 ELF, точный vermagic, CRC `module_layout`, runtime guard ADB,
сохранность runtime sections/relocations при stripping и отсутствие личных
путей в публикуемых файлах. Хеши новой сборки могут отличаться от упакованных
из-за compiler version, build ID и нормализации диагностических путей.

## Отдельные точки входа

```sh
# ADB-модуль и клиент: arm64 PIE для 1307/1245, static armv7 для factory
ANDROID_NDK_HOME=/path/to/android-ndk \
  bash adb-root/build.sh 5000001245 /path/to/prepared-kernel /tmp/opencode/adb-1245

# KernelSU с host/binary-проверками; необязательный четвёртый аргумент — policy snapshot
bash tools/build-policy-transaction.sh 5000001245 /path/to/prepared-kernel /tmp/opencode/ksu-1245

# Совместимая factory-точка входа; также создаёт kernelsu-factory.ko
bash tools/factory/build-module.sh /path/to/prepared-kernel /tmp/opencode/ksu-factory
```

Factory `ksud` и менеджер собираются по [отдельной инструкции](factory/README.md#daemon-and-manager).

## Проверки и включение в APK

```sh
python3 -m unittest -v tools/test_kernel_module_build.py tools/test_lifecycle_events.py
CHIRORU_MODULE_WORK=/tmp/opencode/modules-1245/work \
  python3 -m unittest -v tools/test_policy_transaction.py
```

Для необязательной сверки с полным проверенным ELF ядра `1307` задайте
`CHIRORU_KERNEL_ELF`. Без него пропускается только сравнение с внешним ELF;
проверки собранных объектов выполняются.

Для обновления APK перенесите проверенные модули в
`app/Chiroru/app/src/main/assets/firmware/chiron-BUILD/` и обновите их SHA-256
в `profiles.json`. Затем выполните `verify_firmware_catalog.py`,
`test_native_firmware_profiles.py`, APK-проверки и аппаратные проверки
соответствующего профиля. Сборка сама не обновляет каталог ресурсов.

Скрипт проверен реальной сборкой модулей всех четырёх профилей. Для первых
трёх пар `.text` совпала с упакованными модулями 0.7.2; различия runtime sections
ограничены build ID и диагностическими строками путей. Новый профиль `1248`
проверен статически по OTA и запуском KernelSU на устройстве в rc2;
аппаратная проверка ADB root ожидается. CI запускает проверки выбора
профиля и ELF на упакованных модулях; полная cross-сборка требует внешнего
подготовленного дерева.
