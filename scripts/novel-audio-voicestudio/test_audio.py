import unittest

from audio import validate_audio
from errors import ProviderError


class AudioTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
