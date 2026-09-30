#!/usr/bin/env python3
"""Check changed Gson DTOs with generic fields for explicit @Keep."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def changed_source_paths() -> list[Path]:
    output = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRTUXB"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    ).stdout
    paths = [ROOT / line.strip() for line in output.splitlines() if line.strip()]
    paths.extend(
        ROOT / line[3:].strip()
        for line in subprocess.run(
            ["git", "status", "--short"], cwd=ROOT, text=True, capture_output=True, check=False
        ).stdout.splitlines()
        if line.startswith("?? ")
    )
    return [path for path in paths if path.suffix in {".kt", ".java"} and path.is_file()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=False)
    parser.add_argument("artifacts", nargs="*")
    parser.parse_args()
    failures: list[str] = []
    for path in changed_source_paths():
        text = path.read_text(encoding="utf-8", errors="replace")
        if "List<" not in text and "Map<" not in text and "Set<" not in text:
            continue
        if "Gson" not in text and "gson" not in text and "Json" not in path.name:
            continue
        if "@Keep" not in text:
            failures.append(str(path.relative_to(ROOT)))
    if failures:
        print("Generic Gson candidates missing @Keep:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in failures), file=sys.stderr)
        return 1
    print("Gson generic signature audit passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
