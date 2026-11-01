#define _GNU_SOURCE
#include <jni.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "process_wait.h"
#include "adb_uapi.h"

JNIEXPORT jboolean JNICALL
Java_dev_chiron_chiroru_NativeBridge_consoleRootPresent(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  int fd = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return JNI_FALSE;
  int rc = ioctl(fd, CHIRORU_CONSOLE_INFO, 0);
  close(fd);
  return rc == CHIRORU_ADB_VERSION ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_chiron_chiroru_NativeBridge_adbRootPresent(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  int fd = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return JNI_FALSE;
  int rc = ioctl(fd, CHIRORU_ADB_INFO, 0);
  close(fd);
  return rc == CHIRORU_ADB_VERSION ? JNI_TRUE : JNI_FALSE;
}

/* Read-only GET_INFO from the bundled KernelSU UAPI. Never request SU or
 * lifecycle operations. Invalid Linux reboot magic cannot reboot the device. */
struct ksu_info { uint32_t version, flags, features, uapi_version; };
#define KSU_GET_INFO _IOR('K', 2, struct ksu_info)

static void unavailable(int signal) { (void)signal; _exit(3); }

JNIEXPORT jint JNICALL
Java_dev_chiron_chiroru_NativeBridge_kernelSuQuery(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  int pipes[2];
  if (pipe2(pipes, O_CLOEXEC) != 0) return 0;
  pid_t pid = fork();
  if (pid == 0) {
    close(pipes[0]);
    setpgid(0, 0);
    /* Android may forbid this syscall. Contain SIGSYS in a disposable child,
     * retaining the app's credentials and seccomp filter. No JVM after fork. */
    struct sigaction action = {.sa_handler = unavailable};
    sigemptyset(&action.sa_mask);
    sigaction(SIGSYS, &action, NULL);
    int fd = -1;
    syscall(SYS_reboot, 0xDEADBEEFUL, 0xCAFEBABEUL, 0, &fd);
    if (fd < 0) _exit(2);
    struct ksu_info info = {0};
    int rc = ioctl(fd, KSU_GET_INFO, &info);
    close(fd);
    if (rc != 0 || info.version == 0 || info.uapi_version == 0) _exit(2);
    ssize_t written = write(pipes[1], "KSU", 3);
    _exit(written == 3 ? 0 : 2);
  }
  close(pipes[1]);
  if (pid < 0) { close(pipes[0]); return 0; }
  char output[8];
  struct process_result result = collect_process(pid, pipes[0], 500, output, sizeof output);
  if (result.exit_code == 3) return -1; /* blocked by seccomp */
  return result.exit_code == 0 && !result.error && !result.timed_out &&
      output[0] == 'K' && output[1] == 'S' && output[2] == 'U' ? 1 : 0;
}
