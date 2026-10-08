import io
import json
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path
from urllib.error import HTTPError, URLError

from backend import FakeBackend
from config import ConfigError, load_config
from scripts.novel_audio_server.errors import (
    InvalidBackendResponseError,
    RunnerIncompatibleError,
    RunnerUnavailableError,
)
from model_registry import ModelAsset, RuntimeProfile
from qwen_backend import QwenBackend, QwenTextAdapter, QwenTtsAdapter
from test_support import requires_symlinks
from voices import VoiceCatalog


ROOT = Path(__file__).resolve().parent


def reference_wav():
    """Structural PCM fixture only; never a recording or a claimed user voice."""
    output = io.BytesIO()
    with wave.open(output, "wb") as target:
        target.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        target.writeframes(b"\x00\x00" * 24)
    return output.getvalue()


class _FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, limit=None):
        if limit is None:
            return self.body
        return self.body[:limit]


class _FakeOpener:
    def __init__(self, health_body=None, response_body=None, error=None, health_sequence=None):
        self.health_body = health_body
        self.response_body = response_body
        self.error = error
        self.health_sequence = list(health_sequence or ())
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append((request, timeout))
        if self.error is not None:
            raise self.error
        if request.full_url.endswith("/health"):
            if self.health_sequence:
                body = self.health_sequence.pop(0)
                if isinstance(body, Exception):
                    raise body
                return _FakeResponse(body)
            return _FakeResponse(self.health_body or b'{"status":"ok"}')
        return _FakeResponse(self.response_body or b"{}")


class _FakeProcess:
    def __init__(self, returncode=None):
        self.returncode = returncode
        self.terminate_count = 0
        self.kill_count = 0

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminate_count += 1

    def kill(self):
        self.kill_count += 1

    def wait(self, timeout):
        return self.returncode


