#include "compat.h"
#include "ss/policydb.h"
#include "ss/services.h"
#include "selinux/policy_writer.h"
#include "selinux/policy_sink.h"
#include "selinux/selinuxfs_layout.h"
#include "../../build-txn/selinux/rules.c"

_Thread_local long host_fail_after = -1;
_Thread_local long host_allocations;
_Thread_local bool host_quiet;
struct selinux_state selinux_state;
static struct selinux_ss ss;
static struct file_system_type fs_type = { .name = "selinuxfs" };
static struct super_block sb = { .s_magic = SELINUX_MAGIC, .s_type = &fs_type };
static struct dentry dentry = { .d_sb = &sb };
static struct ksu_selinux_fs_info fsi;
static int path_error;

void host_log(const char *fmt, ...)
{
    if (!host_quiet) {
        va_list args;
        va_start(args, fmt);
        vfprintf(stderr, fmt, args);
        va_end(args);
    }
}

void *host_alloc(size_t size, bool zero)
{
    long index = host_allocations++;
    if (index == host_fail_after)
        return NULL;
    return zero ? calloc(1, size ? size : 1) : malloc(size ? size : 1);
}

int kern_path(const char *name, unsigned flags, struct path *path)
{
    assert(!strcmp(name, "/sys/fs/selinux") && flags == LOOKUP_FOLLOW);
    if (path_error) return path_error;
    path->dentry = &dentry;
    return 0;
}

void path_put(struct path *path) { assert(path->dentry == &dentry); }

/* Native loader MODEL: real parser and policy swap, but no real SID table,
 * LSM hooks or AVC. Those still require kernel runtime validation.
 */
int security_load_policy(struct selinux_state *state, void *data, size_t length)
{
    struct policydb next = { 0 }, old;
    struct policy_file input = { data, length };
    assert(state == &selinux_state);
    assert(pthread_mutex_trylock(&fsi.mutex.value) == EBUSY);
    int ret = policydb_read(&next, &input);
    if (ret) return ret;
    assert(!input.len);
    /* The real loader validates initial SIDs before swapping the policy. */
    for (struct ocontext *c = next.ocontexts[OCON_ISID]; c; c = c->next) {
        if (!c->context[0].user || !c->sid[0] || c->sid[0] > SECINITSID_NUM) {
            policydb_destroy(&next);
            return -EINVAL;
        }
    }
    next.len = length;
    assert(!pthread_rwlock_wrlock(&ss.policy_rwlock));
    old = ss.policydb;
    ss.policydb = next;
    ss.latest_granting++;
    assert(!pthread_rwlock_unlock(&ss.policy_rwlock));
    policydb_destroy(&old);
    return 0;
}

static void *serialize(struct policydb *db, size_t *len)
{
    assert(!ksu_policy_measure(db, len));
    void *data = malloc(*len);
    assert(data && !ksu_policy_serialize(db, data, *len));
    return data;
}

static void test_writer(void)
{
    size_t len;
    void *checked = serialize(&ss.policydb, &len);
    void *native = malloc(len);
    assert(native);
    struct policy_file output = { native, len };
    assert(!policydb_write(&ss.policydb, &output) && !output.len);
    assert(!memcmp(native, checked, len));
    memset(native, 0xa5, len);
    assert(ksu_policy_serialize(&ss.policydb, native, len - 1) < 0);
    assert(((u8 *)native)[len - 1] == 0xa5);
    struct ksu_policy_sink sink = { .capacity = SIZE_MAX };
    assert(ksu_policy_put(NULL, SIZE_MAX, 2, &sink) == -EOVERFLOW);
    assert(!sink.used);
    printf("writer: checked/native byte equality, exact length %zu, short buffer and overflow rejected\n", len);
    free(native);
    free(checked);
}

