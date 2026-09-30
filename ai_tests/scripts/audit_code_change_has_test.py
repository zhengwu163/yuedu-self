#!/usr/bin/env python3
"""Fail when changed production code has no changed test counterpart."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TEST_MARKERS = ("/src/test/", "/src/androidTest/", "/test_")
IGNORED_SUFFIXES = (".md", ".json", ".png", ".jpg", ".log")


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


def git_paths(base: str = "HEAD") -> list[str]:
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


def is_test(path: str) -> bool:
    normalized = "/" + path.replace(os.sep, "/")
    return any(marker in normalized for marker in TEST_MARKERS)


def is_production(path: str) -> bool:
    normalized = path.replace(os.sep, "/")
    if normalized.startswith(("app/src/main/", "scripts/novel-audio-")):
        return Path(path).suffix in {".kt", ".java", ".py"}
    return False


def has_matching_test(source: str, tests: list[str]) -> bool:
    stem = Path(source).stem
    if stem.startswith("NovelAudio"):
        candidates = {stem, stem.replace("Service", ""), "NovelAudio"}
    else:
        candidates = {stem}
    source_tail = source.replace(os.sep, "/").split("/src/main/", 1)[-1]
    source_package = Path(source_tail).parent
    for test in tests:
        test_normalized = test.replace(os.sep, "/")
        if any(candidate in Path(test).stem for candidate in candidates):
            return True
        try:
            test_text = without_comments(
                (ROOT / test).read_text(encoding="utf-8", errors="replace")
            )
        except OSError:
            continue
        source_path = source.replace(os.sep, "/")
        source_after_java = source_path.split("/src/main/java/", 1)[-1]
        source_leaf = "/".join(Path(source_after_java).parts[-2:])
        if any(token in test_text for token in (
            source_path,
            source_tail,
            source_after_java,
            source_leaf,
        )):
            return True
        if re.search(rf"\b{re.escape(stem)}\b", test_text):
            return True
        if "/src/test/" in test_normalized or "/src/androidTest/" in test_normalized:
            if str(source_package) in test_normalized:
                return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=False)
    parser.add_argument("--base", default="HEAD")
    args = parser.parse_args()
    try:
        paths = git_paths(args.base)
    except subprocess.CalledProcessError as exc:
        print(
            f"test-pair audit unavailable: git {exc.cmd[2]} failed "
            f"(exit {exc.returncode})",
            file=sys.stderr,
        )
        return 2
    except OSError as exc:
        print(f"test-pair audit unavailable: {exc}", file=sys.stderr)
        return 2
    tests = [path for path in paths if is_test(path)]
    changed_sources = [
        path
        for path in paths
        if is_production(path) and not path.endswith(IGNORED_SUFFIXES)
    ]
    missing = [path for path in changed_sources if not has_matching_test(path, tests)]
    if missing:
        print("Changed production files without a changed test:", file=sys.stderr)
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        return 1
    print(f"test-pair audit passed: {len(changed_sources)} production file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