class LocalBackendTest(unittest.TestCase):
    def runtime_profile(self):
        text_asset = ModelAsset(
            asset_id="future-text",
            model_type="text",
            family="future-text",
            model_format="gguf",
            path=Path("/models/future-text.gguf"),
            sha256="a" * 64,
            required_vram_gb=24.0,
            capabilities=("chapter-analysis",),
            adapter="llama.cpp-openai-compatible",
            source="official",
            license="license",
            enabled=True,
        )
        tts_asset = ModelAsset(
            asset_id="future-tts",
            model_type="tts",
            family="future-tts",
            model_format="directory",
            path=Path("/models/future-tts"),
            sha256="b" * 64,
            required_vram_gb=24.0,
            capabilities=("speech-synthesis", "voice-design"),
            adapter="qwen3-tts-native",
            source="official",
            license="license",
            enabled=True,
        )
        return RuntimeProfile(
            profile_id="future-profile",
            text_model_id="future-text",
            tts_model_id="future-tts",
            min_vram_gb=24.0,
            max_concurrency=1,
            capabilities=("chapter-analysis", "speech-synthesis", "voice-design"),
            registry_version="1",
            text_asset=text_asset,
            tts_asset=tts_asset,
        )

    def test_config_defaults_to_nine_b_model_and_redacts_paths_from_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(
                (ROOT / "local-model.example.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )
            config = load_config(path)
            self.assertEqual("qwen3.5-9b-q4_k_m", config.text.model)
            self.assertIn("local-qwen-v1-", config.profile)
            self.assertNotIn("model.gguf", config.startup_summary())
            self.assertNotIn("agent-token", repr(config))

    def test_config_defaults_text_backend_port_start_timeout_and_ffprobe_sibling(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            path.write_text(
                (ROOT / "local-model.example.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )

            config = load_config(path)

            self.assertEqual(11435, config.text.backend_port)
            self.assertEqual(180, config.text.start_timeout)
            self.assertEqual(
                config.tts.ffmpeg.with_name("ffprobe.exe"),
                config.tts.ffprobe,
            )

    def test_config_rejects_invalid_text_backend_port_and_timeout(self):
        for key, value in (
            ("backendPort", 0),
            ("backendPort", 65536),
            ("backendPort", True),
            ("startTimeout", 0),
            ("startTimeout", 601),
            ("startTimeout", float("inf")),
        ):
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "local-model.json"
                config_value = json.loads(
                    (ROOT / "local-model.example.json").read_text(encoding="utf-8")
                )
                config_value["text"][key] = value
                path.write_text(json.dumps(config_value), encoding="utf-8")

                with self.assertRaises(ConfigError):
                    load_config(path)

    @requires_symlinks
    def test_config_rejects_symlinked_private_paths_before_resolve(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root.parent / f"{root.name}-outside"
            outside.mkdir()
            symlink = root / "state" / "token-link"
            symlink.parent.mkdir()
            symlink.symlink_to(outside / "token")
            path = root / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            value["tokenFile"] = "state/token-link"
            path.write_text(json.dumps(value), encoding="utf-8")

            with self.assertRaises(ConfigError):
                load_config(path)

    def test_config_allows_external_model_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            value["text"]["modelPath"] = str(root.parent / "external-model.gguf")
            path.write_text(json.dumps(value), encoding="utf-8")

            config = load_config(path)

            self.assertEqual(
                (root.parent / "external-model.gguf").resolve(),
                config.text.model_path,
            )

    def test_config_accepts_a_future_text_model_name(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            value["text"]["model"] = "future-text-v2"
            value["text"]["modelPath"] = "models/future-text-v2.gguf"
            path.write_text(json.dumps(value), encoding="utf-8")

            config = load_config(path)

            self.assertEqual("future-text-v2", config.text.model)
            self.assertTrue(config.text.model_path.name.endswith(".gguf"))

    def test_config_can_load_a_user_managed_runtime_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            value["modelRegistry"] = "models.json"
            value["activeProfile"] = "future-profile"
            registry = {
                "version": "1",
                "models": [
                    {
                        "assetId": "future-text",
                        "type": "text",
                        "family": "future-text",
                        "format": "gguf",
                        "path": "models/future.gguf",
                        "requiredVramGb": 24,
                        "capabilities": ["chapter-analysis"],
                        "adapter": "llama.cpp-openai-compatible"
                    },
                    {
                        "assetId": "future-tts",
                        "type": "tts",
                        "family": "future-tts",
                        "format": "directory",
                        "path": "models/future-tts",
                        "requiredVramGb": 24,
                        "capabilities": ["speech-synthesis"],
                        "adapter": "future-tts-adapter"
                    }
                ],
                "profiles": [
                    {
                        "profileId": "future-profile",
                        "textModel": "future-text",
                        "ttsModel": "future-tts",
                        "minVramGb": 24,
                        "maxConcurrency": 1,
                        "capabilities": ["chapter-analysis", "speech-synthesis"]
                    }
                ],
                "activeProfile": "future-profile"
            }
            path.write_text(json.dumps(value), encoding="utf-8")
            (root / "models.json").write_text(json.dumps(registry), encoding="utf-8")

            config = load_config(path)
            loaded = config.load_model_registry()

            self.assertEqual("future-profile", loaded.active_profile().profile_id)
            self.assertEqual(
                (root / "models" / "future.gguf").resolve(),
                loaded.active_profile().text_asset.path,
            )

    def test_public_bind_requires_explicit_lan_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            value["host"] = "0.0.0.0"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(ConfigError):
                load_config(path)

    def test_voice_match_is_deterministic_and_excludes_narrator(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        first = catalog.match(
            {"traits": ["清朗"]}, [], {"gender": "male"}, ["voice-design"]
        )
        second = catalog.match(
            {"traits": ["清朗"]}, [], {"gender": "male"}, ["voice-design"]
        )
        self.assertEqual(first, second)
        self.assertTrue(first)
        self.assertTrue(all(item["voiceAssetId"] != "local.qwen3-tts.narrator" for item in first))
        self.assertTrue(
            catalog.contains("local.qwen3-tts.narrator", ["voice-design"])
        )

    def test_voice_catalog_filters_assets_by_active_capabilities(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / "reference.wav"
            reference.write_bytes(reference_wav())
            path = root / "voices.json"
            path.write_text(
                json.dumps(
                    {
                        "version": "1",
                        "voices": [
                            {
                                "voiceAssetId": "local.design",
                                "displayName": "设计声线",
                                "gender": "female",
                                "ageRange": "adult",
                                "traits": ["自然"],
                                "kind": "voicedesign",
                                "prompt": "成年中文女声，清晰自然。",
                            },
                            {
                                "voiceAssetId": "local.base",
                                "displayName": "克隆声线",
                                "gender": "male",
                                "ageRange": "adult",
                                "traits": ["沉稳"],
                                "kind": "base",
                                "referenceAudio": "reference.wav",
                            },
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            catalog = VoiceCatalog(path, root)

            design_voices = catalog.public_voices(["voice-design"])
            base_voices = catalog.public_voices(["voice-clone"])

            self.assertEqual(["local.design"], [item["voiceAssetId"] for item in design_voices])
            self.assertEqual(["local.base"], [item["voiceAssetId"] for item in base_voices])
            self.assertFalse(catalog.contains("local.base", ["voice-design"]))
            self.assertFalse(catalog.contains("local.design", ["voice-clone"]))
            self.assertEqual(
                ["local.base"],
                [
                    item["voiceAssetId"]
                    for item in catalog.match(
                        {"traits": []}, [], None, ["voice-clone"]
                    )
                ],
            )

    def test_config_diagnostics_do_not_expose_service_root(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local-model.json"
            value = json.loads((ROOT / "local-model.example.json").read_text())
            path.write_text(json.dumps(value), encoding="utf-8")
            config = load_config(path)

            self.assertNotIn(str(config.root), repr(config))
            self.assertNotIn(str(config.root), config.startup_summary())

    def test_fake_backend_returns_complete_analysis_and_ogg_fixture(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        backend = FakeBackend(catalog)
        request = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [],
            "units": [{"unitId": "u1", "text": "正文"}],
            "previousContext": {"recentAssignments": []},
        }
        response = backend.analyze(request)
        self.assertEqual("u1", response["assignments"][0]["unitId"])
        audio = backend.synthesize(
            {
                "text": "正文",
                "voiceAssetId": "local.qwen3-tts.narrator",
                "language": "zh-CN",
                "speed": 1.0,
            }
        )
        self.assertTrue(audio.startswith(b"OggS"))

    def test_qwen_tts_keeps_voicedesign_and_base_operations_separate(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        operations = []

        def invoke(operation):
            operations.append(operation)
            return {"audio": "T2dnUw=="}

        adapter = QwenTtsAdapter(object(), catalog, invoke=invoke)
        adapter.synthesize(
            {
                "text": "旁白",
                "voiceAssetId": "local.qwen3-tts.young-male",
                "language": "zh-CN",
                "speed": 1.0,
            }
        )
        self.assertEqual("voice_design", operations[0]["operation"])
        self.assertIn("instruct", operations[0])
        self.assertNotIn("refAudio", operations[0])

    def test_qwen_text_uses_bound_profile_model_name_and_path(self):
        profile = self.runtime_profile()
        requests = []
        process_commands = []

        class FakeProcess:
            def terminate(self):
                return None

            def wait(self, timeout):
                return None

        def request(payload):
            requests.append(payload)
            return {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "assignments": [
                                        {"unitId": "u1", "speakerId": "narrator"}
                                    ],
                                    "newCharacters": [],
                                    "aliasUpdates": [],
                                }
                            )
                        }
                    }
                ]
            }

        def runner(command, **kwargs):
            process_commands.append(command)
            return FakeProcess()

        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        adapter = QwenTextAdapter(
            config,
            profile=profile,
            opener=_FakeOpener(
                health_sequence=[
                    URLError("no listener"),
                    b'{"status":"ok"}',
                ]
            ),
            runner=runner,
            request=request,
        )
        adapter.analyze(
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [],
                "units": [{"unitId": "u1", "text": "正文"}],
                "previousContext": {"recentAssignments": []},
            }
        )
        adapter.start()

        self.assertEqual("future-text", requests[0]["model"])
        self.assertIn(str(profile.text_asset.path), process_commands[0])

    def test_qwen_text_starts_runner_on_fixed_port_and_polls_health(self):
        profile = self.runtime_profile()
        opener = _FakeOpener(
            health_sequence=[
                URLError("no listener"),
                b'{"status":"ok"}',
            ]
        )
        process = _FakeProcess()
        commands = []

        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()

        def runner(command, **kwargs):
            commands.append(command)
            return process

        adapter = QwenTextAdapter(
            config,
            profile=profile,
            opener=opener,
            runner=runner,
            sleeper=lambda _: None,
        )

        adapter.start()

        command = commands[0]
        self.assertEqual("127.0.0.1", command[command.index("--host") + 1])
        self.assertEqual("11435", command[command.index("--port") + 1])
        self.assertNotIn("0", command[command.index("--port") + 1:])
        self.assertEqual(2, len(opener.requests))
        self.assertTrue(opener.requests[0][0].full_url.endswith("/health"))

    def test_qwen_text_waits_for_ready_after_loading_health(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        opener = _FakeOpener(
            health_sequence=[
                URLError("no listener"),
                b'{"status":"loading"}',
                b'{"status":"ready"}',
            ]
        )
        process = _FakeProcess()
        calls = []
        adapter = QwenTextAdapter(
            config,
            opener=opener,
            runner=lambda command, **kwargs: calls.append(command) or process,
            sleeper=lambda _: None,
        )

        adapter.start()

        self.assertEqual(3, len(opener.requests))
        self.assertEqual(1, len(calls))

    def test_qwen_text_rejects_preexisting_healthy_backend_port(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        opener = _FakeOpener()
        calls = []
        adapter = QwenTextAdapter(
            config,
            opener=opener,
            runner=lambda *args, **kwargs: calls.append(args) or _FakeProcess(),
        )

        with self.assertRaises(RunnerUnavailableError) as caught:
            adapter.start()

        self.assertEqual("runner_unavailable", caught.exception.code)
        self.assertEqual([], calls)

    def test_qwen_text_rejects_preexisting_loading_backend_port(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        opener = _FakeOpener(health_body=b'{"status":"loading"}')
        calls = []
        adapter = QwenTextAdapter(
            config,
            opener=opener,
            runner=lambda *args, **kwargs: calls.append(args) or _FakeProcess(),
        )

        with self.assertRaises(RunnerUnavailableError) as caught:
            adapter.start()

        self.assertEqual("runner_unavailable", caught.exception.code)
        self.assertEqual([], calls)

    def test_qwen_text_request_uses_json_mode_and_disables_thinking(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        opener = _FakeOpener(
            response_body=json.dumps(
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "assignments": [],
                                        "newCharacters": [],
                                        "aliasUpdates": [],
                                    }
                                )
                            }
                        }
                    ]
                }
            ).encode()
        )
        adapter = QwenTextAdapter(config, opener=opener)

        adapter._request({"messages": []})

        request = opener.requests[0][0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual({"type": "json_object"}, payload["response_format"])
        self.assertEqual({"enable_thinking": False}, payload["chat_template_kwargs"])
        self.assertNotIn("enable_thinking", payload)
        self.assertEqual("POST", request.method)

    def test_qwen_text_rejects_redirect_and_oversized_body(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        redirecting = QwenTextAdapter(
            config,
            opener=_FakeOpener(
                error=HTTPError(
                    "http://127.0.0.1",
                    302,
                    "redirect",
                    {},
                    None,
                )
            ),
        )
        with self.assertRaises(RunnerUnavailableError) as redirect_error:
            redirecting._request({"messages": []})
        self.assertEqual("runner_unavailable", redirect_error.exception.code)

        oversized = QwenTextAdapter(
            config,
            opener=_FakeOpener(response_body=b"x" * (2 * 1024 * 1024 + 1)),
        )
        with self.assertRaises(InvalidBackendResponseError):
            oversized._request({"messages": []})

    def test_qwen_text_sends_short_unit_aliases_and_restores_android_ids(self):
        long_ids = ["u_" + "a1" * 32, "u_" + "b2" * 32]
        sent = []
        content = {
            "assignments": {"u2": "narrator", "u1": "narrator"},
            "newCharacters": [],
            "aliasUpdates": [],
        }

        def request(payload):
            sent.append(payload["messages"][1]["content"])
            self.assertIn('"assignments":{', payload["messages"][0]["content"])
            return {"choices": [{"message": {"content": json.dumps(content)}}]}

        config = type(
            "Config",
            (),
            {"text": type("Text", (), {"model": "legacy-model", "backend_port": 11435})()},
        )()
        adapter = QwenTextAdapter(config, request=request)

        result = adapter.analyze(
            {
                "bookId": "physical:" + "d4" * 32,
                "chapterId": "chapter:" + "e5" * 32,
                "textHash": "f6" * 32,
                "analysisVersion": "1",
                "characters": [],
                "units": [
                    {"unitId": long_ids[0], "text": "夜色落下。"},
                    {"unitId": long_ids[1], "text": "回家吧。"},
                ],
                "previousContext": {"recentAssignments": []},
            }
        )

        self.assertEqual(
            [long_ids[1], long_ids[0]],
            [item["unitId"] for item in result["assignments"]],
        )
        self.assertIn('"u1"', sent[0])
        for unit_id in long_ids:
            self.assertNotIn(unit_id, sent[0])

    def test_qwen_text_normalizes_response_and_strips_private_fields(self):
        config = type(
            "Config",
            (),
            {
                "text": type(
                    "Text",
                    (),
                    {
                        "model": "legacy-model",
                        "model_path": Path("/models/legacy.gguf"),
                        "runner": Path("/runtime/llama-server.exe"),
                        "context_length": 8192,
                        "gpu_layers": 999,
                        "backend_port": 11435,
                        "start_timeout": 1,
                    },
                )()
            },
        )()
        content = {
            "assignments": [
                {"unitId": "u1", "speakerId": "narrator", "privatePath": "/tmp/x"}
            ],
            "newCharacters": [],
            "aliasUpdates": [],
            "privatePrompt": "不要返回",
        }
        response = {
            "choices": [
                {"message": {"content": json.dumps(content, ensure_ascii=False)}}
            ]
        }
        adapter = QwenTextAdapter(config, request=lambda _: response)

        result = adapter.analyze(
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [],
                "units": [{"unitId": "u1", "text": "正文"}],
                "previousContext": {"recentAssignments": []},
            }
        )

        self.assertEqual(
            {
                "assignments": [{"unitId": "u1", "speakerId": "narrator"}],
                "newCharacters": [],
                "aliasUpdates": [],
            },
            result,
        )

    def test_qwen_tts_builds_base_operation_without_mixing_voice_design_args(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / "reference.wav"
            reference.write_bytes(reference_wav())
            catalog_path = root / "voices.json"
            catalog_path.write_text(
                json.dumps(
                    {
                        "version": "1",
                        "voices": [
                            {
                                "voiceAssetId": "local.base",
                                "displayName": "Base",
                                "kind": "base",
                                "referenceAudio": "reference.wav",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            catalog = VoiceCatalog(catalog_path, root)
            adapter = QwenTtsAdapter(
                type("Config", (), {})(),
                catalog,
                invoke=lambda operation: {"audio": "T2dnUw=="},
            )

            adapter.synthesize(
                {
                    "text": "旁白",
                    "voiceAssetId": "local.base",
                    "language": "zh-CN",
                    "speed": 1.0,
                }
            )

            operation = adapter.last_operation
            self.assertEqual("voice_clone", operation["operation"])
            self.assertTrue(operation["xVectorOnlyMode"])
            self.assertIsNone(operation["refText"])
            self.assertNotIn("instruct", operation)

    def test_qwen_backend_metadata_uses_current_profile_identity(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        profile = self.runtime_profile()
        backend = QwenBackend(
            type("Config", (), {"profile": "legacy-profile"})(),
            catalog,
            profile=profile,
            text=type("Text", (), {"close": lambda self: None})(),
            tts=type("Tts", (), {"close": lambda self: None})(),
        )
        backend.profile = "verified-identity"

        self.assertEqual("verified-identity", backend.runtime_metadata()["identity"])

    def test_qwen_tts_uses_bound_profile_asset(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        profile = self.runtime_profile()
        operations = []

        def invoke(operation):
            operations.append(operation)
            return {"audio": "T2dnUw=="}

        adapter = QwenTtsAdapter(
            object(),
            catalog,
            profile=profile,
            invoke=invoke,
        )
        adapter.synthesize(
            {
                "text": "旁白",
                "voiceAssetId": "local.qwen3-tts.young-male",
                "language": "zh-CN",
                "speed": 1.0,
            }
        )

        self.assertEqual("future-tts", operations[0]["modelAssetId"])
        self.assertEqual(str(profile.tts_asset.path), operations[0]["modelPath"])

    def test_qwen_backend_exposes_bound_profile_metadata(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        profile = self.runtime_profile()
        backend = QwenBackend(
            type("Config", (), {"profile": "legacy-profile"})(),
            catalog,
            profile=profile,
            text=type("Text", (), {"close": lambda self: None})(),
            tts=type("Tts", (), {"close": lambda self: None})(),
        )

        metadata = backend.runtime_metadata()

        self.assertEqual(profile.identity, backend.profile)
        self.assertEqual(profile.identity, metadata["identity"])
        self.assertEqual(list(profile.capabilities), metadata["capabilities"])

    def test_qwen_backend_preserves_legacy_positional_adapter_arguments(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        text = type("Text", (), {"close": lambda self: None})()
        tts = type("Tts", (), {"close": lambda self: None})()

        backend = QwenBackend(
            type("Config", (), {"profile": "legacy-profile"})(),
            catalog,
            text,
            tts,
        )

        self.assertIs(text, backend.text)
        self.assertIs(tts, backend.tts)

    def test_qwen_backend_starts_text_runner_before_analysis(self):
        catalog = VoiceCatalog(ROOT / "voices" / "standard.json")
        calls = []

        class Text:
            def start(self):
                calls.append("start")

            def analyze(self, request):
                calls.append("analyze")
                return {
                    "assignments": [
                        {"unitId": "u1", "speakerId": "narrator"}
                    ],
                    "newCharacters": [],
                    "aliasUpdates": [],
                }

            def close(self):
                return None

        backend = QwenBackend(
            type("Config", (), {"profile": "legacy-profile"})(),
            catalog,
            text=Text(),
            tts=type("Tts", (), {"close": lambda self: None})(),
        )

        backend.analyze(
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [],
                "units": [{"unitId": "u1", "text": "正文"}],
                "previousContext": {"recentAssignments": []},
            }
        )

        self.assertEqual(["start", "analyze"], calls)


if __name__ == "__main__":
    unittest.main()
