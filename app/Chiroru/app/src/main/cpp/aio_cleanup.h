#ifndef CHIRORU_AIO_CLEANUP_H
#define CHIRORU_AIO_CLEANUP_H
#include <sys/syscall.h>
#include <unistd.h>

/* io_destroy -> kill_ioctx -> vm_munmap owns the ring mapping. Never munmap
 * its old address again: another app thread may already have reused it. */
static inline int chiroru_aio_destroy(unsigned long *context) {
    if (!*context) return 0;
    if (syscall(SYS_io_destroy, *context) < 0) return -1;
    *context = 0;
    return 0;
}
#endif