struct fault_args { long fail; int kind; long allocations; };
static int fault_mutator(struct policydb *db, void *arg)
{
    struct fault_args *fault = arg;
    bool ok;
    host_allocations = 0;
    host_fail_after = fault->fail;
    switch (fault->kind) {
    case 0: ok = ksu_type(db, "txn_fault_type", "domain"); break;
    case 1: ok = ksu_allowxperm(db, "shell", "ksu", "file", "0x1234"); break;
    case 2: ok = ksu_type_transition(db, "shell", "ksu", "file", "ksu_file", NULL); break;
    case 3: ok = ksu_type_transition(db, "shell", "ksu", "file", "ksu_file", "txn-test"); break;
    case 4: ok = ksu_attribute(db, "txn_fault_attr"); break;
    case 5: ok = ksu_permissive(db, "kernel"); break;
    default: abort();
    }
    fault->allocations = host_allocations;
    host_fail_after = -1;
    return ok ? 0 : -ENOMEM;
}

static void test_faults(void)
{
    size_t before_len, after_len;
    void *before = serialize(&ss.policydb, &before_len);
    unsigned failures = 0;
    for (int kind = 0; kind < 6; kind++) {
        /* Discover allocation count on a disposable private copy. */
        struct policydb copy = { 0 };
        struct policy_file input = { before, before_len };
        struct fault_args probe = { .fail = -1, .kind = kind };
        assert(!policydb_read(&copy, &input));
        assert(!fault_mutator(&copy, &probe));
        policydb_destroy(&copy);
        assert(probe.allocations > 0);
        for (long fail = 0; fail < probe.allocations; fail++) {
            struct fault_args args = { .fail = fail, .kind = kind };
            u32 seq = ss.latest_granting;
            assert(ksu_policy_transaction(fault_mutator, &args) < 0);
            assert(ss.latest_granting == seq);
            void *after = serialize(&ss.policydb, &after_len);
            assert(before_len == after_len && !memcmp(before, after, before_len));
            free(after);
            failures++;
        }
    }
    free(before);
    printf("faults: %u allocation sites rejected, live policy unchanged, private cleanup sanitized\n", failures);
}

static size_t add_command(u8 *data, u32 cmd, u32 subcmd, const char **args, int argc)
{
    struct sepol_data header = { cmd, subcmd };
    size_t n = sizeof(header);
    memcpy(data, &header, n);
    for (int i = 0; i < argc; i++) {
        u32 len = strlen(args[i]);
        memcpy(data + n, &len, sizeof(len));
        n += sizeof(len);
        memcpy(data + n, args[i], len + 1);
        n += len + 1;
    }
    return n;
}

static void test_batches(void)
{
    u8 data[512];
    const char *bad[] = { "su", "ksu", "process", "read" };
    const char *good[] = { "su", "ksu", "file", "read" };
    const char *type[] = { "txn_batch_type", "domain" };
    size_t n = add_command(data, KSU_SEPOLICY_CMD_TYPE, 0, type, 2);
    n += add_command(data + n, KSU_SEPOLICY_CMD_NORMAL_PERM,
                     KSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW, bad, 4);
    n += add_command(data + n, KSU_SEPOLICY_CMD_NORMAL_PERM,
                     KSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW, good, 4);
    u32 seq = ss.latest_granting;
    assert(handle_sepolicy(data, n) < 0);
    assert(ss.latest_granting == seq);
    assert(!hashtab_search(ss.policydb.p_types.table, "txn_batch_type"));
    n = add_command(data, KSU_SEPOLICY_CMD_NORMAL_PERM,
                    KSU_SEPOLICY_SUBCMD_NORMAL_PERM_ALLOW, good, 4);
    assert(handle_sepolicy(data, n - 1) < 0);
    assert(ss.latest_granting == seq);
    assert(handle_sepolicy(data, n) == 1);
    assert(handle_sepolicy(data, n) == 1);
    assert(ss.latest_granting == seq + 2);
    assert(handle_sepolicy(data, KSU_SEPOLICY_MAX_BATCH_SIZE + 1ULL) == -E2BIG);
    assert(handle_sepolicy(NULL, n) == -EINVAL);
    path_error = -ENOENT;
    seq = ss.latest_granting;
    assert(handle_sepolicy(data, n) == -ENOENT);
    assert(apply_kernelsu_rules() == -ENOENT);
    assert(ss.latest_granting == seq);
    path_error = 0;
    puts("batches: success/error/success rolled back, malformed input rejected, repeat apply and unavailable backend checked");
}

