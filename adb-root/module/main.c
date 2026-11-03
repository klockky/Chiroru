/* SPDX-License-Identifier: GPL-2.0-only */
#include <linux/module.h>
#include <linux/miscdevice.h>
#include <linux/cred.h>
#include <linux/security.h>
#include <linux/sched.h>
#include <linux/sched/task.h>
#include <linux/utsname.h>
#include <linux/user_namespace.h>
#include "objsec.h"
#include "../uapi.h"
#include "klog.h"

/* The profile builder supplies this at compile time, before stripping. */
#ifndef CHIRORU_KERNEL_RELEASE
#define CHIRORU_KERNEL_RELEASE "5.4.125-android11-2-g601513d4b115-ab5000001307"
#endif

extern int chiroru_adb_policy(void);
static u32 root_sid, shell_sid;
static unsigned int console_uid;
static char *console_context;
static u32 console_sid;
/* Enrollment is fixed at module load by the privileged bootstrap. */
module_param(console_uid, uint, 0400);
module_param(console_context, charp, 0400);
static bool ready;

static bool console_credential(const struct cred *cred)
{
    const struct task_security_struct *sec = selinux_cred(cred);
    return console_sid && cred->user_ns == &init_user_ns &&
        __kuid_val(cred->uid) == console_uid && __kuid_val(cred->euid) == console_uid &&
        sec && sec->sid == console_sid;
}

static bool shell_credential(const struct cred *cred)
{
    const struct task_security_struct *sec = selinux_cred(cred);
    return cred->user_ns == &init_user_ns &&
        __kuid_val(cred->uid) == 2000 && __kuid_val(cred->euid) == 2000 &&
        sec && sec->sid == shell_sid;
}

static long adb_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct cred *cred;
    struct group_info *groups;
    struct task_security_struct *sec;
    if (!smp_load_acquire(&ready)) return -EAGAIN;
    if (arg) return -EINVAL;
    if (cmd == CHIRORU_ADB_INFO) return CHIRORU_ADB_VERSION;
    if (cmd == CHIRORU_CONSOLE_INFO || cmd == CHIRORU_CONSOLE_GRANT) {
        if (!console_credential(current_cred()) || !console_credential(file->f_cred))
            return -EPERM;
        if (cmd == CHIRORU_CONSOLE_INFO) return CHIRORU_ADB_VERSION;
        /* Separate API: console opens read-only; DAC write access stays shell-only. */
    } else if (cmd == CHIRORU_ADB_GRANT) {
        if (!shell_credential(current_cred()) || !shell_credential(file->f_cred))
            return -EPERM;
        if (!(file->f_mode & FMODE_WRITE)) return -EBADF;
    } else return -ENOTTY;
    if (!current_is_single_threaded()) return -EBUSY;
    cred = prepare_creds();
    if (!cred) return -ENOMEM;
    sec = selinux_cred(cred);
    if (!sec) { abort_creds(cred); return -EINVAL; }
    groups = groups_alloc(0);
    if (!groups) { abort_creds(cred); return -ENOMEM; }
    set_groups(cred, groups);
    put_group_info(groups);
    cred->uid = cred->euid = cred->suid = cred->fsuid = GLOBAL_ROOT_UID;
    cred->gid = cred->egid = cred->sgid = cred->fsgid = GLOBAL_ROOT_GID;
    cred->securebits = 0;
    cred->cap_inheritable = CAP_EMPTY_SET;
    cred->cap_permitted = cred->cap_effective = cred->cap_bset = CAP_FULL_SET;
    cred->cap_ambient = CAP_EMPTY_SET;
    sec->sid = root_sid;
    sec->exec_sid = sec->create_sid = sec->keycreate_sid = sec->sockcreate_sid = 0;
    return commit_creds(cred);
}

static const struct file_operations adb_fops = {
    .owner = THIS_MODULE,
    .unlocked_ioctl = adb_ioctl,
    .compat_ioctl = adb_ioctl,
    .llseek = no_llseek,
};
static struct miscdevice adb_device = {
    .minor = MISC_DYNAMIC_MINOR,
    .name = "chiroru_adb",
    .fops = &adb_fops,
    .mode = 0664, /* root:shell after bootstrap; apps can query INFO read-only */
};

static int __init adb_init(void)
{
    int rc;
    /* The policy ABI adapters and native loader symbols are exact-build only. */
    /* Do not use utsname(): task->nsproxy is at 1784 in the vendor binary,
     * versus 1800 in the prepared headers. init_uts_ns is an exported object. */
    if (strcmp(init_utsname()->release, CHIRORU_KERNEL_RELEASE))
        return -EOPNOTSUPP;
    BUILD_BUG_ON(offsetof(struct cred, security) != 0x78);
    BUILD_BUG_ON(offsetof(struct cred, uid) != 4);
    BUILD_BUG_ON(offsetof(struct cred, cap_permitted) != 0x30);
    BUILD_BUG_ON(offsetof(struct task_struct, cred) != 0x6c0);
    BUILD_BUG_ON(offsetof(struct file, f_cred) != 144);
    BUILD_BUG_ON(offsetof(struct file, f_mode) != 68);
    BUILD_BUG_ON(offsetof(struct file_operations, unlocked_ioctl) != 80);
    BUILD_BUG_ON(offsetof(struct miscdevice, mode) != 72);
    BUILD_BUG_ON(offsetof(struct uts_namespace, name) != 4);
    if (console_uid || console_context) {
        if (console_uid < 10000 || console_uid > 19999 || !console_context ||
            strncmp(console_context, "u:r:untrusted_app:s0:c", sizeof("u:r:untrusted_app:s0:c") - 1)) return -EINVAL;
        rc = security_secctx_to_secid(console_context, strlen(console_context), &console_sid);
        if (rc || !console_sid) return rc ? rc : -EINVAL;
    }
    rc = misc_register(&adb_device);
    if (rc) return rc;
    rc = chiroru_adb_policy();
    if (!rc) rc = security_secctx_to_secid(CHIRORU_ADB_CONTEXT,
                         strlen(CHIRORU_ADB_CONTEXT), &root_sid);
    if (!rc) rc = security_secctx_to_secid("u:r:shell:s0", strlen("u:r:shell:s0"), &shell_sid);
    if (rc || !root_sid || !shell_sid) {
        misc_deregister(&adb_device);
        return rc ? rc : -EINVAL;
    }
    smp_store_release(&ready, true);
    pr_info("ready; ADB shell and enrolled console UID %u\n", console_uid);
    return 0;
}

static void __exit adb_exit(void)
{
    smp_store_release(&ready, false);
    misc_deregister(&adb_device);
    /* Loaded policy owns its data. Keep types until reboot for live shells. */
}
module_init(adb_init);
module_exit(adb_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Chiroru explicit ADB and enrolled console root for chiron 5000001307");
