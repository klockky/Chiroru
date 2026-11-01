// SPDX-License-Identifier: GPL-2.0-only
#include <linux/kernel.h>
#include <linux/string.h>
#include <linux/errno.h>
#include "security.h"
#include "ss/policydb.h"
#include "ss/conditional.h"
#include "policy_writer.h"
#include "policy_sink.h"
#include "policy_writer.inc"

int ksu_policy_measure(struct policydb *db, size_t *length)
{
    struct ksu_policy_sink sink = { .capacity = KSU_POLICY_MAX_BYTES };
    int ret = ksu_checked_policydb_write(db, &sink);

    if (!ret)
        *length = sink.used;
    return ret;
}

int ksu_policy_serialize(struct policydb *db, void *data, size_t capacity)
{
    struct ksu_policy_sink sink = { .data = data, .capacity = capacity };
    int ret;

    if (!data || !capacity || capacity > KSU_POLICY_MAX_BYTES)
        return -EINVAL;
    ret = ksu_checked_policydb_write(db, &sink);
    if (!ret && sink.used != capacity)
        ret = -EINVAL;
    return ret;
}
