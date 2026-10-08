import json
import contextlib
import io
import inspect
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from config import ConfigError, load_config
from checks import (
    CheckReport,
    CheckResult,
    run_checks,
)


ROOT = Path(__file__).resolve().parent
RUNTIME_NAMES = ("pythonRuntime", "qwenTts", "torch", "cuda", "llamaServer", "audioCodec")


def configured_assets(root, *, profile_vram=24):
    """Tiny hashed fixtures, not loadable model weights."""
    from model_registry import sha256_directory_manifest, sha256_file

    value = json.loads((ROOT / "local-model.example.json").read_text())
    (root / "voices").mkdir()
    (root / "voices/standard.json").write_bytes((ROOT / "voices/standard.json").read_bytes())
    (root / "selected-tts").mkdir()
    (root / "selected-tts/config.json").write_text("{}")
    (root / "selected.gguf").write_bytes(b"fixture")
    value["tokenFile"] = "token"
    (root / "token").write_text("test-token")
    value["modelRegistry"] = "models.json"
    value["activeProfile"] = "selected"
    for section, keys in (("text", ("runner",)), ("tts", ("pythonExecutable", "ffmpeg", "ffprobe"))):
        for key in keys:
            value[section][key] = key + ".exe"
            (root / value[section][key]).touch()
    models = [
        dict(assetId="selected-text", type="text", family="qwen", format="gguf",
             path="selected.gguf", sha256=sha256_file(root / "selected.gguf"),
             requiredVramGb=24, capabilities=["chapter-analysis"], adapter="llama.cpp"),
        dict(assetId="selected-tts", type="tts", family="qwen", format="directory",
             path="selected-tts", sha256=sha256_directory_manifest(root / "selected-tts"),
             requiredVramGb=24, capabilities=["speech-synthesis", "voice-design"],
             adapter="qwen3-tts-native"),
        dict(assetId="unused", type="tts", family="qwen", format="directory",
             path="missing-unused", sha256="", requiredVramGb=128,
             capabilities=["speech-synthesis", "voice-clone"], adapter="qwen3-tts-native"),
    ]
    profile = dict(profileId="selected", textModel="selected-text", ttsModel="selected-tts",
                   minVramGb=profile_vram, maxConcurrency=1,
                   capabilities=["chapter-analysis", "speech-synthesis", "voice-design"])
    # The config override, not the registry default, determines the selected assets.
    unused = dict(profile, profileId="unused", ttsModel="unused", minVramGb=128,
                  capabilities=["chapter-analysis", "speech-synthesis", "voice-clone"])
    (root / "models.json").write_text(json.dumps(
        dict(version="1", models=models, profiles=[profile, unused], activeProfile="unused")
    ))
    path = root / "local-model.json"
    path.write_text(json.dumps(value))
    return path


