import tempfile
import unittest
from pathlib import Path

from registry import VoiceRegistry


class VoiceRegistryTest(unittest.TestCase):
    def test_registry_never_exposes_provider_path(self):
        registry = VoiceRegistry.from_records([{
            "voiceAssetId": "voicestudio.narrator",
            "displayName": "旁白",
            "providerRef": "/private/models/voice.wav",
            "gender": "unknown",
            "ageRange": "adult",
            "traits": ["清晰"],
        }])

        public = registry.public_voices()
        self.assertNotIn("providerRef", public[0])
        self.assertNotIn("/private/models/voice.wav", str(public))

    def test_matching_is_deterministic_and_excludes_used_voice_ids(self):
        registry = VoiceRegistry.from_records([
            {
                "voiceAssetId": "voicestudio.male",
                "displayName": "成年男声",
                "providerRef": "profile-male",
                "gender": "male",
                "ageRange": "adult",
                "traits": ["清晰"],
            },
            {
                "voiceAssetId": "voicestudio.female",
                "displayName": "成年女声",
                "providerRef": "profile-female",
                "gender": "female",
                "ageRange": "adult",
                "traits": ["温柔"],
            },
        ])

        first = registry.match(
            {"traits": ["清晰"]},
            ["voicestudio.female"],
            {"gender": "male", "ageRange": "adult"},
        )
        second = registry.match(
            {"traits": ["清晰"]},
            ["voicestudio.female"],
            {"gender": "male", "ageRange": "adult"},
        )

        self.assertEqual(first, second)
        self.assertEqual("voicestudio.male", first[0]["voiceAssetId"])

    def test_gender_match_outranks_age_match(self):
        # 真实联调复现：成年男角色曾被分配到性别未知的英文演示音色，
        # 因为“性别不符”与“年龄不符”各算一次未命中后按 ID 排序打平。
        registry = VoiceRegistry.from_records([
            {
                "voiceAssetId": "voicestudio.profile.demo0001",
                "displayName": "演示音色",
                "providerRef": "demo0001",
                "gender": "unknown",
                "ageRange": "adult",
                "traits": [],
            },
            {
                "voiceAssetId": "voicestudio.profile.ffbc7c5f",
                "displayName": "青年男声",
                "providerRef": "ffbc7c5f",
                "gender": "male",
                "ageRange": "young_adult",
                "traits": [],
            },
        ])

        candidates = registry.match(
            {"traits": []}, [], {"gender": "male", "ageRange": "adult"},
        )

        self.assertEqual("voicestudio.profile.ffbc7c5f", candidates[0]["voiceAssetId"])

    def test_unknown_constraint_does_not_prefer_unknown_voices(self):
        registry = VoiceRegistry.from_records([
            {
                "voiceAssetId": "voicestudio.a-female",
                "displayName": "女声",
                "providerRef": "female",
                "gender": "female",
                "ageRange": "adult",
                "traits": ["温柔"],
            },
            {
                "voiceAssetId": "voicestudio.b-unknown",
                "displayName": "未标注",
                "providerRef": "unknown",
                "gender": "unknown",
                "ageRange": "adult",
                "traits": [],
            },
        ])

        candidates = registry.match(
            {"traits": ["温柔"]}, [], {"gender": "unknown", "ageRange": "unknown"},
        )

        self.assertEqual("voicestudio.a-female", candidates[0]["voiceAssetId"])

    def test_registry_round_trip_preserves_profile_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            original = VoiceRegistry.from_records(
                [{
                    "voiceAssetId": "voicestudio.narrator",
                    "displayName": "旁白",
                    "providerRef": "profile-narrator",
                    "gender": "unknown",
                    "ageRange": "adult",
                    "traits": ["清晰"],
                }],
                profile_revision="engine-a-v1",
            )
            original.save(path)
            loaded = VoiceRegistry.load(path)

        self.assertEqual("engine-a-v1", loaded.profile_revision)
        self.assertEqual(
            original.public_voices(),
            loaded.public_voices(),
        )

    def test_corrupt_registry_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            path.write_text("{broken", encoding="utf-8")

            with self.assertRaises(ValueError):
                VoiceRegistry.load(path)


if __name__ == "__main__":
    unittest.main()
