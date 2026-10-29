import unittest
from pathlib import Path
from types import SimpleNamespace

from qwen_batch_backend import QwenBatchTtsAdapter
from scripts.novel_audio_server.errors import InvalidBackendResponseError


class BatchTtsTests(unittest.TestCase):
    def adapter(self, count=None):
        catalog = SimpleNamespace(asset=lambda voice, caps: SimpleNamespace(
            kind='base', reference_audio=Path(voice + '.wav'), reference_error='', reference_text=voice))
        config = SimpleNamespace(root=Path('.'), tts=SimpleNamespace(base_model=Path('model'),
            ffmpeg=Path('ffmpeg'), ffprobe=Path('ffprobe')))
        adapter = QwenBatchTtsAdapter(config, catalog, audio_encoder=lambda wave, *_: wave)
        class Model:
            def create_voice_clone_prompt(self, **kw):
                return [kw['ref_text']]
            def generate_voice_clone(self, **kw):
                self.kw = kw
                return list(range(len(kw['text']) if count is None else count)), 24000
        adapter.model = Model()
        return adapter

    def requests(self, size=4):
        return [dict(text=str(i), voiceAssetId='local.' + str(i % 2), speed=1.0) for i in range(size)]

    def test_native_batch_preserves_per_item_voice_and_output_order(self):
        adapter = self.adapter()
        self.assertEqual(adapter.synthesize_batch(self.requests()), [(0, 24000), (1, 24000), (2, 24000), (3, 24000)])
        self.assertEqual(adapter.model.kw['voice_clone_prompt'], ['local.0', 'local.1', 'local.0', 'local.1'])
        self.assertFalse(adapter.model.kw['non_streaming_mode'])
        self.assertEqual(len(adapter.clone_prompts), 2)

    def test_long_clone_enables_non_streaming_explicitly(self):
        adapter = self.adapter()
        adapter.synthesize_batch(self.requests(1), non_streaming_mode=True)
        self.assertTrue(adapter.model.kw['non_streaming_mode'])

    def test_missing_batch_output_is_rejected(self):
        with self.assertRaises(InvalidBackendResponseError):
            self.adapter(count=3).synthesize_batch(self.requests())

    def test_batch_bound_is_eight(self):
        with self.assertRaises(ValueError):
            self.adapter().synthesize_batch(self.requests(9))

    def test_closed_adapter_does_not_generate(self):
        adapter = self.adapter()
        adapter.close()
        with self.assertRaises(RuntimeError):
            adapter.synthesize_batch(self.requests())


if __name__ == '__main__':
    unittest.main()
