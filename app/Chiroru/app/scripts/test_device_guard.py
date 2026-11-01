"""Exercise the exact native hardware allowlist without an Android device."""
from pathlib import Path
import subprocess
import tempfile
import unittest

APP = Path(__file__).resolve().parents[1]


class DeviceGuardTest(unittest.TestCase):
    def test_device_and_model_must_both_match_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'test.c'
            binary = Path(directory) / 'test'
            source.write_text(r'''
#include <assert.h>
#include "device_guard.h"
int main(void) {
    assert(chiroru_is_duo_max("chiron", "YNDX-00055"));
    assert(!chiroru_is_duo_max("other", "YNDX-00055"));
    assert(!chiroru_is_duo_max("chiron", "other"));
    assert(!chiroru_is_duo_max("CHIRON", "YNDX-00055"));
    assert(!chiroru_is_duo_max("chiron", "YNDX-00055 "));
    assert(!chiroru_is_duo_max("", ""));
    assert(!chiroru_is_duo_max(0, "YNDX-00055"));
    assert(!chiroru_is_duo_max("chiron", 0));
    return 0;
}
''')
            subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror',
                            '-I' + str(APP / 'src/main/cpp'), str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)


if __name__ == '__main__':
    unittest.main()
