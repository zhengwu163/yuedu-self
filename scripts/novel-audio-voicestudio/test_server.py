import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import server
from config import load_config
from errors import AuthenticationError, ProviderError, ProviderTimeoutError
from models import VoiceAsset
from registry import VoiceRegistry
from server import _load_registry, _profile_revision, validate_bind_host


class StartupCheckTest(unittest.TestCase):
    ENV = {
        "NOVEL_AUDIO_TOKEN": "gateway-secret",
        "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3901",
        "VOICESTUDIO_TOKEN": "voicestudio-secret",
    }

    def run_check(self, env, failure=None):
        stderr = io.StringIO()
        build = patch.object(server, "build_gateway", side_effect=failure)
        with patch.dict(os.environ, env, clear=True), build, \
                contextlib.redirect_stderr(stderr):
            code = server.main(["--check"])
        return code, stderr.getvalue()

    def test_provider_failures_exit_with_operator_hint_not_traceback(self):
        cases = (
            (ProviderError(), "VOICESTUDIO_BASE_URL"),
            (ProviderTimeoutError(), "did not respond"),
            (AuthenticationError(), "VOICESTUDIO_TOKEN"),
        )
        for failure, hint in cases:
            with self.subTest(failure=type(failure).__name__):
                code, output = self.run_check(self.ENV, failure)
                self.assertEqual(1, code)
                self.assertIn(hint, output)
                self.assertNotIn("Traceback", output)
                self.assertNotIn("secret", output)

    def test_invalid_config_exits_with_variable_name_only(self):
        env = dict(self.ENV)
        del env["NOVEL_AUDIO_TOKEN"]
        code, output = self.run_check(env)
        self.assertEqual(1, code)
        self.assertIn("NOVEL_AUDIO_TOKEN is required", output)
        self.assertNotIn("secret", output)


