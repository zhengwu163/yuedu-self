import hashlib
import json
import math
import tempfile
import unittest
from pathlib import Path

from model_registry import ModelRegistry, ModelRegistryError
from test_support import requires_symlinks


class ModelRegistryTest(unittest.TestCase):
    def file_sha256(self, path):
        digest = hashlib.sha256()
        digest.update(Path(path).read_bytes())
        return digest.hexdigest()

    def directory_sha256(self, path):
        root = Path(path)
        entries = []
        for child in sorted(root.rglob("*")):
            if child.is_file():
                entries.append(
                    (child.relative_to(root).as_posix(), self.file_sha256(child))
                )
        return hashlib.sha256(
            json.dumps(entries, separators=(",", ":")).encode("utf-8")
        ).hexdigest()

    def write_registry(self, directory, value):
        path = Path(directory) / "models.json"
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def valid_registry(self):
        return {
            "version": "1",
            "models": [
                {
                    "assetId": "text-future-1",
                    "type": "text",
                    "family": "future-text-family",
                    "format": "gguf",
                    "path": "models/future-text.gguf",
                    "sha256": "a" * 64,
                    "requiredVramGb": 24,
                    "capabilities": ["chapter-analysis", "zh-CN"],
                    "adapter": "llama.cpp-openai-compatible",
                    "source": "official model page",
                    "license": "model license",
                },
                {
                    "assetId": "tts-future-1",
                    "type": "tts",
                    "family": "future-tts-family",
                    "format": "directory",
                    "path": "models/future-tts",
                    "sha256": "b" * 64,
                    "requiredVramGb": 24,
                    "capabilities": ["speech-synthesis", "zh-CN"],
                    "adapter": "future-tts-adapter",
                    "source": "official model page",
                    "license": "model license",
                },
            ],
            "profiles": [
                {
                    "profileId": "future-default",
                    "textModel": "text-future-1",
                    "ttsModel": "tts-future-1",
                    "minVramGb": 24,
                    "maxConcurrency": 1,
                    "capabilities": ["chapter-analysis", "speech-synthesis", "zh-CN"],
                }
            ],
            "activeProfile": "future-default",
        }

    def test_loads_future_model_family_without_hard_coded_names(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry.load(self.write_registry(directory, self.valid_registry()))

            profile = registry.active_profile()

            self.assertEqual("future-default", profile.profile_id)
            self.assertEqual("future-text-family", registry.asset("text-future-1").family)
            self.assertEqual("future-tts-adapter", registry.asset("tts-future-1").adapter)
            self.assertIn("chapter-analysis", profile.capabilities)

    def test_rejects_profile_referencing_unknown_model(self):
        with tempfile.TemporaryDirectory() as directory:
            value = self.valid_registry()
            value["profiles"][0]["ttsModel"] = "missing-tts"

            with self.assertRaises(ModelRegistryError):
                ModelRegistry.load(self.write_registry(directory, value))

    def test_rejects_invalid_checksum_and_duplicate_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            value = self.valid_registry()
            value["models"][0]["sha256"] = "not-a-sha256"
            with self.assertRaises(ModelRegistryError):
                ModelRegistry.load(self.write_registry(directory, value))

            value = self.valid_registry()
            value["models"].append(dict(value["models"][0]))
            with self.assertRaises(ModelRegistryError):
                ModelRegistry.load(self.write_registry(directory, value))

    def test_profile_requires_24gb_runtime_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry.load(self.write_registry(directory, self.valid_registry()))

            registry.validate_hardware(24)
            with self.assertRaises(ModelRegistryError):
                registry.validate_hardware(23.9)

    def test_runtime_profile_identity_changes_when_bound_model_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            first = self.valid_registry()
            first_registry = ModelRegistry.load(self.write_registry(directory, first))
            first_identity = first_registry.active_profile().identity

            changed = self.valid_registry()
            changed["models"][0]["sha256"] = "c" * 64
            second_registry = ModelRegistry.load(self.write_registry(directory, changed))

            self.assertNotEqual(first_identity, second_registry.active_profile().identity)

    def test_public_profile_does_not_expose_model_paths_or_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry.load(self.write_registry(directory, self.valid_registry()))

            public = registry.active_profile().public()

            self.assertNotIn("models/future-text.gguf", json.dumps(public))
            self.assertNotIn("official model page", json.dumps(public))
            self.assertEqual(24, public["minVramGb"])

    def registry_with_real_assets(self, root):
        root = Path(root)
        text = root / "models" / "future-text.gguf"
        tts = root / "models" / "future-tts"
        tts.mkdir(parents=True)
        text.parent.mkdir(parents=True, exist_ok=True)
        text.write_bytes(b"text-v1")
        (tts / "config.json").write_text("{}", encoding="utf-8")
        (tts / "weights.bin").write_bytes(b"weights-v1")

        value = self.valid_registry()
        value["models"][0]["path"] = "models/future-text.gguf"
        value["models"][0]["sha256"] = self.file_sha256(text)
        value["models"][1]["path"] = "models/future-tts"
        value["models"][1]["sha256"] = self.directory_sha256(tts)
        return value

    def test_file_asset_hash_mismatch_blocks_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            value["models"][0]["sha256"] = "0" * 64
            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            with self.assertRaises(ModelRegistryError) as caught:
                registry.verify_profile()

            self.assertEqual("asset_hash_mismatch", caught.exception.code)

    def test_directory_asset_hash_manifest_detects_changed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            model_dir = root / "models" / "future-tts"
            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            registry.verify_profile()
            (model_dir / "weights.bin").write_bytes(b"weights-v2")

            with self.assertRaises(ModelRegistryError) as caught:
                registry.verify_profile()

            self.assertEqual("asset_hash_mismatch", caught.exception.code)

    def test_absolute_model_path_outside_service_root_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "service"
            root.mkdir()
            outside = Path(directory) / "outside.gguf"
            outside.write_bytes(b"model")
            value = self.valid_registry()
            value["models"][0]["path"] = str(outside)

            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            self.assertEqual(outside.resolve(), registry.asset("text-future-1").path)

    def test_symlinked_model_asset_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "real.gguf"
            link = root / "linked.gguf"
            target.write_bytes(b"model")
            try:
                link.symlink_to(target)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")

            value = self.valid_registry()
            value["models"][0]["path"] = str(link)

            with self.assertRaises(ModelRegistryError) as caught:
                ModelRegistry.load(self.write_registry(root, value), root=root)

            self.assertEqual("symlink_asset", caught.exception.code)

    @requires_symlinks
    def test_symlinked_parent_model_asset_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            external = root / "external-models"
            external.mkdir()
            (external / "future-text.gguf").write_bytes(b"model")
            (root / "models").symlink_to(external, target_is_directory=True)

            value = self.valid_registry()
            value["models"][0]["path"] = "models/future-text.gguf"

            with self.assertRaises(ModelRegistryError) as caught:
                ModelRegistry.load(self.write_registry(root, value), root=root)

            self.assertEqual("symlink_asset", caught.exception.code)

    def test_directory_manifest_rejects_symlinked_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            target = root / "outside.bin"
            target.write_bytes(b"outside")
            try:
                (root / "models" / "future-tts" / "linked.bin").symlink_to(target)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")

            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            with self.assertRaises(ModelRegistryError) as caught:
                registry.verify_profile()

            self.assertEqual("symlink_asset", caught.exception.code)

    def test_legacy_capability_aliases_are_normalized(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            value["models"][0]["capabilities"] = ["chapter_analysis", "zh-CN"]
            value["models"][1]["capabilities"] = ["speech_synthesis", "zh-CN"]
            value["profiles"][0]["capabilities"] = [
                "chapter_analysis",
                "speech_synthesis",
                "zh-CN",
            ]

            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            self.assertEqual(
                ("chapter-analysis", "zh-CN"),
                registry.asset("text-future-1").capabilities,
            )
            self.assertEqual(
                ("speech-synthesis", "zh-CN"),
                registry.asset("tts-future-1").capabilities,
            )
            self.assertEqual(
                ("chapter-analysis", "speech-synthesis", "zh-CN"),
                registry.active_profile().capabilities,
            )
            registry.verify_profile()

    def test_rejects_duplicate_legacy_and_canonical_capabilities(self):
        with tempfile.TemporaryDirectory() as directory:
            value = self.valid_registry()
            value["models"][0]["capabilities"] = [
                "chapter_analysis",
                "chapter-analysis",
            ]

            with self.assertRaises(ModelRegistryError) as caught:
                ModelRegistry.load(self.write_registry(directory, value))

            self.assertEqual("duplicate capabilities", caught.exception.code)

    def test_profile_requires_matching_voice_clone_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            value["profiles"][0]["capabilities"].append("voice_clone")
            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            with self.assertRaises(ModelRegistryError) as caught:
                registry.verify_profile()

            self.assertEqual("capability_unavailable", caught.exception.code)

    def test_profile_rejects_missing_voice_operation_capability(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.registry_with_real_assets(root)
            value["profiles"][0]["capabilities"].append("voice-design")
            registry = ModelRegistry.load(self.write_registry(root, value), root=root)

            with self.assertRaises(ModelRegistryError) as caught:
                registry.verify_profile()

            self.assertEqual("capability_unavailable", caught.exception.code)

    def test_validate_hardware_rejects_nonfinite_vram(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry.load(
                self.write_registry(directory, self.valid_registry())
            )

            for value in (math.nan, math.inf, -math.inf):
                with self.assertRaises(ModelRegistryError):
                    registry.validate_hardware(value)

    def test_profile_identity_changes_when_catalog_or_reference_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "voices.json"
            reference = root / "reference.wav"
            catalog.write_text('{"version":"1","voices":[]}', encoding="utf-8")
            reference.write_bytes(b"reference-v1")
            value = self.registry_with_real_assets(root)

            first = ModelRegistry.load(self.write_registry(root, value), root=root)
            first_identity = first.profile_identity(
                first.active_profile(),
                self.file_sha256(catalog),
                self.file_sha256(reference),
            )

            catalog.write_text('{"version":"2","voices":[]}', encoding="utf-8")
            second = ModelRegistry.load(self.write_registry(root, value), root=root)
            second_identity = second.profile_identity(
                second.active_profile(),
                self.file_sha256(catalog),
                self.file_sha256(reference),
            )
            self.assertNotEqual(first_identity, second_identity)

            reference.write_bytes(b"reference-v2")
            third = ModelRegistry.load(self.write_registry(root, value), root=root)
            third_identity = third.profile_identity(
                third.active_profile(),
                self.file_sha256(catalog),
                self.file_sha256(reference),
            )
            self.assertNotEqual(second_identity, third_identity)


if __name__ == "__main__":
    unittest.main()
