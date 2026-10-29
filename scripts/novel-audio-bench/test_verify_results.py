import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from verify_results import verify_chapter


class EvidenceTests(unittest.TestCase):
    def test_completion_flag_cannot_hide_missing_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ch01.txt').write_text('完整正文', encoding='utf-8')
            path = root / 'result.json'
            path.write_text(json.dumps({'complete': True, 'chapter': 'ch01',
                'text_sha256': hashlib.sha256('完整正文'.encode()).hexdigest(),
                'segments': []}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'missing, duplicate'):
                verify_chapter(path, root)

    def test_changed_fixture_cannot_reuse_old_measurement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ch01.txt').write_text('新正文', encoding='utf-8')
            path = root / 'result.json'
            path.write_text(json.dumps({'complete': True, 'chapter': 'ch01',
                'text_sha256': hashlib.sha256('旧正文'.encode()).hexdigest()}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'fixture changed'):
                verify_chapter(path, root)


if __name__ == '__main__':
    unittest.main()
