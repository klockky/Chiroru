#!/usr/bin/env python3
"""Reject an inconsistent prepared kernel tree before building modules."""

import argparse
import re
import sys
from pathlib import Path


OPTIONS = ("CONFIG_PREEMPT", "CONFIG_PREEMPT_COUNT", "CONFIG_PREEMPTION",
           "CONFIG_DEBUG_PREEMPT", "CONFIG_TRACE_PREEMPT_TOGGLE")


def check(config: str, autoconf: str) -> list[str]:
    errors = []
    for option in OPTIONS:
        expected = bool(re.search(rf"^{option}=y$", config, re.MULTILINE))
        actual = bool(re.search(rf"^#define\s+{option}\s+1\s*$", autoconf, re.MULTILINE))
        if expected != actual:
            errors.append(f"{option}: auto.conf={'y' if expected else 'n'}, "
                          f"autoconf.h={'y' if actual else 'n'}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kernel_build", type=Path)
    root = parser.parse_args().kernel_build
    try:
        config = (root / "include/config/auto.conf").read_text()
        autoconf = (root / "include/generated/autoconf.h").read_text()
    except OSError as error:
        print(f"Cannot read prepared kernel configuration: {error}", file=sys.stderr)
        return 2
    errors = check(config, autoconf)
    if errors:
        print("Inconsistent preemption configuration; do not build a module:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print("Preemption configuration agrees (this does not validate the rest of the kernel ABI).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
