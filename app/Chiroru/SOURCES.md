# Исходники и компоненты

## Приложение

Пути относительно `app/Chiroru/`:

| Каталог или файл | Назначение |
|---|---|
| `app/src/main/java/dev/chiron/chiroru/` | Интерфейс, загрузчик, автозагрузка и установка менеджера |
| `…/firmware/` | Выбор профиля, проверка и извлечение ресурсов |
| `…/ui/` | Compose-экраны |
| `app/src/main/cpp/native_bridge.c` | JNI |
| `app/src/main/cpp/backend_registry.*` | Реестр нативных реализаций |
| `app/src/main/cpp/backends/` | Root и диагностика Mali |
| `app/src/main/assets/firmware/` | Каталог прошивок, модули и factory-ресурсы |
| `app/src/main/assets/common/` | Общие клиенты и демоны |
| `app/src/test/`, `app/src/androidTest/` | JVM/UI-тесты и проверки на Android |
| `app/scripts/` | Проверки каталога, APK и нативного кода |
| `build-apk.sh` | Сборка release или debug APK |

Алгоритм root находится в `backends/chiron_5000001307/root.c`, диагностика —
в `backends/mali_jm_probe.c`. Они входят в `libchiroru.so`.
`process_wait.c` ограничивает время работы дочерних процессов.

## Компоненты в корне репозитория

| Каталог | Назначение |
|---|---|
| `third_party/kernelsu/` | Сабмодуль форка KernelSU-Next с адаптацией SELinux и обработкой служб UAPI 5 |
| `linux-txn/` | Vendor SELinux parser и заголовки для обработки политики |
| `root-console/` | Процессная root-консоль и работа с политикой в userspace |
| `adb-root/` | Модуль `chiroru_adb` и клиент `chiroru-su` |
| `tools/policy_host/` | Адаптеры и host-проверки SELinux-кода |
| `tools/factory/` | Патчи и сборка factory KernelSU, демона и менеджера |
| `tools/ota/` | Извлечение ядра из OTA и поиск таблицы системных вызовов |

`third_party/kernelsu/kernel/` обрабатывает `EVENT_SERVICES`: первый вызов разрешает запуск служб,
повторные пропускаются. `post-fs-data` открывает новый цикл. Перед выполнением
скриптов дочерний процесс загрузчика запрашивает `GRANT_ROOT` и проверяет переход
в `u:r:ksu:s0`.

## Бинарные ресурсы

Пути и контрольные суммы заданы в
[`profiles.json`](app/src/main/assets/firmware/profiles.json).
Каждая прошивка имеет свои `kernelsu.ko` и `chiroru_adb.ko`.

- Модули собираются из `third_party/kernelsu/kernel/` и `adb-root/module/`; factory использует
  дополнительный `tools/factory/kernel.patch`.
- Общий `ksud-3.4.2` — ARM64 CLI KernelSU-Next с UAPI 5, полученный с тестовой станции.
- Factory-демон и менеджер собираются из закреплённого upstream-коммита
  с `tools/factory/userspace.patch`.
- `sanitize-module.py` удаляет debug-секции и локальные пути, проверяя сохранность
  исполняемых секций, символов и релокаций.

Сборка APK использует готовые ресурсы. Для их пересборки см.
[KERNEL_MODULES.md](../../tools/KERNEL_MODULES.md) и
[factory README](../../tools/factory/README.md).
Происхождение vendor-исходников описано в [linux-txn](../../linux-txn/README.md).

## Установка менеджера

`ManagerReleases` выбирает закреплённый официальный релиз.
`ManagerPackages` проверяет пакет, версию и сертификат.
`ManagerProvisioning` и `ManagerInstaller` скачивают и устанавливают APK.
Factory-менеджер передаётся системному установщику через FileProvider.

`AppIo` открывает дескрипторы для скачивания и boot-отчёта до изменения
credentials. Проверка APK и передача в `pm install` идут через `/proc/self/fd/N`:
повторное открытие приватного пути приложения после root может быть запрещено.
На целевом Android 11 установка использует `pm install --user 0 -S <размер>`
со stdin, без отдельного аргумента `-`.

## CI и подпись

Workflow `.github/workflows/build-apk.yml` собирает APK, запускает JVM/UI-тесты,
host-тесты и release lint. Подпись выполняется отдельной job только для `main`
при push или ручном запуске. Pull request не получает ключ подписи.

Секреты Actions: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`. Замена ключа нарушает
совместимость обновлений. Подписанный APK сохраняется в `Chiroru-apk`,
R8 mapping — в `build-output`.

Лицензии компонентов: [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
