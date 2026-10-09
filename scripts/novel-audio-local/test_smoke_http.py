"""FAKE HTTP + FAKE ffprobe contract tests; NOT a real-model acceptance run."""

import contextlib
import importlib
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.novel_audio_server.protocol import analysis_request, synthesis_request
from test_support import requires_symlinks


class FakeHttpAgent:
    """Test-only socket server: validates the existing v1 request contracts."""

    identity = "test-only-profile"
    token = "test-only-secret-" + "x" * 32
    audio = b"OggS" + b"\0" * 128

    def __init__(self, voice_count=1):
        self.voice_count = voice_count
        self.requests = []
        self.released = []
        self.lease = None
        self.acquires = 0
        self.rejects = 0
        self.automatic = 0
        self.fault = None
        self.fault_route = "/v1/voices/preview"
        self.leak_auto = False
        self.release_fails = False
        self.empty_match = False
        self.validate_errors = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def handle(self):
                # Windows may report a timed-out client disconnect during the
                # final handler flush, outside dispatch's exception handler.
                try:
                    super().handle()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

            def log_message(self, *_):
                pass

            def do_GET(self):
                self.dispatch()

            def do_POST(self):
                self.dispatch()

            def dispatch(self):
                try:
                    owner.dispatch(self)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
                except Exception:
                    owner.validate_errors.append("invalid_request")
                    self.send_error(400)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(
            target=lambda: self.server.serve_forever(poll_interval=0.01), daemon=True
        )
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def dispatch(self, handler):
        route = handler.path
        lease = handler.headers.get("X-NovelAudio-Lease")
        size = int(handler.headers.get("Content-Length", 0))
        body = json.loads(handler.rfile.read(size)) if size else None
        self.requests.append((handler.command, route, lease, body))
        if handler.headers.get("Authorization") != "Bearer " + self.token:
            return self.reply(handler, 401, {})
        if route == self.fault_route and self.fault:
            if self.fault == "redirect":
                return self.reply(handler, 302, {}, {"Location": "http://example.invalid/secret"})
            if self.fault in {"oversize", "oversize_stream"}:
                data = self.audio * 32
                headers = {"Content-Type": "audio/ogg", "X-TTS-Profile": self.identity}
                return self.reply(handler, 200, data, headers,
                                  length=self.fault != "oversize_stream")
            if self.fault == "slow_body":
                handler.send_response(200)
                handler.send_header("Content-Type", "audio/ogg")
                handler.send_header("Content-Length", "132")
                handler.end_headers()
                for _ in range(132):
                    handler.wfile.write(b"x")
                    handler.wfile.flush()
                    time.sleep(0.02)
                return
            if self.fault == "failure":
                return self.reply(handler, 503, {
                    "error": {"code": self.token, "message": "正文 prompt /private/model"}
                })
            if self.fault == "bad_analysis":
                return self.reply(handler, 200, {"assignments": []})
        metadata = {
            "runtimeProfile": self.identity,
            "runtimeProfileInfo": {
                "identity": self.identity,
                "profileId": "test-only",
                "capabilities": ["chapter-analysis", "speech-synthesis"] + (
                    ["voice-design"] if self.voice_count > 1 else ["voice-clone"]
                ),
            },
        }
        voices = [{
            "voiceAssetId": f"test.voice-{i}",
            "displayName": "测试声线",
            "previewAvailable": True,
        } for i in range(self.voice_count)]
        if route == "/v1/health":
            return self.reply(handler, 200, {
                "status": "ok", "apiVersion": "1", "directorReady": True, "ttsReady": True
            })
        if route == "/v1/runtime/status":
            if self.fault == "wrong_status_identity":
                metadata["runtimeProfileInfo"]["identity"] = "wrong"
            return self.reply(handler, 200, {
                **metadata, "state": "ready" if self.lease else "idle",
                "activeLease": self.lease is not None,
            })
        if route == "/v1/voices":
            return self.reply(handler, 200, {"voices": voices})
        if route == "/v1/voices/match":
            assert isinstance(body["voicePersona"]["traits"], list)
            assert body["alreadyUsedVoiceIds"] == []
            return self.reply(handler, 200, {"candidates": [] if self.empty_match else voices})
        if route == "/v1/runtime/acquire":
            assert body["sessionId"] and body["purpose"] == "auto_prefetch"
            assert body["expectedChapterCount"] == 1
            if self.lease:
                self.rejects += 1
                return self.reply(handler, 429, {"error": {"code": "busy"}})
            self.acquires += 1
            self.lease = f"test-lease-{self.acquires}"
            if self.fault == "wrong_acquire_identity":
                metadata["runtimeProfileInfo"]["identity"] = "wrong"
            return self.reply(handler, 200, {**metadata, "leaseId": self.lease})
        if route == "/v1/runtime/release":
            self.released.append(lease)
            if self.release_fails:
                return self.reply(handler, 503, {"secret": self.token})
            assert lease == self.lease
            self.lease = None
            return self.reply(handler, 200, {"state": "idle"})
        if route == "/v1/chapter/analyze":
            request = analysis_request(body)
            result = {
                "assignments": [
                    {"unitId": unit["unitId"], "speakerId": "narrator"}
                    for unit in request["units"]
                ],
                "newCharacters": [], "aliasUpdates": [],
            }
        elif route in {"/v1/voices/preview", "/v1/tts/synthesize"}:
            request = synthesis_request(body)
            assert request["voiceAssetId"] in {v["voiceAssetId"] for v in voices}
            result = self.audio if self.fault != "bad_audio" else b"not an ogg"
        else:
            return self.reply(handler, 404, {})
        if lease:
            assert lease == self.lease
        else:
            assert self.lease is None
            self.automatic += 1
            if self.leak_auto:
                self.lease = "automatic-leak"
        profile = "wrong" if self.fault == "wrong_audio_identity" else self.identity
        self.reply(handler, 200, result, {
            "Content-Type": "audio/ogg", "X-TTS-Profile": profile,
        } if isinstance(result, bytes) else None)

    @staticmethod
    def reply(handler, status, body, headers=None, length=True):
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        handler.send_response(status)
        headers = headers or {"Content-Type": "application/json"}
        for key, value in headers.items():
            handler.send_header(key, value)
        if length:
            handler.send_header("Content-Length", str(len(raw)))
        handler.end_headers()
        handler.wfile.write(raw)


