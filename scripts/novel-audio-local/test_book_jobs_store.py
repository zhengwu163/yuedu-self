import tempfile
import unittest
from pathlib import Path

from book_jobs_store import BookJobStore

UNITS = [{"unitId": "u1", "text": "天黑了。", "roleType": "narrator", "pieces": ["天黑了。"]}]


class BookJobStoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.store = BookJobStore(root / "jobs.sqlite3", root / "audio")
        self.store.create_job("job-1", "work", "书", 2, 120, {"characters": []}, 1.0)

    def tearDown(self):
        self.store.close()
        self.directory.cleanup()

    def finish(self, chapter_id, audio_id="a" * 32):
        self.store.save_analysis(
            "job-1", chapter_id, {"u1": 0}, {"characters": []},
            [("u1", 0, "天黑了。", "N1")], 0.1, 2.0)
        path = self.store.piece_audio_path("job-1", audio_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"OggS")
        self.store.save_piece(
            "job-1", chapter_id, "u1", 0, audio_id, "sha", 4, "audio/ogg", "fake", "rev")
        self.store.finish_chapter("job-1", chapter_id, 0.2, None, 3.0)
        return path

    def test_mark_uploaded_moves_uploading_job_to_running(self):
        self.assertEqual("uploading", self.store.runnable_job()["state"])
        self.store.mark_uploaded("job-1", 2.0)
        job = self.store.job("job-1")
        self.assertEqual(("running", 1), (job["state"], job["uploaded"]))

    def test_paused_job_is_not_runnable(self):
        self.store.set_job_state("job-1", "paused", 2.0)
        self.assertIsNone(self.store.runnable_job())

    def test_chapters_run_in_upload_order(self):
        self.store.put_chapter("job-1", "c9", 9, "h9", UNITS)
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.assertEqual("c9", self.store.next_chapter("job-1")["chapter_id"])

    def test_same_hash_is_a_no_op(self):
        self.assertTrue(self.store.put_chapter("job-1", "c1", 1, "h1", UNITS))
        self.finish("c1")
        self.assertFalse(self.store.put_chapter("job-1", "c1", 1, "h1", UNITS))
        self.assertEqual("done", self.store.chapter("job-1", "c1")["state"])

    def test_new_hash_resets_chapter_and_keeps_position(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.store.put_chapter("job-1", "c2", 2, "h2", UNITS)
        path = self.finish("c1")
        self.assertTrue(self.store.put_chapter("job-1", "c1", 1, "h1b", UNITS))
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual(("pending", "h1b", 0), (
            chapter["state"], chapter["snapshot_hash"], chapter["position"]))
        self.assertEqual([], self.store.pieces("job-1", "c1"))
        self.assertFalse(path.exists())
        self.assertEqual("c1", self.store.next_chapter("job-1")["chapter_id"])

    def test_audio_only_served_for_done_chapters(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.store.save_analysis(
            "job-1", "c1", {"u1": 0}, {"characters": []}, [("u1", 0, "天黑了。", "N1")], 0.1, 2.0)
        self.store.save_piece(
            "job-1", "c1", "u1", 0, "b" * 32, "sha", 4, "audio/ogg", "fake", "rev")
        self.assertIsNone(self.store.audio("job-1", "b" * 32))
        self.store.finish_chapter("job-1", "c1", 0.2, None, 3.0)
        self.assertEqual("u1", self.store.audio("job-1", "b" * 32)["unit_id"])

    def test_retry_failed_returns_to_the_last_completed_stage(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.store.put_chapter("job-1", "c2", 2, "h2", UNITS)
        self.store.fail_chapter("job-1", "c1", "analysis_failed")
        self.store.save_analysis(
            "job-1", "c2", {"u1": 0}, {"characters": []}, [("u1", 0, "天黑了。", "N1")], 0.1, 2.0)
        self.store.fail_chapter("job-1", "c2", "generation_failed")
        self.store.retry_failed("job-1")
        self.assertEqual("pending", self.store.chapter("job-1", "c1")["state"])
        self.assertEqual("analyzed", self.store.chapter("job-1", "c2")["state"])

    def test_purge_waits_for_retention_after_download(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        path = self.finish("c1")
        self.assertEqual(0, self.store.purge_expired(10 ** 9, 30))
        self.assertTrue(self.store.mark_downloaded("job-1", "c1", 100.0))
        self.assertEqual(0, self.store.purge_expired(100.0 + 29 * 86400, 30))
        self.assertTrue(path.exists())
        self.assertEqual(1, self.store.purge_expired(100.0 + 30 * 86400, 30))
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual(("purged", []), (chapter["state"], chapter["units"]))
        self.assertFalse(path.exists())
        self.assertEqual("", self.store.pieces("job-1", "c1")[0]["text"])

    def test_first_download_time_is_kept(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.assertFalse(self.store.mark_downloaded("job-1", "c1", 5.0))
        self.finish("c1")
        self.assertTrue(self.store.mark_downloaded("job-1", "c1", 5.0))
        self.assertTrue(self.store.mark_downloaded("job-1", "c1", 9.0))
        self.assertEqual(5.0, self.store.chapter("job-1", "c1")["downloaded_at"])

    def test_delete_job_removes_rows_and_audio(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        path = self.finish("c1")
        self.store.delete_job("job-1")
        self.assertIsNone(self.store.job("job-1"))
        self.assertEqual([], self.store.chapters("job-1"))
        self.assertFalse(path.parent.exists())

    def test_state_survives_reopen(self):
        self.store.put_chapter("job-1", "c1", 1, "h1", UNITS)
        self.store.close()
        root = Path(self.directory.name)
        self.store = BookJobStore(root / "jobs.sqlite3", root / "audio")
        self.assertEqual(UNITS, self.store.chapter("job-1", "c1")["units"])


if __name__ == "__main__":
    unittest.main()