static int append_type(struct policydb *db, void *name)
{
    return ksu_type(db, name, "domain") ? 0 : -EINVAL;
}

static int attribute_mutator(struct policydb *db, void *unused)
{
    (void)unused;
    return ksu_attribute(db, "txn_new_attr") &&
           ksu_type(db, "txn_new_type", "txn_new_attr") ? 0 : -EINVAL;
}

static void *writer_thread(void *arg)
{
    host_quiet = true;
    for (unsigned i = 0; i < 8; i++) {
        char name[40];
        snprintf(name, sizeof(name), "txn_thread_%lu_%u", (unsigned long)arg, i);
        assert(!ksu_policy_transaction(append_type, name));
    }
    return NULL;
}

static void *reader_thread(void *arg)
{
    (void)arg;
    host_quiet = true;
    for (unsigned i = 0; i < 24; i++) {
        /* Native sel_open_policy's outer lock spans size -> alloc -> write. */
        mutex_lock(&fsi.mutex);
        size_t len = ss.policydb.len;
        void *data = malloc(len);
        assert(data);
        struct policy_file fp = { data, len };
        read_lock(&ss.policy_rwlock);
        int ret = policydb_write(&ss.policydb, &fp);
        if (ret || fp.len)
            fprintf(stderr, "native model read: allocated=%zu remaining=%zu ret=%d\n", len, fp.len, ret);
        assert(!ret && !fp.len);
        read_unlock(&ss.policy_rwlock);
        free(data);
        mutex_unlock(&fsi.mutex);
    }
    return NULL;
}

static void test_concurrency(void)
{
    pthread_t threads[3];
    u32 count = ss.policydb.p_types.nprim;
    assert(!pthread_create(&threads[0], NULL, writer_thread, (void *)0));
    assert(!pthread_create(&threads[1], NULL, writer_thread, (void *)1));
    assert(!pthread_create(&threads[2], NULL, reader_thread, NULL));
    for (unsigned i = 0; i < 3; i++) assert(!pthread_join(threads[i], NULL));
    assert(ss.policydb.p_types.nprim == count + 16);
    for (unsigned t = 0; t < 2; t++) {
        for (unsigned i = 0; i < 8; i++) {
            char name[40];
            snprintf(name, sizeof(name), "txn_thread_%u_%u", t, i);
            assert(hashtab_search(ss.policydb.p_types.table, name));
        }
    }
    puts("concurrency model: 16 appends retained, native-style readers serialized");
}

static int no_change(struct policydb *db, void *arg)
{
    (void)db; (void)arg;
    return 0;
}

static void test_transaction_failures(void)
{
    /* Sample both parsing passes and their allocation/cleanup paths. Mutation
     * allocation sites are exhausted separately by test_faults().
     */
    host_allocations = 0;
    assert(!ksu_policy_transaction(no_change, NULL));
    long total = host_allocations;
    long sites[] = { 0, 1, 2, 3, 10, 100, total / 4, total / 2,
                     total * 3 / 4, total - 2, total - 1 };
    size_t before_len;
    void *before = serialize(&ss.policydb, &before_len);
    for (size_t i = 0; i < ARRAY_SIZE(sites); i++) {
        u32 seq = ss.latest_granting;
        host_allocations = 0;
        host_fail_after = sites[i];
        int ret = ksu_policy_transaction(no_change, NULL);
        host_fail_after = -1;
        assert(ret < 0 && ss.latest_granting == seq);
        size_t after_len;
        void *after = serialize(&ss.policydb, &after_len);
        assert(before_len == after_len && !memcmp(before, after, before_len));
        free(after);
    }
    free(before);
    printf("transaction allocation failures: %zu sampled sites across %ld allocations, no publication\n",
           ARRAY_SIZE(sites), total);
}

