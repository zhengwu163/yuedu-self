import json
import unittest
from pathlib import Path

from book_jobs_analysis import (
    AliasConflict,
    AnalysisError,
    CharacterTable,
    NoUnusedVoice,
    analyze_chapter,
    bind_voice,
    build_request,
    explicit_speaker,
    normalize_alias,
    partition,
    resolve_characters,
    utf16_len,
    utf16_take_last,
)

PARITY = json.loads(
    (Path(__file__).parent / "fixtures/book_jobs_parity.json").read_text(encoding="utf-8")
)


class ParityTest(unittest.TestCase):
    def test_normalize_alias(self):
        for case in PARITY["normalizeAlias"]:
            with self.subTest(case["input"]):
                if case.get("error"):
                    with self.assertRaises(ValueError):
                        normalize_alias(case["input"])
                else:
                    self.assertEqual(case["output"], normalize_alias(case["input"]))

    def test_explicit_speaker(self):
        for case in PARITY["explicitSpeaker"]:
            with self.subTest(case["name"]):
                self.assertEqual(
                    case["expected"],
                    explicit_speaker(
                        case["units"], case["index"], case["characters"], case["aliases"]
                    ),
                )

    def test_resolve(self):
        for case in PARITY["resolve"]:
            with self.subTest(case["name"]):
                table = CharacterTable(
                    case["existing"], case["existingAliases"], case["merges"]
                )
                if case.get("error"):
                    with self.assertRaises((AliasConflict, ValueError)):
                        resolve_characters(table, case["newCharacters"], case["aliasUpdates"])
                    continue
                mapped, created, aliases = resolve_characters(
                    table, case["newCharacters"], case["aliasUpdates"]
                )
                expected = case["expected"]
                self.assertEqual(expected["temporaryToId"], mapped)
                self.assertEqual(expected["created"], created)
                self.assertEqual(expected["aliases"], aliases)

    def test_bind(self):
        for case in PARITY["bind"]:
            with self.subTest(case["name"]):
                existing = {int(key): value for key, value in case["existing"].items()}
                candidates = [{"voiceAssetId": value} for value in case["candidates"]]
                if case.get("error"):
                    with self.assertRaises(NoUnusedVoice):
                        bind_voice(case["characterId"], existing, candidates)
                else:
                    self.assertEqual(
                        case["expected"], bind_voice(case["characterId"], existing, candidates)
                    )

    def test_partition(self):
        for case in PARITY["partition"]:
            with self.subTest(case["name"]):
                units = [
                    {"unitId": f"u{index}", "text": "字" * length}
                    for index, length in enumerate(case["lengths"])
                ]
                if case.get("error"):
                    with self.assertRaises(AnalysisError):
                        partition(units)
                else:
                    self.assertEqual(
                        case["expected"], [len(batch) for batch in partition(units)]
                    )


class Utf16Test(unittest.TestCase):
    def test_lengths_count_surrogate_pairs_as_two(self):
        self.assertEqual(3, utf16_len("a😀"))
        self.assertEqual("😀", utf16_take_last("ab😀", 2))


class BuildRequestTest(unittest.TestCase):
    def test_drops_oldest_characters_until_request_fits(self):
        characters = [{"id": index, "name": f"角色{index}" + "名" * 120} for index in range(1, 65)]
        aliases = [
            {"characterId": index, "alias": f"别名{index}-{alias}" + "长" * 100}
            for index in range(1, 65)
            for alias in range(4)
        ]
        units = [{"unitId": "u1", "text": "天黑了。"}]
        request = build_request("work", "ch1", "hash", characters, aliases, units, [])
        self.assertLess(len(request["characters"]), 64)
        self.assertEqual("64", request["characters"][-1]["characterId"])