class CheckReportTest(unittest.TestCase):
    def test_worker_timeout_defaults_leave_margin_below_smoke_http_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(
                (ROOT / "local-model.example.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )

            config = load_config(path)

            self.assertEqual(600.0, config.worker_startup_timeout)
            self.assertEqual(540.0, config.worker_ipc_timeout)

    def test_worker_timeout_settings_are_finite_and_bounded(self):
        for key, value in (
            ("workerStartupTimeout", 0),
            ("workerStartupTimeout", 601),
            ("workerStartupTimeout", float("inf")),
            ("workerIpcTimeout", 0),
            ("workerIpcTimeout", 601),
            ("workerIpcTimeout", float("nan")),
        ):
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "local-model.json"
                config_value = json.loads(
                    (ROOT / "local-model.example.json").read_text(encoding="utf-8")
                )
                config_value[key] = value
                path.write_text(json.dumps(config_value), encoding="utf-8")

                with self.assertRaises(ConfigError):
                    load_config(path)

    def test_blocked_runtime_json_and_exit_code_remain_consistent(self):
        from runtime_probe import ProbeOutput
        from test_runtime_probe import GoodRunner
        with tempfile.TemporaryDirectory() as directory:
            value = {name: ["PASS", "ok"] for name in RUNTIME_NAMES[:4]}
            value["cuda"] = ["BLOCKED", "insufficient_vram"]
            runner = GoodRunner({"--python-probe": ProbeOutput("ok", json.dumps(value).encode())})
            report = run_checks(configured_assets(Path(directory)), platform_name="nt", runner=runner)
            output = json.loads(report.to_json(require_windows_runtime=True))
            self.assertEqual(("BLOCKED", 3), (output["overall"], output["exitCode"]))
            self.assertEqual(output["exitCode"], report.exit_code(require_windows_runtime=True))

    def test_check_does_not_start_http_import_models_or_write_assets(self):
        from test_runtime_probe import GoodRunner
        import builtins
        actual_import = builtins.__import__
        def guard(name, *args, **kwargs):
            if name.split(".")[0] in {"torch", "torchaudio", "qwen_tts"}:
                self.fail("Agent GPU import")
            return actual_import(name, *args, **kwargs)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = configured_assets(root)
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob("*") if p.is_file()}
            with patch("builtins.__import__", side_effect=guard), \
                 patch("socket.socket", side_effect=AssertionError("no HTTP")):
                report = run_checks(path, platform_name="nt", runner=GoodRunner())
            after = {p.relative_to(root): p.read_bytes() for p in root.rglob("*") if p.is_file()}
            self.assertEqual(before, after)
            self.assertEqual(0, report.exit_code(require_windows_runtime=True))

    def test_strict_report_requires_all_runtime_results_but_ignores_unselected_assets(self):
        report = CheckReport()
        report.add(CheckResult("ttsBaseModel", "NOT_CHECKED", "not_selected"))
        for name in RUNTIME_NAMES:
            report.add(CheckResult(name, "PASS", "ok"))
        self.assertEqual(0, report.exit_code(require_windows_runtime=True))
        del report.results["cuda"]
        self.assertEqual(4, report.exit_code(require_windows_runtime=True))
        value = json.loads(report.to_json(require_windows_runtime=True))
        self.assertEqual(("NOT_CHECKED", 4), (value["overall"], value["exitCode"]))

    def test_arbitrary_names_and_codes_cannot_leak_into_reports(self):
        for name, code in (("cuda", "/private/secret"), ("/private/secret", "ok"),
                           ("cuda", "unexpected_log_text")):
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                CheckResult(name, "FAIL", code)

    def test_active_profile_uses_only_bound_assets_not_legacy_paths_or_unused_models(self):
        with tempfile.TemporaryDirectory() as directory:
            report = run_checks(configured_assets(Path(directory)), platform_name="posix")
            self.assertEqual("PASS", report.results["textModel"].status)
            self.assertEqual("PASS", report.results["ttsVoiceDesignModel"].status)
            self.assertEqual("not_selected", report.results["ttsBaseModel"].code)
            self.assertEqual("PASS", report.results["modelRegistry"].status)
            self.assertEqual("PASS", report.results["profileIdentity"].status)
            self.assertEqual(0, report.exit_code())
            self.assertEqual(4, report.exit_code(require_windows_runtime=True))

    def test_configured_minimum_is_not_misreported_as_observed_vram(self):
        with tempfile.TemporaryDirectory() as directory:
            path = configured_assets(Path(directory), profile_vram=32)
            report = run_checks(path, platform_name="posix")
            self.assertEqual("PASS", report.results["modelRegistry"].status)
            self.assertEqual("NOT_CHECKED", report.results["cuda"].status)

    def test_windows_runtime_is_injectable_and_uses_profile_memory_requirement(self):
        self.assertIn("runner", inspect.signature(run_checks).parameters)
        from test_runtime_probe import GoodRunner
        with tempfile.TemporaryDirectory() as directory:
            runner = GoodRunner()
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                report = run_checks(configured_assets(Path(directory), profile_vram=32),
                                    platform_name="nt", runner=runner)
            self.assertEqual("", output.getvalue())
            self.assertEqual(0, report.exit_code(require_windows_runtime=True))
            self.assertEqual(32.0, float(runner.calls[0][0][-1]))
            self.assertNotIn(directory, report.to_json())

    def test_mac_does_not_launch_any_windows_program_even_with_injected_runner(self):
        self.assertIn("runner", inspect.signature(run_checks).parameters)
        with tempfile.TemporaryDirectory() as directory:
            with patch("subprocess.Popen", side_effect=AssertionError("must not launch")):
                report = run_checks(configured_assets(Path(directory)), platform_name="posix",
                                    runner=lambda *_a, **_k: self.fail("must not probe"))
            for name in RUNTIME_NAMES:
                self.assertEqual(("NOT_CHECKED", "windows_only"),
                                 (report.results[name].status, report.results[name].code))

    def test_selected_asset_hash_failure_remains_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = configured_assets(Path(directory))
            (Path(directory) / "selected.gguf").write_bytes(b"changed")
            report = run_checks(path, platform_name="posix")
            self.assertEqual("FAIL", report.results["textModel"].status)
            self.assertEqual(2, report.exit_code(require_windows_runtime=True))

    def test_report_serializes_stable_results_and_exit_codes(self):
        report = CheckReport()
        report.add(CheckResult("config", "PASS", "ok"))
        report.add(CheckResult("cuda", "BLOCKED", "deferred_to_windows"))

        value = report.to_dict()

        self.assertEqual("BLOCKED", value["overall"])
        self.assertEqual(
            {"status": "PASS", "code": "ok"},
            value["checks"]["config"],
        )
        self.assertEqual(
            {"status": "BLOCKED", "code": "deferred_to_windows"},
            value["checks"]["cuda"],
        )
        self.assertEqual(
            value,
            json.loads(report.to_json()),
        )

    def test_report_maps_not_checked_and_fail_to_distinct_exit_codes(self):
        not_checked = CheckReport()
        not_checked.add(CheckResult("torch", "NOT_CHECKED", "not_run"))
        self.assertEqual(0, not_checked.exit_code())
        self.assertEqual(4, not_checked.exit_code(require_windows_runtime=True))

        failed = CheckReport()
        failed.add(CheckResult("textModel", "FAIL", "asset_missing"))
        self.assertEqual(2, failed.exit_code())

    def test_run_checks_does_not_load_models_and_marks_windows_runtime_deferred(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "voices" / "standard.json"
            catalog.parent.mkdir()
            catalog.write_text(
                (ROOT / "voices" / "standard.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )
            config = root / "local-model.json"
            value = json.loads(
                (ROOT / "local-model.example.json").read_text(encoding="utf-8")
            )
            value["voiceCatalog"] = "voices/standard.json"
            value["tokenFile"] = "state/token"
            config.write_text(json.dumps(value), encoding="utf-8")

            report = run_checks(config, platform_name="posix")

            self.assertEqual("PASS", report.results["config"].status)
            self.assertEqual("PASS", report.results["voiceCatalog"].status)
            self.assertEqual("FAIL", report.results["textModel"].status)
            self.assertEqual("NOT_CHECKED", report.results["cuda"].status)
            self.assertEqual("windows_only", report.results["cuda"].code)
            self.assertEqual(
                {
                    "audioCodec",
                    "config",
                    "cuda",
                    "ffmpeg",
                    "ffprobe",
                    "llamaServer",
                    "modelRegistry",
                    "profileIdentity",
                    "pythonRuntime",
                    "qwenTts",
                    "textModel",
                    "textRunner",
                    "tokenFile",
                    "torch",
                    "ttsBaseModel",
                    "ttsPython",
                    "ttsVoiceDesignModel",
                    "voiceCatalog",
                },
                set(report.results),
            )


if __name__ == "__main__":
    unittest.main()
