#define _POSIX_C_SOURCE 200809L
#include "process_wait.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int64_t monotonic_ms(void) {
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void *reap_child(void *argument) {
  pid_t pid = (pid_t)(intptr_t)argument;
  while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) { }
  return NULL;
}

static void stop_child(pid_t pid, struct process_result *result) {
  /* Kill the group while the leader is still ours (not yet reaped). Also kill
   * the leader directly in case it has not reached setpgid() yet. */
  kill(-pid, SIGKILL);
  kill(pid, SIGKILL);
  for (int i = 0; i < 10; i++) {
    pid_t wr = waitpid(pid, NULL, WNOHANG);
    if (wr == pid || (wr < 0 && errno == ECHILD)) return;
    poll(NULL, 0, 10);
  }
  /* Even SIGKILL can be delayed by an uninterruptible kernel wait. Never hold
   * the caller (and its cleanup) in a blocking waitpid in that case. */
  pthread_attr_t attributes;
  pthread_t thread;
  int error = pthread_attr_init(&attributes);
  if (error == 0) {
    error = pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    if (error == 0)
      error = pthread_create(&thread, &attributes, reap_child, (void *)(intptr_t)pid);
    pthread_attr_destroy(&attributes);
  }
  if (error != 0 && result->error == 0) result->error = error;
}

static void append_output(char *output, size_t capacity, size_t *used,
                          const char *bytes, size_t count,
                          struct process_result *result) {
  size_t available = capacity - 1 - *used;
  size_t copied = count < available ? count : available;
  memcpy(output + *used, bytes, copied);
  *used += copied;
  output[*used] = 0;
  if (copied < count) result->output_truncated = 1;
}

struct process_result collect_process(pid_t pid, int output_fd, int timeout_ms,
                                      char *output, size_t output_capacity) {
  struct process_result result = {.exit_code = -1};
  size_t used = 0;
  int status, reaped = 0;
  char buffer[4096];
  int64_t started = monotonic_ms();
  output[0] = 0;
  if (started < 0) { result.error = errno; goto done; }
  int64_t deadline = started + timeout_ms;
  int flags = fcntl(output_fd, F_GETFL);
  if (flags < 0 || fcntl(output_fd, F_SETFL, flags | O_NONBLOCK) < 0) {
    result.error = errno;
    goto done;
  }

  for (;;) {
    int64_t now = monotonic_ms();
    if (now < 0) { result.error = errno; break; }

    pid_t wr = waitpid(pid, &status, WNOHANG);
    if (wr == pid) {
      reaped = 1;
      result.exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
      /* Do not wait for EOF: a daemon may have inherited stdout. Limit the
       * final drain too, even if a descendant keeps producing output. */
      while (output_fd >= 0) {
        ssize_t count = read(output_fd, buffer, sizeof buffer);
        if (count <= 0) break;
        append_output(output, output_capacity, &used, buffer, (size_t)count, &result);
        if (used == output_capacity - 1) break;
      }
      break;
    }
    if (wr < 0) {
      if (errno == EINTR) continue;
      result.error = errno;
      // An already-reaped child no longer belongs to us: never signal its PID.
      if (errno == ECHILD) reaped = 1;
      break;
    }
    if (now >= deadline) { result.timed_out = 1; break; }

    int remaining = (int)(deadline - now);
    struct pollfd descriptor = {.fd = output_fd, .events = POLLIN};
    int ready = poll(&descriptor, 1, remaining < 50 ? remaining : 50);
    if (ready < 0) {
      if (errno == EINTR) continue;
      result.error = errno;
      break;
    }
    if (ready == 0) continue;
    if (descriptor.revents & POLLNVAL) { result.error = EBADF; break; }
    ssize_t count = read(output_fd, buffer, sizeof buffer);
    if (count > 0) {
      /* Keep draining after the capture buffer fills; otherwise a verbose
       * child blocks in write(). Check the deadline after every chunk. */
      append_output(output, output_capacity, &used, buffer, (size_t)count, &result);
    } else if (count == 0) {
      close(output_fd);
      output_fd = -1; /* EOF is not process exit; keep polling waitpid. */
    } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
      result.error = errno;
      break;
    }
  }

done:
  if (!reaped) stop_child(pid, &result);
  if (output_fd >= 0) close(output_fd);
  return result;
}
