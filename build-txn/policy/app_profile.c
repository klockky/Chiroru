#include "hook/patch_memory.h"
#include "infra/symbol_resolver.h"
#include "linux/kallsyms.h"
#include <linux/capability.h>
#include <linux/cred.h>
#include <linux/sched.h>
#include <linux/sched/user.h>
#include <linux/sched/signal.h>
#include <linux/seccomp.h>
#include <linux/slab.h>
#include <linux/thread_info.h>
#include <linux/uidgid.h>
#include <linux/version.h>

#include "policy/allowlist.h"
#include "policy/app_profile.h"
#include "klog.h" // IWYU pragma: keep
#include "selinux/selinux.h"
#include "infra/su_mount_ns.h"
#include "hook/hook_manager.h"

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 7, 0)
static struct group_info root_groups = { .usage = REFCOUNT_INIT(2) };
#else
static struct group_info root_groups = { .usage = ATOMIC_INIT(2) };
#endif

void setup_groups(struct root_profile *profile, struct cred *cred)
{
    if (profile->groups_count > KSU_MAX_GROUPS) {
        pr_warn("Failed to setgroups, too large group: %d!\n", profile->uid);
        return;
    }

    if (profile->groups_count == 1 && profile->groups[0] == 0) {
        // setgroup to root and return early.
        if (cred->group_info)
            put_group_info(cred->group_info);
        cred->group_info = get_group_info(&root_groups);
        return;
    }

    u32 ngroups = profile->groups_count;
    struct group_info *group_info = groups_alloc(ngroups);
    if (!group_info) {
        pr_warn("Failed to setgroups, ENOMEM for: %d\n", profile->uid);
        return;
    }

    int i;
    for (i = 0; i < ngroups; i++) {
        gid_t gid = profile->groups[i];
        kgid_t kgid = make_kgid(current_user_ns(), gid);
        if (!gid_valid(kgid)) {
            pr_warn("Failed to setgroups, invalid gid: %d\n", gid);
            put_group_info(group_info);
            return;
        }
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 9, 0)
        group_info->gid[i] = kgid;
#else
        GROUP_AT(group_info, i) = kgid;
#endif
    }

    groups_sort(group_info);
    set_groups(cred, group_info);
    put_group_info(group_info);
}

void seccomp_filter_release(struct task_struct *tsk);

// https://cs.android.com/android/_/android/kernel/common/+/5346453405bf12d7ed6003f45dd47b71744fe1be
// Some 15-6.6 kernel have this backport while others don't have, e.g. Pixel 10
// See also:
// https://github.com/tiann/KernelSU/issues/3629
#define NEED_BACKPORT_COMPAT                                                                                           \
    LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0) && LINUX_VERSION_CODE < KERNEL_VERSION(6, 11, 0)

#if NEED_BACKPORT_COMPAT
static bool has_call_to_spin_lock = false;
#endif

void disable_seccomp(void)
{
	struct sighand_struct *sh = *(struct sighand_struct **)((char *)current + 0x708);

	if (!sh)
		return;

	spin_lock_irq(&sh->siglock);
	clear_thread_flag(TIF_SECCOMP);
	*(int *)((char *)current + 0x770) = 0;		/* seccomp.mode */
	*(int *)((char *)current + 0x774) = 0;		/* seccomp.filter_count */
	*(void **)((char *)current + 0x778) = NULL;	/* seccomp.filter */
	spin_unlock_irq(&sh->siglock);
}

/* GRANT_ROOT from an already-root bootstrap child still needs the KSU domain.
 * Keep this explicit ioctl path separate from sucompat and user root profiles.
 * No UID, groups, capabilities, namespaces or global SELinux mode are changed. */
int ksu_adopt_root_domain(void)
{
    struct cred *cred;
    int error;

    if (current_uid().val != 0 || current_euid().val != 0 ||
        test_thread_flag(TIF_KSU_DISABLE_ESCAPE_WITH_ROOT))
        return -EPERM;
    if (is_ksu_domain())
        return 0;
    cred = prepare_creds();
    if (!cred)
        return -ENOMEM;
    error = setup_selinux(KERNEL_SU_CONTEXT, cred);
    if (error) {
        abort_creds(cred);
        return error;
    }
    return commit_creds(cred);
}

