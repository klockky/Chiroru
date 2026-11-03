# Сборка модулей ядра

`build-kernel-modules.py` собирает `chiroru_adb.ko` из `adb-root/module/`
и `kernelsu.ko` из `build-txn/`. Для factory применяется `tools/factory/kernel.patch`.
Все модули — AArch64, включая factory с 32-битным userspace.

## Зависимости

- Linux, Python 3.10+, Git, GNU Make, `patch`.
- AArch64 GCC и binutils.
- Полные исходники Linux 5.4.125 и подготовленное vendor-совместимое дерево сборки.
- Для клиента `chiroru-su` — Android NDK `28.2.13676358`, API 30.

`linux-txn/` содержит только SELinux-исходники для userspace. Полного дерева
ядра в репозитории нет. Исходное внешнее дерево не содержало Git metadata,
поэтому его точный vendor-коммит неизвестен. `make modules_prepare` на
произвольном upstream-ядре не воспроизводит нужный ABI и `Module.symvers`.

## Подготовленное дерево

Сборщику нужен каталог kernel output:

```text
Makefile                         # Kbuild wrapper
source -> /path/to/kernel-source
.config
Module.symvers
include/config/{auto.conf,kernel.release}
include/generated/{autoconf.h,utsrelease.h,...}
arch/arm64/include/...
scripts/...
security/selinux/flask.h
```

После переноса проверьте ссылку `source` и путь в Makefile. Пути не должны
содержать пробелы. Требуются ARM64, страницы 4 KiB, modules, modversions
и preemption; `auto.conf` и `autoconf.h` должны соответствовать `.config`.

CRC `module_layout` — `0x8395960f`. В используемом `Module.symvers`
это единственная запись. Остальные
символы разрешает загрузчик Chiroru, поэтому предупреждения modpost об undefined
symbols ожидаемы (`KBUILD_MODPOST_WARN=1`). Смещения структур дополнительно
проверяются через `BUILD_BUG_ON`.

Хеши конфигурации дерева сборки:

| Файл | SHA-256 |
|---|---|
| `.config` | `1f0d989dfca069ffc1a869f8a01970777aef718bf9746c1bc6e4ce3b239f46e9` |
| `include/config/auto.conf` | `7fa43a27c0b46f359560592be5588a52a3ddecd46accd16fdc3491354a1b7ee1` |
| `include/generated/autoconf.h` | `a90cb5e2f72f8ba45b50778e153fa0557fb2509372f5b585797f6671f98a1e81` |

## Команды

Из корня репозитория, с новым каталогом результата для каждого запуска:

```sh
python3 tools/build-kernel-modules.py 5000001307 /path/to/prepared-kernel /tmp/opencode/modules-1307
python3 tools/build-kernel-modules.py 5000001245 /path/to/prepared-kernel /tmp/opencode/modules-1245
python3 tools/build-kernel-modules.py 5000001248 /path/to/prepared-kernel /tmp/opencode/modules-1248
python3 tools/build-kernel-modules.py 5000001289 /path/to/prepared-kernel /tmp/opencode/modules-1289
python3 tools/build-kernel-modules.py 1982735225 /path/to/prepared-kernel /tmp/opencode/modules-factory
```

Для одного модуля добавьте `--module adb` или `--module ksu`.
Префикс GCC/binutils задаётся через `--cross-compile`.

Скрипт копирует заголовки в `OUTPUT/work/headers`, задаёт release выбранного
профиля и собирает чистую копию отслеживаемых исходников. Изменения working tree
учитываются; новые исходники нужно добавить в Git index. Полные исходники ядра
остаются внешней зависимостью.

ADB release guard задаётся через `CHIRORU_KERNEL_RELEASE` при компиляции.
Factory-модуль использует compat hooks и проверку сертификата менеджера.
Для собственного сертификата задайте `KSU_NEXT_MANAGER_HASH` (SHA-256 DER)
и `KSU_NEXT_MANAGER_SIZE` (размер DER в hex). По умолчанию берутся значения
factory-профиля. Приватный ключ для сборки модуля не требуется.

### Другие точки входа

```sh
# ADB-модуль и клиент: arm64 PIE или статический armv7 для factory
ANDROID_NDK_HOME=/path/to/android-ndk \
  bash adb-root/build.sh 5000001245 /path/to/prepared-kernel /tmp/opencode/adb-1245

# KernelSU и проверки; четвёртым аргументом можно передать снимок SELinux policy
bash tools/build-policy-transaction.sh 5000001245 /path/to/prepared-kernel /tmp/opencode/ksu-1245

# Factory KernelSU, включая имя kernelsu-factory.ko для совместимости
bash tools/factory/build-module.sh /path/to/prepared-kernel /tmp/opencode/ksu-factory
```

Factory-демон и менеджер: [отдельная инструкция](factory/README.md#daemon-and-manager).

## Результат

| Файл | Содержимое |
|---|---|
| `chiroru_adb.ko`, `kernelsu.ko` | Модули без debug-секций |
| `build-info.json` | Профиль, компилятор, хеши входов и параметры сертификата |
| `SHA256SUMS` | Хеши модулей и манифеста |
| `work/` | Рабочие исходники, заголовки и объекты с локальными путями |

Для публикации используются `.ko`, манифест и контрольные суммы.
Сборщик проверяет ELF AArch64, vermagic, CRC, ADB release guard,
релокации и отсутствие личных путей. Хеш бинарника зависит от компилятора,
build ID и нормализации диагностических строк.

## Проверки и включение в APK

```sh
python3 -m unittest -v tools/test_kernel_module_build.py tools/test_lifecycle_events.py
CHIRORU_MODULE_WORK=/tmp/opencode/modules-1245/work \
  python3 -m unittest -v tools/test_policy_transaction.py
```

Для сверки с полным ELF ядра `1307` задайте `CHIRORU_KERNEL_ELF`.
Без него выполняются проверки собранных объектов, но пропускается сравнение
с внешним ELF.

Поместите модули в `app/Chiroru/app/src/main/assets/firmware/chiron-BUILD/`,
обновите SHA-256 в `profiles.json`, выполните проверки каталога, нативных профилей
и готового APK. Затем проверьте загрузку на целевой прошивке.
Сборщик не обновляет assets автоматически.
