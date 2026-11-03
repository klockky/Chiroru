import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

SOURCE = textwrap.dedent(r'''
    #include "compat.h"
    #include <stdio.h>

    int main(void) {
        _Static_assert(BITS_PER_LONG == 32, "32-bit build must use 32-bit words");
        _Static_assert(sizeof(unsigned long) == 4, "this test must compile as 32-bit");
        unsigned long a[6] = {0};
        a[0] |= 1UL << 0;    /* bit 0   */
        a[1] |= 1UL << 1;    /* bit 33  */
        a[1] |= 1UL << 31;   /* bit 63  */
        a[2] |= 1UL << 0;    /* bit 64  */
        a[3] |= 1UL << 4;    /* bit 100 */
        a[5] |= 1UL << 4;    /* bit 164 */
        a[5] |= 1UL << 31;   /* bit 191 */
        const unsigned long want[] = {0, 33, 63, 64, 100, 164, 191, 192};
        unsigned long pos = 0;
        for (unsigned i = 0; i < 8; i++) {
            pos = find_next_bit(a, 192, pos);
            if (pos != want[i]) { printf("find %u: %lu != %lu\n", i, pos, want[i]); return 1; }
            pos++;
        }
        if (__fls(a[5]) != 31) { printf("fls %lu\n", __fls(a[5])); return 1; }
        if (__fls(a[3]) != 4) { printf("fls2 %lu\n", __fls(a[3])); return 1; }
        puts("ok");
        return 0;
    }
''')


class PolicyHostCompatTest(unittest.TestCase):
    def test_32bit_bit_helpers_use_32bit_words(self):
        """The policy writer's ebitmap iteration must work on ARM32 (ILP32)."""
        if not shutil.which("cc"):
            self.skipTest("no host C compiler")
        root = Path(__file__).resolve().parents[4]
        include = root / "tools" / "policy_host"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "compat32.c"
            binary = Path(directory) / "compat32"
            source.write_text(SOURCE)
            build = subprocess.run(
                ["cc", "-m32", "-std=gnu11", "-I", str(include), str(source), "-o", str(binary)],
                capture_output=True, text=True)
            if build.returncode != 0:
                self.skipTest("32-bit host toolchain unavailable: " + build.stderr[-160:])
            run = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(run.stdout.strip(), "ok")


if __name__ == "__main__":
    unittest.main()
