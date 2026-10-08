"""Stage7B smoke against an ALREADY RUNNING Agent; never starts model workers.

Integration: run_smoke(config_path) -> int (0 passed, 2 failed).
stdout is JSON Lines of fixed step/status/errorCode plus validated audio metrics.
No config, credentials, identities, server text, or diagnostic paths are printed.
Artifacts remain in a new config.root/diagnostics/stage7b-http-* directory.
Only tests supply HTTP/ffprobe doubles; this module has no fake fallback.
"""

import contextlib
import hashlib
import http.client
import importlib
import json
import math
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.novel_audio_server.protocol import (
    MAX_AUDIO, MAX_JSON, analysis_request, analysis_response,
    strict_json_loads, synthesis_request,
)

# Qualified import avoids the bridge/voicestudio modules also named "config".
load_config = importlib.import_module("scripts.novel-audio-local.config").load_config

HTTP_TIMEOUT = 600.0
PROBE_TIMEOUT = 30.0
MAX_PROBE_OUTPUT = 16 * 1024
MIN_AUDIO = 27  # At least one Ogg page header, followed by ffprobe validation.
_ERRORS = frozenset({
    "ok", "config_invalid", "token_unavailable", "diagnostics_failed",
    "http_error", "http_timeout", "redirect_refused", "response_too_large",
    "invalid_response", "identity_mismatch", "runtime_not_idle",
    "runtime_not_ready", "second_acquire_not_busy", "release_failed",
    "invalid_audio", "probe_failed", "interrupted", "internal_error",
})
_STEPS = frozenset({
    "config", "token", "diagnostics", "health", "status", "voices", "match",
    "complete", "automatic.analyze", "automatic.analyze_idle",
    "automatic.preview", "automatic.preview_idle", "automatic.synthesize",
    "automatic.synthesize_idle",
} | {
    f"round{number}.{step}"
    for number in (1, 2)
    for step in ("acquire", "second_acquire", "status", "analyze", "preview",
                 "synthesize1", "synthesize2", "synthesize3", "release", "idle")
})
_FILES = frozenset({
    "automatic-preview.ogg", "automatic-synthesize.ogg",
} | {
    f"round{number}-{suffix}.ogg"
    for number in (1, 2)
    for suffix in ("preview", "synthesize-1", "synthesize-2", "synthesize-3")
})


class SmokeError(Exception):
    def __init__(self, code):
        self.code = code if code in _ERRORS else "internal_error"
        super().__init__(self.code)


def _require(condition, code="invalid_response"):
    if not condition:
        raise SmokeError(code)


def _report(step, code="ok", audio=None):
    # Both keys and string values are locally controlled, never remote echoes.
    step = step if step in _STEPS else "complete"
    code = code if code in _ERRORS else "internal_error"
    value = {"step": step, "status": "passed" if code == "ok" else "failed",
             "errorCode": code}
    if audio is not None:
        value["audio"] = audio
    print(json.dumps(value, sort_keys=True, allow_nan=False), flush=True)


def _read_token(config):
    try:
        path = config.token_file
        _require(not path.is_symlink() and path.is_file(), "token_unavailable")
        with path.open("rb") as stream:
            raw = stream.read(4099)
        _require(len(raw) <= 4098, "token_unavailable")
        token = raw.decode("ascii").strip()
        _require(re.fullmatch(r"[A-Za-z0-9_-]{32,4096}", token), "token_unavailable")
        return token
    except (OSError, ValueError):
        raise SmokeError("token_unavailable") from None


def _diagnostics(config):
    try:
        parent = config.root / "diagnostics"
        _require(not parent.is_symlink(), "diagnostics_failed")
        parent.mkdir(exist_ok=True)
        # resolve also rejects redirected Windows junctions outside config.root.
        _require(parent.resolve() == config.root / "diagnostics", "diagnostics_failed")
        return Path(tempfile.mkdtemp(prefix="stage7b-http-", dir=parent))
    except OSError:
        raise SmokeError("diagnostics_failed") from None


def _header_value(value, limit=256):
    return (isinstance(value, str) and 0 < len(value) <= limit
            and all(32 <= ord(char) < 127 for char in value))


