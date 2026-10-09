import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from test_support import requires_symlinks


class ServiceBundleTest(unittest.TestCase):
    def setUp(self):
        self.revision = patch(
            "package_service.source_revision",
            return_value={"sourceBranch": "feat/local-model-service", "sourceHead": "a" * 40},
            create=True,
        )
        self.revision.start()
        self.addCleanup(self.revision.stop)

    def fixture(self, root):
        from package_service import SOURCE_FILES
        for name in SOURCE_FILES:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# fixture\n", encoding="utf-8")

    def test_resource_gate_and_prewarm_are_in_source_allowlist(self):
        # Both runtime modules must ship in the bundle or the Windows retest
        # downloads a service that cannot import the resource gate or prewarm.
        from package_service import SOURCE_FILES
        self.assertIn("scripts/novel-audio-local/resource_gate.py", SOURCE_FILES)
        self.assertIn("scripts/novel-audio-local/prewarm_http.py", SOURCE_FILES)

    def test_bundle_only_contains_allowed_source_and_matching_hashes(self):
        from package_service import SOURCE_FILES, build_bundle
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "repo"
            self.fixture(root)
            (root / "scripts/novel-audio-local/local-model.json").write_text("private")
            target = Path(directory) / "source.zip"
            build_bundle(root, target)
            with zipfile.ZipFile(target) as archive:
                self.assertEqual(set(SOURCE_FILES) | {"SERVICE_MANIFEST.json"},
                                 set(archive.namelist()))
                manifest = json.loads(archive.read("SERVICE_MANIFEST.json"))
                self.assertEqual("feat/local-model-service", manifest["sourceBranch"])
                self.assertEqual("a" * 40, manifest["sourceHead"])
                self.assertEqual("working-tree", manifest.get("snapshotKind"))
                self.assertEqual(set(SOURCE_FILES), set(manifest["sha256"]))
                for name, digest in manifest["sha256"].items():
                    self.assertEqual(hashlib.sha256(archive.read(name)).hexdigest(), digest)

    def test_bundle_never_overwrites_existing_archive(self):
        from package_service import build_bundle
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "repo"
            self.fixture(root)
            target = Path(directory) / "source.zip"
            target.write_bytes(b"preserve")
            with self.assertRaises(FileExistsError):
                build_bundle(root, target)
            self.assertEqual(b"preserve", target.read_bytes())

    @requires_symlinks
    def test_symlink_or_missing_source_fails_without_partial_archive(self):
        from package_service import SOURCE_FILES, build_bundle
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "repo"
            self.fixture(root)
            source = root / SOURCE_FILES[0]
            source.unlink()
            target = Path(directory) / "source.zip"
            with self.assertRaises(ValueError):
                build_bundle(root, target)
            self.assertFalse(target.exists())
            private = Path(directory) / "private"
            private.write_text("secret")
            source.symlink_to(private)
            with self.assertRaises(ValueError):
                build_bundle(root, target)
            self.assertFalse(target.exists())

    @requires_symlinks
    def test_parent_symlink_cannot_bypass_source_allowlist(self):
        from package_service import build_bundle
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            private = Path(directory) / "private"
            self.fixture(private)
            root = Path(directory) / "repo"
            root.mkdir()
            (root / "scripts").symlink_to(private / "scripts", target_is_directory=True)
            target = Path(directory) / "source.zip"
            with self.assertRaises(ValueError):
                build_bundle(root, target)
            self.assertFalse(target.exists())

    @requires_symlinks
    def test_target_parent_symlink_is_rejected(self):
        from package_service import build_bundle
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            root = Path(directory) / "repo"
            self.fixture(root)
            real_output = Path(directory) / "real-output"
            real_output.mkdir()
            linked_output = Path(directory) / "linked-output"
            linked_output.symlink_to(real_output, target_is_directory=True)
            target = linked_output / "source.zip"
            with self.assertRaises(ValueError):
                build_bundle(root, target)
            self.assertFalse((real_output / "source.zip").exists())

    def test_write_failure_removes_only_new_partial_archive(self):
        from package_service import build_bundle
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "repo"
            self.fixture(root)
            target = root.parent / "source.zip"
            with patch("package_service.zipfile.ZipFile.writestr", side_effect=OSError):
                with self.assertRaisesRegex(ValueError, "bundle_failed"):
                    build_bundle(root, target)
            self.assertFalse(target.exists())

    def test_cli_builds_bundle_and_reports_fixed_failure(self):
        import package_service
        self.assertTrue(callable(getattr(package_service, "main", None)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "repo"
            self.fixture(root)
            target = root.parent / "source.zip"
            output = io.StringIO()
            arguments = ["--root", str(root), "--output", str(target)]
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, package_service.main(arguments))
            self.assertEqual("created", json.loads(output.getvalue())["state"])
            self.assertNotIn(str(root), output.getvalue())
            output = io.StringIO()
            original = target.read_bytes()
            with contextlib.redirect_stdout(output):
                self.assertEqual(2, package_service.main(arguments))
            self.assertEqual(
                {"state": "failed", "errorCode": "bundle_failed"},
                json.loads(output.getvalue()),
            )
            self.assertEqual(original, target.read_bytes())


    def test_real_source_bundle_runs_fake_smoke_without_repository_imports(self):
        from package_service import SOURCE_FILES, build_bundle
        source_root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            target = build_bundle(source_root, root / "source.zip")
            extracted = root / "extracted source"
            with zipfile.ZipFile(target) as archive:
                self.assertEqual(set(SOURCE_FILES) | {"SERVICE_MANIFEST.json"},
                                 set(archive.namelist()))
                # Only the archive just built from the fixed source allowlist is extracted.
                archive.extractall(extracted)
            service = extracted / "scripts" / "novel-audio-local"
            config = service / "local-model.json"
            env = dict(os.environ, PYTHONPATH="", PYTHONDONTWRITEBYTECODE="1",
                       TMPDIR=str(root), TMP=str(root), TEMP=str(root))
            for arguments in (["--init"], ["--smoke", "--fake-backend"]):
                result = subprocess.run(
                    [sys.executable, "-B", str(service / "server.py"),
                     "--config", str(config), *arguments],
                    cwd=extracted, env=env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    text=True, timeout=30, check=False,
                )
                self.assertEqual(0, result.returncode, "packaged CLI failed")
                self.assertEqual("", result.stderr)
            self.assertIn("fake smoke passed", result.stdout)
            self.assertEqual(3, len(list(root.glob("novel-audio-smoke-*/*.ogg"))))


class SourceRevisionTest(unittest.TestCase):
    def test_source_revision_timeout_is_reported_as_invalid_revision(self):
        from package_service import source_revision
        with patch(
            "package_service.subprocess.run",
            side_effect=subprocess.TimeoutExpired(["git"], 5),
        ):
            with self.assertRaisesRegex(ValueError, "invalid_source_revision"):
                source_revision("/tmp/repo")

    def test_source_revision_uses_bounded_git_commands(self):
        from package_service import source_revision
        results = [
            subprocess.CompletedProcess([], 0, "feat/local-model-service\n"),
            subprocess.CompletedProcess([], 0, "a" * 40 + "\n"),
        ]
        with patch("package_service.subprocess.run", side_effect=results) as run:
            self.assertEqual(
                {"sourceBranch": "feat/local-model-service", "sourceHead": "a" * 40},
                source_revision("/tmp/repo"),
            )
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(5, call.kwargs.get("timeout"))
            self.assertEqual(subprocess.DEVNULL, call.kwargs.get("stdin"))

    def test_source_revision_rejects_other_branches_invalid_head_and_git_errors(self):
        from package_service import source_revision
        for branch, head, code in (
            ("feat/ai-audiobook-voicestudio", "a" * 40, 0),
            ("", "a" * 40, 0),
            ("feat/local-model-service", "not-a-head", 0),
            ("feat/local-model-service", "a" * 40, 1),
        ):
            with self.subTest(branch=branch, code=code):
                with patch("package_service.subprocess.run", side_effect=[
                    subprocess.CompletedProcess([], code, branch),
                    subprocess.CompletedProcess([], code, head),
                ]):
                    with self.assertRaisesRegex(ValueError, "invalid_source_revision"):
                        source_revision("/tmp/repo")
        with patch("package_service.subprocess.run", side_effect=FileNotFoundError):
            with self.assertRaisesRegex(ValueError, "invalid_source_revision"):
                source_revision("/tmp/repo")
