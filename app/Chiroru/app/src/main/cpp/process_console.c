/* SPDX-License-Identifier: GPL-2.0-only */
#define _GNU_SOURCE
#include "process_console.h"
#include "../../../../../../root-console/protocol.h"
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int client = -1, server = -1;
static pid_t broker = -1;
static char executable[PATH_MAX];

static int response(int timeout) {
    struct pollfd p = {.fd = client, .events = POLLIN};
    int rc;
    do { rc = poll(&p, 1, timeout); } while (rc < 0 && errno == EINTR);
    if (rc <= 0) return -1;
    struct console_reply reply;
    ssize_t n = recv(client, &reply, sizeof reply, MSG_DONTWAIT);
    return n == sizeof reply && reply.magic == CONSOLE_MAGIC && reply.status == 0 ? 0 : -1;
}

static void close_locked(void) {
    if (client >= 0) close(client);
    if (server >= 0) close(server);
    client = server = -1;
    if (broker > 0) (void)waitpid(broker, NULL, WNOHANG);
}

void process_console_close(void) {
    pthread_mutex_lock(&lock); close_locked(); pthread_mutex_unlock(&lock);
}
int process_console_pending(void) { return server >= 0; }

int process_console_bootstrap(void) {
    pthread_mutex_lock(&lock);
    if (client < 0 || server < 0) { pthread_mutex_unlock(&lock); return -1; }
    long maxfd = sysconf(_SC_OPEN_MAX);
    if (maxfd < 4 || maxfd > 1048576) { close_locked(); pthread_mutex_unlock(&lock); return -1; }
    char *args[] = {executable, "--inherited", NULL};
    extern char **environ;
    broker = fork();
    if (!broker) {
        if (dup2(server, 3) < 0 || fcntl(3, F_SETFD, 0)) _exit(125);
        for (int fd = 4; fd < maxfd; ++fd) close(fd);
        int nullfd = open("/dev/null", O_RDWR);
        if (nullfd < 0) _exit(125);
        for (int fd = 0; fd < 3; ++fd) if (dup2(nullfd, fd) < 0) _exit(125);
        if (nullfd > 3) close(nullfd);
        execve(executable, args, environ);
        _exit(127);
    }
    close(server); server = -1;
    int rc = broker > 0 ? response(10000) : -1;
    if (rc) {
        if (broker > 0) { kill(broker, SIGKILL); while (waitpid(broker, NULL, 0) < 0 && errno == EINTR) {} }
        close_locked();
    }
    pthread_mutex_unlock(&lock);
    return rc;
}

static int query(unsigned operation) {
    pthread_mutex_lock(&lock);
    struct console_request request = {CONSOLE_MAGIC, operation, 0};
    int rc = client >= 0 && server < 0 &&
        send(client, &request, sizeof request, MSG_NOSIGNAL) == sizeof request && !response(500);
    if (!rc && server < 0) close_locked();
    pthread_mutex_unlock(&lock);
    return rc;
}
int process_console_ready(void) { return query(CONSOLE_PING); }
int process_console_verify(void) { return query(CONSOLE_VERIFY_ENFORCING); }

JNIEXPORT jboolean JNICALL
Java_dev_chiron_chiroru_NativeBridge_prepareProcessConsole(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    if (!path) return JNI_FALSE;
    const char *text = (*env)->GetStringUTFChars(env, path, NULL);
    if (!text) return JNI_FALSE;
    pthread_mutex_lock(&lock);
    int pair[2], ok = client < 0 && strlen(text) < sizeof executable && !access(text, X_OK) &&
        !socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair);
    if (ok) { strcpy(executable, text); client = pair[0]; server = pair[1]; }
    pthread_mutex_unlock(&lock);
    (*env)->ReleaseStringUTFChars(env, path, text);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_chiron_chiroru_NativeBridge_processConsolePresent(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return process_console_ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_dev_chiron_chiroru_NativeBridge_startProcessConsole(JNIEnv *env, jclass cls, jbyteArray command) {
    (void)cls;
    if (!command) return NULL;
    jsize length = (*env)->GetArrayLength(env, command);
    if (length < 1 || length > CONSOLE_LIMIT) return NULL;
    char text[CONSOLE_LIMIT];
    (*env)->GetByteArrayRegion(env, command, 0, length, (jbyte *)text);
    if ((*env)->ExceptionCheck(env) || memchr(text, 0, length)) return NULL;
    int output[2] = {-1,-1}, control[2] = {-1,-1}, status[2] = {-1,-1};
    jintArray result = NULL;
    pthread_mutex_lock(&lock);
    if (client < 0 || server >= 0 || pipe2(output, O_CLOEXEC) || pipe2(control, O_CLOEXEC) || pipe2(status, O_CLOEXEC)) goto done;
    struct console_request header = {CONSOLE_MAGIC, CONSOLE_RUN, (uint32_t)length};
    struct iovec io[] = {{&header, sizeof header}, {text, (size_t)length}};
    char ancillary[CMSG_SPACE(3 * sizeof(int))] = {0};
    struct msghdr message = {.msg_iov = io, .msg_iovlen = 2,
        .msg_control = ancillary, .msg_controllen = sizeof ancillary};
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(3 * sizeof(int));
    int fds[] = {output[1], control[0], status[1]};
    memcpy(CMSG_DATA(c), fds, sizeof fds);
    if (sendmsg(client, &message, MSG_NOSIGNAL) != (ssize_t)(sizeof header + length) || response(1000)) {
        close_locked(); goto done;
    }
    result = (*env)->NewIntArray(env, 3);
    if (result) {
        jint values[] = {output[0], control[1], status[0]};
        (*env)->SetIntArrayRegion(env, result, 0, 3, values);
        if (!(*env)->ExceptionCheck(env)) output[0] = control[1] = status[0] = -1;
    }
done:
    for (int i = 0; i < 2; ++i) {
        if (output[i] >= 0) close(output[i]);
        if (control[i] >= 0) close(control[i]);
        if (status[i] >= 0) close(status[i]);
    }
    pthread_mutex_unlock(&lock);
    return result;
}
