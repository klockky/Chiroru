#ifndef KSU54_SHIM_H
#define KSU54_SHIM_H
#ifndef __nocfi
#define __nocfi
#endif
unsigned long ksu_copy_to_user(void *to, const void *from, unsigned long n);
unsigned long ksu_copy_from_user(void *to, const void *from, unsigned long n);
#endif
