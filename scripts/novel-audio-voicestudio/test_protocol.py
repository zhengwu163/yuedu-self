import unittest

from protocol import (
    parse_analysis,
    parse_synthesis,
    project_analysis,
    strict_json_loads,
)


class ProtocolTest(unittest.TestCase):
    def test_synthesis_request_does_not_require_a_vendor_model_name(self):
        request = parse_synthesis({
            "text": "测试",
            "voiceAssetId": "voicestudio.narrator",
            "language": "zh-CN",
            "speed": 1.0,
        })

        self.assertEqual("voicestudio.narrator", request.voice_asset_id)
        self.assertEqual("zh-CN", request.language)

    def test_strict_json_rejects_duplicate_keys_nonfinite_numbers_and_surrogates(self):
        for raw in (
            '{"voiceAssetId":"one","voiceAssetId":"two"}',
            '{"speed":NaN}',
            '{"speed":Infinity}',
            '{"text":"\\ud800"}',
        ):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    strict_json_loads(raw)

    def test_analysis_response_must_cover_each_input_unit_once(self):
        request = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [],
            "units": [
                {"unitId": "u1", "text": "旁白"},
                {"unitId": "u2", "text": "第二句"},
            ],
            "previousContext": {"recentAssignments": []},
        }

        with self.assertRaises(ValueError):
            project_analysis({
                "assignments": [
                    {"unitId": "u1", "speakerId": "narrator"},
                    {"unitId": "u1", "speakerId": "narrator"},
                ],
                "newCharacters": [],
                "aliasUpdates": [],
            }, request)

    def test_analysis_response_does_not_forward_provider_text(self):
        request = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [],
            "units": [{"unitId": "u1", "text": "旁白"}],
            "previousContext": {"recentAssignments": []},
        }
        response = project_analysis({
            "assignments": [{"unitId": "u1", "speakerId": "narrator"}],
            "newCharacters": [],
            "aliasUpdates": [],
            "providerText": "must not leak",
        }, request)

        self.assertNotIn("providerText", response)


if __name__ == "__main__":
    unittest.main()
