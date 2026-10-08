#!/usr/bin/env python3
"""Check changed Gson DTOs with generic fields for explicit @Keep."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def is_gson_generic_candidate(path: Path, text: str) -> bool:
    """Return true only for production DTO declarations, not consumers/tests."""
    try:
        relative = path.relative_to(ROOT)
    except ValueError:
        return False
    if "src" not in relative.parts or "main" not in relative.parts:
        return False
    if "List<" not in text and "Map<" not in text and "Set<" not in text:
        return False
    if "data class" not in text and "@SerializedName" not in text:
        return False
    return "Gson" in text or "gson" in text or "Json" in path.name


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
        if is_gson_generic_candidate(path, text) and "@Keep" not in text:
            failures.append(str(path.relative_to(ROOT)))
    if failures:
        print("Generic Gson candidates missing @Keep:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in failures), file=sys.stderr)
        return 1
    print("Gson generic signature audit passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
