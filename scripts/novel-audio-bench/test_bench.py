import unittest
import json
import subprocess
import sys
from pathlib import Path
from bench import split_text, summarize, validate_url, role_segments


class BenchmarkTests(unittest.TestCase):
    def test_split_preserves_every_character(self):
        text = '长宁镇的银行。曾晚说：“还给我。”\n' * 12
        chunks = split_text(text, 50)
        self.assertEqual(''.join(chunks), text)
        self.assertTrue(all(len(x) <= 50 for x in chunks))

    def test_split_prefers_sentence_then_clause(self):
        self.assertEqual(split_text('甲乙。丙丁，戊己庚辛', 6), ['甲乙。', '丙丁，', '戊己庚辛'])

    def test_rtf_uses_total_duration_not_average_of_ratios(self):
        result = summarize([{'wall_s': 4, 'duration_s': 2}, {'wall_s': 4, 'duration_s': 6}])
        self.assertEqual(result['rtf'], 1)
        self.assertEqual(result['duration_s'], 8)

    def test_external_hosts_and_userinfo_are_rejected(self):
        for value in ['http://127.0.0.1:8787@example.com', 'http://example.com:8787',
                      'http://localhost:8787/path', 'http://localhost:8787/?x=1']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_url(value)
        self.assertEqual(validate_url('http://127.0.0.1:8787'), 'http://127.0.0.1:8787')

    def test_roles_preserve_narration_around_split_quotes(self):
        text = '他说：“你好。”她说：“再见。”'
        rows = role_segments(text, {'male': [0], 'female': [1]}, 5)
        self.assertEqual(''.join(row['text'] for row in rows), text)
        self.assertEqual([row['role'] for row in rows], ['narrator', 'male', 'narrator', 'female'])

    def test_all_role_fixtures_cover_exact_text_and_only_three_voices(self):
        fixtures = Path(__file__).parent / 'fixtures'
        annotations = json.loads((fixtures / 'roles.json').read_text(encoding='utf-8'))
        for chapter in ('ch01', 'ch02', 'ch03'):
            text = (fixtures / (chapter + '.txt')).read_text(encoding='utf-8')
            rows = role_segments(text, annotations[chapter])
            self.assertEqual(''.join(row['text'] for row in rows), text)
            self.assertEqual({row['role'] for row in rows}, {'narrator', 'male', 'female'})
            self.assertTrue(all(len(row['text']) <= 50 for row in rows))

    def test_offline_runner_rejects_network_engine_before_loading(self):
        result = subprocess.run([sys.executable, str(Path(__file__).parent / 'local_bench.py'),
            '--engine', 'voicestudio', '--vs-engine', 'gpt-sovits', '--config', 'unused',
            '--output', 'unused'], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'invalid choice', result.stderr)


if __name__ == '__main__':
    unittest.main()
