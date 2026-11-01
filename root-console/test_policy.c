/* SPDX-License-Identifier: GPL-2.0-only */
#include "policy.c"

int main(int argc, char **argv) {
    assert(argc == 2);
    hashtab_cache_init(); avtab_cache_init(); ebitmap_cache_init();
    FILE *file = fopen(argv[1], "rb");
    assert(file && !fseek(file, 0, SEEK_END));
    size_t length = ftell(file);
    rewind(file);
    void *input = malloc(length);
    assert(input && fread(input, 1, length, file) == length);
    fclose(file);
    void *output = NULL, *again = NULL;
    size_t size = 0, again_size = 0;
    host_allocations = 0;
    assert(!console_policy_transform(input, length, &output, &size));
    long allocations = host_allocations;
    assert(!console_policy_transform(output, size, &again, &again_size));
    assert(size == again_size && !memcmp(output, again, size));
    free(again); again = NULL;
    assert(console_policy_transform(input, length - 16, &again, &again_size) < 0 && !again);
    /* Allocation failures leave the input image intact and produce no image. */
    const long faults[] = {0, 1, 17, 200, 1000, allocations / 2, allocations - 1};
    for (unsigned i = 0; i < sizeof faults / sizeof faults[0]; ++i) {
        host_allocations = 0; host_fail_after = faults[i];
        assert(console_policy_transform(input, length, &again, &again_size) < 0 && !again);
    }
    host_fail_after = -1;
    assert(!console_policy_transform(input, length, &again, &again_size));
    assert(size == again_size && !memcmp(output, again, size));
    free(again); free(output); free(input);
    puts("PASS: enforcing userspace console policy, roundtrip/idempotence, truncation and allocation failures");
    return 0;
}
