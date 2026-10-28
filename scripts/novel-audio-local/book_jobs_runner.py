"""Background executor for whole-book jobs.

One thread owns the runtime lease while a job has work, analyzes each chapter
with the ported phone rules, synthesizes every piece and stores the audio. The
lease is released (and the model unloaded) whenever no job can make progress.
"""

import hashlib
import json
import secrets
import threading
import time

from scripts.novel_audio_server.errors import (
    InvalidBackendResponseError,
    NovelAudioError,
    ResourceCheckError,
    ResourceUnavailableError,
    ServiceBusyError,
)
from scripts.novel_audio_server.protocol import (
    MAX_AUDIO,
    analysis_request,
    analysis_response,
    synthesis_request,
)

from book_jobs_analysis import (
    AliasConflict,
    AnalysisError,
    CharacterTable,
    NoUnusedVoice,
    analyze_chapter,
)
from book_jobs_pronunciation import PronunciationError

DAY = 86400.0
MAX_CHAPTER_ATTEMPTS = 3


class _Interrupted(Exception):
    """The job was paused, cancelled or deleted while it was running."""


class BookJobRunner:
    def __init__(self, store, runtime, catalog, capabilities, pronunciation,
                 retention_days=30, clock=time.time, idle_seconds=5.0,
                 busy_backoff_seconds=60.0):
        self.store = store
        self.runtime = runtime
        self.catalog = catalog
        self.capabilities = capabilities
        self.pronunciation = pronunciation
        self.retention_days = retention_days
        self.clock = clock
        self.idle_seconds = idle_seconds
        self.busy_backoff_seconds = busy_backoff_seconds
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._lease_id = None
        self._attempts = {}
        self._last_purge = None
        self._thread = None

    # Lifecycle ------------------------------------------------------------

    def start(self):
        self._thread = threading.Thread(
            target=self._loop, name="novel-audio-book-jobs", daemon=True)
        self._thread.start()

    def wake(self):
        self._wake.set()

    def close(self, timeout=30.0):
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout)
        self._release()

    def busy(self):
        """True while a book job owns (or is about to own) the runtime."""
        return self._lease_id is not None or self.store.runnable_job() is not None

    def _loop(self):
        while not self._stop.is_set():
            try:
                worked = self.step()
            except Exception:
                # A host-level fault must not kill the executor; retry later.
                self._release()
                worked = False
                self._sleep(self.busy_backoff_seconds)
                continue
            if not worked:
                self._sleep(self.idle_seconds)

    def _sleep(self, seconds):
        self._wake.wait(seconds)
        self._wake.clear()

    # One unit of work -----------------------------------------------------

    def step(self):
        """Advance the oldest runnable job by one chapter. Returns False when idle."""
        self._purge_if_due()
        job = self.store.runnable_job()
        if job is None:
            self._release()
            return False
        chapter = self.store.next_chapter(job["job_id"])
        if chapter is None:
            if job["uploaded"]:
                self.store.set_job_state(job["job_id"], "done", self.clock())
                return True
            self._release()
            return False
        key = (job["job_id"], chapter["chapter_id"])
        try:
            self._ensure_lease(job)
            if chapter["state"] == "pending":
                self._analyze(job, chapter)
                chapter = self.store.chapter(*key)
            self._synthesize(job, chapter)
            self._attempts.pop(key, None)
        except _Interrupted:
            self._release()
        except (ResourceUnavailableError, ResourceCheckError, ServiceBusyError, PronunciationError):
            # Another program holds the GPU, a phone request is in flight, or the
            # dictionary file is broken; wait instead of failing every chapter.
            self._release()
            self._sleep(self.busy_backoff_seconds)
        except (AnalysisError, AliasConflict, NoUnusedVoice):
            self.store.fail_chapter(*key, "analysis_failed")
        except (NovelAudioError, ValueError, TypeError, KeyError, OSError) as error:
            if isinstance(error, NovelAudioError) and not isinstance(
                error, InvalidBackendResponseError
            ):
                # The worker or lease may be gone; retry with a fresh lease. A bad
                # model answer keeps the loaded model for the next attempt.
                self._release()
            attempts = self._attempts.get(key, 0) + 1
            self._attempts[key] = attempts
            if attempts >= MAX_CHAPTER_ATTEMPTS:
                self.store.fail_chapter(*key, getattr(error, "code", "generation_failed"))
                self._attempts.pop(key, None)
        return True

    # Runtime --------------------------------------------------------------

    def _ensure_lease(self, job):
        if self._lease_id is None:
            remaining = max(1, job["expected_chapters"])
            lease = self.runtime.acquire(
                "book-job-" + secrets.token_urlsafe(12), "book_job", remaining)
            self._lease_id = lease.lease_id

    def _release(self):
        lease_id, self._lease_id = self._lease_id, None
        if lease_id is None:
            return
        try:
            self.runtime.release(lease_id)
        except Exception:
            cancel = getattr(self.runtime, "cancel_generation", None)
            if callable(cancel):
                try:
                    cancel(lease_id)
                except Exception:
                    pass

    def _generate(self, action):
        backend = self.runtime.begin_generation(self._lease_id)
        try:
            return action(backend), getattr(backend, "profile", "local-runtime-v1")
        finally:
            self.runtime.end_generation(self._lease_id)

    def _check_running(self, job_id):
        job = self.store.job(job_id)
        if job is None or job["state"] not in ("uploading", "running") or self._stop.is_set():
            raise _Interrupted()

    # Chapter work ---------------------------------------------------------

    def _analyze(self, job, chapter):
        started = time.monotonic()
        stored = _loads(job["table_json"])
        table = CharacterTable.from_json(stored)
        units = [
            {"unitId": unit["unitId"], "text": unit["text"], "roleType": unit["roleType"]}
            for unit in chapter["units"]
        ]

        def analyze(request):
            self._check_running(job["job_id"])
            validated = analysis_request(request)
            raw, _ = self._generate(lambda backend: backend.analyze(validated))
            return analysis_response(raw, validated)

        def voices():
            return self.catalog.public_voices(self.capabilities())

        def match(used, constraints):
            return self.catalog.match({"traits": []}, used, constraints, self.capabilities())

        result = analyze_chapter(
            table, job["work_key"], chapter["chapter_id"], chapter["snapshot_hash"],
            units, analyze, voices, match,
        )
        speakers, bindings = result["speakers"], result["bindings"]
        saved = table.to_json()
        saved["phoneIds"] = stored.get("phoneIds", [])
        # A manual binding the phone sent during analysis wins over this chapter's choice.
        latest = _loads(self.store.job(job["job_id"])["table_json"])
        for key, value in latest.get("bindings", {}).items():
            if stored.get("bindings", {}).get(key) != value:
                saved["bindings"][key] = value
                bindings[int(key)] = value
        pieces = []
        for unit in chapter["units"]:
            voice = bindings[speakers[unit["unitId"]]]["voiceAssetId"]
            for index, text in enumerate(unit["pieces"]):
                pieces.append((unit["unitId"], index, text, voice))
        self.store.save_analysis(
            job["job_id"], chapter["chapter_id"], speakers, saved, pieces,
            time.monotonic() - started, self.clock(),
        )

    def _synthesize(self, job, chapter):
        started = time.monotonic()
        dictionary = self.pronunciation()
        revision = dictionary.digest_for(job["work_key"])
        for piece in self.store.pending_pieces(job["job_id"], chapter["chapter_id"]):
            self._check_running(job["job_id"])
            request = synthesis_request({
                "text": dictionary.apply(piece["text"], job["work_key"]),
                "voiceAssetId": piece["voice_asset_id"],
                "language": "zh-CN",
                "speed": 1.0,
            })
            audio, profile = self._generate(lambda backend: backend.synthesize(request))
            if not isinstance(audio, bytes) or not 0 < len(audio) <= MAX_AUDIO:
                raise ValueError("invalid audio")
            audio_id = secrets.token_hex(16)
            path = self.store.piece_audio_path(job["job_id"], audio_id)
            path.parent.mkdir(parents=True, exist_ok=True)
            partial = path.with_suffix(".partial")
            partial.write_bytes(audio)
            partial.replace(path)
            self.store.save_piece(
                job["job_id"], chapter["chapter_id"], piece["unit_id"], piece["piece_index"],
                audio_id, hashlib.sha256(audio).hexdigest(), len(audio), "audio/ogg",
                profile, revision,
            )
        self.store.finish_chapter(
            job["job_id"], chapter["chapter_id"], time.monotonic() - started, None, self.clock())

    # Retention ------------------------------------------------------------

    def _purge_if_due(self):
        now = self.clock()
        if self._last_purge is not None and now - self._last_purge < DAY:
            return
        self._last_purge = now
        self.store.purge_expired(now, self.retention_days)


def _loads(value):
    return json.loads(value)
