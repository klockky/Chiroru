/* SPDX-License-Identifier: GPL-2.0-only */
/* Executable packaged as a native library. EOF on the private control pipe
 * terminates the command group, including when Android kills the app. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include "adb_uapi.h"
#ifndef CONSOLE_SHELL
#define CONSOLE_SHELL "/system/bin/sh"
#endif

int console_run_command(const char *command);
#ifndef CONSOLE_LIBRARY
int main(int argc, char **argv)
{
    if (argc != 3 || (strcmp(argv[1], "--root") && strcmp(argv[1], "--user"))) return 2;
    if (!strcmp(argv[1], "--root")) {
        int fd = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
        if (fd < 0) { perror("Console root unavailable"); return 126; }
        int rc = ioctl(fd, CHIRORU_CONSOLE_GRANT, 0);
        int saved = errno;
        close(fd);
        if (rc || getuid() || geteuid()) {
            errno = saved; perror("Console root rejected (enable updated Chiroru module)"); return 126;
        }
    }
    return console_run_command(argv[2]);
}
#endif

int console_run_command(const char *command)
{
    /* Supervisor keeps the control pipe; the command gets EOF on stdin.
     * A process group lets Stop cancel pipelines and ordinary descendants. */
    int ready[2];
    if (pipe2(ready, O_CLOEXEC)) return 125;
    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 125; }
    if (!pid) {
        close(ready[0]);
        if (setsid() < 0) _exit(125);
        int input = open("/dev/null", O_RDONLY);
        if (input < 0 || dup2(input, STDIN_FILENO) < 0) _exit(125);
        if (input > STDERR_FILENO) close(input);
        if (write(ready[1], "1", 1) != 1) _exit(125);
        close(ready[1]);
        execl(CONSOLE_SHELL, "sh", "-p", "-c", command, (char *)NULL);
        perror("exec shell"); _exit(127);
    }
    close(ready[1]);
    char ack;
    ssize_t n;
    do { n = read(ready[0], &ack, 1); } while (n < 0 && errno == EINTR);
    close(ready[0]);
    int status = 0, stopped = 0;
    struct pollfd control = { .fd = STDIN_FILENO, .events = POLLIN | POLLHUP };
    if (n == 1) for (;;) {
        /* Keep the leader unreaped until group cleanup: its PID cannot be reused. */
        siginfo_t info = {0};
        if (waitid(P_PID, pid, &info, WEXITED | WNOHANG | WNOWAIT) < 0) break;
        if (info.si_pid) break;
        int rc = poll(&control, 1, 50);
        if (rc < 0 && errno == EINTR) continue;
        if (rc != 0) { stopped = 1; break; }
    }
    kill(-pid, SIGKILL);
    if (n != 1) kill(pid, SIGKILL);
    while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) return 125;
    if (stopped) return 130;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
