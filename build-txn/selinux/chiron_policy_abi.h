/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef KSU_CHIRON_POLICY_ABI_H
#define KSU_CHIRON_POLICY_ABI_H

/* chiron 5000001307, reviewed against chiron-vmlinux.elf:
 * sel_fill_super stores fsi at sb+968, sel_write_load reads the same slot.
 * The prepared upstream 5.4 headers put it at 952. Do not use that member.
 * This adapter is deliberately specific to this vendor binary; the remaining
 * offsets used by the transaction must still match the checked headers.
 */
#define KSU_CHIRON_SB_FS_INFO_OFFSET 968

static struct ksu_selinux_fs_info *ksu_selinux_fs_private(struct super_block *sb)
{
#ifdef __KERNEL__
    BUILD_BUG_ON(offsetof(struct dentry, d_sb) != 104);
    BUILD_BUG_ON(offsetof(struct super_block, s_type) != 40);
    BUILD_BUG_ON(offsetof(struct super_block, s_magic) != 96);
    BUILD_BUG_ON(offsetof(struct ksu_selinux_fs_info, mutex) != 64);
    BUILD_BUG_ON(offsetof(struct ksu_selinux_fs_info, state) != 104);
    BUILD_BUG_ON(offsetof(struct ksu_selinux_fs_info, sb) != 112);
    BUILD_BUG_ON(sizeof(struct ksu_selinux_fs_info) != 120);
    BUILD_BUG_ON(offsetof(struct selinux_state, ss) != 24);
    BUILD_BUG_ON(offsetof(struct selinux_ss, policydb) != 8);
    BUILD_BUG_ON(offsetof(struct selinux_ss, policy_rwlock) != 496);
    BUILD_BUG_ON(sizeof(struct policydb) != 488);
    BUILD_BUG_ON(sizeof(struct context) != 80);
    BUILD_BUG_ON(offsetof(struct context, hash) != 72);
    BUILD_BUG_ON(offsetof(struct ocontext, context) != 40);
    BUILD_BUG_ON(offsetof(struct ocontext, sid) != 200);
    BUILD_BUG_ON(offsetof(struct ocontext, next) != 208);
    return READ_ONCE(*(struct ksu_selinux_fs_info **)
                     ((char *)sb + KSU_CHIRON_SB_FS_INFO_OFFSET));
#else
    /* Host tests model VFS, not the vendor kernel's object layout. */
    return sb->s_fs_info;
#endif
}
#endif
