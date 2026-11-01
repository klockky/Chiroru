#ifndef CHIRORU_PROCESS_WAIT_H
#define CHIRORU_PROCESS_WAIT_H

#include <stddef.h>
#include <sys/types.h>

struct process_result {
  int exit_code; /* -1 for a signal, timeout or supervision error. */
  int timed_out;
  int error;
  int output_truncated;
};

/* Takes ownership of output_fd. The child must call setpgid(0, 0) before exec
 * so a timeout can stop its process group without affecting the caller.
 * Output is NUL-terminated; output_capacity must be at least one. */
struct process_result collect_process(pid_t pid, int output_fd, int timeout_ms,
                                      char *output, size_t output_capacity);

#endif