/* Synthetic fixture, NOT a reconstruction of the historical device policy:
 * rename the existing KSU symbols so initialization must append new types and
 * create new wildcard rules. Numeric references to the old types stay valid.
 */
static void rename_type(struct policydb *db, const char *old, const char *name)
{
    struct hashtab *tab = db->p_types.table;
    for (u32 i = 0; i < tab->size; i++) {
        struct hashtab_node **link = &tab->htable[i];
        while (*link) {
            struct hashtab_node *node = *link;
            if (!strcmp(node->key, old)) {
                struct type_datum *type = node->datum;
                char *key = strdup(name);
                assert(key);
                *link = node->next;
                tab->nel--;
                free(node->key);
                free(node);
                assert(!hashtab_insert(tab, key, type));
                db->sym_val_to_name[SYM_TYPES][type->value - 1] = key;
                return;
            }
            link = &node->next;
        }
    }
    abort();
}

static int initial_fault_mutator(struct policydb *db, void *arg)
{
    struct fault_args *fault = arg;
    host_allocations = 0;
    host_fail_after = fault->fail;
    int ret = apply_kernelsu_rules_fn(db, NULL);
    fault->allocations = host_allocations;
    host_fail_after = -1;
    return ret;
}

static void test_initial_failures(void)
{
    size_t original_len;
    void *original = serialize(&ss.policydb, &original_len);
    rename_type(&ss.policydb, "ksu", "fixture_previous_ksu");
    rename_type(&ss.policydb, "ksu_file", "fixture_previous_ksu_file");
    size_t len;
    void *fixture = serialize(&ss.policydb, &len);
    ss.policydb.len = len;
    struct policydb probe_db = { 0 };
    struct policy_file input = { fixture, len };
    struct fault_args probe = { .fail = -1 };
    assert(!policydb_read(&probe_db, &input));
    assert(!initial_fault_mutator(&probe_db, &probe));
    policydb_destroy(&probe_db);
    long total = probe.allocations;
    long sites[] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                     total / 4, total / 2, total * 3 / 4, total - 2, total - 1 };
    for (size_t i = 0; i < ARRAY_SIZE(sites); i++) {
        struct fault_args fault = { .fail = sites[i] };
        u32 seq = ss.latest_granting;
        assert(ksu_policy_transaction(initial_fault_mutator, &fault) < 0);
        assert(ss.latest_granting == seq);
        size_t after_len;
        void *after = serialize(&ss.policydb, &after_len);
        assert(len == after_len && !memcmp(fixture, after, len));
        free(after);
    }
    assert(!apply_kernelsu_rules());
    assert(hashtab_search(ss.policydb.p_types.table, "ksu"));
    assert(hashtab_search(ss.policydb.p_types.table, "ksu_file"));
    test_writer();
    mutex_lock(&fsi.mutex);
    assert(!security_load_policy(&selinux_state, original, original_len));
    mutex_unlock(&fsi.mutex);
    free(original);
    free(fixture);
    printf("initial creation fixture: %zu sampled failure sites across %ld allocations, rollback and success verified\n",
           ARRAY_SIZE(sites), total);
}

