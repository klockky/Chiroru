"""Synthetic format/ambiguity tests; no vendor images or hardware keys needed."""
import gzip
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from tools.ota import aml_boot, find_syscall_table as finder


def image_fixture():
    image = bytearray(0x3000)
    image[56:60] = b"ARMd"
    struct.pack_into("<3Q", image, 8, 0x80000, 0x4000, 10)
    return image


def boot_fixture():
    image = bytes(image_fixture())
    packed = gzip.compress(image, mtime=0)
    header = bytearray(4096)
    header[:8] = b"ANDROID!"
    struct.pack_into("<4I", header, 8, len(packed), 16, 0, 1580)
    struct.pack_into("<I", header, 40, 3)
    plain = bytes(header) + packed
    plain += bytes((-len(plain)) % 4096) + bytes(16)
    aml = bytearray(0x300)
    aml[:4] = b"@AML"
    struct.pack_into("<5I", aml, 4, 1, 0, 0, len(plain), len(aml))
    aml[24:56] = hashlib.sha256(plain).digest()
    # Ciphertext content is immaterial to offline validation; decryption is external.
    wrapped = bytes(header) + bytes(aml) + bytes(len(plain))
    return wrapped, plain, image


def table_fixture(compat=False):
    image = image_fixture()
    base = 0xffffffc010080000
    anchors = finder.COMPAT_ANCHORS if compat else finder.ANCHORS
    symbols = {name: base + 0x100 + nr * 4 for nr, name in anchors.items()}
    symbols["_stext"] = base + 0x800
    for nr, name in anchors.items():
        struct.pack_into("<Q", image, 0x1800 + nr * 8, symbols[name])
    return image, symbols, base


def elf_fixture(image, symbols, base):
    names = bytearray(b"\0")
    symtab = bytearray(24)
    for name, address in symbols.items():
        offset = len(names)
        names += name.encode() + b"\0"
        symtab += struct.pack("<IBBHQQ", offset, 0x12, 0, 1, address, 4)
    data = bytearray(0x200) + image
    symoff = len(data)
    data += symtab
    stroff = len(data)
    data += names
    shoff = len(data)
    data += bytes(64)
    data += struct.pack("<IIQQQQIIQQ", 0, 1, 7, base, 0x200, len(image), 0, 0, 4, 0)
    data += struct.pack("<IIQQQQIIQQ", 0, 2, 0, 0, symoff, len(symtab), 3, 0, 8, 24)
    data += struct.pack("<IIQQQQIIQQ", 0, 3, 0, 0, stroff, len(names), 0, 0, 1, 0)
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, ident, 2, 183, 1, base,
                     64, shoff, 0, 64, 56, 1, 64, 4, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 7, 0x200, base, base, len(image), len(image), 4096)
    return data


class AmlBootTest(unittest.TestCase):
    def test_verified_payload_extracts_image_without_runtime_bss(self):
        wrapped, plain, expected = boot_fixture()
        image, metadata = aml_boot.unpack(wrapped, plain)
        self.assertEqual(expected, image)
        self.assertEqual(len(expected), metadata["imageFileSize"])
        self.assertGreater(metadata["imageMemorySize"], len(image))

    def test_wrong_plaintext_is_rejected(self):
        wrapped, plain, _ = boot_fixture()
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            aml_boot.unpack(wrapped, plain[:-1] + b"\x01")

    def test_truncated_ciphertext_and_unknown_header_are_rejected(self):
        wrapped, _, _ = boot_fixture()
        with self.assertRaisesRegex(ValueError, "truncated"):
            aml_boot.container(wrapped[:-1])
        broken = bytearray(wrapped)
        struct.pack_into("<I", broken, 4096 + 20, 0x400)
        with self.assertRaisesRegex(ValueError, "variant"):
            aml_boot.container(broken)

    def test_hash_valid_payload_with_wrong_android_header_is_rejected(self):
        wrapped, plain, _ = boot_fixture()
        plain = bytearray(plain)
        struct.pack_into("<I", plain, 8, 1)
        wrapped = bytearray(wrapped)
        wrapped[4096 + 24:4096 + 56] = hashlib.sha256(plain).digest()
        with self.assertRaisesRegex(ValueError, "disagree"):
            aml_boot.unpack(wrapped, plain)

    def test_reads_boot_from_full_ota_zip(self):
        wrapped, _, _ = boot_fixture()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "update.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("boot.img", wrapped)
                archive.writestr("unrelated.txt", "ignored")
            self.assertEqual(wrapped, aml_boot.read_boot(path))


class SyscallTableTest(unittest.TestCase):
    def test_compat_table_checks_every_anchor_and_rejects_ambiguity(self):
        image, symbols, base = table_fixture(compat=True)
        recovered, mapped_base = finder.symbols_and_base(image, elf_fixture(image, symbols, base), compat=True)
        result = finder.find_table(image, recovered, mapped_base, compat=True)
        self.assertEqual("0x1000", result["compat_sys_call_table"]["stextRelative"])
        for nr in finder.COMPAT_ANCHORS:
            broken = bytearray(image)
            struct.pack_into("<Q", broken, 0x1800 + nr * 8, 0)
            with self.assertRaisesRegex(ValueError, "found 0"):
                finder.find_table(broken, recovered, mapped_base, compat=True)
        image.extend(bytes(0x1000))
        for nr, name in finder.COMPAT_ANCHORS.items():
            struct.pack_into("<Q", image, 0x3000 + nr * 8, symbols[name])
        with self.assertRaisesRegex(ValueError, "found 2"):
            finder.find_table(image, recovered, mapped_base, compat=True)

    def test_matching_elf_recovers_unique_table(self):
        image, symbols, base = table_fixture()
        recovered, mapped_base = finder.symbols_and_base(image, elf_fixture(image, symbols, base))
        result = finder.find_table(image, recovered, mapped_base)
        self.assertEqual("0x1000", result["sys_call_table"]["stextRelative"])

    def test_elf_from_another_image_is_rejected(self):
        image, symbols, base = table_fixture()
        elf = elf_fixture(image, symbols, base)
        image[64] ^= 1
        with self.assertRaisesRegex(ValueError, "identical"):
            finder.symbols_and_base(image, elf)

    def test_one_wrong_slot_blocks_candidate(self):
        image, symbols, base = table_fixture()
        struct.pack_into("<Q", image, 0x1800 + 142 * 8, 0)
        with self.assertRaisesRegex(ValueError, "found 0"):
            finder.find_table(image, symbols, base)

    def test_duplicate_tables_are_not_guessed(self):
        image, symbols, base = table_fixture()
        image.extend(bytes(0x1000))
        for nr, name in finder.ANCHORS.items():
            struct.pack_into("<Q", image, 0x3000 + nr * 8, symbols[name])
        with self.assertRaisesRegex(ValueError, "found 2"):
            finder.find_table(image, symbols, base)


if __name__ == "__main__":
    unittest.main()
