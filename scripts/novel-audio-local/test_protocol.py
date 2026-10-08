import json
import unittest

from scripts.novel_audio_server.protocol import (
    analysis_request,
    analysis_response,
    model_analysis_request,
    restore_model_unit_ids,
    strict_json_loads,
    synthesis_request,
)

# Android TextUnitParser 的真实 ID 形态：u_ + 64 位 SHA-256 十六进制。
ANDROID_UNIT_A = "u_" + "a1" * 32
ANDROID_UNIT_B = "u_" + "b2" * 32
ANDROID_PREVIOUS = "u_" + "c3" * 32


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

    def android_request(self):
        value = valid_analysis_request()
        value.update(
            bookId="physical:" + "d4" * 32,
            chapterId="chapter:" + "e5" * 32,
            textHash="f6" * 32,
        )
        value["units"][0]["unitId"] = ANDROID_UNIT_A
        value["units"][1]["unitId"] = ANDROID_UNIT_B
        value["previousContext"]["recentAssignments"] = [
            {"unitId": ANDROID_PREVIOUS, "speakerId": "char-1"}
        ]
        return analysis_request(value)

    def test_model_request_replaces_opaque_ids_with_short_aliases(self):
        request = self.android_request()

        model_request, _ = model_analysis_request(request)

        self.assertEqual(
            ["u1", "u2"], [unit["unitId"] for unit in model_request["units"]]
        )
        self.assertEqual(
            ["夜色落下。", "回家吧。"],
            [unit["text"] for unit in model_request["units"]],
        )
        self.assertEqual(request["characters"], model_request["characters"])
        self.assertEqual(
            [{"unitId": "p1", "speakerId": "char-1"}],
            model_request["previousContext"]["recentAssignments"],
        )
        encoded = json.dumps(model_request, ensure_ascii=False)
        for opaque in (
            ANDROID_UNIT_A, ANDROID_UNIT_B, ANDROID_PREVIOUS,
            request["bookId"], request["chapterId"], request["textHash"],
        ):
            self.assertNotIn(opaque, encoded)

    def test_restored_aliases_validate_against_original_unit_ids(self):
        request = self.android_request()
        _, aliases = model_analysis_request(request)

        restored = restore_model_unit_ids(
            {
                "assignments": [
                    {"unitId": "u2", "speakerId": "char-1"},
                    {"unitId": "u1", "speakerId": "narrator"},
                ],
                "newCharacters": [],
                "aliasUpdates": [],
            },
            aliases,
        )

        self.assertEqual(
            [
                {"unitId": ANDROID_UNIT_B, "speakerId": "char-1"},
                {"unitId": ANDROID_UNIT_A, "speakerId": "narrator"},
            ],
            analysis_response(restored, request)["assignments"],
        )

    def test_compact_alias_mapping_restores_to_assignment_list(self):
        # 模型侧用 {"u1":"narrator"} 映射输出，每单元 token 约减半。
        request = self.android_request()
        _, aliases = model_analysis_request(request)

        restored = restore_model_unit_ids(
            {
                "assignments": {"u2": "char-1", "u1": "narrator"},
                "newCharacters": [],
                "aliasUpdates": [],
            },
            aliases,
        )

        self.assertEqual(
            [
                {"unitId": ANDROID_UNIT_B, "speakerId": "char-1"},
                {"unitId": ANDROID_UNIT_A, "speakerId": "narrator"},
            ],
            analysis_response(restored, request)["assignments"],
        )

    def test_unknown_model_alias_still_fails_strict_validation(self):
        request = self.android_request()
        _, aliases = model_analysis_request(request)

        restored = restore_model_unit_ids(
            {
                "assignments": [
                    {"unitId": "u1", "speakerId": "narrator"},
                    {"unitId": "u9", "speakerId": "narrator"},
                ],
                "newCharacters": [],
                "aliasUpdates": [],
            },
            aliases,
        )

        with self.assertRaises(ValueError):
            analysis_response(restored, request)

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
