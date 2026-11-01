/* SPDX-License-Identifier: GPL-2.0-only */
/* Run via chiroru-su -c 'test-console-auth UID FULL_APP_CONTEXT'. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include "uapi.h"

static void *sleeper(void *unused) { (void)unused; sleep(30); return NULL; }

static void scenario(int number, int passed, uid_t enrolled, const char *context)
{
    uid_t uid = number == 1 ? enrolled + 1 : enrolled;
    if (setgroups(0, NULL) || setresgid(uid, uid, uid) || setresuid(uid, uid, uid)) _exit(10);
    const char *domain = number == 2 ? "u:r:shell:s0" :
        number == 5 ? "u:r:untrusted_app:s0:c82,c256,c512,c768" : context;
    int attr = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
    if (attr < 0 || write(attr, domain, strlen(domain)) != (ssize_t)strlen(domain)) _exit(11);
    close(attr);
    int fd = number == 3 ? passed : open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
    if (fd < 0) _exit(12);
    int eligible = number == 0 || number == 4;
    int info = ioctl(fd, CHIRORU_CONSOLE_INFO, 0);
    if ((eligible && info != CHIRORU_ADB_VERSION) || (!eligible && info != -1) || getuid() != uid) _exit(13);
    if (number == 4) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, sleeper, NULL)) _exit(14);
    }
    int rc = ioctl(fd, CHIRORU_CONSOLE_GRANT, 0);
    if (number == 0) {
        if (rc || getuid() || geteuid()) _exit(15);
    } else if (rc != -1 || getuid() != uid || (number == 4 && errno != EBUSY)) _exit(16);
    _exit(0);
}

int main(int argc, char **argv)
{
    assert(argc == 3 && getuid() == 0);
    uid_t uid = (uid_t)strtoul(argv[1], NULL, 10);
    assert(uid >= 10000 && uid <= 19999);
    int fd = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
    assert(fd >= 0);
    for (int i = 0; i < 6; ++i) {
        pid_t pid = fork();
        assert(pid >= 0);
        if (!pid) scenario(i, fd, uid, argv[2]);
        int status;
        assert(waitpid(pid, &status, 0) == pid);
        printf("console credential scenario %d: exit=%d\n", i, WIFEXITED(status) ? WEXITSTATUS(status) : -1);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) return 1;
    }
    close(fd);
    puts("PASS: exact console identity; reject wrong UID, SID, opener and multithreaded grant");
    return 0;
}
