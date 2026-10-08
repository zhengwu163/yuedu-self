import json
import http.client
import socket
import threading
import time
import unittest

from scripts.novel_audio_server import api as api_module
from scripts.novel_audio_server.api import NovelAudioApi
from scripts.novel_audio_server.errors import (
    BackendError,
    InvalidRequestError,
    ServiceBusyError,
    WorkerUnavailableError,
)
from scripts.novel_audio_server.http import create_server
from scripts.novel_audio_server.runtime import RuntimeManager, RuntimeState
from unittest.mock import patch


class Catalog:
    def public_voices(self, capabilities=None):
        return []

    def match(self, *args, **kwargs):
        return []

    def contains(self, voice_asset_id, capabilities=None):
        return voice_asset_id == "local.narrator"


class StaticBackend:
    profile = "advertised-profile"
    ready = True

    def runtime_metadata(self):
        return {
            "profileId": "profile-id",
            "identity": self.profile,
            "capabilities": ["speech-synthesis"],
            "minVramGb": None,
            "hardware": {"status": "unknown"},
        }

    def close(self):
        pass


class BlockingWorker:
    def __init__(self, profile="advertised-profile", block_close=False):
        self.profile = profile
        self.started = threading.Event()
        self.cancelled = threading.Event()
        self.close_started = threading.Event()
        self.close_release = threading.Event()
        self.block_close = block_close
        self.close_count = 0

    def synthesize(self, request):
        self.started.set()
        self.cancelled.wait(10)
        raise RuntimeError("private worker failure")

    def cancel(self):
        self.cancelled.set()

    def close(self):
        self.cancel()
        self.close_count += 1
        self.close_started.set()
        if self.block_close:
            self.close_release.wait(2)


class ImmediateWorker(BlockingWorker):
    def synthesize(self, request):
        return b"OggS-audio"


class FatalWorker(BlockingWorker):
    def synthesize(self, request):
        raise WorkerUnavailableError()


class WorkerFactory:
    def __init__(self, worker):
        self.worker = worker
        self.start_count = 0

    def start(self, profile):
        self.start_count += 1
        return self.worker


def synthesis_request():
    return {
        "text": "正文",
        "voiceAssetId": "local.narrator",
        "language": "zh-CN",
        "speed": 1.0,
    }


