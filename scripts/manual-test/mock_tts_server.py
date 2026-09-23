#!/usr/bin/env python3
"""本地假 TTS 服务：用 macOS `say` 合成中文语音，供人工测试多角色朗读。

- 应用通过 adb reverse 访问 http://127.0.0.1:8765（模拟器、真机通用）
- POST /v1/audio/speech   OpenAI 兼容合成接口，返回 mp3
- GET  /engine.json       应用可导入的朗读引擎（脚本型，含 9 个音色）
- GET  /admin             浏览器控制台：请求计数、最近请求、故障模拟开关
只依赖 Python 标准库；mp3 转码依赖 ffmpeg，缺失时退回 wav。
"""

import hashlib
import html
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("MOCK_TTS_PORT", "8765"))
CACHE_DIR = os.environ.get(
    "MOCK_TTS_CACHE", os.path.join(tempfile.gettempdir(), "legado-mock-tts-cache")
)
FFMPEG = shutil.which("ffmpeg")
BASE_WPM = 185

# toneID -> (显示名, macOS 语音名, 标签)
VOICES = {
    "tingting": ("婷婷·女·标准", "Tingting", ["女", "青年", "旁白"]),
    "flo": ("Flo·女·青年", "Flo (中文（中国大陆）)", ["女", "青年"]),
    "sandy": ("Sandy·女·青年", "Sandy (中文（中国大陆）)", ["女", "少女"]),
    "shelley": ("Shelley·女·成熟", "Shelley (中文（中国大陆）)", ["女", "中年"]),
    "grandma": ("奶奶·女·老年", "Grandma (中文（中国大陆）)", ["女", "老年"]),
    "eddy": ("Eddy·男·青年", "Eddy (中文（中国大陆）)", ["男", "青年", "少年"]),
    "reed": ("Reed·男·成熟", "Reed (中文（中国大陆）)", ["男", "中年"]),
    "rocko": ("Rocko·男·成熟", "Rocko (中文（中国大陆）)", ["男", "中年"]),
    "grandpa": ("爷爷·男·老年", "Grandpa (中文（中国大陆）)", ["男", "老年"]),
}
DEFAULT_VOICE = "tingting"

MODES = {
    "normal": "正常合成",
    "slow": "每次请求慢 15 秒",
    "hang": "请求卡住 120 秒（模拟超时）",
    "error500": "返回 500 服务器错误",
    "error401": "返回 401 鉴权失败",
    "error429": "返回 429 限流",
    "flaky": "每隔一次失败（500）",
    "empty": "返回 200 但音频为空",
}

state_lock = threading.Lock()
state = {"mode": "normal", "count": 0, "since": time.time(), "flaky_toggle": False}
recent = deque(maxlen=200)


ENGINE_SCRIPT = """// @name: 本机测试 TTS（Mac 语音）
// @schema: 1
// @capabilities: speed
// @defaultSpeed: 50
// 仅用于人工测试：请求本机 mock_tts_server.py（需先运行 scripts/manual-test/start.sh）
var ENDPOINT = "http://127.0.0.1:%(port)d/v1/audio/speech";
var VOICES = %(voices)s;

function synthesize(text, voice, params, options, ctx) {
    return {
        url: ENDPOINT,
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
            model: "mac-say",
            input: text,
            voice: voice || "%(default)s",
            speed: Number((params.rate || 1).toFixed(2)),
            response_format: "mp3"
        })
    };
}

function voices() {
    return VOICES;
}

function options() {
    return [];
}
"""


def voice_catalog():
    return [
        {"speakerName": name, "toneID": tone, "tags": tags}
        for tone, (name, _, tags) in VOICES.items()
    ]


