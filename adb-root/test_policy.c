/* SPDX-License-Identifier: GPL-2.0-only */
#define main original_transaction_tests_main
#include "../tools/policy_host/test.c"
#undef main
#include "module/policy.c"

int main(int argc, char **argv)
{
    assert(argc == 2);
    host_quiet = true;
    hashtab_cache_init(); avtab_cache_init(); ebitmap_cache_init();
    FILE *file = fopen(argv[1], "rb");
    assert(file && !fseek(file, 0, SEEK_END));
    long length = ftell(file);
    assert(length > 0 && !fseek(file, 0, SEEK_SET));
    void *data = malloc(length);
    assert(data && fread(data, 1, length, file) == (size_t)length);
    fclose(file);
    struct policy_file input = {data, length};
    assert(!policydb_read(&ss.policydb, &input) && !input.len);
    free(data);
    assert(!hashtab_search(ss.policydb.p_types.table, "ksu"));
    struct type_datum *existing_root = hashtab_search(ss.policydb.p_types.table, "chiroru_adb");
    if (existing_root)
        assert(!ebitmap_get_bit(&ss.policydb.permissive_map, existing_root->value));
    assert(!pthread_rwlock_init(&ss.policy_rwlock, NULL));
    assert(!pthread_mutex_init(&fsi.mutex.value, NULL));
    fsi.sb = &sb; fsi.state = &selinux_state; sb.s_fs_info = &fsi;
    selinux_state.initialized = true; selinux_state.ss = &ss;
    assert(!chiroru_adb_policy());
    struct type_datum *root = hashtab_search(ss.policydb.p_types.table, "chiroru_adb");
    assert(root && !ebitmap_get_bit(&ss.policydb.permissive_map, root->value));
    assert(hashtab_search(ss.policydb.p_types.table, "chiroru_adb_device"));
    assert(!hashtab_search(ss.policydb.p_types.table, "ksu"));
    test_writer();
    size_t first_len, second_len;
    void *first = serialize(&ss.policydb, &first_len);
    assert(!chiroru_adb_policy());
    void *second = serialize(&ss.policydb, &second_len);
    assert(first_len == second_len && !memcmp(first, second, first_len));
    free(first); free(second);
    policydb_destroy(&ss.policydb);
    pthread_rwlock_destroy(&ss.policy_rwlock);
    pthread_mutex_destroy(&fsi.mutex.value);
    puts("PASS: stock policy without KernelSU; enforcing ADB domain; exact serialization and idempotence");
    return 0;
}
