"""真实网络替换为协议响应；音频编码使用本机 ffmpeg。"""
import io
import json
import shutil
import struct
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from http.client import HTTPResponse
from urllib.error import HTTPError, URLError

from cloud import BailianClient
from local_state import BridgeConfig
from protocol import (
    BridgeError, CloudProtocolError, CloudQuotaError, CloudRateError, CloudAuthError,
    CloudTimeoutError, TtsWavError, MAX_JSON,
)
from test_boundaries import analysis

AUDIO_URL = "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/test.wav?sign=example"


def wav_fixture(frames=2400, rate=24000):
    stream = io.BytesIO()
    with wave.open(stream, "wb") as output:
        output.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        output.writeframes(b"\x10\x00" * frames)
    return stream.getvalue()


class Response(io.BytesIO):
    def __init__(self, data, status=200, headers=None):
        super().__init__(data)
        self.status = status
        self.headers = headers or {}


class Transport:
    def __init__(self, responses):
        self.responses, self.requests = list(responses), []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def response(value):
    return Response(json.dumps(value).encode())


class CloudTest(unittest.TestCase):
    def client(self, replies, runner=None):
        transport = Transport(replies)
        client = BailianClient(BridgeConfig(dashscope_api_key="cloud-secret"),
                               opener=transport, runner=runner or subprocess.run)
        return client, transport

    def test_text_request_uses_fixed_model_json_mode_and_no_thinking(self):
        answer = {"assignments": [], "newCharacters": [], "aliasUpdates": []}
        client, transport = self.client([response({"choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps(answer)}}]})])
        self.assertEqual(answer, client.analyze(analysis()))
        request, timeout = transport.requests[0]
        self.assertEqual("Bearer cloud-secret", request.get_header("Authorization"))
        payload = json.loads(request.data)
        self.assertEqual("qwen3.7-plus", payload["model"])
        self.assertFalse(payload["enable_thinking"])
        self.assertFalse(payload["stream"])
        self.assertEqual({"type": "json_object"}, payload["response_format"])
        self.assertLessEqual(payload["max_tokens"], 4096)
        self.assertIn("JSON", payload["messages"][0]["content"])
        self.assertNotIn("parameters", payload)
        self.assertNotIn("extra_body", payload)
        self.assertNotIn("input", payload)
        self.assertEqual("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                         request.full_url)

    def test_text_request_waits_for_whole_deadline_not_ten_second_socket_cap(self):
        # 真机实测：非流式整章分析首字节常超过 10 秒；单次读取上限必须是剩余总时限。
        answer = {"assignments": [], "newCharacters": [], "aliasUpdates": []}
        client, transport = self.client([response({"choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps(answer)}}]})])
        client.analyze(analysis())
        self.assertGreater(transport.requests[0][1], 30)

    def test_long_android_unit_ids_reach_model_as_short_aliases(self):
        # 真机实测：模型抄错 66 位哈希 unitId 导致整章覆盖校验失败。
        request = analysis()
        long_ids = ["u_" + "a" * 64, "u_" + "b" * 64]
        for unit, unit_id in zip(request["units"], long_ids):
            unit["unitId"] = unit_id
        request["previousContext"]["recentAssignments"] = [{"unitId": "u_" + "c" * 64,
                                                            "speakerId": "char_1"}]
        answer = {"assignments": [{"unitId": "u2", "speakerId": "char_1"},
                                  {"unitId": "u1", "speakerId": "narrator"}],
                  "newCharacters": [], "aliasUpdates": []}
        client, transport = self.client([response({"choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps(answer)}}]})])
        result = client.analyze(request)
        sent = json.loads(json.loads(transport.requests[0][0].data)["messages"][1]["content"])
        self.assertEqual(["u1", "u2"], [unit["unitId"] for unit in sent["units"]])
        self.assertEqual([{"unitId": "p1", "speakerId": "char_1"}],
                         sent["previousContext"]["recentAssignments"])
        self.assertNotIn("a" * 64, transport.requests[0][0].data.decode())
        self.assertEqual([long_ids[1], long_ids[0]], [item["unitId"] for item in result["assignments"]])

    def test_analysis_output_failures_have_distinct_codes(self):
        cases = (({"finish_reason": "length", "message": {"content": "{}"}}, "analysis_truncated"),
                 ({"finish_reason": "stop", "message": {"content": "不是 JSON"}}, "analysis_invalid_json"),
                 ({"finish_reason": "stop", "message": {"content": "{\"assignments\": 1}"}},
                  "analysis_invalid_json"))
        for choice, code in cases:
            with self.subTest(code=code):
                client, _ = self.client([response({"choices": [choice]})])
                with self.assertRaises(CloudProtocolError) as raised:
                    client.analyze(analysis())
                self.assertEqual(code, raised.exception.code)

    def test_truncated_model_output_rejected(self):
        client, _ = self.client([response({"choices": [
            {"finish_reason": "length", "message": {"content": "{}"}}]})])
        with self.assertRaises(CloudProtocolError):
            client.analyze(analysis())

    def test_tts_download_never_receives_api_key_and_https_is_forced(self):
        def runner(command, **kwargs):
            self.assertNotIn("cloud-secret", str(command))
            self.assertNotIn("sign=example", str(command))
            self.assertIn("-protocol_whitelist", command)
            self.assertEqual("wav", command[command.index("-f") + 1])
            return SimpleNamespace(returncode=0, stdout=b"OggS" + b"x" * 30)
        client, transport = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}),
            Response(wav_fixture()),
        ], runner)
        result = client.synthesize({"text": "你好", "voice": "Ethan", "language": "zh-CN", "speed": 1.0})
        self.assertTrue(result.startswith(b"OggS"))
        payload = json.loads(transport.requests[0][0].data)
        self.assertEqual("qwen3-tts-instruct-flash", payload["model"])
        self.assertEqual("Ethan", payload["input"]["voice"])
        self.assertEqual("你好", payload["input"]["text"])
        self.assertFalse(payload["input"]["optimize_instructions"])
        download = transport.requests[1][0]
        self.assertIsNone(download.get_header("Authorization"))
        self.assertEqual(AUDIO_URL.replace("http:", "https:", 1), download.full_url)

    def test_two_cloud_chunks_preserve_all_text_and_convert_once(self):
        text = "中😀" * 350
        calls = []
        def runner(command, **kwargs):
            calls.append(kwargs["input"])
            return SimpleNamespace(returncode=0, stdout=b"OggS" + b"x" * 30)
        client, transport = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
        ], runner)
        client.synthesize({"text": text, "voice": "Ethan", "language": "zh-CN", "speed": 1.0})
        sent = [json.loads(req.data)["input"]["text"] for req, _ in transport.requests if req.data]
        self.assertEqual(text, "".join(sent))
        self.assertEqual(2, len(sent))
        self.assertTrue(all(len(chunk) <= 600 for chunk in sent))
        self.assertEqual(1, len(calls))

    def test_observed_result_host_download_is_https_without_authorization(self):
        url = AUDIO_URL.replace("dashscope-result-bj", "dashscope-a717")
        client, transport = self.client([
            response({"output": {"audio": {"url": url}}}), Response(wav_fixture()),
        ], lambda *_, **__: SimpleNamespace(returncode=0, stdout=b"OggS" + b"x" * 30))
        client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        download = transport.requests[1][0]
        self.assertEqual(url.replace("http:", "https:", 1), download.full_url)
        self.assertIsNone(download.get_header("Authorization"))
        self.assertEqual(2, len(transport.requests))

    def test_http_errors_do_not_retry_and_errors_are_redacted(self):
        for status, value, kind in (
            (401, {}, CloudAuthError),
            (403, {"code": "AllocationQuota.FreeTierOnly"}, CloudQuotaError),
            (403, {"error": {"code": "AllocationQuota.FreeTierOnly"}}, CloudQuotaError),
            (403, {"code": "AllocationQuota.FreeTierOnly", "error": None}, CloudQuotaError),
            (429, {"code": "Throttling"}, CloudRateError),
            (503, {"message": "cloud-secret https://secret.invalid novel-body"}, BridgeError),
        ):
            failure = HTTPError("https://secret.invalid", status, "cloud-secret", {},
                                io.BytesIO(json.dumps(value).encode()))
            client, transport = self.client([failure])
            with self.assertRaises(kind) as caught:
                client.analyze(analysis())
            self.assertNotIn("secret", str(caught.exception))
            self.assertNotIn("novel-body", str(caught.exception))
            self.assertEqual(1, len(transport.requests))

    def test_json_size_encoding_duplicates_and_malformed_shape(self):
        for raw in (b"\xff", b'{"choices":[],"choices":[]}', b"[]" ,
                    b'{"choices":[null]}', b'{"choices":[]}',
                    b'{"choices":[{"finish_reason":"stop","message":{"content":[]}}]}',
                    b"x" * (MAX_JSON + 1)):
            client, _ = self.client([Response(raw)])
            with self.assertRaises(CloudProtocolError):
                client.analyze(analysis())

    def test_download_rejects_foreign_urls_before_fetch(self):
        for url in ("https://example.invalid/a.wav", "file:///etc/passwd",
                    "https://127.0.0.1/a.wav",
                    AUDIO_URL.replace("http://", "https://user:pass@"),
                    AUDIO_URL.replace(".com/", ".com:444/")):
            client, transport = self.client([response({"output": {"audio": {"url": url}}})])
            with self.assertRaises(CloudProtocolError):
                client.synthesize({"text": "文", "voice": "Ethan", "language": "zh-CN", "speed": 1.0})
            self.assertEqual(1, len(transport.requests))

    def test_non_wav_and_truncated_wav_never_reach_ffmpeg(self):
        def forbidden(*_, **__):
            self.fail("invalid media reached ffmpeg")
        for audio in (b"#EXTM3U\nhttp://127.0.0.1/secret", wav_fixture()[:-10], b""):
            client, _ = self.client([
                response({"output": {"audio": {"url": AUDIO_URL}}}), Response(audio),
            ], forbidden)
            with self.assertRaises(CloudProtocolError):
                client.synthesize({"text": "文", "voice": "Ethan", "language": "zh-CN", "speed": 1.0})

    def test_tts_response_failures_identify_stage_without_sensitive_content(self):
        cases = [
            ([Response(b"cloud-secret novel-body")], "tts_invalid_json"),
            ([Response(b'{"output":{},"output":{}}')], "tts_invalid_json"),
            ([Response(b"\xff")], "tts_invalid_json"),
            ([Response(b"{}extra", headers={"Content-Length": "2"})], "tts_invalid_response"),
            ([Response(b"{}", headers={"Content-Length": str(MAX_JSON + 1)})],
             "tts_invalid_response"),
            ([response({"output": {"audio": {"url": ""}}})], "tts_missing_audio_url"),
            ([response({"output": {"audio": None}})], "tts_missing_audio_url"),
            ([response({"output": {"audio": {"url": "https://secret.invalid/novel-body.wav"}}})],
             "tts_unsafe_audio_url"),
            ([response({"output": {"audio": {"url": AUDIO_URL.replace(".wav", ".mp3")}}})],
             "tts_unsafe_audio_url"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}),
              URLError("cloud-secret novel-body sign=example")], "tts_download_failed"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}),
              Response(b"x", headers={"Content-Length": "20"})], "tts_download_failed"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}),
              Response(b"#EXTM3U\nhttps://secret.invalid/novel-body")], "tts_invalid_wav"),
            ([response({"output": {"audio": {"url": AUDIO_URL}}}),
              Response(wav_fixture()[:-10])], "tts_invalid_wav"),
        ]
        def forbidden(*_, **__):
            self.fail("invalid response reached ffmpeg")
        for replies, expected in cases:
            with self.subTest(expected=expected, responses=len(replies)):
                request_count = len(replies)
                client, transport = self.client(replies, forbidden)
                with self.assertRaises(BridgeError) as caught:
                    client.synthesize({"text": "novel-body", "voice": "Ethan", "speed": 1.0})
                self.assertEqual(expected, caught.exception.code)
                self.assertEqual(expected, str(caught.exception))
                self.assertEqual(502, caught.exception.status)
                self.assertEqual(request_count, len(transport.requests))

    def test_audio_storage_errors_are_not_model_auth_or_quota_errors(self):
        # 音频存储返回 403 不能被误认成推理 Key 失效，也不能触发模型配额熔断。
        for status in (301, 403, 429, 503):
            with self.subTest(status=status):
                failure = HTTPError(AUDIO_URL, status, "cloud-secret", {},
                                    io.BytesIO(b'{"code":"AllocationQuota.FreeTierOnly"}'))
                client, transport = self.client([
                    response({"output": {"audio": {"url": AUDIO_URL}}}), failure])
                with self.assertRaises(BridgeError) as caught:
                    client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
                self.assertEqual("tts_download_failed", caught.exception.code)
                self.assertEqual(2, len(transport.requests))
                self.assertIsNone(transport.requests[1][0].get_header("Authorization"))

    def test_tts_conversion_failures_have_fixed_diagnostic(self):
        for result in (SimpleNamespace(returncode=1, stdout=b"cloud-secret"),
                       SimpleNamespace(returncode=0, stdout=b"not-ogg")):
            client, transport = self.client([
                response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
            ], lambda *_, **__: result)
            with self.assertRaises(BridgeError) as caught:
                client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
            self.assertEqual("tts_conversion_failed", caught.exception.code)
            self.assertEqual("tts_conversion_failed", str(caught.exception))
            self.assertEqual(2, len(transport.requests))
        def missing(*_, **__):
            raise FileNotFoundError("cloud-secret")
        client, _ = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
        ], missing)
        with self.assertRaises(BridgeError) as caught:
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        self.assertEqual("tts_conversion_failed", caught.exception.code)

    def test_tts_diagnostics_preserve_api_errors_and_deadlines(self):
        for status, raw, kind in (
            (401, b"cloud-secret", CloudAuthError),
            (403, b'{"code":"AllocationQuota.FreeTierOnly"}', CloudQuotaError),
            (429, b"cloud-secret", CloudRateError),
        ):
            failure = HTTPError("https://secret.invalid", status, "secret", {}, io.BytesIO(raw))
            client, transport = self.client([failure])
            with self.assertRaises(kind):
                client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
            self.assertEqual(1, len(transport.requests))
        client, transport = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), TimeoutError("secret")])
        with self.assertRaises(CloudTimeoutError):
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        self.assertEqual(2, len(transport.requests))
        def timeout(*_, **__):
            raise subprocess.TimeoutExpired("secret", .1)
        client, _ = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
        ], timeout)
        with self.assertRaises(CloudTimeoutError):
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})

    def test_wrapped_url_timeout_keeps_timeout_in_both_stages(self):
        for download in (False, True):
            for reason in (TimeoutError("cloud-secret"), OSError("cloud-secret"), "cloud-secret"):
                with self.subTest(download=download, reason_type=type(reason).__name__):
                    replies = [response({"output": {"audio": {"url": AUDIO_URL}}})] if download else []
                    client, transport = self.client(replies + [URLError(reason)])
                    with self.assertRaises(BridgeError) as caught:
                        client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
                    expected = ("cloud_timeout" if isinstance(reason, TimeoutError) else
                                "tts_download_failed" if download else "cloud_unavailable")
                    self.assertEqual(expected, caught.exception.code)
                    self.assertEqual(expected, str(caught.exception))
                    self.assertEqual(2 if download else 1, len(transport.requests))

    def test_truncated_http_reads_keep_tts_stage_diagnostics(self):
        def truncated():
            wire = io.BytesIO(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nA\r\nabc")
            reply = HTTPResponse(SimpleNamespace(makefile=lambda *_: wire))
            reply.begin()
            return reply
        client, _ = self.client([truncated()])
        with self.assertRaises(BridgeError) as caught:
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        self.assertEqual("tts_invalid_response", caught.exception.code)

        client, _ = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), truncated(),
        ])
        with self.assertRaises(BridgeError) as caught:
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        self.assertEqual("tts_download_failed", caught.exception.code)

    def test_http_error_body_read_timeout_is_bounded(self):
        class FailureBody(io.BytesIO):
            def read(self, *_):
                raise failure_type("secret")
        for failure_type in (TimeoutError, ConnectionResetError):
            for download in (False, True):
                with self.subTest(error=failure_type.__name__, download=download):
                    failure = HTTPError("https://secret.invalid", 403, "secret", {}, FailureBody())
                    replies = [response({"output": {"audio": {"url": AUDIO_URL}}})] if download else []
                    client, transport = self.client(replies + [failure])
                    with self.assertRaises(BridgeError) as caught:
                        client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
                    expected = ("cloud_timeout" if failure_type is TimeoutError else
                                "tts_download_failed" if download else "cloud_unavailable")
                    self.assertEqual(expected, caught.exception.code)
                    self.assertEqual(2 if download else 1, len(transport.requests))

    def test_top_level_quota_with_null_error_persists_circuit_breaker(self):
        from bridge import BridgeApi, UsageGuard
        from test_boundaries import synthesis
        for irrelevant in (None, [], "", 3):
            with self.subTest(irrelevant=irrelevant), tempfile.TemporaryDirectory() as directory:
                raw = json.dumps({"code": "AllocationQuota.FreeTierOnly", "error": irrelevant}).encode()
                failure = HTTPError("https://secret.invalid", 403, "secret", {}, io.BytesIO(raw))
                client, transport = self.client([failure])
                config = BridgeConfig(dashscope_api_key="fake", bridge_token="local")
                path = Path(directory) / "state.db"
                api = BridgeApi(config, client, UsageGuard(path), True)
                first = api.respond("POST", "/v1/tts/synthesize", "Bearer local", synthesis())
                self.assertEqual((429, {"code": "free_quota_only"}), (first[0], first[2]["error"]))
                self.assertEqual(429, api.respond(
                    "POST", "/v1/tts/synthesize", "Bearer local", synthesis())[0])
                reloaded = BridgeApi(config, client, UsageGuard(path), True)
                self.assertEqual(429, reloaded.respond(
                    "POST", "/v1/tts/synthesize", "Bearer local", synthesis())[0])
                self.assertFalse(UsageGuard(path).available("tts"))
                self.assertFalse(UsageGuard(path).available("analysis"))
                self.assertEqual(1, len(transport.requests))

    def test_malformed_riff_subchunk_is_classified_as_invalid_wav(self):
        malformed = b"RIFF" + struct.pack("<I", 12) + b"WAVEJUNK" + struct.pack("<I", 0xffffffff)
        client, _ = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(malformed),
        ])
        with self.assertRaises(TtsWavError) as caught:
            client.synthesize({"text": "文", "voice": "Ethan", "speed": 1.0})
        self.assertEqual("tts_invalid_wav", caught.exception.code)

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not installed")
    def test_actual_opus_encode_and_decode(self):
        client, _ = self.client([
            response({"output": {"audio": {"url": AUDIO_URL}}}), Response(wav_fixture()),
        ])
        result = client.synthesize({"text": "文", "voice": "Ethan", "language": "zh-CN", "speed": 1.0})
        self.assertTrue(result.startswith(b"OggS"))
        decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", "pipe:0",
                                  "-f", "s16le", "pipe:1"], input=result,
                                 capture_output=True, timeout=5)
        self.assertEqual(0, decoded.returncode)
        self.assertGreater(len(decoded.stdout), 0)


if __name__ == "__main__":
    unittest.main()