class RequestTimeoutConfigTest(unittest.TestCase):
    BASE = StartupCheckTest.ENV

    def test_defaults_below_android_call_timeout(self):
        # Android 端 NovelAudioServerClient 的 callTimeout 为 45 秒。
        self.assertEqual(40, load_config(dict(self.BASE)).request_timeout)
        self.assertEqual(30, load_config(dict(
            self.BASE, NOVEL_AUDIO_REQUEST_TIMEOUT="30")).request_timeout)
        for value in ("0", "45", "1.5", "abc", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                load_config(dict(self.BASE, NOVEL_AUDIO_REQUEST_TIMEOUT=value))

    def test_gateway_and_speech_provider_use_configured_timeout(self):
        registry = VoiceRegistry.from_records([{
            "voiceAssetId": "voice", "displayName": "旁白",
            "gender": "unknown", "ageRange": "adult", "traits": [],
            "providerRef": "profile",
        }], profile_revision="revision")
        config = load_config(dict(self.BASE, NOVEL_AUDIO_REQUEST_TIMEOUT="30"))
        with patch.object(server, "_load_registry", return_value=registry):
            gateway = server.build_gateway(config)
        self.assertEqual(30, gateway.request_timeout)
        self.assertEqual(30, gateway.speech.config.request_timeout)

    def test_local_voicestudio_tts_is_not_metered_but_remote_director_is(self):
        registry = VoiceRegistry.from_records([{
            "voiceAssetId": "voice", "displayName": "旁白",
            "gender": "unknown", "ageRange": "adult", "traits": [],
            "providerRef": "profile",
        }], profile_revision="revision")
        cases = (
            ({}, []),
            ({"DIRECTOR_BASE_URL": "http://127.0.0.1:9", "DIRECTOR_TOKEN": "d"},
             ["analysis"]),
        )
        for extra, expected in cases:
            with self.subTest(expected=expected):
                config = load_config(dict(self.BASE, **extra))
                with patch.object(server, "_load_registry", return_value=registry):
                    gateway = server.build_gateway(config)
                self.assertEqual(expected, gateway._health()[2]["meteredOperations"])


class ServerConfigTest(unittest.TestCase):
    def test_discovery_failure_does_not_reuse_or_overwrite_old_registry(self):
        class UnavailableSpeech:
            def voices(self):
                raise ProviderError()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            registry = VoiceRegistry.from_records([{
                "voiceAssetId": "voice", "displayName": "旁白",
                "gender": "unknown", "ageRange": "adult", "traits": [],
                "providerRef": "previous-profile",
            }], profile_revision="previous-revision")
            registry.save(path)
            before = path.read_bytes()
            config = load_config({
                "NOVEL_AUDIO_TOKEN": "gateway-secret",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3901",
                "VOICESTUDIO_TOKEN": "voicestudio-secret",
                "NOVEL_AUDIO_VOICE_REGISTRY": str(path),
            })
            with self.assertRaises(ProviderError):
                _load_registry(config, UnavailableSpeech())
            self.assertEqual(before, path.read_bytes())

    def test_defaults_to_loopback_and_does_not_echo_tokens(self):
        config = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
        })

        self.assertEqual("127.0.0.1", config.host)
        self.assertFalse(config.allow_lan)
        self.assertEqual(8788, config.port)
        self.assertEqual("wav", config.voicestudio_response_format)
        self.assertEqual("ffmpeg", config.ffmpeg_path)
        self.assertEqual("/v1/audio/speech", config.voicestudio_speech_path)
        self.assertNotIn("gateway-secret", repr(config))
        self.assertNotIn("voicestudio-secret", repr(config))
        self.assertNotIn("director-secret", repr(load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "DIRECTOR_TOKEN": "director-secret",
        })))

    def test_requires_local_gateway_and_voice_studio_tokens(self):
        with self.assertRaises(ValueError):
            load_config({
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
                "VOICESTUDIO_TOKEN": "token",
            })
        with self.assertRaises(ValueError):
            load_config({
                "NOVEL_AUDIO_TOKEN": "token",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            })

    def test_missing_registry_is_discovered_and_saved_from_provider(self):
        class FakeSpeech:
            def voices(self):
                return [
                    VoiceAsset(
                        voice_asset_id="voicestudio.profile.demo0001",
                        display_name="旁白",
                        gender="unknown",
                        age_range="adult",
                        traits=("清晰",),
                        preview_available=True,
                    )
                ]

            def provider_ref_for(self, voice_asset_id):
                return {
                    "voicestudio.profile.demo0001": "demo0001",
                }[voice_asset_id]

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            config = load_config({
                "NOVEL_AUDIO_TOKEN": "gateway-secret",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
                "VOICESTUDIO_TOKEN": "voicestudio-secret",
                "NOVEL_AUDIO_VOICE_REGISTRY": str(path),
            })

            registry = _load_registry(config, FakeSpeech())

            self.assertTrue(path.exists())
            self.assertEqual("demo0001", registry.resolve(
                "voicestudio.profile.demo0001"
            ).provider_ref)

    def test_stale_registry_is_refreshed_when_synthesis_configuration_changes(self):
        class FakeSpeech:
            def voices(self):
                return [
                    VoiceAsset(
                        voice_asset_id="voicestudio.profile.demo0001",
                        display_name="旁白",
                        gender="unknown",
                        age_range="adult",
                        traits=("清晰",),
                        preview_available=True,
                    )
                ]

            def provider_ref_for(self, voice_asset_id):
                return "demo0001"

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            first = load_config({
                "NOVEL_AUDIO_TOKEN": "gateway-secret",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
                "VOICESTUDIO_TOKEN": "voicestudio-secret",
                "NOVEL_AUDIO_VOICE_REGISTRY": str(path),
                "VOICESTUDIO_MODEL": "tts-1",
            })
            second = load_config({
                "NOVEL_AUDIO_TOKEN": "gateway-secret",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
                "VOICESTUDIO_TOKEN": "voicestudio-secret",
                "NOVEL_AUDIO_VOICE_REGISTRY": str(path),
                "VOICESTUDIO_MODEL": "tts-1-hd",
            })

            original = _load_registry(first, FakeSpeech())
            refreshed = _load_registry(second, FakeSpeech())

            self.assertNotEqual(
                original.profile_revision,
                refreshed.profile_revision,
            )

    def test_empty_voice_discovery_does_not_persist_an_empty_registry(self):
        class EmptySpeech:
            def voices(self):
                return []

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "voices.json"
            config = load_config({
                "NOVEL_AUDIO_TOKEN": "gateway-secret",
                "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
                "VOICESTUDIO_TOKEN": "voicestudio-secret",
                "NOVEL_AUDIO_VOICE_REGISTRY": str(path),
            })

            with self.assertRaises(ValueError):
                _load_registry(config, EmptySpeech())

            self.assertFalse(path.exists())

    def test_profile_revision_changes_with_synthesis_configuration(self):
        first = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "VOICESTUDIO_MODEL": "tts-1",
        })
        second = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "VOICESTUDIO_MODEL": "tts-1-hd",
        })

        self.assertNotEqual(
            _profile_revision(first),
            _profile_revision(second),
        )

        third = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3901",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "VOICESTUDIO_MODEL": "tts-1",
        })
        self.assertNotEqual(
            _profile_revision(first),
            _profile_revision(third),
        )

        fourth = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "VOICESTUDIO_PROFILE_REVISION": "2",
        })
        self.assertNotEqual(
            _profile_revision(first),
            _profile_revision(fourth),
        )

    def test_non_loopback_bind_requires_explicit_opt_in(self):
        validate_bind_host("127.0.0.1", allow_lan=False)
        with self.assertRaises(ValueError):
            validate_bind_host("0.0.0.0", allow_lan=False)
        validate_bind_host("0.0.0.0", allow_lan=True)

    def test_provider_config_is_used_for_voice_studio_speech_path(self):
        config = load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-secret",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "voicestudio-secret",
            "VOICESTUDIO_SPEECH_PATH": "/custom/speech",
        })

        self.assertEqual("/custom/speech", config.voicestudio_speech_path)


if __name__ == "__main__":
    unittest.main()
