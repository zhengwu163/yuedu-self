"""HTTP handlers for /v1/book-jobs (whole-book background generation).

NovelAudioApi authenticates first and then delegates every /v1/book-jobs path
here. Responses use the same (status, headers, body) shape and fixed error
codes; chapter text and model output never appear in errors or logs.
"""

import hashlib
import json
import re
import secrets

from scripts.novel_audio_server.protocol import array, record, require, string

from book_jobs_analysis import CharacterTable, normalize_gender

PREFIX = "/v1/book-jobs"
MAX_ID = 512
MAX_UNITS_PER_CHAPTER = 4000
MAX_PIECES_PER_UNIT = 256
MAX_PIECE_TEXT = 1200
MAX_UNIT_TEXT = 4000
_JSON = {"Content-Type": "application/json"}


def _error(status, code):
    return status, dict(_JSON), {"error": {"code": code}}


def _ok(body):
    return 200, dict(_JSON), body


class BookJobsApi:
    def __init__(self, store, runner, max_segment_chars, clock):
        self.store = store
        self.runner = runner
        self.max_segment_chars = max_segment_chars
        self.clock = clock

    def handles(self, path):
        return path == PREFIX or path.startswith(PREFIX + "/")

    def busy(self):
        return self.runner.busy()

    def close(self):
        self.runner.close()
        self.store.close()

    def respond(self, method, path, body):
        parts = path[len(PREFIX):].strip("/").split("/") if path != PREFIX else []
        try:
            if not parts:
                if method == "POST":
                    return self._create(body)
                if method == "GET":
                    return _ok({"jobs": [self._summary(job) for job in self.store.jobs()]})
                return _error(404, "not_found")
            job = self.store.job(parts[0])
            if job is None:
                return _error(404, "unknown_job")
            return self._job_route(method, job, parts[1:], body)
        except (ValueError, TypeError, KeyError, AttributeError):
            return _error(400, "invalid_request")

    def _job_route(self, method, job, rest, body):
        job_id = job["job_id"]
        if not rest:
            if method == "GET":
                return _ok(self._detail(job))
            if method == "DELETE":
                self.store.delete_job(job_id)
                self.runner.wake()
                return _ok({"state": "deleted"})
            return _error(404, "not_found")
        if len(rest) == 1 and method == "POST" and rest[0] in ("uploaded", "pause", "resume", "cancel"):
            return self._control(job, rest[0])
        if len(rest) == 1 and rest[0] == "voice-bindings" and method == "PUT":
            return self._voice_bindings(job, body)
        if len(rest) == 2 and rest[0] == "chapters":
            chapter_id = string(rest[1], MAX_ID)
            if method == "PUT":
                return self._put_chapter(job, chapter_id, body)
            if method == "GET":
                return self._chapter_result(job, chapter_id)
        if len(rest) == 3 and rest[0] == "chapters" and rest[2] == "downloaded" and method == "POST":
            if not self.store.mark_downloaded(job_id, string(rest[1], MAX_ID), self.clock()):
                return _error(409, "chapter_not_ready")
            return _ok({"state": "downloaded"})
        if len(rest) == 2 and rest[0] == "audio" and method == "GET":
            return self._audio(job, rest[1])
        return _error(404, "not_found")

    # Handlers -------------------------------------------------------------

    def _create(self, body):
        record(body)
        work_key = string(body.get("bookId"), MAX_ID)
        title = string(body.get("title"), 256)
        expected = body.get("expectedChapterCount")
        require(type(expected) is int and 1 <= expected <= 10000)
        characters, aliases, merges, bindings = [], [], {}, {}
        for item in array(body.get("characters"), 10000):
            record(item)
            characters.append({
                "id": _long(item.get("characterId")),
                "name": string(item.get("displayName")),
                "gender": normalize_gender(item.get("gender") if isinstance(item.get("gender"), str) else ""),
            })
        require(len({item["id"] for item in characters}) == len(characters))
        require(all(item["id"] > 0 for item in characters))
        for item in array(body.get("aliases"), 100000):
            record(item)
            aliases.append({
                "characterId": _long(item.get("characterId")),
                "alias": string(item.get("alias")),
                "normalizedAlias": string(item.get("normalizedAlias")),
            })
        for item in array(body.get("merges"), 10000):
            record(item)
            merges[_long(item.get("absorbedCharacterId"))] = _long(item.get("primaryCharacterId"))
        for item in array(body.get("bindings"), 10000):
            record(item)
            revision = item.get("revision")
            require(type(revision) is int and revision >= 0)
            bindings[_long(item.get("characterId"))] = {
                "voiceAssetId": string(item.get("voiceAssetId")),
                "revision": revision,
            }
        table = CharacterTable(characters, aliases, merges, bindings).to_json()
        table["phoneIds"] = sorted(item["id"] for item in characters)
        job_id = "job-" + secrets.token_hex(12)
        self.store.create_job(
            job_id, work_key, title, expected, self.max_segment_chars, table, self.clock())
        self.runner.wake()
        return _ok({"jobId": job_id, "maxSegmentChars": self.max_segment_chars,
                    "state": "uploading"})

    def _put_chapter(self, job, chapter_id, body):
        if job["state"] in ("done", "cancelled"):
            return _error(409, "job_closed")
        record(body)
        index = body.get("chapterIndex")
        require(type(index) is int and 0 <= index <= 100000)
        snapshot_hash = string(body.get("snapshotHash"), 256)
        units, seen = [], set()
        for item in array(body.get("units"), MAX_UNITS_PER_CHAPTER):
            record(item)
            unit_id = string(item.get("unitId"), MAX_ID)
            require(unit_id not in seen)
            seen.add(unit_id)
            text = item.get("text")
            require(isinstance(text, str) and len(text) <= MAX_UNIT_TEXT)
            pieces = array(item.get("pieces"), MAX_PIECES_PER_UNIT)
            for piece in pieces:
                require(isinstance(piece, str) and len(piece) <= MAX_PIECE_TEXT)
            units.append({
                "unitId": unit_id,
                "text": text,
                "roleType": string(item.get("roleType"), 32),
                "pieces": list(pieces),
            })
        require(units)
        changed = self.store.put_chapter(job["job_id"], chapter_id, index, snapshot_hash, units)
        if job["state"] == "done":
            self.store.set_job_state(job["job_id"], "running", self.clock())
        self.runner.wake()
        return _ok({"state": "stored" if changed else "unchanged"})

    def _control(self, job, action):
        job_id, now, state = job["job_id"], self.clock(), job["state"]
        if action == "uploaded":
            self.store.mark_uploaded(job_id, now)
        elif action == "pause":
            if state in ("uploading", "running"):
                self.store.set_job_state(job_id, "paused", now)
        elif action == "resume":
            if state in ("paused", "done"):
                self.store.retry_failed(job_id)
                self.store.set_job_state(job_id, "running" if job["uploaded"] else "uploading", now)
        elif action == "cancel":
            if state != "cancelled":
                self.store.set_job_state(job_id, "cancelled", now)
        self.runner.wake()
        return _ok({"state": self.store.job(job_id)["state"]})

    def _voice_bindings(self, job, body):
        record(body)
        table = _load_table(job)
        for item in array(body.get("bindings"), 10000):
            record(item)
            revision = item.get("revision")
            require(type(revision) is int and revision >= 0)
            table["bindings"][str(_long(item.get("characterId")))] = {
                "voiceAssetId": string(item.get("voiceAssetId")),
                "revision": revision,
            }
        self.store.save_table(job["job_id"], table, self.clock())
        return _ok({"state": "stored"})

    def _summary(self, job):
        return {"jobId": job["job_id"], "bookId": job["work_key"], "title": job["title"],
                "state": job["state"]}

    def _detail(self, job):
        chapters = self.store.chapters(job["job_id"])
        finished = [item for item in chapters if item["synthesis_seconds"] is not None]
        recent = finished[-5:]
        return {
            **self._summary(job),
            "uploaded": bool(job["uploaded"]),
            "expectedChapterCount": job["expected_chapters"],
            "maxSegmentChars": job["max_segment_chars"],
            "counts": {
                state: sum(1 for item in chapters if item["state"] == state)
                for state in ("pending", "analyzed", "done", "failed", "purged")
            },
            "recentChapterSeconds": (
                sum(item["analysis_seconds"] + item["synthesis_seconds"] for item in recent)
                / len(recent) if recent else None
            ),
            "chapters": [
                {
                    "chapterId": item["chapter_id"],
                    "chapterIndex": item["chapter_index"],
                    "snapshotHash": item["snapshot_hash"],
                    "state": item["state"],
                    "failure": item["failure"],
                    "downloaded": item["downloaded_at"] is not None,
                }
                for item in chapters
            ],
        }

    def _chapter_result(self, job, chapter_id):
        chapter = self.store.chapter(job["job_id"], chapter_id)
        if chapter is None:
            return _error(404, "unknown_chapter")
        if chapter["state"] != "done":
            return _error(409, "chapter_not_ready")
        table = _load_table(job)
        phone_ids = set(table.get("phoneIds", ()))
        pieces = {}
        for piece in self.store.pieces(job["job_id"], chapter_id):
            pieces.setdefault(piece["unit_id"], []).append(piece)
        units, referenced = [], set()
        for unit in chapter["units"]:
            speaker = chapter["speakers"][unit["unitId"]]
            if speaker:
                referenced.add(speaker)
            units.append({
                "unitId": unit["unitId"],
                "speakerId": str(speaker),
                "pieces": [
                    {
                        "index": piece["piece_index"],
                        "textSha256": _sha256(piece["text"]),
                        "audioId": piece["audio_id"],
                        "sha256": piece["sha256"],
                        "size": piece["size"],
                        "contentType": piece["content_type"],
                        "ttsProfile": piece["tts_profile"],
                    }
                    for piece in sorted(pieces.get(unit["unitId"], ()), key=lambda p: p["piece_index"])
                ],
            })
        characters = [
            {
                "characterId": str(item["id"]),
                "displayName": item["name"],
                "gender": item["gender"],
                "phoneKnown": item["id"] in phone_ids,
                "stableAliases": [
                    alias["alias"] for alias in table["aliases"]
                    if alias["characterId"] == item["id"]
                ],
            }
            for item in table["characters"] if item["id"] in referenced
        ]
        bindings = [
            {"characterId": key, "voiceAssetId": value["voiceAssetId"],
             "revision": value["revision"]}
            for key, value in table["bindings"].items()
            if int(key) in referenced or int(key) == 0
        ]
        return _ok({
            "chapterId": chapter_id,
            "chapterIndex": chapter["chapter_index"],
            "snapshotHash": chapter["snapshot_hash"],
            "units": units,
            "characters": characters,
            "bindings": bindings,
        })

    def _audio(self, job, audio_id):
        if not re.fullmatch(r"[0-9a-f]{32}", audio_id or ""):
            return _error(404, "unknown_audio")
        piece = self.store.audio(job["job_id"], audio_id)
        if piece is None:
            return _error(404, "unknown_audio")
        path = self.store.piece_audio_path(job["job_id"], audio_id)
        try:
            audio = path.read_bytes()
        except OSError:
            return _error(404, "unknown_audio")
        return 200, {"Content-Type": piece["content_type"],
                     "X-TTS-Profile": piece["tts_profile"]}, audio


def _long(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{1,18}", value))
    return int(value)


def _sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _load_table(job):
    return json.loads(job["table_json"])
