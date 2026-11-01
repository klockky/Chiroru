/* SPDX-License-Identifier: GPL-2.0-only */
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "sepolicy.h"
#include "policy_transaction.h"

void *ksu_compat_kvrealloc(const void *p, size_t oldsize, size_t newsize, gfp_t flags)
{
    void *n;
    if (oldsize >= newsize) return (void *)p;
    n = kvmalloc(newsize, flags);
    if (!n) return NULL;
    memcpy(n, p, oldsize);
    kvfree(p);
    return n;
}

#define REQUIRE(x) do { if (!(x)) return -EINVAL; } while (0)
static int mutate(struct policydb *db, void *unused)
{
    const char *root = "chiroru_adb", *node = "chiroru_adb_device";
    const char *parents[] = {"shell", "adbd"};
    const char *readers[] = {"shell", "untrusted_app_all"};
    const char *read_perms[] = {"open", "read", "getattr", "ioctl"};
    const char *signals[] = {"getattr", "signal", "sigkill", "sigstop"};
    const char *ioctls[] = {"file", "chr_file", "blk_file", "fifo_file"};
    int i, j;
    (void)unused;
    REQUIRE(ksu_type(db, root, "domain"));
    REQUIRE(ksu_enforce(db, root));
    REQUIRE(ksu_typeattribute(db, root, "mlstrustedsubject"));
    REQUIRE(ksu_typeattribute(db, root, "netdomain"));
    REQUIRE(ksu_allow(db, root, NULL, NULL, NULL));
    for (i = 0; i < ARRAY_SIZE(ioctls); ++i)
        REQUIRE(ksu_allowxperm(db, root, NULL, ioctls[i], NULL));
    REQUIRE(ksu_type(db, node, "dev_type"));
    REQUIRE(ksu_typeattribute(db, node, "mlstrustedobject"));
    for (i = 0; i < ARRAY_SIZE(readers); ++i)
        for (j = 0; j < ARRAY_SIZE(read_perms); ++j)
            REQUIRE(ksu_allow(db, readers[i], node, "chr_file", read_perms[j]));
    REQUIRE(ksu_allow(db, "shell", node, "chr_file", "write"));
    for (i = 0; i < ARRAY_SIZE(parents); ++i) {
        REQUIRE(ksu_allow(db, parents[i], root, "fd", "use"));
        for (j = 0; j < ARRAY_SIZE(signals); ++j)
            REQUIRE(ksu_allow(db, parents[i], root, "process", signals[j]));
    }
    /* No transition rule gives ordinary shell/apps entry into the root domain.
     * Only the authenticated GRANT ioctl can set it on a newly prepared cred. */
    return 0;
}

int chiroru_adb_policy(void) { return ksu_policy_transaction(mutate, NULL); }
