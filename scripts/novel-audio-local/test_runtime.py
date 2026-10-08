import unittest
import time
import threading

from scripts.novel_audio_server.errors import (
    LeaseExpiredError,
    ServiceBusyError,
    WorkerStartError,
    WorkerStopError,
)
from scripts.novel_audio_server.runtime import RuntimeManager, RuntimeState


class FakeWorker:
    def __init__(self, profile, close_error=None):
        self.profile = profile
        self.close_error = close_error
        self.close_count = 0

    def close(self):
        self.close_count += 1
        if self.close_error is not None:
            raise self.close_error


class FakeWorkerFactory:
    def __init__(self, error=None, close_error=None, start_entered=None, start_release=None):
        self.error = error
        self.close_error = close_error
        self.start_entered = start_entered
        self.start_release = start_release
        self.workers = []
        self.profiles = []

    @property
    def start_count(self):
        return len(self.workers)

    @property
    def close_count(self):
        return sum(worker.close_count for worker in self.workers)

    def start(self, profile):
        self.profiles.append(profile)
        if self.start_entered is not None:
            self.start_entered.set()
        if self.start_release is not None:
            self.start_release.wait(2)
        if self.error is not None:
            raise self.error
        worker = FakeWorker(profile, close_error=self.close_error)
        self.workers.append(worker)
        return worker


