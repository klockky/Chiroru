#include "backend_registry.h"
#include <string.h>

#define CHIRORU_BACKEND(id, probe, root) \
  const char *probe(void); \
  int root(const char *, const char *, int, struct rpt *);
#include "backend_registry.def"
#undef CHIRORU_BACKEND

static const struct chiroru_backend backends[] = {
#define CHIRORU_BACKEND(id, probe, root) { id, probe, root },
#include "backend_registry.def"
#undef CHIRORU_BACKEND
};

const struct chiroru_backend *chiroru_find_backend(const char *id) {
  if (!id) return NULL;
  for (size_t i = 0; i < sizeof backends / sizeof backends[0]; i++)
    if (!strcmp(id, backends[i].id)) return &backends[i];
  return NULL;
}
