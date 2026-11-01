/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef CHIRORU_PROCESS_CONSOLE_PROTOCOL_H
#define CHIRORU_PROCESS_CONSOLE_PROTOCOL_H
#include <stdint.h>
#define CONSOLE_MAGIC 0x43504301u
#define CONSOLE_PING 1u
#define CONSOLE_RUN 2u
#define CONSOLE_VERIFY_ENFORCING 3u
#define CONSOLE_LIMIT 16384
#define CONSOLE_CONTEXT "u:r:chiroru_console:s0"
struct console_request { uint32_t magic, operation, length; };
struct console_reply { uint32_t magic; int32_t status; };
#endif
