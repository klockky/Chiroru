# Процессная root-консоль Chiroru

Режим **In-app root shell** в 0.6.2 подготавливает отдельный userspace-процесс
для команд, без KernelSU, ADB-модуля или публичного сервиса.

## Запуск и границы доступа

1. Пока Chiroru ещё имеет обычные credentials, JNI проверяет исполняемый файл
   `libchiroru_root_console.so` в установленном nativeLibraryDir и создаёт
   приватную `SOCK_SEQPACKET` socketpair с CLOEXEC. Именованных сокетов/портов нет.
2. После существующего exact-firmware root primitive главный поток делает
   fork/exec исполнителя внутри bootstrap-окна. В дочернем процессе остаются
   только stdio и явно переданный дескриптор socketpair; другие FD закрываются.
3. Исполнитель считывает **текущую**, а не сохранённую политику selinuxfs, добавляет
   `chiroru_console` (enforcing, mlstrustedsubject, netdomain, root permissions),
   проверяет сериализацию повторным чтением и публикует одним write в selinuxfs.
   Используется проверенный vendor parser/mutator/writer в userspace, без
   `security_load_policy()` из kernel module и без KernelSU runtime. Перед write
   повторно сверяется исходный снимок; native loader ядра проверяет целую политику.
4. Исполнитель очищает supplementary groups и входит в новый домен. Родитель
   восстанавливает Enforcing. Успех требует отдельного live-запроса: UID/EUID 0,
   точный контекст и Enforcing. Только после этого появляется `root_console: ready`.
5. Команды передаются по приватному каналу UTF-8-пакетом и тремя FD через
   SCM_RIGHTS (stdout/stderr, управление, exit status). Pipe создаёт сам Chiroru;
   ему не нужно запускать ProcessBuilder из credential-модифицированного процесса.
   Backend запускает тот же проверенный supervisor групп процессов, что модульная
   консоль. Отказ транспорта никогда не переключает команду на другой backend.

Root-сессия связана с процессом приложения. App death закрывает приватный канал
и управляющие pipe: команды завершаются, backend собирает дочерние процессы и
выходит. Завершение асинхронное, обычно занимает несколько циклов 50-ms supervisor.
Повторное открытие приложения не восстанавливает root по сохранённому отчёту.
Runtime всегда проверяет действующий канал и credentials исполнителя.

В этой же сборке исправлена очистка AIO в firmware backend: `io_destroy` уже
снимает ring mapping через vendor `kill_ioctx -> vm_munmap`. Повторный `munmap`
старого адреса удалён, чтобы не освободить новую allocation другого потока ART.
`testAioCleanup` проверяет повторное использование адреса и вызов `vm_munmap`
в дизассемблированном ядре станции. Результаты устройства и обнаруженное до
исправления падение: [отчёт 2026-11-01](../reports/chiroru-process-console-2026-11-01.md).

Сам bootstrap-процесс Chiroru сохраняет изменённые credentials и ограниченный
app SELinux-контекст; повторный root primitive в нём запрещён. Команды выполняются
отдельно в `chiroru_console`, с унаследованным от bootstrap отключённым seccomp.
Рабочий каталог и HOME — `/`. В этом режиме Root включён постоянно; обычная
app-консоль снова доступна после завершения этого процесса и нового запуска.

Политика остаётся в памяти до перезагрузки, но не содержит перехода, который
позволял бы другому приложению войти в новый домен. Механизм выдачи полномочий —
владение приватным дескриптором, а не доступный другим приложениям ioctl/сокет.
Нет автозапуска, постоянного daemon, модификации разделов или постоянного permissive.

## Сборка и проверки

Исполнитель собирается CMake вместе с APK. Генератор проверяет pinned vendor
policy writer/context adapters. Из `tools/policy_host` переиспользуются userspace
allocation/header shims; модель kernel locks/publication в executable не линкуется.

Из корня репозитория:

```sh
python3 root-console/test_server.py
python3 tools/policy_host/build.py --out /tmp/opencode/process-console-policy \
  --test-source root-console/test_policy.c
/tmp/opencode/process-console-policy/test-policy /tmp/opencode/chiron-stock-policy.bin
```

Host transport tests используют отдельный driver без root/policy startup; они
проверяют framing/FDs, повторы, live ping, exit code, остановку и EOF. Policy tests
под ASan/UBSan проверяют enforcing-тип, roundtrip/idempotence, усечённые входы и
ошибки выделения памяти. Реальный bootstrap/SELinux/lifecycle проверяется отдельно
на устройстве. Gradle `:app:testProcessConsole` включает transport tests в `check`.
