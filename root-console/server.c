/* SPDX-License-Identifier: GPL-2.0-only */
#define _GNU_SOURCE
#include "protocol.h"
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
#include <arpa/inet.h>

int console_policy_install(void);
int console_run_command(const char *command);
int console_server_loop(int socket, int (*verify)(void));

static int reply(int socket, int status) {
    struct console_reply result = {CONSOLE_MAGIC, status};
    return send(socket, &result, sizeof result, MSG_NOSIGNAL) == sizeof result ? 0 : -1;
}

static int live_root(void) {
    char context[128] = {0};
    int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, context, sizeof context - 1); close(fd);
    if (n <= 0 || strcmp(context, CONSOLE_CONTEXT) || getuid() || geteuid()) return -1;
    return 0;
}

static int is_enforcing(void) {
    char enforcing = 0;
    int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, &enforcing, 1); close(fd);
    return n == 1 && enforcing == '1' ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc != 2 || strcmp(argv[1], "--inherited") || getuid() || geteuid()) return 2;
    const int socket = 3;
    fcntl(socket, F_SETFD, FD_CLOEXEC);
    signal(SIGPIPE, SIG_IGN);
    if (console_policy_install() || setgroups(0, NULL) || chdir("/")) { reply(socket, -1); return 1; }
    int attr = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
    if (attr < 0 || write(attr, CONSOLE_CONTEXT, sizeof(CONSOLE_CONTEXT) - 1) != sizeof(CONSOLE_CONTEXT) - 1) {
        if (attr >= 0) close(attr);
        reply(socket, -1); return 1;
    }
    close(attr);
    setenv("HOME", "/", 1); setenv("USER", "root", 1); setenv("LOGNAME", "root", 1); setenv("TERM", "dumb", 1);
    return console_server_loop(socket, live_root);
}

int console_server_loop(int socket, int (*verify)(void)) {
    if (reply(socket, 0)) return 1; /* Parent restores Enforcing, then sends PING. */
    for (;;) {
        while (waitpid(-1, NULL, WNOHANG) > 0) { }
        struct { struct console_request header; char command[CONSOLE_LIMIT + 1]; } request;
        char ancillary[CMSG_SPACE(3 * sizeof(int))] = {0};
        struct iovec io = {&request, sizeof request};
        struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
            .msg_control = ancillary, .msg_controllen = sizeof ancillary};
        ssize_t n = recvmsg(socket, &message, MSG_CMSG_CLOEXEC);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        int fds[3] = {-1, -1, -1}, count = 0;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
            size_t size = c->cmsg_len - CMSG_LEN(0);
            int *received = (int *)CMSG_DATA(c);
            for (size_t i = 0; i < size / sizeof(int); ++i) {
                if (count < 3) fds[count++] = received[i]; else close(received[i]);
            }
        }
        int status = -1;
        if (!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) && n >= (ssize_t)sizeof request.header &&
            request.header.magic == CONSOLE_MAGIC && request.header.length <= CONSOLE_LIMIT &&
            n == (ssize_t)(sizeof request.header + request.header.length) && !verify()) {
            if (request.header.operation == CONSOLE_PING && !request.header.length && !count) status = 0;
            else if (request.header.operation == CONSOLE_VERIFY_ENFORCING && !request.header.length && !count)
                status = is_enforcing();
            else if (request.header.operation == CONSOLE_RUN && request.header.length && count == 3 &&
                     !memchr(request.command, 0, request.header.length)) {
                request.command[request.header.length] = 0;
                pid_t pid = fork();
                if (!pid) {
                    close(socket);
                    if (dup2(fds[0], STDOUT_FILENO) < 0 || dup2(fds[0], STDERR_FILENO) < 0 ||
                        dup2(fds[1], STDIN_FILENO) < 0) _exit(125);
                    close(fds[0]); close(fds[1]);
                    int code = console_run_command(request.command);
                    uint32_t wire = htonl((uint32_t)code);
                    ssize_t written;
                    do { written = write(fds[2], &wire, sizeof wire); }
                    while (written < 0 && errno == EINTR);
                    close(fds[2]); _exit(written == sizeof wire ? 0 : 125);
                }
                if (pid > 0) status = 0;
            }
        }
        for (int i = 0; i < count; ++i) close(fds[i]);
        if (reply(socket, status)) break;
    }
    close(socket);
    /* App death closes each command's private control pipe as well. */
    while (waitpid(-1, NULL, 0) >= 0 || errno == EINTR) { }
    return 0;
}
