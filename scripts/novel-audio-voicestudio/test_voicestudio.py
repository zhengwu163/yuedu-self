import time
import unittest

from errors import BusyError, ProviderError, ProviderTimeoutError
from models import SynthesisRequest
from voicestudio import VoiceStudioConfig, VoiceStudioSpeechProvider


class FakeTransport:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def __call__(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        return self.response


def json_bytes(value):
    import json
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


class VoiceStudioProviderTest(unittest.TestCase):
    def test_remote_http_requires_explicit_https_opt_in(self):
        with self.assertRaises(ValueError):
            VoiceStudioSpeechProvider(
                base_url="http://192.168.1.2:3900",
                token="local-token",
                profile="voice-studio-engine-v1",
                voice_resolver=lambda _: {"voice": "narrator-profile"},
            )

        with self.assertRaises(ValueError):
            VoiceStudioSpeechProvider(
                base_url="http://192.168.1.2:3900",
                token="local-token",
                profile="voice-studio-engine-v1",
                voice_resolver=lambda _: {"voice": "narrator-profile"},
                allow_remote=True,
            )

        with self.assertRaises(ValueError):
            VoiceStudioSpeechProvider(
                base_url="https://192.168.1.2:3900",
                token="local-token",
                profile="voice-studio-engine-v1",
                voice_resolver=lambda _: {"voice": "narrator-profile"},
            )

        VoiceStudioSpeechProvider(
            base_url="https://192.168.1.2:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            allow_remote=True,
        )

    def test_health_probes_voice_studio_instead_of_only_checking_configuration(self):
        transport = FakeTransport((
            503,
            {"Content-Type": "application/json"},
            b'{"detail":"backend unavailable"}',
        ))
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            transport=transport,
        )

        self.assertFalse(provider.health().ready)
        self.assertEqual(("GET", "/system/info", None), transport.calls[0])
        self.assertNotIn("local-token", repr(VoiceStudioConfig(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="profile",
        )))

    def test_wav_health_requires_an_available_ffmpeg(self):
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            ffmpeg_path="/path/that/does/not/exist",
            transport=FakeTransport((
                200,
                {"Content-Type": "application/json"},
                b"{}",
            )),
        )

        self.assertFalse(provider.health().ready)

    def test_sends_resolved_provider_voice_without_leaking_android_id(self):
        transport = FakeTransport((
            200,
            {"Content-Type": "audio/ogg"},
            b"OggS-local-audio",
        ))
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda voice_id: {
                "voicestudio.narrator": {"voice": "narrator-profile"},
            }[voice_id],
            transport=transport,
        )

        result = provider.synthesize(SynthesisRequest(
            text="测试",
            voice_asset_id="voicestudio.narrator",
            language="zh-CN",
            speed=1.0,
        ))

        self.assertEqual(b"OggS-local-audio", result.audio)
        self.assertEqual("voice-studio-engine-v1", result.profile)
        self.assertEqual("POST", transport.calls[0][0])
        self.assertEqual("/v1/audio/speech", transport.calls[0][1])
        self.assertEqual("tts-1", transport.calls[0][2]["model"])
        self.assertEqual("narrator-profile", transport.calls[0][2]["voice"])
        self.assertEqual("wav", transport.calls[0][2]["response_format"])
        self.assertNotEqual(
            "voicestudio.narrator",
            transport.calls[0][2]["voice"],
        )

    def test_accepts_case_insensitive_content_type_headers(self):
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            transport=FakeTransport((
                200,
                {"content-type": "audio/ogg"},
                b"OggS-local-audio",
            )),
        )

        result = provider.synthesize(SynthesisRequest(
            text="测试",
            voice_asset_id="voice",
            language="zh-CN",
            speed=1.0,
        ))

        self.assertEqual("audio/ogg", result.content_type)
        self.assertEqual(b"OggS-local-audio", result.audio)

    def test_maps_provider_rate_limit_to_busy(self):
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            transport=FakeTransport((429, {}, b"")),
        )

        with self.assertRaises(BusyError):
            provider.synthesize(SynthesisRequest(
                text="测试",
                voice_asset_id="voice",
                language="zh-CN",
                speed=1.0,
            ))

    def test_discovers_saved_profiles_without_exposing_provider_fields(self):
        transport = FakeTransport((
            200,
            {"Content-Type": "application/json"},
            json_bytes({
                "voices": [
                    {
                        "voice_id": "demo0001",
                        "name": "旁白",
                        "type": "profile",
                        "language": "zh-CN",
                    },
                    {
                        "voice_id": "default",
                        "name": "默认",
                        "type": "alias",
                    },
                ],
                "engines": [],
            }),
        ))
        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda voice_id: {"voice": voice_id},
            transport=transport,
        )

        voices = provider.voices()

        self.assertEqual("voicestudio.profile.demo0001", voices[0].voice_asset_id)
        self.assertEqual("旁白", voices[0].display_name)
        self.assertEqual(
            "demo0001",
            provider.provider_ref_for("voicestudio.profile.demo0001"),
        )
        self.assertEqual("GET", transport.calls[0][0])
        self.assertEqual("/v1/audio/voices", transport.calls[0][1])
        self.assertNotIn("voice_id", voices[0].as_dict())

    def test_provider_maps_remote_errors_to_sanitized_errors(self):
        for status in (401, 403, 500):
            with self.subTest(status=status):
                transport = FakeTransport((status, {}, b"secret provider error"))
                provider = VoiceStudioSpeechProvider(
                    base_url="http://127.0.0.1:3900",
                    token="local-token",
                    profile="voice-studio-engine-v1",
                    voice_resolver=lambda _: {"voice": "narrator-profile"},
                    transport=transport,
                )

                with self.assertRaises(ProviderError) as caught:
                    provider.synthesize(SynthesisRequest(
                        text="测试",
                        voice_asset_id="voice",
                        language="zh-CN",
                        speed=1.0,
                    ))

                self.assertNotIn("secret provider error", str(caught.exception))

    def test_empty_or_wrong_audio_is_rejected(self):
        for body in (b"", b"not-audio"):
            with self.subTest(body=body):
                provider = VoiceStudioSpeechProvider(
                    base_url="http://127.0.0.1:3900",
                    token="local-token",
                    profile="voice-studio-engine-v1",
                    voice_resolver=lambda _: {"voice": "narrator-profile"},
                    transport=FakeTransport((200, {"Content-Type": "audio/ogg"}, body)),
                )

                with self.assertRaises(ProviderError):
                    provider.synthesize(SynthesisRequest(
                        text="测试",
                        voice_asset_id="voice",
                        language="zh-CN",
                        speed=1.0,
                    ))

    def test_request_deadline_covers_transport_before_audio_normalization(self):
        def slow_transport(method, path, payload, context=None):
            time.sleep(0.02)
            context.check()
            return 200, {"Content-Type": "audio/ogg"}, b"OggS-local-audio"

        provider = VoiceStudioSpeechProvider(
            base_url="http://127.0.0.1:3900",
            token="local-token",
            profile="voice-studio-engine-v1",
            voice_resolver=lambda _: {"voice": "narrator-profile"},
            transport=slow_transport,
            request_timeout=0.01,
        )

        with self.assertRaises(ProviderTimeoutError):
            provider.synthesize(SynthesisRequest(
                text="测试",
                voice_asset_id="voice",
                language="zh-CN",
                speed=1.0,
            ))


if __name__ == "__main__":
    unittest.main()
