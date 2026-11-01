#!/usr/bin/env python3
"""Check tracked files or APK/ZIP contents for common publication leftovers.

This complements a secret scanner and manual review; upstream author credits
and the public signing certificate are intentionally preserved.
"""
import argparse
import io
from pathlib import Path
import re
import subprocess
import zipfile

HOME_PATH = re.compile(rb'/(?:home|Users)/([A-Za-z0-9._-]+)/')
PRIVATE_KEY = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----')
TOKEN = re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})\b')
UUID = re.compile(rb'\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', re.I)


def check(name, data, errors, depth=0):
    if Path(name).suffix.lower() in {'.keystore', '.jks', '.p12', '.pfx'}:
        errors.add((name, 'signing container'))
    for match in HOME_PATH.finditer(data):
        if match[1] not in {b'runner', b'runneradmin'}:
            errors.add((name, 'developer home-directory path'))
    if PRIVATE_KEY.search(data) or TOKEN.search(data):
        errors.add((name, 'private key or credential pattern'))
    for match in UUID.finditer(data):
        if not match[0].startswith(b'00000000-0000-4000-8000-'):
            errors.add((name, 'non-synthetic UUID; review before publication'))
    # Inspect compressed payloads, not only visible source files.
    if depth < 4 and zipfile.is_zipfile(io.BytesIO(data)):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    check(name + '!' + entry.filename, archive.read(entry), errors, depth + 1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paths', nargs='*', type=Path, help='Default: all Git-tracked files')
    args = parser.parse_args()
    paths = args.paths or [Path(p) for p in subprocess.check_output(
        ['git', 'ls-files', '-z']).decode().split('\0') if p]
    errors = set()
    for path in paths:
        check(str(path), path.read_bytes(), errors)
    for name, reason in sorted(errors):
        print(f'{name}: {reason}')
    if errors:
        raise SystemExit(1)
    print(f'Publication checks passed for {len(paths)} files (including archive contents)')
