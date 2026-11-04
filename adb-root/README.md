# ADB root

Модуль `chiroru_adb` выдаёт root консоли ADB и отдельному исполнителю команд
Chiroru. KernelSU для этого режима не нужен.

## Использование

В настройках Chiroru выберите **ADB root shell** и нажмите **«Получить root»**.
С компьютера:

```sh
adb shell -t /data/local/tmp/chiroru-su
adb shell /data/local/tmp/chiroru-su -c 'id; getenforce'
adb shell /data/local/tmp/chiroru-su --status
```

Root-shell получает UID/GID 0 и контекст `u:r:chiroru_adb:s0`.
Обычный `adb shell` остаётся UID 2000. `exit` закрывает root-shell.
Модуль работает до перезагрузки станции, в том числе после закрытия Chiroru.
Автозагрузки этого режима нет; `adb root` и системный `su` не устанавливаются.

Для команд на станции откройте вкладку **«Консоль»** и включите **Root**.
После первоначальной загрузки модуля приложение предложит перезапустить Chiroru.

## Доступ к модулю

Устройство модуля — `/dev/chiroru_adb`, права `root:shell 0664`.

| Запрос | Назначение |
|---|---|
| `GET_INFO` | Проверить наличие модуля без изменения прав |
| `ADB_GRANT` | Выдать root однопоточному shell-процессу с UID/EUID 2000 |
| `CONSOLE_INFO` (`0x4372`) | Проверить доступ исполнителя Chiroru |
| `CONSOLE_GRANT` (`0x4373`) | Выдать root исполнителю Chiroru |

`ADB_GRANT` требует RW-дескриптор, SELinux SID `shell` и initial user namespace.
Проверяются credentials текущего процесса и процесса, открывшего дескриптор.

Для встроенной консоли загрузчик передаёт исходные UID и полный SELinux-контекст
приложения, включая MCS, в параметры `console_uid` и `console_context`.
Модуль сравнивает их с обоими наборами credentials. Эта привязка действует до
перезагрузки и не является проверкой подписи APK в ядре.

Root получает однопоточный `libchiroru_console.so` из `nativeLibraryDir`.
Он наследует Android seccomp-фильтр: запрещённые им системные вызовы остаются
недоступны. `chiroru-su` использует ioctl и запускает `/system/bin/sh -p`.

## Загрузка и SELinux

Модуль создаёт credentials через `prepare_creds`/`commit_creds`, очищает
supplementary groups и устанавливает UID/GID 0 и capabilities.
Домен `chiroru_adb` работает в Enforcing. Политика загружается через
`security_load_policy()` и хранится в памяти до перезагрузки.

Начальный загрузчик Chiroru использует краткое permissive-окно и временно
снимает `kptr_restrict` для разрешения символов. Успех возвращается после
восстановления исходного `kptr_restrict` и SELinux Enforcing.
Сам модуль и клиент Enforcing не отключают.

На arm64 `ksud insmod` используется как загрузчик ELF. Factory разрешает
символы и вызывает `init_module()` из 32-битного приложения:
[FACTORY-MODULE.md](FACTORY-MODULE.md).

## Сборка и проверки

Из корня репозитория:

```sh
bash adb-root/build.sh 5000001245 /path/to/prepared-kernel /tmp/chiroru-adb-1245
python3 adb-root/test_abi.py
python3 tools/policy_host/build.py --out /tmp/chiroru-adb-policy-host \
  --test-source adb-root/test_policy.c
adb pull /odm/etc/selinux/precompiled_sepolicy /tmp/chiroru-stock-policy.bin
/tmp/chiroru-adb-policy-host/test-policy /tmp/chiroru-stock-policy.bin
```

Нужны подготовленное дерево ядра, GCC ARM64 и NDK `28.2.13676358`.
Входные файлы и проверка ABI описаны в [KERNEL_MODULES.md](../tools/KERNEL_MODULES.md).
Для включения результата в APK обновите ресурсы и хеши блока `adbRoot`.

`test_client.c` запускается на устройстве от обычного shell и проверяет ioctl,
отказы по UID/SID и передачу дескриптора приложению. `test_console_auth.c`
запускается через root CLI и проверяет UID, MCS, владельца дескриптора и отказ
многопоточному процессу.

Код модуля, клиента и SELinux-библиотеки — GPL-2.0-only.
