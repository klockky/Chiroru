#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include "uapi/selinux.h"
#include "klog.h"
#include "selinux.h"
#include "sepolicy.h"
#include "ss/policydb.h"
#include "policy_transaction.h"
#include "compat/kernel_compat.h"

#define ALL NULL
#define REQUIRE(rule) do { \
    if (!(rule)) { \
        pr_err("sepol: initial rule failed: %s\n", #rule); \
        return -EINVAL; \
    } \
} while (0)

static int apply_kernelsu_rules_fn(struct policydb *db, void *unused)
{
    (void)unused;
    REQUIRE(ksu_type(db, KERNEL_SU_DOMAIN, "domain"));
    REQUIRE(ksu_permissive(db, KERNEL_SU_DOMAIN));
    REQUIRE(ksu_typeattribute(db, KERNEL_SU_DOMAIN, "mlstrustedsubject"));
    REQUIRE(ksu_typeattribute(db, KERNEL_SU_DOMAIN, "netdomain"));
    REQUIRE(ksu_typeattribute(db, KERNEL_SU_DOMAIN, "bluetoothdomain"));
    REQUIRE(ksu_type(db, KERNEL_SU_FILE, "file_type"));
    REQUIRE(ksu_typeattribute(db, KERNEL_SU_FILE, "mlstrustedobject"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_FILE, ALL, ALL));
    REQUIRE(ksu_allow(db, KERNEL_SU_DOMAIN, ALL, ALL, ALL));
    if (db->policyvers >= POLICYDB_VERSION_XPERMS_IOCTL) {
        REQUIRE(ksu_allowxperm(db, KERNEL_SU_DOMAIN, ALL, "blk_file", ALL));
        REQUIRE(ksu_allowxperm(db, KERNEL_SU_DOMAIN, ALL, "fifo_file", ALL));
        REQUIRE(ksu_allowxperm(db, KERNEL_SU_DOMAIN, ALL, "chr_file", ALL));
        REQUIRE(ksu_allowxperm(db, KERNEL_SU_DOMAIN, ALL, "file", ALL));
    }

    REQUIRE(ksu_allow(db, "kernel", "adb_data_file", "dir", ALL));
    REQUIRE(ksu_allow(db, "kernel", "adb_data_file", "file", ALL));
    REQUIRE(ksu_allow(db, "kernel", "apk_data_file", "file", "open"));
    REQUIRE(ksu_allow(db, "kernel", "apk_data_file", "dir", "open"));
    REQUIRE(ksu_allow(db, "kernel", "apk_data_file", "dir", "read"));
    REQUIRE(ksu_allow(db, "kernel", "apk_data_file", "dir", "search"));
    REQUIRE(ksu_allow(db, "kernel", "shell_data_file", "file", ALL));
    REQUIRE(ksu_allow(db, "kernel", "kernel", "capability", "dac_override"));
    REQUIRE(ksu_allow(db, "kernel", "packages_list_file", "file", ALL));
    REQUIRE(ksu_allow(db, "kernel", "packages_list_file", "dir", ALL));
    REQUIRE(ksu_allow(db, "kernel", "system_data_file", "file", ALL));
    REQUIRE(ksu_allow(db, "kernel", "system_data_file", "dir", ALL));
    REQUIRE(ksu_allow(db, "init", "adb_data_file", "file", ALL));
    REQUIRE(ksu_allow(db, "init", "adb_data_file", "dir", ALL));
    REQUIRE(ksu_allow(db, "init", KERNEL_SU_DOMAIN, ALL, ALL));
    REQUIRE(ksu_allow(db, "zygote", "adb_data_file", "dir", "search"));

    REQUIRE(ksu_allow(db, "servicemanager", KERNEL_SU_DOMAIN, "dir", "search"));
    REQUIRE(ksu_allow(db, "servicemanager", KERNEL_SU_DOMAIN, "dir", "read"));
    REQUIRE(ksu_allow(db, "servicemanager", KERNEL_SU_DOMAIN, "file", "open"));
    REQUIRE(ksu_allow(db, "servicemanager", KERNEL_SU_DOMAIN, "file", "read"));
    REQUIRE(ksu_allow(db, "servicemanager", KERNEL_SU_DOMAIN, "process", "getattr"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "process", "sigchld"));
    REQUIRE(ksu_allow(db, "logd", KERNEL_SU_DOMAIN, "dir", "search"));
    REQUIRE(ksu_allow(db, "logd", KERNEL_SU_DOMAIN, "file", "read"));
    REQUIRE(ksu_allow(db, "logd", KERNEL_SU_DOMAIN, "file", "open"));
    REQUIRE(ksu_allow(db, "logd", KERNEL_SU_DOMAIN, "file", "getattr"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "fd", "use"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "fifo_file", "write"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "fifo_file", "read"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "fifo_file", "open"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "fifo_file", "getattr"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "unix_stream_socket", "read"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "unix_stream_socket", "write"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "unix_stream_socket", "connectto"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "unix_stream_socket", "getopt"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "unix_stream_socket", "getattr"));

    /* Absent on chiron; skip only the known optional class, not allocation
     * failures or unknown permissions in a class which does exist.
     */
    if (hashtab_search(db->p_classes.table, "memfd_file")) {
        REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "memfd_file", "execute"));
        REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "memfd_file", "getattr"));
        REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "memfd_file", "map"));
        REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "memfd_file", "read"));
        REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "memfd_file", "write"));
    }
    REQUIRE(ksu_allow(db, "hwservicemanager", KERNEL_SU_DOMAIN, "dir", "search"));
    REQUIRE(ksu_allow(db, "hwservicemanager", KERNEL_SU_DOMAIN, "file", "read"));
    REQUIRE(ksu_allow(db, "hwservicemanager", KERNEL_SU_DOMAIN, "file", "open"));
    REQUIRE(ksu_allow(db, "hwservicemanager", KERNEL_SU_DOMAIN, "process", "getattr"));
    REQUIRE(ksu_allow(db, "kernel", ALL, "file", "read"));
    REQUIRE(ksu_allow(db, "kernel", ALL, "file", "write"));
    REQUIRE(ksu_allow(db, "domain", KERNEL_SU_DOMAIN, "binder", ALL));
    REQUIRE(ksu_allow(db, "system_server", KERNEL_SU_DOMAIN, "process", "getpgid"));
    REQUIRE(ksu_allow(db, "system_server", KERNEL_SU_DOMAIN, "process", "sigkill"));
    return 0;
}
#undef REQUIRE

