import io
import json
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import audio
from audio import AudioError, encode_ogg_opus, waveform_to_wav


class _Result:
    def __init__(self, stdout=b""):
        self.returncode = 0
        self.stdout = stdout


class AudioEncodingTest(unittest.TestCase):
    def test_qwen_batch_of_numpy_like_waveforms(self):
        class Array:
            def tolist(self):
                return [0.0, 0.5, -1.0]
        encoded = waveform_to_wav(([Array()], 24000))
        with wave.open(io.BytesIO(encoded), "rb") as source:
            self.assertEqual(3, source.getnframes())
            self.assertEqual(24000, source.getframerate())

    def test_waveform_is_normalized_to_mono_pcm16_wav(self):
        encoded = waveform_to_wav(([0.0, 0.5, -1.0], 24000))

        with wave.open(io.BytesIO(encoded), "rb") as source:
            self.assertEqual(1, source.getnchannels())
            self.assertEqual(2, source.getsampwidth())
            self.assertEqual(24000, source.getframerate())
            self.assertEqual(3, source.getnframes())

    def test_encode_validates_wav_and_ogg_and_uses_fixed_opus_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = []

            def runner(command, **kwargs):
                calls.append(command)
                if command[0] == "ffprobe":
                    return _Result(
                        json.dumps(
                            {
                                "streams": [
                                    {
                                        "codec_type": "audio",
                                        "channels": 1,
                                        "sample_rate": "24000",
                                        "duration": "0.1",
                                    }
                                ]
                            }
                        ).encode("utf-8")
                    )
                self.assertEqual("ffmpeg", command[0])
                Path(command[-1]).write_bytes(b"OggS-test")
                return _Result()

            result = encode_ogg_opus(
                [0.0, 0.25, -0.25],
                1.25,
                "ffmpeg",
                "ffprobe",
                root,
                runner=runner,
            )

            self.assertEqual(b"OggS-test", result)
            self.assertEqual(3, len(calls))
            ffmpeg = calls[1]
            self.assertEqual(
                ["-filter:a", "atempo=1.25"],
                ffmpeg[ffmpeg.index("-filter:a") : ffmpeg.index("-filter:a") + 2],
            )
            for option, value in (
                ("-ac", "1"),
                ("-ar", "24000"),
                ("-c:a", "libopus"),
                ("-f", "ogg"),
            ):
                self.assertEqual(value, ffmpeg[ffmpeg.index(option) + 1])
            self.assertEqual([], list(root.iterdir()))

    def test_invalid_waveform_and_probe_output_are_rejected(self):
        with self.assertRaises(AudioError):
            waveform_to_wav(b"not-a-waveform")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AudioError):
                encode_ogg_opus(
                    [0.0, 0.1],
                    1.0,
                    "ffmpeg",
                    "ffprobe",
                    Path(directory),
                    runner=lambda *args, **kwargs: _Result(b"{}"),
                )
            self.assertEqual([], list(Path(directory).iterdir()))


class ReferenceWavTest(unittest.TestCase):
    @staticmethod
    def wav(channels=1, width=2, rate=24000, frames=24):
        output = io.BytesIO()
        with wave.open(output, "wb") as target:
            target.setparams((channels, width, rate, 0, "NONE", "not compressed"))
            target.writeframes(b"\x00" * channels * width * frames)
        return output.getvalue()

    def validate(self, content):
        validator = getattr(audio, "validate_reference_wav", None)
        self.assertTrue(callable(validator), "missing stdlib reference WAV validator")
        return validator(content)

    def test_accepts_complete_24k_mono_pcm16_without_external_decoder(self):
        content = self.wav()
        with patch("audio.subprocess.run", side_effect=AssertionError("external decoder")):
            self.assertEqual(content, self.validate(content))

    def test_rejects_empty_non_wav_and_header_only(self):
        for content in (b"", b"RIFF", b"not audio", self.wav(frames=0)):
            with self.subTest(content=content[:8]), self.assertRaises(AudioError):
                self.validate(content)

    def test_rejects_unapproved_formats(self):
        for kwargs in ({"channels": 2}, {"width": 1}, {"width": 3}, {"rate": 16000}):
            with self.subTest(kwargs=kwargs), self.assertRaises(AudioError):
                self.validate(self.wav(**kwargs))

    def test_rejects_truncated_frames_even_with_consistent_riff_size(self):
        content = bytearray(self.wav()[:-2])
        content[4:8] = (len(content) - 8).to_bytes(4, "little")
        with self.assertRaises(AudioError):
            self.validate(bytes(content))

    def test_rejects_partial_pcm_frame(self):
        content = bytearray(self.wav() + b"\x00")
        content[4:8] = (len(content) - 8).to_bytes(4, "little")
        content[40:44] = (len(content) - 44).to_bytes(4, "little")
        with self.assertRaises(AudioError):
            self.validate(bytes(content))

    def test_rejects_missing_half_frame_hidden_by_frame_count_rounding(self):
        content = bytearray(self.wav())
        content[40:44] = (len(content) - 43).to_bytes(4, "little")
        with self.assertRaises(AudioError):
            self.validate(bytes(content))

    def test_rejects_non_pcm16_fmt_fields_even_when_wave_accepts_them(self):
        for offset, width, value in ((34, 2, 15), (32, 2, 4), (28, 4, 96000)):
            with self.subTest(offset=offset):
                content = bytearray(self.wav())
                content[offset:offset + width] = value.to_bytes(width, "little")
                with self.assertRaises(AudioError):
                    self.validate(bytes(content))

    def test_rejects_incomplete_riff_container(self):
        content = bytearray(self.wav())
        content[4:8] = (len(content) + 16).to_bytes(4, "little")
        with self.assertRaises(AudioError):
            self.validate(bytes(content))

    def test_duration_is_checked_before_frame_decode(self):
        content = bytearray(self.wav())
        content[40:44] = (24000 * (audio.MAX_DURATION_SECONDS + 1) * 2).to_bytes(4, "little")
        with patch.object(wave.Wave_read, "readframes", side_effect=AssertionError("unbounded read")):
            with self.assertRaises(AudioError):
                self.validate(bytes(content))

    def test_size_is_checked_before_wave_parser(self):
        content = self.wav()
        with patch.object(audio, "MAX_AUDIO", len(content) - 1):
            with patch("audio.wave.open", side_effect=AssertionError("oversized parse")):
                with self.assertRaises(AudioError):
                    self.validate(content)

    def test_duration_limit_is_inclusive(self):
        content = self.wav(frames=24000 * audio.MAX_DURATION_SECONDS)
        self.assertEqual(content, self.validate(content))
        with self.assertRaises(AudioError):
            self.validate(self.wav(frames=24000 * audio.MAX_DURATION_SECONDS + 1))


if __name__ == "__main__":
    unittest.main()
