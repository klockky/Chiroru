// SPDX-License-Identifier: GPL-2.0-only
#include <linux/fs.h>
#include <linux/namei.h>
#include <linux/magic.h>
#include <linux/mutex.h>
#include <linux/vmalloc.h>
#include <linux/slab.h>
#include <linux/version.h>
#include "security.h"
#include "ss/services.h"
#include "policy_writer.h"
#include "policy_transaction.h"
#include "selinuxfs_layout.h"
#include "chiron_policy_abi.h"
#include "klog.h"

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 4, 0) || \
    LINUX_VERSION_CODE >= KERNEL_VERSION(5, 5, 0)
#error "This transaction backend is specific to the reviewed vendor 5.4 tree"
#endif
#if !defined(CONFIG_PREEMPT_COUNT) || !defined(CONFIG_PREEMPTION)
#error "chiron requires consistent PREEMPT_COUNT/PREEMPTION build headers"
#endif
#ifdef CONFIG_DEBUG_PREEMPT
#error "chiron has no DEBUG_PREEMPT; use its real inline preemption counters"
#endif

/* Lock order is the native selinuxfs order: fsi->mutex -> policy_rwlock.
 * kern_path pins the mount/superblock until after the mutex is released.
 * Do not open policy/load or call their file operations while holding mutex:
 * those operations acquire it themselves. Native APIs below do not.
 *
 * This vendor uses get_tree_single(), so all selinuxfs mounts share the same
 * fsi. Other out-of-tree writers must use this protocol too; an unrelated
 * module modifying policy without it cannot be made safe by this backend.
 */
int ksu_policy_transaction(ksu_policy_mutator mutate, void *arg)
{
    struct path path;
    struct super_block *sb;
    struct ksu_selinux_fs_info *fsi;
    struct policydb *copy = NULL;
    struct policy_file input;
    struct selinux_ss *ss;
    void *blob = NULL;
    size_t length = 0;
    bool parsed = false;
    int ret;

    ret = kern_path("/sys/fs/selinux", LOOKUP_FOLLOW, &path);
    if (ret)
        return ret;
    sb = path.dentry->d_sb;
    if (sb->s_magic != SELINUX_MAGIC || strcmp(sb->s_type->name, "selinuxfs")) {
        pr_err("sepol: unexpected filesystem at /sys/fs/selinux\n");
        ret = -EOPNOTSUPP;
        goto out_path;
    }
    fsi = ksu_selinux_fs_private(sb);
    /* Layout is generated from the selected vendor source. These checks catch
     * wrong mounts/state, but are not a substitute for matching the live ABI.
     */
    if (!fsi || fsi->sb != sb || fsi->state != &selinux_state) {
        pr_err("sepol: selinuxfs vendor layout/state mismatch\n");
        ret = -EOPNOTSUPP;
        goto out_path;
    }
    mutex_lock(&fsi->mutex);
    if (!selinux_state.initialized || !selinux_state.ss) {
        ret = -EAGAIN;
        goto out_unlock;
    }
    ss = selinux_state.ss;
    copy = kzalloc(sizeof(*copy), GFP_KERNEL);
    if (!copy) {
        ret = -ENOMEM;
        goto out_unlock;
    }

    /* Count and copy with checked writers; ignore the live heuristic db.len.
     * The outer mutex excludes load, booleans, other KSU transactions and
     * sel_open_policy's allocate/serialize window throughout both passes.
     */
    read_lock(&ss->policy_rwlock);
    ret = ksu_policy_measure(&ss->policydb, &length);
    read_unlock(&ss->policy_rwlock);
    if (ret)
        goto out_unlock;
    blob = vmalloc(length);
    if (!blob) {
        ret = -ENOMEM;
        goto out_unlock;
    }
    read_lock(&ss->policy_rwlock);
    ret = ksu_policy_serialize(&ss->policydb, blob, length);
    read_unlock(&ss->policy_rwlock);
    if (ret)
        goto out_unlock;

    input.data = blob;
    input.len = length;
    ret = policydb_read(copy, &input);
    if (ret)
        goto out_unlock; /* policydb_read destroys partial parses */
    parsed = true;
    if (input.len) {
        ret = -EINVAL;
        goto out_unlock;
    }
    vfree(blob);
    blob = NULL;
    ret = mutate(copy, arg);
    if (ret)
        goto out_unlock;
    ret = ksu_policy_measure(copy, &length);
    if (ret)
        goto out_unlock;
    blob = vmalloc(length);
    if (!blob) {
        ret = -ENOMEM;
        goto out_unlock;
    }
    ret = ksu_policy_serialize(copy, blob, length);
    if (ret)
        goto out_unlock;

    /* Check the native parser's normalized representation before committing.
     * For example it discards serialized object_r role data. Do not publish
     * a length which only describes a noncanonical intermediate policy.
     */
    policydb_destroy(copy);
    memset(copy, 0, sizeof(*copy));
    parsed = false;
    input.data = blob;
    input.len = length;
    ret = policydb_read(copy, &input);
    if (ret)
        goto out_unlock;
    parsed = true;
    {
        size_t normalized_len = 0;
        ret = ksu_policy_measure(copy, &normalized_len);
        if (!ret && (input.len || normalized_len != length))
            ret = -EINVAL;
        if (ret)
            goto out_unlock;
    }

    /* Parse/validate, preserve booleans, convert SIDs, rebuild class mappings,
     * swap under rwlock, and reset caches/notify with the correct sequence.
     * Our mutators cannot change classes, booleans or policy capabilities,
     * so their selinuxfs nodes and pending boolean arrays remain valid.
     * No KSU pointer is retained by the loaded policy.
     */
    ret = security_load_policy(&selinux_state, blob, length);
    if (!ret)
        pr_info("sepol: native transaction committed %zu bytes\n", length);
out_unlock:
    if (parsed)
        policydb_destroy(copy);
    kfree(copy);
    vfree(blob);
    mutex_unlock(&fsi->mutex);
out_path:
    path_put(&path);
    return ret;
}
