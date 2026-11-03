# Chiroru

Android-приложение для Яндекс Станции Дуо Макс (`chiron`, `YNDX-00055`):
диагностика, In-app root shell, ADB root shell и KernelSU.
Root разрешён только при точном совпадении проверенного профиля прошивки.
Текущая сборка — **0.7.3-rc2**. Один APK содержит аппаратно проверенные профили
**5000001307**, **5000001245**, factory **1982735225** и тестовый **5000001248**
(`OTA test`: KernelSU проверен, остальные режимы ожидают проверки). Профиль и native ABI выбираются
автоматически. Для каждого объявлены In-app root, ADB root и KernelSU.
Основания адаптации нового профиля: [5000001248](tools/ota/5000001248.md).

Для factory сначала нажмите **«Установить совместимый менеджер»** в Chiroru:
подписанный armv7 APK включён в приложение. После установки вернитесь в Chiroru
и запустите KernelSU. Подпись и версия проверяются до изменения ядра.
На `5000001307`, `5000001245` и кандидате `5000001248` отсутствующий официальный менеджер **v3.4.2**
устанавливается после загрузки KSU. Менеджеры разных профилей не взаимозаменяемы.
[Технические сведения о factory](tools/factory/README.md).

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
Скрипты сборки `chiroru_adb.ko` и `kernelsu.ko` для всех четырёх профилей,
требования к заголовкам и команды: [KERNEL_MODULES.md](tools/KERNEL_MODULES.md).
См. также [SOURCES.md](app/Chiroru/SOURCES.md) и [vendor provenance](linux-txn/README.md).

Проверка AIO использует сохранённый дизассемблированный `kill_ioctx` с хешами
исходного ELF и выдержки. Для проверки полного локального ELF задайте
`CHIRORU_KERNEL_ELF=/path/to/chiron-vmlinux.elf` и установите aarch64 binutils.

На `5000001307` автозагрузка KernelSU проверена с задержкой 120 секунд,
SELinux Enforcing. LSPosed после поздней загрузки Zygisk требует отдельного
перезапуска Android framework; автоматического перезапуска в приложении нет.
На `5000001245` на чистом устройстве проверены In-app root, ADB root и KSU
(lifecycle complete, менеджер v3.4.2, UAPI 5), SELinux Enforcing.
На factory проверены root, выдача/отзыв прав KSU и три этапа модульных скриптов;
совместимость Zygisk/LSPosed пока не установлена.