int apply_kernelsu_rules(void)
{
    int ret = ksu_policy_transaction(apply_kernelsu_rules_fn, NULL);
    if (ret)
        pr_err("sepol: initial transaction aborted: %d\n", ret);
    return ret;
}

#define KSU_SEPOLICY_MAX_BATCH_SIZE (8U * 1024U * 1024U)
#define KSU_SEPOLICY_MAX_ARGS 5

struct sepol_data { u32 cmd; u32 subcmd; };
struct sepol_batch_cursor { const u8 *cur; const u8 *end; };
struct sepol_batch_args {
    const u8 *payload;
    size_t data_len;
    int success_cmd_count;
};

static int sepol_read_string(struct sepol_batch_cursor *cursor, const char **out)
{
    u32 len;
    const char *str;
    if ((size_t)(cursor->end - cursor->cur) < sizeof(len))
        return -EINVAL;
    memcpy(&len, cursor->cur, sizeof(len));
    cursor->cur += sizeof(len);
    if (len >= (size_t)(cursor->end - cursor->cur))
        return -EINVAL;
    str = (const char *)cursor->cur;
    if (memchr(str, '\0', len) || str[len] != '\0')
        return -EINVAL;
    cursor->cur += len + 1;
    *out = len ? str : ALL;
    return 0;
}

static int sepol_expected_argc(u32 cmd)
{
    switch (cmd) {
    case KSU_SEPOLICY_CMD_NORMAL_PERM: return 4;
    case KSU_SEPOLICY_CMD_XPERM: return 5;
    case KSU_SEPOLICY_CMD_TYPE_STATE: return 1;
    case KSU_SEPOLICY_CMD_TYPE:
    case KSU_SEPOLICY_CMD_TYPE_ATTR: return 2;
    case KSU_SEPOLICY_CMD_ATTR: return 1;
    case KSU_SEPOLICY_CMD_TYPE_TRANSITION: return 5;
    case KSU_SEPOLICY_CMD_TYPE_CHANGE: return 4;
    case KSU_SEPOLICY_CMD_GENFSCON: return 3;
    default: return -EINVAL;
    }
}

