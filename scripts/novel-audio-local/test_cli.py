import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import checks
import server


ROOT = Path(__file__).resolve().parent


class LocalCliTest(unittest.TestCase):
    def test_windows_check_requires_complete_runtime_report(self):
        report = Mock()
        report.to_json.return_value = '{"overall":"NOT_CHECKED","exitCode":4}'
        report.exit_code.return_value = 4
        with patch.object(server, "run_checks", return_value=report), \
                patch.object(server, "WINDOWS", True, create=True), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(4, server.main(["--check"]))
        report.to_json.assert_called_once_with(require_windows_runtime=True)
        report.exit_code.assert_called_once_with(require_windows_runtime=True)

    def test_runtime_check_can_be_required_explicitly_on_mac(self):
        report = Mock()
        report.to_json.return_value = '{"overall":"NOT_CHECKED","exitCode":4}'
        report.exit_code.return_value = 4
        with patch.object(server, "run_checks", return_value=report), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(4, server.main(["--check", "--require-windows-runtime"]))
        report.to_json.assert_called_once_with(require_windows_runtime=True)

    def test_real_smoke_never_silently_uses_fake_backend(self):
        with patch.object(server, "_smoke") as smoke, \
                contextlib.redirect_stdout(io.StringIO()):
            result = server.main(["--smoke"])
        self.assertNotEqual(0, result)
        smoke.assert_not_called()

    def test_windows_pid_probe_never_calls_os_kill(self):
        with patch.object(server, "WINDOWS", True, create=True), \
                patch.object(server, "_windows_pid_is_running", return_value=True,
                             create=True) as probe, \
                patch.object(server.os, "kill") as kill:
            self.assertTrue(server._pid_is_running(1234))
            probe.assert_called_once_with(1234)
            kill.assert_not_called()

    def test_stale_ready_status_is_not_reported_as_live(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(json.dumps(self._config_value(directory)))
            state = Path(directory) / "state"
            state.mkdir()
            (state / "agent.pid").write_text("1234")
            (state / "agent.status.json").write_text(
                '{"state":"ready","activeLease":true}'
            )
            with patch.object(server, "_pid_is_running", return_value=False):
                value = server._read_status(server.load_config(path))
            self.assertEqual("stopped", value["state"])
            self.assertFalse(value["activeLease"])
            self.assertNotIn("pid", value)

    def test_status_preserves_cleanup_failure_when_agent_has_exited(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(json.dumps(self._config_value(directory)))
            state = Path(directory) / "state"
            state.mkdir()
            (state / "agent.status.json").write_text(
                '{"state":"failed","errorCode":"worker_stop_failed","activeLease":true}'
            )
            value = server._read_status(server.load_config(path))
            self.assertEqual("failed", value["state"])
            self.assertEqual("worker_stop_failed", value["errorCode"])

    def test_windows_stop_uses_owned_tree_script(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(json.dumps(self._config_value(directory)))
            with patch.object(server, "WINDOWS", True), \
                    patch.object(server, "_stop_windows_agent", return_value=2,
                                 create=True) as stop, \
                    contextlib.redirect_stdout(io.StringIO()):
                result = server.main(["--stop", "--config", str(path)])
            self.assertEqual(2, result)
            stop.assert_called_once_with(path, 10.0)

    def test_stop_timeout_is_failure_not_successful_request(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(json.dumps(self._config_value(directory)))
            state = Path(directory) / "state"
            state.mkdir()
            (state / "agent.pid").write_text("1234")
            output = io.StringIO()
            with patch.object(server, "_pid_is_running", return_value=True), \
                    contextlib.redirect_stdout(output):
                result = server.main([
                    "--stop", "--stop-timeout", "0.05", "--config", str(path)
                ])
            self.assertEqual(2, result)
            self.assertEqual("worker_stop_failed",
                             json.loads(output.getvalue())["errorCode"])
            self.assertTrue((state / "agent.pid").exists())

    def _config_value(self, directory):
        value = json.loads((ROOT / "local-model.example.json").read_text())
        catalog = Path(directory) / "voices" / "standard.json"
        catalog.parent.mkdir()
        catalog.write_text(
            (ROOT / "voices" / "standard.json").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        value["voiceCatalog"] = "voices/standard.json"
        value["tokenFile"] = "state/token"
        return value

    def test_init_does_not_overwrite_existing_config_or_token(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            config_path.write_text(
                (ROOT / "local-model.example.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, server.main(["--init", "--config", str(config_path)]))
            original = config_path.read_text(encoding="utf-8")
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, server.main(["--init", "--config", str(config_path)]))
            self.assertEqual(original, config_path.read_text(encoding="utf-8"))
            self.assertNotIn("token", output.getvalue().lower())

    def test_fake_smoke_creates_three_ogg_files_without_sensitive_output(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            value = self._config_value(directory)
            config_path.write_text(json.dumps(value), encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                result = server.main(
                    ["--smoke", "--fake-backend", "--config", str(config_path)]
                )
            self.assertEqual(0, result)
            self.assertIn("fake smoke passed", output.getvalue())
            self.assertNotIn("model.gguf", output.getvalue())
            self.assertNotIn("agent-token", output.getvalue())

    def test_check_reports_missing_windows_runtime_without_loading_models(self):
        # Pin the non-Windows branch: on a Windows host the real runtime probe
        # would run and the expected "windows_only" code would never appear.
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            value = self._config_value(directory)
            config_path.write_text(json.dumps(value), encoding="utf-8")
            output = io.StringIO()
            with patch.object(server, "WINDOWS", False), patch.object(
                server, "run_checks",
                lambda path: checks.run_checks(path, platform_name="posix"),
            ), contextlib.redirect_stdout(output):
                result = server.main(["--check", "--config", str(config_path)])
            self.assertEqual(2, result)
            report = json.loads(output.getvalue())
            self.assertEqual("FAIL", report["overall"])
            self.assertEqual(2, report["exitCode"])
            self.assertEqual("FAIL", report["checks"]["textModel"]["status"])
            self.assertEqual("NOT_CHECKED", report["checks"]["cuda"]["status"])
            self.assertEqual(
                "windows_only",
                report["checks"]["cuda"]["code"],
            )

    def test_check_returns_structured_report_for_invalid_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            config_path.write_text("{not-json", encoding="utf-8")
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                result = server.main(["--check", "--config", str(config_path)])

            self.assertEqual(2, result)
            self.assertEqual(
                {
                    "overall": "FAIL",
                    "exitCode": 2,
                    "checks": {
                        "config": {
                            "status": "FAIL",
                            "code": "invalid_config",
                        }
                    },
                },
                json.loads(output.getvalue()),
            )

    def test_status_reads_sanitized_pid_and_state_files(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            value = self._config_value(directory)
            config_path.write_text(json.dumps(value), encoding="utf-8")
            state = Path(directory) / "state"
            state.mkdir()
            (state / "agent.pid").write_text("1234\n", encoding="ascii")
            (state / "agent.status.json").write_text(
                json.dumps(
                    {
                        "state": "ready",
                        "profileId": "profile-default",
                        "identity": "profile-default-opaque",
                        "capabilities": ["speech-synthesis"],
                        "activeLease": False,
                        "hardware": {"status": "deferred"},
                        "modelPath": "/private/model.gguf",
                    }
                ),
                encoding="utf-8",
            )

            output = io.StringIO()
            with contextlib.redirect_stdout(output), \
                    patch.object(server, "_pid_is_running", return_value=True):
                result = server.main(["--status", "--config", str(config_path)])

            self.assertEqual(0, result)
            status = json.loads(output.getvalue())
            self.assertEqual(1234, status["pid"])
            self.assertEqual("ready", status["state"])
            self.assertEqual("profile-default", status["profileId"])
            self.assertNotIn("modelPath", status)

    def test_stop_writes_a_marker_without_exposing_pid_or_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "local-model.json"
            value = self._config_value(directory)
            config_path.write_text(json.dumps(value), encoding="utf-8")
            state = Path(directory) / "state"
            state.mkdir()
            (state / "agent.pid").write_text("1234\n", encoding="ascii")

            original_probe = server._pid_is_running
            server._pid_is_running = lambda pid: False
            try:
                output = io.StringIO()
                # The marker protocol is the POSIX branch; Windows delegates to
                # stop-agent.ps1, which has its own operator coverage.
                with patch.object(server, "WINDOWS", False), \
                        contextlib.redirect_stdout(output):
                    result = server.main(["--stop", "--config", str(config_path)])
            finally:
                server._pid_is_running = original_probe

            self.assertEqual(0, result)
            self.assertTrue((state / "agent.stop").is_file())
            self.assertEqual("stopped", json.loads(output.getvalue())["state"])
            self.assertNotIn(str(directory), output.getvalue())
            self.assertNotIn("1234", output.getvalue())


if __name__ == "__main__":
    unittest.main()
