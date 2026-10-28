"""Persistent store for whole-book jobs (SQLite + audio files).

Everything a job needs to resume after a host restart lives here: the uploaded
units, the evolving character table, per-piece audio and download times.
Chapter text never leaves the service root and is erased by retention purge.
"""

import json
import shutil
import sqlite3
import threading
from pathlib import Path

JOB_STATES = ("uploading", "running", "paused", "done", "cancelled")
CHAPTER_STATES = ("pending", "analyzed", "done", "failed", "purged")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
  job_id TEXT PRIMARY KEY, work_key TEXT NOT NULL, title TEXT NOT NULL,
  state TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0,
  expected_chapters INTEGER NOT NULL, max_segment_chars INTEGER NOT NULL,
  table_json TEXT NOT NULL, next_position INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL, updated_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS chapters (
  job_id TEXT NOT NULL, chapter_id TEXT NOT NULL, chapter_index INTEGER NOT NULL,
  position INTEGER NOT NULL, snapshot_hash TEXT NOT NULL, state TEXT NOT NULL,
  failure TEXT, units_json TEXT NOT NULL, speakers_json TEXT,
  analysis_seconds REAL, synthesis_seconds REAL, audio_seconds REAL,
  completed_at REAL, downloaded_at REAL,
  PRIMARY KEY (job_id, chapter_id));
CREATE TABLE IF NOT EXISTS pieces (
  job_id TEXT NOT NULL, chapter_id TEXT NOT NULL, unit_id TEXT NOT NULL,
  piece_index INTEGER NOT NULL, text TEXT NOT NULL, voice_asset_id TEXT NOT NULL,
  audio_id TEXT, path TEXT, sha256 TEXT, size INTEGER, content_type TEXT,
  tts_profile TEXT, pronunciation TEXT,
  PRIMARY KEY (job_id, chapter_id, unit_id, piece_index));
