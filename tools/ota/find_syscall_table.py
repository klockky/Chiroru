#!/usr/bin/env python3
"""Find a unique arm64 syscall table using an Image and its reconstructed ELF."""
import argparse
import json
from pathlib import Path
import struct

# Independent anchors across the table, not a previous firmware's offset.
ANCHORS = {0: "__arm64_sys_io_setup", 48: "__arm64_sys_faccessat",
           79: "__arm64_sys_newfstatat", 142: "__arm64_sys_reboot",
           147: "__arm64_sys_setresuid", 221: "__arm64_sys_execve",
           281: "__arm64_sys_execveat"}


def symbols_and_base(image, elf):
    if len(image) < 64 or image[56:60] != b"ARMd":
        raise ValueError("Expected ARM64 Image")
    if len(elf) < 64 or elf[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected little-endian ELF64")
    h = struct.unpack_from("<16sHHIQQQIHHHHHH", elf)
    if h[1:3] != (2, 183) or h[9] != 56 or h[11] != 64:
        raise ValueError("Expected AArch64 executable ELF with standard headers")
    bases = []
    for i in range(h[10]):
        p = struct.unpack_from("<IIQQQQQQ", elf, h[5] + i * h[9])
        if p[0] == 1 and p[5] == len(image) and elf[p[2]:p[2] + p[5]] == image:
            bases.append(p[3])
    if len(bases) != 1:
        raise ValueError("ELF must contain exactly one LOAD segment identical to this Image")
    sections = [struct.unpack_from("<IIQQQQIIQQ", elf, h[6] + i * h[11]) for i in range(h[12])]
    wanted = {"_stext", *ANCHORS.values()}
    symbols = {}
    for section in sections:
        if section[1] != 2:
            continue
        if section[9] != 24 or section[5] % 24:
            raise ValueError("Malformed ELF symbol table")
        strings = sections[section[6]]
        strings = elf[strings[4]:strings[4] + strings[5]]
        for offset in range(section[4], section[4] + section[5], 24):
            name, info, other, index, address, size = struct.unpack_from("<IBBHQQ", elf, offset)
            label = strings[name:].split(b"\0", 1)[0].decode(errors="replace")
            if index and label in wanted:
                if label in symbols and symbols[label] != address:
                    raise ValueError(f"Ambiguous symbol: {label}")
                symbols[label] = address
    if wanted - symbols.keys():
        raise ValueError("Missing symbols: " + ", ".join(sorted(wanted - symbols.keys())))
    if not all(bases[0] <= address < bases[0] + len(image) for address in symbols.values()):
        raise ValueError("Symbol outside the Image; check ELF reconstruction")
    return symbols, bases[0]


def find_table(image, symbols, base):
    needle = struct.pack("<Q", symbols[ANCHORS[0]])
    matches, start = [], 0
    while True:
        offset = image.find(needle, start)
        if offset < 0:
            break
        start = offset + 1
        if offset % 8 or offset + (max(ANCHORS) + 1) * 8 > len(image):
            continue
        if all(struct.unpack_from("<Q", image, offset + nr * 8)[0] == symbols[name]
               for nr, name in ANCHORS.items()):
            matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"Expected one syscall table; found {len(matches)}")
    offset = matches[0]
    return {"sys_call_table": {"imageOffset": hex(offset),
                              "stextRelative": hex(base + offset - symbols["_stext"])},
            "validatedSlots": ANCHORS}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("elf", type=Path, help="ELF reconstructed by vmlinux-to-elf")
    args = parser.parse_args()
    try:
        image = args.image.read_bytes()
        symbols, base = symbols_and_base(image, args.elf.read_bytes())
        print(json.dumps(find_table(image, symbols, base), indent=2))
    except (OSError, ValueError, IndexError, struct.error) as error:
        parser.exit(1, f"Analysis failed: {error}\n")


if __name__ == "__main__":
    main()
