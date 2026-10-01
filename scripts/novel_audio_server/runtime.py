"""Bounded model-worker lifecycle and one active runtime lease."""

import secrets
import threading
import time
from dataclasses import dataclass
from enum import Enum

from .errors import (
    LeaseError,
    LeaseExpiredError,
    ServiceBusyError,
    WorkerStartError,
    WorkerStopError,
)


class RuntimeState(str, Enum):
    IDLE = "idle"
    STARTING = "starting"
    READY = "ready"
    GENERATING = "generating"
    UNLOADING = "unloading"
    FAILED = "failed"


@dataclass(frozen=True)
class Lease:
    lease_id: str
    session_id: str
    purpose: str
    expected_chapter_count: int
    expires_at: float


@dataclass(frozen=True)
class RuntimeStatus:
    state: RuntimeState
    active_lease_count: int


class RuntimeManager:
    def __init__(self, worker_factory, lease_ttl=300.0, clock=None):
        if type(lease_ttl) not in (int, float) or lease_ttl <= 0:
            raise ValueError("invalid lease ttl")
        self._worker_factory = worker_factory
        self._lease_ttl = float(lease_ttl)
        self._clock = clock or time.monotonic
        self._lock = threading.RLock()
        self._state = RuntimeState.IDLE
        self._lease = None
        self._worker = None
        self._closed = False

    def acquire(self, session_id, purpose, expected_chapter_count):
        if not isinstance(session_id, str) or not session_id.strip():
            raise ValueError("invalid session")
        if not isinstance(purpose, str) or not purpose.strip():
            raise ValueError("invalid purpose")
        if type(expected_chapter_count) is not int or expected_chapter_count <= 0:
            raise ValueError("invalid chapter count")

        with self._lock:
            self._expire_locked()
            if self._closed:
                raise WorkerStartError()
            if self._lease is not None:
                raise ServiceBusyError()

            self._state = RuntimeState.STARTING
            try:
                worker = self._worker_factory.start("default")
            except Exception:
                self._state = RuntimeState.FAILED
                self._state = RuntimeState.IDLE
                raise WorkerStartError() from None

            lease = Lease(
                lease_id=secrets.token_urlsafe(24),
                session_id=session_id,
                purpose=purpose,
                expected_chapter_count=expected_chapter_count,
                expires_at=self._clock() + self._lease_ttl,
            )
            self._worker = worker
            self._lease = lease
            self._state = RuntimeState.READY
            return lease

    def validate(self, lease_id):
        with self._lock:
            self._expire_locked(raise_error=True)
            if self._lease is None or not secrets.compare_digest(
                self._lease.lease_id, str(lease_id)
            ):
                raise LeaseError()
            self._state = RuntimeState.GENERATING
            return self._worker

    def release(self, lease_id):
        with self._lock:
            self._expire_locked(raise_error=True)
            if self._lease is None or not secrets.compare_digest(
                self._lease.lease_id, str(lease_id)
            ):
                raise LeaseError()
            self._close_worker_locked()

    def status(self):
        with self._lock:
            self._expire_locked()
            return RuntimeStatus(
                state=self._state,
                active_lease_count=1 if self._lease is not None else 0,
            )

    def close(self):
        with self._lock:
            self._closed = True
            if self._lease is not None or self._worker is not None:
                self._close_worker_locked()
            else:
                self._state = RuntimeState.IDLE

    def _expire_locked(self, raise_error=False):
        if self._lease is not None and self._clock() >= self._lease.expires_at:
            self._close_worker_locked()
            if raise_error:
                raise LeaseExpiredError()

    def _close_worker_locked(self):
        worker = self._worker
        self._state = RuntimeState.UNLOADING
        self._worker = None
        self._lease = None
        try:
            if worker is not None:
                worker.close()
        except Exception:
            raise WorkerStopError() from None
        finally:
            self._state = RuntimeState.IDLE
