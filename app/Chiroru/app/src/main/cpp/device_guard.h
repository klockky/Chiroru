#ifndef CHIRORU_DEVICE_GUARD_H
#define CHIRORU_DEVICE_GUARD_H
#include <string.h>

static inline int chiroru_is_duo_max(const char *device, const char *model) {
    return device && model && !strcmp(device, "chiron") && !strcmp(model, "YNDX-00055");
}
#endif