static void test_xperm_and_filename(void)
{
    size_t len;
    void *data = serialize(&ss.policydb, &len);
    struct policydb db = { 0 };
    struct policy_file input = { data, len };
    assert(!policydb_read(&db, &input));
    assert(!ksu_allowxperm(&db, "shell", "ksu", "file", "invalid"));
    assert(!ksu_allowxperm(&db, "shell", "ksu", "file", "0x10000"));
    assert(!ksu_allowxperm(&db, "shell", "ksu", "file", "0x20-0x10"));
    assert(ksu_allowxperm(&db, "shell", "ksu", "file", "0x12ff-0x1300"));
    assert(ksu_allowxperm(&db, "shell", "ksu", "file", "0x1301"));
    struct type_datum *src = hashtab_search(db.p_types.table, "shell");
    struct type_datum *dst = hashtab_search(db.p_types.table, "ksu");
    struct type_datum *def = hashtab_search(db.p_types.table, "ksu_file");
    struct class_datum *cls = hashtab_search(db.p_classes.table, "file");
    struct avtab_key key = { src->value, dst->value, cls->value, AVTAB_XPERMS_ALLOWED };
    unsigned nodes = 0, bits = 0;
    for (struct avtab_node *node = avtab_search_node(&db.te_avtab, &key); node;
         node = avtab_search_node_next(node, key.specified)) {
        struct avtab_extended_perms *xp = node->datum.u.xperms;
        assert(xp->specified == AVTAB_XPERMS_IOCTLFUNCTION);
        assert(xp->driver == 0x12 || xp->driver == 0x13);
        for (unsigned i = 0; i < ARRAY_SIZE(xp->perms.p); i++)
            bits += __builtin_popcount(xp->perms.p[i]);
        nodes++;
    }
    assert(nodes == 2 && bits == 3);
    assert(ksu_type_transition(&db, "shell", "ksu", "file", "ksu_file", "txn-test"));
    struct filename_trans ft = { .stype = src->value, .ttype = dst->value,
                                .tclass = cls->value, .name = "txn-test" };
    struct filename_trans_datum *trans = hashtab_search(db.filename_trans, &ft);
    assert(trans && trans->otype == def->value);
    assert(ebitmap_get_bit(&db.filename_trans_ttypes, dst->value));
    assert(ksu_type_transition(&db, "shell", "ksu", "file", "shell", "txn-test"));
    assert(trans->otype == src->value);
    policydb_destroy(&db);
    free(data);
    puts("mutators: strict ioctl ranges, mask merging and 5.4 filename transition keys verified");
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    assert(sizeof(struct context) == 80 && offsetof(struct context, hash) == 72);
    assert(offsetof(struct ocontext, sid) == 200 && offsetof(struct ocontext, next) == 208);
    assert(argc == 2);
    host_quiet = true;
    hashtab_cache_init();
    avtab_cache_init();
    ebitmap_cache_init();
    FILE *file = fopen(argv[1], "rb");
    assert(file && !fseek(file, 0, SEEK_END));
    long length = ftell(file);
    assert(length > 0 && !fseek(file, 0, SEEK_SET));
    void *data = malloc(length);
    assert(data && fread(data, 1, length, file) == (size_t)length);
    fclose(file);
    struct policy_file input = { data, length };
    assert(!policydb_read(&ss.policydb, &input) && !input.len);
    ss.policydb.len = length;
    free(data);
    assert(!pthread_rwlock_init(&ss.policy_rwlock, NULL));
    assert(!pthread_mutex_init(&fsi.mutex.value, NULL));
    fsi.sb = &sb;
    fsi.state = &selinux_state;
    sb.s_fs_info = &fsi;
    selinux_state.initialized = true;
    selinux_state.ss = &ss;

    test_writer();
    test_faults();
    test_xperm_and_filename();
    test_transaction_failures();
    test_initial_failures();
    test_batches();
    assert(!ksu_policy_transaction(attribute_mutator, NULL));
    assert(!apply_kernelsu_rules());
    size_t first_len, second_len;
    void *first = serialize(&ss.policydb, &first_len);
    assert(!apply_kernelsu_rules());
    void *second = serialize(&ss.policydb, &second_len);
    assert(first_len == second_len && !memcmp(first, second, first_len));
    free(first); free(second);
    puts("initial rules: successful repeat is byte-idempotent on saved policy");
    test_concurrency();
    test_writer();
    policydb_destroy(&ss.policydb);
    assert(!pthread_rwlock_destroy(&ss.policy_rwlock));
    assert(!pthread_mutex_destroy(&fsi.mutex.value));
    puts("PASS (host algorithms/model only; not a live-kernel ABI/lockdep test)");
    return 0;
}
