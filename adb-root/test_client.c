/* SPDX-License-Identifier: GPL-2.0-only */
/* Device integration test, invoked as ordinary ADB shell after activation. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include "uapi.h"

static void child_test(int passed_fd, int scenario)
{
    /* The parent legitimately requested root as shell; these children drop it.
     * Keep the enforcing root domain for UID-only/domain-only gate tests. */
    uid_t uid = scenario == 1 ? 2000 : 10081;
    if (setgroups(0, NULL) || setresgid(uid, uid, uid) || setresuid(uid, uid, uid)) _exit(10);
    if (scenario == 2) {
        const char context[] = "u:r:untrusted_app:s0:c81,c256,c512,c768";
        int f = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
        if (f < 0 || write(f, context, sizeof context - 1) != sizeof context - 1) _exit(11);
        close(f);
        int query = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
        if (query < 0 || ioctl(query, CHIRORU_ADB_INFO, 0) != CHIRORU_ADB_VERSION) _exit(12);
        close(query);
    }
    errno = 0;
    int rc = ioctl(passed_fd, CHIRORU_ADB_GRANT, 0);
    int e = errno;
    if (rc != -1 || (e != EPERM && !(scenario == 2 && e == EACCES)) || getuid() != uid) _exit(13);
    _exit(0);
}

int main(void)
{
    assert(getuid() == 2000 && geteuid() == 2000);
    int ro = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
    assert(ro >= 0 && ioctl(ro, CHIRORU_ADB_INFO, 0) == CHIRORU_ADB_VERSION);
    assert(ioctl(ro, CHIRORU_ADB_GRANT, 0) == -1 && errno == EBADF);
    assert(ioctl(ro, _IO('C', 0x7f), 0) == -1 && errno == ENOTTY);
    assert(ioctl(ro, CHIRORU_ADB_GRANT, 1) == -1 && errno == EINVAL);
    close(ro);
    int fd = open(CHIRORU_ADB_DEVICE, O_RDWR | O_CLOEXEC);
    assert(fd >= 0 && ioctl(fd, CHIRORU_ADB_GRANT, 0) == 0 && getuid() == 0);
    for (int i = 0; i < 3; ++i) {
        pid_t pid = fork();
        assert(pid >= 0);
        if (!pid) child_test(fd, i);
        int status;
        assert(waitpid(pid, &status, 0) == pid);
        printf("credential rejection scenario %d: exit=%d\n", i, WIFEXITED(status) ? WEXITSTATUS(status) : -1);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) return 1;
    }
    close(fd);
    puts("PASS: read-only API, invalid commands, UID/domain gates and app-domain passed-fd rejection");
    return 0;
}
