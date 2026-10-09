import hashlib
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from agent import LocalAgent
from model_registry import ModelRegistryError
from voices import VoiceCatalogError


class LocalAgentStartupTest(unittest.TestCase):
    def test_operator_owned_pid_is_retained_until_operator_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = LocalAgent(self.write_config(root, self.registry()), fake=True)
            agent._write_pid()
            (root / "state/agent.owner.json").write_text("{}", encoding="utf-8")
            agent._clear_pid()
            self.assertTrue((root / "state/agent.pid").exists())
            agent.close()

    def wait_until(self, predicate, timeout=3):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return True
            threading.Event().wait(0.02)
        return False

    def test_stop_marker_closes_active_worker_and_removes_owned_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            agent = LocalAgent(config_path, fake=True)
            agent.runtime.acquire("stop-test", "auto_prefetch", 1)
            worker = agent.runtime._worker
            failures = []

            def run():
                try:
                    agent.serve()
                except Exception as error:
                    failures.append(type(error).__name__)

            thread = threading.Thread(target=run, daemon=True)
            with patch.object(worker, "close", wraps=worker.close) as close:
                thread.start()
                try:
                    self.assertTrue(self.wait_until(
                        lambda: (root / "state/agent.pid").exists()
                    ))
                    (root / "state/agent.stop").touch()
                    thread.join(3)
                    self.assertFalse(thread.is_alive(), "stop marker was not consumed")
                    self.assertEqual([], failures)
                    close.assert_called_once()
                    self.assertFalse((root / "state/agent.pid").exists())
                    self.assertFalse((root / "state/agent.stop").exists())
                    status = json.loads((root / "state/agent.status.json").read_text())
                    self.assertEqual("stopped", status["state"])
                    self.assertFalse(status["activeLease"])
                finally:
                    if thread.is_alive():
                        agent.http.shutdown()
                        thread.join(3)
                    agent.close()

    def test_serve_clears_stale_marker_before_accepting_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            agent = LocalAgent(config_path, fake=True)
            marker = root / "state/agent.stop"
            marker.touch()
            observed = []
            agent.http.serve_forever = lambda: observed.append(marker.exists())
            try:
                agent.serve()
                self.assertEqual([False], observed)
            finally:
                agent.close()

    def test_serve_exception_still_closes_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = LocalAgent(self.write_config(root, self.registry()), fake=True)
            with patch.object(agent.http, "serve_forever", side_effect=RuntimeError), \
                    patch.object(agent.api, "close", wraps=agent.api.close) as close:
                with self.assertRaises(RuntimeError):
                    agent.serve()
                close.assert_called_once()
            agent.close()

    def test_configured_worker_timeouts_reach_factory_and_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            (root / "models.json").write_text(
                json.dumps(self.valid_registry(root)),
                encoding="utf-8",
            )
            value = json.loads(config_path.read_text(encoding="utf-8"))
            value["workerStartupTimeout"] = 321
            value["workerIpcTimeout"] = 432
            config_path.write_text(json.dumps(value), encoding="utf-8")

            with patch("agent.SubprocessWorkerFactory") as factory, \
                    patch("agent.RuntimeManager") as runtime:
                agent = LocalAgent(config_path)
                try:
                    self.assertEqual(
                        321.0,
                        factory.call_args.kwargs["startup_timeout"],
                    )
                    self.assertEqual(
                        432.0,
                        factory.call_args.kwargs["ipc_timeout"],
                    )
                    self.assertEqual(
                        321.0,
                        runtime.call_args.kwargs["startup_timeout"],
                    )
                finally:
                    agent.close()

    def test_unserved_agent_close_does_not_remove_another_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = LocalAgent(self.write_config(root, self.registry()), fake=True)
            pid_path = root / "state/agent.pid"
            pid_path.write_text("1234")
            agent.close()
            self.assertEqual("1234", pid_path.read_text())

    def test_runtime_cleanup_failure_preserves_failed_state_and_pid(self):
        from scripts.novel_audio_server.runtime import WorkerStopError

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = LocalAgent(self.write_config(root, self.registry()), fake=True)
            agent.http.serve_forever = lambda: None
            with patch.object(agent.runtime, "close", side_effect=WorkerStopError):
                with self.assertRaises(WorkerStopError):
                    agent.serve()
            self.assertTrue((root / "state/agent.pid").exists())
            value = json.loads((root / "state/agent.status.json").read_text())
            self.assertEqual("failed", value["state"])
            self.assertEqual("worker_stop_failed", value["errorCode"])
            # Close the bound socket even when runtime cleanup raises.
            self.assertEqual(-1, agent.http.socket.fileno())

    def file_sha256(self, path):
        return hashlib.sha256(Path(path).read_bytes()).hexdigest()

    def directory_sha256(self, path):
        root = Path(path)
        entries = []
        for child in sorted(root.rglob("*")):
            if child.is_file():
                entries.append(
                    (child.relative_to(root).as_posix(), self.file_sha256(child))
                )
        return hashlib.sha256(
            json.dumps(entries, separators=(",", ":")).encode("utf-8")
        ).hexdigest()

    def write_config(self, root, registry):
        models = root / "models"
        models.mkdir()
        (models / "text.gguf").write_bytes(b"text")
        tts = models / "tts"
        tts.mkdir()
        (tts / "config.json").write_text("{}", encoding="utf-8")
        registry_path = root / "models.json"
        registry_path.write_text(json.dumps(registry), encoding="utf-8")

        catalog = root / "voices.json"
        catalog.write_text(
            json.dumps(
                {
                    "version": "1",
                    "voices": [
                        {
                            "voiceAssetId": "local.voice",
                            "displayName": "测试声线",
                            "gender": "unknown",
                            "ageRange": "adult",
                            "traits": ["自然"],
                            "kind": "voicedesign",
                            "prompt": "成年中文旁白，清晰自然。",
                        }
                    ],
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        config = {
            "host": "127.0.0.1",
            "port": 18787,
            "allowLan": False,
            "tokenFile": "state/token",
            "modelRegistry": "models.json",
            "activeProfile": "profile",
            "minimumVramGb": 24,
            "text": {
                "model": "text",
                "modelPath": "models/text.gguf",
                "runner": "runtime/llama-server.exe",
            },
            "tts": {
                "runtime": "runtime/tts-python",
                "pythonExecutable": "runtime/tts-python/python.exe",
                "voiceDesignModel": "models/tts",
                "baseModel": "models/tts",
                "ffmpeg": "runtime/ffmpeg.exe",
            },
            "voiceCatalog": "voices.json",
        }
        config_path = root / "local-model.json"
        config_path.write_text(json.dumps(config), encoding="utf-8")
        return config_path

    def registry(self, text_hash=""):
        return {
            "version": "1",
            "models": [
                {
                    "assetId": "text",
                    "type": "text",
                    "family": "text",
                    "format": "gguf",
                    "path": "models/text.gguf",
                    "sha256": text_hash,
                    "requiredVramGb": 24,
                    "capabilities": ["chapter-analysis"],
                    "adapter": "text",
                },
                {
                    "assetId": "tts",
                    "type": "tts",
                    "family": "tts",
                    "format": "directory",
                    "path": "models/tts",
                    "sha256": "b" * 64,
                    "requiredVramGb": 24,
                    "capabilities": ["speech-synthesis", "voice-design"],
                    "adapter": "tts",
                },
            ],
            "profiles": [
                {
                    "profileId": "profile",
                    "textModel": "text",
                    "ttsModel": "tts",
                    "minVramGb": 24,
                    "maxConcurrency": 1,
                    "capabilities": [
                        "chapter-analysis",
                        "speech-synthesis",
                        "voice-design",
                    ],
                }
            ],
            "activeProfile": "profile",
        }

    def valid_registry(self, root, voice_capability="voice-design"):
        value = self.registry(
            text_hash=self.file_sha256(Path(root) / "models" / "text.gguf")
        )
        value["models"][1]["sha256"] = self.directory_sha256(
            Path(root) / "models" / "tts"
        )
        value["models"][1]["capabilities"] = [
            "speech-synthesis",
            voice_capability,
        ]
        value["profiles"][0]["capabilities"] = [
            "chapter-analysis",
            "speech-synthesis",
            voice_capability,
        ]
        return value

    def test_unverified_profile_prevents_non_fake_agent_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())

            with self.assertRaises(ModelRegistryError) as caught:
                LocalAgent(config_path)

            self.assertEqual("missing_sha256", caught.exception.code)

    def test_agent_identity_changes_with_catalog_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            (root / "models.json").write_text(
                json.dumps(self.valid_registry(root)),
                encoding="utf-8",
            )

            first = LocalAgent(config_path)
            try:
                first_identity = first.backend.profile
            finally:
                first.close()

            catalog_path = root / "voices.json"
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            catalog["voices"][0]["prompt"] = "成年中文旁白，温和自然。"
            catalog_path.write_text(
                json.dumps(catalog, ensure_ascii=False),
                encoding="utf-8",
            )

            second = LocalAgent(config_path)
            try:
                second_identity = second.backend.profile
            finally:
                second.close()

            self.assertNotEqual(first_identity, second_identity)
            self.assertNotIn(str(root), first_identity)
            self.assertNotIn(str(root), second_identity)

    def test_base_profile_requires_a_valid_reference_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            (root / "models.json").write_text(
                json.dumps(self.valid_registry(root, voice_capability="voice-clone")),
                encoding="utf-8",
            )
            (root / "voices.json").write_text(
                json.dumps(
                    {
                        "version": "1",
                        "voices": [
                            {
                                "voiceAssetId": "local.base",
                                "displayName": "Base",
                                "gender": "unknown",
                                "ageRange": "adult",
                                "traits": ["自然"],
                                "kind": "base",
                                "referenceAudio": "state/missing.wav",
                            }
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            with self.assertRaises(VoiceCatalogError) as caught:
                LocalAgent(config_path)

            self.assertEqual("missing_reference_audio", caught.exception.code)

    def test_serve_persists_sanitized_status_and_pid_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = self.write_config(root, self.registry())
            (root / "models.json").write_text(
                json.dumps(self.valid_registry(root)),
                encoding="utf-8",
            )
            agent = LocalAgent(config_path)
            observed = {}

            def serve_forever():
                state_dir = root / "state"
                observed["pid"] = (state_dir / "agent.pid").read_text(
                    encoding="ascii"
                ).strip()
                observed["status"] = json.loads(
                    (state_dir / "agent.status.json").read_text(encoding="utf-8")
                )

            agent.http.serve_forever = serve_forever
            try:
                agent.serve()
            finally:
                agent.close()

            self.assertEqual(str(os.getpid()), observed["pid"])
            self.assertEqual("idle", observed["status"]["state"])
            self.assertNotIn("modelPath", observed["status"])
            self.assertEqual("stopped", json.loads(
                (root / "state" / "agent.status.json").read_text(
                    encoding="utf-8"
                )
            )["state"])
            self.assertFalse((root / "state" / "agent.pid").exists())


if __name__ == "__main__":
    unittest.main()