class AnalyzeChapterTest(unittest.TestCase):
    VOICES = [
        {"voiceAssetId": "N1", "narrator": True},
        {"voiceAssetId": "F1", "gender": "female"},
        {"voiceAssetId": "M1", "gender": "male"},
    ]

    def match(self, used, constraints):
        gender = (constraints or {}).get("gender")
        return [
            voice for voice in self.VOICES
            if not voice.get("narrator")
            and voice["voiceAssetId"] not in used
            and (gender is None or voice.get("gender") == gender)
        ]

    def test_new_characters_get_formal_ids_and_voices(self):
        table = CharacterTable([{"id": 1, "name": "单牧", "gender": "male"}])
        units = [
            {"unitId": "n0", "text": "雨停了，曾晚推门进来。", "roleType": "narrator"},
            {"unitId": "d1", "text": "“钟又不响了。”", "roleType": "dialogue"},
            {"unitId": "n2", "text": "过了很久，单牧才低声说：", "roleType": "narrator"},
            {"unitId": "d3", "text": "“我知道。”", "roleType": "dialogue"},
            {"unitId": "b4", "text": "  ", "roleType": "narrator"},
        ]
        requests = []

        def analyze(request):
            requests.append(request)
            # The model wrongly gives d3 to the new character; the reporting
            # clause 「单牧才低声说」 must override it.
            return {
                "assignments": [
                    {"unitId": "n0", "speakerId": "narrator"},
                    {"unitId": "d1", "speakerId": "t1"},
                    {"unitId": "n2", "speakerId": "narrator"},
                    {"unitId": "d3", "speakerId": "t1"},
                ],
                "newCharacters": [
                    {"temporaryId": "t1", "displayName": "曾晚", "gender": "女"}
                ],
                "aliasUpdates": [],
            }

        result = analyze_chapter(
            table, "work", "ch1", "hash", units, analyze, lambda: self.VOICES, self.match
        )
        self.assertEqual(["n0", "d1", "n2", "d3"], [u["unitId"] for u in requests[0]["units"]])
        self.assertEqual(
            {"n0": 0, "d1": 2, "n2": 0, "d3": 1, "b4": 0}, result["speakers"]
        )
        self.assertEqual(
            {
                0: {"voiceAssetId": "N1", "revision": 1},
                2: {"voiceAssetId": "F1", "revision": 1},
                1: {"voiceAssetId": "M1", "revision": 1},
            },
            result["bindings"],
        )
        self.assertEqual(result["bindings"], table.bindings)
        self.assertEqual("曾晚", table.characters[-1]["name"])

    def test_existing_binding_wins_over_new_match(self):
        table = CharacterTable(
            [{"id": 1, "name": "单牧", "gender": "male"}],
            bindings={1: {"voiceAssetId": "M9", "revision": 3}},
        )
        units = [{"unitId": "d0", "text": "“好。”", "roleType": "dialogue"}]

        def analyze(request):
            return {
                "assignments": [{"unitId": "d0", "speakerId": "1"}],
                "newCharacters": [],
                "aliasUpdates": [],
            }

        result = analyze_chapter(
            table, "work", "ch1", "hash", units, analyze, lambda: self.VOICES, self.match
        )
        self.assertEqual({"voiceAssetId": "M9", "revision": 3}, result["bindings"][1])

    def test_response_missing_a_unit_fails(self):
        table = CharacterTable()
        units = [
            {"unitId": "n0", "text": "天黑了。", "roleType": "narrator"},
            {"unitId": "n1", "text": "风停了。", "roleType": "narrator"},
        ]

        def analyze(request):
            return {
                "assignments": [{"unitId": "n0", "speakerId": "narrator"}],
                "newCharacters": [],
                "aliasUpdates": [],
            }

        with self.assertRaises(AnalysisError):
            analyze_chapter(
                table, "work", "ch1", "hash", units, analyze, lambda: self.VOICES, self.match
            )

    def test_unknown_speaker_fails(self):
        units = [{"unitId": "d0", "text": "“好。”", "roleType": "dialogue"}]

        def analyze(request):
            return {
                "assignments": [{"unitId": "d0", "speakerId": "t9"}],
                "newCharacters": [],
                "aliasUpdates": [],
            }

        with self.assertRaises(AnalysisError):
            analyze_chapter(
                CharacterTable(), "work", "ch1", "hash", units, analyze,
                lambda: self.VOICES, self.match,
            )


if __name__ == "__main__":
    unittest.main()