def engine_json():
    script = ENGINE_SCRIPT % {
        "port": PORT,
        "voices": json.dumps(voice_catalog(), ensure_ascii=False),
        "default": DEFAULT_VOICE,
    }
    return [
        {
            # 固定 id：重复导入时覆盖同一条引擎记录，而不是越导越多
            "id": 20260923001,
            "name": "本机测试 TTS（Mac 语音）",
            "url": "http://127.0.0.1:%d/v1/audio/speech" % PORT,
            "type": 2,
            "script": script,
            "concurrentRate": "0",
            "synthesisThreadCount": 2,
        }
    ]


def synthesize(text, tone, speed):
    _, mac_voice, _ = VOICES.get(tone, VOICES[DEFAULT_VOICE])
    rate = max(90, min(400, int(BASE_WPM * (speed or 1.0))))
    ext = "mp3" if FFMPEG else "wav"
    key = hashlib.sha256(f"{mac_voice}|{rate}|{text}".encode()).hexdigest()
    os.makedirs(CACHE_DIR, exist_ok=True)
    out = os.path.join(CACHE_DIR, f"{key}.{ext}")
    if os.path.exists(out) and os.path.getsize(out) > 0:
        return out, ext
    with tempfile.TemporaryDirectory() as tmp:
        text_file = os.path.join(tmp, "in.txt")
        aiff = os.path.join(tmp, "out.aiff")
        # 文本走文件而不是命令行参数，避免特殊字符和长度问题
        with open(text_file, "w", encoding="utf-8") as f:
            f.write(text)
        subprocess.run(
            ["say", "-v", mac_voice, "-r", str(rate), "-f", text_file, "-o", aiff],
            check=True, capture_output=True, timeout=120,
        )
        tmp_out = os.path.join(tmp, f"out.{ext}")
        if FFMPEG:
            subprocess.run(
                [FFMPEG, "-y", "-loglevel", "error", "-i", aiff,
                 "-ac", "1", "-ar", "24000", "-b:a", "64k", tmp_out],
                check=True, capture_output=True, timeout=120,
            )
        else:
            subprocess.run(
                ["afconvert", "-f", "WAVE", "-d", "LEI16@24000", aiff, tmp_out],
                check=True, capture_output=True, timeout=120,
            )
        shutil.move(tmp_out, out)
    return out, ext


def log_request(entry):
    with state_lock:
        state["count"] += 1
        recent.appendleft(entry)
    print(
        f"[{entry['time']}] {entry['status']} voice={entry['voice']} "
        f"{entry['ms']}ms text={entry['text']}",
        flush=True,
    )


