"""loopback HTTP、命令入口、硬期限；不请求云服务。"""
import contextlib
import http.client
import io
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import server
import test_cloud
import worker as worker_module
from bridge import BridgeApi, BridgeConfig, UsageGuard
from protocol import BridgeError, CloudTimeoutError
from test_cloud import Response, response, AUDIO_URL, wav_fixture
from test_boundaries import analysis, synthesis
from test_bridge import FakeCloud
from worker import CloudWorker, run_bounded_process


class HttpTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.cloud = FakeCloud()
        self.api = BridgeApi(BridgeConfig(dashscope_api_key="secret", bridge_token="local"),
                             self.cloud, UsageGuard(Path(directory.name) / "state.db"), True)
        self.server = server.create_server("127.0.0.1", 0, self.api)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        def stop():
            self.server.shutdown()
            self.server.server_close()
            thread.join(2)
        self.addCleanup(stop)

    def request(self, path, body=None, token="Bearer local", raw=None):
        with contextlib.closing(http.client.HTTPConnection(*self.server.server_address, timeout=3)) as conn:
            headers = {"Authorization": token}
            if body is not None or raw is not None:
                headers["Content-Type"] = "application/json"
                conn.request("POST", path, body=raw if raw is not None else json.dumps(body), headers=headers)
            else:
                conn.request("GET", path, headers=headers)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def test_all_six_endpoints_over_http(self):
        self.assertEqual(200, self.request("/v1/health")[0])
        self.assertEqual(7, len(json.loads(self.request("/v1/voices")[2])["voices"]))
        self.assertEqual(200, self.request("/v1/chapter/analyze", analysis())[0])
        self.assertEqual(200, self.request("/v1/voices/match",
                                          {"voicePersona": {"traits": []}, "alreadyUsedVoiceIds": []})[0])
        for route in ("/v1/voices/preview", "/v1/tts/synthesize"):
            status, headers, audio = self.request(route, synthesis())
            self.assertEqual(200, status)
            self.assertEqual("audio/ogg", headers["Content-Type"])
            self.assertIn("X-TTS-Profile", headers)
            self.assertEqual(b"converted-ogg", audio)

    def test_auth_precedes_json_and_no_body_log(self):
        with contextlib.redirect_stderr(io.StringIO()) as output:
            self.assertEqual(401, self.request("/v1/chapter/analyze", token="wrong", raw=b"\xff")[0])
        self.assertEqual("", output.getvalue())
        self.assertEqual([], self.cloud.analysis_requests)

    def test_rejects_malformed_json_and_utf8(self):
        for raw in (b"\xff", b'{"units":[],"units":[]}', b'{"speed":NaN}',
                    b'{"text":"\\ud800"}'):
            self.assertEqual(400, self.request("/v1/tts/synthesize", raw=raw)[0])

    def test_rejects_ambiguous_and_oversize_framing_without_body(self):
        for headers, status in (
            (b"Content-Length: 2\r\nContent-Length: 2\r\n", 400),
            (b"Transfer-Encoding: chunked\r\n", 400),
            (b"Content-Length: 2097153\r\n", 413),
            (b"Content-Length: -1\r\n", 400),
        ):
            with socket.create_connection(self.server.server_address, timeout=3) as sock:
                sock.sendall(b"POST /v1/chapter/analyze HTTP/1.1\r\nHost: localhost\r\n"
                             b"Authorization: Bearer local\r\nContent-Type: application/json\r\n"
                             + headers + b"\r\n")
                self.assertIn(f" {status} ".encode(), sock.recv(1024))

    def test_public_bind_is_refused(self):
        with self.assertRaises(ValueError):
            server.create_server("0.0.0.0", 0, self.api)

    def test_tts_diagnostic_survives_http_without_raw_response(self):
        client, _ = test_cloud.CloudTest().client([response({"output": {"audio": {}}})])
        self.api.cloud = client
        status, _, body = self.request("/v1/tts/synthesize", synthesis())
        self.assertEqual(502, status)
        self.assertEqual({"error": {"code": "tts_missing_audio_url"}}, json.loads(body))


class CliTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "novel-audio.local.env"

    def run_main(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            result = server.main(["--config", str(self.path), *args])
        return result, out.getvalue()

    def test_init_never_overwrites_user_key(self):
        result, _ = self.run_main("--init")
        self.assertEqual(0, result)
        self.assertIn("DASHSCOPE_API_KEY=", self.path.read_text())
        self.path.write_text("DASHSCOPE_API_KEY=secret-for-test\n")
        self.assertEqual(0, self.run_main("--init")[0])
        self.assertIn("secret-for-test", self.path.read_text())

    def test_check_has_no_network_and_never_displays_key_or_token(self):
        self.path.write_text("DASHSCOPE_API_KEY=sk-secret.test.part.signature\nBRIDGE_TOKEN=local-secret\n")
        with patch.object(CloudWorker, "analyze", side_effect=AssertionError("network")), \
                patch.object(CloudWorker, "synthesize", side_effect=AssertionError("network")):
            code, output = self.run_main("--check")
        self.assertEqual(0, code)
        self.assertNotIn("secret", output)
        connection_path = self.path.parent / "novel-audio.local.connection.json"
        connection = json.loads(connection_path.read_text())
        self.assertEqual({"baseUrl": "http://127.0.0.1:8787", "token": "local-secret",
                          "allowInsecureHttp": True}, connection)
        self.assertNotIn("sk-secret.test.part.signature", connection_path.read_text())
        if os.name == "posix":
            self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
            self.assertEqual(0o600, connection_path.stat().st_mode & 0o777)

    def test_serve_and_smoke_require_explicit_console_confirmation(self):
        self.path.write_text("DASHSCOPE_API_KEY=secret-for-test\n")
        for mode in ("--serve", "--smoke", "--smoke-tts"):
            code, output = self.run_main(mode)
            self.assertNotEqual(0, code)
            self.assertIn("免费额度用完即停", output)
            self.assertNotIn("secret", output)

    def test_missing_key_fails_before_cloud(self):
        self.run_main("--init")
        code, output = self.run_main("--smoke", "--free-quota-confirmed")
        self.assertNotEqual(0, code)
        self.assertIn("API Key", output)

    def test_smoke_uses_original_short_text_and_distinct_voices(self):
        cloud = FakeCloud()
        def analyze_request(body):
            return {
                "assignments": [{"unitId": "demo-u1", "speakerId": "narrator"},
                                {"unitId": "demo-u2", "speakerId": "demo-char-a"},
                                {"unitId": "demo-u3", "speakerId": "demo-char-b"}],
                "newCharacters": [], "aliasUpdates": [],
            }
        cloud.analyze = analyze_request
        api = BridgeApi(BridgeConfig(dashscope_api_key="key", bridge_token="local"),
                        cloud, UsageGuard(self.path.parent / "state.db"), True)
        with contextlib.redirect_stdout(io.StringIO()):
            result = server.smoke(api, self.path.parent / "audio")
        self.assertEqual(0, result)
        self.assertEqual(3, len(cloud.tts_requests))
        self.assertEqual(3, len({request["voice"] for request in cloud.tts_requests}))
        self.assertLess(sum(len(request["text"]) for request in cloud.tts_requests), 200)
        self.assertEqual(3, len(list((self.path.parent / "audio").glob("*.ogg"))))

    def test_smoke_reports_stage_and_stops_after_first_tts_failure(self):
        cloud = FakeCloud()
        cloud.analysis_response = {
            "assignments": [{"unitId": "demo-u1", "speakerId": "narrator"},
                            {"unitId": "demo-u2", "speakerId": "demo-char-a"},
                            {"unitId": "demo-u3", "speakerId": "demo-char-b"}],
            "newCharacters": [], "aliasUpdates": [],
        }
        client, transport = test_cloud.CloudTest().client([response({"output": {"audio": {}}})])
        cloud.synthesize = client.synthesize
        api = BridgeApi(BridgeConfig(dashscope_api_key="key", bridge_token="local"),
                        cloud, UsageGuard(self.path.parent / "state.db"), True)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = server.smoke(api, self.path.parent / "audio")
        self.assertEqual(2, result)
        self.assertIn("tts_missing_audio_url", output.getvalue())
        self.assertEqual(1, len(transport.requests))
        self.assertEqual(1, len(cloud.analysis_requests))
        self.assertEqual([], list((self.path.parent / "audio").glob("*.ogg")))

    def test_single_tts_cli_saves_one_audio_without_analysis(self):
        self.path.write_text("DASHSCOPE_API_KEY=fake-key\n")
        with patch.object(CloudWorker, "analyze", side_effect=AssertionError("unexpected analysis")), \
                patch.object(CloudWorker, "synthesize", return_value=b"offline-audio") as tts, \
                patch.object(CloudWorker, "close") as close:
            code, output = self.run_main("--smoke-tts", "--free-quota-confirmed")
        self.assertEqual(0, code)
        self.assertEqual(1, tts.call_count)
        close.assert_called_once()
        self.assertLessEqual(len(tts.call_args.args[0]["text"]), 30)
        self.assertEqual("Ethan", tts.call_args.args[0]["voice"])
        self.assertNotIn("fake-key", output)
        files = list((self.path.parent / "novel-audio.local.smoke").glob("*/*.ogg"))
        self.assertEqual(1, len(files))
        self.assertEqual(b"offline-audio", files[0].read_bytes())

    def test_single_tts_cli_failure_does_not_retry_or_publish_audio(self):
        from protocol import TtsMissingAudioUrlError
        self.path.write_text("DASHSCOPE_API_KEY=fake-key\n")
        with patch.object(CloudWorker, "analyze", side_effect=AssertionError("unexpected analysis")), \
                patch.object(CloudWorker, "synthesize", side_effect=TtsMissingAudioUrlError()) as tts, \
                patch.object(CloudWorker, "close") as close:
            code, output = self.run_main("--smoke-tts", "--free-quota-confirmed")
        self.assertEqual(2, code)
        self.assertEqual(1, tts.call_count)
        close.assert_called_once()
        self.assertIn("tts_missing_audio_url", output)
        self.assertEqual([], list((self.path.parent / "novel-audio.local.smoke").glob("*/*.ogg")))


class WorkerTest(unittest.TestCase):
    def test_tts_stage_codes_roundtrip_through_worker_exit_only(self):
        cases = (
            ([Response(b"cloud-secret")], "tts_invalid_json"),
            ([Response(b"{}", headers={"Content-Length": "20"})], "tts_invalid_response"),
            ([response({"output": {"audio": {}}})], "tts_missing_audio_url"),
            ([response({"output": {"audio": {"url": "https://secret.invalid/a.wav"}}})],
             "tts_unsafe_audio_url"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}), OSError("secret")],
             "tts_download_failed"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}), Response(b"secret")],
             "tts_invalid_wav"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture())],
             "tts_conversion_failed"),
        )
        for replies, expected in cases:
            with self.subTest(expected=expected):
                client, _ = test_cloud.CloudTest().client(replies, lambda *_, **__: subprocess.CompletedProcess(
                    [], 1, stdout=b"secret"))
                payload = json.dumps({"key": "fake-key", "ffmpeg": "ffmpeg",
                                      "request": {"text": "文", "voice": "Ethan", "speed": 1.0}})
                stdin = io.TextIOWrapper(io.BytesIO(payload.encode()))
                stdout = io.TextIOWrapper(io.BytesIO())
                stderr = io.StringIO()
                with stdin, stdout, patch.object(worker_module.sys, "argv", ["worker", "synthesize"]), \
                        patch.object(worker_module.sys, "stdin", stdin), \
                        contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), \
                        patch.object(worker_module, "BailianClient", return_value=client):
                    exit_code = worker_module.main()
                    stdout.flush()
                    self.assertEqual(b"", stdout.buffer.getvalue())
                self.assertEqual("", stderr.getvalue())
                with self.assertRaises(BridgeError) as caught:
                    run_bounded_process([sys.executable, "-c", f"raise SystemExit({exit_code})"], b"", 3)
                self.assertEqual(expected, caught.exception.code)

    @unittest.skipUnless(os.name == "posix", "POSIX service lifecycle")
    def test_sigint_during_http_generation_reaps_worker_group(self):
        # 子服务只运行离线休眠替身；它另起一个子进程来模拟 ffmpeg。
        program = r'''
import json, os, subprocess, sys
from pathlib import Path
import worker
from bridge import BridgeApi, BridgeConfig, UsageGuard
from server import create_server
original = worker.run_bounded_process
child = """import json, os, subprocess, sys, time
from pathlib import Path
grandchild = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(5)'])
Path(sys.argv[1]).write_text(json.dumps([os.getpid(), grandchild.pid]))
time.sleep(5)
"""
def offline(command, payload, timeout, **kwargs):
    return original([sys.executable, '-c', child, sys.argv[1]], payload, .4, **kwargs)
worker.run_bounded_process = offline
config = BridgeConfig(dashscope_api_key='test', bridge_token='local')
cloud = worker.CloudWorker(config, 'test')
api = BridgeApi(config, cloud, UsageGuard(Path(sys.argv[2])), True)
try:
    with create_server('127.0.0.1', 0, api) as httpd:
        print(httpd.server_address[1], flush=True)
        httpd.serve_forever()
except KeyboardInterrupt:
    pass
'''
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "pids.json"
            process = subprocess.Popen(
                [sys.executable, "-c", program, str(pid_file), str(Path(directory) / "state.db")],
                cwd=Path(__file__).parent, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            pids = []
            try:
                port = int(process.stdout.readline().strip())
                def request():
                    try:
                        with contextlib.closing(http.client.HTTPConnection("127.0.0.1", port, timeout=2)) as conn:
                            conn.request("POST", "/v1/tts/synthesize", body=json.dumps(synthesis()),
                                         headers={"Authorization": "Bearer local", "Content-Type": "application/json"})
                            conn.getresponse().read()
                    except (OSError, http.client.HTTPException):
                        pass
                thread = threading.Thread(target=request, daemon=True)
                thread.start()
                deadline = time.monotonic() + 2
                while not pid_file.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(pid_file.exists(), "offline worker did not start")
                pids = json.loads(pid_file.read_text())
                process.send_signal(signal.SIGINT)
                process.communicate(timeout=2)
                thread.join(2)
                deadline = time.monotonic() + .8
                def alive(pid):
                    result = subprocess.run(["ps", "-p", str(pid), "-o", "stat="],
                                            capture_output=True, timeout=1)
                    state = result.stdout.strip()
                    return bool(state) and not state.startswith(b"Z")
                while any(alive(pid) for pid in pids) and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertFalse(any(alive(pid) for pid in pids),
                                 "worker survived service shutdown and deadline")
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=2)
                for pid in pids:
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_wall_clock_timeout_terminates_process(self):
        started = time.monotonic()
        with self.assertRaises(CloudTimeoutError):
            run_bounded_process([sys.executable, "-c", "import time; time.sleep(3)"], b"", 0.1)
        self.assertLess(time.monotonic() - started, 2)

    def test_worker_never_places_key_in_command_arguments(self):
        worker = CloudWorker(BridgeConfig(dashscope_api_key="hidden-key"), "profile")
        captured = []
        def run(command, payload, timeout, **kwargs):
            captured.append((command, payload, timeout))
            return b'{"assignments":[],"newCharacters":[],"aliasUpdates":[]}'
        with patch("worker.run_bounded_process", side_effect=run):
            worker.analyze(analysis())
        command, payload, timeout = captured[0]
        self.assertNotIn("hidden-key", str(command))
        self.assertIn(b"hidden-key", payload)
        self.assertLessEqual(timeout, 40)


if __name__ == "__main__":
    unittest.main()
