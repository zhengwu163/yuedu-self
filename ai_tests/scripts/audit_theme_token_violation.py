#!/usr/bin/env python3
"""Audit changed UI files; selection-color hints are advisory, not Kotlin analysis."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / "ai_tests" / "config" / "theme_token_allowlist.json"
COLOR_PATTERNS = (
    re.compile(r"\bColor\s*\(\s*0x[0-9a-fA-F]+"),
    re.compile(r"\bColor\.(?:rgb|argb)\s*\("),
    re.compile(r"\bsetBackgroundColor\s*\("),
)


def git_output(*args: str) -> str:
    """Never turn a failed Git query into an empty (passing) change set."""
    return subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="surrogateescape",
        capture_output=True,
        check=True,
    ).stdout


def resolve_base(base: str) -> str:
    # Resolve a single commit first: reject revision ranges/options and pin HEAD.
    return git_output(
        "rev-parse", "--verify", "--end-of-options", f"{base}^{{commit}}"
    ).strip()


def changed_ui_files(base: str = "HEAD") -> list[Path]:
    # One commit vs working tree includes staged + unstaged net changes.
    tracked = git_output(
        "diff", "--name-only", "-z", "--no-ext-diff", "--no-textconv",
        "--diff-filter=ACMRTUXB", resolve_base(base), "--",
    )
    untracked = git_output("ls-files", "--others", "--exclude-standard", "-z", "--")
    # NUL records preserve Unicode, spaces and newlines in Git path names.
    names = set(tracked.split("\0")) | set(untracked.split("\0"))
    return [
        ROOT / name
        for name in sorted(names)
        if name
        and "/ui/" in name
        and (ROOT / name).is_file()
        and (ROOT / name).suffix in {".kt", ".java", ".xml"}
    ]


def kotlin_code(text: str) -> str:
    """Mask comments/literals, retaining offsets. Not a Kotlin semantic parser."""
    code = list(text)
    i = 0
    while i < len(text):
        start = i
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(text) and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = len(text) if end < 0 else end + 3
        elif text[i] in "\"'`":
            quote = text[i]
            i += 1
            while i < len(text):
                char = text[i]
                i += 1
                if char == "\\" and quote != "`":
                    i += 1
                elif char == quote:
                    break
        else:
            i += 1
            continue
        for offset in range(start, min(i, len(text))):
            if text[offset] != "\n":
                code[offset] = " "
    return "".join(code)


def named_arguments(code: str) -> dict[str, str]:
    """Split ordinary named arguments, ignoring nested calls/lambdas/arrays."""
    depth = 0
    start = 0
    result = {}
    for index, char in enumerate(code + ","):
        if char in "({[":
            depth += 1
        elif char in ")}]":
            depth -= 1
        elif char == "," and depth == 0:
            match = re.match(r"\s*(\w+)\s*=(?!=)(.*)", code[start:index], re.S)
            if match:
                result[match[1]] = match[2].strip()
            start = index + 1
    return result


def selection_candidates(text: str) -> list[tuple[int, int]]:
    """Find direct custom-color calls lacking an inline selection override.

    CompositionLocal inheritance and factory/alias resolution cannot be proven
    from this file. Candidates require manual review, never a blocking verdict.
    """
    code = kotlin_code(text)
    candidates = []
    for field in re.finditer(r"\b(?:OutlinedTextField|BasicTextField)\s*\(", code):
        depth = 1
        end = field.end()
        while end < len(code) and depth:
            if code[end] == "(":
                depth += 1
            elif code[end] == ")":
                depth -= 1
            end += 1
        if depth:
            continue
        args = named_arguments(code[field.end():end - 1])
        colors = re.fullmatch(
            r"(?:androidx\.compose\.material3\.)?"
            r"(?:OutlinedTextFieldDefaults|TextFieldDefaults)\s*\.\s*"
            r"(?:colors|outlinedTextFieldColors)\s*\((.*)\)",
            args.get("colors", ""), re.S,
        )
        color_args = named_arguments(colors[1]) if colors else {}
        # Presence is not proof of a correct value; only avoid claiming it absent.
        if color_args.get("selectionColors", "null") != "null":
            continue
        style = re.fullmatch(
            r"(?:[\w.]+\.)?(?:TextStyle|copy)\s*\((.*)\)",
            args.get("textStyle", ""), re.S,
        )
        style_args = named_arguments(style[1]) if style else {}
        if (
            any(name.endswith("Color") for name in color_args)
            or "cursorBrush" in args
            or "color" in style_args
        ):
            candidates.append((
                code.count("\n", 0, field.start()) + 1,
                code.count("\n", 0, end) + 1,
            ))
    return candidates


def selection_review_lines(path: Path, text: str, revision: str) -> list[int]:
    candidates = selection_candidates(text)
    if not candidates:
        return []
    patch = git_output(
        "diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-textconv",
        "--no-renames", "--text", revision, "--",
        f":(literal){path.relative_to(ROOT).as_posix()}",
    )
    # A discovered file without a tracked diff is untracked: all calls are new.
    if not patch:
        return [start for start, _ in candidates]
    hunks = [
        (int(match[1]), int(match[2]) if match[2] is not None else 1)
        for match in re.finditer(
            r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", patch, re.M
        )
    ]
    return [
        start for start, end in candidates
        if any(
            # A deletion is a gap after line n, e.g. removing selectionColors.
            (start <= line < end) if count == 0
            else (line <= end and line + count - 1 >= start)
            for line, count in hunks
        )
    ]


def changed_line_numbers(path: Path, revision: str) -> set[int]:
    """Return added worktree line numbers for one path; untracked files are all new."""
    patch = git_output(
        "diff", "--unified=0", "--no-color", "--no-ext-diff", "--no-textconv",
        "--no-renames", "--text", revision, "--",
        f":(literal){path.relative_to(ROOT).as_posix()}",
    )
    if not patch:
        return set(range(1, len(path.read_text(encoding="utf-8", errors="replace").splitlines()) + 1))
    changed = set()
    for match in re.finditer(
        r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", patch, re.M
    ):
        start = int(match[1])
        count = int(match[2]) if match[2] is not None else 1
        changed.update(range(start, start + count))
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=False)
    parser.add_argument("--base", default="HEAD")
    args = parser.parse_args()
    try:
        allowlist = json.loads(ALLOWLIST.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"invalid theme allowlist: {exc}", file=sys.stderr)
        return 2
    allowed = set(allowlist.get("paths", [])) if isinstance(allowlist, dict) else set()
    violations: list[str] = []
    reviews: list[str] = []
    try:
        revision = resolve_base(args.base)
        for path in changed_ui_files(revision):
            relative = str(path.relative_to(ROOT))
            text = path.read_text(encoding="utf-8", errors="replace")
            lines = text.splitlines()
            changed_lines = changed_line_numbers(path, revision)
            if relative not in allowed and any(
                pattern.search(lines[line - 1])
                for line in changed_lines
                if 0 < line <= len(lines)
                for pattern in COLOR_PATTERNS
            ):
                violations.append(relative)
            if path.suffix == ".kt":
                reviews.extend(
                    f"{relative}:{line}" for line in selection_review_lines(path, text, revision)
                )
    except subprocess.CalledProcessError as exc:
        print(
            f"theme token audit unavailable: git {exc.cmd[1]} failed "
            f"(exit {exc.returncode})",
            file=sys.stderr,
        )
        return 2
    except OSError as exc:
        print(f"theme token audit unavailable: {exc}", file=sys.stderr)
        return 2
    if reviews:
        print(
            "selection-color review required (advisory): changed direct text fields "
            "customize colors without an inline selectionColors override; "
            "LocalTextSelectionColors scope/inheritance is not verified. "
            "Confirm the provider and theme behavior manually:",
            file=sys.stderr,
        )
        print("\n".join(f"  {item}" for item in reviews), file=sys.stderr)
    if violations:
        print("Changed UI files contain unregistered hard-coded colors:", file=sys.stderr)
        print("\n".join(f"  {item}" for item in violations), file=sys.stderr)
        return 1
    print("theme token audit passed (hard-coded color rules only; selection colors not verified)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