class _Client:
    def __init__(self, config, token):
        self.port = config.port
        self.token = token

    def request(self, method, route, body=None, lease=None, audio=False):
        # HTTPConnection does not consult proxy environment or follow Location.
        connection = http.client.HTTPConnection("127.0.0.1", self.port,
                                                timeout=HTTP_TIMEOUT)
        headers = {"Authorization": "Bearer " + self.token, "Connection": "close"}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if lease is not None:
            _require(_header_value(lease))
            headers["X-NovelAudio-Lease"] = lease
        deadline = time.monotonic() + HTTP_TIMEOUT
        expired = threading.Event()
        timer = None
        try:
            connection.connect()
            connected_socket = connection.sock

            def expire():
                # A socket timeout alone permits endless trickled headers/body.
                expired.set()
                with contextlib.suppress(OSError):
                    connected_socket.shutdown(socket.SHUT_RDWR)

            timer = threading.Timer(max(0.001, deadline - time.monotonic()), expire)
            timer.daemon = True
            timer.start()
            connection.request(method, route, body=payload, headers=headers)
            response = connection.getresponse()
            with contextlib.closing(response):
                _require(not 300 <= response.status < 400, "redirect_refused")
                received = {}
                for key, value in response.getheaders():
                    key = key.lower()
                    if key in {"content-type", "content-length", "transfer-encoding",
                               "content-encoding", "x-tts-profile"}:
                        _require(key not in received)
                        received[key] = value
                _require(received.get("content-encoding", "identity") == "identity")
                _require(received.get("transfer-encoding") in (None, "chunked"))
                length = received.get("content-length")
                limit = MAX_AUDIO if audio and response.status == 200 else MAX_JSON
                if length is not None:
                    _require("transfer-encoding" not in received)
                    _require(re.fullmatch(r"[0-9]{1,12}", length))
                    _require(int(length) <= limit, "response_too_large")
                data = bytearray()
                while True:
                    _require(not expired.is_set() and time.monotonic() < deadline,
                             "http_timeout")
                    block = response.read1(min(64 * 1024, limit + 1 - len(data)))
                    if not block:
                        break
                    data.extend(block)
                    _require(len(data) <= limit, "response_too_large")
                _require(not expired.is_set(), "http_timeout")
                if length is not None:
                    _require(len(data) == int(length))
                return response.status, received, bytes(data)
        except TimeoutError:
            raise SmokeError("http_timeout") from None
        except (OSError, http.client.HTTPException):
            raise SmokeError("http_timeout" if expired.is_set() else "http_error") from None
        finally:
            if timer is not None:
                timer.cancel()
            connection.close()

    def json(self, method, route, body=None, lease=None):
        status, headers, raw = self.request(method, route, body, lease)
        _require(status == 200, "http_error")
        return _json_body(headers, raw)


def _json_body(headers, raw):
    _require(headers.get("content-type", "").split(";")[0].strip() == "application/json")
    try:
        result = strict_json_loads(raw)
        _require(isinstance(result, dict))
        return result
    except (ValueError, TypeError):
        raise SmokeError("invalid_response") from None


def _identity(value, expected=None):
    info = value.get("runtimeProfileInfo")
    _require(isinstance(info, dict), "identity_mismatch")
    identity = info.get("identity")
    _require(_header_value(identity) and identity == value.get("runtimeProfile"),
             "identity_mismatch")
    if expected is not None:
        _require(identity == expected, "identity_mismatch")
    return identity


def _probe(config, path):
    process = None
    timer = None
    expired = threading.Event()
    try:
        process = subprocess.Popen([
            str(config.tts.ffprobe), "-v", "quiet",
            "-protocol_whitelist", "file", "-f", "ogg", "-select_streams", "a:0",
            "-show_entries", "stream=codec_name,channels,sample_rate:format=duration",
            "-of", "json", str(path),
        ], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            shell=False)

        def expire():
            expired.set()
            with contextlib.suppress(OSError):
                process.kill()

        timer = threading.Timer(PROBE_TIMEOUT, expire)
        timer.daemon = True
        timer.start()
        # Never communicate() an unbounded subprocess output into memory.
        raw = process.stdout.read(MAX_PROBE_OUTPUT + 1)
        _require(len(raw) <= MAX_PROBE_OUTPUT, "probe_failed")
        _require(process.wait(timeout=PROBE_TIMEOUT) == 0 and not expired.is_set(),
                 "probe_failed")
        value = strict_json_loads(raw)
        _require(isinstance(value, dict) and "streams" in value and "format" in value,
                 "probe_failed")
        streams = value["streams"]
        _require(isinstance(streams, list) and len(streams) == 1, "probe_failed")
        stream = streams[0]
        _require(
            isinstance(stream, dict)
            and all(key in stream for key in ("codec_name", "channels", "sample_rate")),
            "probe_failed",
        )
        codec, channels = stream["codec_name"], stream["channels"]
        rate = stream["sample_rate"]
        _require(
            isinstance(value["format"], dict)
            and "duration" in value["format"],
            "probe_failed",
        )
        duration = value["format"]["duration"]
        _require(codec == "opus" and type(channels) is int and channels in (1, 2),
                 "probe_failed")
        _require(isinstance(rate, str) and re.fullmatch(r"[0-9]{4,5}", rate),
                 "probe_failed")
        _require(type(duration) in (str, int, float), "probe_failed")
        rate, duration = int(rate), float(duration)
        _require(8000 <= rate <= 48000 and math.isfinite(duration)
                 and 0 < duration <= 180, "probe_failed")
        return {"codec": "opus", "channels": channels,
                "sampleRate": rate, "duration": duration}
    except SmokeError:
        raise
    except (OSError, ValueError, TypeError, KeyError, OverflowError,
            subprocess.SubprocessError):
        raise SmokeError("probe_failed") from None
    finally:
        if timer is not None:
            timer.cancel()
        if process is not None:
            if process.poll() is None:
                with contextlib.suppress(OSError):
                    process.kill()
            with contextlib.suppress(OSError, subprocess.SubprocessError):
                process.wait(timeout=PROBE_TIMEOUT)
            if process.stdout is not None:
                process.stdout.close()


