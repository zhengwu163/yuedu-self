"""Provider-neutral NovelAudioServer v1 request dispatcher."""

import hmac
import secrets
import threading

from .errors import (
    BackendError,
    InvalidBackendResponseError,
    InvalidRequestError,
    LeaseError,
    NovelAudioError,
    RequestCancelledError,
    WorkerUnavailableError,
)
from .protocol import (
    MAX_AUDIO,
    analysis_request,
    analysis_response,
    array,
    record,
    string,
    synthesis_request,
)


class RequestContext:
    """One HTTP request owns cancellation until its response is published."""

    def __init__(self):
        self._event = threading.Event()
        self._lock = threading.Lock()
        self._callbacks = []
        self._finished = False
        self._cleanup_failed = False

    @property
    def cancelled(self):
        return self._event.is_set()

    def check(self):
        if self.cancelled:
            if self._cleanup_failed:
                raise WorkerUnavailableError()
            raise RequestCancelledError()

    def on_cancel(self, callback):
        with self._lock:
            if self._finished:
                return
            if not self.cancelled:
                self._callbacks.append(callback)
                return
        self._cleanup(callback)

    def _cleanup(self, callback):
        try:
            callback()
        except Exception:
            # Never retain provider/socket exception text in the public context.
            self._cleanup_failed = True

    def cancel(self):
        with self._lock:
            if self._finished or self.cancelled:
                return
            self._event.set()
            callbacks, self._callbacks = self._callbacks, []
        for callback in callbacks:
            self._cleanup(callback)

    def complete(self):
        with self._lock:
            self._finished = True
            self._callbacks.clear()

    def run(self, action):
        """Do not pin a HTTP slot to a provider that ignores cancellation."""
        self.check()
        done = threading.Event()
        result = []

        def invoke():
            try:
                self.check()
                result.append((True, action()))
            except BaseException as error:
                result.append((False, error))
            finally:
                done.set()

        threading.Thread(target=invoke, daemon=True, name="novel-audio-operation").start()
        while not done.wait(0.05):
            self.check()
        self.check()
        ok, value = result[0]
        if not ok:
            raise value
        return value


def _acquire(runtime, session, purpose, expected, context):
    context.check()
    acquire = getattr(runtime, "acquire_with_context", None)
    if callable(acquire):
        lease = acquire(session, purpose, expected, context)
    else:
        lease = runtime.acquire(session, purpose, expected)
    _bind_lease(runtime, lease.lease_id, context)
    context.check()
    return lease


def _bind_lease(runtime, lease_id, context):
    cancel = getattr(runtime, "cancel_generation", None)
    if callable(cancel):
        context.on_cancel(lambda: cancel(lease_id))


class _RequestLease:
    """Bind one generation call to an explicit or request-scoped lease."""

    def __init__(self, runtime, lease_id, purpose, backend, context):
        self.runtime = runtime
        self.lease_id = lease_id
        self.purpose = purpose
        self.backend = backend
        self.request_lease = False
        self.should_end = False
        self.context = context

    def __enter__(self):
        self.context.check()
        if self.runtime is None:
            return self.backend

        begin = getattr(self.runtime, "begin_generation", None)
        if not self.lease_id:
            lease = _acquire(
                self.runtime,
                secrets.token_urlsafe(24),
                self.purpose,
                1,
                self.context,
            )
            self.lease_id = lease.lease_id
            self.request_lease = True

        try:
            self.context.check()
            if begin is None:
                self.backend = self.runtime.validate(self.lease_id)
            else:
                self.backend = begin(self.lease_id)
                self.should_end = True
            if not self.request_lease:
                # Bind only after admission: a rejected busy request must not
                # cancel the request already using the same explicit lease.
                _bind_lease(self.runtime, self.lease_id, self.context)
            self.context.check()
            return self.backend
        except BaseException:
            if self.request_lease:
                try:
                    self.runtime.release(self.lease_id)
                except BaseException:
                    pass
                self.request_lease = False
            raise

    def __exit__(self, exc_type, exc, traceback):
        if self.context.cancelled:
            self.context.check()
        if isinstance(exc, WorkerUnavailableError) and self.runtime is not None:
            cancel = getattr(self.runtime, "cancel_generation", None)
            if callable(cancel):
                # A failed Worker/IPC must not become READY again. Revocation
                # is lease-scoped; RuntimeManager retains cleanup ownership
                # until the old Worker is gone, even when cleanup times out.
                try:
                    cancel(self.lease_id)
                except Exception:
                    raise WorkerUnavailableError() from None
                return False
        cleanup_error = None
        if self.should_end:
            try:
                self.runtime.end_generation(self.lease_id)
            except BaseException as error:
                cleanup_error = error
        if self.request_lease:
            try:
                self.runtime.release(self.lease_id)
            except BaseException as error:
                cleanup_error = cleanup_error or error
            self.request_lease = False

        if exc_type is not None:
            return False
        if cleanup_error is not None:
            raise WorkerUnavailableError() from None
        return False


