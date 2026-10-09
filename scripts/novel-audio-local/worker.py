"""Bounded Worker IPC and lazy Qwen model loading.

The parent Agent never imports torch/qwen_tts. This file is the only local
service entrypoint allowed to load heavy model dependencies.
"""

import base64
import json
import math
import os
import queue
import signal
import subprocess
import sys
import threading
import time
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.novel_audio_server.errors import (
    NovelAudioError, RequestCancelledError, WorkerStartError, WorkerUnavailableError,
    ResourceUnavailableError, ResourceCheckError,
)
from scripts.novel_audio_server.protocol import (
    analysis_request,
    analysis_response,
    strict_json_loads,
    synthesis_request,
)

from backend import FakeBackend
from config import (
    DEFAULT_WORKER_IPC_TIMEOUT,
    DEFAULT_WORKER_STARTUP_TIMEOUT,
    WORKER_TIMEOUT_MAX,
    WORKER_TIMEOUT_MIN,
    load_config,
)
from model_registry import sha256_file
from qwen_backend import QwenBackend
from resource_gate import ensure_resources
from voices import VoiceCatalog


MAX_LINE = 24 * 1024 * 1024
# The startup budget includes child-side registry verification and hashing before
# the ready event. Generation gets a separate budget because Qwen models load on
# the first analyze/synthesize request, after readiness has already succeeded.
STARTUP_TIMEOUT = DEFAULT_WORKER_STARTUP_TIMEOUT
IPC_TIMEOUT = DEFAULT_WORKER_IPC_TIMEOUT
RESOURCE_ERRORS = {error.code: error for error in (ResourceUnavailableError, ResourceCheckError)}


def _timeout(value, name):
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
        or not WORKER_TIMEOUT_MIN <= float(value) <= WORKER_TIMEOUT_MAX
    ):
        raise ValueError(f"invalid {name}")
    return float(value)


def _ipc_json_loads(value):
    def pairs(items):
        result = {}
        for key, item in items:
            if key in result:
                raise ValueError("duplicate")
            result[key] = item
        return result

    def reject(_):
        raise ValueError("constant")

    try:
        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="strict")
        if not isinstance(value, str) or len(value.encode("utf-8")) > MAX_LINE:
            raise ValueError()
        result = json.loads(
            value,
            object_pairs_hook=pairs,
            parse_constant=reject,
        )
        json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return result
    except (RecursionError, UnicodeError, ValueError):
        raise ValueError("invalid") from None


def _readline_bounded(stream):
    line = stream.readline(MAX_LINE + 1)
    if not line:
        raise RuntimeError("worker unavailable")
    if len(line.encode("utf-8")) > MAX_LINE:
        raise ValueError("oversized response")
    return line