def _sample():
    # Original, short Chinese text; do not depend on a three-character prediction.
    units = [
        {"unitId": "smoke-u1", "text": "雨停了，屋檐下的小灯映着一片新叶。"},
        {"unitId": "smoke-u2", "text": "林溪说：“把这片叶子夹进书里吧。”"},
        {"unitId": "smoke-u3", "text": "许舟回答：“好，明天再读这一页。”"},
    ]
    return analysis_request({
        "bookId": "stage7b-original", "chapterId": "smoke-chapter",
        "textHash": hashlib.sha256(json.dumps(units, ensure_ascii=False).encode()).hexdigest(),
        "analysisVersion": "1",
        "characters": [
            {"characterId": "linxi", "displayName": "林溪", "stableAliases": []},
            {"characterId": "xuzhou", "displayName": "许舟", "stableAliases": []},
        ],
        "units": units, "previousContext": {"recentAssignments": []},
    })


class _Smoke:
    def __init__(self):
        self.step = "config"
        self.identity = None
        self.client = None
        self.config = None
        self.output = None
        self.voices = []
        self.request = _sample()

    def status(self, step, active=False):
        self.step = step
        value = self.client.json("GET", "/v1/runtime/status")
        self.identity = _identity(value, self.identity)
        _require(value.get("activeLease") is active
                 and value.get("state") == ("ready" if active else "idle"),
                 "runtime_not_ready" if active else "runtime_not_idle")
        _report(step)
        return value

    def analyze(self, step, lease=None):
        self.step = step
        value = self.client.json("POST", "/v1/chapter/analyze", self.request, lease)
        try:
            analysis_response(value, self.request)
        except (ValueError, TypeError, KeyError):
            raise SmokeError("invalid_response") from None
        _report(step)

    def audio(self, step, route, filename, voice, lease=None, idle_step=None):
        self.step = step
        payload = synthesis_request({
            "text": self.request["units"][0]["text"], "voiceAssetId": voice,
            "language": "zh-CN", "speed": 1.0,
        })
        try:
            status, headers, raw = self.client.request(
                "POST", route, payload, lease, audio=True
            )
            # Automatic leases are owned by the Agent, never guess their lease IDs.
            _require(status == 200, "http_error")
            _require(headers.get("x-tts-profile") == self.identity, "identity_mismatch")
            _require(headers.get("content-type", "").split(";")[0].strip() == "audio/ogg"
                     and MIN_AUDIO <= len(raw) <= MAX_AUDIO and raw.startswith(b"OggS"),
                     "invalid_audio")
        finally:
            if idle_step is not None:
                self.status(idle_step)
                self.step = step
        _require(filename in _FILES, "diagnostics_failed")
        try:
            path = self.output / filename
            # Never trust voice IDs as filenames; exclusive mode forbids overwrite.
            with path.open("xb") as target:
                target.write(raw)
        except OSError:
            raise SmokeError("diagnostics_failed") from None
        metrics = _probe(self.config, path)
        _report(step, audio=metrics)

    def release(self, leases, prefix):
        failed = False
        for lease in reversed(leases):
            try:
                value = self.client.json("POST", "/v1/runtime/release", lease=lease)
                _require(value.get("state") == "idle", "release_failed")
            except Exception:
                failed = True
                _report(prefix + ".release", "release_failed")
        if failed:
            raise SmokeError("release_failed")
        _report(prefix + ".release")
        self.status(prefix + ".idle")

    def round(self, number):
        prefix = f"round{number}"
        leases = []
        try:
            self.step = prefix + ".acquire"
            body = {"sessionId": f"stage7b-http-{number}", "purpose": "auto_prefetch",
                    "expectedChapterCount": 1}
            value = self.client.json("POST", "/v1/runtime/acquire", body)
            lease = value.get("leaseId")
            _require(_header_value(lease))
            # Record ownership BEFORE identity checks so wrong identity still releases.
            leases.append(lease)
            _identity(value, self.identity)
            _report(self.step)
            self.step = prefix + ".second_acquire"
            status, headers, raw = self.client.request(
                "POST", "/v1/runtime/acquire", {**body, "sessionId": body["sessionId"] + "-busy"}
            )
            if status == 200:
                extra = _json_body(headers, raw).get("leaseId")
                if _header_value(extra) and extra not in leases:
                    leases.append(extra)
            _require(status == 429, "second_acquire_not_busy")
            _report(self.step)
            self.status(prefix + ".status", active=True)
            self.analyze(prefix + ".analyze", lease)
            self.audio(prefix + ".preview", "/v1/voices/preview",
                       prefix + "-preview.ogg", self.voices[0], lease)
            for index, voice in enumerate(self.voices, 1):
                self.audio(prefix + f".synthesize{index}", "/v1/tts/synthesize",
                           prefix + f"-synthesize-{index}.ogg", voice, lease)
        finally:
            if leases:
                original_step = self.step
                original_error = sys.exc_info()[0] is not None
                try:
                    self.step = prefix + ".release"
                    self.release(leases, prefix)
                except Exception:
                    if not original_error:
                        raise
                    # Retain the original failure and report cleanup independently.
                    _report(prefix + ".release", "release_failed")
                finally:
                    if original_error:
                        self.step = original_step

    def run(self, config_path):
        try:
            self.config = load_config(config_path)
        except Exception:
            raise SmokeError("config_invalid") from None
        _report("config")
        self.step = "token"
        token = _read_token(self.config)
        _report("token")
        self.client = _Client(self.config, token)
        self.step = "diagnostics"
        self.output = _diagnostics(self.config)
        _report("diagnostics")
        self.step = "health"
        health = self.client.json("GET", "/v1/health")
        _require(health.get("status") == "ok" and health.get("apiVersion") == "1"
                 and health.get("directorReady") is True and health.get("ttsReady") is True)
        _report("health")
        status = self.status("status")
        capabilities = status["runtimeProfileInfo"].get("capabilities")
        _require(isinstance(capabilities, list)
                 and all(isinstance(item, str) for item in capabilities))
        self.step = "voices"
        voices = self.client.json("GET", "/v1/voices").get("voices")
        _require(isinstance(voices, list) and 0 < len(voices) <= 1024)
        ids = []
        for voice in voices:
            _require(isinstance(voice, dict))
            voice_id = voice.get("voiceAssetId")
            _require(_header_value(voice_id, 128) and voice_id not in ids)
            ids.append(voice_id)
        self.voices = ids[:3] if "voice-design" in capabilities else ids[:1]
        _report("voices")
        self.step = "match"
        candidates = self.client.json("POST", "/v1/voices/match", {
            "voicePersona": {"traits": ["清晰", "自然"]}, "alreadyUsedVoiceIds": [],
        }).get("candidates")
        _require(isinstance(candidates, list) and len(candidates) <= 1024)
        _require(all(isinstance(item, dict) and item.get("voiceAssetId") in ids
                     for item in candidates))
        _report("match")
        for number in (1, 2):
            self.round(number)
        try:
            self.analyze("automatic.analyze")
        finally:
            original_step = self.step
            self.status("automatic.analyze_idle")
            self.step = original_step
        self.audio("automatic.preview", "/v1/voices/preview", "automatic-preview.ogg",
                   self.voices[0], idle_step="automatic.preview_idle")
        self.audio("automatic.synthesize", "/v1/tts/synthesize", "automatic-synthesize.ogg",
                   self.voices[0], idle_step="automatic.synthesize_idle")


def run_smoke(config_path) -> int:
    """Read existing config/token, exercise real HTTP, return 0 or fixed failure 2."""
    smoke = _Smoke()
    try:
        smoke.run(config_path)
    except SmokeError as error:
        _report(smoke.step, error.code)
        _report("complete", error.code)
        return 2
    except KeyboardInterrupt:
        _report(smoke.step, "interrupted")
        _report("complete", "interrupted")
        return 2
    except Exception:
        _report(smoke.step, "internal_error")
        _report("complete", "internal_error")
        return 2
    _report("complete")
    return 0


def main(config_path) -> int:
    """Optional caller-owned entry point; importing this module runs no smoke."""
    return run_smoke(config_path)
