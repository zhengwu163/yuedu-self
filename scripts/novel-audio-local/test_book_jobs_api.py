import contextlib
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from scripts.novel_audio_server.api import NovelAudioApi
from scripts.novel_audio_server.http import create_server

from book_jobs_api import BookJobsApi
from book_jobs_pronunciation import PronunciationDictionary
from book_jobs_runner import BookJobRunner
from book_jobs_store import BookJobStore
from test_book_jobs_runner import UNITS, FakeBackend, FakeCatalog
from test_book_jobs_runner import FakeRuntime as RunnerRuntime
from test_http import FakeBackend as HttpBackend
from test_http import FakeCatalog as HttpCatalog
from test_http import FakeRuntime as HttpRuntime


def create_body(**overrides):
    body = {
        "bookId": "work",
        "title": "哑钟",
        "expectedChapterCount": 3,
        "characters": [{"characterId": "1", "displayName": "单牧", "gender": "男"}],
        "aliases": [{"characterId": "1", "alias": "老单", "normalizedAlias": "老单"}],
        "merges": [],
        "bindings": [{"characterId": "1", "voiceAssetId": "M1", "revision": 2}],
    }
    body.update(overrides)
    return body


def chapter_body(units=UNITS, snapshot="hash-1"):
    return {"chapterIndex": 0, "snapshotHash": snapshot, "units": units}


class BookJobsApiTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.store = BookJobStore(root / "jobs.sqlite3", root / "audio")
        self.backend = FakeBackend()
        self.runtime = RunnerRuntime(self.backend)
        self.now = 1000.0
        self.runner = BookJobRunner(
            self.store, self.runtime, FakeCatalog(), lambda: ["voice-design"],
            PronunciationDictionary, clock=lambda: self.now,
            idle_seconds=0, busy_backoff_seconds=0,
        )
        self.api = BookJobsApi(self.store, self.runner, 120, lambda: self.now)

    def tearDown(self):
        self.api.close()
        self.store.close()
        self.directory.cleanup()

    def call(self, method, path, body=None):
        status, headers, payload = self.api.respond(method, "/v1/book-jobs" + path, body)
        return status, headers, payload

    def create(self, **overrides):
        status, _, body = self.call("POST", "", create_body(**overrides))
        self.assertEqual(200, status)
        return body["jobId"]

    def test_create_stores_the_phone_table(self):
        status, _, body = self.call("POST", "", create_body())
        self.assertEqual((200, 120, "uploading"), (
            status, body["maxSegmentChars"], body["state"]))
        table = json.loads(self.store.job(body["jobId"])["table_json"])
        self.assertEqual([{"id": 1, "name": "单牧", "gender": "male"}], table["characters"])
        self.assertEqual({"1": {"voiceAssetId": "M1", "revision": 2}}, table["bindings"])
        self.assertEqual([1], table["phoneIds"])
        self.assertEqual(200, self.call("GET", "")[0])

    def test_invalid_bodies_are_rejected(self):
        for body in (
            None,
            create_body(expectedChapterCount=0),
            create_body(characters=[{"characterId": "x", "displayName": "单牧"}]),
            create_body(characters=[{"characterId": "0", "displayName": "旁白"}]),
            create_body(bindings=[{"characterId": "1", "voiceAssetId": "M1", "revision": -1}]),
        ):
            with self.subTest(body):
                self.assertEqual(
                    (400, {"error": {"code": "invalid_request"}}),
                    self.call("POST", "", body)[::2],
                )
        job_id = self.create()
        duplicate = [dict(UNITS[0]), dict(UNITS[0])]
        self.assertEqual(400, self.call("PUT", f"/{job_id}/chapters/c1", chapter_body(duplicate))[0])
        too_long = [dict(UNITS[0], pieces=["字" * 1201])]
        self.assertEqual(400, self.call("PUT", f"/{job_id}/chapters/c1", chapter_body(too_long))[0])

    def test_unknown_routes(self):
        self.assertEqual(404, self.call("GET", "/job-missing")[0])
        job_id = self.create()
        self.assertEqual(404, self.call("PATCH", f"/{job_id}")[0])
        self.assertEqual(404, self.call("GET", f"/{job_id}/audio/not-hex")[0])
        self.assertEqual(404, self.call("GET", f"/{job_id}/chapters/c9")[0])

    def test_upload_generate_download_round_trip(self):
        job_id = self.create()
        self.assertEqual("stored", self.call(
            "PUT", f"/{job_id}/chapters/c1", chapter_body())[2]["state"])
        self.assertEqual("unchanged", self.call(
            "PUT", f"/{job_id}/chapters/c1", chapter_body())[2]["state"])
        self.assertEqual(409, self.call("GET", f"/{job_id}/chapters/c1")[0])
        self.assertEqual("running", self.call("POST", f"/{job_id}/uploaded")[2]["state"])

        self.runner.step()
        status, _, result = self.call("GET", f"/{job_id}/chapters/c1")
        self.assertEqual(200, status)
        self.assertEqual(["0", "2"], [unit["speakerId"] for unit in result["units"]])
        self.assertEqual(
            [{"characterId": "2", "displayName": "曾晚", "gender": "female",
              "phoneKnown": False, "stableAliases": ["曾晚"]}],
            result["characters"],
        )
        self.assertEqual({"0", "2"}, {item["characterId"] for item in result["bindings"]})
        piece = result["units"][1]["pieces"][0]
        self.assertEqual(0, piece["index"])
        status, headers, audio = self.call("GET", f"/{job_id}/audio/{piece['audioId']}")
        self.assertEqual((200, "audio/ogg", piece["size"]), (
            status, headers["Content-Type"], len(audio)))

        self.assertEqual(200, self.call("POST", f"/{job_id}/chapters/c1/downloaded")[0])
        detail = self.call("GET", f"/{job_id}")[2]
        self.assertEqual(1, detail["counts"]["done"])
        self.assertTrue(detail["chapters"][0]["downloaded"])
        self.assertIsNotNone(detail["recentChapterSeconds"])

    def test_download_before_done_is_a_conflict(self):
        job_id = self.create()
        self.call("PUT", f"/{job_id}/chapters/c1", chapter_body())
        self.assertEqual(409, self.call("POST", f"/{job_id}/chapters/c1/downloaded")[0])

    def test_pause_resume_cancel_and_delete(self):
        job_id = self.create()
        self.assertEqual("paused", self.call("POST", f"/{job_id}/pause")[2]["state"])
        self.assertEqual("uploading", self.call("POST", f"/{job_id}/resume")[2]["state"])
        self.assertEqual("cancelled", self.call("POST", f"/{job_id}/cancel")[2]["state"])
        self.assertEqual(409, self.call("PUT", f"/{job_id}/chapters/c1", chapter_body())[0])
        self.assertEqual(200, self.call("DELETE", f"/{job_id}")[0])
        self.assertIsNone(self.store.job(job_id))

    def test_resume_retries_failed_chapters(self):
        job_id = self.create()
        self.call("PUT", f"/{job_id}/chapters/c1", chapter_body())
        self.store.fail_chapter(job_id, "c1", "analysis_failed")
        self.call("POST", f"/{job_id}/pause")
        self.call("POST", f"/{job_id}/resume")
        self.assertEqual("pending", self.store.chapter(job_id, "c1")["state"])

    def test_voice_bindings_update_the_table(self):
        job_id = self.create()
        body = {"bindings": [{"characterId": "0", "voiceAssetId": "N2", "revision": 1}]}
        self.assertEqual(200, self.call("PUT", f"/{job_id}/voice-bindings", body)[0])
        table = json.loads(self.store.job(job_id)["table_json"])
        self.assertEqual({"voiceAssetId": "N2", "revision": 1}, table["bindings"]["0"])


class BookJobsHttpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.store = BookJobStore(root / "jobs.sqlite3", root / "audio")
        runner = BookJobRunner(
            self.store, RunnerRuntime(FakeBackend()), FakeCatalog(), lambda: [],
            PronunciationDictionary,
        )
        self.api = NovelAudioApi(
            token="local-token",
            backend=HttpBackend(),
            catalog=HttpCatalog(),
            runtime=HttpRuntime(HttpBackend()),
            book_jobs=BookJobsApi(self.store, runner, 120, lambda: 1000.0),
        )
        self.server = create_server("127.0.0.1", 0, self.api)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.store.close()
        self.directory.cleanup()

    def request(self, method, path, body=None, token="Bearer local-token"):
        headers = {"Authorization": token}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        with contextlib.closing(
            http.client.HTTPConnection(*self.server.server_address, timeout=3)
        ) as connection:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    def test_put_and_delete_reach_book_jobs_after_auth(self):
        self.assertEqual(401, self.request("POST", "/v1/book-jobs", create_body(), token="x")[0])
        status, body = self.request("POST", "/v1/book-jobs", create_body())
        self.assertEqual(200, status)
        job_id = body["jobId"]
        status, body = self.request(
            "PUT", f"/v1/book-jobs/{job_id}/chapters/c1", chapter_body())
        self.assertEqual((200, "stored"), (status, body["state"]))
        self.assertEqual(200, self.request("DELETE", f"/v1/book-jobs/{job_id}")[0])

    def test_running_job_blocks_per_chapter_generation(self):
        self.request("POST", "/v1/book-jobs", create_body())
        status, body = self.request("POST", "/v1/runtime/acquire", {
            "sessionId": "phone", "purpose": "foreground_play", "expectedChapterCount": 1,
        })
        self.assertEqual((429, "book_job_running"), (status, body["error"]["code"]))
        self.assertEqual(200, self.request("GET", "/v1/health")[0])


if __name__ == "__main__":
    unittest.main()
