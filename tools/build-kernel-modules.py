#!/usr/bin/env python3
"""Build exact-profile modules in a new directory from a prepared kernel tree."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/Chiroru/app/src/main/assets/firmware/profiles.json"
BUILDS = ("5000001307", "5000001245", "1982735225", "5000001248")
CRC = 0x8395960f
HEADER_INPUTS = (".config", "include/config/auto.conf",
                 "include/generated/autoconf.h", "include/generated/utsrelease.h",
                 "include/config/kernel.release", "Module.symvers")


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def profiles():
    return {p["match"]["incremental"]: p for p in json.loads(CATALOG.read_text())["profiles"]}


def validate_headers(headers):
    for name in (*HEADER_INPUTS, "Makefile", "security/selinux/flask.h"):
        if not (headers / name).is_file():
            raise ValueError(f"Missing prepared kernel input: {name}")
    release = (headers / "include/config/kernel.release").read_text().strip()
    known = {profiles()[build]["match"]["kernelRelease"] for build in BUILDS}
    uts = (headers / "include/generated/utsrelease.h").read_text()
    if release not in known or not re.search(
            rf'^#define UTS_RELEASE "{re.escape(release)}"$', uts, re.M):
        raise ValueError("Prepared tree must have a consistent, supported kernel release")
    config = (headers / "include/config/auto.conf").read_text()
    for option in ("CONFIG_ARM64", "CONFIG_64BIT", "CONFIG_MODULES",
                   "CONFIG_MODVERSIONS", "CONFIG_MODULE_UNLOAD", "CONFIG_PREEMPT"):
        if f"{option}=y" not in config.splitlines():
            raise ValueError(f"Prepared tree requires {option}=y")
    if "CONFIG_ARM64_4K_PAGES=y" not in config.splitlines():
        raise ValueError("Prepared tree requires 4096-byte pages")
    entries = [line.split() for line in (headers / "Module.symvers").read_text().splitlines()]
    layout = [int(e[0], 16) for e in entries if len(e) >= 2 and e[1] == "module_layout"]
    if layout != [CRC]:
        raise ValueError("module_layout CRC must be exactly 0x8395960f")
    check = load_tool("check-kernel-preempt-config")
    errors = check.check(config, (headers / "include/generated/autoconf.h").read_text())
    if errors:
        raise ValueError("Inconsistent preemption configuration: " + "; ".join(errors))
    # Generated inputs must be private regular files in the copied tree.
    # A symlink here could make retargeting/make write into the caller's tree.
    for path in headers.rglob("*"):
        if path.is_symlink() and path.relative_to(headers) != Path("source"):
            raise ValueError(f"Unsupported prepared-tree symlink: {path.relative_to(headers)}")
    source = headers / "source"
    if not source.is_symlink() or not source.is_dir() or not (source / "Makefile").is_file():
        raise ValueError("Prepared tree needs a working source link to the full kernel source")
    return release


def verify_module(path, release, adb=False):
    elf = load_tool("sanitize-module").Elf(path.read_bytes())
    sections = dict(zip(elf.names, elf.sections))
    modinfo = elf.contents(sections[".modinfo"]).split(b"\0")
    expected = f"vermagic={release} SMP preempt mod_unload modversions aarch64".encode()
    if expected not in modinfo:
        raise ValueError(f"{path.name}: unexpected vermagic")
    versions = elf.contents(sections["__versions"])
    if len(versions) % 64:
        raise ValueError(f"{path.name}: malformed module versions")
    layout = [struct.unpack_from("<Q", versions, i)[0]
              for i in range(0, len(versions), 64)
              if versions[i + 8:i + 64].split(b"\0", 1)[0] == b"module_layout"]
    if layout != [CRC]:
        raise ValueError(f"{path.name}: unexpected module_layout CRC")
    if adb:
        rodata = b"\0".join(elf.contents(s) for name, s in sections.items()
                            if name.startswith(".rodata"))
        releases = set(re.findall(rb"5\.4\.125-android11-2-g[0-9a-f]+-ab[0-9]+", rodata))
        if releases != {release.encode()}:
            raise ValueError("ADB runtime release guard does not match the selected profile")
    return {"sha256": digest(path), "vermagic": expected.decode().split("=", 1)[1],
            "module_layout": f"0x{CRC:08x}"}


def copy_sources(destination):
    # Only tracked source, but use working-tree contents so local edits are built.
    files = subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-files", "-z", "build-txn", "adb-root", "tools"]
    ).decode().split("\0")
    hashes = {}
    for name in filter(None, files):
        src = ROOT / name
        if src.is_symlink():
            raise ValueError(f"Unexpected source symlink: {name}")
        dst = destination / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        hashes[name] = digest(src)
    return hashes


def build(args):
    profile = profiles()[args.firmware]
    release = profile["match"]["kernelRelease"]
    headers, out = args.headers.resolve(), args.output.resolve()
    if out.exists():
        raise ValueError("Output directory already exists; choose a new directory")
    if out == headers or headers in out.parents or out in headers.parents:
        raise ValueError("Output and prepared kernel tree must be separate")
    # Kbuild and KCFLAGS cannot reliably handle whitespace in these paths.
    if any(re.search(r"\s", str(p)) for p in (ROOT, headers, out, (headers / "source").resolve())):
        raise ValueError("Kbuild paths must not contain whitespace")
    base_release = validate_headers(headers)
    for tool in ("git", "make", "patch", args.cross_compile + "gcc", args.cross_compile + "objcopy"):
        if not shutil.which(tool):
            raise ValueError(f"Missing build tool: {tool}")
    run(sys.executable, ROOT / "tools/gen_policy_writer.py", ROOT / "linux-txn/source",
        ROOT / "build-txn/selinux", "--check")
    out.mkdir(parents=True)
    work = out / "work"
    work.mkdir()
    source_hashes = copy_sources(work)
    # Work on a private copy. The full kernel source remains an external input.
    prepared = work / "headers"
    shutil.copytree(headers, prepared, symlinks=True)
    (prepared / "source").unlink()
    (prepared / "source").symlink_to((headers / "source").resolve(), target_is_directory=True)
    (prepared / "include/config/kernel.release").write_text(release + "\n")
    (prepared / "include/generated/utsrelease.h").write_text(f'#define UTS_RELEASE "{release}"\n')
    target = work / "target.h"
    target.write_text(f'#define CHIRORU_KERNEL_RELEASE "{release}"\n')
    factory = args.firmware == "1982735225"
    build_ksu = args.module in ("all", "ksu")
    if factory and build_ksu:
        run("patch", "--batch", "--fuzz=0", "-d", work / "build-txn", "-p1",
            "-i", ROOT / "tools/factory/kernel.patch")
    # Map private paths before compilation; sanitization also checks the result.
    flags = (f"-ffile-prefix-map={work}=/src/Chiroru "
             f"-ffile-prefix-map={(headers / 'source').resolve()}=/src/linux "
             f"-ffile-prefix-map={ROOT}=/src/Chiroru")
    common = ["make", "-C", prepared, "ARCH=arm64", f"CROSS_COMPILE={args.cross_compile}",
              "KBUILD_MODPOST_WARN=1"]
    modules = {}
    if args.module in ("all", "adb"):
        run(*common, f"M={work / 'adb-root/module'}",
            f"KCFLAGS={flags} -include {target}", "modules")
        modules["chiroru_adb.ko"] = work / "adb-root/module/chiroru_adb.ko"
    if build_ksu:
        kflags = f"{flags} -include {work / 'tools/ksu54-shim.h'} -DKSU_OPTIONAL_SECCOMP_FILTER_RELEASE"
        options = ["CONFIG_KSU=m", "CONFIG_KSU_SYSCALL_TABLE_HOOK=y",
                   "KSU_VERSION_OVERRIDE=34000",
                   f"KSU_VERSION_TAG_OVERRIDE={'v3.4.2-factory' if factory else 'v3.4.0-legacy'}"]
        if factory:
            kflags += " -DKSU_FACTORY_COMPAT"
            manager_hash = os.environ.get("KSU_NEXT_MANAGER_HASH", profile["kernelSu"]["manager"]["signerSha256"])
            manager_size = os.environ.get("KSU_NEXT_MANAGER_SIZE", "0x324")
            if not re.fullmatch(r"[0-9a-f]{64}", manager_hash) or not re.fullmatch(r"0x[0-9a-fA-F]+", manager_size):
                raise ValueError("Invalid factory manager certificate digest or DER size")
            options += ["KSU_MANAGER_PACKAGE=com.rifsxd.ksunext",
                        f"KSU_NEXT_MANAGER_HASH={manager_hash}", f"KSU_NEXT_MANAGER_SIZE={manager_size}"]
        run(*common, f"M={work / 'build-txn'}", *options, f"KCFLAGS={kflags}", "modules")
        modules["kernelsu.ko"] = work / "build-txn/kernelsu.ko"
    manifest = {"firmware": args.firmware, "kernelRelease": release,
                "baseKernelRelease": base_release,
                "compiler": subprocess.check_output([args.cross_compile + "gcc", "--version"], text=True).splitlines()[0],
                "builderSha256": digest(Path(__file__)),
                "catalogSha256": digest(CATALOG),
                "sourceFiles": source_hashes,
                "headerInputs": {name: digest(headers / name) for name in HEADER_INPUTS},
                "modules": {}}
    if factory and build_ksu:
        manifest["manager"] = {"signerSha256": manager_hash, "certificateSize": manager_size}
    for name, source in modules.items():
        run(sys.executable, ROOT / "tools/sanitize-module.py", source, out / name,
            "--objcopy", args.cross_compile + "objcopy")
        manifest["modules"][name] = verify_module(out / name, release, name == "chiroru_adb.ko")
    (out / "build-info.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (out / "SHA256SUMS").write_text("".join(
        f"{digest(out / name)}  {name}\n" for name in (*modules, "build-info.json")))
    run(sys.executable, ROOT / "tools/check-public-files.py",
        *(out / name for name in (*modules, "build-info.json", "SHA256SUMS")))
    print(f"Built {args.firmware}: {', '.join(modules)} in {out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", choices=BUILDS)
    parser.add_argument("headers", type=Path, help="Prepared kernel output tree with a working source link")
    parser.add_argument("output", type=Path, help="New output directory (must not exist)")
    parser.add_argument("--module", choices=("all", "adb", "ksu"), default="all")
    parser.add_argument("--cross-compile", default="aarch64-linux-gnu-")
    args = parser.parse_args()
    try:
        build(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Build failed: {error}\n")


if __name__ == "__main__":
    main()
