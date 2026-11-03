# Chiroru

Root для Яндекс Станции Дуо Макс: консоль в приложении, ADB root и KernelSU.
Использует CVE-2022-36449 в драйвере ARM Mali, без прошивки boot-раздела.

## Прошивки

| Прошивка | In-app root | ADB root | KernelSU | Автозагрузка KSU |
|---|---|---|---|---|
| `5000001307` | Проверен | Проверен | Проверен | Проверена |
| `5000001245` | Проверен | Проверен | Проверен | Не проверена |
| `1982735225` (factory) | Проверен | Проверен | Проверен | Не проверена |
| `5000001248` | Проверен | Проверен | Проверен | Проверена |

Профиль выбирается автоматически. Неизвестные прошивки не поддерживаются.

## Установка

Скачайте APK: [Actions → Build APK](https://github.com/klockky/Chiroru/actions/workflows/build-apk.yml)
→ успешная сборка `main` → **Artifacts → Chiroru-apk**.

Установите APK, выберите способ в настройках и нажмите **«Получить root»**.
На factory сначала нажмите **«Установить совместимый менеджер»** для KernelSU.

In-app root работает до закрытия процесса Chiroru, ADB root и KernelSU —
до перезагрузки станции. Автозагрузка KernelSU включается в настройках.

## Документация

- [Использование и сборка APK](app/Chiroru/README.md)
- [Совместимость прошивок](app/Chiroru/FIRMWARE.md)
- [Сборка модулей](tools/KERNEL_MODULES.md)
- [Анализ OTA](tools/ota/README.md)
- [Состав исходников](app/Chiroru/SOURCES.md)

Лицензия — [MIT](LICENSE), исключения — [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
