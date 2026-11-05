# Процессная root-консоль

Режим **In-app root shell** запускает отдельный процесс для команд Chiroru.
KernelSU и ADB-модуль не используются. Сессия работает до завершения процесса
приложения; команды получают UID 0, домен `chiroru_console`, каталог и `HOME` — `/`.

## Запуск

1. До получения root JNI проверяет `libchiroru_root_console.so` в
   `nativeLibraryDir` и создаёт приватную `SOCK_SEQPACKET` socketpair с CLOEXEC.
2. После получения root главный поток запускает исполнителя через fork/exec.
   Дочерний процесс сохраняет stdio и дескриптор канала, остальные FD закрывает.
3. Исполнитель читает текущую политику selinuxfs и добавляет enforcing-домен
   `chiroru_console`. После проверки сериализации и исходного снимка политика
   публикуется одним write в selinuxfs.
4. Исполнитель очищает supplementary groups и входит в новый домен.
   Родитель восстанавливает Enforcing и проверяет UID/EUID, контекст и живой
   канал. Только после этого возвращается `root_console: ready`.

Подготовка политики выполняется внутри краткого permissive-окна загрузчика.
Команды принимаются уже при Enforcing. Исполнитель наследует отключённый
при bootstrap seccomp; интерфейс приложения остаётся в исходном app-домене
с изменёнными credentials. Повторный root в этом процессе запрещён.

## Канал и завершение

Команда передаётся UTF-8-пакетом с тремя FD через `SCM_RIGHTS`: вывод,
управление и статус завершения. Дескрипторы создаёт Chiroru; запускать
`ProcessBuilder` из процесса с изменёнными credentials не требуется.

Именованных сокетов и портов нет. Доступ определяется владением приватным
дескриптором. При закрытии приложения канал обрывается, исполнитель завершает
команды и выходит. Новый процесс Chiroru не получает эту сессию из сохранённого
отчёта. Ошибка транспорта не переключает команду на другой способ исполнения.

Политика остаётся в памяти до перезагрузки, но не содержит перехода для входа
других приложений в `chiroru_console`.

## Сборка и проверки

Исполнитель собирается CMake вместе с APK. Он использует vendor parser/writer
из `linux-txn/`, адаптеры из `third_party/kernelsu/kernel/` и userspace-обёртки `tools/policy_host/`.
Генератор проверяет хеши исходников и соответствие адаптеров.

Из корня репозитория:

```sh
python3 root-console/test_server.py
python3 tools/policy_host/build.py --out /tmp/chiroru-process-console-policy \
  --test-source root-console/test_policy.c
adb pull /odm/etc/selinux/precompiled_sepolicy /tmp/chiroru-stock-policy.bin
/tmp/chiroru-process-console-policy/test-policy /tmp/chiroru-stock-policy.bin
```

Gradle-задача `:app:testProcessConsole` входит в `check`.

`testAioCleanup` проверяет освобождение AIO ring mapping вызовом
`io_destroy` через `kill_ioctx → vm_munmap`.
Для сверки с полным ELF задайте `CHIRORU_KERNEL_ELF` и установите AArch64 binutils.
