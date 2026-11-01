/* SPDX-License-Identifier: GPL-2.0-only */
/* Userspace adapter to the already verified vendor parser/mutator/writer.
 * No kernel structure access or mocked policy publication is linked here. */
#include "compat.h"
#include "ss/policydb.h"
#include "selinux/sepolicy.h"
#include "selinux/policy_writer.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

_Thread_local long host_fail_after = -1, host_allocations;
_Thread_local bool host_quiet;
void host_log(const char *format, ...) {
    va_list args; va_start(args, format); vfprintf(stderr, format, args); va_end(args);
}
void *host_alloc(size_t size, bool zero) {
    if (host_allocations++ == host_fail_after) return NULL;
    return zero ? calloc(1, size ? size : 1) : malloc(size ? size : 1);
}
void *ksu_compat_kvrealloc(const void *p, size_t old, size_t size, gfp_t flags) {
    (void)flags;
    if (old >= size) return (void *)p;
    void *next = malloc(size);
    if (next && p) memcpy(next, p, old);
    if (next) free((void *)p);
    return next;
}

static int rules(struct policydb *db) {
    const char *domain = "chiroru_console";
    if (!ksu_type(db, domain, "domain") || !ksu_enforce(db, domain) ||
        !ksu_typeattribute(db, domain, "mlstrustedsubject") ||
        !ksu_typeattribute(db, domain, "netdomain") ||
        !ksu_allow(db, domain, NULL, NULL, NULL)) return -1;
    const char *classes[] = {"file", "chr_file", "blk_file", "fifo_file"};
    for (unsigned i = 0; i < sizeof classes / sizeof classes[0]; ++i)
        if (!ksu_allowxperm(db, domain, NULL, classes[i], NULL)) return -1;
    /* No transition or app->root permissions: only the already-root bootstrap
     * enters this enforcing domain, during its bounded bootstrap window. */
    return 0;
}

int console_policy_transform(void *input, size_t length, void **output, size_t *size) {
    struct policydb db = {0}, checked = {0};
    struct policy_file file = {input, length};
    *output = NULL;
    if (policydb_read(&db, &file)) return -1;
    int rc = -1;
    if (file.len || rules(&db) || ksu_policy_measure(&db, size) || *size > 16 * 1024 * 1024) goto done;
    *output = malloc(*size);
    if (!*output || ksu_policy_serialize(&db, *output, *size)) goto done;
    file = (struct policy_file){*output, *size};
    if (policydb_read(&checked, &file)) goto done;
    struct type_datum *type = hashtab_search(checked.p_types.table, "chiroru_console");
    if (!file.len && type && !ebitmap_get_bit(&checked.permissive_map, type->value)) {
        /* Match the native loader roundtrip used by the transaction library.
         * avtab collision chains reverse on read; two reads keep serialization
         * order stable across repeated applications of the same rules. */
        size_t canonical_size;
        if (!ksu_policy_measure(&checked, &canonical_size) && canonical_size == *size &&
            !ksu_policy_serialize(&checked, *output, *size)) rc = 0;
    }
    policydb_destroy(&checked);
done:
    policydb_destroy(&db);
    if (rc) { free(*output); *output = NULL; }
    return rc;
}

static void *read_policy(size_t *length) {
    int fd = open("/sys/fs/selinux/policy", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    /* selinuxfs reports st_size=0; use bounded reads until EOF. */
    const size_t capacity = 16 * 1024 * 1024;
    char *data = malloc(capacity);
    size_t used = 0;
    while (data && used < capacity) {
        ssize_t n = read(fd, data + used, capacity - used);
        if (n < 0 && errno == EINTR) continue;
        if (n == 0) break;
        if (n < 0) { free(data); data = NULL; break; }
        used += n;
    }
    close(fd);
    if (!used || used == capacity) { free(data); data = NULL; }
    *length = used;
    return data;
}

int console_policy_install(void) {
    hashtab_cache_init(); avtab_cache_init(); ebitmap_cache_init();
    size_t length = 0, size = 0, latest_length = 0;
    void *input = read_policy(&length), *output = NULL, *latest = NULL;
    int rc = -1;
    if (!input || console_policy_transform(input, length, &output, &size)) goto done;
    latest = read_policy(&latest_length);
    if (!latest || length != latest_length || memcmp(input, latest, length)) goto done;
    int fd = open("/sys/fs/selinux/load", O_WRONLY | O_CLOEXEC);
    if (fd < 0) goto done;
    /* selinuxfs validates and atomically publishes one complete image. Never
     * split this write; on rejection the kernel retains its existing policy. */
    ssize_t n;
    do { n = write(fd, output, size); } while (n < 0 && errno == EINTR);
    close(fd);
    if (n == (ssize_t)size) rc = 0;
done:
    free(latest); free(output); free(input);
    return rc;
}
