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
        units = request.as_dict()["units"] if hasattr(request, "as_dict") else request["units"]
        return {
            "assignments": [
                {"unitId": unit["unitId"], "speakerId": "narrator"}
                for unit in units
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

    def analysis_body(self):
        return {
            "bookId": "book", "chapterId": "chapter", "textHash": "hash",
            "analysisVersion": "1", "characters": [],
            "units": [{"unitId": "u1", "text": "旁白"}],
            "previousContext": {"recentAssignments": []},
        }

    def test_repeated_chapter_analysis_reuses_the_first_result(self):
        # 真机实测：合成失败后每次重试都重新分析，白白消耗云端分析额度。
        class CountingDirector(FakeDirector):
            calls = 0

            def analyze(self, request):
                CountingDirector.calls += 1
                return super().analyze(request)

        gateway = NovelAudioGateway(FakeSpeech(), CountingDirector(), "local-token")
        for _ in range(2):
            status, _, body = gateway.respond(
                "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
            )
            self.assertEqual(200, status)
            body["assignments"].clear()  # 调用方修改返回值不得污染缓存
        self.assertEqual(1, CountingDirector.calls)
        _, _, body = gateway.respond(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        )
        self.assertEqual([{"unitId": "u1", "speakerId": "narrator"}], body["assignments"])

    def test_concurrent_identical_analysis_waits_instead_of_busy(self):
        # 真机实测：App 取消后立即重发同一章分析，第二个请求撞单生成槽被回 429，整章判失败。
        import threading

        started, release = threading.Event(), threading.Event()

        class SlowDirector(FakeDirector):
            calls = 0

            def analyze(self, request):
                SlowDirector.calls += 1
                started.set()
                release.wait(2)
                return super().analyze(request)

        gateway = NovelAudioGateway(FakeSpeech(), SlowDirector(), "local-token")
        results = []
        first = threading.Thread(target=lambda: results.append(gateway.respond(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        )))
        first.start()
        self.assertTrue(started.wait(2))
        threading.Timer(0.1, release.set).start()
        status, _, body = gateway.respond(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        )
        first.join(2)
        self.assertEqual(200, status)
        self.assertEqual("narrator", body["assignments"][0]["speakerId"])
        self.assertEqual(200, results[0][0])
        self.assertEqual(1, SlowDirector.calls)

    def test_analysis_survives_the_first_client_leaving_for_a_waiting_retry(self):
        # 真机实测：App 取消首个分析后立即重发；首个请求的取消不能让重发拿到 429。
        import threading

        from lifecycle import RequestContext

        started, release = threading.Event(), threading.Event()

        class SlowDirector(FakeDirector):
            def analyze(self, request, context=None):
                started.set()
                release.wait(2)
                context.check()
                return super().analyze(request)

        gateway = NovelAudioGateway(FakeSpeech(), SlowDirector(), "local-token")
        leaving = RequestContext(5)
        first = threading.Thread(target=gateway.respond, args=(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        ), kwargs={"context": leaving})
        first.start()
        self.assertTrue(started.wait(2))
        leaving.cancel()
        threading.Timer(0.1, release.set).start()
        status, _, body = gateway.respond(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        )
        first.join(2)
        self.assertEqual(200, status)
        self.assertEqual("narrator", body["assignments"][0]["speakerId"])

    def test_different_chapters_still_share_the_single_generation_slot(self):
        import threading

        started, release = threading.Event(), threading.Event()

        class SlowDirector(FakeDirector):
            def analyze(self, request):
                started.set()
                release.wait(2)
                return super().analyze(request)

        gateway = NovelAudioGateway(FakeSpeech(), SlowDirector(), "local-token")
        first = threading.Thread(target=gateway.respond, args=(
            "POST", "/v1/chapter/analyze", "Bearer local-token", self.analysis_body(),
        ))
        first.start()
        self.assertTrue(started.wait(2))
        other = dict(self.analysis_body(), chapterId="chapter-2")
        status, _, body = gateway.respond("POST", "/v1/chapter/analyze", "Bearer local-token", other)
        release.set()
        first.join(2)
        self.assertEqual(429, status)
        self.assertEqual("busy", body["error"]["code"])


if __name__ == "__main__":
    unittest.main()
