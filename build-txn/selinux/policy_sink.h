/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef KSU_POLICY_SINK_H
#define KSU_POLICY_SINK_H

/* Used by every generated writer, including avtab/bitmap/conditional paths.
 * data == NULL counts bytes; capacity is still enforced in counting mode.
 * The caller supplies an immutable policy during each pass.
 */
struct ksu_policy_sink {
    char *data;
    size_t capacity;
    size_t used;
};

static int ksu_policy_put(const void *data, size_t size, size_t count, void *ptr)
{
    struct ksu_policy_sink *sink = ptr;
    size_t bytes;

    if (count && size > (size_t)-1 / count)
        return -EOVERFLOW;
    bytes = size * count;
    if (sink->used > sink->capacity || bytes > sink->capacity - sink->used)
        return -E2BIG;
    if (sink->data && bytes)
        memcpy(sink->data + sink->used, data, bytes);
    sink->used += bytes;
    return 0;
}
#endif
