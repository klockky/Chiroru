# Ядро из OTA Duo Max: контейнер `@AML`

Проверен путь **OTA → зашифрованный boot payload → аппаратная расшифровка на
станции → gzip → ARM64 Image → восстановленные символы → `sys_call_table`**.
Исследуемую прошивку не требуется устанавливать: factory-образ `1982735225`
успешно расшифрован на станции с `5000001245`.

## Установленный формат

Для проверенных boot images:

| Смещение | Поле |
|---|---|
| `0` | Внешний Android boot header v3 (`ANDROID!`), страница 4096 байт |
| `0x1000` | `@AML`, версия 1 |
| `0x1010` | LE32: длина шифротекста, кратна 16 |
| `0x1014` | LE32: размер заголовка `@AML`, `0x300` |
| `0x1018` | 32 байта SHA-256 полного открытого payload |
| `0x1300` | Шифротекст: внутри находится **целый Android boot image** |

Расшифровка: AF_ALG `cbc(aes-kl-aml)`, нулевой IV, существующая key-table entry
`0x86`. Передаваемые драйверу 32 байта с первым байтом `0x86` — **селектор KTE,
не AES-ключ**. Это не позволяет расшифровывать образ обычным OpenSSL на ПК.
Ключевые байты инструмент не получает и не сохраняет. Доступность слота на
других устройствах/после других boot chains не установлена; успешный запуск
helper сам по себе не считается доказательством правильной расшифровки.

У открытого payload проверяется SHA-256 из контейнера, затем совпадение
внутреннего/внешнего Android header и gzip CRC. Хеш проверяет целостность;
он не заменяет проверку подписи OTA.

## Повторение

Нужны Python 3, ADB, уже работающий root на станции и зарегистрированный
`cbc(aes-kl-aml)` в `/proc/crypto`. Для helper — NDK r28c/API 30. Для
восстановления символов — внешний `vmlinux-to-elf==1.4.0`.

### 1. Извлечь шифротекст на ПК

Принимается полный OTA ZIP с единственным `boot.img` либо отдельный boot image:

```sh
python3 tools/ota/aml_boot.py split /path/to/update.zip /tmp/opencode/ota-cipher
```

Результат: `ciphertext.bin` и `metadata.json`. Размер для helper находится в
`ciphertextSize`. Каталог результата должен быть новым.

### 2. Собрать helper и расшифровать на станции

Статический ARMv7 executable может работать и без `linker64`:

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
вместо `su -c` используется `/data/local/tmp/chiroru-su -c`. Для запуска helper
нужен уже доступный root; он не активирует root и не меняет SELinux.

Передача идёт через временные файлы: поток stdin через разные реализации
`adb exec-out`/`su` может зависать. Helper читает заданное число байт и работает
только с stdin/stdout и AF_ALG. Каждый блок отправляется и сразу считывается,
чтобы не заполнить socket buffer до начала чтения. Путь к разделам он не открывает.

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

Анализатор сначала проверяет, что LOAD segment ELF побайтно соответствует
именно этому `Image`. Затем ищет единственную таблицу по семи независимым
слотам: `io_setup`, `faccessat`, `newfstatat`, `reboot`, `setresuid`, `execve`,
`execveat`. Смещение от `_stext` вычисляется по ELF mapping, без переноса
константы предыдущей прошивки. При отсутствии символов или неоднозначности
результат не выдаётся.

После работы удалите созданные временные файлы на станции:

```sh
adb shell 'su -c "rm -f /data/local/tmp/chiroru-ota-decrypt /data/local/tmp/chiroru-ota.enc /data/local/tmp/chiroru-ota.plain"'
```

## Результаты проверки

| Прошивка | SHA-256 извлечённого Image | Найденный `sys_call_table - _stext` |
|---|---|---|
| `5000001307` | `adeb94b18d177fd330c8a4f02034881cb8450b0ace46fcf1c08af892f877769b` | `0xEEE6E8` |
| `5000001245` | `65f5dec63d6deb7604554d465db5a5189011a51e8f6250ea31284cfa4b91f932` | `0xEEE700` |
| `1982735225` | `1e587534a1c67328c736c7221ab50ea5ff6a833eef733060dc5772a95b34707e` | `0xEEE548` |
| `5000001248` | `8fa132b00093d59747c0fbce89da45a6c6d4a7337855e24bc7df83eaf1412832` | `0xEEE718` |

Для `1245` использован `boot.img` непосредственно из OTA ZIP; все **43 726**
общих уникальных имён символов имеют те же относительные адреса, что в ранее
прочитанном из памяти ядре этой прошивки. Для `1307` использована сохранённая
пара wrapped/plain boot; factory-образ расшифрован заново на `1245`, в том
числе статическим helper из этого каталога. Все три plaintext SHA-256 совпали
с заголовками контейнеров. SELinux при проверке оставался Enforcing.

Для `1248` также успешно выполнена расшифровка непосредственно из OTA ZIP
на станции с `1245`. Результат использован для подготовки отдельного
[тестового профиля](5000001248.md), включая проверку остальных globals.

Полностью офлайн-расшифровка на ПК пока не реализована: для первого шага
нужна станция с доступным аппаратным слотом. Все последующие операции офлайн.
Публичные инструменты содержат исходники и синтетические тесты; образы,
локальные ELF и сырые device evidence остаются вне репозитория.

Это начало анализатора прошивок, а не генератор готовой root-конфигурации:
`init_task`, SELinux globals, offsets структур, совместимость драйвера и
модульного ABI ещё требуют отдельных проверок. KASLR/runtime значения
вычисляются при запуске. Автоматического добавления прошивки в каталог нет.

Тесты формата, целостности и отказа при неоднозначных результатах:

```sh
python3 -m unittest -v tools/test_ota_analysis.py
```
