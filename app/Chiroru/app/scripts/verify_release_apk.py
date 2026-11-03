#!/usr/bin/env python3
"""Verify the actual universal APK, including both ABIs and profile-specific payloads."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


def elf(data, elf_class, machine, name):
    if data[:6] != b"\x7fELF" + bytes([elf_class, 1]) or struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError(f"Wrong ELF architecture: {name}")


def arm32_runtime(data):
    """Gradle strips ELF section metadata; compare program headers and loadable bytes."""
    elf(data, 1, 40, "factory daemon")
    image = bytearray(data)
    header = struct.unpack_from("<16sHHIIIIIHHHHHH", image)
    image[32:36] = bytes(4)  # e_shoff
    image[46:52] = bytes(6)  # e_shentsize, e_shnum, e_shstrndx
    segments = []
    for i in range(header[10]):
        program = struct.unpack_from("<IIIIIIII", image, header[5] + i * header[9])
        if program[0] == 1:  # PT_LOAD
            segments.append((program, bytes(image[program[1]:program[1] + program[4]])))
    if not segments:
        raise ValueError("Daemon has no loadable segments")
    return segments


def verify(apk):
    app = Path(__file__).resolve().parents[1]
    catalog_bytes = (app / "src/main/assets/firmware/profiles.json").read_bytes()
    catalog = json.loads(catalog_bytes)
    with zipfile.ZipFile(apk) as archive:
        if archive.read("assets/firmware/profiles.json") != catalog_bytes:
            raise ValueError("APK catalog differs from source")
        if {p["id"] for p in catalog["profiles"]} != {"chiron-1982735225", "chiron-5000001307"}:
            raise ValueError("Release must contain both verified firmware profiles")
        for abi, cls, machine in (("arm64-v8a", 2, 183), ("armeabi-v7a", 1, 40)):
            for library in ("libchiroru.so", "libchiroru_console.so", "libchiroru_root_console.so"):
                name = f"lib/{abi}/{library}"
                elf(archive.read(name), cls, machine, name)
        for profile in catalog["profiles"]:
            cls, machine = (1, 40) if profile["match"]["machine"] == "armv8l" else (2, 183)
            for support, kinds in (("kernelSu", ("module", "ksud")), ("adbRoot", ("module", "loader", "client"))):
                for kind in kinds:
                    artifact = profile[support][kind]
                    data = archive.read("assets/" + artifact["asset"])
                    if hashlib.sha256(data).hexdigest() != artifact["sha256"]:
                        raise ValueError(f"Wrong APK payload: {profile['id']}/{kind}")
                    # Both kernel modules are AArch64. The legacy ADB loader is
                    # AArch64 too; factory's direct init_module path never runs it.
                    kernel_or_loader = kind in {"module", "loader"}
                    elf(data, 2 if kernel_or_loader else cls, 183 if kernel_or_loader else machine, artifact["asset"])
            manager = profile["kernelSu"]["manager"]
            if manager["source"] == "BUNDLED":
                artifact = manager["apk"]
                data = archive.read("assets/" + artifact["asset"])
                if hashlib.sha256(data).hexdigest() != artifact["sha256"]:
                    raise ValueError("Wrong bundled manager APK")
                with zipfile.ZipFile(io.BytesIO(data)) as bundled:
                    elf(bundled.read("lib/armeabi-v7a/libksud.so"), 1, 40, "manager/ksud")
                    if arm32_runtime(bundled.read("lib/armeabi-v7a/libksud.so")) != arm32_runtime(archive.read("assets/" + profile["kernelSu"]["ksud"]["asset"])):
                        raise ValueError("Bundled manager and loader must use the same daemon")
            print(f"Verified release APK profile: {profile['id']}")


if __name__ == "__main__":
    verify(sys.argv[1])
