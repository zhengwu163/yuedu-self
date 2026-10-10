"""Base/VoiceDesign contracts; PCM fixtures are structural, not user recordings."""

import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import Mock, patch

from qwen_backend import QwenBackend, QwenTtsAdapter
from scripts.novel_audio_server.errors import MissingReferenceAudioError
from scripts.novel_audio_server.protocol import MAX_AUDIO
from test_support import requires_symlinks
from voices import VoiceCatalog, VoiceCatalogError


ROOT = Path(__file__).resolve().parent
STANDARD = ROOT / "voices" / "standard.json"
BASE_ID = "local.qwen3-tts.base-reference"
DESIGN_ID = "local.qwen3-tts.narrator"
REFERENCE = "state/reference-audio/reference.wav"


def wav_bytes(sample=0):
    output = io.BytesIO()
    with wave.open(output, "wb") as target:
        target.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        target.writeframes(sample.to_bytes(2, "little", signed=True) * 24)
    return output.getvalue()


class ProfileModesTest(unittest.TestCase):
    def setUp(self):
        resource_check = patch("qwen_backend.ensure_resources")
        resource_check.start()
        self.addCleanup(resource_check.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reference = self.root / REFERENCE
        self.reference.parent.mkdir(parents=True)
        self.reference.write_bytes(wav_bytes())

    def catalog(self, reference=REFERENCE, **base_fields):
        path = self.root / "voices.json"
        path.write_text(json.dumps({
            "version": "contract-1",
            "voices": [
                {
                    "voiceAssetId": DESIGN_ID, "kind": "voicedesign",
                    "prompt": "自然中文旁白", "narrator": True,
                },
                {
                    "voiceAssetId": BASE_ID, "kind": "base",
                    "referenceAudio": reference, **base_fields,
                },
            ],
        }), encoding="utf-8")
        return VoiceCatalog(path, self.root)

    def assert_catalog_error(self, code, action):
        with self.assertRaises(VoiceCatalogError) as caught:
            action()
        self.assertEqual(code, caught.exception.code)
        self.assertEqual(code, str(caught.exception))
        self.assertNotIn(str(self.root), str(caught.exception))

    def profile(self, capability):
        return SimpleNamespace(
            capabilities=("speech-synthesis", capability),
            tts_asset=SimpleNamespace(path=self.root / capability, asset_id=capability),
            identity="verified-profile",
        )

    def request(self, voice_id=BASE_ID):
        return {"voiceAssetId": voice_id, "text": "测试文本", "speed": 1.0, "language": "zh-CN"}

    def test_standard_contains_stable_base_reference_and_optional_text(self):
        data = json.loads(STANDARD.read_text(encoding="utf-8"))
        entries = [voice for voice in data["voices"] if voice["kind"] == "base"]
        self.assertEqual(1, len(entries))
        self.assertEqual(BASE_ID, entries[0]["voiceAssetId"])
        self.assertEqual(REFERENCE, entries[0]["referenceAudio"])
        self.assertEqual("", entries[0].get("referenceText", ""))
        asset = VoiceCatalog(STANDARD, self.root).asset(BASE_ID, ["voice-clone"])
        self.assertEqual(self.reference, asset.reference_audio)
        self.assertFalse(asset.narrator)

    def test_standard_filters_all_public_lookup_and_match_surfaces(self):
        catalog = VoiceCatalog(STANDARD, self.root)
        self.assertEqual([BASE_ID], [v["voiceAssetId"] for v in catalog.public_voices(["voice-clone"])])
        self.assertEqual([BASE_ID], [v["voiceAssetId"] for v in catalog.match({}, [], capabilities=["voice-clone"])])
        self.assertNotIn(BASE_ID, [v["voiceAssetId"] for v in catalog.public_voices(["voice-design"])])
        self.assertFalse(catalog.contains(BASE_ID, ["voice-design"]))
        self.assertFalse(catalog.contains(DESIGN_ID, ["voice-clone"]))
        for voice_id, caps in ((BASE_ID, ["voice-design"]), (DESIGN_ID, ["voice-clone"])):
            self.assert_catalog_error("unknown_voice", lambda: catalog.asset(voice_id, caps))

    def test_standard_missing_reference_fails_with_fixed_code(self):
        self.reference.unlink()
        catalog = VoiceCatalog(STANDARD, self.root)
        self.assertEqual([], catalog.public_voices(["voice-clone"]))
        self.assert_catalog_error("missing_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    def test_missing_asset_is_not_confused_with_unknown_voice(self):
        self.reference.unlink()
        catalog = self.catalog()
        self.assertFalse(catalog.contains(BASE_ID, ["voice-clone"]))
        self.assert_catalog_error("missing_reference_audio", lambda: catalog.asset(BASE_ID, ["voice-clone"]))
        self.assert_catalog_error("unknown_voice", lambda: catalog.asset("local.unknown", ["voice-clone"]))

    def test_no_base_catalog_reports_missing_reference(self):
        path = self.root / "only-design.json"
        path.write_text(json.dumps({"version": "1", "voices": [
            {"voiceAssetId": DESIGN_ID, "kind": "voicedesign", "prompt": "旁白"},
        ]}), encoding="utf-8")
        catalog = VoiceCatalog(path, self.root)
        self.assert_catalog_error("missing_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    def test_missing_reference_field_uses_fixed_missing_code(self):
        catalog = self.catalog(reference="")
        self.assert_catalog_error("missing_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    def test_invalid_paths_are_rejected_with_fixed_code(self):
        for reference in (
            str(self.reference), "../outside.wav", "state/../reference.wav",
            r"..\outside.wav", r"C:\private.wav", r"C:private.wav",
            r"\\host\share\private.wav", "state/reference.wav:stream",
            "state/\x00private.wav", 123,
        ):
            with self.subTest(reference=reference):
                catalog = self.catalog(reference)
                self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))
                self.assertEqual([], catalog.public_voices(["voice-clone"]))

    @requires_symlinks
    def test_symlink_file_is_invalid_even_if_target_is_inside_root(self):
        target = self.root / "target.wav"
        target.write_bytes(wav_bytes())
        self.reference.unlink()
        self.reference.symlink_to(target)
        catalog = self.catalog()
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    @requires_symlinks
    def test_symlink_parent_is_invalid_even_if_target_is_inside_root(self):
        link = self.root / "link"
        link.symlink_to(self.reference.parent, target_is_directory=True)
        catalog = self.catalog("link/reference.wav")
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    @requires_symlinks
    def test_symlink_root_is_invalid(self):
        catalog = self.catalog()
        link = self.root / "root-link"
        link.symlink_to(self.root, target_is_directory=True)
        linked = VoiceCatalog(catalog.path, link)
        self.assert_catalog_error("invalid_reference_audio", lambda: linked.validate_references(["voice-clone"]))

    @requires_symlinks
    def test_rechecks_symlink_after_catalog_load(self):
        catalog = self.catalog()
        self.reference.unlink()
        self.reference.symlink_to(self.root / "missing-outside.wav")
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.asset(BASE_ID, ["voice-clone"]))

    def test_reference_must_be_regular_file(self):
        self.reference.unlink()
        self.reference.mkdir()
        catalog = self.catalog()
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO only available on POSIX")
    def test_fifo_is_rejected_without_opening(self):
        self.reference.unlink()
        os.mkfifo(self.reference)
        catalog = self.catalog()
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    def test_empty_and_undecodable_audio_are_invalid(self):
        catalog = self.catalog()
        for content in (b"", b"RIFF", b"not-a-wave", wav_bytes()[:-1]):
            with self.subTest(content=content[:8]):
                self.reference.write_bytes(content)
                self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))
                self.assertEqual([], catalog.public_voices(["voice-clone"]))

    def test_oversized_reference_is_rejected_before_reading(self):
        with self.reference.open("wb") as target:
            target.truncate(MAX_AUDIO + 1)
        catalog = self.catalog()
        with patch("voices.os.open", side_effect=AssertionError("must not open oversized file")):
            self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))

    def test_reference_text_must_be_string(self):
        for value in (None, [], 17):
            with self.subTest(value=value):
                self.assert_catalog_error("invalid_reference_text", lambda: self.catalog(referenceText=value))

    def test_design_validation_and_digest_do_not_read_reference(self):
        catalog = self.catalog()
        with patch("voices.os.open", side_effect=AssertionError("reference read")):
            catalog.validate_references(["voice-design"])
            digest = catalog.reference_audio_digest(["voice-design"])
            self.assertEqual([DESIGN_ID], [v["voiceAssetId"] for v in catalog.public_voices(["voice-design"])])
        self.assertEqual(hashlib.sha256(b"voice-design-no-reference-v1").hexdigest(), digest)

    def test_reference_digest_is_content_sensitive_not_mtime_sensitive(self):
        catalog = self.catalog()
        first = catalog.reference_audio_digest(["voice-clone"])
        previous = self.reference.stat()
        self.reference.write_bytes(wav_bytes(sample=1))
        os.utime(self.reference, ns=(previous.st_atime_ns, previous.st_mtime_ns))
        self.assertNotEqual(first, catalog.reference_audio_digest(["voice-clone"]))
        self.assertNotIn(str(self.root), first)
        self.assertEqual(64, len(first))

    def test_reference_digest_hashes_only_validated_wav_bytes(self):
        catalog = self.catalog()
        self.reference.write_bytes(b"RIFF")
        self.assert_catalog_error("invalid_reference_audio", lambda: catalog.reference_audio_digest(["voice-clone"]))

    def test_reference_digest_is_stable_across_service_roots(self):
        first = self.catalog().reference_audio_digest(["voice-clone"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / REFERENCE
            reference.parent.mkdir(parents=True)
            reference.write_bytes(wav_bytes())
            other = VoiceCatalog(self.root / "voices.json", root)
            self.assertEqual(first, other.reference_audio_digest(["voice-clone"]))

    def test_public_base_voice_does_not_expose_private_fields(self):
        catalog = self.catalog(referenceText="私有参考文本")
        public = catalog.public_voices(["voice-clone"])[0]
        self.assertEqual(
            {"voiceAssetId", "displayName", "gender", "ageRange", "traits", "previewAvailable", "narrator"},
            set(public),
        )
        self.assertIsInstance(public["narrator"], bool)
        self.assertNotIn("私有参考文本", json.dumps(public, ensure_ascii=False))
        self.assertNotIn(str(self.root), json.dumps(public))

    def test_base_missing_reference_never_loads_model(self):
        catalog = self.catalog()
        self.reference.unlink()
        loader = Mock(side_effect=AssertionError("model load"))
        adapter = QwenTtsAdapter(object(), catalog, profile=self.profile("voice-clone"), model_loader=loader)
        with self.assertRaises(MissingReferenceAudioError) as caught:
            adapter.synthesize(self.request())
        self.assertEqual("missing_reference_audio", caught.exception.code)
        loader.assert_not_called()

    def test_backend_preserves_missing_reference_code(self):
        self.reference.unlink()
        catalog = self.catalog()
        tts = Mock()
        backend = QwenBackend(object(), catalog, profile=self.profile("voice-clone"), text=Mock(), tts=tts)
        with self.assertRaises(MissingReferenceAudioError):
            backend.synthesize(self.request())
        tts.synthesize.assert_not_called()

    def test_base_invalid_reference_preserves_catalog_error_without_loading(self):
        self.reference.write_bytes(b"RIFF")
        loader = Mock(side_effect=AssertionError("model load"))
        adapter = QwenTtsAdapter(object(), self.catalog(), profile=self.profile("voice-clone"), model_loader=loader)
        self.assert_catalog_error("invalid_reference_audio", lambda: adapter.synthesize(self.request()))
        loader.assert_not_called()

    def test_real_dispatch_uses_only_mode_specific_kwargs_and_is_lazy(self):
        for capability, voice_id, method in (
            ("voice-clone", BASE_ID, "generate_voice_clone"),
            ("voice-design", DESIGN_ID, "generate_voice_design"),
        ):
            with self.subTest(capability=capability):
                model = Mock()
                getattr(model, method).return_value = ([0.0], 24000)
                loader = Mock(return_value=model)
                adapter = QwenTtsAdapter(
                    object(), self.catalog(), profile=self.profile(capability), model_loader=loader,
                )
                operation = adapter.build_operation(self.request(voice_id))
                loader.assert_not_called()
                adapter._invoke(operation)
                adapter._invoke(operation)
                loader.assert_called_once_with(str(self.root / capability))
                kwargs = getattr(model, method).call_args.kwargs
                if capability == "voice-clone":
                    self.assertEqual({"text", "language", "ref_audio", "ref_text", "x_vector_only_mode"}, set(kwargs))
                    self.assertEqual(str(self.reference), kwargs["ref_audio"])
                    self.assertIsNone(kwargs["ref_text"])
                    self.assertIs(True, kwargs["x_vector_only_mode"])
                    model.generate_voice_design.assert_not_called()
                else:
                    self.assertEqual({"text", "language", "instruct"}, set(kwargs))
                    model.generate_voice_clone.assert_not_called()

    def test_reference_text_enables_transcript_mode_but_whitespace_does_not(self):
        for text, expected in (("", None), (" \n\t ", None), ("参考台词", "参考台词")):
            with self.subTest(text=text):
                adapter = QwenTtsAdapter(object(), self.catalog(referenceText=text), profile=self.profile("voice-clone"))
                operation = adapter.build_operation(self.request())
                self.assertEqual(expected, operation["refText"])
                self.assertEqual(expected is None, operation["xVectorOnlyMode"])
                self.assertNotIn("instruct", operation)

    def test_agent_import_does_not_import_heavy_dependencies(self):
        code = """
import builtins
original = builtins.__import__
def guarded(name, *args, **kwargs):
    if name.split('.')[0] in {'torch', 'torchaudio', 'qwen_tts', 'transformers', 'numpy', 'soundfile'}:
        raise AssertionError('eager heavyweight import: ' + name)
    return original(name, *args, **kwargs)
builtins.__import__ = guarded
import agent
import qwen_backend
"""
        result = subprocess.run(
            [sys.executable, "-B", "-c", code], capture_output=True, text=True,
            cwd=ROOT.parent.parent, timeout=15,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_reference_unreadable_error_is_sanitized(self):
        catalog = self.catalog()
        with patch("voices.os.open", side_effect=PermissionError(str(self.reference))):
            self.assert_catalog_error("invalid_reference_audio", lambda: catalog.validate_references(["voice-clone"]))
            self.assert_catalog_error("invalid_reference_audio", lambda: catalog.reference_audio_digest(["voice-clone"]))

    def test_adapter_rejects_cross_profile_voice_before_loading(self):
        for capability, voice_id in (("voice-design", BASE_ID), ("voice-clone", DESIGN_ID)):
            with self.subTest(capability=capability):
                loader = Mock(side_effect=AssertionError("cross-profile model load"))
                adapter = QwenTtsAdapter(
                    object(), self.catalog(), profile=self.profile(capability), model_loader=loader,
                )
                self.assert_catalog_error("unknown_voice", lambda: adapter.synthesize(self.request(voice_id)))
                loader.assert_not_called()

    def test_default_model_loader_is_local_only_and_lazy(self):
        model = Mock()
        torch = ModuleType("torch")
        torch.bfloat16 = object()
        qwen = ModuleType("qwen_tts")
        qwen.Qwen3TTSModel = SimpleNamespace(from_pretrained=Mock(return_value=model))
        adapter = QwenTtsAdapter(object(), self.catalog(), profile=self.profile("voice-clone"))
        with patch.dict(sys.modules, {"torch": torch, "qwen_tts": qwen}):
            operation = adapter.build_operation(self.request())
            qwen.Qwen3TTSModel.from_pretrained.assert_not_called()
            adapter._invoke(operation)
            adapter._invoke(operation)
        qwen.Qwen3TTSModel.from_pretrained.assert_called_once_with(
            str(self.root / "voice-clone"), device_map="cuda:0",
            dtype=torch.bfloat16, local_files_only=True,
        )


if __name__ == "__main__":
    unittest.main()