static int apply_one_sepolicy_cmd(struct policydb *db,
                                const struct sepol_data *header,
                                const char **args)
{
    bool success = false;
    switch (header->cmd) {
    case KSU_SEPOLICY_CMD_NORMAL_PERM:
        switch (header->subcmd) {
        case KSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW:
            success = ksu_allow(db, args[0], args[1], args[2], args[3]); break;
        case KSU_SEPOLICY_SUBCMD_NORMAL_PERM_DENY:
            success = ksu_deny(db, args[0], args[1], args[2], args[3]); break;
        case KSU_SEPOLICY_SUBCMD_NORMAL_PERM_AUDITALLOW:
            success = ksu_auditallow(db, args[0], args[1], args[2], args[3]); break;
        case KSU_SEPOLICY_SUBCMD_NORMAL_PERM_DONTAUDIT:
            success = ksu_dontaudit(db, args[0], args[1], args[2], args[3]); break;
        }
        break;
    case KSU_SEPOLICY_CMD_XPERM:
        if (!args[3] || strcmp(args[3], "ioctl") || !args[4])
            return -EINVAL;
        switch (header->subcmd) {
        case KSU_SEPOLICY_SUBCMD_XPERM_ALLOW:
            success = ksu_allowxperm(db, args[0], args[1], args[2], args[4]); break;
        case KSU_SEPOLICY_SUBCMD_XPERM_AUDITALLOW:
            success = ksu_auditallowxperm(db, args[0], args[1], args[2], args[4]); break;
        case KSU_SEPOLICY_SUBCMD_XPERM_DONTAUDIT:
            success = ksu_dontauditxperm(db, args[0], args[1], args[2], args[4]); break;
        }
        break;
    case KSU_SEPOLICY_CMD_TYPE_STATE:
        if (!args[0]) return -EINVAL;
        if (header->subcmd == KSU_SEPOLICY_SUBCMD_TYPE_STATE_PERMISSIVE)
            success = ksu_permissive(db, args[0]);
        else if (header->subcmd == KSU_SEPOLICY_SUBCMD_TYPE_STATE_ENFORCE)
            success = ksu_enforce(db, args[0]);
        break;
    case KSU_SEPOLICY_CMD_TYPE:
    case KSU_SEPOLICY_CMD_TYPE_ATTR:
        if (!args[0] || !args[1]) return -EINVAL;
        success = header->cmd == KSU_SEPOLICY_CMD_TYPE
            ? ksu_type(db, args[0], args[1])
            : ksu_typeattribute(db, args[0], args[1]);
        break;
    case KSU_SEPOLICY_CMD_ATTR:
        if (!args[0]) return -EINVAL;
        success = ksu_attribute(db, args[0]);
        break;
    case KSU_SEPOLICY_CMD_TYPE_TRANSITION:
        if (!args[0] || !args[1] || !args[2] || !args[3]) return -EINVAL;
        success = ksu_type_transition(db, args[0], args[1], args[2], args[3], args[4]);
        break;
    case KSU_SEPOLICY_CMD_TYPE_CHANGE:
        if (!args[0] || !args[1] || !args[2] || !args[3]) return -EINVAL;
        if (header->subcmd == KSU_SEPOLICY_SUBCMD_TYPE_CHANGE_CHANGE)
            success = ksu_type_change(db, args[0], args[1], args[2], args[3]);
        else if (header->subcmd == KSU_SEPOLICY_SUBCMD_TYPE_CHANGE_MEMBER)
            success = ksu_type_member(db, args[0], args[1], args[2], args[3]);
        break;
    case KSU_SEPOLICY_CMD_GENFSCON:
        return -EOPNOTSUPP; /* the legacy helper was a no-op returning false */
    default:
        return -EINVAL;
    }
    return success ? 0 : -EINVAL;
}

static int ksu_sepol_mutator(struct policydb *db, void *arg)
{
    struct sepol_batch_args *batch = arg;
    struct sepol_batch_cursor cursor = {
        .cur = batch->payload, .end = batch->payload + batch->data_len,
    };
    int count = 0;
    while (cursor.cur < cursor.end) {
        struct sepol_data header;
        const char *args[KSU_SEPOLICY_MAX_ARGS] = { 0 };
        int argc, i, ret;
        if ((size_t)(cursor.end - cursor.cur) < sizeof(header))
            return -EINVAL;
        memcpy(&header, cursor.cur, sizeof(header));
        cursor.cur += sizeof(header);
        argc = sepol_expected_argc(header.cmd);
        if (argc < 0 || argc > KSU_SEPOLICY_MAX_ARGS)
            return -EINVAL;
        for (i = 0; i < argc; i++) {
            ret = sepol_read_string(&cursor, &args[i]);
            if (ret)
                return ret;
        }
        ret = apply_one_sepolicy_cmd(db, &header, args);
        if (ret) {
            pr_err("sepol: abort batch at cmd #%d, cmd=%u subcmd=%u\n",
                   count, header.cmd, header.subcmd);
            return ret;
        }
        count++;
    }
    batch->success_cmd_count = count;
    return 0;
}

int handle_sepolicy(void __user *user_data, u64 data_len)
{
    struct sepol_batch_args batch = { 0 };
    u8 *payload;
    int ret;
    if (!user_data || !data_len)
        return -EINVAL;
    if (data_len > KSU_SEPOLICY_MAX_BATCH_SIZE)
        return -E2BIG;
    payload = kvmalloc((size_t)data_len, GFP_KERNEL);
    if (!payload)
        return -ENOMEM;
    if (ksu_copy_from_user(payload, user_data, (size_t)data_len)) {
        ret = -EFAULT;
        goto out;
    }
    batch.payload = payload;
    batch.data_len = (size_t)data_len;
    ret = ksu_policy_transaction(ksu_sepol_mutator, &batch);
    if (!ret)
        ret = batch.success_cmd_count;
out:
    kvfree(payload);
    return ret;
}
