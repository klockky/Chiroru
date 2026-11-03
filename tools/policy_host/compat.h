/* Host-only environment for the real vendor policy parser/mutators/writer.
 * Not a kernel ABI or scheduler emulator. ASan/UBSan and allocation failures
 * exercise ownership, parser rejection and serialization without a device.
 */
#ifndef POLICY_HOST_COMPAT_H
#define POLICY_HOST_COMPAT_H
#define _GNU_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <errno.h>
#include <endian.h>
#include <asm/types.h>
#include <pthread.h>
#include <stdarg.h>
#undef bool
typedef _Bool bool;

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;
typedef uint32_t __u32;
typedef uint16_t __le16;
typedef uint32_t __le32;
typedef uint64_t __le64;
typedef uint16_t __be16;
typedef uint32_t __be32;
typedef uint64_t __be64;
typedef int gfp_t;
#define __user
#define __init
#if defined(__LP64__) || defined(_LP64)
#define CONFIG_64BIT 1
#define BITS_PER_LONG 64
#else
#define BITS_PER_LONG 32
#endif
#define CONFIG_PREEMPT_COUNT 1
#define CONFIG_PREEMPTION 1
#define KERNEL_VERSION(a,b,c) (((a) << 16) | ((b) << 8) | (c))
#define LINUX_VERSION_CODE KERNEL_VERSION(5,4,125)
#define GFP_KERNEL 0
#define GFP_ATOMIC 0
#define __GFP_ZERO 0
#define SLAB_PANIC 0
#define SLAB_ACCOUNT 0
#define PAGE_SIZE 4096
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define U8_MAX UINT8_MAX
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define BUG_ON(x) assert(!(x))
#define WARN_ON(x) (!!(x))
#define unlikely(x) (x)
#define likely(x) (x)
#define min(a,b) ((a) < (b) ? (a) : (b))
#define max(a,b) ((a) > (b) ? (a) : (b))
#define rounddown(x,y) ((x) / (y) * (y))
#define roundup(x,y) (((x) + (y) - 1) / (y) * (y))
#define cpu_to_le16 htole16
#define cpu_to_le32 htole32
#define cpu_to_le64 htole64
#define le16_to_cpu le16toh
#define le32_to_cpu le32toh
#define le64_to_cpu le64toh
#define be64_to_cpu be64toh
#define cpu_to_be64 htobe64
#define cond_resched() ((void)0)
#define pr_err(...) host_log(__VA_ARGS__)
#define pr_warn(...) host_log(__VA_ARGS__)
#define pr_info(...) host_log(__VA_ARGS__)
#define pr_debug(...) ((void)0)
#define __KSU_H_SELINUX
#define KERNEL_SU_DOMAIN "ksu"
#define KERNEL_SU_FILE "ksu_file"
#define _SS_SIDTAB_H_
#define _SELINUX_SECURITY_H_
struct sidtab;
struct context;
struct netlbl_lsm_catmap;
struct netlbl_lsm_secattr;
struct extended_perms;
struct extended_perms_decision;
struct extended_perms_data { u32 p[8]; };
struct av_decision { u32 allowed, auditallow, auditdeny; };
int sidtab_init(struct sidtab *s);
void sidtab_destroy(struct sidtab *s);
int sidtab_set_initial(struct sidtab *s, u32 sid, struct context *c);
void hashtab_cache_init(void);
void avtab_cache_init(void);
void ebitmap_cache_init(void);

