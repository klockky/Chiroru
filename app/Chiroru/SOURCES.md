# Состав Chiroru

```text
Chiroru/
├── app/src/main/
│   ├── java/dev/chiron/chiroru/   Kotlin: Activity, ViewModel, загрузчик, сервис, receiver
│   │   ├── firmware/             определение прошивки, каталог, ресурсы, запуск
│   │   └── ui/                   Compose-экраны, тема, векторные значки
│   ├── cpp/
│   │   ├── native_bridge.c       JNI-транспорт
│   │   ├── backend_registry.*    реестр скомпилированных реализаций
│   │   └── backends/             диагностика Mali JM и загрузчики прошивок
│   ├── assets/
│   │   ├── firmware/             profiles.json и модули отдельных профилей
│   │   └── common/               общие версионированные ресурсы
│   ├── res/                      строки EN/RU, темы, иконки
│   └── AndroidManifest.xml
├── app/src/test/                 проверки результатов и UI
├── app/src/androidTest/          проверки private IO и GitHub APK на Android
├── gradle/wrapper/               Gradle Wrapper с SHA-256 дистрибутива
└── build-apk.sh                  release: Chiroru.apk; --debug: Chiroru-debug.apk
```

`cpp/` основан на `jni/` исходного приложения. Начиная с 0.4.0 JNI-экспорты
находятся в `native_bridge.c`, а реестр выбирает реализацию по ID профиля.
Бывший `mali36449.c` перенесён в `backends/chiron_5000001307/root.c`: здесь
сосредоточены адреса, ABI ядра, запуск хуков KSU, распознавание уже загруженного
модуля, очистка окон памяти и обработка ошибок хуков. Диагностика из `maliprobe.c`
находится в `backends/mali_jm_probe.c`. `process_wait.c/.h` обеспечивают общее
ожидание дочерних процессов с таймаутом. Библиотека собирается как `libchiroru.so`.

`assets/firmware/chiron-5000001307/kernelsu.ko` — проверенная на chiron 5000001307 транзакционная сборка
`duomax-ksu/kernelsu-policy-transaction.ko` из `build-txn/`, SHA-256
`b4a66c69cd199276f363ff8f26deec7c5d0057b4b004a1ba50c737f4e5ca6088`.
Публикуемый модуль очищен `tools/sanitize-module.py`: удалены debug-секции,
пути сборочной машины в диагностических строках заменены на `/src/build-txn/…`.
Ведущие `/` сохраняют длины строк и смещения суффиксов. Скрипт сверяет runtime
секции, таблицу символов и релокации с исходным модулем; машинный код не меняется.
Исходный модуль проверен на станции; очищенная упаковка проверяется статически
и при сборке APK, отдельной загрузки очищенного `.ko` на станции не проводилось.
Транзакционная политика включена с 0.3.5; с 0.6.5 модуль также реализует
`EVENT_SERVICES` UAPI 5: первый вызов возвращает 1, последующие — 0,
`post-fs-data` сбрасывает допуск для нового lifecycle, включая soft reboot.
Обработчик проверяется host-тестом `tools/test_lifecycle_events.py` с
конкурентными вызовами; он входит в `tools/build-policy-transaction.sh`.
Явный `GRANT_ROOT` от процесса с real/effective UID 0 и SELinux SID исходного
загрузчика модуля переводит его в домен `ksu`, сохраняя остальные credentials.
Другие UID-0 домены этим исключением не допускаются. Ограничение no-new-privileges учитывается;
ошибка подготовки контекста не публикует credentials. Chiroru запрашивает этот
переход только в дочернем процессе перед lifecycle и проверяет фактический SID.
`assets/common/ksud-3.4.2` — CLI KernelSU-Next 3.4.2
(UAPI 5), снятый с тестовой колонки для совпадения с её ядром. Оба файла включены
в проект. Native policy builder дополнительно использует `root-console/`,
`tools/`, `build-txn/` и `linux-txn/` из корня этого репозитория.
Пути, SHA-256 и признаки поддерживаемой прошивки связаны в
`assets/firmware/profiles.json`. Проверка перед сборкой сверяет каталог,
реестр native-реализаций и содержимое ресурсов. Расширение описано в [FIRMWARE.md](FIRMWARE.md).
С версии 0.4.1 каталог использует схему 2: обязательный `root.backend` и
необязательный блок `kernelSu` со своей реализацией и двумя ресурсами.

Gradle Wrapper скопирован из существующего Gradle-проекта в рабочей области,
дистрибутив закреплён на 8.14.3 с проверкой SHA-256. Внешние библиотеки
загружаются Gradle из официальных репозиториев.

`ManagerReleases`, `ManagerProvisioning` и `ManagerInstaller` проверяют наличие KSU Next
Manager, выбирают и проверяют официальный GitHub release APK и устанавливают отсутствующий
пакет после загрузки KernelSU. `AppIo` сохраняет открытые дескрипторы для скачивания
и boot-отчёта до смены credentials. Нативные исходники этой функцией не меняются.
