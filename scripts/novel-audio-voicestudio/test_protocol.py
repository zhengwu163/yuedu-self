import json
import unittest

from protocol import (
    parse_analysis,
    parse_synthesis,
    project_analysis,
    strict_json_loads,
)
from providers import DirectorProvider, SpeechProvider


class ProtocolTest(unittest.TestCase):
    def _analysis_request_with_metadata_size(self, target_size):
        request = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [
                {
                    "characterId": "character-" + str(index),
                    "displayName": "角色",
                    "stableAliases": ["x"] * 32,
                }
                for index in range(64)
            ],
            "units": [{"unitId": "unit-0", "text": "x"}],
            "previousContext": {"recentAssignments": []},
        }

        def encoded_size():
            return len(json.dumps(
                request,
                ensure_ascii=False,
                allow_nan=False,
            ).encode("utf-8"))

        remaining = target_size - encoded_size()
        self.assertGreaterEqual(remaining, 0)
        for character in request["characters"]:
            for alias_index, alias in enumerate(character["stableAliases"]):
                addition = min(128 - len(alias), remaining)
                character["stableAliases"][alias_index] = alias + ("x" * addition)
                remaining -= addition
        self.assertEqual(0, remaining)
        self.assertEqual(target_size, encoded_size())
        return request

    def test_provider_protocols_define_the_vendor_neutral_surface(self):
        self.assertEqual(
            {"health", "voices", "match", "preview", "synthesize"},
            set(SpeechProvider.__dict__) & {
                "health", "voices", "match", "preview", "synthesize",
            },
        )
        self.assertEqual(
            {"health", "analyze"},
            set(DirectorProvider.__dict__) & {"health", "analyze"},
        )

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

    def test_analysis_requires_bounded_metadata_arrays(self):
        request = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [],
            "units": [{"unitId": "u1", "text": "旁白"}],
            "previousContext": {"recentAssignments": []},
        }

        missing_characters = dict(request)
        missing_characters.pop("characters")
        with self.assertRaises(ValueError):
            parse_analysis(missing_characters)

        too_many_characters = dict(request)
        too_many_characters["characters"] = [
            {
                "characterId": "character-" + str(index),
                "displayName": "角色",
                "stableAliases": [],
            }
            for index in range(65)
        ]
        with self.assertRaises(ValueError):
            parse_analysis(too_many_characters)

        oversized_metadata = dict(request)
        oversized_metadata["characters"] = [{
            "characterId": "character-" + str(index),
            "displayName": "角色",
            "stableAliases": ["x" * 128] * 32,
        } for index in range(9)]
        oversized_metadata["previousContext"] = {
            "recentAssignments": [
                {"unitId": "u1", "speakerId": "narrator"}
            ],
        }
        with self.assertRaises(ValueError):
            parse_analysis(oversized_metadata)

        at_limits = {
            "bookId": "book",
            "chapterId": "chapter",
            "textHash": "hash",
            "analysisVersion": "1",
            "characters": [
                {
                    "characterId": "character-" + str(index),
                    "displayName": "角色",
                    "stableAliases": [],
                }
                for index in range(64)
            ],
            "units": [
                {"unitId": "unit-" + str(index), "text": "x"}
                for index in range(64)
            ],
            "previousContext": {
                "recentAssignments": [
                    {"unitId": "u1", "speakerId": "narrator"}
                    for _ in range(32)
                ],
            },
        }
        parsed = parse_analysis(at_limits)
        self.assertEqual(64, len(parsed.characters))
        self.assertEqual(64, len(parsed.units))
        self.assertEqual(32, len(parsed.previous_context["recentAssignments"]))

        too_many_units = dict(at_limits)
        too_many_units["units"] = at_limits["units"] + [
            {"unitId": "unit-64", "text": "x"},
        ]
        with self.assertRaises(ValueError):
            parse_analysis(too_many_units)

        too_many_recent = dict(at_limits)
        too_many_recent["previousContext"] = {
            "recentAssignments": at_limits["previousContext"]["recentAssignments"] + [
                {"unitId": "u1", "speakerId": "narrator"},
            ],
        }
        with self.assertRaises(ValueError):
            parse_analysis(too_many_recent)

    def test_analysis_metadata_exactly_at_limit_is_accepted(self):
        request = self._analysis_request_with_metadata_size(32 * 1024)

        parsed = parse_analysis(request)

        self.assertEqual(64, len(parsed.characters))

    def test_analysis_metadata_one_byte_over_limit_is_rejected(self):
        request = self._analysis_request_with_metadata_size(32 * 1024 + 1)

        with self.assertRaises(ValueError):
            parse_analysis(request)

if __name__ == "__main__":
    unittest.main()
