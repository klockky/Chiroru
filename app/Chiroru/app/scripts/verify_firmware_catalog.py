#!/usr/bin/env python3
"""Check the packaged profile/backend/payload contract before any APK build."""
import hashlib
import json
import re
from pathlib import Path


def verify(app: Path) -> None:
    assets = app / "src/main/assets"
    catalog = json.loads((assets / "firmware/profiles.json").read_text())
    if catalog.get("schemaVersion") != 3 or not catalog.get("profiles"):
        raise ValueError("Missing profiles or unsupported catalog schema")
    registry = (app / "src/main/cpp/backend_registry.def").read_text()
    backends = re.findall(r'^CHIRORU_BACKEND\("([a-z0-9-]+)"', registry, re.M)
    if len(backends) != len(set(backends)):
        raise ValueError("Duplicate native backend ID")
    ids, identities = set(), set()
    fields = {"device", "model", "fingerprint", "incremental", "sdk", "kernelRelease", "machine", "pageSize"}
    for profile in catalog["profiles"]:
        name = profile["id"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name) or name in ids:
            raise ValueError(f"Invalid or duplicate firmware ID: {name}")
        ids.add(name)
        root = profile.get("root")
        if not isinstance(root, dict) or set(root) != {"backend"}:
            raise ValueError(f"Incomplete root support: {name}")
        if not profile["label"].strip() or root["backend"] not in backends:
            raise ValueError(f"Missing label/native backend: {name}")
        if set(profile) - {"id", "label", "match", "root", "kernelSu", "adbRoot"}:
            raise ValueError(f"Unknown profile fields: {name}")
        match = profile["match"]
        if set(match) != fields:
            raise ValueError(f"Incomplete/unknown identity fields: {name}")
        for key, value in match.items():
            valid = (type(value) is int and value > 0) if key in {"sdk", "pageSize"} else (
                isinstance(value, str) and bool(value.strip()) and "*" not in value)
            if not valid:
                raise ValueError(f"Identity must be exact and nonempty: {name}/{key}")
        identity = json.dumps(match, sort_keys=True)
        if identity in identities:
            raise ValueError(f"Ambiguous firmware identity: {name}")
        identities.add(identity)
        for key, label, kinds in (("kernelSu", "KernelSU", ("module", "ksud")),
                                  ("adbRoot", "ADB root", ("module", "loader", "client"))):
            support = profile.get(key)
            if support is None:
                continue
            expected = {"backend", *kinds} | ({"manager"} if key == "kernelSu" else set())
            if not isinstance(support, dict) or set(support) != expected:
                raise ValueError(f"Incomplete {label} support: {name}")
            if support["backend"] not in backends:
                raise ValueError(f"Missing {label} native backend: {name}")
            artifacts = {kind: support[kind] for kind in kinds}
            if key == "kernelSu":
                manager = support["manager"]
                source = manager.get("source")
                required = {"source", "packageName", "signerSha256", "versionCode"}
                if type(manager.get("versionCode")) is not int or manager["versionCode"] <= 0:
                    raise ValueError(f"Invalid manager version: {name}")
                if source == "BUNDLED":
                    required |= {"apk"}
                    artifacts["manager"] = manager["apk"]
                elif source != "OFFICIAL_RELEASE":
                    raise ValueError(f"Unknown manager source: {name}")
                if set(manager) != required or not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+", manager.get("packageName", "")):
                    raise ValueError(f"Incomplete manager identity: {name}")
                if not re.fullmatch(r"[a-f0-9]{64}", manager["signerSha256"]):
                    raise ValueError(f"Invalid manager certificate: {name}")
            for kind, artifact in artifacts.items():
                path = artifact["asset"]
                if not all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", p) for p in path.split("/")):
                    raise ValueError(f"Invalid asset path: {path}")
                data = (assets / path).read_bytes()
                if not data or hashlib.sha256(data).hexdigest() != artifact["sha256"]:
                    raise ValueError(f"Payload hash mismatch: {name}/{kind}")
        print(f"Verified firmware profile: {name} (root, optional KernelSU/ADB root)")


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[1])