class SubprocessWorker:
    profile = "local-worker-v1"

    def __init__(
        self,
        command,
        profile,
        expected_identity=None,
        startup_timeout=None,
        ipc_timeout=None,
    ):
        self.startup_timeout = _timeout(
            STARTUP_TIMEOUT if startup_timeout is None else startup_timeout,
            "startup timeout",
        )
        self.ipc_timeout = _timeout(
            IPC_TIMEOUT if ipc_timeout is None else ipc_timeout,
            "ipc timeout",
        )
        self.profile = profile
        try:
            self.process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                bufsize=1,
                start_new_session=os.name == "posix",
            )
        except OSError:
            raise WorkerStartError() from None
        self._responses = queue.Queue()
        self._request_lock = threading.Lock()
        self._close_lock = threading.Lock()
        self._cancelled = threading.Event()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()
        self._closed = False
        try:
            readiness = self._responses.get(timeout=self.startup_timeout)
        except queue.Empty:
            self._fail_start()
        if (
            not isinstance(readiness, dict)
            or readiness.get("ok") is not True
            or readiness.get("event") != "ready"
        ):
            code = readiness.get("code") if isinstance(readiness, dict) else None
            error_type = RESOURCE_ERRORS.get(code) if isinstance(code, str) else None
            self._fail_start(error_type() if error_type else None)
        identity = readiness.get("profile")
        if not isinstance(identity, str) or (
            expected_identity is not None and identity != expected_identity
        ):
            self._fail_start()
        self.profile = identity

    def _fail_start(self, error=None):
        error = error or WorkerStartError()
        try:
            self.close()
        except Exception:
            # A failed constructor cannot return its worker. Transfer ownership
            # privately so Runtime still blocks admission until cleanup succeeds.
            error.worker = self
        raise error from None

    def _read_loop(self):
        try:
            while True:
                try:
                    line = _readline_bounded(self.process.stdout)
                    self._responses.put(_ipc_json_loads(line))
                except ValueError as error:
                    self._responses.put(error)
                    return
                except RuntimeError as error:
                    self._responses.put(error)
                    return
        except Exception as error:
            self._responses.put(error)

    def _call(self, operation, request):
        self._check_available()
        if not self._request_lock.acquire(blocking=False):
            raise WorkerUnavailableError()
        try:
            return self._exchange(operation, request)
        finally:
            self._request_lock.release()

    def _check_available(self):
        if self._cancelled.is_set():
            raise RequestCancelledError()
        if self._closed:
            raise WorkerUnavailableError()

    def _exchange(self, operation, request):
        self._check_available()
        payload = json.dumps(
            {"operation": operation, "request": request},
            ensure_ascii=False,
            allow_nan=False,
        )
        if len(payload.encode("utf-8")) > MAX_LINE:
            raise ValueError("oversized request")
        try:
            self.process.stdin.write(payload + "\n")
            self.process.stdin.flush()
            response = self._responses.get(timeout=self.ipc_timeout)
        except (OSError, ValueError, queue.Empty):
            self._check_available()
            raise WorkerUnavailableError() from None
        self._check_available()
        if isinstance(response, Exception):
            raise WorkerUnavailableError() from None
        if not isinstance(response, dict) or not response.get("ok"):
            code = response.get("code") if isinstance(response, dict) else None
            if isinstance(code, str) and code in RESOURCE_ERRORS:
                raise RESOURCE_ERRORS[code]()
            raise WorkerUnavailableError()
        return response.get("result")

    def analyze(self, request):
        return self._call("analyze", request)

    def synthesize(self, request):
        result = self._call("synthesize", request)
        try:
            return base64.b64decode(result, validate=True)
        except (TypeError, ValueError):
            raise WorkerUnavailableError() from None

    def cancel(self):
        # Wake a response waiter without taking its IPC lock. Close will kill
        # the process before touching streams, also interrupting a blocked write.
        self._cancelled.set()
        self._responses.put(RequestCancelledError())

    def close(self):
        if not self._close_lock.acquire(timeout=2):
            raise WorkerUnavailableError()
        try:
            if self._closed:
                return
            self._graceful_close = not self._request_lock.locked()
            self.cancel()
            self._terminate_tree()
            self._reader.join(timeout=1)
            if self._reader.is_alive():
                raise WorkerUnavailableError()
            if not self._request_lock.acquire(timeout=1):
                raise WorkerUnavailableError()
            self._request_lock.release()
            for stream in (self.process.stdin, self.process.stdout):
                try:
                    stream.close()
                except OSError:
                    pass
            self._closed = True
        except (OSError, ValueError):
            raise WorkerUnavailableError() from None
        finally:
            self._close_lock.release()

    def _terminate_tree(self):
        # Let an idle backend close its owned llama process before killing the
        # Windows venv launcher. Keep the force-stop path for busy/hung workers.
        if (os.name == "nt" and getattr(self, "_graceful_close", True)
                and getattr(self.process, "stdin", None) is not None
                and self.process.poll() is None and self._request_lock.acquire(blocking=False)):
            try:
                try:
                    self.process.stdin.write('{"operation":"close"}\n')
                    self.process.stdin.flush()
                    self.process.wait(timeout=2)
                    return
                except (OSError, ValueError, subprocess.TimeoutExpired):
                    pass
            finally:
                self._request_lock.release()
        # Every worker is its own POSIX session. Killing just the parent loses
        # ownership of llama/encoder children, including children holding pipes.
        if os.name == "posix":
            # start_new_session guarantees this group ID, even if the parent
            # has already exited and only its descendants remain.
            process_group = self.process.pid
            self.process.poll()
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(process_group, sig)
                except ProcessLookupError:
                    self.process.wait(timeout=1)
                    return
                except PermissionError:
                    # macOS can transiently report EPERM for an exiting group.
                    # Only ESRCH below proves cleanup; EPERM is never success.
                    pass
                deadline = time.monotonic() + 0.5
                while time.monotonic() < deadline:
                    self.process.poll()  # Reap the parent before testing its group.
                    try:
                        os.killpg(process_group, 0)
                    except ProcessLookupError:
                        self.process.wait(timeout=1)
                        return
                    except PermissionError:
                        pass
                    time.sleep(0.01)
            raise WorkerUnavailableError()
        # taskkill must see the living parent to enumerate its descendants;
        # do not terminate the parent first. The command itself is also bounded.
        if self.process.poll() is None:
            try:
                result = subprocess.run(
                    ["taskkill", "/PID", str(self.process.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=2, check=False,
                )
                if result.returncode != 0:
                    # The Worker may exit between poll() and taskkill; taskkill
                    # then fails although the stop already happened.
                    self.process.wait(timeout=1)
                    return
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                raise WorkerUnavailableError() from None


class SubprocessWorkerFactory:
    def __init__(
        self,
        config_path,
        fake=False,
        expected_identity=None,
        startup_timeout=None,
        ipc_timeout=None,
    ):
        self.config_path = Path(config_path)
        self.fake = fake
        self.expected_identity = expected_identity
        self.startup_timeout = startup_timeout
        self.ipc_timeout = ipc_timeout
        self.start_count = 0

    def start(self, profile):
        self.start_count += 1
        try:
            config = load_config(self.config_path)
        except Exception:
            raise WorkerStartError() from None
        if not self.fake:
            ensure_resources(profile_id=profile)
        command = [
            sys.executable if self.fake else str(config.tts.python_executable),
            "-u",
            str(Path(__file__).resolve()),
            "--worker",
            "--config",
            str(self.config_path),
            "--profile",
            str(profile),
        ]
        if self.fake:
            command.append("--fake-backend")
        try:
            return SubprocessWorker(
                command,
                profile,
                expected_identity=(
                    "fake-local-v1"
                    if self.fake and self.expected_identity is None
                    else self.expected_identity
                ),
                startup_timeout=(
                    config.worker_startup_timeout
                    if self.startup_timeout is None
                    else self.startup_timeout
                ),
                ipc_timeout=(
                    config.worker_ipc_timeout
                    if self.ipc_timeout is None
                    else self.ipc_timeout
                ),
            )
        except WorkerStartError:
            raise
        except Exception:
            raise WorkerStartError() from None


class InProcessWorkerFactory:
    def __init__(self, catalog, profile="fake-local-v1"):
        self.catalog = catalog
        self.profile = profile
        self.start_count = 0
        self.workers = []

    def start(self, profile):
        self.start_count += 1
        worker = FakeBackend(self.catalog, self.profile)
        self.workers.append(worker)
        return worker


def _load_backend(config_path, fake, profile_id):
    config = load_config(config_path)
    catalog = VoiceCatalog(config.voice_catalog, config.root)
    if fake:
        return FakeBackend(catalog, profile_id)
    registry = config.load_model_registry()
    profile = registry.active_profile(profile_id) if registry else None
    if registry is not None:
        catalog.validate_references(profile.capabilities)
        registry.verify_profile(
            profile_id,
            catalog=catalog,
            available_vram_gb=config.minimum_vram_gb,
        )
        identity = registry.profile_identity(
            profile,
            sha256_file(catalog.path),
            catalog.reference_audio_digest(profile.capabilities),
        )
    else:
        identity = profile_id
    backend = QwenBackend(config, catalog, profile=profile)
    backend.profile = identity
    return backend


def _ready_response(backend):
    return {
        "ok": True,
        "event": "ready",
        "profile": backend.profile,
    }


def _worker_main(config_path, fake, profile_id):
    # Windows pipes default to the ANSI code page (GBK on Chinese systems), but
    # the Agent always reads and writes UTF-8; chapter text would be corrupted.
    for stream in (sys.stdin, sys.stdout):
        stream.reconfigure(encoding="utf-8")
    # Python redirect_stdout cannot catch native libraries writing to fd 1.
    # Keep a dedicated IPC descriptor and send all backend output to stderr.
    ipc = os.fdopen(os.dup(sys.stdout.fileno()), "w", encoding="utf-8", buffering=1)
    os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
    sys.stdout = ipc
    try:
        with redirect_stdout(sys.stderr):
            backend = _load_backend(config_path, fake, profile_id)
    except Exception:
        sys.stdout.write('{"ok":false,"code":"worker_start_failed"}\n')
        sys.stdout.flush()
        return 2
    backend_closed = False
    try:
        sys.stdout.write(json.dumps(_ready_response(backend)) + "\n")
        sys.stdout.flush()
        while True:
            line = sys.stdin.readline(MAX_LINE + 1)
            if not line:
                break
            try:
                if len(line.encode("utf-8")) > MAX_LINE:
                    return 2
                value = strict_json_loads(line)
                if not isinstance(value, dict):
                    raise ValueError()
                operation = value.get("operation")
                if operation == "close":
                    try:
                        with redirect_stdout(sys.stderr):
                            backend.close()
                        backend_closed = True
                        output = {"ok": True, "result": None}
                    except Exception:
                        output = {"ok": False, "code": "worker_close_failed"}
                    encoded = json.dumps(output, ensure_ascii=False, allow_nan=False)
                    sys.stdout.write(encoded + "\n")
                    sys.stdout.flush()
                    return 0 if output["ok"] else 2
                request = value.get("request")
                with redirect_stdout(sys.stderr):
                    if operation == "analyze":
                        request = analysis_request(request)
                        result = backend.analyze(request)
                    elif operation == "synthesize":
                        request = synthesis_request(request)
                        audio = backend.synthesize(request)
                        if len(audio) > 16 * 1024 * 1024:
                            raise ValueError()
                        result = base64.b64encode(audio).decode("ascii")
                    else:
                        raise ValueError()
                output = {"ok": True, "result": result}
            except NovelAudioError as error:
                output = {"ok": False, "code": error.code}
            except Exception:
                output = {"ok": False, "code": "worker_failed"}
            encoded = json.dumps(output, ensure_ascii=False, allow_nan=False)
            if len(encoded.encode("utf-8")) > MAX_LINE:
                return 2
            sys.stdout.write(encoded + "\n")
            sys.stdout.flush()
    finally:
        if not backend_closed:
            try:
                with redirect_stdout(sys.stderr):
                    backend.close()
            except Exception:
                pass
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--worker" not in argv:
        return 2
    try:
        config_path = argv[argv.index("--config") + 1]
        profile_id = argv[argv.index("--profile") + 1]
    except (ValueError, IndexError):
        return 2
    return _worker_main(config_path, "--fake-backend" in argv, profile_id)


if __name__ == "__main__":
    raise SystemExit(main())