int escape_with_root_profile(void)
{
    int ret = 0;
    struct cred *cred;
    struct task_struct *p = current;
    struct task_struct *t;
    struct root_profile *profile = NULL;
    struct user_struct *new_user;

    cred = prepare_creds();
    if (!cred) {
        pr_warn("prepare_creds failed!\n");
        return -ENOMEM;
    }

    if (cred->euid.val == 0) {
        pr_warn("Already root, don't escape!\n");
        goto out_abort_creds;
    }

    if (test_thread_flag(TIF_KSU_DISABLE_ESCAPE_WITH_ROOT)) {
        pr_warn("TIF_KSU_DISABLE_ESCAPE_WITH_ROOT found, don't escape!\n");
        goto out_abort_creds;
    }

    profile = ksu_get_root_profile(cred->uid.val);

    cred->uid.val = profile->uid;
    cred->suid.val = profile->uid;
    cred->euid.val = profile->uid;
    cred->fsuid.val = profile->uid;

    cred->gid.val = profile->gid;
    cred->fsgid.val = profile->gid;
    cred->sgid.val = profile->gid;
    cred->egid.val = profile->gid;
    cred->securebits = 0;

    BUILD_BUG_ON(sizeof(profile->capabilities.effective) != sizeof(kernel_cap_t));

    /*
     * Mirror the kernel set*uid path: update cred->user first, then
     * cred->ucounts, before commit_creds(). commit_creds() moves
     * RLIMIT_NPROC accounting based on cred->user; if uid changes while
     * user/ucounts stay stale, the old charge can remain pinned to the
     * previous UID.
     * See kernel/sys.c:set_user() and kernel/cred.c:set_cred_ucounts() /
     * commit_creds():
     * https://github.com/torvalds/linux/blob/v5.14/kernel/sys.c
     * https://github.com/torvalds/linux/blob/v5.14/kernel/cred.c
     */
    new_user = alloc_uid(cred->uid);
    if (!new_user) {
        ret = -ENOMEM;
        goto out_abort_creds;
    }

    free_uid(cred->user);
    cred->user = new_user;

    // v5.14+ added cred->ucounts, so we must refresh it after changing uid/user:
    // https://github.com/torvalds/linux/commit/905ae01c4ae2ae3df05bb141801b1db4b7d83c61#diff-ff6060da281bd9ef3f24e17b77a9b0b5b2ed2d7208bb69b29107bee69732bd31
    // on older kernels, per-UID process accounting lives in user_struct.
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 14, 0)
    if (set_cred_ucounts(cred)) {
        goto out_abort_creds;
    }
#endif

    memcpy(&cred->cap_effective, &profile->capabilities.effective, sizeof(cred->cap_effective));
    memcpy(&cred->cap_permitted, &profile->capabilities.effective, sizeof(cred->cap_permitted));
    memcpy(&cred->cap_bset, &profile->capabilities.effective, sizeof(cred->cap_bset));
    if (profile->uid != 0) {
        memcpy(&cred->cap_inheritable, &profile->capabilities.effective, sizeof(cred->cap_inheritable));
        memcpy(&cred->cap_ambient, &profile->capabilities.effective, sizeof(cred->cap_ambient));
    }

    setup_groups(profile, cred);
    setup_selinux(profile->selinux_domain, cred);

    commit_creds(cred);

    disable_seccomp();

    if (profile->flags & FLAG_KSU_NO_NEW_PRIVS) {
        set_thread_flag(TIF_KSU_DISABLE_ESCAPE_WITH_ROOT);
    }

#ifdef KSU_KPROBES_HOOK
    /* vendor: tracepoint marking is unused (sys_enter tracepoint is unavailable) */
    ksu_set_task_tracepoint_flag(current);
#endif

    setup_mount_ns(profile->namespaces);
    ksu_put_root_profile(profile);
    return 0;

out_abort_creds:
    if (profile)
        ksu_put_root_profile(profile);
    abort_creds(cred);
    return ret;
}

void escape_to_root_for_init(void)
{
    struct cred *cred = prepare_creds();
    if (!cred) {
        pr_err("Failed to prepare init's creds!\n");
        return;
    }

    setup_selinux(KERNEL_SU_CONTEXT, cred);
    commit_creds(cred);
}

void __init ksu_app_profile_init(void)
{
#if NEED_BACKPORT_COMPAT
    unsigned long size = 0;
    int ret;
    void *raw_spin_lock_irq_sym = find_kernel_symbol_exact("_raw_spin_lock_irq");
    void *seccomp_filter_release_sym = find_kernel_symbol_exact("seccomp_filter_release");
    ret = kallsyms_lookup_size_offset(seccomp_filter_release_sym, &size, NULL);
    if (!ret || !size) {
        pr_err("failed to get size of seccomp_filter_release: %d, use 128\n", ret);
        size = 128;
    }
    has_call_to_spin_lock = scan_call_to(seccomp_filter_release_sym, size, raw_spin_lock_irq_sym) != NULL;
    pr_info("seccomp_filter_release has_call_to_spin_lock = %d\n", has_call_to_spin_lock);
#endif
}
