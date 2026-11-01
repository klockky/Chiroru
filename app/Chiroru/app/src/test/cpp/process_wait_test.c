#define _POSIX_C_SOURCE 200809L
#include "process_wait.h"

#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int64_t now_ms(void) {
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void sleep_ms(int ms) {
  struct timespec delay = {.tv_sec = ms / 1000, .tv_nsec = (ms % 1000) * 1000000L};
  while (nanosleep(&delay, &delay) < 0 && errno == EINTR) { }
}

static void child(const char *mode) {
  char bytes[4096];
  memset(bytes, 'x', sizeof bytes);
  if (!strcmp(mode, "success")) {
    dprintf(1, "stdout\n");
    dprintf(2, "stderr\n");
    _exit(0);
  }
  if (!strcmp(mode, "failure")) _exit(7);
  if (!strcmp(mode, "signal")) { raise(SIGKILL); _exit(99); }
  if (!strcmp(mode, "closed_output")) { close(1); close(2); }
  if (!strcmp(mode, "full_buffer") || !strcmp(mode, "verbose_success") || !strcmp(mode, "tiny_buffer")) {
    for (int i = 0; i < 128; i++) {
      if (write(1, bytes, sizeof bytes) != sizeof bytes) _exit(99);
    }
    if (strcmp(mode, "full_buffer")) _exit(0);
  }
  if (!strcmp(mode, "continuous_output")) {
    for (;;) {
      if (write(1, bytes, sizeof bytes) != sizeof bytes) _exit(99);
      sleep_ms(1);
    }
  }
  if (!strcmp(mode, "descendant") || !strcmp(mode, "inherited_pipe")) {
    pid_t descendant = fork();
    if (descendant < 0) _exit(99);
    if (descendant == 0) { sleep_ms(2000); _exit(0); }
    dprintf(1, "%d\n", descendant);
    if (!strcmp(mode, "inherited_pipe")) _exit(0);
  }
  sleep_ms(2000);
  _exit(0);
}

static void signal_handler(int signal_number) { (void)signal_number; }

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  const char *mode = argv[1];
  int pipe_fd[2];
  /* Own orphaned grandchildren so the tests can verify timeout cleanup and
   * leave no background process behind, including on a failed assertion. */
  if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0 || pipe(pipe_fd) != 0) return 2;
  pid_t pid = fork();
  if (pid == 0) {
    if (setpgid(0, 0) != 0) _exit(126);
    dup2(pipe_fd[1], 1);
    dup2(pipe_fd[1], 2);
    close(pipe_fd[0]); close(pipe_fd[1]);
    child(mode);
  }
  if (pid < 0) return 2;
  close(pipe_fd[1]);
  struct itimerval timer = {0};
  if (!strcmp(mode, "interrupted")) {
    struct sigaction action = {.sa_handler = signal_handler};
    sigemptyset(&action.sa_mask);
    sigaction(SIGALRM, &action, NULL);
    timer.it_interval.tv_usec = timer.it_value.tv_usec = 1000;
    setitimer(ITIMER_REAL, &timer, NULL);
  }
  char output[256];
  size_t capacity = !strcmp(mode, "tiny_buffer") ? 1 : sizeof output;
  int expect_timeout = !strcmp(mode, "quiet") || !strcmp(mode, "closed_output") ||
      !strcmp(mode, "full_buffer") || !strcmp(mode, "continuous_output") ||
      !strcmp(mode, "interrupted") || !strcmp(mode, "descendant");
  int64_t started = now_ms();
  struct process_result result = collect_process(pid, pipe_fd[0], expect_timeout ? 200 : 1500,
                                                 output, capacity);
  int64_t elapsed = now_ms() - started;
  memset(&timer, 0, sizeof timer);
  setitimer(ITIMER_REAL, &timer, NULL);
  printf("%s: rc=%d timeout=%d error=%d truncated=%d elapsed_ms=%lld bytes=%zu\n",
         mode, result.exit_code, result.timed_out, result.error, result.output_truncated,
         (long long)elapsed, strlen(output));

  int ok = !result.error && result.timed_out == expect_timeout && elapsed < 1000;
  if (expect_timeout) ok &= result.exit_code == -1 && elapsed >= 180;
  else if (!strcmp(mode, "failure")) ok &= result.exit_code == 7;
  else if (!strcmp(mode, "signal")) ok &= result.exit_code == -1;
  else ok &= result.exit_code == 0;
  if (!strcmp(mode, "success")) ok &= !strcmp(output, "stdout\nstderr\n");
  if (!strcmp(mode, "full_buffer") || !strcmp(mode, "continuous_output") ||
      !strcmp(mode, "verbose_success") || !strcmp(mode, "tiny_buffer"))
    ok &= result.output_truncated && strlen(output) == capacity - 1;

  if (!strcmp(mode, "descendant") || !strcmp(mode, "inherited_pipe")) {
    pid_t descendant = (pid_t)strtol(output, NULL, 10);
    if (descendant <= 0) return 1;
    int status = 0;
    pid_t wr = 0;
    if (!strcmp(mode, "descendant")) {
      for (int i = 0; i < 50 && wr == 0; i++) {
        wr = waitpid(descendant, &status, WNOHANG);
        if (!wr) sleep_ms(10);
      }
      ok &= wr == descendant && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL;
    }
    if (wr == 0) {
      kill(descendant, SIGKILL);
      waitpid(descendant, NULL, 0);
    }
  }
  return ok ? 0 : 1;
}
