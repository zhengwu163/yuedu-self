import importlib
import json
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from scripts.novel_audio_server.errors import NovelAudioError, WorkerStartError
from worker import SubprocessWorker, SubprocessWorkerFactory
from unittest.mock import Mock


class WorkerResourceGateTest(unittest.TestCase):
    def test_insufficient_resources_prevent_worker_creation(self):
        config = SimpleNamespace(
            tts=SimpleNamespace(python_executable=sys.executable),
            worker_startup_timeout=1, worker_ipc_timeout=1,
        )
        with patch("worker.load_config", return_value=config), \
                patch("worker.ensure_resources", create=True, side_effect=WorkerStartError()), \
                patch("worker.SubprocessWorker") as constructor:
            with self.assertRaises(WorkerStartError):
                SubprocessWorkerFactory("unused").start("profile")
            constructor.assert_not_called()

    def test_startup_resource_code_survives_ipc(self):
        with self.assertRaises(WorkerStartError) as caught:
            SubprocessWorker([
                sys.executable, "-u", "-c",
                "print('{\"ok\":false,\"code\":\"insufficient_resources\"}',flush=True)",
            ], "profile")
        self.assertEqual("insufficient_resources", caught.exception.code)

    def test_invalid_startup_code_remains_a_fixed_worker_error(self):
        with self.assertRaises(WorkerStartError) as caught:
            SubprocessWorker([sys.executable, "-u", "-c", "print('{\"ok\":false,\"code\":[]}',flush=True)"], "profile")
        self.assertEqual("worker_start_failed", caught.exception.code)

    def test_generation_resource_code_preserves_live_worker(self):
        script = (
            "import sys; print('{\"ok\":true,\"event\":\"ready\",\"profile\":\"profile\"}',flush=True); "
            "sys.stdin.readline(); print('{\"ok\":false,\"code\":\"insufficient_resources\"}',flush=True); "
            "sys.stdin.readline()"
        )
        worker = SubprocessWorker([sys.executable, "-u", "-c", script], "profile")
        try:
            with self.assertRaises(NovelAudioError) as caught:
                worker.analyze({})
            self.assertEqual("insufficient_resources", caught.exception.code)
            self.assertIsNone(worker.process.poll())
        finally:
            worker.close()


class ResourceProbeTest(unittest.TestCase):
    def setUp(self):
        self.gate = importlib.import_module("resource_gate")

    def test_low_resources_block_both_native_model_loaders(self):
        from qwen_backend import QwenTextAdapter, QwenTtsAdapter
        from scripts.novel_audio_server.errors import ResourceUnavailableError
        text = QwenTextAdapter.__new__(QwenTextAdapter)
        text.process = None
        text.profile = None
        text._probe_health = Mock(return_value=None)
        text._command = Mock()
        tts = QwenTtsAdapter.__new__(QwenTtsAdapter)
        tts.model = None
        tts.model_loader = None
        tts.profile = None
        with patch("qwen_backend.ensure_resources", side_effect=ResourceUnavailableError()) as check:
            for adapter, operation in ((text, text.start), (tts, lambda: tts._load_model("unused"))):
                with self.assertRaises(ResourceUnavailableError):
                    operation()
            self.assertEqual(["text", "tts"], [call.args[0] for call in check.call_args_list])
        text._command.assert_not_called()
        self.assertIsNone(tts.model)

    def test_combined_boundary_and_each_insufficient_resource(self):
        good = self.gate.ResourceSnapshot(12288, 8192, 8192)
        self.assertEqual(good, self.gate.ensure_resources(probe=lambda: good))
        for values in [(12287,8192,8192),(12288,8191,8192),(12288,8192,8191)]:
            with self.subTest(values=values), self.assertRaises(NovelAudioError) as caught:
                self.gate.ensure_resources(probe=lambda: self.gate.ResourceSnapshot(*values))
            self.assertEqual("insufficient_resources", caught.exception.code)

    def test_per_model_load_checks_remaining_resources(self):
        for stage, minimum in (("text", 7168), ("tts", 5120)):
            with self.subTest(stage=stage):
                self.gate.ensure_resources(stage, probe=lambda: self.gate.ResourceSnapshot(minimum,4096,4096))
                with self.assertRaises(NovelAudioError):
                    self.gate.ensure_resources(stage, probe=lambda: self.gate.ResourceSnapshot(minimum-1,4096,4096))

    def test_four_b_profile_has_smaller_text_headroom(self):
        good = self.gate.ResourceSnapshot(4096,4096,4096)
        self.gate.ensure_resources("text", profile_id="qwen35-4b-base", probe=lambda: good)
        with self.assertRaises(NovelAudioError):
            self.gate.ensure_resources("text", profile_id="qwen35-9b-voicedesign", probe=lambda: good)

    def test_probe_failure_is_closed_and_has_fixed_code(self):
        for value in (b"N/A", b"999999\n999999", b"x" * 2048):
            with patch.object(self.gate, "_windows_memory", return_value=(8192,8192)), \
                    patch.object(self.gate.subprocess, "run", return_value=SimpleNamespace(returncode=0,stdout=value)):
                with self.assertRaises(NovelAudioError) as caught:
                    self.gate.resource_snapshot()
                self.assertEqual("resource_check_failed", caught.exception.code)

    def test_probe_is_bounded_and_uses_gpu_zero(self):
        with patch.object(self.gate, "_windows_memory", return_value=(10000,11000)), \
                patch.object(self.gate.subprocess, "run", return_value=SimpleNamespace(returncode=0,stdout=b"14000\n")) as run:
            self.assertEqual(self.gate.ResourceSnapshot(14000,10000,11000),self.gate.resource_snapshot())
            self.assertIn("--id=0",run.call_args.args[0])
            self.assertEqual(3,run.call_args.kwargs["timeout"])

    def test_resource_error_is_preserved_by_runtime_acquire(self):
        from scripts.novel_audio_server.runtime import RuntimeManager
        gate = self.gate
        class Factory:
            def start(self, profile):
                gate.ensure_resources(probe=lambda: gate.ResourceSnapshot(0,0,0))
        runtime = RuntimeManager(Factory(), profile_id="profile")
        try:
            with self.assertRaises(NovelAudioError) as caught:
                runtime.acquire("resources", "auto_prefetch", 1)
            self.assertEqual("insufficient_resources", caught.exception.code)
            self.assertEqual("idle",runtime.status().state.value)
        finally:
            runtime.close()
