import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from pathlib import Path

from scripts.novel_audio_server.errors import WorkerStartError, WorkerUnavailableError
from worker import SubprocessWorker, SubprocessWorkerFactory


ROOT = Path(__file__).resolve().parent


class _ExitingProcess:
    """Process double for the Windows taskkill path; never spawns anything."""

    pid = 4242

    def __init__(self, exits):
        self.exits = exits
        self.wait_count = 0

    def poll(self):
        return None

    def wait(self, timeout=None):
        self.wait_count += 1
        if not self.exits:
            raise subprocess.TimeoutExpired("worker", timeout)
        return 0


class WorkerProcessTest(unittest.TestCase):
    def _config(self, directory):
        value = json.loads(
            (ROOT / "local-model.example.json").read_text(encoding="utf-8")
        )
        catalog = Path(directory) / "voices" / "standard.json"
        catalog.parent.mkdir()
        catalog.write_text(
            (ROOT / "voices" / "standard.json").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        value["voiceCatalog"] = "voices/standard.json"
        path = Path(directory) / "local-model.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def _request(self):
        return {
            "bookId": "worker-test",
            "chapterId": "chapter-1",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [
                {"characterId": "char-a", "displayName": "甲", "stableAliases": []},
            ],
            "units": [{"unitId": "u1", "text": "测试正文"}],
            "previousContext": {"recentAssignments": []},
        }

    def test_fake_worker_process_round_trip_and_close(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            worker = SubprocessWorkerFactory(
                config_path, fake=True, expected_identity="fake-local-v1"
            ).start(
                "fake-local-v1"
            )
            try:
                self.assertEqual("fake-local-v1", worker.profile)
                response = worker.analyze(self._request())
                self.assertEqual(["u1"], [item["unitId"] for item in response["assignments"]])
                audio = worker.synthesize(
                    {
                        "text": "测试正文",
                        "voiceAssetId": "local.qwen3-tts.narrator",
                        "language": "zh-CN",
                        "speed": 1.0,
                    }
                )
                self.assertTrue(audio.startswith(b"OggS"))
                self.assertIsNone(worker.process.poll())
            finally:
                started = time.monotonic()
                worker.close()
                self.assertLess(time.monotonic() - started, 2.0)
            self.assertIsNotNone(worker.process.poll())

    def test_fake_worker_round_trip_survives_non_utf8_console_code_page(self):
        # Windows pipes default to the ANSI code page (GBK on Chinese systems);
        # the Agent always speaks UTF-8, so the Worker must not inherit the locale.
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {"PYTHONIOENCODING": "gbk"}):
            config_path = self._config(directory)
            worker = SubprocessWorkerFactory(
                config_path, fake=True, expected_identity="fake-local-v1"
            ).start("fake-local-v1")
            try:
                response = worker.analyze(self._request())
                self.assertEqual(["u1"], [item["unitId"] for item in response["assignments"]])
            finally:
                worker.close()

    def test_windows_cleanup_accepts_worker_that_exits_before_taskkill(self):
        # The Worker can exit between poll() and taskkill; taskkill then fails
        # although the process is already gone, which is a clean stop.
        worker = SubprocessWorker.__new__(SubprocessWorker)
        worker.process = _ExitingProcess(exits=True)
        with patch("worker.os", type("WindowsOs", (), {"name": "nt"})), \
                patch("worker.subprocess.run", return_value=type("Result", (), {"returncode": 128})()):
            worker._terminate_tree()
        self.assertEqual(1, worker.process.wait_count)

    def test_windows_cleanup_fails_when_taskkill_fails_and_worker_survives(self):
        worker = SubprocessWorker.__new__(SubprocessWorker)
        worker.process = _ExitingProcess(exits=False)
        with patch("worker.os", type("WindowsOs", (), {"name": "nt"})), \
                patch("worker.subprocess.run", return_value=type("Result", (), {"returncode": 1})()):
            with self.assertRaises(WorkerUnavailableError):
                worker._terminate_tree()

    def test_fake_worker_profile_mismatch_fails_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            with self.assertRaises(WorkerStartError):
                SubprocessWorkerFactory(
                    config_path, fake=True, expected_identity="unexpected-profile"
                ).start("fake-local-v1")

    def test_worker_start_failure_and_eof_fail_fast(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "missing.json"
            started = time.monotonic()
            with self.assertRaises(WorkerStartError):
                SubprocessWorkerFactory(config_path, fake=True).start("fake-local-v1")
            self.assertLess(time.monotonic() - started, 2.0)

        started = time.monotonic()
        with self.assertRaises(WorkerStartError):
            SubprocessWorker(
                [
                    sys.executable,
                    "-c",
                    "import sys; sys.exit(0)",
                ],
                "profile",
            )
        self.assertLess(time.monotonic() - started, 2.0)

    def test_real_worker_uses_configured_tts_python_and_profile_argument(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            sentinel = object()
            with patch("worker.SubprocessWorker", return_value=sentinel) as constructor:
                result = SubprocessWorkerFactory(config_path).start("profile-9b")

            self.assertIs(sentinel, result)
            command = constructor.call_args.args[0]
            self.assertEqual(
                (
                    Path(directory)
                    / "runtime"
                    / "tts-python"
                    / "python.exe"
                ).resolve(),
                Path(command[0]).resolve(),
            )
            self.assertIn("--profile", command)
            self.assertEqual("profile-9b", command[command.index("--profile") + 1])
            self.assertNotIn("--fake-backend", command)

    def test_fake_worker_uses_agent_python_only_when_explicitly_fake(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            sentinel = object()
            with patch("worker.SubprocessWorker", return_value=sentinel) as constructor:
                SubprocessWorkerFactory(config_path, fake=True).start("fake-local-v1")

            command = constructor.call_args.args[0]
            self.assertEqual(sys.executable, command[0])
            self.assertIn("--fake-backend", command)

    def test_worker_factory_passes_configured_startup_and_ipc_timeouts(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            value = json.loads(config_path.read_text(encoding="utf-8"))
            value["workerStartupTimeout"] = 321
            value["workerIpcTimeout"] = 432
            config_path.write_text(json.dumps(value), encoding="utf-8")
            sentinel = object()

            with patch("worker.SubprocessWorker", return_value=sentinel) as constructor:
                result = SubprocessWorkerFactory(config_path).start("profile-9b")

            self.assertIs(sentinel, result)
            self.assertEqual(321.0, constructor.call_args.kwargs["startup_timeout"])
            self.assertEqual(432.0, constructor.call_args.kwargs["ipc_timeout"])

    def test_fake_worker_rejects_invalid_operation_without_exposing_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = self._config(directory)
            worker = SubprocessWorkerFactory(config_path, fake=True).start(
                "fake-local-v1"
            )
            try:
                with self.assertRaises(RuntimeError):
                    worker._call("unknown", {})
            finally:
                worker.close()

    def test_agent_modules_import_without_heavy_model_dependencies(self):
        import agent

        self.assertTrue(hasattr(agent, "LocalAgent"))

    def test_close_interrupts_blocked_ipc_without_waiting_for_request_timeout(self):
        worker = SubprocessWorker(
            [
                sys.executable,
                "-u",
                "-c",
                (
                    "import json, sys, time; "
                    "print(json.dumps({'ok': True, 'event': 'ready', "
                    "'profile': 'profile'}), flush=True); "
                    "line = sys.stdin.readline(); "
                    "time.sleep(100)"
                ),
            ],
            "profile",
        )
        result = []
        thread = threading.Thread(
            target=lambda: self._capture(
                result,
                lambda: worker.synthesize(
                    {
                        "text": "blocked",
                        "voiceAssetId": "local.narrator",
                        "language": "zh-CN",
                        "speed": 1.0,
                    }
                ),
            )
        )
        thread.start()
        try:
            time.sleep(0.1)
            started = time.monotonic()
            self.assertTrue(callable(getattr(worker, "cancel", None)))
            worker.cancel()
            worker.close()
            thread.join(2)
            self.assertFalse(thread.is_alive())
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual("request_cancelled", getattr(result[0], "code", None))
        finally:
            worker.close()

    def test_close_alone_interrupts_blocked_pipe_write_with_long_ipc_timeout(self):
        # The child never consumes stdin: a large write blocks inside TextIO.
        worker = SubprocessWorker(
            [sys.executable, "-u", "-c",
             "import json,time; print(json.dumps({'ok':True,'event':'ready',"
             "'profile':'profile'}),flush=True); time.sleep(100)"],
            "profile",
        )
        result = []
        thread = threading.Thread(target=lambda: self._capture(
            result, lambda: worker._call("analyze", {"text": "x" * (2 * 1024 * 1024)})
        ))
        with patch("worker.IPC_TIMEOUT", 300):
            thread.start()
            try:
                deadline = time.monotonic() + 1
                while not worker._request_lock.locked() and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(worker._request_lock.locked())
                started = time.monotonic()
                worker.close()
                thread.join(1)
                self.assertLess(time.monotonic() - started, 2)
                self.assertFalse(thread.is_alive())
                self.assertEqual("request_cancelled", getattr(result[0], "code", None))
                self.assertIsNotNone(worker.process.poll())
                self.assertTrue(worker.process.stdin.closed)
                self.assertTrue(worker.process.stdout.closed)
            finally:
                worker.close()
                thread.join(2)

    @unittest.skipUnless(os.name == "posix", "POSIX process group fixture")
    def test_close_reaps_descendant_even_when_worker_exits_on_sigterm(self):
        code = (
            "import json,os,signal,subprocess,sys,time; "
            "signal.signal(signal.SIGCHLD, signal.SIG_IGN); "
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(100)'],"
            "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); "
            "print(json.dumps({'ok':True,'event':'ready','profile':'profile'}),flush=True); "
            "print(json.dumps({'ok':True,'result':child.pid}),flush=True); time.sleep(100)"
        )
        worker = SubprocessWorker([sys.executable, "-u", "-c", code], "profile")
        pid = worker._responses.get(timeout=1)["result"]
        try:
            worker.close()
            deadline = time.monotonic() + 1
            while self._pid_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertFalse(self._pid_alive(pid), "worker descendant survived close")
        finally:
            if self._pid_alive(pid):
                os.kill(pid, signal.SIGKILL)
            worker.close()

    @staticmethod
    def _pid_alive(pid):
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False

    def test_ipc_exception_body_is_replaced_by_worker_unavailable(self):
        worker = SubprocessWorker(
            [sys.executable, "-u", "-c",
             "import json,sys; print(json.dumps({'ok':True,'event':'ready',"
             "'profile':'profile'}),flush=True); sys.stdin.readline(); "
             "print(json.dumps({'ok':False,'code':'private-path-token'}),flush=True)"],
            "profile",
        )
        try:
            with self.assertRaises(RuntimeError) as caught:
                worker._call("analyze", {})
            self.assertEqual("worker_unavailable", str(caught.exception))
        finally:
            worker.close()

    def test_startup_failure_transfers_failed_cleanup_ownership(self):
        original = SubprocessWorker.close
        retained = []

        def failing_close(worker):
            retained.append(worker)
            raise RuntimeError("private cleanup exception")

        try:
            with patch.object(SubprocessWorker, "close", failing_close):
                with self.assertRaises(WorkerStartError) as caught:
                    SubprocessWorker(
                        [sys.executable, "-u", "-c",
                         "import time; print('{\"ok\":false}',flush=True); time.sleep(100)"],
                        "profile",
                    )
                self.assertIs(retained[0], getattr(caught.exception, "worker", None))
                self.assertEqual("worker_start_failed", str(caught.exception))
        finally:
            for worker in retained:
                original(worker)

    @staticmethod
    def _capture(result, action):
        try:
            result.append(action())
        except BaseException as error:
            result.append(error)


if __name__ == "__main__":
    unittest.main()
