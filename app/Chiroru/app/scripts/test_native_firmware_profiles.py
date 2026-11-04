"""Exercise native profile gates and ADB relocation against every shipped module."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]
SOURCE = (APP / "src/main/cpp/backends/chiron_5000001307/root.c").read_text()


class NativeFirmwareProfilesTest(unittest.TestCase):
    def test_base_reads_do_not_reuse_cached_window_translations(self):
        profiles = SOURCE[SOURCE.index("struct chiroru_vendor_sym"):SOURCE.index("#define WALK_SUB")]
        start = SOURCE.index("    volatile uint8_t *slot0 =")
        setup = SOURCE[start:SOURCE.index("      for (n = 0;", start)]
        program = r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#define rp(...) ((void)0)
#define BLOCK_BYTES 4096
''' + profiles + r'''
static uint8_t pages[8][4096], *tlb[512];
static uint64_t physical[8];
static int page_count;
static uint8_t *page(uint64_t pa) {
    pa &= ~0xfffULL;
    for (int i = 0; i < page_count; ++i)
        if (physical[i] == pa) return pages[i];
    assert(page_count < 8);
    physical[page_count] = pa;
    return pages[page_count++];
}
static void put(uint64_t pa, uint64_t value) {
    memcpy(page(pa) + (pa & 0xfff), &value, 8);
}
static volatile uint8_t *win_open(volatile uint64_t *table, int idx,
        uint64_t live, uint64_t pa, volatile uint8_t *base) {
    assert(idx >= 3 && idx < 512);
    /* Model a cached translation surviving a PTE clear. */
    if (!tlb[idx]) tlb[idx] = page(pa);
    return tlb[idx];
}
static void win_close(volatile uint64_t *table, int idx) { }
static int run(const struct chiroru_profile *prof) {
    memset(pages, 0, sizeof pages); memset(tlb, 0, sizeof tlb); page_count = 0;
    uint64_t expected_base = 0xffffff8040000000ULL;
    uint64_t expected_task = expected_base + 0x1234418ULL;
    uint64_t init_page_pa = prof->init_task_pa & ~0xfffULL;
    uint64_t init_lbase_raw = (prof->init_task_pa & 0xfffULL) + 0x418;
    uint64_t init_walk_raw = (prof->init_task_pa & 0xfffULL) + 0x420;
    put(init_page_pa + init_lbase_raw, expected_base + prof->lbase_magic);
    put(init_page_pa + init_walk_raw, expected_task);
    if (prof->image_size) {
        put(prof->image_pa + 8, 0x80000);
        put(prof->image_pa + 16, prof->image_size);
        put(prof->image_pa + 56, 0x644d5241);
    }
    if (!prof->use_magic_lbase) {
        put(prof->memstart_addr_pa, 0ULL - 0x40000000ULL);
        put(prof->vabits_actual_pa, 39);
        put(prof->kimage_voffset_pa, 0xffffffee0f000000ULL);
    }
    uint64_t lbase = 0, stext_va = 0, table0 = 0, table[512] = {0};
    uint8_t spray_base[4096]; int found_block = 0;
''' + setup + r'''
        if (lbase != expected_base || va != expected_task) return 2;
        if (!prof->use_magic_lbase &&
            stext_va != 0xffffffee0f000000ULL + prof->image_pa + 0x800) return 3;
    }
    return 0;
out:
    return 4;
}
int main(void) {
    const struct chiroru_profile *all[] = { &profile_5000001307,
        &profile_1982735225, &profile_5000001245, &profile_5000001248,
        &profile_5000001289, &profile_5000001321, &profile_5000001346, &profile_5000001364,
        &profile_5000001388 };
    for (unsigned i = 0; i < sizeof all / sizeof all[0]; ++i) { int rc = run(all[i]); if (rc) return rc; }
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            source, binary = Path(directory) / "windows.c", Path(directory) / "windows"
            for reuse_header_slot in (False, True):
                text = program
                if reuse_header_slot:
                    # Reintroduce rc1's header/memstart slot collision.
                    text = text.replace("int slot = next_slot++;", "int slot = 3;", 1)
                source.write_text(text)
                subprocess.run(["cc", "-std=gnu11", "-O2", str(source), "-o", str(binary)], check=True)
                result = subprocess.run([str(binary)], timeout=10)
                self.assertEqual(result.returncode, 2 if reuse_header_slot else 0)

    def test_exact_gates_and_adb_data_symbols_for_all_firmwares(self):
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
    struct rpt report;
    const struct chiroru_profile *all[] = { &profile_5000001307, &profile_1982735225, &profile_5000001245, &profile_5000001248,
        &profile_5000001289, &profile_5000001321, &profile_5000001346, &profile_5000001364,
        &profile_5000001388 };
    const unsigned count = sizeof all / sizeof all[0];
    assert(argc == count + 1);
    for (unsigned i = 0; i < count; ++i) {
        const struct chiroru_profile *prof = all[i];
        fp = prof->fingerprint; inc = prof->incremental;
        release = prof->kernel_release; machine = prof->machine;
        sdk = "30"; page_size = 4096; bad_uname = 0;
        assert(profile_gate(prof, &report) == 0);
        for (unsigned j = 0; j < count; ++j) if (j != i) assert(profile_gate(all[j], &report) != 0);
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
                                          for build in ("5000001307", "1982735225", "5000001245", "5000001248", "5000001289", "5000001321",
                                          "5000001346", "5000001364", "5000001388"))], check=True, timeout=10)

    def test_1388_ksu_syscall_symbol_matches_its_decrypted_ota_address(self):
        profiles = SOURCE[SOURCE.index("struct chiroru_vendor_sym"):SOURCE.index("#define WALK_SUB")]
        patcher = SOURCE[SOURCE.index("static int patch_vendor_syms("):SOURCE.index("static int patch_module_symbols(")]
        program = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <elf.h>
struct rpt { int unused; };
#define rp(...) ((void)0)
''' + profiles + patcher + r'''
int main(int argc, char **argv) {
    assert(argc == 2);
    FILE *file = fopen(argv[1], "rb"); assert(file);
    assert(!fseek(file, 0, SEEK_END)); long size = ftell(file); assert(size > 0); rewind(file);
    void *data = malloc(size); assert(data);
    assert(fread(data, 1, size, file) == (size_t)size); fclose(file);
    struct rpt report;
    assert(patch_vendor_syms(data, size, 0xffffffc010080800ULL,
                            &profile_5000001388, 4, &report) == 0);
    Elf64_Ehdr *eh = data;
    Elf64_Shdr *sections = (Elf64_Shdr *)((char *)data + eh->e_shoff);
    int found = 0;
    for (unsigned i = 0; i < eh->e_shnum; ++i) {
        if (sections[i].sh_type != SHT_SYMTAB) continue;
        const char *names = (char *)data + sections[sections[i].sh_link].sh_offset;
        Elf64_Sym *symbols = (Elf64_Sym *)((char *)data + sections[i].sh_offset);
        for (unsigned j = 0; j < sections[i].sh_size / sizeof *symbols; ++j) {
            if (strcmp(names + symbols[j].st_name, "sys_call_table")) continue;
            assert(symbols[j].st_shndx == SHN_ABS);
            /* Independent OTA evidence: Image base + 0xEEEEE8, located by
             * seven named syscall slots. Reusing 1364 would yield +0xEEEF00. */
            assert(symbols[j].st_value == 0xffffffc010f6eee8ULL);
            found++;
        }
    }
    assert(found == 1);
    free(data);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            source, binary = Path(directory) / "ksu.c", Path(directory) / "ksu"
            source.write_text(program)
            subprocess.run(["cc", "-std=gnu11", "-O2", str(source), "-o", str(binary)], check=True)
            subprocess.run([str(binary), str(APP / "src/main/assets/firmware/chiron-5000001388/kernelsu.ko")],
                           check=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
