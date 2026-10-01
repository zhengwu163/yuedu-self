import unittest

from errors import ProviderError
from models import SynthesisRequest
from voicestudio import VoiceStudioSpeechProvider


class FakeTransport:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def __call__(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        return self.response


class VoiceStudioProviderTest(unittest.TestCase):
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
        self.assertEqual("narrator-profile", transport.calls[0][2]["voice"])
        self.assertNotEqual(
            "voicestudio.narrator",
            transport.calls[0][2]["voice"],
        )

    def test_provider_maps_remote_errors_to_sanitized_errors(self):
        for status in (401, 403, 429, 500):
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


if __name__ == "__main__":
    unittest.main()
