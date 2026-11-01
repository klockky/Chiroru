/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef KSU_POLICY_TRANSACTION_H
#define KSU_POLICY_TRANSACTION_H
struct policydb;
typedef int (*ksu_policy_mutator)(struct policydb *, void *);
int ksu_policy_transaction(ksu_policy_mutator mutate, void *arg);
#endif
