"""CLI self-tests in disposable Git repositories; never stage the working project."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "ai_tests/scripts/audit_theme_token_violation.py"
GIT = shutil.which("git")
UI = "app/src/main/java/example/ui/"
SAFE = "val color = palette.surface\n"
HARDCODED = "val color = Color(0xff123456)\n"
OUTLINED = """@Composable
fun Field() {
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        colors = OutlinedTextFieldDefaults.colors(
            cursorColor = palette.accent,
            focusedTextColor = palette.text,
        ),
    )
}
"""
BASIC = """@Composable
fun Field() {
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        textStyle = TextStyle(color = palette.text),
    )
}
"""
REVIEW = "selection-color review required"


class ThemeTokenAuditTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(GIT, "Self-tests require a real Git executable")
        temporary = TemporaryDirectory(prefix="theme-audit-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        # A fixed environment avoids global Git config, credentials and project hooks.
        self.env = {
            "PATH": f"{Path(GIT).parent}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_TERMINAL_PROMPT": "0",
            "LC_ALL": "C",
        }
        self.script = self.write(
            "ai_tests/scripts/audit_theme_token_violation.py",
            AUDIT.read_text(encoding="utf-8"),
        )
        self.allowlist = self.write(
            "ai_tests/config/theme_token_allowlist.json", '{"paths": []}\n'
        )
        self.git("init", "--quiet", "--template=")
        self.write("fixture.txt", "fixture\n")
        self.commit("fixture.txt")

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def git(self, *args):
        return subprocess.run(
            [GIT, "-c", "user.name=Theme Audit Fixture",
             "-c", "user.email=theme-audit@example.invalid",
             "-c", "commit.gpgSign=false",
             "-c", f"core.hooksPath={self.root / 'empty-hooks'}", *args],
            cwd=self.root, env=self.env, capture_output=True, text=True, check=True,
        ).stdout.strip()

    def commit(self, *paths):
        # Commits exist only in this test's TemporaryDirectory, for --base coverage.
        self.git("add", "--", *paths)
        self.git("commit", "--quiet", "-m", "test fixture")

    def audit(self, *args, env=None):
        return subprocess.run(
            [sys.executable, "-B", str(self.script), *args],
            cwd=self.root, env=self.env if env is None else env,
            capture_output=True, text=True, check=False,
        )

    def assert_audit(self, code, *args, env=None):
        result = self.audit(*args, env=env)
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        if code:
            self.assertNotIn("audit passed", result.stdout)
        return result

    def test_unstaged_modified_ui_is_rejected(self):
        self.write(UI + "Field.kt", SAFE)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", HARDCODED)
        self.assert_audit(1)

    def test_staged_modified_ui_is_rejected(self):
        self.write(UI + "Field.kt", SAFE)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", HARDCODED)
        self.git("add", "--", UI + "Field.kt")
        self.assert_audit(1)

    def test_staged_new_ui_is_rejected(self):
        self.write(UI + "Field.kt", HARDCODED)
        self.git("add", "--", UI + "Field.kt")
        self.assert_audit(1)

    def test_untracked_ui_is_rejected(self):
        self.write(UI + "Field.kt", HARDCODED)
        self.assert_audit(1)

    def test_mixed_changes_are_all_reported_once(self):
        for name in ("Staged", "Unstaged", "Both"):
            self.write(UI + name + ".kt", SAFE)
        self.commit(UI)
        for name in ("Staged", "Unstaged", "Both", "Untracked"):
            self.write(UI + name + ".kt", HARDCODED)
        self.git("add", "--", UI + "Staged.kt", UI + "Both.kt")
        self.write(UI + "Both.kt", HARDCODED + "// unstaged too\n")
        result = self.assert_audit(1)
        for name in ("Staged", "Unstaged", "Both", "Untracked"):
            self.assertEqual(1, result.stderr.count(UI + name + ".kt"))

    def test_explicit_base_includes_committed_changes(self):
        base = self.git("rev-parse", "HEAD")
        self.write(UI + "Field.kt", HARDCODED)
        self.commit(UI + "Field.kt")
        self.assert_audit(0, "--base", "HEAD")
        self.assert_audit(1, "--base", base)
        self.assert_audit(1, "--base", "HEAD~1", "--stage", "commit")

    def test_base_and_dirty_tree_are_combined(self):
        self.write(UI + "Committed.kt", SAFE)
        self.commit(UI + "Committed.kt")
        self.write(UI + "Committed.kt", HARDCODED)
        self.commit(UI + "Committed.kt")
        self.write(UI + "Staged.kt", HARDCODED)
        self.git("add", "--", UI + "Staged.kt")
        self.write(UI + "New.kt", HARDCODED)
        result = self.assert_audit(1, "--base", "HEAD~1")
        for name in ("Committed", "Staged", "New"):
            self.assertIn(UI + name + ".kt", result.stderr)

    def test_invalid_base_fails_closed(self):
        self.assert_audit(2, "--base", "missing-base")

    def test_revision_range_is_not_a_single_base(self):
        self.assert_audit(2, "--base", "HEAD..HEAD")

    def test_option_like_base_fails_closed(self):
        self.assert_audit(2, "--base=--all")

    def test_non_repository_fails_closed(self):
        # Keep the fixture outside all repositories without touching the project.
        (self.root / ".git").rename(self.root / "detached-git")
        self.assert_audit(2)

    def test_unborn_head_fails_closed(self):
        self.git("checkout", "--orphan", "unborn", "--quiet")
        self.assert_audit(2)

    def test_missing_git_fails_closed(self):
        self.assert_audit(2, env={**self.env, "PATH": str(self.root / "no-git")})

    def test_untracked_listing_failure_fails_closed(self):
        # Delegate every other command to real Git; fail only the second query.
        wrapper = self.write(
            "bin/git",
            f'#!/bin/sh\nif [ "$1" = "ls-files" ]; then exit 47; fi\n'
            f'exec "{GIT}" "$@"\n',
        )
        wrapper.chmod(0o755)
        self.assert_audit(2, env={**self.env, "PATH": f"{wrapper.parent}:/usr/bin:/bin"})

    def test_tracked_diff_failure_fails_closed(self):
        wrapper = self.write(
            "bin/git",
            f'#!/bin/sh\nif [ "$1" = "diff" ]; then exit 49; fi\n'
            f'exec "{GIT}" "$@"\n',
        )
        wrapper.chmod(0o755)
        result = self.assert_audit(
            2, env={**self.env, "PATH": f"{wrapper.parent}:/usr/bin:/bin"}
        )
        self.assertIn("git diff failed", result.stderr)

    def test_unicode_space_and_newline_paths_are_not_split_or_unquoted(self):
        names = ["设置 页.kt", "line\nbreak.kt", " spaced .kt"]
        for name in names:
            self.write(UI + name, SAFE)
        self.commit(UI)
        for name in names:
            self.write(UI + name, HARDCODED)
        result = self.assert_audit(1)
        for name in names:
            self.assertIn(UI + name, result.stderr)

    def test_staged_rename_checks_destination(self):
        self.write(UI + "Old.kt", HARDCODED)
        self.commit(UI + "Old.kt")
        self.git("mv", "--", UI + "Old.kt", UI + "Renamed.kt")
        self.assertIn(UI + "Renamed.kt", self.assert_audit(1).stderr)

    def test_ignored_non_ui_and_untouched_history_are_not_scanned(self):
        self.write(UI + "Historical.kt", HARDCODED)
        self.write(UI + "Deleted.kt", HARDCODED)
        self.write(".gitignore", "Ignored.kt\n")
        self.commit(UI, ".gitignore")
        self.git("rm", "--", UI + "Deleted.kt")
        self.write(UI + "Ignored.kt", HARDCODED)
        self.write(UI + "Notes.txt", HARDCODED)
        self.write("app/src/main/java/example/model/Model.kt", HARDCODED)
        self.write(UI + "Safe.kt", SAFE)
        self.assert_audit(0)

    def test_worktree_content_is_used_after_staging(self):
        self.write(UI + "Field.kt", SAFE)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", HARDCODED)
        self.git("add", "--", UI + "Field.kt")
        self.write(UI + "Field.kt", SAFE)
        self.assert_audit(0)

    def test_existing_patterns_and_extensions_remain_blocking(self):
        for name, content in (
            ("Color.kt", "val c = Color(0xFF112233)\n"),
            ("Rgb.java", "Color.rgb(1, 2, 3);\n"),
            ("Argb.java", "Color.argb(4, 1, 2, 3);\n"),
            ("Background.xml", "setBackgroundColor(themeColor)\n"),
        ):
            self.write(UI + name, SAFE)
            self.commit(UI + name)
            self.write(UI + name, content)
            with self.subTest(name=name):
                self.assertIn(UI + name, self.assert_audit(1).stderr)
            self.write(UI + name, SAFE)

    def test_unchanged_hardcoded_color_in_changed_file_is_not_rejected(self):
        self.write(UI + "Field.kt", HARDCODED)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", HARDCODED + "// unrelated edit\n")
        self.assert_audit(0)

    def test_existing_allowlist_contract_is_preserved_in_fixture(self):
        name = UI + "Allowed.kt"
        self.write(name, SAFE)
        self.commit(name)
        self.write(name, HARDCODED)
        self.allowlist.write_text(json.dumps({"paths": [name]}), encoding="utf-8")
        self.assert_audit(0)
        self.allowlist.write_text('{"paths": []}', encoding="utf-8")
        self.assert_audit(1)

    def test_invalid_allowlist_fails_closed(self):
        self.allowlist.write_text("{", encoding="utf-8")
        self.assert_audit(2)

    def test_untracked_custom_outlined_requests_manual_selection_review(self):
        self.write(UI + "Field.kt", OUTLINED)
        result = self.assert_audit(0)
        self.assertIn(REVIEW, result.stderr)
        self.assertIn(UI + "Field.kt:3", result.stderr)
        self.assertIn("LocalTextSelectionColors", result.stderr)
        self.assertIn("not verified", result.stderr)

    def test_staged_custom_basic_requests_manual_selection_review(self):
        self.write(UI + "Field.kt", BASIC)
        self.git("add", "--", UI + "Field.kt")
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_selection_review_honors_explicit_base(self):
        self.write(UI + "Field.kt", OUTLINED)
        self.commit(UI + "Field.kt")
        self.assertNotIn(REVIEW, self.assert_audit(0).stderr)
        self.assertIn(REVIEW, self.assert_audit(0, "--base", "HEAD~1").stderr)

    def test_changed_argument_with_unchanged_call_line_is_reviewed(self):
        self.write(UI + "Field.kt", OUTLINED)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", OUTLINED.replace("palette.text", "palette.body"))
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_unchanged_historical_field_in_changed_file_is_not_reviewed(self):
        self.write(UI + "Field.kt", OUTLINED + "\nval extra = 1\n")
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", OUTLINED + "\nval extra = 2\n")
        self.assertNotIn(REVIEW, self.assert_audit(0).stderr)

    def test_inline_selection_colors_is_not_reported_missing(self):
        source = OUTLINED.replace(
            "cursorColor =",
            "selectionColors = TextSelectionColors(palette.accent, palette.selection),\n"
            "            cursorColor =",
        )
        self.write(UI + "Field.kt", source)
        self.assertNotIn(REVIEW, self.assert_audit(0).stderr)

    def test_null_selection_colors_still_needs_review(self):
        self.write(UI + "Field.kt", OUTLINED.replace(
            "cursorColor =", "selectionColors = null,\n            cursorColor ="
        ))
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_sibling_field_selection_does_not_suppress_missing_override(self):
        first = OUTLINED.replace(
            "cursorColor =", "selectionColors = palette.selection,\n            cursorColor ="
        )
        self.write(UI + "Field.kt", first + "\n" + BASIC)
        result = self.assert_audit(0)
        expected_line = first.count("\n") + 4
        self.assertIn(UI + f"Field.kt:{expected_line}", result.stderr)
        self.assertEqual(1, result.stderr.count(UI + "Field.kt:"))

    def test_existing_color_allowlist_does_not_silence_selection_advisory(self):
        name = UI + "Field.kt"
        self.allowlist.write_text(json.dumps({"paths": [name]}), encoding="utf-8")
        self.write(name, OUTLINED + HARDCODED)
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_external_colors_factory_is_outside_advisory_scope(self):
        self.write(UI + "Field.kt", (
            "OutlinedTextField(value = value, onValueChange = {}, colors = appColors())\n"
        ))
        result = self.assert_audit(0)
        self.assertNotIn(REVIEW, result.stderr)
        self.assertIn("selection colors not verified", result.stdout)

    def test_selection_override_removal_is_reviewed(self):
        source = OUTLINED.replace(
            "cursorColor =", "selectionColors = palette.selection,\n            cursorColor ="
        )
        self.write(UI + "Field.kt", source)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", OUTLINED)
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_unrelated_selection_mentions_do_not_certify_field(self):
        self.write(UI + "Field.kt", (
            "// selectionColors = palette.selection\n"
            "val hint = \"selectionColors = pretend )\"\n"
            "/* outer /* LocalTextSelectionColors */ selectionColors = fake */\n"
            + OUTLINED
            + "\nval other = OutlinedTextFieldDefaults.colors(selectionColors = otherColors)\n"
        ))
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_provider_inheritance_is_reported_as_unverified_not_a_violation(self):
        self.write(UI + "Field.kt", (
            "CompositionLocalProvider(LocalTextSelectionColors provides customSelection) {\n"
            + BASIC + "\n}\n"
        ))
        result = self.assert_audit(0)
        self.assertIn(REVIEW, result.stderr)
        self.assertIn("not verified", result.stderr)
        self.assertNotIn("unregistered hard-coded", result.stderr)

    def test_default_fields_need_no_custom_color_review(self):
        self.write(UI + "Field.kt", (
            "OutlinedTextField(value = value, onValueChange = {})\n"
            "BasicTextField(value = value, onValueChange = {}, textStyle = TextStyle())\n"
        ))
        self.assertNotIn(REVIEW, self.assert_audit(0).stderr)

    def test_comments_and_strings_are_not_field_calls(self):
        self.write(UI + "Field.kt", (
            '// BasicTextField(cursorBrush = brush)\n'
            'val example = "BasicTextField(cursorBrush = brush)"\n'
            'val raw = """OutlinedTextField(colors = OutlinedTextFieldDefaults.colors('
            'cursorColor = palette.accent))"""\n'
            '/* nested /* comment */ BasicTextField(cursorBrush = brush) */\n'
        ))
        self.assertNotIn(REVIEW, self.assert_audit(0).stderr)

    def test_nested_calls_strings_and_lambdas_do_not_truncate_field(self):
        self.write(UI + "Field.kt", OUTLINED.replace(
            "value = value,",
            'value = transform(value, ")"),\n'
            '        label = { Text(")") },',
        ).replace("palette.accent", "palette.accent.copy(alpha = opacity())"))
        self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_cursor_brush_and_copied_text_style_are_explicit_customization(self):
        for style in (
            "cursorBrush = SolidColor(palette.accent)",
            "textStyle = LocalTextStyle.current.copy(color = palette.text)",
        ):
            with self.subTest(style=style):
                self.write(UI + "Field.kt", f"BasicTextField({style})\n")
                self.assertIn(REVIEW, self.assert_audit(0).stderr)

    def test_selection_review_does_not_weaken_hardcoded_color_failure(self):
        self.write(UI + "Field.kt", OUTLINED + HARDCODED)
        result = self.assert_audit(1)
        self.assertIn(REVIEW, result.stderr)
        self.assertIn("unregistered hard-coded", result.stderr)

    def test_selection_diff_query_failure_fails_closed(self):
        self.write(UI + "Field.kt", OUTLINED)
        self.commit(UI + "Field.kt")
        self.write(UI + "Field.kt", OUTLINED.replace("palette.text", "palette.body"))
        wrapper = self.write(
            "bin/git",
            '#!/bin/sh\nfor arg in "$@"; do\n'
            '  if [ "$arg" = "--unified=0" ]; then exit 48; fi\ndone\n'
            f'exec "{GIT}" "$@"\n',
        )
        wrapper.chmod(0o755)
        self.assert_audit(2, env={**self.env, "PATH": f"{wrapper.parent}:/usr/bin:/bin"})


if __name__ == "__main__":
    unittest.main()
