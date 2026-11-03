# Chiroru

Android-приложение для Яндекс Станции Дуо Макс (`chiron`, `YNDX-00055`):
диагностика, In-app root shell, ADB root shell и KernelSU.
Root разрешён только при точном совпадении проверенного профиля прошивки.
Версия этой ветки — **0.7.0**; профили — **5000001307** и factory **1982735225**.
Factory KernelSU Next требует отдельного armv7-менеджера:
[установка, проверка и сборка](tools/factory/README.md).

Собственный код приложения — [MIT](LICENSE). Лицензии native-компонентов,
KernelSU и Linux сохранены: [состав и исключения](THIRD_PARTY_NOTICES.md).

## APK из GitHub Actions

Откройте **Actions → Build APK → последний успешный запуск → Artifacts → Chiroru-apk**.
Архив содержит `Chiroru.apk`, `SHA256SUMS` и сведения о сертификате.
APK подписан тем же ключом, что установленная версия; обновление сохраняет данные.
Артефакты хранятся 30 дней. Ручной запуск: **Run workflow → main**.

Push и pull request запускают сборку, JVM/UI-тесты, native host-тесты и release lint.
Подписанный APK выпускается только для `main` при push или ручном запуске.
Pull request получает unsigned APK и отчёты, без доступа к ключу подписи.
R8 mapping доступен в отдельном артефакте `build-output`.

Подпись выполняется в отдельной job после успешных проверок. Ключ и параметры
хранятся в Actions Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`.
Замена этих secrets на другой ключ нарушит совместимость обновлений.

## Локальная сборка

Нужны Linux, JDK 17, Python 3, C-компилятор, Android SDK platform/build-tools 36,
NDK `28.2.13676358`, CMake `3.22.1`. Gradle Wrapper `8.14.3` включён.

```sh
cd app/Chiroru
./build-apk.sh :app:testDebugUnitTest :app:lintRelease
```

Результат: `app/Chiroru/Chiroru.apk`. Локально используется `$HOME/.android/debug.keystore`;
на другой машине он не совпадёт с CI-ключом автоматически.
Для unsigned APK: `./gradlew :app:assembleRelease -Pchiroru.unsigned=true`.

## Состав

- [`app/Chiroru`](app/Chiroru/README.md) — Kotlin/Compose, JNI, каталог прошивок и проверенные payloads.
- [`root-console`](root-console/README.md) — приватный userspace broker и host-тесты.
- [`adb-root`](adb-root/README.md) — исходники отдельного ADB-модуля и клиента.
- `build-txn` — изменённые исходники KernelSU, включая lifecycle и SELinux transaction.
- `linux-txn` — vendor SELinux-исходники и generated flask header для userspace policy builder.
- `tools` — генерация/проверка policy adapters и host-тесты.

Checkout самодостаточен для сборки APK. CI пересобирает приложение и userspace
native-компоненты; `kernelsu.ko`, `chiroru_adb.ko`, `ksud` и `chiroru-su` берутся
из проверенных ресурсов, их SHA-256 проверяется перед сборкой.
Полная пересборка kernel-модулей требует отдельно подготовленного vendor kernel
build tree; его конфигурация и бинарные device evidence в этот checkout не входят.
См. [SOURCES.md](app/Chiroru/SOURCES.md) и [vendor provenance](linux-txn/README.md).

Проверка AIO использует сохранённый дизассемблированный `kill_ioctx` с хешами
исходного ELF и выдержки. Для проверки полного локального ELF задайте
`CHIRORU_KERNEL_ELF=/path/to/chiron-vmlinux.elf` и установите aarch64 binutils.

Автозагрузка KernelSU проверена с задержкой 120 секунд, SELinux Enforcing.
LSPosed после поздней загрузки Zygisk требует отдельного перезапуска Android
framework; автоматического перезапуска в приложении нет.
