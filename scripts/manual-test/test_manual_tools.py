"""测试环境工具的回归检查；不启动 Android，不调用收费接口。"""

import subprocess
import unittest
from pathlib import Path
from unittest.mock import mock_open, patch

import make_test_books as books
import mock_tts_server as tts


class ManualToolsTest(unittest.TestCase):
    def test_shell_syntax(self):
        for script in Path(__file__).parent.glob("*.sh"):
            with self.subTest(script=script.name):
                subprocess.run(["bash", "-n", str(script)], check=True)

    def test_voice_catalog_has_unique_ids(self):
        catalog = tts.voice_catalog()
        self.assertEqual(9, len(catalog))
        self.assertEqual(len(catalog), len({v["toneID"] for v in catalog}))
        self.assertIn(tts.DEFAULT_VOICE, tts.VOICES)

    def test_engine_contract(self):
        engine = tts.engine_json()[0]
        self.assertEqual(2, engine["type"])
        self.assertEqual(engine["id"], tts.engine_json()[0]["id"])
        self.assertIn(f":{tts.PORT}/v1/audio/speech", engine["url"])
        for function in ("synthesize", "voices", "options"):
            self.assertIn(f"function {function}(", engine["script"])

    def test_books_are_deterministic(self):
        for builder in (books.book_a, books.book_b, books.book_c):
            with self.subTest(builder=builder.__name__):
                self.assertEqual(builder(), builder())

    def test_same_title_different_author(self):
        first, second = books.book_a(), books.book_b()
        self.assertEqual(first[0], second[0])
        self.assertNotEqual(first[1], second[1])
        self.assertEqual(30, len(first[3]))
        self.assertEqual(5, len(second[3]))

    def test_boundary_fixtures(self):
        chapters = dict(books.book_c()[3])
        self.assertEqual(8, len(chapters))
        self.assertGreaterEqual(len(chapters["超长章节"]), 18000)
        self.assertIn("😀", chapters["符号与外文"])
        self.assertIn("师父", chapters["场景称谓"])
        self.assertNotIn("“", chapters["纯旁白"])

    def test_stable_aliases_exclude_contextual_titles(self):
        for character in books.CAST.values():
            self.assertTrue(
                {"师父", "娘", "哥哥"}.isdisjoint(character["aliases"])
            )

    def test_book_filename_and_chapter_format(self):
        writer = mock_open()
        with patch.object(books.os, "makedirs"), patch("builtins.open", writer):
            path, count, chars = books.write_book(
                "样本", "测试作者", "简介", [("开篇", "  正文  ")]
            )
        self.assertTrue(path.endswith("《样本》作者：测试作者.txt"))
        self.assertEqual(1, count)
        self.assertEqual(6, chars)
        self.assertIn("第1章 开篇\n\n正文", writer().write.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
