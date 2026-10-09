"""Loopback HTTP coverage; fixture audio is not real-model acceptance evidence."""
import http.client
import json
import threading
import unittest
from dataclasses import replace

from config import load_config
from director_http import HttpDirectorProvider
from gateway import NovelAudioGateway
from protocol import MAX_JSON
from registry import VoiceRegistry
from server import create_server
from test_director import request as analysis_request
from voicestudio import VoiceStudioSpeechProvider


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.speech_status = 200

        def transport(method, path, payload=None):
            self.calls.append((method, path))
            if path == "/system/info":
                return 200, {}, b"{}"
            if path == "/v1/health":
                return 200, {}, json.dumps({
                    "status": "ok",
                    "apiVersion": "1",
                    "directorReady": True,
                }).encode()
            if path == "/v1/chapter/analyze":
                return 200, {}, json.dumps({
                    "assignments": [{"unitId": "u1", "speakerId": "narrator"}],
                    "newCharacters": [],
                    "aliasUpdates": [],
                }).encode()
            return self.speech_status, {"content-type": "audio/ogg"}, b"OggS-fixture"

        registry = VoiceRegistry.from_records([{
            "voiceAssetId": "voice",
            "displayName": "旁白",
            "gender": "unknown",
            "ageRange": "adult",
            "traits": ["清晰"],
            "providerRef": "local-profile",
        }])
        speech = VoiceStudioSpeechProvider(
            "http://127.0.0.1:3900",
            "fixture-provider-token",
            "fixture-profile",
            voice_resolver=lambda voice_id: {
                "voice": registry.resolve(voice_id).provider_ref,
            },
            response_format="ogg",
            transport=transport,
        )
        gateway = NovelAudioGateway(
            speech,
            HttpDirectorProvider(
                "http://127.0.0.1:8787", "fixture-director-token", transport=transport,
            ),
            "fixture-gateway-token",
            registry=registry,
        )
        config = replace(load_config({
            "NOVEL_AUDIO_TOKEN": "fixture-gateway-token",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "fixture-provider-token",
        }), port=0)
        self.server = create_server(config, gateway)
        self.thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01},
        )
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.audio_request = {
            "text": "测试", "voiceAssetId": "voice", "language": "zh-CN", "speed": 1.0,
        }
        self.routes = [
            ("GET", "/v1/health", None),
            ("GET", "/v1/voices", None),
            ("POST", "/v1/voices/match", {
                "voicePersona": {"traits": ["清晰"]}, "alreadyUsedVoiceIds": [],
            }),
            ("POST", "/v1/chapter/analyze", analysis_request().as_dict()),
            ("POST", "/v1/voices/preview", self.audio_request),
            ("POST", "/v1/tts/synthesize", self.audio_request),
        ]

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.assertFalse(self.thread.is_alive())

    def test_access_log_records_route_status_and_error_code_without_secrets(self):
        # 真机联调只能看到 App 的笼统报错；服务端须留下每个请求的路径、状态与错误码。
        import contextlib
        import io

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.json_request("GET", "/v1/health")
            self.send("GET", "/v1/voices?probe=1")
        lines = stderr.getvalue().splitlines()
        self.assertEqual(2, len(lines))
        self.assertRegex(lines[0], r"GET /v1/health 200 \d+ms$")
        self.assertRegex(lines[1], r"GET /v1/voices 401 \d+ms unauthorized$")
        self.assertNotIn("fixture-gateway-token", stderr.getvalue())
        self.assertNotIn("probe", stderr.getvalue())

    def send(self, method, path, raw=b"", headers=()):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            connection.putrequest(method, path)
            for name, value in headers:
                connection.putheader(name, value)
            connection.endheaders(raw)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def json_request(self, method, path, body=None):
        raw = json.dumps(body).encode() if body is not None else b""
        return self.send(method, path, raw, [
            ("Authorization", "Bearer fixture-gateway-token"),
            ("Content-Type", "application/json"),
            ("Content-Length", str(len(raw))),
        ])

    def test_all_six_endpoints_over_http(self):
        for method, path, body in self.routes:
            with self.subTest(path=path):
                status, headers, raw = self.json_request(method, path, body)
                self.assertEqual(200, status)
                self.assertEqual("no-store", headers["Cache-Control"])
                if path.endswith(("/synthesize", "/preview")):
                    self.assertEqual("audio/ogg", headers["Content-Type"])
                    self.assertEqual("fixture-profile", headers["X-TTS-Profile"])
                    self.assertEqual(b"OggS-fixture", raw)
                else:
                    payload = json.loads(raw)
                    key = {
                        "/v1/health": "ttsReady", "/v1/voices": "voices",
                        "/v1/voices/match": "candidates",
                        "/v1/chapter/analyze": "assignments",
                    }[path]
                    self.assertTrue(payload[key])

    def test_authentication_precedes_json_parsing_and_provider_calls(self):
        for method, path, _ in self.routes:
            with self.subTest(path=path):
                status, _, _ = self.send(method, path, b"not-json", [
                    ("Content-Type", "application/json"), ("Content-Length", "8"),
                ])
                self.assertEqual(401, status)
        self.assertEqual([], self.calls)

    def test_ambiguous_and_oversize_framing_is_rejected_without_body(self):
        cases = [
            (400, []),
            (400, [("Content-Length", "2"), ("Content-Length", "2")]),
            (400, [("Content-Length", "2"), ("Transfer-Encoding", "chunked")]),
            (400, [("Content-Length", "-1")]),
            (413, [("Content-Length", str(MAX_JSON + 1))]),
        ]
        for expected, framing in cases:
            with self.subTest(framing=framing):
                status, _, _ = self.send("POST", "/v1/tts/synthesize", headers=[
                    ("Authorization", "Bearer fixture-gateway-token"),
                    ("Content-Type", "application/json"),
                    *framing,
                ])
                self.assertEqual(expected, status)
        self.assertEqual([], self.calls)

    def test_invalid_json_is_rejected_before_provider_calls(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":"\\ud800"}', b"\xff"):
            with self.subTest(raw=raw):
                status, _, _ = self.send("POST", "/v1/tts/synthesize", raw, [
                    ("Authorization", "Bearer fixture-gateway-token"),
                    ("Content-Type", "application/json"),
                    ("Content-Length", str(len(raw))),
                ])
                self.assertEqual(400, status)
        self.assertEqual([], self.calls)

    def test_provider_rate_limit_preserves_http_429(self):
        self.speech_status = 429
        status, _, raw = self.json_request(
            "POST", "/v1/tts/synthesize", self.audio_request,
        )
        self.assertEqual(429, status)
        self.assertEqual({"error": {"code": "busy"}}, json.loads(raw))


if __name__ == "__main__":
    unittest.main()
