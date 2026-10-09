"""Bounded model-worker lifecycle and one active runtime lease."""

import math
import queue
import secrets
import threading
import time
from dataclasses import dataclass
from enum import Enum

from .errors import (
    LeaseError,
    LeaseExpiredError,
    ServiceBusyError,
    RequestCancelledError,
    WorkerUnavailableError,
    WorkerStartError,
    WorkerStopError,
    ResourceUnavailableError,
    ResourceCheckError,
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
    active_request_count: int = 0
    worker_profile: str | None = None


class _Startup:
    def __init__(
        self,
        profile_id,
        session_id,
        purpose,
        expected_chapter_count,
    ):
        self.profile_id = profile_id
        self.session_id = session_id
        self.purpose = purpose
        self.expected_chapter_count = expected_chapter_count
        self.done = threading.Event()
        self.worker = None
        self.error = None
        self.cancelled = False
        self.lease = None


class _Cleanup:
    def __init__(self, worker):
        self.worker = worker
        self.done = threading.Event()
        self.error = False


class RuntimeManager:
    def __init__(
        self,
        worker_factory,
        lease_ttl=300.0,
        clock=None,
        profile_id="default",
        startup_timeout=30.0,
        cleanup_timeout=10.0,
    ):
        for value, name in (
            (lease_ttl, "lease ttl"),
            (startup_timeout, "startup timeout"),
            (cleanup_timeout, "cleanup timeout"),
        ):
            if (
                type(value) not in (int, float)
                or not math.isfinite(float(value))
                or value <= 0
            ):
                raise ValueError(f"invalid {name}")
        if not isinstance(profile_id, str) or not profile_id.strip():
            raise ValueError("invalid profile")

        self._worker_factory = worker_factory
        self._lease_ttl = float(lease_ttl)
        self._startup_timeout = float(startup_timeout)
        self._cleanup_timeout = float(cleanup_timeout)
        self._profile_id = profile_id
        self._clock = clock or time.monotonic
        self._lock = threading.RLock()
        self._state = RuntimeState.IDLE
        self._lease = None
        self._worker = None
        self._startup = None
        self._cleanup = None
        self._active_requests = 0
        self._expired_lease_ids = set()
        self._closed = False
        self._stop_event = threading.Event()
        self._watchdog = threading.Thread(
            target=self._watch_expiry,
            name="novel-audio-runtime-watchdog",
            daemon=True,
        )
        self._watchdog.start()

    def acquire_with_context(self, session_id, purpose, expected_chapter_count, context):
        return self.acquire(session_id, purpose, expected_chapter_count, context)

    def acquire(self, session_id, purpose, expected_chapter_count, context=None):
        self._validate_acquire_args(
            session_id, purpose, expected_chapter_count
        )
        self._expire_if_needed()

        with self._lock:
            if context is not None:
                context.check()
            if self._closed:
                raise WorkerStartError()
            if (
                self._startup is not None
                or self._lease is not None
                or self._worker is not None
                or self._state in (
                    RuntimeState.STARTING,
                    RuntimeState.UNLOADING,
                    RuntimeState.FAILED,
                )
            ):
                raise ServiceBusyError()
            startup = _Startup(
                self._profile_id,
                session_id,
                purpose,
                expected_chapter_count,
            )
            self._startup = startup
            self._state = RuntimeState.STARTING

        threading.Thread(
            target=self._start_worker,
            args=(startup,),
            name="novel-audio-runtime-start",
            daemon=True,
        ).start()

        deadline = time.monotonic() + self._startup_timeout
        while not startup.done.wait(min(0.05, max(0, deadline - time.monotonic()))):
            if context is not None and context.cancelled:
                self._cancel_startup(startup)
                raise RequestCancelledError()
            if time.monotonic() < deadline:
                continue
            with self._lock:
                if self._startup is startup and not startup.done.is_set():
                    startup.cancelled = True
                    self._state = RuntimeState.UNLOADING
                    raise WorkerStartError()
            break
        if context is not None and context.cancelled:
            self._cancel_startup(startup)
            raise RequestCancelledError()
        if startup.error is not None:
            if isinstance(startup.error, (ResourceUnavailableError, ResourceCheckError)):
                raise startup.error from None
            raise WorkerStartError() from None

        with self._lock:
            if (
                self._startup is not None
                or self._closed
                or self._worker is None
                or self._lease is None
            ):
                raise WorkerStartError()
            return self._lease

    def _cancel_startup(self, startup):
        with self._lock:
            startup.cancelled = True
            if self._startup is startup:
                self._state = RuntimeState.UNLOADING
            lease = startup.lease
        if lease is not None:
            self.cancel_generation(lease.lease_id)

    def begin_generation(self, lease_id):
        self._expire_if_needed()
        with self._lock:
            self._raise_for_lease_locked(lease_id)
            if self._closed or self._state in (RuntimeState.UNLOADING, RuntimeState.FAILED):
                raise WorkerUnavailableError()
            if self._worker is None:
                raise LeaseError()
            if self._active_requests:
                raise ServiceBusyError()
            self._active_requests = 1
            self._state = RuntimeState.GENERATING
            return self._worker

    def validate(self, lease_id):
        """Compatibility alias for callers that predate request accounting."""
        return self.begin_generation(lease_id)

    def end_generation(self, lease_id):
        with self._lock:
            self._raise_for_lease_locked(lease_id)
            if self._state in (RuntimeState.UNLOADING, RuntimeState.FAILED):
                raise WorkerUnavailableError()
            if self._active_requests != 1:
                raise LeaseError()
            self._active_requests = 0
            self._state = RuntimeState.READY

    def release(self, lease_id):
        self._expire_if_needed()
        with self._lock:
            self._raise_for_lease_locked(lease_id)
            if self._active_requests:
                raise ServiceBusyError()
            worker = self._worker
            lease = self._lease
            self._state = RuntimeState.UNLOADING

        self._cleanup_worker(worker, lease)

    def invalidate(self, lease_id):
        """Retry cleanup for a failed Worker without creating a second Worker."""
        with self._lock:
            self._raise_for_lease_locked(lease_id)
            if self._active_requests:
                raise ServiceBusyError()
            worker = self._worker
            lease = self._lease
            self._state = RuntimeState.UNLOADING
        self._cleanup_worker(worker, lease)

    def cancel_generation(self, lease_id):
        """Revoke only this lease, even when cancellation follows publication."""
        with self._lock:
            if self._lease is None or not secrets.compare_digest(
                self._lease.lease_id, str(lease_id)
            ):
                return
            worker, lease = self._worker, self._lease
            self._lease = None
            self._active_requests = 0
            self._state = RuntimeState.UNLOADING
        try:
            self._cleanup_worker(worker, lease)
        except WorkerStopError:
            raise WorkerUnavailableError() from None

    def status(self):
        self._expire_if_needed()
        with self._lock:
            return RuntimeStatus(
                state=self._state,
                active_lease_count=1 if self._lease is not None else 0,
                active_request_count=self._active_requests,
                worker_profile=(
                    getattr(self._worker, "profile", "")
                    if self._worker is not None else None
                ),
            )

    def close(self):
        with self._lock:
            self._closed = True
            self._stop_event.set()
            startup = self._startup
            worker = self._worker
            lease = self._lease
            if startup is not None:
                startup.cancelled = True
                startup.error = WorkerStartError()
                startup.done.set()
                self._state = RuntimeState.UNLOADING
            elif worker is not None:
                self._state = RuntimeState.UNLOADING
            else:
                self._state = RuntimeState.IDLE

        if startup is not None:
            return
        if worker is not None:
            self._cleanup_worker(worker, lease)

    def _start_worker(self, startup):
        try:
            worker = self._worker_factory.start(startup.profile_id)
        except BaseException as error:
            # Startup may fail after spawning a process whose cleanup could not
            # be verified. Treat it as an orphan, never as an empty runtime.
            worker = getattr(error, "worker", None) if isinstance(error, WorkerStartError) else None
            self._finish_startup(startup, worker, error)
            return
        self._finish_startup(startup, worker, None)

    def _finish_startup(self, startup, worker, error):
        orphan = None
        with self._lock:
            valid = (
                self._startup is startup
                and not self._closed
                and not startup.cancelled
                and self._state == RuntimeState.STARTING
            )
            if valid and error is None:
                self._worker = worker
                self._lease = Lease(
                    lease_id=secrets.token_urlsafe(24),
                    session_id=startup.session_id,
                    purpose=startup.purpose,
                    expected_chapter_count=startup.expected_chapter_count,
                    expires_at=self._clock() + self._lease_ttl,
                )
                startup.lease = self._lease
                self._startup = None
                self._state = RuntimeState.READY
            else:
                if worker is not None:
                    orphan = worker
                    # Publish cleanup ownership before dropping startup. Close
                    # and cancellation must see the same late worker.
                    self._worker = worker
                    self._state = RuntimeState.UNLOADING
                elif self._state in (
                    RuntimeState.STARTING,
                    RuntimeState.UNLOADING,
                ):
                    self._state = RuntimeState.IDLE
                self._startup = None
            startup.worker = worker
            startup.error = error or (
                RuntimeError("worker start cancelled") if not valid else None
            )
            startup.done.set()

        if orphan is not None:
            try:
                self._cleanup_worker(orphan, None)
            except WorkerStopError:
                pass

    def _cleanup_worker(self, worker, lease):
        # A timeout does not transfer ownership: all concurrent cleanup callers
        # join this record, and only its completion may admit another worker.
        with self._lock:
            if worker is None or self._worker is not worker:
                return
            cleanup = self._cleanup
            if cleanup is None or (cleanup.done.is_set() and cleanup.error):
                cleanup = _Cleanup(worker)
                self._cleanup = cleanup
                self._state = RuntimeState.UNLOADING
                threading.Thread(
                    target=self._run_cleanup, args=(cleanup,), daemon=True,
                    name="novel-audio-runtime-cleanup",
                ).start()
        if not cleanup.done.wait(self._cleanup_timeout):
            with self._lock:
                if self._cleanup is cleanup and not cleanup.done.is_set():
                    self._state = RuntimeState.FAILED
            raise WorkerStopError()
        if cleanup.error:
            raise WorkerStopError()

    def _run_cleanup(self, cleanup):
        try:
            cleanup.worker.close()
        except BaseException:
            cleanup.error = True
        with self._lock:
            if self._cleanup is cleanup:
                if cleanup.error:
                    self._state = RuntimeState.FAILED
                else:
                    self._worker = None
                    self._lease = None
                    self._active_requests = 0
                    self._state = RuntimeState.IDLE
                    self._cleanup = None
            cleanup.done.set()

    def _expire_if_needed(self):
        with self._lock:
            lease = self._lease
            if (
                lease is None
                or self._clock() < lease.expires_at
                or self._state in (RuntimeState.UNLOADING, RuntimeState.FAILED)
            ):
                return
            self._expired_lease_ids.add(lease.lease_id)
            worker = self._worker
            self._state = RuntimeState.UNLOADING
        try:
            self._cleanup_worker(worker, lease)
        except WorkerStopError:
            pass

    def _watch_expiry(self):
        while not self._stop_event.wait(0.2):
            self._expire_if_needed()

    def _raise_for_lease_locked(self, lease_id):
        lease_id = str(lease_id)
        if lease_id in self._expired_lease_ids:
            raise LeaseExpiredError()
        if self._lease is None or not secrets.compare_digest(
            self._lease.lease_id, lease_id
        ):
            raise LeaseError()

    @staticmethod
    def _validate_acquire_args(session_id, purpose, expected_chapter_count):
        if not isinstance(session_id, str) or not session_id.strip():
            raise ValueError("invalid session")
        if not isinstance(purpose, str) or not purpose.strip():
            raise ValueError("invalid purpose")
        if type(expected_chapter_count) is not int or expected_chapter_count <= 0:
            raise ValueError("invalid chapter count")

    @staticmethod
    def _bounded_call(action, error_type, timeout=30.0):
        result = queue.Queue(maxsize=1)

        def run():
            try:
                result.put((True, action()))
            except BaseException as error:
                result.put((False, error))

        threading.Thread(target=run, daemon=True).start()
        try:
            ok, value = result.get(timeout=timeout)
        except queue.Empty:
            raise error_type() from None
        if not ok:
            raise value
        return value