class CancellationTest(unittest.TestCase):
    def context(self):
        self.assertTrue(hasattr(api_module, "RequestContext"))
        return api_module.RequestContext()

    def _api(self, runtime, backend=None):
        return NovelAudioApi(
            token="token",
            backend=backend or StaticBackend(),
            catalog=Catalog(),
            runtime=runtime,
        )

    def test_request_cancellation_recycles_worker_and_auto_lease(self):
        context = self.context()
        worker = BlockingWorker()
        runtime = RuntimeManager(
            WorkerFactory(worker),
            startup_timeout=0.5,
            cleanup_timeout=0.5,
        )
        api = self._api(runtime)
        result = []

        thread = threading.Thread(
            target=lambda: result.append(
                api.respond_with_context(
                    "POST",
                    "/v1/tts/synthesize",
                    "Bearer token",
                    synthesis_request(),
                    request_context=context,
                )
            )
        )
        thread.start()
        self.assertTrue(worker.started.wait(1))

        context.cancel()
        thread.join(2)

        self.assertFalse(thread.is_alive())
        self.assertEqual(
            (503, {"Content-Type": "application/json"}, {
                "error": {"code": "request_cancelled"}
            }),
            result[0],
        )
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        self.assertEqual(1, worker.close_count)
        runtime.close()

    def test_cancellation_cleanup_keeps_runtime_busy_until_worker_is_gone(self):
        worker = BlockingWorker(block_close=True)
        factory = WorkerFactory(worker)
        runtime = RuntimeManager(
            factory,
            startup_timeout=0.5,
            cleanup_timeout=0.5,
        )
        lease = runtime.acquire("session-1", "pinned", 1)
        self.addCleanup(runtime.close)
        self.addCleanup(worker.close_release.set)
        runtime.begin_generation(lease.lease_id)
        result = []
        self.assertTrue(callable(getattr(runtime, "cancel_generation", None)))

        thread = threading.Thread(
            target=lambda: self._capture(
                result,
                lambda: runtime.cancel_generation(lease.lease_id),
            )
        )
        thread.start()
        self.assertTrue(worker.close_started.wait(1))

        with self.assertRaises(ServiceBusyError):
            runtime.acquire("session-2", "pinned", 1)

        worker.close_release.set()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual([], result)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        runtime.close()

    def test_real_socket_disconnect_cancels_blocked_generation(self):
        worker = BlockingWorker()
        runtime = RuntimeManager(
            WorkerFactory(worker),
            startup_timeout=0.5,
            cleanup_timeout=0.5,
        )
        api = self._api(runtime)
        server = create_server("127.0.0.1", 0, api)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = socket.create_connection(server.server_address, timeout=2)
        payload = json.dumps(synthesis_request()).encode("utf-8")
        client.sendall(
            (
                b"POST /v1/tts/synthesize HTTP/1.1\r\n"
                b"Host: localhost\r\n"
                b"Authorization: Bearer token\r\n"
                b"Content-Type: application/json\r\n"
                + f"Content-Length: {len(payload)}\r\n".encode("ascii")
                + b"Connection: close\r\n\r\n"
                + payload
            )
        )
        try:
            self.assertTrue(worker.started.wait(1))
            client.shutdown(socket.SHUT_RDWR)
            client.close()

            deadline = time.monotonic() + 1
            while (
                runtime.status().active_lease_count
                or runtime.status().state != RuntimeState.IDLE
            ) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertEqual(RuntimeState.IDLE, runtime.status().state)
            self.assertEqual(0, runtime.status().active_lease_count)
            self.assertEqual(1, worker.close_count)
        finally:
            try:
                client.close()
            except OSError:
                pass
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_response_write_os_error_cancels_context(self):
        self._write_failure("headers", explicit=True)

    def test_response_body_write_failure_revokes_acquired_lease(self):
        self._write_failure("body", acquire=True)

    def test_response_body_write_failure_auto_lease_has_no_residue(self):
        self._write_failure("body")

    def _write_failure(self, phase, explicit=False, acquire=False):
        context = self.context()
        worker = ImmediateWorker()
        runtime = RuntimeManager(WorkerFactory(worker), cleanup_timeout=0.5)
        api = self._api(runtime)
        lease = runtime.acquire("session", "pinned", 1) if explicit else None
        received = threading.Event()
        server = create_server("127.0.0.1", 0, api)
        original = server.RequestHandlerClass.end_headers
        def fail_write(handler):
            if phase == "headers":
                received.set()
                raise OSError("private socket detail")
            original(handler)
            writer = handler.wfile

            class Writer:
                def write(self, data):
                    received.set()
                    raise OSError("private socket detail")

                def __getattr__(self, name):
                    return getattr(writer, name)

            handler.wfile = Writer()

        # Deterministically fail header publication on a real HTTP handler.
        with patch("scripts.novel_audio_server.http.RequestContext",
                      return_value=context), \
                patch.object(server.RequestHandlerClass, "end_headers", fail_write):
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with socket.create_connection(server.server_address, timeout=2) as client:
                    body = json.dumps({
                        "sessionId": "s", "purpose": "pinned", "expectedChapterCount": 1,
                    } if acquire else synthesis_request()).encode()
                    route = "/v1/runtime/acquire" if acquire else "/v1/tts/synthesize"
                    lease_header = (
                        f"X-NovelAudio-Lease: {lease.lease_id}\r\n".encode() if lease else b""
                    )
                    client.sendall(
                        f"POST {route} HTTP/1.1\r\n".encode()
                        +
                        b"Host: localhost\r\nAuthorization: Bearer token\r\n"
                        b"Content-Type: application/json\r\n"
                        + lease_header
                        + f"Content-Length: {len(body)}\r\n\r\n".encode()
                        + body
                    )
                    self.assertTrue(received.wait(1))
                    deadline = time.monotonic() + 1
                    while (
                        not context.cancelled or runtime.status().active_lease_count
                    ) and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(context.cancelled)
                    self.assertEqual(0, runtime.status().active_lease_count)
                    self._wait_idle(runtime)
                    self.assertEqual(1, worker.close_count)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(2)

    def test_worker_identity_mismatch_is_rejected_without_pseudo_profile(self):
        worker = ImmediateWorker(profile="worker-mismatch")
        runtime = RuntimeManager(
            WorkerFactory(worker),
            startup_timeout=0.5,
            cleanup_timeout=0.5,
        )
        api = self._api(runtime)

        status, headers, body = api.respond(
            "POST",
            "/v1/tts/synthesize",
            "Bearer token",
            synthesis_request(),
        )

        self.assertEqual(502, status)
        self.assertEqual({"error": {"code": "invalid_backend_response"}}, body)
        self.assertNotIn("X-TTS-Profile", headers)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        runtime.close()

    def test_explicit_worker_failure_revokes_lease_and_allows_next_worker(self):
        first = FatalWorker()
        second = ImmediateWorker()

        class Factory:
            def __init__(self):
                self.workers = [first, second]
                self.start_count = 0

            def start(self, profile):
                self.start_count += 1
                return self.workers.pop(0)

        factory = Factory()
        runtime = RuntimeManager(factory, cleanup_timeout=0.5)
        self.addCleanup(runtime.close)
        api = self._api(runtime)
        lease = runtime.acquire("s", "pinned", 1)

        status, _, body = api.respond(
            "POST",
            "/v1/tts/synthesize",
            "Bearer token",
            synthesis_request(),
            lease.lease_id,
        )

        self.assertEqual(503, status)
        self.assertEqual({"error": {"code": "worker_unavailable"}}, body)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        self.assertEqual(0, runtime.status().active_request_count)

        next_lease = runtime.acquire("next", "pinned", 1)
        self.assertEqual(2, factory.start_count)
        runtime.cancel_generation(lease.lease_id)
        self.assertEqual(RuntimeState.READY, runtime.status().state)
        self.assertEqual(0, second.close_count)
        self.assertEqual(1, first.close_count)
        status, _, audio = api.respond(
            "POST", "/v1/tts/synthesize", "Bearer token",
            synthesis_request(), next_lease.lease_id,
        )
        self.assertEqual((200, b"OggS-audio"), (status, audio))
        runtime.release(next_lease.lease_id)

    def test_explicit_worker_failure_cleanup_timeout_blocks_next_worker(self):
        worker = FatalWorker(block_close=True)
        runtime = RuntimeManager(
            WorkerFactory(worker),
            cleanup_timeout=0.05,
        )
        self.addCleanup(runtime.close)
        self.addCleanup(worker.close_release.set)
        api = self._api(runtime)
        lease = runtime.acquire("s", "pinned", 1)

        status, _, body = api.respond(
            "POST",
            "/v1/tts/synthesize",
            "Bearer token",
            synthesis_request(),
            lease.lease_id,
        )

        self.assertEqual(503, status)
        self.assertEqual({"error": {"code": "worker_unavailable"}}, body)
        self.assertEqual(RuntimeState.FAILED, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        with self.assertRaises(ServiceBusyError):
            runtime.acquire("next", "pinned", 1)
        worker.close_release.set()
        self._wait_idle(runtime)
        self.assertEqual(1, worker.close_count)

    def test_automatic_worker_failure_cleans_up_once(self):
        worker = FatalWorker()
        runtime = RuntimeManager(WorkerFactory(worker), cleanup_timeout=0.5)
        self.addCleanup(runtime.close)
        status, _, body = self._api(runtime).respond(
            "POST", "/v1/voices/preview", "Bearer token", synthesis_request(),
        )
        self.assertEqual(503, status)
        self.assertEqual({"error": {"code": "worker_unavailable"}}, body)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        self.assertEqual(1, worker.close_count)

    def test_ordinary_backend_errors_preserve_explicit_lease(self):
        for error in (InvalidRequestError(), BackendError()):
            with self.subTest(code=error.code):
                worker = ImmediateWorker()
                runtime = RuntimeManager(WorkerFactory(worker), cleanup_timeout=0.5)
                self.addCleanup(runtime.close)
                lease = runtime.acquire("s", "pinned", 1)
                with patch.object(worker, "synthesize", side_effect=error):
                    status, _, body = self._api(runtime).respond(
                        "POST", "/v1/tts/synthesize", "Bearer token",
                        synthesis_request(), lease.lease_id,
                    )
                self.assertEqual(error.status, status)
                self.assertEqual({"error": {"code": error.code}}, body)
                self.assertEqual(RuntimeState.READY, runtime.status().state)
                self.assertEqual(1, runtime.status().active_lease_count)
                self.assertEqual(0, worker.close_count)
                runtime.release(lease.lease_id)

    def test_cancel_returns_bounded_when_backend_and_cleanup_are_blocked(self):
        release = threading.Event()
        entered = threading.Event()
        worker = BlockingWorker(block_close=True)

        def blocked(request):
            entered.set()
            release.wait(5)
            return b"OggS-late"

        worker.synthesize = blocked
        runtime = RuntimeManager(WorkerFactory(worker), cleanup_timeout=0.05)
        self.addCleanup(runtime.close)
        self.addCleanup(worker.close_release.set)
        self.addCleanup(release.set)
        api = self._api(runtime)
        context = self.context()
        result = []
        thread = threading.Thread(target=lambda: result.append(
            api.respond_with_context(
                "POST", "/v1/tts/synthesize", "Bearer token", synthesis_request(),
                request_context=context,
            )
        ))
        thread.start()
        try:
            self.assertTrue(entered.wait(1))
            context.cancel()
            thread.join(0.5)
            self.assertFalse(thread.is_alive())
            self.assertIn(result[0][2]["error"]["code"],
                          {"request_cancelled", "worker_unavailable"})
            self.assertEqual(0, runtime.status().active_lease_count)
            with self.assertRaises(ServiceBusyError):
                runtime.acquire("next", "pinned", 1)
        finally:
            release.set()
            worker.close_release.set()
            thread.join(2)

    def test_acquire_rejects_identity_mismatch_and_clears_lease(self):
        worker = ImmediateWorker(profile="worker-mismatch")
        runtime = RuntimeManager(WorkerFactory(worker))
        self.addCleanup(runtime.close)
        api = self._api(runtime)
        status, headers, body = api.respond(
            "POST", "/v1/runtime/acquire", "Bearer token",
            {"sessionId": "s", "purpose": "pinned", "expectedChapterCount": 1},
        )
        self.assertEqual(502, status)
        self.assertEqual({"error": {"code": "invalid_backend_response"}}, body)
        self.assertNotIn("X-TTS-Profile", headers)
        self.assertEqual(0, runtime.status().active_lease_count)
        self.assertEqual(1, worker.close_count)

    def test_status_rejects_active_worker_identity_mismatch(self):
        runtime = RuntimeManager(WorkerFactory(ImmediateWorker("worker-mismatch")))
        self.addCleanup(runtime.close)
        runtime.acquire("s", "pinned", 1)
        status, _, body = self._api(runtime).respond(
            "GET", "/v1/runtime/status", "Bearer token", None,
        )
        self.assertEqual(502, status)
        self.assertEqual({"error": {"code": "invalid_backend_response"}}, body)

    def test_profile_change_during_synthesis_is_not_published(self):
        worker = ImmediateWorker()

        def synthesize(request):
            worker.profile = "changed-identity"
            return b"OggS-result"

        worker.synthesize = synthesize
        runtime = RuntimeManager(WorkerFactory(worker))
        self.addCleanup(runtime.close)
        status, headers, body = self._api(runtime).respond(
            "POST", "/v1/tts/synthesize", "Bearer token", synthesis_request(),
        )
        self.assertEqual(502, status)
        self.assertNotIn("X-TTS-Profile", headers)
        self.assertEqual({"error": {"code": "invalid_backend_response"}}, body)

    def test_close_and_cancel_share_cleanup_even_after_timeout(self):
        worker = BlockingWorker(block_close=True)
        factory = WorkerFactory(worker)
        runtime = RuntimeManager(factory, cleanup_timeout=0.05)
        self.addCleanup(runtime.close)
        self.addCleanup(worker.close_release.set)
        lease = runtime.acquire("s", "pinned", 1)
        runtime.begin_generation(lease.lease_id)
        with self.assertRaises(WorkerUnavailableError):
            runtime.cancel_generation(lease.lease_id)
        self.assertEqual(RuntimeState.FAILED, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)
        errors = []
        thread = threading.Thread(target=lambda: self._capture(errors, runtime.close))
        thread.start()
        thread.join(0.2)
        self.assertFalse(thread.is_alive())
        self.assertEqual(1, worker.close_count)
        self.assertEqual(1, factory.start_count)
        worker.close_release.set()
        self._wait_idle(runtime)
        self.assertEqual(1, worker.close_count)

    def test_shutdown_first_then_cancel_does_not_restore_ready_or_close_twice(self):
        worker = BlockingWorker(block_close=True)
        runtime = RuntimeManager(WorkerFactory(worker), cleanup_timeout=0.5)
        self.addCleanup(runtime.close)
        self.addCleanup(worker.close_release.set)
        lease = runtime.acquire("s", "pinned", 1)
        runtime.begin_generation(lease.lease_id)
        errors = []
        shutdown = threading.Thread(target=lambda: self._capture(errors, runtime.close))
        shutdown.start()
        self.assertTrue(worker.close_started.wait(1))
        with self.assertRaises(WorkerUnavailableError):
            runtime.end_generation(lease.lease_id)
        cancellation = threading.Thread(target=lambda: self._capture(
            errors, lambda: runtime.cancel_generation(lease.lease_id)
        ))
        cancellation.start()
        worker.close_release.set()
        shutdown.join(1)
        cancellation.join(1)
        self.assertEqual([], errors)
        self.assertEqual(1, worker.close_count)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)
        self.assertEqual(0, runtime.status().active_lease_count)

    def test_pre_cancelled_request_never_starts_worker(self):
        context = self.context()
        context.cancel()
        factory = WorkerFactory(ImmediateWorker())
        runtime = RuntimeManager(factory)
        self.addCleanup(runtime.close)
        status, _, body = self._api(runtime).respond_with_context(
            "POST", "/v1/tts/synthesize", "Bearer token", synthesis_request(),
            request_context=context,
        )
        self.assertEqual(503, status)
        self.assertEqual({"error": {"code": "request_cancelled"}}, body)
        self.assertEqual(0, factory.start_count)

    def test_busy_requests_cannot_cancel_another_requests_lease(self):
        runtime = RuntimeManager(WorkerFactory(ImmediateWorker()))
        self.addCleanup(runtime.close)
        lease = runtime.acquire("s", "pinned", 1)
        runtime.begin_generation(lease.lease_id)
        context = self.context()
        status, _, _ = self._api(runtime).respond_with_context(
            "POST", "/v1/tts/synthesize", "Bearer token", synthesis_request(),
            lease.lease_id, request_context=context,
        )
        self.assertEqual(429, status)
        context.cancel()
        self.assertEqual(1, runtime.status().active_lease_count)
        self.assertEqual(1, runtime.status().active_request_count)

    def test_cancel_during_startup_reaps_late_worker_without_new_generation(self):
        entered, release = threading.Event(), threading.Event()
        worker = BlockingWorker()

        class Factory:
            def start(self, profile):
                entered.set()
                release.wait(3)
                return worker

        runtime = RuntimeManager(Factory(), startup_timeout=300, cleanup_timeout=0.5)
        self.addCleanup(runtime.close)
        self.addCleanup(release.set)
        api, context, result = self._api(runtime), self.context(), []
        thread = threading.Thread(target=lambda: result.append(
            api.respond_with_context(
                "POST", "/v1/tts/synthesize", "Bearer token", synthesis_request(),
                request_context=context,
            )
        ))
        thread.start()
        self.assertTrue(entered.wait(1))
        context.cancel()
        thread.join(0.5)
        self.assertFalse(thread.is_alive())
        self.assertEqual({"error": {"code": "request_cancelled"}}, result[0][2])
        with self.assertRaises(ServiceBusyError):
            runtime.acquire("next", "pinned", 1)
        release.set()
        self._wait_idle(runtime)
        self.assertFalse(worker.started.is_set())
        self.assertEqual(1, worker.close_count)

    def test_real_socket_explicit_lease_disconnect_and_metadata_stays_lazy(self):
        self._socket_explicit(disconnect=True)

    def test_real_socket_identity_status_acquire_and_audio_are_consistent(self):
        self._socket_explicit(disconnect=False)

    def _socket_explicit(self, disconnect):
        worker = BlockingWorker() if disconnect else ImmediateWorker()
        factory = WorkerFactory(worker)
        runtime = RuntimeManager(factory, cleanup_timeout=0.5)
        server = create_server("127.0.0.1", 0, self._api(runtime))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def request(method, path, body=None, lease=None):
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            try:
                headers = {"Authorization": "Bearer token", "Content-Type": "application/json"}
                if lease:
                    headers["X-NovelAudio-Lease"] = lease
                connection.request(method, path, json.dumps(body) if body else None, headers)
                response = connection.getresponse()
                return response.status, dict(response.getheaders()), response.read()
            finally:
                connection.close()

        try:
            for method, path, body in (
                ("GET", "/v1/health", None),
                ("GET", "/v1/voices", None),
                ("GET", "/v1/runtime/status", None),
                ("POST", "/v1/voices/match", {"voicePersona": {"traits": []}}),
            ):
                self.assertEqual(200, request(method, path, body)[0])
            self.assertEqual(0, factory.start_count)
            status_payload = json.loads(request("GET", "/v1/runtime/status")[2])
            code, _, raw = request("POST", "/v1/runtime/acquire", {
                "sessionId": "s", "purpose": "pinned", "expectedChapterCount": 1,
            })
            self.assertEqual(200, code)
            acquired = json.loads(raw)
            lease = acquired["leaseId"]
            if disconnect:
                with socket.create_connection(server.server_address, timeout=2) as client:
                    body = json.dumps(synthesis_request()).encode()
                    client.sendall(
                        b"POST /v1/tts/synthesize HTTP/1.1\r\n"
                        b"Host: localhost\r\nAuthorization: Bearer token\r\n"
                        b"Content-Type: application/json\r\n"
                        + f"X-NovelAudio-Lease: {lease}\r\n".encode()
                        + f"Content-Length: {len(body)}\r\n\r\n".encode() + body
                    )
                    self.assertTrue(worker.started.wait(1))
                    client.shutdown(socket.SHUT_RDWR)
                self._wait_idle(runtime)
                self.assertEqual(1, worker.close_count)
                self.assertEqual(0, runtime.status().active_request_count)
                self.assertEqual(0, runtime.status().active_lease_count)
            else:
                code, headers, raw = request("POST", "/v1/tts/synthesize", synthesis_request(), lease)
                self.assertEqual(200, code)
                self.assertEqual(b"OggS-audio", raw)
                self.assertEqual(1, factory.start_count)
                identities = {
                    status_payload["runtimeProfile"],
                    status_payload["runtimeProfileInfo"]["identity"],
                    acquired["runtimeProfile"], acquired["runtimeProfileInfo"]["identity"],
                    headers["X-TTS-Profile"],
                }
                self.assertEqual({"advertised-profile"}, identities)
                self.assertEqual(200, request("POST", "/v1/runtime/release", lease=lease)[0])
                self.assertEqual(0, runtime.status().active_lease_count)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def _wait_idle(self, runtime):
        deadline = time.monotonic() + 1
        while runtime.status().state != RuntimeState.IDLE and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(RuntimeState.IDLE, runtime.status().state)

    @staticmethod
    def _capture(result, action):
        try:
            action()
        except BaseException as error:
            result.append(error)


if __name__ == "__main__":
    unittest.main()
