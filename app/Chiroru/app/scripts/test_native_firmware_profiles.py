"""Exercise native profile gates and ADB relocation against every shipped module."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]
SOURCE = (APP / "src/main/cpp/backends/chiron_5000001307/root.c").read_text()


class NativeFirmwareProfilesTest(unittest.TestCase):
    def test_exact_gates_and_adb_data_symbols_for_both_firmwares(self):
        profiles = SOURCE[SOURCE.index("struct chiroru_vendor_sym"):SOURCE.index("#define WALK_SUB")]
        patcher = SOURCE[SOURCE.index("static int patch_vendor_syms("):SOURCE.index("static int patch_module_symbols(")]
        gate = SOURCE[SOURCE.index("static int profile_gate(const struct chiroru_profile *prof, struct rpt *r) {"):
                      SOURCE.index("int chiron_5000001307_root(")]
        adb = SOURCE[SOURCE.index("static int load_adb_module("):SOURCE.index("static int factory_manager_path(")]
        # Use the real caller's count expression: a wrong KSU symbol count here
        # used to reject the valid 1307 ADB module before init_module.
        count = re.search(r"patch_vendor_syms\(kbuf, ksize, stext, prof,\s*(.*?), r\)", adb, re.S).group(1)
        start = SOURCE.index("static int chiroru_root_with(")
        entry = SOURCE[start:SOURCE.index("/* Mandatory exact identity gate", start)]
        self.assertLess(entry.index("profile_gate(prof, r)"), entry.index("open(ko_path"))
        program = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <elf.h>
#include <unistd.h>
#include <sys/utsname.h>
struct rpt { int unused; };
#define rp(...) ((void)0)
static const char *fp, *inc, *release, *machine, *sdk;
static int bad_uname;
static long page_size;
static int fake_property(const char *key, char *out) {
    const char *value = !strcmp(key, "ro.build.fingerprint") ? fp :
        !strcmp(key, "ro.build.version.incremental") ? inc : sdk;
    strcpy(out, value); return strlen(value);
}
static int fake_uname(struct utsname *out) {
    if (bad_uname) return -1;
    strcpy(out->release, release); strcpy(out->machine, machine); return 0;
}
static long fake_sysconf(int key) { (void)key; return page_size; }
#define __system_property_get fake_property
#define uname fake_uname
#define sysconf fake_sysconf
''' + profiles + patcher + gate + r'''
int main(int argc, char **argv) {
    assert(argc == 4);
    struct rpt report;
    const struct chiroru_profile *all[] = { &profile_5000001307, &profile_1982735225, &profile_5000001245 };
    for (int i = 0; i < 3; ++i) {
        const struct chiroru_profile *prof = all[i];
        fp = prof->fingerprint; inc = prof->incremental;
        release = prof->kernel_release; machine = prof->machine;
        sdk = "30"; page_size = 4096; bad_uname = 0;
        assert(profile_gate(prof, &report) == 0);
        for (int j = 0; j < 3; ++j) if (j != i) assert(profile_gate(all[j], &report) != 0);
        const char **fields[] = { &fp, &inc, &release, &machine, &sdk };
        for (int j = 0; j < 5; ++j) {
            const char *saved = *fields[j]; *fields[j] = "different";
            assert(profile_gate(prof, &report) != 0); *fields[j] = saved;
        }
        page_size = 16384; assert(profile_gate(prof, &report) != 0); page_size = 4096;
        bad_uname = 1; assert(profile_gate(prof, &report) != 0); bad_uname = 0;
        struct chiroru_profile missing = *prof;
        missing.fingerprint = NULL; assert(profile_gate(&missing, &report) != 0);
        FILE *file = fopen(argv[i+1], "rb"); assert(file);
        assert(!fseek(file, 0, SEEK_END)); long size = ftell(file); assert(size > 0); rewind(file);
        void *data = malloc(size); assert(data); assert(fread(data, 1, size, file) == (size_t)size); fclose(file);
        assert(patch_vendor_syms(data, size, 0xffffffc010080800ULL, prof, COUNT, &report) == 0);
        free(data);
    }
    return 0;
}
'''.replace("COUNT", count)
        with tempfile.TemporaryDirectory() as directory:
            source, binary = Path(directory) / "profiles.c", Path(directory) / "profiles"
            source.write_text(program)
            subprocess.run(["cc", "-std=gnu11", "-O2", str(source), "-o", str(binary)], check=True)
            subprocess.run([str(binary), *(str(APP / f"src/main/assets/firmware/chiron-{build}/chiroru_adb.ko")
                                          for build in ("5000001307", "1982735225", "5000001245"))], check=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
