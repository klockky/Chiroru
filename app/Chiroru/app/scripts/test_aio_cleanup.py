"""Kernel AIO unmap ownership and protection of a mapping reusing the address."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]


class AioCleanupTest(unittest.TestCase):
    def test_destroy_owns_unmap_and_cleanup_preserves_reused_address(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'test.c'
            binary = Path(directory) / 'test'
            source.write_text(r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <sys/mman.h>
#include "aio_cleanup.h"
int main(void) {
    unsigned long context = 0;
    assert(!syscall(SYS_io_setup, 32, &context));
    void *address = (void *)context;
    long page = sysconf(_SC_PAGESIZE);
    unsigned char state;
    assert(!mincore(address, page, &state));
    assert(!chiroru_aio_destroy(&context) && !context);
    assert(mincore(address, page, &state) == -1 && errno == ENOMEM);
    volatile unsigned char *reused = mmap(address, page, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    assert((void *)reused == address);
    *reused = 0x5a;
    assert(!chiroru_aio_destroy(&context));
    assert(!mincore(address, page, &state) && *reused == 0x5a);
    assert(!munmap(address, page));
    return 0;
}
''')
            subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', '-I' + str(APP / 'src/main/cpp'),
                            str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)

    def test_vendor_kernel_and_backend_agree_on_ring_ownership(self):
        fixtures = Path(__file__).with_name('fixtures')
        metadata = json.loads((fixtures / 'kernel.json').read_text())
        elf = os.environ.get('CHIRORU_KERNEL_ELF')
        if elf:
            self.assertEqual(hashlib.sha256(Path(elf).read_bytes()).hexdigest(), metadata['elfSha256'])
            code = subprocess.check_output(['aarch64-linux-gnu-objdump', '-d', '--disassemble=kill_ioctx', elf], text=True)
        else:
            code = (fixtures / 'kill_ioctx.txt').read_text()
            self.assertEqual(hashlib.sha256(code.encode()).hexdigest(), metadata['disassemblySha256'])
        self.assertIn('<vm_munmap>', code)
        source = (APP / 'src/main/cpp/backends/chiron_5000001307/root.c').read_text()
        self.assertIn('chiroru_aio_destroy(&aio)', source)
        self.assertNotRegex(source, r'munmap\s*\([^;]*(?:ub\.ptr|\baio\b)')


if __name__ == '__main__':
    unittest.main()
