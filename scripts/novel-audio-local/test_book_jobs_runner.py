import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from scripts.novel_audio_server.errors import ServiceBusyError

from book_jobs_pronunciation import PronunciationDictionary
from book_jobs_runner import MAX_CHAPTER_ATTEMPTS, BookJobRunner
from book_jobs_store import BookJobStore

VOICES = [
    {"voiceAssetId": "N1", "narrator": True},
    {"voiceAssetId": "F1", "gender": "female"},
    {"voiceAssetId": "M1", "gender": "male"},
]


class FakeBackend:
    profile = "fake-local-v1"

    def __init__(self):
        self.analysis_requests = []
        self.synthesis_requests = []
        self.on_analyze = None
        self.on_synthesize = None
        self.invalid_analysis = False

    def analyze(self, request):
        self.analysis_requests.append(request)
        if self.on_analyze:
            self.on_analyze()
        if self.invalid_analysis:
            return {"assignments": [], "newCharacters": [], "aliasUpdates": []}
        assignments = [
            {"unitId": unit["unitId"],
             "speakerId": "t1" if unit["text"].startswith("“") else "narrator"}
            for unit in request["units"]
        ]
        return {
            "assignments": assignments,
            "newCharacters": [{
                "temporaryId": "t1", "displayName": "曾晚", "gender": "女",
                "ageRange": "青年", "voicePersona": {"traits": ["清亮"]},
            }],
            "aliasUpdates": [],
        }

    def synthesize(self, request):
        self.synthesis_requests.append(request)
        if self.on_synthesize:
            self.on_synthesize()
        return b"OggS-" + request["text"].encode("utf-8")


class FakeRuntime:
    def __init__(self, backend):
        self.backend = backend
        self.lease = None
        self.acquired = []
        self.busy = False

    def acquire(self, session_id, purpose, expected_chapter_count):
        if self.busy:
            raise ServiceBusyError()
        self.acquired.append(purpose)
        self.lease = "lease-%d" % len(self.acquired)
        return SimpleNamespace(lease_id=self.lease)

    def begin_generation(self, lease_id):
        assert lease_id == self.lease
        return self.backend

    def end_generation(self, lease_id):
        assert lease_id == self.lease

    def release(self, lease_id):
        assert lease_id == self.lease
        self.lease = None


class FakeCatalog:
    def __init__(self, voices=VOICES):
        self.voices = voices

    def public_voices(self, capabilities=None):
        return [dict(voice) for voice in self.voices]

    def match(self, persona, used, constraints=None, capabilities=None):
        gender = (constraints or {}).get("gender")
        candidates = [voice for voice in self.voices if not voice.get("narrator")]
        return sorted(
            candidates,
            key=lambda voice: (voice["voiceAssetId"] in used, voice.get("gender") != gender),
        )


UNITS = [
    {"unitId": "n0", "text": "曾晚推门进来。", "roleType": "narrator",
     "pieces": ["曾晚推门进来。"]},
    {"unitId": "d1", "text": "“钟又不响了。”", "roleType": "dialogue",
     "pieces": ["“钟又不响了。”"]},
]


class BookJobRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.store = BookJobStore(root / "jobs.sqlite3", root / "audio")
        self.backend = FakeBackend()
        self.runtime = FakeRuntime(self.backend)
        self.now = 1000.0
        self.dictionary = PronunciationDictionary(
            [{"source": "曾晚", "target": "增晚", "scope": "global"}])
        self.runner = BookJobRunner(
            self.store, self.runtime, FakeCatalog(), lambda: ["voice-design"],
            lambda: self.dictionary, clock=lambda: self.now,
            idle_seconds=0, busy_backoff_seconds=0,
        )
        table = {"characters": [], "aliases": [], "merges": {}, "bindings": {}, "phoneIds": []}
        self.store.create_job("job-1", "work", "哑钟", 1, 120, table, self.now)

    def tearDown(self):
        self.runner.close()
        self.store.close()
        self.directory.cleanup()

    def upload(self, chapter_id="c1", units=UNITS):
        self.store.put_chapter("job-1", chapter_id, 1, "hash-" + chapter_id, units)

    def test_chapter_is_analyzed_synthesized_and_job_finishes(self):
        self.upload()
        self.store.mark_uploaded("job-1", self.now)
        self.assertTrue(self.runner.step())
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual("done", chapter["state"])
        self.assertEqual({"n0": 0, "d1": 1}, chapter["speakers"])
        pieces = self.store.pieces("job-1", "c1")
        self.assertEqual(["N1", "F1"], [piece["voice_asset_id"] for piece in pieces])
        self.assertEqual("增晚推门进来。", self.backend.synthesis_requests[0]["text"])
        for piece in pieces:
            path = self.store.piece_audio_path("job-1", piece["audio_id"])
            self.assertTrue(path.read_bytes().startswith(b"OggS-"))
            self.assertEqual("fake-local-v1", piece["tts_profile"])
            self.assertEqual(self.dictionary.digest_for("work"), piece["pronunciation"])
        table = json.loads(self.store.job("job-1")["table_json"])
        self.assertEqual("曾晚", table["characters"][0]["name"])
        self.assertEqual("F1", table["bindings"]["1"]["voiceAssetId"])
        self.assertEqual(["book_job"], self.runtime.acquired)

        self.assertTrue(self.runner.step())
        self.assertEqual("done", self.store.job("job-1")["state"])
        self.assertFalse(self.runner.step())
        self.assertIsNone(self.runtime.lease)
        self.assertFalse(self.runner.busy())

    def test_waiting_for_upload_does_not_hold_the_model(self):
        self.assertFalse(self.runner.step())
        self.assertEqual([], self.runtime.acquired)
        self.assertTrue(self.runner.busy())

    def test_busy_runtime_leaves_chapter_pending(self):
        self.upload()
        self.runtime.busy = True
        self.assertTrue(self.runner.step())
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual(("pending", None), (chapter["state"], chapter["failure"]))

    def test_no_voice_fails_chapter_as_analysis_failure(self):
        self.runner.catalog = FakeCatalog(voices=[])
        self.upload()
        self.runner.step()
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual(("failed", "analysis_failed"), (chapter["state"], chapter["failure"]))

    def test_invalid_model_answer_retries_then_fails(self):
        self.backend.invalid_analysis = True
        self.upload()
        for _ in range(MAX_CHAPTER_ATTEMPTS - 1):
            self.runner.step()
            self.assertEqual("pending", self.store.chapter("job-1", "c1")["state"])
        self.runner.step()
        chapter = self.store.chapter("job-1", "c1")
        self.assertEqual(("failed", "generation_failed"), (chapter["state"], chapter["failure"]))
        self.assertEqual(["book_job"], self.runtime.acquired)

    def test_pause_mid_chapter_keeps_finished_pieces(self):
        self.upload()
        self.backend.on_synthesize = lambda: self.store.set_job_state("job-1", "paused", self.now)
        self.runner.step()
        self.assertIsNone(self.runtime.lease)
        pending = self.store.pending_pieces("job-1", "c1")
        self.assertEqual(["d1"], [piece["unit_id"] for piece in pending])

        self.backend.on_synthesize = None
        self.store.set_job_state("job-1", "running", self.now)
        self.runner.step()
        self.assertEqual("done", self.store.chapter("job-1", "c1")["state"])
        self.assertEqual(2, len(self.backend.synthesis_requests))
        self.assertEqual(1, len(self.backend.analysis_requests))

    def test_manual_binding_during_analysis_wins(self):
        self.upload()

        def bind_manually():
            table = json.loads(self.store.job("job-1")["table_json"])
            table["bindings"]["0"] = {"voiceAssetId": "M1", "revision": 5}
            self.store.save_table("job-1", table, self.now)

        self.backend.on_analyze = bind_manually
        self.runner.step()
        pieces = self.store.pieces("job-1", "c1")
        self.assertEqual("M1", pieces[0]["voice_asset_id"])
        table = json.loads(self.store.job("job-1")["table_json"])
        self.assertEqual({"voiceAssetId": "M1", "revision": 5}, table["bindings"]["0"])

    def test_purge_runs_once_a_day(self):
        self.upload()
        self.store.mark_uploaded("job-1", self.now)
        self.runner.step()
        self.store.mark_downloaded("job-1", "c1", self.now)
        self.now += 31 * 86400
        self.runner.step()
        self.assertEqual("purged", self.store.chapter("job-1", "c1")["state"])


if __name__ == "__main__":
    unittest.main()
