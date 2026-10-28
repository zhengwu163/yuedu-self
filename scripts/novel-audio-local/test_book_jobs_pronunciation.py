import json
import tempfile
import unittest
from pathlib import Path

from book_jobs_pronunciation import PronunciationDictionary, PronunciationError


class PronunciationDictionaryTest(unittest.TestCase):
    def test_longest_match_wins_and_output_is_not_rescanned(self):
        dictionary = PronunciationDictionary([
            {"source": "单", "target": "丹", "scope": "global"},
            {"source": "单牧", "target": "善牧", "scope": "global"},
            {"source": "善", "target": "错", "scope": "global"},
        ])
        self.assertEqual("善牧拿着丹子", dictionary.apply("单牧拿着单子", "work"))

    def test_work_entry_overrides_global_and_stays_scoped(self):
        dictionary = PronunciationDictionary([
            {"source": "解", "target": "姐", "scope": "global"},
            {"source": "解", "target": "谢", "scope": "work-a"},
        ])
        self.assertEqual("谢老板", dictionary.apply("解老板", "work-a"))
        self.assertEqual("姐老板", dictionary.apply("解老板", "work-b"))

    def test_digest_changes_only_with_visible_entries(self):
        base = [{"source": "曾", "target": "增", "scope": "global"}]
        other = base + [{"source": "解", "target": "谢", "scope": "work-b"}]
        own = base + [{"source": "解", "target": "谢", "scope": "work-a"}]
        digest = PronunciationDictionary(base).digest_for("work-a")
        self.assertEqual(digest, PronunciationDictionary(other).digest_for("work-a"))
        self.assertNotEqual(digest, PronunciationDictionary(own).digest_for("work-a"))

    def test_missing_file_is_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            dictionary = PronunciationDictionary.load(Path(directory) / "missing.json")
            self.assertEqual("单牧", dictionary.apply("单牧", "work"))

    def test_load_reads_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pronunciation.json"
            path.write_text(json.dumps({"version": 1, "entries": [
                {"source": "曾晚", "target": "增晚", "scope": "global"}
            ]}, ensure_ascii=False), encoding="utf-8")
            self.assertEqual("增晚说", PronunciationDictionary.load(path).apply("曾晚说", "w"))

    def test_invalid_files_and_entries_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pronunciation.json"
            for content in ("{", "[]", '{"entries": {}}'):
                path.write_text(content, encoding="utf-8")
                with self.subTest(content), self.assertRaises(PronunciationError):
                    PronunciationDictionary.load(path)
        for entry in (
            {"source": "", "target": "x"},
            {"source": "x", "target": " "},
            {"source": "x", "target": "y", "scope": ""},
            {"source": "x" * 65, "target": "y"},
        ):
            with self.subTest(entry), self.assertRaises(PronunciationError):
                PronunciationDictionary([entry])


if __name__ == "__main__":
    unittest.main()
