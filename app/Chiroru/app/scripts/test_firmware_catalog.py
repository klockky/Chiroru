import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_firmware_catalog import verify


class FirmwareCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.app = Path(self.temporary.name)
        self.assets = self.app / "src/main/assets"
        (self.assets / "firmware").mkdir(parents=True)
        native = self.app / "src/main/cpp"
        native.mkdir(parents=True)
        (native / "backend_registry.def").write_text(
            'CHIRORU_BACKEND("root-backend", probe, root)\n'
            'CHIRORU_BACKEND("ksu-backend", probe, root_and_module)\n')
        shipped = Path(__file__).resolve().parents[1] / "src/main/assets/firmware/profiles.json"
        self.catalog = json.loads(shipped.read_text())
        self.profile = self.catalog["profiles"][0]
        self.profile.pop("adbRoot", None)
        self.profile["root"]["backend"] = "root-backend"
        self.profile["kernelSu"]["backend"] = "ksu-backend"
        for kind in ("module", "ksud"):
            content = f"fixture {kind}".encode()
            self.profile["kernelSu"][kind] = {"asset": kind, "sha256": hashlib.sha256(content).hexdigest()}
            (self.assets / kind).write_bytes(content)

    def check(self):
        (self.assets / "firmware/profiles.json").write_text(json.dumps(self.catalog))
        verify(self.app)

    def test_root_only_needs_no_module_or_cli_files(self):
        self.profile.pop("kernelSu")
        (self.assets / "module").unlink()
        (self.assets / "ksud").unlink()
        self.check()
        self.profile["kernelSu"] = None
        self.check()

    def test_adb_support_without_kernel_su_validates_all_three_assets(self):
        support = self.profile.pop("kernelSu")
        self.profile["adbRoot"] = {"backend": "root-backend", "module": support["module"],
                                   "loader": support["ksud"], "client": support["ksud"]}
        self.check()
        self.profile["adbRoot"].pop("client")
        with self.assertRaisesRegex(ValueError, "Incomplete ADB root support"):
            self.check()

    def test_full_support_checks_both_payloads(self):
        self.check()
        for kind in ("module", "ksud"):
            with self.subTest(kind=kind):
                path = self.assets / kind
                content = path.read_bytes()
                path.write_bytes(b"wrong payload")
                with self.assertRaisesRegex(ValueError, "Payload hash mismatch"):
                    self.check()
                path.write_bytes(content)

    def test_declared_kernel_su_requires_its_own_compiled_backend(self):
        self.profile["kernelSu"]["backend"] = "not-compiled"
        with self.assertRaisesRegex(ValueError, "Missing KernelSU native backend"):
            self.check()

    def test_partial_kernel_su_is_not_silently_treated_as_root_only(self):
        self.profile["kernelSu"].pop("ksud")
        with self.assertRaisesRegex(ValueError, "Incomplete KernelSU support"):
            self.check()

    def test_multiple_firmwares_can_share_root_backend(self):
        other = copy.deepcopy(self.profile)
        other["id"] = "another-build"
        other["match"]["incremental"] = "another-build"
        other.pop("kernelSu")
        self.catalog["profiles"].append(other)
        self.check()

    def test_old_schema_and_unknown_root_backend_fail(self):
        self.catalog["schemaVersion"] = 1
        with self.assertRaisesRegex(ValueError, "unsupported catalog schema"):
            self.check()
        self.catalog["schemaVersion"] = 2
        self.profile["root"]["backend"] = "not-compiled"
        with self.assertRaisesRegex(ValueError, "Missing label/native backend"):
            self.check()


if __name__ == "__main__":
    unittest.main()
