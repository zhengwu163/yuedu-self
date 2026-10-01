import unittest

from scripts.novel_audio_server.protocol import (
    analysis_request,
    analysis_response,
    strict_json_loads,
    synthesis_request,
)


def valid_analysis_request():
    return {
        "bookId": "book-1",
        "chapterId": "chapter-1",
        "textHash": "hash-1",
        "analysisVersion": "1",
        "characters": [
            {
                "characterId": "char-1",
                "displayName": "林舟",
                "stableAliases": [],
            }
        ],
        "units": [
            {"unitId": "unit-1", "text": "夜色落下。"},
            {"unitId": "unit-2", "text": "回家吧。"},
        ],
        "previousContext": {"recentAssignments": []},
    }


class ProtocolTest(unittest.TestCase):
    def test_synthesis_request_preserves_opaque_voice_asset_id(self):
        request = synthesis_request(
            {
                "text": "测试",
                "voiceAssetId": "local.qwen3-tts.voice.young-male",
                "language": "zh-CN",
                "speed": 1.0,
            }
        )

        self.assertEqual(
            "local.qwen3-tts.voice.young-male",
            request["voiceAssetId"],
        )
        self.assertEqual("zh-CN", request["language"])
        self.assertEqual(1.0, request["speed"])

    def test_analysis_response_rejects_unknown_assignment_unit(self):
        request = analysis_request(valid_analysis_request())

        with self.assertRaises(ValueError):
            analysis_response(
                {
                    "assignments": [
                        {
                            "unitId": "not-in-request",
                            "speakerId": "narrator",
                        }
                    ],
                    "newCharacters": [],
                    "aliasUpdates": [],
                },
                request,
            )

    def test_strict_json_rejects_duplicate_keys_at_nested_level(self):
        with self.assertRaises(ValueError):
            strict_json_loads('{"outer":{"value":1,"value":2}}')

    def test_strict_json_rejects_invalid_utf8(self):
        with self.assertRaises(ValueError):
            strict_json_loads(b"\xff")

    def test_analysis_response_requires_exact_unit_coverage(self):
        request = analysis_request(valid_analysis_request())

        with self.assertRaises(ValueError):
            analysis_response(
                {
                    "assignments": [
                        {"unitId": "unit-1", "speakerId": "narrator"}
                    ],
                    "newCharacters": [],
                    "aliasUpdates": [],
                },
                request,
            )


if __name__ == "__main__":
    unittest.main()
