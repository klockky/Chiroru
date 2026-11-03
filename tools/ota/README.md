# Извлечение ядра из OTA

Инструменты извлекают ARM64 Image из зашифрованного `boot.img` Duo Max
и находят `sys_call_table`. Для расшифровки нужна станция с root и аппаратным
crypto driver; остальные шаги выполняются на ПК. Исследуемую прошивку
устанавливать не нужно.

## Формат `@AML`

Структура проверенных boot images:

| Смещение | Поле |
|---|---|
| `0` | Внешний Android boot header v3 (`ANDROID!`), страница 4096 байт |
| `0x1000` | `@AML`, версия 1 |
| `0x1010` | LE32: длина шифротекста, кратна 16 |
| `0x1014` | LE32: размер заголовка `@AML`, `0x300` |
| `0x1018` | 32 байта SHA-256 полного открытого payload |
| `0x1300` | Шифротекст: внутри находится **целый Android boot image** |

Расшифровка использует AF_ALG `cbc(aes-kl-aml)`, нулевой IV и key-table entry
`0x86`. Передаваемые драйверу 32 байта с первым байтом `0x86` — селектор KTE,
а не AES-ключ. Сам ключ инструменту недоступен; расшифровка через OpenSSL
на ПК не реализована. Доступность слота на других устройствах не проверена.

У открытого payload проверяется SHA-256 из контейнера, затем совпадение
внутреннего/внешнего Android header и gzip CRC. Хеш проверяет целостность;
он не заменяет проверку подписи OTA.

## Порядок работы

Нужны Python 3, ADB, работающий root на станции и зарегистрированный
`cbc(aes-kl-aml)` в `/proc/crypto`. Для утилиты расшифровки — NDK r28c/API 30. Для
восстановления символов — внешний `vmlinux-to-elf==1.4.0`.

### 1. Извлечь шифротекст на ПК

Принимается полный OTA ZIP с единственным `boot.img` либо отдельный boot image:

```sh
python3 tools/ota/aml_boot.py split /path/to/update.zip /tmp/opencode/ota-cipher
```

Результат: `ciphertext.bin` и `metadata.json`. Размер шифротекста находится в
`ciphertextSize`. Каталог результата должен быть новым.

### 2. Расшифровать на станции

Соберите статический ARMv7-файл, совместимый с 32-битным userspace:

```sh
NDK=/path/to/android-ndk
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi30-clang" \
  -static -O2 -Wall -Wextra -Werror tools/ota/aml_decrypt.c \
  -o /tmp/opencode/aml-decrypt
adb push /tmp/opencode/aml-decrypt /data/local/tmp/chiroru-ota-decrypt
adb push /tmp/opencode/ota-cipher/ciphertext.bin /data/local/tmp/chiroru-ota.enc
adb shell chmod 755 /data/local/tmp/chiroru-ota-decrypt

# Пример размера для boot payload 1245/1307; берите его из своего metadata.json.
adb shell 'su -c "timeout 60 /data/local/tmp/chiroru-ota-decrypt 9392128 < /data/local/tmp/chiroru-ota.enc > /data/local/tmp/chiroru-ota.plain"'
adb pull /data/local/tmp/chiroru-ota.plain /tmp/opencode/ota-boot.plain
```

При нескольких устройствах укажите `adb -s SERIAL`. В режиме Chiroru ADB root
вместо `su -c` используется `/data/local/tmp/chiroru-su -c`.

Используйте временные файлы: передача stdin через `adb exec-out` и `su`
может зависать. Утилита читает заданное число байт, расшифровывает через AF_ALG
и пишет результат в stdout.

### 3. Проверить и извлечь ядро на ПК

```sh
python3 tools/ota/aml_boot.py unpack /path/to/update.zip \
  /tmp/opencode/ota-boot.plain /tmp/opencode/ota-kernel
```

Результат: `Image` и `metadata.json` с хешами. Образ в файле может быть короче
`imageMemorySize` из ARM64 header: runtime BSS не требуется хранить целиком.

### 4. Восстановить символы и найти таблицу

```sh
python3 -m venv /tmp/opencode/ota-venv
/tmp/opencode/ota-venv/bin/pip install 'vmlinux-to-elf==1.4.0'
/tmp/opencode/ota-venv/bin/vmlinux-to-elf \
  /tmp/opencode/ota-kernel/Image /tmp/opencode/ota-kernel/vmlinux.elf
python3 tools/ota/find_syscall_table.py \
  /tmp/opencode/ota-kernel/Image /tmp/opencode/ota-kernel/vmlinux.elf
```

Анализатор проверяет совпадение LOAD-сегмента ELF с `Image`, затем ищет
единственную таблицу по семи
слотам: `io_setup`, `faccessat`, `newfstatat`, `reboot`, `setresuid`, `execve`,
`execveat`. Смещение от `_stext` вычисляется по адресам ELF. При отсутствии
символов или неоднозначности анализ завершается ошибкой.

После работы удалите созданные временные файлы на станции:

```sh
adb shell 'su -c "rm -f /data/local/tmp/chiroru-ota-decrypt /data/local/tmp/chiroru-ota.enc /data/local/tmp/chiroru-ota.plain"'
```

## Проверенные образы

| Прошивка | SHA-256 извлечённого Image | Найденный `sys_call_table - _stext` |
|---|---|---|
| `5000001307` | `adeb94b18d177fd330c8a4f02034881cb8450b0ace46fcf1c08af892f877769b` | `0xEEE6E8` |
| `5000001245` | `65f5dec63d6deb7604554d465db5a5189011a51e8f6250ea31284cfa4b91f932` | `0xEEE700` |
| `1982735225` | `1e587534a1c67328c736c7221ab50ea5ff6a833eef733060dc5772a95b34707e` | `0xEEE548` |
| `5000001248` | `8fa132b00093d59747c0fbce89da45a6c6d4a7337855e24bc7df83eaf1412832` | `0xEEE718` |

[Адреса и параметры `1248`](5000001248.md).

Для нового профиля отдельно проверяются `init_task`, переменные SELinux,
смещения структур, драйвер и ABI модулей. Эти инструменты не добавляют
прошивку в каталог автоматически.

Проверка формата, целостности и неоднозначных результатов:

```sh
python3 -m unittest -v tools/test_ota_analysis.py
```