ADMIN_PAGE = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="3"><title>本机测试 TTS 控制台</title>
<style>
body{font-family:-apple-system,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#222}
.big{font-size:48px;font-weight:700}.mode{padding:6px 12px;border-radius:6px;background:#eef;display:inline-block}
a.btn{display:inline-block;margin:4px;padding:8px 12px;border:1px solid #88a;border-radius:6px;text-decoration:none;color:#224}
a.on{background:#224;color:#fff}table{border-collapse:collapse;width:100%%;font-size:13px}
td,th{border-bottom:1px solid #ddd;padding:4px 6px;text-align:left}.bad{color:#c00}
</style></head><body>
<h2>本机测试 TTS 控制台</h2>
<p>自 <b>%(since)s</b> 起，应用共请求合成 <span class="big">%(count)d</span> 次
 &nbsp;<a class="btn" href="/admin/reset">计数清零</a></p>
<p>当前模式：<span class="mode">%(mode)s</span></p>
<p>%(buttons)s</p>
<p style="color:#666">离线测试时：先点「计数清零」，再在应用里播放已下载章节；数字一直是 0 才说明真的没联网。页面每 3 秒自动刷新。</p>
<h3>最近请求</h3><table><tr><th>时间</th><th>结果</th><th>音色</th><th>耗时</th><th>文本开头</th></tr>%(rows)s</table>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "LegadoMockTTS/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, location):
        self.send_response(302)
        self.send_header("Location", location)
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self._send(200, "ok", "text/plain")
        if url.path == "/voices":
            return self._send(200, json.dumps(voice_catalog(), ensure_ascii=False))
        if url.path == "/engine.json":
            return self._send(200, json.dumps(engine_json(), ensure_ascii=False))
        if url.path == "/admin/mode":
            mode = parse_qs(url.query).get("m", ["normal"])[0]
            if mode in MODES:
                with state_lock:
                    state["mode"] = mode
            return self._redirect("/admin")
        if url.path == "/admin/reset":
            with state_lock:
                state["count"] = 0
                state["since"] = time.time()
                recent.clear()
            return self._redirect("/admin")
        if url.path in ("/", "/admin"):
            return self._send(200, self._admin_html(), "text/html; charset=utf-8")
        self._send(404, '{"error":"not found"}')

    def do_POST(self):
        url = urlparse(self.path)
        if url.path not in ("/v1/audio/speech", "/tts"):
            return self._send(404, '{"error":"not found"}')
        started = time.time()
        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            payload = {}
        text = str(payload.get("input", ""))
        tone = str(payload.get("voice") or DEFAULT_VOICE)
        speed = float(payload.get("speed") or 1.0)
        with state_lock:
            mode = state["mode"]
            if mode == "flaky":
                state["flaky_toggle"] = not state["flaky_toggle"]
                fail_now = state["flaky_toggle"]
            else:
                fail_now = False

        status = 200
        try:
            if mode == "slow":
                time.sleep(15)
            elif mode == "hang":
                time.sleep(120)
            if mode == "error500" or fail_now:
                status = 500
                return self._send(500, '{"error":"mock server error"}')
            if mode == "error401":
                status = 401
                return self._send(401, '{"error":"mock unauthorized"}')
            if mode == "error429":
                status = 429
                return self._send(429, '{"error":"mock rate limited"}')
            if mode == "empty":
                return self._send(200, b"", "audio/mpeg")
            if not text.strip():
                status = 400
                return self._send(400, '{"error":"empty input"}')
            path, ext = synthesize(text, tone, speed)
            with open(path, "rb") as f:
                data = f.read()
            self._send(200, data, "audio/mpeg" if ext == "mp3" else "audio/wav")
        except (BrokenPipeError, ConnectionResetError):
            status = "断开"
        except Exception as e:  # 合成失败也要让应用收到明确错误
            status = 500
            try:
                self._send(500, json.dumps({"error": str(e)}))
            except Exception:
                pass
        finally:
            log_request({
                "time": time.strftime("%H:%M:%S"),
                "status": status,
                "voice": tone,
                "ms": int((time.time() - started) * 1000),
                "text": text[:24].replace("\n", " "),
            })

    def _admin_html(self):
        with state_lock:
            mode, count, since = state["mode"], state["count"], state["since"]
            rows_data = list(recent)[:50]
        buttons = "".join(
            f'<a class="btn{" on" if key == mode else ""}" href="/admin/mode?m={key}">{html.escape(label)}</a>'
            for key, label in MODES.items()
        )
        rows = "".join(
            "<tr><td>{time}</td><td class='{cls}'>{status}</td><td>{voice}</td><td>{ms}ms</td><td>{text}</td></tr>".format(
                cls="" if r["status"] == 200 else "bad",
                **{k: html.escape(str(v)) for k, v in r.items()},
            )
            for r in rows_data
        )
        return ADMIN_PAGE % {
            "since": time.strftime("%H:%M:%S", time.localtime(since)),
            "count": count,
            "mode": html.escape(MODES[mode]),
            "buttons": buttons,
            "rows": rows,
        }


def main():
    if sys.platform != "darwin":
        sys.exit("mock_tts_server 依赖 macOS 的 say 命令")
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"本机测试 TTS 已启动：http://127.0.0.1:{PORT}/admin （ffmpeg={'有' if FFMPEG else '无，输出 wav'}）", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
