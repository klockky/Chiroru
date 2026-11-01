/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef KSU_POLICY_WRITER_H
#define KSU_POLICY_WRITER_H
struct policydb;
#define KSU_POLICY_MAX_BYTES (32UL * 1024 * 1024)
int ksu_policy_measure(struct policydb *db, size_t *length);
int ksu_policy_serialize(struct policydb *db, void *data, size_t capacity);
#endif
