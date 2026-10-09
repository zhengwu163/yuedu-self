import unittest

from gateway import NovelAudioGateway
from models import AudioResult, HealthStatus, VoiceAsset


class FakeSpeech:
    def health(self):
        return HealthStatus(ready=True)

    def voices(self):
        return [
            VoiceAsset(
                voice_asset_id="voicestudio.narrator",
                display_name="旁白",
                gender="unknown",
                age_range="adult",
                traits=["清晰"],
                preview_available=True,
            )
        ]

    def match(self, request):
        return self.voices()

    def preview(self, request):
        return AudioResult(b"OggS-preview", "audio/ogg", "profile-v1")

    def synthesize(self, request):
        return AudioResult(b"OggS-synth", "audio/ogg", "profile-v1")


class FakeDirector:
    def __init__(self, ready=True):
        self.ready = ready

    def health(self):
        return HealthStatus(ready=self.ready)

    def analyze(self, request):
        return {
            "assignments": [
                {"unitId": unit["unitId"], "speakerId": "narrator"}
                for unit in request["units"]
            ],
            "newCharacters": [],
            "aliasUpdates": [],
        }


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.gateway = NovelAudioGateway(
            speech=FakeSpeech(),
            director=FakeDirector(),
            token="local-token",
        )

    def test_health_reports_tts_and_director_separately(self):
        status, headers, body = self.gateway.respond(
            "GET", "/v1/health", "Bearer local-token", None,
        )

        self.assertEqual(200, status)
        self.assertEqual("application/json", headers["Content-Type"])
        self.assertTrue(body["ttsReady"])
        self.assertTrue(body["directorReady"])

    def test_tts_can_be_ready_while_director_is_unavailable(self):
        gateway = NovelAudioGateway(
            speech=FakeSpeech(),
            director=FakeDirector(ready=False),
            token="local-token",
        )

        _, _, body = gateway.respond(
            "GET", "/v1/health", "Bearer local-token", None,
        )

        self.assertTrue(body["ttsReady"])
        self.assertFalse(body["directorReady"])

    def test_health_declares_only_metered_operations(self):
        # Android 只对声明计费的操作扣设备试用额度；未声明计费属性的提供方按计费处理。
        class LocalSpeech(FakeSpeech):
            metered = False

        class CloudDirector(FakeDirector):
            metered = True

        cases = (
            (LocalSpeech(), CloudDirector(), ["analysis"]),
            (LocalSpeech(), FakeDirector(), ["analysis"]),
            (FakeSpeech(), CloudDirector(), ["analysis", "tts"]),
        )
        for speech, director, expected in cases:
            with self.subTest(expected=expected):
                gateway = NovelAudioGateway(
                    speech=speech, director=director, token="local-token",
                )
                _, _, body = gateway.respond(
                    "GET", "/v1/health", "Bearer local-token", None,
                )
                self.assertEqual(expected, body["meteredOperations"])

    def test_all_routes_require_bearer_auth(self):
        for route in (
            "/v1/health",
            "/v1/voices",
            "/v1/chapter/analyze",
            "/v1/voices/match",
            "/v1/voices/preview",
            "/v1/tts/synthesize",
        ):
            with self.subTest(route=route):
                status, _, _ = self.gateway.respond("GET", route, None, None)
                self.assertEqual(401, status)

    def test_tts_response_contains_audio_and_profile(self):
        status, headers, body = self.gateway.respond(
            "POST",
            "/v1/tts/synthesize",
            "Bearer local-token",
            {
                "text": "测试",
                "voiceAssetId": "voicestudio.narrator",
                "language": "zh-CN",
                "speed": 1.0,
            },
        )

        self.assertEqual(200, status)
        self.assertEqual("audio/ogg", headers["Content-Type"])
        self.assertEqual("profile-v1", headers["X-TTS-Profile"])
        self.assertEqual(b"OggS-synth", body)

    def test_analysis_is_unavailable_when_director_is_not_ready(self):
        gateway = NovelAudioGateway(
            speech=FakeSpeech(),
            director=FakeDirector(ready=False),
            token="local-token",
        )
        status, _, body = gateway.respond(
            "POST",
            "/v1/chapter/analyze",
            "Bearer local-token",
            {
                "bookId": "book",
                "chapterId": "chapter",
                "textHash": "hash",
                "analysisVersion": "1",
                "characters": [],
                "units": [{"unitId": "u1", "text": "旁白"}],
                "previousContext": {"recentAssignments": []},
            },
        )

        self.assertEqual(503, status)
        self.assertEqual("not_ready", body["error"]["code"])


if __name__ == "__main__":
    unittest.main()
