#include <stdint.h>
#include <string.h>

#include "reclaim_scan.h"

/* arm64 leaf PTE bit0 = valid.  Attribute bits differ per mapping type;
 * entries of the same fault type inside one table share them, so
 * consistency across valid entries is the discriminator. */
#define RS_PTE_VALID 1u

void rs_analyze(const uint64_t *page, struct rs_result *out)
{
	int i, j;
	uint64_t first_pa = 0;
	uint32_t first_attr = 0;
	int seen_valid = 0;

	memset(out, 0, sizeof *out);
	out->idx_first = -1;
	out->idx_last = -1;
	out->attrs_consistent = 1;
	out->pas_plausible = 1;
	out->pas_distinct = 1;

	for (i = 0; i < RS_ENTRIES; i++) {
		uint64_t e = page[i];
		uint64_t pa;

		if (!(e & RS_PTE_VALID))
			continue;
		out->n_valid++;
		if (out->idx_first < 0)
			out->idx_first = i;
		out->idx_last = i;

		pa = e & RS_PA_MASK;
		if (pa == 0 || pa % 0x1000 || pa >= RS_PA_MAX) {
			out->pas_plausible = 0;
		} else if (seen_valid && pa == first_pa) {
			out->pas_distinct = 0;
		}

		if (!seen_valid) {
			first_pa = pa;
			first_attr = (uint32_t)(e & 0xfff);
			seen_valid = 1;
		} else {
			if ((uint32_t)(e & 0xfff) != first_attr)
				out->attrs_consistent = 0;
			for (j = out->idx_first; j < i; j++)
				if ((page[j] & RS_PTE_VALID) &&
				    (page[j] & RS_PA_MASK) == pa)
					out->pas_distinct = 0;
		}
	}

	out->contiguous = (out->n_valid > 0 &&
			   out->idx_last - out->idx_first + 1 == out->n_valid);
}

int rs_is_marker_table(const uint64_t *page, int k, int first_idx,
		       struct rs_result *out)
{
	rs_analyze(page, out);

	if (out->n_valid != k)
		return 0;
	if (out->idx_first != first_idx)
		return 0;
	if (!out->contiguous || !out->attrs_consistent)
		return 0;
	if (!out->pas_plausible || !out->pas_distinct)
		return 0;
	return 1;
}

uint64_t pt_build_window(uint64_t live_entry, uint64_t target_pa)
{
	return (live_entry & ~(uint64_t)RS_PA_MASK) |
	       (target_pa & (uint64_t)RS_PA_MASK);
}

int pt_target_ok(uint64_t pa)
{
	return pa != 0 && (pa & 0xfff) == 0 && pa < RS_PA_MAX;
}

int pt_idx_usable(const uint64_t *table, int idx)
{
	return idx >= 0 && idx < RS_ENTRIES && table[idx] == 0;
}

#if defined(__arm__)
#define RS_BARRIER() __asm__ __volatile__("dsb ish" ::: "memory")
#else
#define RS_BARRIER() __sync_synchronize()
#endif

void pt_install_window(volatile uint64_t *entry, uint64_t w)
{
	volatile uint32_t *words = (volatile uint32_t *)entry;

	words[1] = (uint32_t)(w >> 32);   /* flags/PA-high: entry stays invalid */
	RS_BARRIER();
	words[0] = (uint32_t)w;           /* one atomic store: valid|PA|attrs */
	RS_BARRIER();
}

void pt_close_window(volatile uint64_t *entry)
{
	volatile uint32_t *words = (volatile uint32_t *)entry;

	words[0] = 0;                     /* atomic invalidation first */
	RS_BARRIER();
	words[1] = 0;
	RS_BARRIER();
}
