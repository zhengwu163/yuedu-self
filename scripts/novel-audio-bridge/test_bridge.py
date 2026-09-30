"""百炼临时桥接服务的离线行为测试。"""
import json
import tempfile
import unittest
from pathlib import Path

from protocol import BridgeError
from bridge import (
    BridgeApi,
    BridgeConfig,
    CloudQuotaError,
    LocalQuotaError,
    UsageGuard,
    VoiceCatalog,
    parse_analysis_json,
    parse_tts_audio_url,
)


class FakeCloud:
    def __init__(self):
        self.analysis_requests = []
        self.tts_requests = []
        self.analysis_response = {
            "assignments": [
                {"unitId": "u1", "speakerId": "narrator"},
                {"unitId": "u2", "speakerId": "char_1"},
            ],
            "newCharacters": [],
            "aliasUpdates": [],
        }
        self.audio = b"converted-ogg"

    def analyze(self, request):
        self.analysis_requests.append(request)
        return self.analysis_response

    def synthesize(self, request):
        self.tts_requests.append(request)
        return self.audio


class BridgeConfigTest(unittest.TestCase):
    def test_loads_root_env_file_without_printing_key(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "novel-audio.local.env"
            path.write_text(
                "DASHSCOPE_API_KEY=secret-key\n"
                "BRIDGE_TOKEN=local-token\n"
                "BRIDGE_PORT=8799\n",
                encoding="utf-8",
            )
            config = BridgeConfig.from_env_file(path)

        self.assertEqual("secret-key", config.dashscope_api_key)
        self.assertEqual("local-token", config.bridge_token)
        self.assertEqual(8799, config.port)
        self.assertNotIn("secret-key", config.startup_summary())

    def test_models_are_fixed_to_selected_defaults(self):
        config = BridgeConfig(dashscope_api_key="key")

        self.assertEqual("qwen3.7-plus", config.text_model)
        self.assertEqual("qwen3-tts-instruct-flash", config.tts_model)

    def test_bearer_key_characters_are_preserved_without_logging(self):
        for value in ("sk-test.part.one.two", "sk-Test_123-~+/.==", "x" * 4096):
            with self.subTest(value_length=len(value)), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "novel-audio.local.env"
                path.write_text(f"DASHSCOPE_API_KEY={value}\nBRIDGE_TOKEN=local-test\n")
                config = BridgeConfig.from_env_file(path)
                self.assertEqual(value, config.dashscope_api_key)
                self.assertNotIn(value, config.startup_summary())
                self.assertNotIn(value, repr(config))

    def test_invalid_bearer_key_is_rejected_without_echoing_value(self):
        for value in ("sk-test bad", "sk-test\tbad", "sk-test\rInjected: yes",
                      "sk-test\nInjected: yes", "sk-test\u0000bad", "sk-中文",
                      "sk-test:bad", "sk-test;bad", "=bad", "sk-test=bad", "x" * 4097):
            with self.subTest(value_length=len(value)), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "novel-audio.local.env"
                path.write_text(f"DASHSCOPE_API_KEY={value}\nBRIDGE_TOKEN=local-test\n")
                with self.assertRaises(ValueError) as caught:
                    BridgeConfig.from_env_file(path)
                self.assertNotIn(value, str(caught.exception))

    def test_missing_key_is_not_ready(self):
        config = BridgeConfig()

        self.assertFalse(config.cloud_ready())
        self.assertIn("API Key", config.startup_summary())


class ProtocolParserTest(unittest.TestCase):
    def test_analysis_parser_accepts_json_code_fence(self):
        value = parse_analysis_json(
            "```json\n"
            '{"assignments":[{"unitId":"u1","speakerId":"narrator"}],'
            '"newCharacters":[],"aliasUpdates":[]}\n'
            "```"
        )

        self.assertEqual("narrator", value["assignments"][0]["speakerId"])

    def test_analysis_parser_rejects_missing_arrays(self):
        with self.assertRaises(ValueError):
            parse_analysis_json('{"assignments":[]}')

    def test_tts_parser_requires_audio_url(self):
        self.assertEqual(
            "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav",
            parse_tts_audio_url(
                {"output": {"audio": {"url": "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/audio.wav"}}}
            ),
        )
        with self.assertRaises(BridgeError) as caught:
            parse_tts_audio_url({"output": {"audio": {}}})
        self.assertEqual("tts_missing_audio_url", caught.exception.code)
        self.assertEqual(
            "https://dashscope-a717.oss-cn-beijing.aliyuncs.com/audio.wav",
            parse_tts_audio_url(
                {"output": {"audio": {
                    "url": "http://dashscope-a717.oss-cn-beijing.aliyuncs.com/audio.wav"
                }}}
            ),
        )

    def test_tts_url_rejections_are_fixed_and_never_echo_url(self):
        base = "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com"
        for url in ("https://secret.invalid/a.wav", base + ":bad/a.wav?secret",
                    base + "/a.mp3?secret", base + "/a.wav#secret",
                    base + "/a.wav?secret\n", base + "/a.wav?\x00secret",
                    "file:///secret.wav", "https://user:secret@" + base[8:] + "/a.wav"):
            with self.subTest():
                with self.assertRaises(BridgeError) as caught:
                    parse_tts_audio_url({"output": {"audio": {"url": url}}})
                self.assertEqual("tts_unsafe_audio_url", str(caught.exception))

    def test_observed_result_host_does_not_allow_neighbor_buckets(self):
        for host in ("dashscope-aaaa.oss-cn-beijing.aliyuncs.com",
                     "dashscope-a717.oss-cn-wulanchabu.aliyuncs.com",
                     "dashscope-a717.oss-cn-beijing.aliyuncs.com.evil.invalid",
                     "other.oss-cn-beijing.aliyuncs.com",
                     "dashscope-a717.oss-cn-beijing-internal.aliyuncs.com"):
            with self.subTest(host=host):
                with self.assertRaises(BridgeError) as caught:
                    parse_tts_audio_url({"output": {"audio": {"url": "https://" + host + "/a.wav"}}})
                self.assertEqual("tts_unsafe_audio_url", caught.exception.code)


class VoiceCatalogTest(unittest.TestCase):
    def test_narrator_is_separate_and_matching_is_deterministic(self):
        catalog = VoiceCatalog()
        first = catalog.match({"traits": ["温暖"]}, [])
        second = catalog.match({"traits": ["温暖"]}, [])

        self.assertEqual(first[0]["voiceAssetId"], second[0]["voiceAssetId"])
        self.assertEqual("bailian.narrator", catalog.narrator_id)
        self.assertNotEqual(catalog.narrator_id, first[0]["voiceAssetId"])

    def test_matching_prefers_unused_voice(self):
        catalog = VoiceCatalog()
        candidates = catalog.match({"traits": []}, ["bailian.cherry"])

        self.assertNotEqual("bailian.cherry", candidates[0]["voiceAssetId"])

class UsageGuardTest(unittest.TestCase):
    def test_persists_trial_limits_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            guard = UsageGuard(
                state,
                limits={
                    "analysis_requests": 1,
                    "analysis_characters": 10,
                    "tts_requests": 1,
                    "tts_characters": 4,
                },
            )
            guard.reserve("analysis", 5)
            with self.assertRaises(LocalQuotaError):
                guard.reserve("analysis", 1)

            reloaded = UsageGuard(
                state,
                limits={
                    "analysis_requests": 1,
                    "analysis_characters": 10,
                    "tts_requests": 1,
                    "tts_characters": 4,
                },
            )
            with self.assertRaises(LocalQuotaError):
                reloaded.reserve("analysis", 1)

    def test_corrupt_state_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            state.write_text("{broken", encoding="utf-8")
            guard = UsageGuard(
                state,
                limits={
                    "analysis_requests": 5,
                    "analysis_characters": 100,
                    "tts_requests": 5,
                    "tts_characters": 100,
                },
            )
            with self.assertRaises(LocalQuotaError):
                guard.reserve("analysis", 1)


class BridgeApiTest(unittest.TestCase):
    def setUp(self):
        self.cloud = FakeCloud()
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.config = BridgeConfig(
            dashscope_api_key="cloud-key",
            bridge_token="bridge-token",
            ffmpeg_path="ffmpeg",
        )
        self.api = BridgeApi(
            self.config,
            self.cloud,
            usage=UsageGuard(
                Path(directory.name) / "state.db",
                limits={
                    "analysis_requests": 5,
                    "analysis_characters": 1000,
                    "tts_requests": 5,
                    "tts_characters": 1000,
                },
            ),
            ffmpeg_available=True,
        )

    def call(self, path, body=None, token="Bearer bridge-token"):
        method = "GET" if body is None else "POST"
        return self.api.respond(method, path, token, body)

    def test_health_and_auth(self):
        code, _, body = self.call("/v1/health")
        self.assertEqual(200, code)
        self.assertEqual(
            {
                "status": "ok",
                "apiVersion": "1",
                "directorReady": True,
                "ttsReady": True,
            },
            body,
        )
        self.assertEqual(401, self.call("/v1/health", token="wrong")[0])

    def test_analyze_returns_contract_without_forwarding_raw_text_as_response(self):
        code, _, body = self.call(
            "/v1/chapter/analyze",
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [
                    {
                        "characterId": "char_1",
                        "displayName": "萧炎",
                        "stableAliases": [],
                    }
                ],
                "units": [
                    {"unitId": "u1", "text": "旁白"},
                    {"unitId": "u2", "text": "你好"},
                ],
                "previousContext": {"recentAssignments": []},
            },
        )
        self.assertEqual(200, code)
        self.assertEqual(["u1", "u2"], [item["unitId"] for item in body["assignments"]])
        self.assertNotIn("text", body)
        self.assertEqual(1, len(self.cloud.analysis_requests))

    def test_analyze_rejects_assignment_outside_request(self):
        self.cloud.analysis_response = {
            "assignments": [{"unitId": "unknown", "speakerId": "narrator"}],
            "newCharacters": [],
            "aliasUpdates": [],
        }
        code, _, body = self.call(
            "/v1/chapter/analyze",
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [],
                "units": [{"unitId": "u1", "text": "正文"}],
                "previousContext": {"recentAssignments": []},
            },
        )
        self.assertEqual(502, code)
        self.assertEqual("invalid_cloud_response", body["error"]["code"])

    def test_tts_returns_audio_and_profile(self):
        code, headers, body = self.call(
            "/v1/tts/synthesize",
            {
                "text": "测试",
                "voiceAssetId": "bailian.narrator",
                "language": "zh-CN",
                "speed": 1.0,
            },
        )
        self.assertEqual(200, code)
        self.assertEqual("audio/ogg", headers["Content-Type"])
        self.assertTrue(headers["X-TTS-Profile"].startswith("bailian-qwen3-tts-instruct-flash-v1-"))
        self.assertEqual(b"converted-ogg", body)
        self.assertEqual("Ethan", self.cloud.tts_requests[0]["voice"])

    def test_cloud_free_quota_error_is_bounded(self):
        self.cloud.synthesize = lambda request: (_ for _ in ()).throw(
            CloudQuotaError()
        )
        code, _, body = self.call(
            "/v1/tts/synthesize",
            {
                "text": "测试",
                "voiceAssetId": "bailian.narrator",
                "language": "zh-CN",
                "speed": 1.0,
            },
        )
        self.assertEqual(429, code)
        self.assertEqual("free_quota_only", body["error"]["code"])


if __name__ == "__main__":
    unittest.main()