"""


class BookJobStore:
    def __init__(self, database, audio_root):
        self.audio_root = Path(audio_root)
        self.audio_root.mkdir(parents=True, exist_ok=True)
        Path(database).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(database), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock, self._db:
            self._db.executescript(_SCHEMA)

    def close(self):
        with self._lock:
            self._db.close()

    # Jobs -----------------------------------------------------------------

    def create_job(self, job_id, work_key, title, expected, max_segment_chars, table, now):
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO jobs (job_id, work_key, title, state, expected_chapters,"
                " max_segment_chars, table_json, created_at, updated_at)"
                " VALUES (?, ?, ?, 'uploading', ?, ?, ?, ?, ?)",
                (job_id, work_key, title, expected, max_segment_chars,
                 json.dumps(table, ensure_ascii=False), now, now),
            )

    def job(self, job_id):
        with self._lock:
            row = self._db.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
            return dict(row) if row else None

    def jobs(self):
        with self._lock:
            return [dict(row) for row in self._db.execute(
                "SELECT * FROM jobs ORDER BY created_at, job_id")]

    def set_job_state(self, job_id, state, now):
        assert state in JOB_STATES
        with self._lock, self._db:
            self._db.execute(
                "UPDATE jobs SET state = ?, updated_at = ? WHERE job_id = ?",
                (state, now, job_id))

    def mark_uploaded(self, job_id, now):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE jobs SET uploaded = 1, state = CASE WHEN state = 'uploading'"
                " THEN 'running' ELSE state END, updated_at = ? WHERE job_id = ?",
                (now, job_id))

    def save_table(self, job_id, table, now):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE jobs SET table_json = ?, updated_at = ? WHERE job_id = ?",
                (json.dumps(table, ensure_ascii=False), now, job_id))

    def runnable_job(self):
        """Oldest job that may run now; uploading jobs run as chapters arrive."""
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM jobs WHERE state IN ('uploading', 'running')"
                " ORDER BY created_at, job_id LIMIT 1").fetchone()
            return dict(row) if row else None

    def delete_job(self, job_id):
        with self._lock, self._db:
            for table in ("pieces", "chapters", "jobs"):
                self._db.execute(f"DELETE FROM {table} WHERE job_id = ?", (job_id,))
        shutil.rmtree(self.audio_root / job_id, ignore_errors=True)

    # Chapters -------------------------------------------------------------

    def put_chapter(self, job_id, chapter_id, chapter_index, snapshot_hash, units):
        """Store one uploaded chapter; same hash is a no-op, a new hash resets it."""
        with self._lock, self._db:
            current = self._db.execute(
                "SELECT snapshot_hash, position FROM chapters WHERE job_id = ? AND chapter_id = ?",
                (job_id, chapter_id)).fetchone()
            if current is not None and current["snapshot_hash"] == snapshot_hash:
                return False
            if current is not None:
                # A re-uploaded chapter keeps its place in the generation order.
                position = current["position"]
                self._remove_chapter_audio(job_id, chapter_id)
                for table in ("pieces", "chapters"):
                    self._db.execute(
                        f"DELETE FROM {table} WHERE job_id = ? AND chapter_id = ?",
                        (job_id, chapter_id))
            else:
                position = self._db.execute(
                    "SELECT next_position FROM jobs WHERE job_id = ?", (job_id,)).fetchone()[0]
                self._db.execute(
                    "UPDATE jobs SET next_position = next_position + 1 WHERE job_id = ?",
                    (job_id,))
            self._db.execute(
                "INSERT INTO chapters (job_id, chapter_id, chapter_index, position,"
                " snapshot_hash, state, units_json) VALUES (?, ?, ?, ?, ?, 'pending', ?)",
                (job_id, chapter_id, chapter_index, position, snapshot_hash,
                 json.dumps(units, ensure_ascii=False)))
            return True

    def chapters(self, job_id):
        with self._lock:
            return [self._chapter(row) for row in self._db.execute(
                "SELECT * FROM chapters WHERE job_id = ? ORDER BY position", (job_id,))]

    def chapter(self, job_id, chapter_id):
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM chapters WHERE job_id = ? AND chapter_id = ?",
                (job_id, chapter_id)).fetchone()
            return self._chapter(row) if row else None

    def next_chapter(self, job_id):
        with self._lock:
            row = self._db.execute(
                "SELECT * FROM chapters WHERE job_id = ? AND state IN ('pending', 'analyzed')"
                " ORDER BY position LIMIT 1", (job_id,)).fetchone()
            return self._chapter(row) if row else None

    @staticmethod
    def _chapter(row):
        value = dict(row)
        value["units"] = json.loads(value.pop("units_json"))
        speakers = value.pop("speakers_json")
        value["speakers"] = json.loads(speakers) if speakers else None
        return value

    def save_analysis(self, job_id, chapter_id, speakers, table, pieces, seconds, now):
        """Commit speakers, the grown character table and the piece plan together."""
        with self._lock, self._db:
            self._db.execute(
                "UPDATE chapters SET state = 'analyzed', speakers_json = ?, analysis_seconds = ?"
                " WHERE job_id = ? AND chapter_id = ? AND state = 'pending'",
                (json.dumps(speakers, ensure_ascii=False), seconds, job_id, chapter_id))
            self._db.execute(
                "UPDATE jobs SET table_json = ?, updated_at = ? WHERE job_id = ?",
                (json.dumps(table, ensure_ascii=False), now, job_id))
            self._db.execute(
                "DELETE FROM pieces WHERE job_id = ? AND chapter_id = ?", (job_id, chapter_id))
            self._db.executemany(
                "INSERT INTO pieces (job_id, chapter_id, unit_id, piece_index, text,"
                " voice_asset_id) VALUES (?, ?, ?, ?, ?, ?)",
                [(job_id, chapter_id, unit_id, index, text, voice)
                 for unit_id, index, text, voice in pieces])

    def pending_pieces(self, job_id, chapter_id):
        with self._lock:
            return [dict(row) for row in self._db.execute(
                "SELECT * FROM pieces WHERE job_id = ? AND chapter_id = ? AND audio_id IS NULL"
                " ORDER BY rowid", (job_id, chapter_id))]

    def pieces(self, job_id, chapter_id):
        with self._lock:
            return [dict(row) for row in self._db.execute(
                "SELECT * FROM pieces WHERE job_id = ? AND chapter_id = ? ORDER BY rowid",
                (job_id, chapter_id))]

    def piece_audio_path(self, job_id, audio_id):
        return self.audio_root / job_id / f"{audio_id}.ogg"

    def save_piece(self, job_id, chapter_id, unit_id, piece_index, audio_id, sha256,
                   size, content_type, tts_profile, pronunciation):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE pieces SET audio_id = ?, path = ?, sha256 = ?, size = ?,"
                " content_type = ?, tts_profile = ?, pronunciation = ?"
                " WHERE job_id = ? AND chapter_id = ? AND unit_id = ? AND piece_index = ?",
                (audio_id, f"{job_id}/{audio_id}.ogg", sha256, size, content_type,
                 tts_profile, pronunciation, job_id, chapter_id, unit_id, piece_index))

    def finish_chapter(self, job_id, chapter_id, synthesis_seconds, audio_seconds, now):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE chapters SET state = 'done', synthesis_seconds = ?, audio_seconds = ?,"
                " completed_at = ? WHERE job_id = ? AND chapter_id = ?",
                (synthesis_seconds, audio_seconds, now, job_id, chapter_id))

    def fail_chapter(self, job_id, chapter_id, failure):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE chapters SET state = 'failed', failure = ?"
                " WHERE job_id = ? AND chapter_id = ?", (failure, job_id, chapter_id))

    def retry_failed(self, job_id):
        with self._lock, self._db:
            self._db.execute(
                "UPDATE chapters SET state = CASE WHEN speakers_json IS NULL THEN 'pending'"
                " ELSE 'analyzed' END, failure = NULL WHERE job_id = ? AND state = 'failed'",
                (job_id,))

    def audio(self, job_id, audio_id):
        with self._lock:
            row = self._db.execute(
                "SELECT p.* FROM pieces p JOIN chapters c ON c.job_id = p.job_id"
                " AND c.chapter_id = p.chapter_id WHERE p.job_id = ? AND p.audio_id = ?"
                " AND c.state = 'done'", (job_id, audio_id)).fetchone()
            return dict(row) if row else None

    def mark_downloaded(self, job_id, chapter_id, now):
        with self._lock, self._db:
            cursor = self._db.execute(
                "UPDATE chapters SET downloaded_at = COALESCE(downloaded_at, ?)"
                " WHERE job_id = ? AND chapter_id = ? AND state = 'done'",
                (now, job_id, chapter_id))
            return cursor.rowcount == 1

    def purge_expired(self, now, retention_days):
        """Delete audio and text of chapters downloaded more than N days ago."""
        cutoff = now - retention_days * 86400
        with self._lock, self._db:
            rows = self._db.execute(
                "SELECT job_id, chapter_id FROM chapters WHERE state = 'done'"
                " AND downloaded_at IS NOT NULL AND downloaded_at <= ?", (cutoff,)).fetchall()
            for row in rows:
                self._remove_chapter_audio(row["job_id"], row["chapter_id"])
                self._db.execute(
                    "UPDATE pieces SET text = '', audio_id = NULL, path = NULL"
                    " WHERE job_id = ? AND chapter_id = ?", (row["job_id"], row["chapter_id"]))
                self._db.execute(
                    "UPDATE chapters SET state = 'purged', units_json = '[]'"
                    " WHERE job_id = ? AND chapter_id = ?", (row["job_id"], row["chapter_id"]))
            return len(rows)

    def _remove_chapter_audio(self, job_id, chapter_id):
        for row in self._db.execute(
            "SELECT audio_id FROM pieces WHERE job_id = ? AND chapter_id = ?"
            " AND audio_id IS NOT NULL", (job_id, chapter_id)).fetchall():
            self.piece_audio_path(job_id, row["audio_id"]).unlink(missing_ok=True)
