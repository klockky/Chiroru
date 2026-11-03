#!/usr/bin/env python3
"""Split a verified-format @AML boot container and validate its decrypted Image."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile
import zlib

MAX_BOOT = 64 * 1024 * 1024
MAX_IMAGE = 128 * 1024 * 1024
PAGE = 4096


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_boot(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entries = [e for e in archive.infolist() if e.filename == "boot.img"]
            if len(entries) != 1 or not 0 < entries[0].file_size <= MAX_BOOT:
                raise ValueError("Expected one boot.img, at most 64 MiB, in the OTA ZIP")
            return archive.read(entries[0])
    if not 0 < path.stat().st_size <= MAX_BOOT:
        raise ValueError("boot.img must be at most 64 MiB")
    return path.read_bytes()


def android_v3(data):
    if len(data) < PAGE or data[:8] != b"ANDROID!":
        raise ValueError("Expected Android boot image")
    kernel_size, ramdisk_size, os_version, header_size = struct.unpack_from("<4I", data, 8)
    version = struct.unpack_from("<I", data, 40)[0]
    if version != 3 or header_size != 1580:
        raise ValueError("Only the verified Android boot header v3 is supported")
    if not kernel_size or kernel_size > MAX_BOOT or ramdisk_size > MAX_BOOT:
        raise ValueError("Invalid Android kernel/ramdisk size")
    return {"kernelSize": kernel_size, "ramdiskSize": ramdisk_size, "osVersion": os_version}


def container(data):
    android = android_v3(data)
    if len(data) < PAGE + 0x300 or data[PAGE:PAGE + 4] != b"@AML":
        raise ValueError("Expected @AML at offset 0x1000")
    version, reserved1, reserved2, size, header = struct.unpack_from("<5I", data, PAGE + 4)
    if (version, reserved1, reserved2, header) != (1, 0, 0, 0x300):
        raise ValueError("Unknown @AML header variant")
    start = PAGE + header
    if not size or size % 16 or size > MAX_BOOT or start + size > len(data):
        raise ValueError("Invalid or truncated @AML ciphertext length")
    return data[start:start + size], {
        "format": "android-v3-aml-v1", "bootSha256": sha256(data),
        "ciphertextOffset": start, "ciphertextSize": size,
        "plaintextSha256": data[PAGE + 24:PAGE + 56].hex(), **android,
    }


def unpack(data, plain):
    _, meta = container(data)
    if len(plain) != meta["ciphertextSize"] or sha256(plain) != meta["plaintextSha256"]:
        raise ValueError("Decrypted payload size/SHA-256 does not match @AML header")
    inner = android_v3(plain)
    if any(inner[k] != meta[k] for k in inner):
        raise ValueError("Inner and outer Android headers disagree")
    end = PAGE + inner["kernelSize"]
    ramdisk_start = (end + PAGE - 1) & -PAGE
    if ramdisk_start + inner["ramdiskSize"] > len(plain):
        raise ValueError("Truncated inner boot image")
    packed = plain[PAGE:end]
    if not packed.startswith(b"\x1f\x8b"):
        raise ValueError("Expected gzip kernel in decrypted boot image")
    dec = zlib.decompressobj(16 + zlib.MAX_WBITS)
    image = dec.decompress(packed, MAX_IMAGE + 1)
    if len(image) > MAX_IMAGE or not dec.eof or dec.unused_data or dec.unconsumed_tail:
        raise ValueError("Oversized, truncated or multi-stream gzip kernel")
    if len(image) < 64 or image[56:60] != b"ARMd":
        raise ValueError("Decompressed kernel is not an ARM64 Image")
    text_offset, memory_size, flags = struct.unpack_from("<3Q", image, 8)
    if not len(image) <= memory_size <= MAX_IMAGE:
        raise ValueError("Invalid ARM64 memory image size")
    meta.update({"imageSha256": sha256(image), "imageFileSize": len(image),
                 "imageMemorySize": memory_size, "textOffset": text_offset, "imageFlags": flags})
    return image, meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    split = commands.add_parser("split", help="Extract encrypted payload from boot.img or a full OTA ZIP")
    split.add_argument("input", type=Path)
    split.add_argument("output", type=Path, help="New directory")
    extract = commands.add_parser("unpack", help="Verify decrypted boot payload and extract ARM64 Image")
    extract.add_argument("input", type=Path, help="Original wrapped boot.img or OTA ZIP")
    extract.add_argument("plaintext", type=Path)
    extract.add_argument("output", type=Path, help="New directory")
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise ValueError("Output directory already exists")
        data = read_boot(args.input)
        if args.command == "split":
            payload, metadata = container(data)
            name = "ciphertext.bin"
        else:
            if args.plaintext.stat().st_size > MAX_BOOT:
                raise ValueError("Decrypted payload exceeds 64 MiB")
            payload, metadata = unpack(data, args.plaintext.read_bytes())
            name = "Image"
        args.output.mkdir(parents=True)
        (args.output / name).write_bytes(payload)
        (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(json.dumps(metadata, indent=2))
    except (OSError, ValueError, zipfile.BadZipFile, zlib.error) as error:
        parser.exit(1, f"Extraction failed: {error}\n")


if __name__ == "__main__":
    main()
