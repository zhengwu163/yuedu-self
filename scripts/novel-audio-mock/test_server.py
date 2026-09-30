"""开发 Mock 的协议测试，不涉及真实模型。"""
import unittest
from server import MockApi


class MockApiTest(unittest.TestCase):
    def setUp(self):
        self.api = MockApi("dev-token")

    def call(self, path, body=None, token="Bearer dev-token"):
        return self.api.respond("GET" if body is None else "POST", path, token, body)

    def test_health_separates_missing_audio_fixture(self):
        code, _, body = self.call("/v1/health")
        self.assertEqual(200, code)
        self.assertEqual("1", body["apiVersion"])
        self.assertTrue(body["directorReady"])
        self.assertFalse(body["ttsReady"])

    def test_auth_is_required_for_every_endpoint(self):
        for path in ("/v1/health", "/v1/voices", "/v1/chapter/analyze",
                     "/v1/voices/match", "/v1/voices/preview", "/v1/tts/synthesize"):
            self.assertEqual(401, self.call(path, token="wrong")[0])

    def test_analysis_covers_units_and_does_not_promote_contextual_aliases(self):
        text = "“师傅，你终于醒了。”"
        code, _, response = self.call("/v1/chapter/analyze", {
            "bookId": "book", "chapterId": "chapter", "textHash": "hash", "analysisVersion": "1",
            "characters": [], "previousContext": {"recentAssignments": []},
            "units": [{"unitId": "u1", "text": "萧炎望着老人。"}, {"unitId": "u2", "text": text}]
        })
        self.assertEqual(200, code)
        self.assertEqual(["u1", "u2"], [v["unitId"] for v in response["assignments"]])
        self.assertEqual("narrator", response["assignments"][0]["speakerId"])
        self.assertEqual([], response["aliasUpdates"])
        self.assertNotIn("text", response)

    def test_invalid_analysis_is_rejected(self):
        self.assertEqual(400, self.call("/v1/chapter/analyze", {})[0])

    def test_matching_prefers_unused_voice(self):
        code, _, response = self.call("/v1/voices/match", {
            "voicePersona": {"traits": []}, "alreadyUsedVoiceIds": ["mock_narrator"]})
        self.assertEqual(200, code)
        self.assertNotEqual("mock_narrator", response["candidates"][0]["voiceAssetId"])

    def test_missing_audio_fixture_is_not_silent_success(self):
        for path in ("/v1/tts/synthesize", "/v1/voices/preview"):
            self.assertEqual(503, self.call(path, {
                "text": "测试", "voiceAssetId": "mock_narrator", "language": "zh-CN", "speed": 1.0
            })[0])

    def test_fault_injection_and_unknown_route(self):
        self.api.fault = 429
        self.assertEqual(429, self.call("/v1/health")[0])
        self.api.fault = 0
        self.assertEqual(404, self.call("/other")[0])


if __name__ == "__main__":
    unittest.main()
