/* SPDX-License-Identifier: GPL-2.0-only */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include "uapi.h"

int main(int argc, char **argv)
{
    int status = argc == 2 && !strcmp(argv[1], "--status");
    if (!status && argc != 1 && !(argc == 3 && !strcmp(argv[1], "-c"))) {
        fprintf(stderr, "Usage: chiroru-su [--status | -c COMMAND]\n");
        return 2;
    }
    int fd = open(CHIRORU_ADB_DEVICE, (status ? O_RDONLY : O_RDWR) | O_CLOEXEC);
    if (fd < 0) { perror("Chiroru ADB module unavailable"); return 1; }
    if (ioctl(fd, CHIRORU_ADB_INFO, 0) != CHIRORU_ADB_VERSION) {
        fprintf(stderr, "Chiroru ADB module not ready or incompatible\n"); close(fd); return 1;
    }
    if (status) { puts("Chiroru ADB root: available (API 1)"); close(fd); return 0; }
    if (ioctl(fd, CHIRORU_ADB_GRANT, 0) != 0) {
        perror("Chiroru root request rejected (requires ADB shell UID 2000)"); close(fd); return 1;
    }
    close(fd);
    if (getuid() != 0 || geteuid() != 0) { fputs("Root credentials not acquired\n", stderr); return 1; }
    setenv("HOME", "/", 1); setenv("USER", "root", 1); setenv("LOGNAME", "root", 1);
    if (argc == 3) execl("/system/bin/sh", "sh", "-p", "-c", argv[2], (char *)NULL);
    else execl("/system/bin/sh", "sh", "-p", (char *)NULL);
    perror("exec shell");
    return 127;
}
