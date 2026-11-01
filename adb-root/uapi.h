/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef CHIRORU_ADB_UAPI_H
#define CHIRORU_ADB_UAPI_H
#include <linux/ioctl.h>
#define CHIRORU_ADB_DEVICE "/dev/chiroru_adb"
#define CHIRORU_ADB_CONTEXT "u:r:chiroru_adb:s0"
#define CHIRORU_ADB_NODE_CONTEXT "u:object_r:chiroru_adb_device:s0"
/* No userspace pointers, variable-length data, or caller-selected credentials. */
#define CHIRORU_ADB_INFO _IO('C', 0x70)
#define CHIRORU_ADB_GRANT _IO('C', 0x71)
#define CHIRORU_CONSOLE_INFO _IO('C', 0x72)
#define CHIRORU_CONSOLE_GRANT _IO('C', 0x73)
#define CHIRORU_ADB_VERSION 0x43414401
#endif
