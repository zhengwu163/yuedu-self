import io
import shutil
import subprocess
import unittest
import wave

from audio import normalize_audio, validate_audio
from errors import ProviderError


class AudioTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is required for codec verification")
    def test_real_ffmpeg_encodes_and_decodes_opus(self):
        source = io.BytesIO()
        with wave.open(source, "wb") as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(24000)
            stream.writeframes(b"\x00\x00" * 2400)
        content_type, audio = normalize_audio("audio/wav", source.getvalue())
        decoded = subprocess.run(
            ["ffmpeg", "-nostdin", "-v", "error", "-i", "pipe:0",
             "-f", "s16le", "-ac", "1", "-ar", "24000", "pipe:1"],
            input=audio, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=5, check=True,
        )
        self.assertEqual("audio/ogg", content_type)
        self.assertEqual(4800, len(decoded.stdout))

    def test_accepts_ogg_and_rejects_empty_or_unknown_audio(self):
        self.assertEqual(
            ("audio/ogg", b"OggS-valid"),
            validate_audio("audio/ogg", b"OggS-valid"),
        )

        for content_type, body in (
            ("audio/ogg", b""),
            ("application/octet-stream", b"data"),
        ):
            with self.subTest(content_type=content_type, body=body):
                with self.assertRaises(ProviderError):
                    validate_audio(content_type, body)

    def test_converts_wav_to_android_compatible_ogg(self):
        calls = []

        def runner(command, **kwargs):
            calls.append((command, kwargs))
            return type("Result", (), {
                "returncode": 0,
                "stdout": b"OggS-converted",
            })()

        source = io.BytesIO()
        with wave.open(source, "wb") as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(24000)
            stream.writeframes(b"\x00\x00" * 240)

        content_type, body = normalize_audio(
            "audio/wav",
            source.getvalue(),
            ffmpeg_path="ffmpeg",
            runner=runner,
        )

        self.assertEqual("audio/ogg", content_type)
        self.assertEqual(b"OggS-converted", body)
        self.assertEqual("ffmpeg", calls[0][0][0])
        self.assertEqual(5, calls[0][1]["timeout"])
        self.assertIn("-nostdin", calls[0][0])
        self.assertIn("-protocol_whitelist", calls[0][0])
        self.assertIn("pipe", calls[0][0])


if __name__ == "__main__":
    unittest.main()