class FakeProbeProcess:
    """A subprocess double, never an encoder or a model substitute in production."""

    def __init__(self, payload, returncode=0):
        self.stdout = io.BytesIO(payload)
        self.returncode = returncode
        self.killed = False

    def wait(self, timeout=None):
        return self.returncode

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9


class SmokeHttpTest(unittest.TestCase):
    def setUp(self):
        # Keep the initial red phase an assertion failure, not an import error.
        self.assertTrue(Path(__file__).with_name("smoke_http.py").is_file(),
                        "run_smoke implementation is missing")
        self.smoke = importlib.import_module("scripts.novel-audio-local.smoke_http")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.agent = FakeHttpAgent()
        self.addCleanup(self.agent.close)
        self.config = self.root / "config.json"
        self.token_path = self.root / "state" / "agent-token"
        self.token_path.parent.mkdir()
        self.token_path.write_text(self.agent.token, encoding="ascii")
        self.write_config()
        self.probe_payload = json.dumps({
            "streams": [{"codec_name": "opus", "channels": 1, "sample_rate": "48000",
                         }],
            "format": {"duration": "0.25"},
        }).encode()
        self.probe_calls = []

    def write_config(self, **changes):
        data = {
            "host": "127.0.0.1", "port": self.agent.server.server_port,
            "tokenFile": "state/agent-token", "text": {}, "tts": {"ffprobe": "ffprobe"},
        }
        data.update(changes)
        self.config.write_text(json.dumps(data), encoding="utf-8")

    def fake_probe(self, command, **kwargs):
        self.probe_calls.append((command, kwargs))
        self.assertEqual(str((self.root / "ffprobe").resolve()), command[0])
        self.assertNotIn(self.agent.token, repr(command))
        self.assertEqual(subprocess.DEVNULL, kwargs["stderr"])
        self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])
        self.assertFalse(kwargs.get("shell", False))
        self.assertEqual(
            "stream=codec_name,channels,sample_rate:format=duration",
            command[command.index("-show_entries") + 1],
        )
        self.assertEqual("ogg", command[command.index("-f") + 1])
        self.assertEqual("file", command[command.index("-protocol_whitelist") + 1])
        self.assertTrue(Path(command[-1]).read_bytes().startswith(b"OggS"))
        return FakeProbeProcess(self.probe_payload)

    def run_smoke(self, probe=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), \
                patch.object(self.smoke.subprocess, "Popen",
                             side_effect=probe or self.fake_probe):
            code = self.smoke.run_smoke(self.config)
        self.assertEqual("", err.getvalue())
        self.assertEqual([], self.agent.validate_errors)
        text = out.getvalue()
        for private in (self.agent.token, str(self.root), "正文", "prompt", "/private/model"):
            self.assertNotIn(private, text)
        report = [json.loads(line) for line in text.splitlines()]
        self.assertTrue(report)
        return code, report

    def assert_failure(self, code, report, error):
        self.assertEqual(2, code)
        self.assertEqual("failed", report[-1]["status"])
        self.assertIn(error, [entry["errorCode"] for entry in report])

    def test_two_rounds_and_automatic_reclaim_with_single_base_voice(self):
        code, report = self.run_smoke()
        self.assertEqual(0, code)
        self.assertEqual("passed", report[-1]["status"])
        self.assertEqual(2, self.agent.acquires)
        self.assertEqual(2, self.agent.rejects)
        self.assertEqual(["test-lease-1", "test-lease-2"], self.agent.released)
        self.assertIsNone(self.agent.lease)
        self.assertEqual(3, self.agent.automatic)
        self.assertEqual(6, len(self.probe_calls))
        routes = {item[1] for item in self.agent.requests}
        self.assertEqual({
            "/v1/health", "/v1/voices", "/v1/voices/match", "/v1/chapter/analyze",
            "/v1/voices/preview", "/v1/tts/synthesize", "/v1/runtime/status",
            "/v1/runtime/acquire", "/v1/runtime/release",
        }, routes)
        # Every unleased generation is followed immediately by an idle check.
        for index, (_, route, lease, _) in enumerate(self.agent.requests):
            if lease is None and route in {
                "/v1/chapter/analyze", "/v1/voices/preview", "/v1/tts/synthesize"
            }:
                self.assertEqual("/v1/runtime/status", self.agent.requests[index + 1][1])

    def test_expected_disconnect_during_handler_flush_is_quiet(self):
        handler = object.__new__(self.agent.server.RequestHandlerClass)
        for error in (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            with self.subTest(error=error.__name__), patch.object(BaseHTTPRequestHandler, "handle", side_effect=error()):
                self.agent.server.RequestHandlerClass.handle(handler)

    def test_base_narrator_only_allows_empty_match_candidates(self):
        # Real VoiceCatalog.match excludes narrator assets.
        self.agent.empty_match = True
        self.assertEqual(0, self.run_smoke()[0])
        self.assertEqual(6, len(self.probe_calls))

    def test_voice_design_can_exercise_three_distinct_voices(self):
        self.agent.voice_count = 3
        self.assertEqual(0, self.run_smoke()[0])
        ids = {body["voiceAssetId"] for _, route, _, body in self.agent.requests
               if route == "/v1/tts/synthesize"}
        self.assertEqual(3, len(ids))
        self.assertEqual(10, len(self.probe_calls))

    def test_always_release_on_generation_and_probe_failure(self):
        for fault in ("failure", "bad_audio", "probe"):
            with self.subTest(fault=fault):
                self.agent.fault = None if fault == "probe" else fault
                probe = (lambda *a, **k: (_ for _ in ()).throw(
                    OSError(self.agent.token))) if fault == "probe" else None
                before = len(self.agent.released)
                code, _ = self.run_smoke(probe)
                self.assertEqual(2, code)
                self.assertEqual(before + 1, len(self.agent.released))
                self.assertIsNone(self.agent.lease)

    def test_wrong_identity_is_rejected_and_acquired_lease_is_released(self):
        for fault in ("wrong_acquire_identity", "wrong_audio_identity", "wrong_status_identity"):
            with self.subTest(fault=fault):
                self.agent.fault = fault
                before = len(self.agent.released)
                self.assert_failure(*self.run_smoke(), "identity_mismatch")
                self.assertIsNone(self.agent.lease)
                self.assertEqual(before + (fault != "wrong_status_identity"),
                                 len(self.agent.released))

    def test_redirect_is_not_followed_and_still_releases(self):
        self.agent.fault = "redirect"
        self.assert_failure(*self.run_smoke(), "redirect_refused")
        self.assertEqual(["test-lease-1"], self.agent.released)
        self.assertNotIn("/secret", [r[1] for r in self.agent.requests])

    def test_oversize_with_and_without_content_length_is_bounded(self):
        for fault in ("oversize", "oversize_stream"):
            with self.subTest(fault=fault), patch.object(self.smoke, "MAX_AUDIO", 1024):
                self.agent.fault = fault
                self.assert_failure(*self.run_smoke(), "response_too_large")
                self.assertIsNone(self.agent.lease)
        self.assertEqual([], self.probe_calls)

    def test_total_http_deadline_stops_a_trickling_body(self):
        self.agent.fault = "slow_body"
        with patch.object(self.smoke, "HTTP_TIMEOUT", 0.15):
            started = time.monotonic()
            self.assertEqual(2, self.run_smoke()[0])
            self.assertLess(time.monotonic() - started, 2.0)
        self.assertIsNone(self.agent.lease)

    def test_fixed_safe_output_and_probe_whitelist(self):
        code, report = self.run_smoke()
        self.assertEqual(0, code)
        for entry in report:
            self.assertLessEqual(set(entry), {"step", "status", "errorCode", "audio"})
            self.assertRegex(entry["step"], r"^[a-z0-9_.]+$")
            self.assertIn(entry["status"], {"passed", "failed"})
            if "audio" in entry:
                self.assertEqual({
                    "codec": "opus", "channels": 1, "sampleRate": 48000, "duration": 0.25,
                }, entry["audio"])
        self.agent.fault = "failure"
        _, failed = self.run_smoke()
        self.assertIn({
            "step": "round1.preview", "status": "failed", "errorCode": "http_error",
        }, failed)

    def test_missing_token_never_creates_token_or_makes_requests(self):
        self.token_path.unlink()
        self.assert_failure(*self.run_smoke(), "token_unavailable")
        self.assertFalse(self.token_path.exists())
        self.assertEqual([], self.agent.requests)
        self.assertFalse((self.root / "diagnostics").exists())

    def test_invalid_and_oversized_token_are_read_only(self):
        for value in ("bad", "x" * 5000):
            self.token_path.write_text(value)
            self.assert_failure(*self.run_smoke(), "token_unavailable")
            self.assertEqual(value, self.token_path.read_text())
        self.assertEqual([], self.agent.requests)

    def test_automatic_lease_leak_is_detected_without_releasing_an_unknown_lease(self):
        self.agent.leak_auto = True
        self.assert_failure(*self.run_smoke(), "runtime_not_idle")
        self.assertEqual(["test-lease-1", "test-lease-2"], self.agent.released)

    def test_release_failure_fails_the_run(self):
        self.agent.release_fails = True
        self.assert_failure(*self.run_smoke(), "release_failed")
        self.assertEqual(["test-lease-1"], self.agent.released)

    def test_second_acquire_requires_exactly_429(self):
        dispatch = self.agent.dispatch

        def wrong_busy_status(handler):
            if handler.path == "/v1/runtime/acquire" and self.agent.lease:
                return self.agent.reply(handler, 503, {})
            return dispatch(handler)

        self.agent.dispatch = wrong_busy_status
        self.assert_failure(*self.run_smoke(), "second_acquire_not_busy")
        self.assertEqual(["test-lease-1"], self.agent.released)

    def test_keyboard_interrupt_releases_before_returning_safe_error(self):
        def interrupt(*args, **kwargs):
            raise KeyboardInterrupt()

        self.assert_failure(*self.run_smoke(interrupt), "interrupted")
        self.assertEqual(["test-lease-1"], self.agent.released)
        self.assertIsNone(self.agent.lease)

    def test_actual_v1_dispatcher_and_runtime_with_fake_backend_and_probe(self):
        """FAKE model/probe, but use production API, HTTP server and lease manager."""
        from scripts.novel_audio_server.api import NovelAudioApi
        from scripts.novel_audio_server.http import create_server
        from scripts.novel_audio_server.runtime import RuntimeManager

        profile = self.agent.identity
        fixture = self.agent.audio
        workers = []

        class Backend:
            ready = True

            def __init__(self):
                self.profile = profile
                self.closed = False

            def runtime_metadata(self):
                return {
                    "profileId": "test-only", "identity": profile,
                    "capabilities": ["chapter-analysis", "speech-synthesis", "voice-clone"],
                    "minVramGb": 24.0, "hardware": {"status": "unknown"},
                }

            def analyze(self, request):
                return {
                    "assignments": [{"unitId": u["unitId"], "speakerId": "narrator"}
                                    for u in request["units"]],
                    "newCharacters": [], "aliasUpdates": [],
                }

            def synthesize(self, request):
                return fixture

            def close(self):
                self.closed = True

        class Factory:
            def start(self, profile_id):
                worker = Backend()
                workers.append(worker)
                return worker

        class Catalog:
            def public_voices(self, capabilities):
                return [{"voiceAssetId": "test.narrator", "previewAvailable": True}]

            def match(self, *args):
                return []

            def contains(self, voice, capabilities):
                return voice == "test.narrator"

        runtime = RuntimeManager(Factory())
        api = NovelAudioApi(self.agent.token, Backend(), Catalog(), runtime)
        server = create_server("127.0.0.1", 0, api)
        thread = threading.Thread(
            target=lambda: server.serve_forever(poll_interval=0.01), daemon=True
        )
        thread.start()
        try:
            self.write_config(port=server.server_port)
            self.assertEqual(0, self.run_smoke()[0])
            self.assertEqual(5, len(workers))
            self.assertTrue(all(worker.closed for worker in workers))
            self.assertEqual(0, runtime.status().active_lease_count)
            self.assertEqual(6, len(self.probe_calls))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
            api.close()

    def test_unique_diagnostics_directories_and_fixed_exclusive_filenames(self):
        self.assertEqual(0, self.run_smoke()[0])
        first = next((self.root / "diagnostics").iterdir())
        snapshot = {p.name: p.read_bytes() for p in first.iterdir()}
        self.assertEqual({
            "round1-preview.ogg", "round1-synthesize-1.ogg",
            "round2-preview.ogg", "round2-synthesize-1.ogg",
            "automatic-preview.ogg", "automatic-synthesize.ogg",
        }, set(snapshot))
        self.assertEqual(0, self.run_smoke()[0])
        self.assertEqual(2, len(list((self.root / "diagnostics").iterdir())))
        self.assertEqual(snapshot, {p.name: p.read_bytes() for p in first.iterdir()})

    @requires_symlinks
    def test_diagnostics_symlink_is_rejected(self):
        target = self.root / "other"
        target.mkdir()
        (self.root / "diagnostics").symlink_to(target, target_is_directory=True)
        self.assert_failure(*self.run_smoke(), "diagnostics_failed")
        self.assertEqual([], list(target.iterdir()))

    def test_probe_invalid_numbers_and_unlisted_codec_never_escape(self):
        for field, value in (("duration", "NaN"), ("duration", "Infinity"),
                             ("duration", "0"), ("codec_name", self.agent.token),
                             ("channels", True), ("sample_rate", "1")):
            with self.subTest(field=field, value=value if field != "codec_name" else "invalid"):
                payload = {"streams": [{"codec_name": "opus", "channels": 1,
                                        "sample_rate": "48000"}],
                           "format": {"duration": "0.25"}}
                (payload["format"] if field == "duration" else payload["streams"][0])[field] = value
                self.probe_payload = json.dumps(payload).encode()
                self.assert_failure(*self.run_smoke(), "probe_failed")
                self.assertIsNone(self.agent.lease)

    def test_probe_unlisted_fields_are_not_reported(self):
        self.probe_payload = json.dumps({
            "streams": [{"codec_name": "opus", "channels": 1,
                         "sample_rate": "48000", "tags": {"secret": self.agent.token}}],
            "programs": [],
            "format": {"duration": "0.25", "filename": "/private/model"},
        }).encode()
        code, report = self.run_smoke()
        self.assertEqual(0, code)
        for entry in report:
            if "audio" in entry:
                self.assertEqual({"codec", "channels", "sampleRate", "duration"},
                                 set(entry["audio"]))

    def test_automatic_transport_failure_still_checks_idle(self):
        dispatch = self.agent.dispatch

        def fail_only_automatic(handler):
            if handler.path == "/v1/voices/preview" and not handler.headers.get("X-NovelAudio-Lease"):
                self.agent.fault = "redirect"
            return dispatch(handler)

        self.agent.dispatch = fail_only_automatic
        self.assert_failure(*self.run_smoke(), "redirect_refused")
        self.assertEqual("/v1/runtime/status", self.agent.requests[-1][1])
        self.assertIsNone(self.agent.lease)

    def test_probe_output_size_exit_and_timeout_fail_closed(self):
        for mode in ("size", "exit", "timeout"):
            with self.subTest(mode=mode):
                child = FakeProbeProcess(
                    b"x" * (self.smoke.MAX_PROBE_OUTPUT + 1) if mode == "size"
                    else self.probe_payload, returncode=1 if mode == "exit" else 0
                )
                if mode == "timeout":
                    child.wait = lambda timeout=None: (_ for _ in ()).throw(
                        subprocess.TimeoutExpired("private-command", timeout)
                    )
                self.assert_failure(*self.run_smoke(lambda *a, **kw: child), "probe_failed")
                self.assertTrue(child.stdout.closed)
                self.assertIsNone(self.agent.lease)

    def test_exclusive_file_open_never_overwrites_existing_audio(self):
        output = self.root / "existing"
        output.mkdir()
        audio = output / "round1-preview.ogg"
        audio.write_bytes(b"previous")
        with patch.object(self.smoke, "_diagnostics", return_value=output):
            self.assert_failure(*self.run_smoke(), "diagnostics_failed")
        self.assertEqual(b"previous", audio.read_bytes())
        self.assertIsNone(self.agent.lease)

    def test_main_definition_does_not_import_server_or_start_an_agent(self):
        import inspect
        import importlib.util

        before = set(sys.modules)
        spec = importlib.util.spec_from_file_location(
            "stage7b_smoke_isolated", Path(__file__).with_name("smoke_http.py")
        )
        module = importlib.util.module_from_spec(spec)
        with patch.object(self.smoke.http.client, "HTTPConnection") as connection, \
                patch.object(self.smoke.subprocess, "Popen") as process:
            spec.loader.exec_module(module)
        connection.assert_not_called()
        process.assert_not_called()
        self.assertFalse(any(name.rsplit(".", 1)[-1] in {"server", "agent", "qwen_backend"}
                             for name in set(sys.modules) - before))
        self.assertEqual(["config_path"], list(inspect.signature(module.run_smoke).parameters))
        with patch.object(module, "run_smoke", return_value=2) as smoke:
            self.assertEqual(2, module.main(self.config))
        smoke.assert_called_once_with(self.config)

    def test_proxy_environment_does_not_change_loopback_destination(self):
        with patch.dict(os.environ, {"HTTP_PROXY": "http://example.invalid:9",
                                     "http_proxy": "http://example.invalid:9",
                                     "ALL_PROXY": "socks5://example.invalid:9",
                                     "NO_PROXY": "", "no_proxy": ""}):
            self.write_config(host="0.0.0.0", allowLan=True)
            self.assertEqual(0, self.run_smoke()[0])

    def test_malformed_analysis_is_rejected_and_released(self):
        self.agent.fault_route = "/v1/chapter/analyze"
        self.agent.fault = "bad_analysis"
        self.assert_failure(*self.run_smoke(), "invalid_response")
        self.assertIsNone(self.agent.lease)


if __name__ == "__main__":
    print("FAKE HTTP + FAKE ffprobe: contract tests only")
    unittest.main()
