import unittest

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
    def __init__(self, error=None, close_error=None):
        self.error = error
        self.close_error = close_error
        self.workers = []

    @property
    def start_count(self):
        return len(self.workers)

    @property
    def close_count(self):
        return sum(worker.close_count for worker in self.workers)

    def start(self, profile):
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

    def test_release_closes_worker_and_returns_idle(self):
        factory = FakeWorkerFactory()
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        runtime.release(lease.lease_id)

        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(1, factory.close_count)
        self.assertEqual(0, runtime.status().active_lease_count)

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

    def test_worker_close_failure_is_sanitized_and_runtime_is_idle(self):
        factory = FakeWorkerFactory(close_error=RuntimeError("secret path"))
        runtime = RuntimeManager(factory)
        lease = runtime.acquire("session-1", "auto_prefetch", 3)

        with self.assertRaises(WorkerStopError) as caught:
            runtime.release(lease.lease_id)

        self.assertEqual("worker_stop_failed", caught.exception.code)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)


if __name__ == "__main__":
    unittest.main()
