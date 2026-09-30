#!/usr/bin/env python3
"""Check changed host screens have a changed test or explicit host test."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def without_comments(text: str) -> str:
    """Keep string literals, but remove source comments before reference matching."""
    result: list[str] = []
    index = 0
    quote: str | None = None
    while index < len(text):
        if quote is not None:
            if text.startswith(quote, index):
                result.append(quote)
                index += len(quote)
                quote = None
            elif quote in {'"', "'"} and text[index] == "\\" and index + 1 < len(text):
                result.extend((text[index], text[index + 1]))
                index += 2
            else:
                result.append(text[index])
                index += 1
            continue
        if text.startswith("//", index):
            newline = text.find("\n", index)
            index = len(text) if newline < 0 else newline
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            index = len(text) if end < 0 else end + 2
            continue
        if text[index] == "#":
            newline = text.find("\n", index)
            index = len(text) if newline < 0 else newline
            continue
        if text.startswith('"""', index) or text.startswith("'''", index):
            quote = text[index:index + 3]
            result.append(quote)
            index += 3
            continue
        if text[index] in {'"', "'", "`"}:
            quote = text[index]
            result.append(quote)
            index += 1
            continue
        result.append(text[index])
        index += 1
    return "".join(result)


def git_output(*args: str) -> str:
    """Never turn a failed Git query into an empty, passing change set."""
    output = subprocess.run(
        ["git", "--literal-pathspecs", *args],
        cwd=ROOT,
        capture_output=True,
        check=True,
    ).stdout
    # Avoid universal-newline translation: even CR is a valid path byte.
    return output.decode("utf-8", errors="surrogateescape")


def changed_paths(base: str = "HEAD") -> list[str]:
    # Resolve one commit, rejecting ranges/options and pinning HEAD for the diff.
    revision = git_output(
        "rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}"
    ).strip()
    # Commit vs worktree is the net staged + unstaged diff, not their union.
    tracked = git_output(
        "diff", "--name-only", "-z", "--no-ext-diff", "--no-textconv",
        "--diff-filter=ACMRTUXB", revision, "--",
    )
    untracked = git_output("ls-files", "--others", "--exclude-standard", "-z", "--")
    # NUL preserves path bytes; let Git apply ignores instead of walking folders.
    names = set(tracked.split("\0")) | set(untracked.split("\0"))
    return [name for name in sorted(names) if name and (ROOT / name).is_file()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=False)
    parser.add_argument("--base", default="HEAD")
    args = parser.parse_args()
    try:
        paths = changed_paths(args.base)
    except subprocess.CalledProcessError as exc:
        print(
            f"host refresh audit unavailable: git {exc.cmd[2]} failed "
            f"(exit {exc.returncode})",
            file=sys.stderr,
        )
        return 2
    except OSError as exc:
        print(f"host refresh audit unavailable: {exc}", file=sys.stderr)
        return 2
    tests = [path for path in paths if "/src/test/" in path or "/src/androidTest/" in path]
    hosts = [
        path for path in paths
        if "/ui/" in path
        and Path(path).suffix in {".kt", ".java"}
        and any(token in Path(path).stem for token in ("Activity", "Fragment", "Screen", "Dialog"))
    ]
    def has_reference(source: str, test: str) -> bool:
        stem = Path(source).stem
        test_stem = Path(test).stem
        base = re.sub(r"(Activity|Fragment|Screen|Dialog)$", "", stem)
        if re.fullmatch(
            rf"{re.escape(base)}(?:Activity|Fragment|Screen|Dialog)?"
            rf"[A-Za-z0-9_]*(?:Test|Tests|DeviceTest|UiTest)",
            test_stem,
        ):
            return True
        source_normalized = source.replace("\\", "/")
        source_after_java = source_normalized.split("/src/main/java/", 1)[-1]
        source_leaf = "/".join(Path(source_after_java).parts[-2:])
        try:
            content = without_comments(
                (ROOT / test).read_text(encoding="utf-8", errors="replace")
            )
        except OSError:
            return False
        if any(token in content for token in (
            source_normalized,
            source_after_java,
            source_leaf,
        )):
            return True
        return re.search(rf"\b{re.escape(stem)}\b", content) is not None

    missing = [path for path in hosts if not any(has_reference(path, test) for test in tests)]
    if missing:
        print("Changed host screens without a matching test:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in missing), file=sys.stderr)
        return 1
    print(f"host refresh audit passed: {len(hosts)} host screen(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
