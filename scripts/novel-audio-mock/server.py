#!/usr/bin/env python3
"""NovelAudioServer v1 开发协议桩；不加载 AI 模型，不推断人物身份。"""
import argparse
import hmac
import json
import math
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_JSON = 2 * 1024 * 1024
VOICES = [
    {"voiceAssetId": key, "displayName": name, "gender": gender, "ageRange": "adult",
     "traits": [], "previewAvailable": False}
    for key, name, gender in (
        ("mock_narrator", "Mock 旁白", "unknown"),
        ("mock_a", "Mock 人物甲", "male"),
        ("mock_b", "Mock 人物乙", "female"))
]


class MockApi:
    def __init__(self, token, audio=None, media_type="audio/ogg", fault=0):
        if not token or not token.isascii():
            raise ValueError("Mock Token 必须为非空 ASCII")
        self.token, self.audio, self.media_type, self.fault = token, audio, media_type, fault

    @staticmethod
    def error(status, code):
        return status, {"Content-Type": "application/json"}, {"error": {"code": code}}

    def respond(self, method, path, authorization, body):
        if not hmac.compare_digest((authorization or "").encode(), f"Bearer {self.token}".encode()):
            return self.error(401, "unauthorized")
        if self.fault:
            return self.error(self.fault, "injected")
        headers = {"Content-Type": "application/json"}
        if method == "GET" and path == "/v1/health":
            return 200, headers, {"status": "ok", "apiVersion": "1",
                                  "directorReady": True, "ttsReady": bool(self.audio)}
        voices = [dict(v, previewAvailable=bool(self.audio)) for v in VOICES]
        if method == "GET" and path == "/v1/voices":
            return 200, headers, {"voices": voices}
        if method != "POST" or not isinstance(body, dict):
            return self.error(404, "not_found")
        if path == "/v1/chapter/analyze":
            units = body.get("units")
            characters = body.get("characters")
            if (not all(isinstance(body.get(k), str) and body[k].strip()
                        for k in ("bookId", "chapterId", "textHash", "analysisVersion"))
                    or not isinstance(units, list) or not units
                    or not isinstance(characters, list)
                    or not isinstance(body.get("previousContext"), dict)
                    or not isinstance(body["previousContext"].get("recentAssignments"), list)):
                return self.error(400, "invalid_analysis")
            if (any(not isinstance(u, dict) or not isinstance(u.get("unitId"), str)
                    or not u["unitId"].strip() or not isinstance(u.get("text"), str)
                    or not u["text"].strip() for u in units)
                    or len({u["unitId"] for u in units}) != len(units)):
                return self.error(400, "invalid_units")
            if any(not isinstance(c, dict) or not isinstance(c.get("characterId"), str)
                   or not c["characterId"].strip() for c in characters):
                return self.error(400, "invalid_characters")
            # 演示顺序映射，非人物推理；正式 Registry 仍需逐项校验。
            speakers = [c["characterId"] for c in characters[:2]]
            created = []
            if not speakers:
                used = {"narrator", *(c["characterId"] for c in characters)}
                for i in range(2):
                    key = f"mock_tmp_{i}"
                    while key in used:
                        key += "_"
                    used.add(key)
                    speakers.append(key)
                    created.append({"temporaryId": key, "displayName": f"Mock 人物{i + 1}",
                                    "gender": "unknown", "ageRange": "unknown",
                                    "voicePersona": {"traits": []}})
            assignments = []
            for i, unit in enumerate(units):
                speaker = "narrator" if i % 3 == 0 else speakers[(i - 1) % len(speakers)]
                assignments.append({"unitId": unit["unitId"], "speakerId": speaker})
            return 200, headers, {"assignments": assignments, "newCharacters": created, "aliasUpdates": []}
        if path == "/v1/voices/match":
            if (not isinstance(body.get("voicePersona"), dict)
                    or not isinstance(body["voicePersona"].get("traits"), list)
                    or not isinstance(body.get("alreadyUsedVoiceIds"), list)):
                return self.error(400, "invalid_match")
            used = body["alreadyUsedVoiceIds"]
            return 200, headers, {"candidates": sorted(voices, key=lambda v: v["voiceAssetId"] in used)}
        if path in ("/v1/voices/preview", "/v1/tts/synthesize"):
            speed = body.get("speed")
            if (not all(isinstance(body.get(k), str) and body[k].strip()
                        for k in ("text", "voiceAssetId", "language"))
                    or isinstance(speed, bool) or not isinstance(speed, (int, float))
                    or not math.isfinite(speed) or speed <= 0
                    or len(body["text"].encode("utf-16-le")) // 2 > 1200):
                return self.error(400, "invalid_synthesis")
            if body["voiceAssetId"] not in {v["voiceAssetId"] for v in voices}:
                return self.error(400, "unknown_voice")
            if not self.audio:
                return self.error(503, "audio_fixture_missing")
            return 200, {"Content-Type": self.media_type, "X-TTS-Profile": "mock-fixture-v1"}, self.audio
        return self.error(404, "not_found")


def create_server(host, port, api, delay=0):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # 不记录 URL/header/body，避免 Token 与正文进入开发日志。

        def do_GET(self):
            self.handle_api()

        def do_POST(self):
            self.handle_api()

        def handle_api(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > MAX_JSON:
                    result = api.error(413, "too_large")
                else:
                    raw = self.rfile.read(length) if length else b""
                    body = json.loads(raw) if raw else None
                    if delay:
                        time.sleep(delay)
                    result = api.respond(self.command, self.path,
                                         self.headers.get("Authorization"), body)
            except (ValueError, UnicodeDecodeError):
                result = api.error(400, "invalid_json")
            code, headers, body = result
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            for key, value in headers.items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--audio", type=Path, help="明确提供的 Ogg/Opus、M4A 或 AAC 测试音频")
    parser.add_argument("--fault", type=int, choices=(0, 401, 429, 500, 503), default=0)
    parser.add_argument("--delay", type=float, default=0, help="延迟响应秒数（0–60）")
    args = parser.parse_args()
    token = os.environ.get("NOVEL_AUDIO_MOCK_TOKEN", "")
    if not token:
        parser.error("先设置 NOVEL_AUDIO_MOCK_TOKEN；不要使用正式服务 Token")
    if not math.isfinite(args.delay) or not 0 <= args.delay <= 60:
        parser.error("delay 必须在 0–60 秒之间")
    audio, media_type = None, "audio/ogg"
    if args.audio:
        media_type = {".ogg": "audio/ogg", ".opus": "audio/ogg", ".m4a": "audio/mp4",
                      ".aac": "audio/aac"}.get(args.audio.suffix.lower())
        if not media_type or not 0 < args.audio.stat().st_size <= 16 * 1024 * 1024:
            parser.error("音频需为支持的压缩格式且不超过 16 MiB")
        audio = args.audio.read_bytes()
    api = MockApi(token, audio, media_type, args.fault)
    with create_server("127.0.0.1", args.port, api, args.delay) as server:
        print(f"NovelAudioServer 开发 Mock：127.0.0.1:{args.port}（不是正式 AI 服务）")
        server.serve_forever()


if __name__ == "__main__":
    main()