class RuntimeManagerTest(unittest.TestCase):
    def test_acquire_starts_one_worker_and_returns_opaque_lease(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)

        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        self.assertTrue(lease.lease_id)
        self.assertEqual("session-1", lease.session_id)
        self.assertEqual(3, lease.expected_chapter_count)
        self.assertEqual(RuntimeState.READY, runtime.status().state)
        self.assertEqual(1, factory.start_count)

    def test_validate_returns_worker_for_active_lease(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        self.assertIs(factory.workers[0], runtime.validate(lease.lease_id))

    def test_acquire_passes_profile_and_starting_acquire_is_busy_without_waiting(self):
        started = threading.Event()
        release_start = threading.Event()
        factory = FakeWorkerFactory(
            start_entered=started,
            start_release=release_start,
        )
        runtime = RuntimeManager(factory, profile_id="profile-9b", startup_timeout=1)
        result = []

        thread = threading.Thread(
            target=lambda: result.append(
                runtime.acquire("session-1", "auto_prefetch", 3)
            )
        )
        thread.start()
        self.assertTrue(started.wait(1))

        began = time.monotonic()
        with self.assertRaises(ServiceBusyError):
            runtime.acquire("session-2", "pinned", 10)
        self.assertLess(time.monotonic() - began, 0.2)

        release_start.set()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(["profile-9b"], factory.profiles)
        runtime.close()

    def test_release_closes_worker_and_returns_idle(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        runtime.release(lease.lease_id)

        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(1, factory.close_count)
        self.assertEqual(0, runtime.status().active_lease_count)

    def test_generation_end_restores_ready_and_release_cannot_close_in_flight_worker(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        self.assertIs(factory.workers[0], runtime.begin_generation(lease.lease_id))
        with self.assertRaises(ServiceBusyError):
            runtime.release(lease.lease_id)
        self.assertEqual(1, runtime.status().active_request_count)
        runtime.end_generation(lease.lease_id)
        self.assertEqual(RuntimeState.READY, runtime.status().state)
        runtime.release(lease.lease_id)

    def test_generation_concurrency_is_limited_to_one(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        runtime.begin_generation(lease.lease_id)
        with self.assertRaises(ServiceBusyError):
            runtime.begin_generation(lease.lease_id)
        runtime.end_generation(lease.lease_id)
        runtime.release(lease.lease_id)

    def test_second_active_lease_is_rejected_without_starting_worker(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        runtime.acquire("session-1", "auto_prefetch", 3)

        with self.assertRaises(ServiceBusyError):
            runtime.acquire("session-2", "pinned", 10)

        self.assertEqual(1, factory.start_count)

    def test_expired_lease_is_cleaned_before_validation(self):
        now = [100.0]
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory, lease_ttl=10, clock=lambda: now[0])
        lease = runtime.acquire("session-1", "auto_prefetch", 3)
        now[0] = 111.0

        with self.assertRaises(LeaseExpiredError):
            runtime.validate(lease.lease_id)

        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(1, factory.close_count)

    def test_expired_lease_token_remains_expired_after_watchdog_removes_lease(self):
        now = [100.0]
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory, lease_ttl=10, clock=lambda: now[0])
        lease = runtime.acquire("session-1", "auto_prefetch", 3)
        now[0] = 111.0

        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        with self.assertRaises(LeaseExpiredError):
            runtime.begin_generation(lease.lease_id)

    def test_worker_start_failure_returns_sanitized_error_and_idle_state(self):
        factory = FakeWorkerFactory(error=RuntimeError("secret model path"))
        runtime = RuntimeManager(factory)

        with self.assertRaises(WorkerStartError) as caught:
            runtime.acquire("session-1", "auto_prefetch", 3)

        self.assertEqual("worker_start_failed", caught.exception.code)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)

    def test_close_is_idempotent_and_closes_active_worker_once(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        runtime.acquire("session-1", "auto_prefetch", 3)

        runtime.close()
        runtime.close()

        self.assertEqual(1, factory.close_count)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)

    def test_worker_close_failure_keeps_runtime_blocked_from_second_live_worker(self):
        factory = FakeWorkerFactory(close_error=RuntimeError("secret path"))
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        with self.assertRaises(WorkerStopError) as caught:
            runtime.release(lease.lease_id)

        self.assertEqual("worker_stop_failed", caught.exception.code)
        self.assertEqual(RuntimeState.FAILED, runtime.status().state)
        with self.assertRaises(ServiceBusyError):
            runtime.acquire("session-2", "pinned", 1)

    def test_failed_worker_can_be_invalidated_and_reacquired_after_cleanup(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        runtime.invalidate(lease.lease_id)
        next_lease = runtime.acquire("session-2", "pinned", 1)

        self.assertNotEqual(lease.lease_id, next_lease.lease_id)
        self.assertEqual(2, factory.start_count)
        runtime.close()

    def test_close_during_startup_closes_worker_that_finishes_late(self):
        started = threading.Event()
        release_start = threading.Event()
        factory = FakeWorkerFactory(
            start_entered=started,
            start_release=release_start,
        )
        runtime = RuntimeManager(factory, startup_timeout=0.05)
        result = []

        thread = threading.Thread(
            target=lambda: self._capture_error(
                result,
                lambda: runtime.acquire("session-1", "auto_prefetch", 3),
            )
        )
        thread.start()
        self.assertTrue(started.wait(1))
        runtime.close()
        release_start.set()
        thread.join(2)

        deadline = time.monotonic() + 2
        while (
            factory.close_count == 0
            or runtime.status().state != RuntimeState.IDLE
        ) and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(1, factory.close_count)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)

    def test_startup_timeout_cancels_late_worker_and_returns_idle(self):
        started = threading.Event()
        release_start = threading.Event()
        factory = FakeWorkerFactory(
            start_entered=started,
            start_release=release_start,
        )
        runtime = RuntimeManager(factory, startup_timeout=0.05)
        result = []

        thread = threading.Thread(
            target=lambda: self._capture_error(
                result,
                lambda: runtime.acquire("session-1", "auto_prefetch", 3),
            )
        )
        thread.start()
        self.assertTrue(started.wait(1))
        thread.join(1)
        self.assertFalse(thread.is_alive())
        self.assertIsInstance(result[0], WorkerStartError)

        release_start.set()
        deadline = time.monotonic() + 2
        while (
            factory.close_count == 0
            or runtime.status().state != RuntimeState.IDLE
        ) and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(1, factory.close_count)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)

    @staticmethod
    def _capture_error(result, action):
        try:
            result.append(action())
        except Exception as error:
            result.append(error)

    def test_nonfinite_lease_ttl_is_rejected(self):
        with self.assertRaises(ValueError):
            RuntimeManager(FakeWorkerFactory(), lease_ttl=float("nan"))

    def test_watchdog_expires_lease_without_followup_request(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory, lease_ttl=0.05)
        runtime.acquire("session-1", "auto_prefetch", 3)
        deadline = time.monotonic() + 2
        while factory.close_count == 0 and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(1, factory.close_count)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        runtime.close()

    def test_failed_start_keeps_unreaped_worker_owned_until_verified_cleanup(self):
        entered, release = threading.Event(), threading.Event()

        class Worker:
            profile = "profile"

            def close(self):
                entered.set()
                release.wait(2)

        worker = Worker()
        error = WorkerStartError()
        error.worker = worker
        runtime = RuntimeManager(FakeWorkerFactory(error=error), cleanup_timeout=0.05)
        self.addCleanup(runtime.close)
        self.addCleanup(release.set)
        with self.assertRaises(WorkerStartError):
            runtime.acquire("first", "pinned", 1)
        self.assertTrue(entered.wait(0.5))
        with self.assertRaises(ServiceBusyError):
            runtime.acquire("second", "pinned", 1)
        release.set()
        deadline = time.monotonic() + 1
        while runtime.status().state != RuntimeState.IDLE and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)


if __name__ == "__main__":
    unittest.main()
