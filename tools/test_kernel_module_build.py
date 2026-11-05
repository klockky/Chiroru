"""Exercise profile/ABI rejection using the actual shipped ELF modules."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("module_build", Path(__file__).with_name("build-kernel-modules.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class ModuleBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def artifact(self, build, name="chiroru_adb.ko"):
        return builder.ROOT / f"app/Chiroru/app/src/main/assets/firmware/chiron-{build}/{name}"

    def release(self, build):
        return builder.profiles()[build]["match"]["kernelRelease"]

    def git(self, directory, *args):
        return subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
             "-c", "commit.gpgsign=false", "-c", "protocol.file.allow=always",
             "-C", str(directory), *args], check=True, capture_output=True, text=True)

    def submodule_tree(self):
        source, parent = self.root / "upstream", self.root / "checkout"
        source.mkdir()
        parent.mkdir()
        self.git(source, "init")
        (source / "kernel").mkdir()
        (source / "kernel/include").mkdir()
        (source / "uapi").mkdir()
        (source / "kernel/include/uapi").symlink_to("../../uapi")
        (source / "kernel/Kbuild").write_text("# kernel build\n")
        (source / "uapi/supercall.h").write_text("/* shared UAPI */\n")
        self.git(source, "add", ".")
        self.git(source, "commit", "-m", "Test upstream")
        self.git(parent, "init")
        self.git(parent, "submodule", "add", str(source), str(builder.KERNEL_SU))
        (parent / "tools").mkdir()
        (parent / "tools/build.py").write_text("# parent build tool\n")
        self.git(parent, "add", "tools")
        return parent

    def test_source_snapshot_includes_submodule_uapi_and_local_edits(self):
        parent = self.submodule_tree()
        source = parent / builder.KERNEL_SU
        (source / "kernel/Kbuild").write_text("# local tracked edit\n")
        (source / "kernel/untracked.ko").write_bytes(b"not source")
        out = self.root / "snapshot"
        with mock.patch.object(builder, "ROOT", parent):
            revision = builder.kernel_source_revision()
            hashes = builder.copy_sources(out)
        self.assertEqual(self.git(source, "rev-parse", "HEAD").stdout.strip(), revision)
        self.assertEqual(set(hashes), {"tools/build.py", "third_party/kernelsu/kernel/Kbuild",
                                       "third_party/kernelsu/kernel/include/uapi",
                                       "third_party/kernelsu/uapi/supercall.h"})
        self.assertEqual((out / builder.KERNEL_SOURCE / "Kbuild").read_text(), "# local tracked edit\n")
        self.assertEqual((out / builder.KERNEL_SOURCE / "include/uapi/supercall.h").read_text(),
                         "/* shared UAPI */\n")
        self.assertFalse((out / builder.KERNEL_SU / ".git").exists())

    def test_modified_uapi_symlink_cannot_escape_source_snapshot(self):
        parent = self.submodule_tree()
        link = parent / builder.KERNEL_SOURCE / "include/uapi"
        link.unlink()
        link.symlink_to(self.root)
        with mock.patch.object(builder, "ROOT", parent):
            with self.assertRaisesRegex(ValueError, "Unexpected source symlink"):
                builder.copy_sources(self.root / "snapshot")

    def test_uninitialized_submodule_is_rejected_before_copy(self):
        parent = self.submodule_tree()
        self.git(parent, "submodule", "deinit", "-f", "--", str(builder.KERNEL_SU))
        out = self.root / "snapshot"
        with mock.patch.object(builder, "ROOT", parent):
            with self.assertRaisesRegex(ValueError, "submodule update --init"):
                builder.copy_sources(out)
        self.assertFalse(out.exists())

    def test_publication_scan_checks_submodule_contents(self):
        parent = self.submodule_tree()
        nested = parent / builder.KERNEL_SOURCE / "Kbuild"
        nested.write_text("/home/" + "private-developer/project/\n")
        result = subprocess.run([sys.executable, str(builder.ROOT / "tools/check-public-files.py")],
                                cwd=parent, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("third_party/kernelsu/kernel/Kbuild", result.stdout)
        self.assertIn("home-directory path", result.stdout)

    def headers(self):
        root = self.root / "headers"
        source = self.root / "source"
        source.mkdir()
        (source / "Makefile").write_text("# test source\n")
        config = "\n".join(f"CONFIG_{key}=y" for key in (
            "ARM64", "64BIT", "MODULES", "MODVERSIONS", "MODULE_UNLOAD", "PREEMPT", "ARM64_4K_PAGES"))
        files = {name: "" for name in builder.HEADER_INPUTS}
        files.update({"Makefile": "# test output\n", "security/selinux/flask.h": "",
                      ".config": config, "include/config/auto.conf": config,
                      "include/generated/autoconf.h": "#define CONFIG_PREEMPT 1\n",
                      "include/config/kernel.release": self.release("5000001307") + "\n",
                      "include/generated/utsrelease.h": f'#define UTS_RELEASE "{self.release("5000001307")}"\n',
                      "Module.symvers": "0x8395960f\tmodule_layout\tvmlinux\tEXPORT_SYMBOL\n"})
        for name, contents in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents)
        (root / "source").symlink_to(source, target_is_directory=True)
        return root

    def test_preemption_mismatch_is_rejected_before_build(self):
        root = self.headers()
        builder.validate_headers(root)
        (root / "include/generated/autoconf.h").write_text("")
        with self.assertRaisesRegex(ValueError, "preemption"):
            builder.validate_headers(root)

    def test_symlinked_release_cannot_retarget_callers_files(self):
        root = self.headers()
        release = root / "include/config/kernel.release"
        external = self.root / "external-release"
        external.write_bytes(release.read_bytes())
        release.unlink()
        release.symlink_to(external)
        with self.assertRaisesRegex(ValueError, "symlink"):
            builder.validate_headers(root)
        self.assertEqual(self.release("5000001307") + "\n", external.read_text())

    def test_shipped_modules_accept_only_their_exact_profile(self):
        for build in builder.BUILDS:
            for name in ("chiroru_adb.ko", "kernelsu.ko"):
                with self.subTest(build=build, name=name):
                    path = self.artifact(build, name)
                    builder.verify_module(path, self.release(build), name == "chiroru_adb.ko")
                    for other in builder.BUILDS:
                        if build != other:
                            with self.assertRaisesRegex(ValueError, "vermagic"):
                                builder.verify_module(path, self.release(other))

    def test_wrong_adb_runtime_guard_is_rejected_even_with_valid_vermagic(self):
        data = self.artifact("5000001245").read_bytes()
        old = self.release("5000001245").encode()
        new = self.release("5000001307").encode()
        # Replace the runtime string only; vermagic is checked independently.
        elf = builder.load_tool("sanitize-module").Elf(data)
        data = bytearray(data)
        for name, section in zip(elf.names, elf.sections):
            if name.startswith(".rodata"):
                contents = elf.contents(section)
                data[section[4]:section[4] + section[5]] = contents.replace(old, new)
        path = self.root / "wrong-guard.ko"
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "runtime release guard"):
            builder.verify_module(path, old.decode(), adb=True)

    def test_wrong_module_layout_crc_is_rejected(self):
        data = bytearray(self.artifact("5000001245").read_bytes())
        elf = builder.load_tool("sanitize-module").Elf(data)
        section = elf.sections[elf.names.index("__versions")]
        struct.pack_into("<Q", data, section[4], 0)
        path = self.root / "wrong-crc.ko"
        path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "module_layout"):
            builder.verify_module(path, self.release("5000001245"), adb=True)

    def test_existing_output_is_preserved(self):
        sentinel = self.root / "keep.txt"
        sentinel.write_text("existing build")
        result = subprocess.run([sys.executable, builder.__file__, "5000001245",
                                 str(self.root / "missing-headers"), str(self.root)],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("already exists", result.stderr)
        self.assertEqual("existing build", sentinel.read_text())

    def test_unknown_firmware_is_rejected_before_creating_output(self):
        out = self.root / "output"
        result = subprocess.run([sys.executable, builder.__file__, "5000001246",
                                 str(self.root / "missing-headers"), str(out)],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
