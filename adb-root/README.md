# Chiroru ADB root — chiron 5000001307

Независимый от загруженного KernelSU режим для консоли с компьютера и из приложения.
В Chiroru 0.6.0 выберите **«Включить root для ADB»**, затем откройте вкладку
**«Консоль»** и включите **Root**, либо выполните с компьютера:

```sh
adb shell -t /data/local/tmp/chiroru-su
```

Одна команда или проверка доступности:

```sh
adb shell /data/local/tmp/chiroru-su -c 'id; getenforce'
adb shell /data/local/tmp/chiroru-su --status
```

Ожидаемые права: UID/GID 0, `u:r:chiroru_adb:s0`. Обычный `adb shell` остаётся
UID 2000. `exit` завершает root-консоль. Модуль работает после закрытия Chiroru,
до перезагрузки; после перезагрузки его нужно снова включить кнопкой. Автозагрузки
для этого режима нет. `adb root` и системный `su` не устанавливаются.

## Реализация

- `chiroru_adb.ko` — устройство `/dev/chiroru_adb`.
- `chiroru-su` — 8 040 байт; явный ioctl и `exec /system/bin/sh -p`.
- GET_INFO не меняет права и доступен для проверки из обычного Chiroru.
- ADB GRANT проверяет реальный/effective UID **2000**, исходный SELinux SID `shell`
  и initial user namespace как у текущей задачи, так и у открывшего дескриптор.
  Требуется RW-дескриптор и однопоточный вызывающий процесс. Передача дескриптора
  обычному приложению не выдаёт ему root.
- В 0.6.0 добавлены отдельные CONSOLE_INFO/CONSOLE_GRANT (0x4372/0x4373).
  Загрузчик до изменения credentials запоминает фактические UID и полный
  SELinux-контекст Chiroru, включая MCS-категории. Они передаются в read-only
  параметры модуля `console_uid`/`console_context`. Модуль сравнивает **оба**
  credentials (opener/current) с этой идентичностью и initial user namespace;
  CONSOLE_GRANT дополнительно требует однопоточный процесс. Он доступен через
  read-only FD: права узла root:shell 0664 и политика SELinux не расширены.
  CONSOLE_INFO только проверяет доступность и не выдаёт права. Без enrollment
  сохраняется режим только ADB. Enrollment действует в пределах текущей загрузки;
  это привязка к UID/SID, а не проверка подписи APK внутри ядра.
- Команды приложения запускает отдельный executable `libchiroru_console.so`
  из nativeLibraryDir. Root запрашивает этот однопоточный дочерний процесс,
  не ART/UI-процесс. Он сохраняет наследуемый Android seccomp-фильтр; команды,
  которым нужны запрещённые этим фильтром syscalls, могут завершаться ошибкой.
  Сам модуль ADB и клиент `chiroru-su` остаются совместимыми с API 1.
- Новые credentials создаются через native `prepare_creds`/`commit_creds`;
  supplementary groups очищаются, выставляются UID/GID 0 и capabilities.
- Отдельный SELinux-домен `chiroru_adb` **enforcing**, с разрешениями root.
  Политика публикуется штатным `security_load_policy()` в проверенной транзакции.
  Типы и правила живут в памяти до перезагрузки. Глобальный Enforcing не отключается
  модулем или консольным клиентом.
- Для bootstrap из приложения используется существующее краткое permissive-окно
  native-загрузчика Chiroru. `kptr_restrict` временно устанавливается в 0 для
  relocation, затем возвращается к исходному значению (на станции — 2).
  Успех операции публикуется после восстановления SELinux Enforcing.
- Пользовательский `ksud insmod` используется только как ELF-релокатор/загрузчик
  `.ko`; `kernelsu.ko`, KernelSU Manager, SU API и lifecycle не используются.
  Три файла SELinux-библиотеки переиспользуются из проверенного `build-txn`,
  но KernelSU hooks/runtime в модуль не линкуются.
- Root «только для процесса», KernelSU и ADB root — три независимых операции.

## Сборка и проверки

Из корня `duomax-ksu`:

```sh
bash adb-root/build.sh
python3 adb-root/test_abi.py
python3 tools/policy_host/build.py --out /tmp/opencode/adb-policy-host \
  --test-source adb-root/test_policy.c
adb pull /odm/etc/selinux/precompiled_sepolicy /tmp/opencode/chiron-stock-policy.bin
/tmp/opencode/adb-policy-host/test-policy /tmp/opencode/chiron-stock-policy.bin
```

Нужны подготовленный `linux-txn`, GCC ARM64 и NDK 28.2.13676358. Проверки используют
сохранённый vendor ELF, закреплённый SHA-256. Исходники библиотеки и адаптеры
проверяются `gen_policy_writer.py --check`, конфигурация preemption — отдельным
проверяющим скриптом. Готовые файлы находятся в `out/`; для APK их копируют в
`app/Chiroru/app/src/main/assets/{firmware/chiron-5000001307,common}` и обновляют
SHA-256 блока `adbRoot` в каталоге. Валидатор APK сверяет все три ресурса.

`test_client.c` — отдельный on-device тест, запускаемый именно от обычного shell
после активации модуля. Он проверяет read-only ioctl, неизвестные команды,
неправильный аргумент, отказ по UID/SELinux-домену и передачу FD приложению.
`test_console_auth.c` запускается через root CLI и проверяет точный UID/SID,
чужие UID, домен и MCS, чужого opener и отказ многопоточному вызывающему.

Ограниченная проверка устройства и известный исправленный сбой:
[отчёт](../reports/chiroru-adb-root-device-2026-11-01.md).
Код модуля/клиента и переиспользованной SELinux-библиотеки — GPL-2.0-only.