class NovelAudioApi:
    def __init__(self, token, backend, catalog, runtime=None):
        self.token = token
        self.backend = backend
        self.catalog = catalog
        self.runtime = runtime
        self._closed = False

    def authorized(self, authorization):
        return hmac.compare_digest(
            (authorization or "").encode("utf-8"),
            ("Bearer " + self.token).encode("utf-8"),
        )

    @staticmethod
    def error(status, code):
        return status, {"Content-Type": "application/json"}, {
            "error": {"code": code}
        }

    def close(self):
        if self._closed:
            return
        self._closed = True
        if self.runtime is not None:
            self.runtime.close()
        close = getattr(self.backend, "close", None)
        if close is not None:
            close()

    def respond(self, method, path, authorization, body, lease_id=None):
        return self.respond_with_context(
            method, path, authorization, body, lease_id
        )

    def respond_with_context(
        self, method, path, authorization, body, lease_id=None,
        request_context=None,
    ):
        context = request_context or RequestContext()
        try:
            result = self._respond(method, path, authorization, body, lease_id, context)
            context.check()
            return result
        except (RequestCancelledError, WorkerUnavailableError) as error:
            return self.error(error.status, error.code)
        finally:
            if request_context is None:
                context.complete()

    def _respond(self, method, path, authorization, body, lease_id, context):
        if not self.authorized(authorization):
            return self.error(401, "unauthorized")
        if self._closed:
            return self.error(503, "stopped")

        try:
            context.check()
            if method == "GET" and path == "/v1/health":
                ready = bool(getattr(self.backend, "ready", True))
                return 200, {"Content-Type": "application/json"}, {
                    "status": "ok",
                    "apiVersion": "1",
                    "directorReady": ready,
                    "ttsReady": ready,
                }

            if method == "GET" and path == "/v1/voices":
                return 200, {"Content-Type": "application/json"}, {
                    "voices": self.catalog.public_voices(
                        self._active_capabilities()
                    )
                }

            if method == "GET" and path == "/v1/runtime/status":
                if self.runtime is None:
                    return self.error(404, "not_found")
                status = self.runtime.status()
                metadata = self._runtime_metadata()
                self._check_runtime_identity(status, metadata)
                return 200, {"Content-Type": "application/json"}, {
                    "state": status.state.value,
                    "activeLease": status.active_lease_count > 0,
                    "runtimeProfile": getattr(
                        self.backend,
                        "profile",
                        "local-runtime-v1",
                    ),
                    "runtimeProfileInfo": metadata,
                }

            if method != "POST":
                return self.error(404, "not_found")

            if path == "/v1/runtime/acquire":
                if self.runtime is None:
                    return self.error(404, "not_found")
                request = self._runtime_request(body)
                metadata = self._runtime_metadata()
                lease = _acquire(
                    self.runtime,
                    request["sessionId"],
                    request["purpose"],
                    request["expectedChapterCount"],
                    context,
                )
                try:
                    self._check_runtime_identity(self.runtime.status(), metadata)
                except InvalidBackendResponseError:
                    cancel = getattr(self.runtime, "cancel_generation", None)
                    if callable(cancel):
                        cancel(lease.lease_id)
                    else:
                        self.runtime.release(lease.lease_id)
                    raise
                return 200, {"Content-Type": "application/json"}, {
                    "leaseId": lease.lease_id,
                    "runtimeProfile": getattr(
                        self.backend,
                        "profile",
                        "local-runtime-v1",
                    ),
                    "runtimeProfileInfo": metadata,
                }

            if path == "/v1/runtime/release":
                if self.runtime is None or not lease_id:
                    raise LeaseError()
                self.runtime.release(lease_id)
                status = self.runtime.status()
                return 200, {"Content-Type": "application/json"}, {
                    "state": status.state.value
                }

            if path == "/v1/voices/match":
                return self._match(body)

            if path == "/v1/chapter/analyze":
                request = analysis_request(body)
                with self._backend_for_lease(
                    lease_id,
                    "chapter_analysis",
                    context,
                ) as backend:
                    self._check_worker_identity(backend, lease_id)
                    try:
                        result = context.run(lambda: backend.analyze(request))
                        projected = analysis_response(result, request)
                    except NovelAudioError:
                        raise
                    except (
                        ValueError,
                        TypeError,
                        KeyError,
                        OverflowError,
                        AttributeError,
                    ):
                        raise InvalidBackendResponseError() from None
                    except Exception:
                        raise BackendError() from None
                return 200, {"Content-Type": "application/json"}, projected

            if path in ("/v1/voices/preview", "/v1/tts/synthesize"):
                request = synthesis_request(body)
                if hasattr(self.catalog, "contains") and not self.catalog.contains(
                    request["voiceAssetId"], self._active_capabilities()
                ):
                    raise InvalidRequestError()
                with self._backend_for_lease(
                    lease_id,
                    "speech_synthesis",
                    context,
                ) as backend:
                    self._check_worker_identity(backend, lease_id)
                    try:
                        audio = context.run(lambda: backend.synthesize(request))
                    except NovelAudioError:
                        raise
                    except Exception:
                        raise BackendError() from None
                    self._check_worker_identity(backend, lease_id)
                if not isinstance(audio, bytes) or not 0 < len(audio) <= MAX_AUDIO:
                    raise InvalidBackendResponseError()
                profile = getattr(backend, "profile", "local-runtime-v1")
                if (
                    not isinstance(profile, str)
                    or not profile
                    or len(profile) > 256
                    or any(ord(char) < 32 or ord(char) == 127 for char in profile)
                ):
                    raise InvalidBackendResponseError()
                return 200, {
                    "Content-Type": "audio/ogg",
                    "X-TTS-Profile": profile,
                }, audio

            return self.error(404, "not_found")
        except NovelAudioError as error:
            return self.error(error.status, error.code)
        except (ValueError, TypeError, KeyError, OverflowError, AttributeError):
            return self.error(400, "invalid_request")

    @staticmethod
    def _check_runtime_identity(status, metadata):
        if (
            hasattr(status, "worker_profile")
            and status.worker_profile is not None
            and status.worker_profile != metadata["identity"]
        ):
            raise InvalidBackendResponseError()

    def _check_worker_identity(self, backend, lease_id):
        if getattr(backend, "profile", None) == self._runtime_metadata()["identity"]:
            return
        if self.runtime is not None and lease_id:
            cancel = getattr(self.runtime, "cancel_generation", None)
            if callable(cancel):
                cancel(lease_id)
        raise InvalidBackendResponseError()

    def _runtime_metadata(self):
        metadata = getattr(self.backend, "runtime_metadata", None)
        if metadata is None:
            profile = getattr(self.backend, "profile", "local-runtime-v1")
            return {
                "profileId": profile,
                "identity": profile,
                "capabilities": [],
                "minVramGb": None,
                "hardware": {"status": "unknown"},
            }
        value = metadata()
        if not isinstance(value, dict):
            raise InvalidBackendResponseError()
        allowed = {"profileId", "identity", "capabilities", "minVramGb", "hardware"}
        if set(value) != allowed:
            raise InvalidBackendResponseError()
        profile_id = value["profileId"]
        identity = value["identity"]
        capabilities = value["capabilities"]
        min_vram = value["minVramGb"]
        hardware = value["hardware"]
        if (
            not isinstance(profile_id, str)
            or not profile_id
            or not isinstance(identity, str)
            or not identity
            or not isinstance(capabilities, list)
            or any(not isinstance(item, str) or not item for item in capabilities)
            or (
                min_vram is not None
                and (
                    isinstance(min_vram, bool)
                    or not isinstance(min_vram, (int, float))
                    or min_vram != min_vram
                    or min_vram in (float("inf"), float("-inf"))
                )
            )
            or not isinstance(hardware, dict)
            or set(hardware) != {"status"}
            or hardware["status"]
            not in {"unknown", "deferred", "ready", "failed"}
        ):
            raise InvalidBackendResponseError()
        backend_profile = getattr(self.backend, "profile", None)
        if (
            isinstance(backend_profile, str)
            and backend_profile
            and identity != backend_profile
        ):
            raise InvalidBackendResponseError()
        return {
            "profileId": profile_id,
            "identity": identity,
            "capabilities": list(capabilities),
            "minVramGb": min_vram,
            "hardware": {"status": hardware["status"]},
        }

    def _active_capabilities(self):
        metadata = self._runtime_metadata()
        return metadata["capabilities"]

    @staticmethod
    def _runtime_request(body):
        record(body)
        session_id = string(body.get("sessionId"))
        purpose = string(body.get("purpose"))
        expected = body.get("expectedChapterCount")
        if type(expected) is not int or expected <= 0 or expected > 10000:
            raise ValueError("invalid")
        return {
            "sessionId": session_id,
            "purpose": purpose,
            "expectedChapterCount": expected,
        }

    def _backend_for_lease(self, lease_id, purpose, context):
        return _RequestLease(
            self.runtime,
            lease_id,
            purpose,
            self.backend,
            context,
        )

    def _match(self, body):
        record(body)
        persona = record(body.get("voicePersona"))
        traits = array(persona.get("traits"), 32)
        for trait in traits:
            string(trait)
        used = body.get("alreadyUsedVoiceIds")
        used = [] if used is None else used
        if not isinstance(used, list) or len(used) > 32:
            raise ValueError("invalid")
        for voice_id in used:
            string(voice_id)
        constraints = body.get("optionalConstraints")
        if constraints is not None:
            record(constraints)
            if set(constraints) - {"gender", "ageRange"}:
                raise ValueError("invalid")
            for value in constraints.values():
                string(value, 64)
        return 200, {"Content-Type": "application/json"}, {
            "candidates": self.catalog.match(
                {"traits": traits},
                used,
                constraints,
                self._active_capabilities(),
            )
        }
