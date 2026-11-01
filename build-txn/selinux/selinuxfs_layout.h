// SPDX-License-Identifier: GPL-2.0-only
/* Generated private vendor layout, not a stable kernel API.
 * selinuxfs.c sha256 559f75fa3bb30a336fb48fb47f681bd3e6fb5970bebcb35ffd4af4c2ae0e5592
 */
struct ksu_selinux_fs_info {
	struct dentry *bool_dir;
	unsigned int bool_num;
	char **bool_pending_names;
	unsigned int *bool_pending_values;
	struct dentry *class_dir;
	unsigned long last_class_ino;
	bool policy_opened;
	struct dentry *policycap_dir;
	struct mutex mutex;
	unsigned long last_ino;
	struct selinux_state *state;
	struct super_block *sb;
};