extern _Thread_local long host_fail_after;
extern _Thread_local long host_allocations;
extern _Thread_local bool host_quiet;
void host_log(const char *fmt, ...);
void *host_alloc(size_t size, bool zero);
#define kmalloc(s,f) host_alloc((s), false)
#define kzalloc(s,f) host_alloc((s), true)
#define kvmalloc(s,f) host_alloc((s), false)
#define vmalloc(s) host_alloc((s), false)
#define kfree(p) free((void *)(p))
#define kvfree(p) free((void *)(p))
#define vfree(p) free((void *)(p))
static inline void *host_array(size_t n, size_t s, bool zero) {
    if (n && s > SIZE_MAX / n) return NULL;
    return host_alloc(n * s, zero);
}
#define kcalloc(n,s,f) host_array(n,s,true)
#define kvcalloc(n,s,f) host_array(n,s,true)
#define kmalloc_array(n,s,f) host_array(n,s,false)
#define kvmalloc_array(n,s,f) host_array(n,s,false)
static inline char *kstrdup(const char *s, gfp_t flags) {
    (void)flags;
    char *p = host_alloc(strlen(s) + 1, false);
    if (p) strcpy(p, s);
    return p;
}
struct kmem_cache { size_t size; };
static inline struct kmem_cache *kmem_cache_create(const char *name, size_t size,
                                                  size_t align, int flags, void *ctor) {
    (void)name; (void)align; (void)flags; (void)ctor;
    static struct kmem_cache caches[8];
    static unsigned used;
    assert(used < ARRAY_SIZE(caches));
    caches[used].size = size;
    return &caches[used++];
}
#define kmem_cache_zalloc(c,f) host_alloc((c)->size, true)
#define kmem_cache_free(c,p) free(p)
static inline unsigned long find_next_bit(const unsigned long *a, unsigned long n,
                                          unsigned long start) {
    const unsigned long bits = sizeof(unsigned long) * 8;
    for (; start < n; start++)
        if (a[start / bits] & ((unsigned long)1 << (start % bits))) break;
    return start;
}
#define find_first_bit(a,n) find_next_bit(a,n,0)
static inline int fls(unsigned int x) { return x ? 32 - __builtin_clz(x) : 0; }
static inline unsigned long __fls(unsigned long x) {
    return (sizeof(unsigned long) * 8 - 1) - (unsigned long)__builtin_clzl(x);
}
static inline unsigned long partial_name_hash(unsigned long c, unsigned long prev) {
    return (prev + (c << 4) + (c >> 4)) * 11;
}
static inline ssize_t strscpy(char *dst, const char *src, size_t size) {
    size_t n = strlen(src);
    if (n >= size) return -E2BIG;
    memcpy(dst, src, n + 1);
    return n;
}
static inline int kstrtou16(const char *s, unsigned base, u16 *out) {
    char *end;
    unsigned long n;
    if (!*s || *s == '-') return -EINVAL;
    errno = 0;
    n = strtoul(s, &end, base);
    if (errno || *end || n > UINT16_MAX) return -EINVAL;
    *out = n;
    return 0;
}
static inline unsigned long ksu_copy_from_user(void *to, const void *from, unsigned long n) {
    memcpy(to, from, n);
    return 0;
}

/* Transaction tests use pthread locks and a tiny pinned-fs model. */
struct mutex { pthread_mutex_t value; };
typedef pthread_rwlock_t rwlock_t;
#define mutex_lock(m) assert(!pthread_mutex_lock(&(m)->value))
#define mutex_unlock(m) assert(!pthread_mutex_unlock(&(m)->value))
#define read_lock(m) assert(!pthread_rwlock_rdlock(m))
#define read_unlock(m) assert(!pthread_rwlock_unlock(m))
struct file_system_type { const char *name; };
struct super_block { unsigned long s_magic; struct file_system_type *s_type; void *s_fs_info; };
struct dentry { struct super_block *d_sb; };
struct path { struct dentry *dentry; };
#define LOOKUP_FOLLOW 1
#define SELINUX_MAGIC 0xf97cff8c
int kern_path(const char *name, unsigned flags, struct path *path);
void path_put(struct path *path);
struct selinux_ss;
struct selinux_state { bool initialized; struct selinux_ss *ss; };
extern struct selinux_state selinux_state;
int security_load_policy(struct selinux_state *, void *, size_t);
#endif
