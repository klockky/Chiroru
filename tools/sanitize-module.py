#!/usr/bin/env python3
"""Remove build-machine paths from an AArch64 module without changing code.

Keep the runtime symbol table: Chiroru resolves undefined symbols before loading.
Only debug sections and diagnostic source-path strings are changed. Runtime
sections, symbols and relocations are compared before writing the output.
"""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile


class Elf:
    def __init__(self, data):
        self.data = data
        h = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        if h[0][:6] != b'\x7fELF\x02\x01' or h[1:3] != (1, 183):
            raise ValueError('expected little-endian ELF64 AArch64 relocatable module')
        self.sections = [struct.unpack_from('<IIQQQQIIQQ', data, h[6] + i * h[11])
                         for i in range(h[12])]
        strings = self.contents(self.sections[h[13]])
        self.names = [strings[s[0]:].split(b'\0', 1)[0].decode() for s in self.sections]

    def contents(self, section):
        return b'' if section[1] == 8 else self.data[section[4]:section[4] + section[5]]

    def symbols(self, index):
        table = self.sections[index]
        strings = self.contents(self.sections[table[6]])
        result = []
        for offset in range(0, table[5], table[9]):
            name, info, other, section, value, size = struct.unpack_from(
                '<IBBHQQ', self.data, table[4] + offset)
            label = self.names[section] if 0 < section < len(self.names) else str(section)
            result.append((strings[name:].split(b'\0', 1)[0], info, other, label, value, size))
        return result

    def runtime_symbols(self):
        result = []
        for i, section in enumerate(self.sections):
            if section[1] == 2:
                result.extend(s for s in self.symbols(i)
                              if s[0] and s[1] & 15 != 4 and not s[3].startswith('.debug'))
        return sorted(result)

    def relocations(self):
        result = {}
        for name, section in zip(self.names, self.sections):
            if section[1] not in (4, 9) or self.names[section[7]].startswith('.debug'):
                continue
            if section[1] != 4:
                raise ValueError('unexpected REL relocation table')
            symbols = self.symbols(section[6])
            entries = []
            for offset in range(0, section[5], section[9]):
                location, info, addend = struct.unpack_from('<QQq', self.data, section[4] + offset)
                entries.append((location, info & 0xffffffff, symbols[info >> 32], addend))
            result[name] = entries
        return result

    def runtime_sections(self):
        return {name: (s[1], s[2], s[3], s[5], s[8], self.contents(s))
                for name, s in zip(self.names, self.sections) if s[2] & 2}


def sanitize(source, destination, objcopy):
    if source.resolve() == destination.resolve():
        raise ValueError('input and output must be different files')
    original = Elf(source.read_bytes())
    changed = bytearray(original.data)
    count = 0
    for name, section in zip(original.names, original.sections):
        if not section[2] & 2 or section[1] == 8:
            continue
        contents = original.contents(section)
        for match in re.finditer(rb'/(?:home|Users)/[^\0\r\n]+?/build-txn/([^\0\r\n]+)', contents):
            if not name.startswith('.rodata'):
                raise ValueError('unexpected personal path outside diagnostic strings')
            replacement = b'/src/build-txn/' + match[1]
            if len(replacement) > len(match[0]):
                raise ValueError('replacement would move runtime offsets')
            start = section[4] + match.start()
            # Leading slashes keep the original string length and suffix offsets,
            # including any linker tail-merged references into the filename.
            changed[start:start + len(match[0])] = replacement.rjust(len(match[0]), b'/')
            count += 1
    expected = Elf(bytes(changed))
    with tempfile.TemporaryDirectory(prefix='chiroru-module-') as directory:
        temporary = Path(directory)
        (temporary / 'input.ko').write_bytes(changed)
        subprocess.run([objcopy, '--strip-debug', str(temporary / 'input.ko'),
                        str(temporary / 'output.ko')], check=True)
        clean = Elf((temporary / 'output.ko').read_bytes())
        if expected.runtime_sections() != clean.runtime_sections():
            raise ValueError('runtime section changed during debug stripping')
        if original.runtime_symbols() != clean.runtime_symbols():
            raise ValueError('runtime symbol table changed')
        if original.relocations() != clean.relocations():
            raise ValueError('runtime relocations changed')
        if re.search(rb'/(?:home|Users)/', clean.data):
            raise ValueError('a home-directory path remains in the module')
        destination.write_bytes(clean.data)
    print(f'Sanitized {count} diagnostic paths; runtime sections, symbols and relocations verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--objcopy', default='aarch64-linux-gnu-objcopy')
    args = parser.parse_args()
    sanitize(args.source, args.destination, args.objcopy)
