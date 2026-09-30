"""Run both gate CLIs in disposable Git repositories, never in the project."""

from __future__ import annotations

import shlex
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "ai_tests/scripts"
GIT = shutil.which("git")
UI = "app/src/main/java/example/ui/"
TESTS = "app/src/test/java/checks/"
SOURCE = UI + "ReaderActivity.kt"
TEST = TESTS + "ReaderActivityTest.kt"
ORIGINAL = "class ReaderActivity\n"
CHANGED = "class ReaderActivity { val changed = true }\n"


class ChangeDiscoveryCases:
    """The same observable contract must hold for each executable gate."""

    def setUp(self):
        self.assertIsNotNone(GIT, "Self-tests require a real Git executable")
        temporary = TemporaryDirectory(prefix="change-discovery-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        # No inherited env, user Git config, credentials, templates or hooks.
        self.env = {
            "PATH": f"{Path(GIT).parent}:/usr/bin:/bin",
            "HOME": str(self.root),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_CEILING_DIRECTORIES": str(self.root.parent),
            "LC_ALL": "C",
        }
        self.script = self.write(
            "ai_tests/scripts/" + self.GATE,
            (SCRIPTS / self.GATE).read_text(encoding="utf-8"),
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
            [GIT, "--literal-pathspecs",
             "-c", "user.name=Change Discovery Fixture",
             "-c", "user.email=change-discovery@example.invalid",
             "-c", "commit.gpgSign=false",
             "-c", f"core.hooksPath={self.root / 'empty-hooks'}", *args],
            cwd=self.root, env=self.env, capture_output=True, check=True,
        ).stdout.decode("utf-8", errors="surrogateescape").strip()

    def commit(self, *paths):
        # All mutations are confined to this test's TemporaryDirectory.
        self.git("add", "--", *paths)
        self.git("commit", "--quiet", "-m", "test fixture")

    def assert_audit(self, code, *args, env=None, count=None):
        result = subprocess.run(
            [sys.executable, "-B", str(self.script), *args],
            cwd=self.root, env=self.env if env is None else env,
            capture_output=True, check=False,
        )
        result.stdout = result.stdout.decode("utf-8", errors="surrogateescape")
        result.stderr = result.stderr.decode("utf-8", errors="surrogateescape")
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        if code:
            self.assertNotIn("audit passed", result.stdout)
        if code == 2:
            # argparse rejecting --base is not proof that Git fails closed.
            self.assertIn("audit unavailable", result.stderr)
            self.assertNotIn("Traceback", result.stderr)
        if count is not None:
            self.assertIn(f"audit passed: {count} ", result.stdout)
        return result

    def wrapper_env(self, body):
        wrapper = self.write(
            "bin/git", "#!/bin/sh\n" + body + f"\nexec {shlex.quote(GIT)} \"$@\"\n"
        )
        wrapper.chmod(0o755)
        return {**self.env, "PATH": f"{wrapper.parent}:/usr/bin:/bin"}

    def test_clean_default_head_and_stage_argument(self):
        self.assert_audit(0, "--stage", "commit", count=0)
        self.assert_audit(0, "--base", "HEAD", count=0)

    def test_unstaged_source_is_rejected(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.write(SOURCE, CHANGED)
        self.assertIn(SOURCE, self.assert_audit(1).stderr)

    def test_staged_source_is_rejected(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.write(SOURCE, CHANGED)
        self.git("add", "--", SOURCE)
        self.assertIn(SOURCE, self.assert_audit(1).stderr)

    def test_staged_new_source_is_rejected(self):
        self.write(SOURCE, CHANGED)
        self.git("add", "--", SOURCE)
        self.assertIn(SOURCE, self.assert_audit(1).stderr)

    def test_untracked_source_is_rejected(self):
        self.write(SOURCE, CHANGED)
        self.assertIn(SOURCE, self.assert_audit(1).stderr)

    def test_mixed_changes_report_each_path_once(self):
        names = ("StagedActivity", "UnstagedActivity", "BothActivity")
        for name in names:
            self.write(UI + name + ".kt", ORIGINAL)
        self.commit(UI)
        for name in (*names, "UntrackedActivity"):
            self.write(UI + name + ".kt", CHANGED)
        self.git("add", "--", UI + "StagedActivity.kt", UI + "BothActivity.kt")
        self.write(UI + "BothActivity.kt", CHANGED + "// unstaged too\n")
        result = self.assert_audit(1)
        for name in (*names, "UntrackedActivity"):
            self.assertEqual(1, result.stderr.count(UI + name + ".kt"), result.stderr)

    def test_changed_tests_can_be_staged_unstaged_or_untracked(self):
        self.write(SOURCE, ORIGINAL)
        self.write(TEST, "// baseline\n")
        self.commit(SOURCE, TEST)
        self.write(SOURCE, CHANGED)
        self.write(TEST, "// updated\n")
        self.assert_audit(0, count=1)
        self.git("add", "--", SOURCE, TEST)
        self.assert_audit(0, count=1)
        self.commit(SOURCE, TEST)
        self.write(SOURCE, ORIGINAL)
        self.write(TESTS + "ReaderActivityExtraTest.kt", "// new test\n")
        self.assert_audit(0, count=1)

    def test_unchanged_test_does_not_satisfy_pairing(self):
        self.write(SOURCE, ORIGINAL)
        self.write(TEST, "// existing test\n")
        self.commit(SOURCE, TEST)
        self.write(SOURCE, CHANGED)
        self.assert_audit(1)

    def test_explicit_base_includes_committed_changes(self):
        base = self.git("rev-parse", "HEAD")
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.assert_audit(0, count=0)
        self.assertIn(SOURCE, self.assert_audit(1, "--base", base).stderr)
        self.assertIn(
            SOURCE, self.assert_audit(1, "--base", "HEAD~1", "--stage", "ci").stderr
        )

    def test_base_combines_history_staged_unstaged_and_untracked(self):
        base = self.git("rev-parse", "HEAD")
        self.write(UI + "HistoryActivity.kt", ORIGINAL)
        self.write(UI + "DirtyActivity.kt", ORIGINAL)
        self.commit(UI)
        self.write(UI + "DirtyActivity.kt", CHANGED)
        self.write(UI + "StagedActivity.kt", CHANGED)
        self.git("add", "--", UI + "StagedActivity.kt")
        self.write(UI + "NewActivity.kt", CHANGED)
        result = self.assert_audit(1, "--base", base)
        for name in ("History", "Dirty", "Staged", "New"):
            self.assertEqual(1, result.stderr.count(UI + name + "Activity.kt"))

    def test_staged_change_cancelled_in_worktree_is_not_changed(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.write(SOURCE, CHANGED)
        self.git("add", "--", SOURCE)
        self.write(SOURCE, ORIGINAL)
        self.assert_audit(0, count=0)

    def test_history_change_cancelled_in_worktree_is_not_changed(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.write(SOURCE, CHANGED)
        self.commit(SOURCE)
        self.write(SOURCE, ORIGINAL)
        self.assert_audit(0, "--base", "HEAD~1", count=0)

    def test_cancelled_test_change_does_not_satisfy_pairing(self):
        self.write(SOURCE, ORIGINAL)
        self.write(TEST, "// baseline\n")
        self.commit(SOURCE, TEST)
        self.write(TEST, "// staged change\n")
        self.git("add", "--", TEST)
        self.write(TEST, "// baseline\n")
        self.write(SOURCE, CHANGED)
        self.assert_audit(1)

    def test_nul_paths_preserve_unicode_whitespace_and_magic_literally(self):
        names = (
            "设置 页Activity.kt", "line\nbreakActivity.kt", "carriage\rActivity.kt",
            "tab\tActivity.kt", 'quote"Activity.kt', " spaced Activity.kt",
            "[abc]Activity.kt", ":(glob)*Activity.kt", "-dashActivity.kt",
        )
        for name in names:
            self.write(UI + name, ORIGINAL)
        self.commit(UI)
        for name in names:
            self.write(UI + name, CHANGED)
        result = self.assert_audit(1)
        for name in names:
            self.assertEqual(1, result.stderr.count(UI + name), repr(result.stderr))

    def test_untracked_special_paths_are_preserved(self):
        paths = (UI + "设置 Activity.kt", UI + "line\nActivity.kt", UI + "[x]Activity.kt")
        for path in paths:
            self.write(path, CHANGED)
        result = self.assert_audit(1)
        for path in paths:
            self.assertEqual(1, result.stderr.count(path))

    def test_staged_rename_checks_destination_not_source(self):
        old = UI + "OldActivity.kt"
        new = UI + "Renamed Activity.kt"
        self.write(old, ORIGINAL)
        self.commit(old)
        (self.root / old).rename(self.root / new)
        self.git("add", "--", old, new)
        result = self.assert_audit(1)
        self.assertEqual(1, result.stderr.count(new))
        self.assertNotIn(old, result.stderr)

    def test_rename_out_of_scope_does_not_scan_deleted_source(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        (self.root / SOURCE).rename(self.root / "moved.txt")
        self.git("add", "--", SOURCE, "moved.txt")
        self.assert_audit(0, count=0)

    def test_untracked_test_rename_is_discovered(self):
        self.write(SOURCE, ORIGINAL)
        old = "notes.txt"
        self.write(old, "// test fixture\n")
        self.commit(SOURCE, old)
        self.write(SOURCE, CHANGED)
        target = self.root / TEST
        target.parent.mkdir(parents=True)
        (self.root / old).rename(target)
        self.assert_audit(0, count=1)

    def test_deleted_sources_are_excluded_at_every_index_state(self):
        paths = [UI + name + "Activity.kt" for name in ("Staged", "Unstaged")]
        for path in paths:
            self.write(path, ORIGINAL)
        self.commit(*paths)
        for path in paths:
            (self.root / path).unlink()
        self.git("add", "--", paths[0])
        self.assert_audit(0, count=0)
        new = UI + "AddedThenDeletedActivity.kt"
        self.write(new, ORIGINAL)
        self.git("add", "--", new)
        (self.root / new).unlink()
        self.assert_audit(0, count=0)

    def test_deleted_tests_do_not_satisfy_pairing(self):
        self.write(SOURCE, ORIGINAL)
        self.write(TEST, "// test fixture\n")
        self.commit(SOURCE, TEST)
        self.write(SOURCE, CHANGED)
        (self.root / TEST).unlink()
        self.assert_audit(1)
        self.git("add", "--", TEST)
        self.assert_audit(1)

    def test_ignored_source_inside_new_directory_is_not_scanned(self):
        ignored = UI + "new/IgnoredActivity.kt"
        self.write(".gitignore", "IgnoredActivity.kt\n")
        self.commit(".gitignore")
        self.write(ignored, CHANGED)
        self.write(UI + "new/visible.txt", "keep directory visible to status\n")
        self.assert_audit(0, count=0)

    def test_ignored_test_inside_new_directory_cannot_satisfy_pairing(self):
        self.write(SOURCE, ORIGINAL)
        self.write(".gitignore", "ReaderActivityTest.kt\n")
        self.commit(SOURCE, ".gitignore")
        self.write(SOURCE, CHANGED)
        self.write(TEST, "// ignored test\n")
        self.write(TESTS + "visible.txt", "keep directory visible to status\n")
        self.assert_audit(1)

    def test_git_info_and_config_excludes_are_respected(self):
        paths = [UI + "InfoActivity.kt", UI + "ConfigActivity.kt"]
        self.write(".git/info/exclude", "InfoActivity.kt\n")
        excludes = self.write("fixture-excludes", "ConfigActivity.kt\n")
        self.git("config", "core.excludesFile", str(excludes))
        for path in paths:
            self.write(path, CHANGED)
        self.write(UI + "visible.txt", "visible\n")
        self.assert_audit(0, count=0)

    def test_tracked_source_is_not_hidden_by_ignore(self):
        self.write(SOURCE, ORIGINAL)
        self.commit(SOURCE)
        self.write(".gitignore", "ReaderActivity.kt\n")
        self.commit(".gitignore")
        self.write(SOURCE, CHANGED)
        self.assert_audit(1)

    def test_invalid_bases_fail_closed(self):
        blob = self.git("rev-parse", "HEAD:fixture.txt")
        for base in ("missing-base", "HEAD..HEAD", "HEAD...HEAD", "--all", "HEAD^{tree}", blob):
            with self.subTest(base=base):
                self.assert_audit(2, "--base=" + base)

    def test_non_repository_fails_closed(self):
        (self.root / ".git").rename(self.root / "detached-git")
        self.assert_audit(2)

    def test_unborn_head_fails_closed(self):
        (self.root / ".git").rename(self.root / "detached-git")
        self.git("init", "--quiet", "--template=")
        self.assert_audit(2)

    def test_missing_git_fails_closed(self):
        self.assert_audit(2, env={**self.env, "PATH": str(self.root / "no-git")})

    def test_each_git_query_failure_fails_closed(self):
        for command, status in (("rev-parse", 46), ("diff", 47), ("ls-files", 48)):
            with self.subTest(command=command):
                env = self.wrapper_env(
                    'for arg in "$@"; do\n'
                    f'  if [ "$arg" = "{command}" ]; then exit {status}; fi\n'
                    "done"
                )
                result = self.assert_audit(2, env=env)
                self.assertIn(f"git {command} failed", result.stderr)
                self.assertIn(f"exit {status}", result.stderr)

    def test_failed_query_discards_partial_stdout(self):
        env = self.wrapper_env(
            'for arg in "$@"; do\n'
            '  if [ "$arg" = "diff" ]; then\n'
            f"    printf '%s\\000' '{SOURCE}'\n"
            "    exit 49\n"
            "  fi\n"
            "done"
        )
        self.assert_audit(2, env=env)

    def test_external_diff_and_textconv_are_disabled(self):
        self.write(SOURCE, ORIGINAL)
        self.write(".gitattributes", "*.kt diff=fixture\n")
        self.commit(SOURCE, ".gitattributes")
        driver = self.write(
            "driver.sh", "#!/bin/sh\n: > driver-was-run\nexit 51\n"
        )
        driver.chmod(0o755)
        self.git("config", "diff.fixture.command", str(driver))
        self.git("config", "diff.fixture.textconv", str(driver))
        self.write(SOURCE, CHANGED)
        env = self.wrapper_env(
            'case " $* " in\n'
            '  *" diff "*)\n'
            '    case " $* " in *" --no-ext-diff "*) ;; *) exit 52 ;; esac\n'
            '    case " $* " in *" --no-textconv "*) ;; *) exit 53 ;; esac\n'
            "    ;;\n"
            "esac"
        )
        self.assert_audit(1, env={**env, "GIT_EXTERNAL_DIFF": str(driver)})
        self.assertFalse((self.root / "driver-was-run").exists())


class HostRefreshDiscoveryTest(ChangeDiscoveryCases, unittest.TestCase):
    GATE = "audit_host_refresh_coverage.py"

    def test_changed_test_content_can_reference_host_without_matching_filename(self):
        source = "app/src/main/java/example/ui/ReadBookActivity.kt"
        test = TESTS + "AudioPrefetchWiringTest.kt"
        self.write(source, "class ReadBookActivity\n")
        self.write(test, "// baseline\n")
        self.commit(source, test)
        self.write(source, "class ReadBookActivity { val changed = true }\n")
        self.write(test, 'val body = method("ui/book/read/ReadBookActivity.kt")\n')
        self.assert_audit(0, count=1)

    def test_comment_only_host_reference_does_not_satisfy_coverage(self):
        source = "app/src/main/java/example/ui/ReadBookActivity.kt"
        test = TESTS + "AudioPrefetchWiringTest.kt"
        self.write(source, "class ReadBookActivity\n")
        self.write(test, "// baseline\n")
        self.commit(source, test)
        self.write(source, "class ReadBookActivity { val changed = true }\n")
        self.write(test, "// ui/book/read/ReadBookActivity.kt\n")
        self.assertIn(source, self.assert_audit(1).stderr)

    def test_existing_host_scan_scope_is_preserved(self):
        for path in (UI + "Widget.kt", UI + "ReaderActivity.xml", "other/Activity.kt"):
            self.write(path, ORIGINAL)
        self.assert_audit(0, count=0)
        path = "other/ui/ReaderDialog.java"
        self.write(path, ORIGINAL)
        self.assertIn(path, self.assert_audit(1).stderr)


class CodeTestPairDiscoveryTest(ChangeDiscoveryCases, unittest.TestCase):
    GATE = "audit_code_change_has_test.py"

    def test_changed_test_content_can_reference_source_without_matching_filename(self):
        source = "app/src/main/java/example/model/ReadBook.kt"
        test = TESTS + "AudioPrefetchWiringTest.kt"
        self.write(source, "class ReadBook\n")
        self.write(test, "// baseline\n")
        self.commit(source, test)
        self.write(source, "class ReadBook { val changed = true }\n")
        self.write(test, 'val sourcePath = "model/ReadBook.kt"\n')
        self.assert_audit(0, count=1)

    def test_comment_only_source_reference_does_not_satisfy_pairing(self):
        source = "app/src/main/java/example/model/ReadBook.kt"
        test = TESTS + "AudioPrefetchWiringTest.kt"
        self.write(source, "class ReadBook\n")
        self.write(test, "// baseline\n")
        self.commit(source, test)
        self.write(source, "class ReadBook { val changed = true }\n")
        self.write(test, "// model/ReadBook.kt\n")
        self.assertIn(source, self.assert_audit(1).stderr)

    def test_existing_production_scan_scope_is_preserved(self):
        for path in ("other/Model.kt", "scripts/other.py", UI + "Reader.xml"):
            self.write(path, ORIGINAL)
        self.assert_audit(0, count=0)
        paths = ("app/src/main/java/example/model/Model.java", "scripts/novel-audio-task.py")
        for path in paths:
            self.write(path, ORIGINAL)
        result = self.assert_audit(1)
        for path in paths:
            self.assertIn(path, result.stderr)


if __name__ == "__main__":
    unittest.main()
