#ifndef CHIRORU_BACKEND_REGISTRY_H
#define CHIRORU_BACKEND_REGISTRY_H

#include <stddef.h>

struct rpt { char *buf; size_t cap; size_t len; };

/* A backend owns its driver/kernel ABI and module-loading strategy. */
struct chiroru_backend {
  const char *id;
  const char *(*probe)(void);
  int (*root)(const char *ko, const char *ksud, int run_hooks, struct rpt *report);
};

const struct chiroru_backend *chiroru_find_backend(const char *id);

#endif
