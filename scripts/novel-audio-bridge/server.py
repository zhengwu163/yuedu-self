#!/usr/bin/env python3
"""百炼试用桥接：本地检查、六端点服务、原创短章节试听。"""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import time
import wave
from datetime import datetime
from pathlib import Path

from bridge import BridgeApi
from cloud import INSTRUCTIONS, convert_wavs
from local_state import BridgeConfig, UsageGuard
from protocol import BridgeError, MAX_JSON, TTS_MODEL, VoiceCatalog, strict_json_loads
from worker import CloudWorker

try:
    from scripts.novel_audio_server.http import create_server as _create_shared_server
except ModuleNotFoundError:
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from scripts.novel_audio_server.http import create_server as _create_shared_server

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = "# 仅填写北京地域百炼通用 API Key，保存后执行检查。\nDASHSCOPE_API_KEY=\n"


def create_server(host, port, api):
    return _create_shared_server(host, port, api)


def media_profile(config):
    executable = shutil.which(config.ffmpeg_path)
    if not executable:
        raise BridgeError()
    # 真实编码一次极短 PCM，确认安装的 ffmpeg 具有 libopus，而不仅是存在命令。
    audio = io.BytesIO()
    with wave.open(audio, "wb") as stream:
        stream.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        stream.writeframes(b"\0\0" * 240)
    convert_wavs([audio.getvalue()], 1, executable)
    version = subprocess.run([executable, "-version"], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, timeout=5, check=True).stdout
    identity = version + INSTRUCTIONS.encode() + repr(VoiceCatalog.entries).encode()
    return f"bailian-{TTS_MODEL}-v1-{hashlib.sha256(identity).hexdigest()[:16]}"


def initialize(path):
    if path.is_symlink():
        raise ValueError()
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(TEMPLATE)


def write_connection(config, directory):
    """本地可打开的 Android 配置快照，仅含桥接 Token，不含百炼 Key。"""
    path = directory / "novel-audio.local.connection.json"
    pending = path.with_suffix(".json.new")
    fd = os.open(pending, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump({"baseUrl": f"http://127.0.0.1:{config.port}", "token": config.bridge_token,
                       "allowInsecureHttp": True}, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, path)
    finally:
        if pending.exists():
            pending.unlink()
    return path


def smoke(api, directory):
    """只用程序内原创文字；正文与音色来自本地计划，不从模型输出读取。"""
    units = [{"unitId": "demo-u1", "text": "黄昏的灯塔下，林舟和苏禾停下脚步。"},
             {"unitId": "demo-u2", "text": "“苏禾，明天一起去看海吧。”林舟说。"},
             {"unitId": "demo-u3", "text": "“好，我会带上那本蓝色的笔记。”苏禾笑着回答。"}]
    request = {"bookId": "bridge-original-demo-v1", "chapterId": "demo-chapter-1",
               "textHash": hashlib.sha256(json.dumps(units).encode()).hexdigest(),
               "analysisVersion": "1", "units": units,
               "characters": [{"characterId": key, "displayName": name, "stableAliases": []}
                              for key, name in (("demo-char-a", "林舟"), ("demo-char-b", "苏禾"))],
               "previousContext": {"recentAssignments": []}}
    auth = "Bearer " + api.config.bridge_token
    code, _, response = api.respond("POST", "/v1/chapter/analyze", auth, request)
    if code != 200:
        print("章节分析失败：" + response["error"]["code"])
        return 2
    assignments = {a["unitId"]: a["speakerId"] for a in response["assignments"]}
    if set(assignments.values()) != {"narrator", "demo-char-a", "demo-char-b"}:
        print("模型返回的角色分配未达到三角色试听要求，已停止，未重试。")
        return 2
    bindings = {"narrator": "bailian.narrator"}
    for key, gender in (("demo-char-a", "male"), ("demo-char-b", "female")):
        code, _, matched = api.respond("POST", "/v1/voices/match", auth, {
            "voicePersona": {"traits": []}, "alreadyUsedVoiceIds": list(bindings.values()),
            "optionalConstraints": {"gender": gender, "ageRange": "adult"}})
        if code != 200 or not matched["candidates"]:
            return 2
        bindings[key] = matched["candidates"][0]["voiceAssetId"]
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    for index, unit in enumerate(units, 1):
        code, _, audio = api.respond("POST", "/v1/tts/synthesize", auth, {
            "text": unit["text"], "voiceAssetId": bindings[assignments[unit["unitId"]]],
            "language": "zh-CN", "speed": 1.0})
        if code != 200:
            print("语音生成失败：" + audio["error"]["code"] + "；已完成文件保留。")
            return 2
        path = directory / f"{index:02d}.ogg"
        with path.open("xb") as stream:
            stream.write(audio)
    print("三角色试听已生成：" + str(directory))
    return 0


def smoke_tts(api, directory):
    """一次原创短句 TTS；不重做人物分析，沿用预算、worker 硬期限及脱敏错误。"""
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    code, _, audio = api.respond("POST", "/v1/tts/synthesize", "Bearer " + api.config.bridge_token, {
        "text": "黄昏的灯塔下，林舟停下脚步。",
        "voiceAssetId": "bailian.narrator", "language": "zh-CN", "speed": 1.0})
    if code != 200:
        print("单段语音失败：" + audio["error"]["code"] + "；已停止，未重试。")
        return 2
    path = directory / "01.ogg"
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(audio)
    print("单段试听已生成：" + str(path))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "novel-audio.local.env")
    modes = parser.add_mutually_exclusive_group()
    for mode in ("init", "check", "serve", "smoke", "smoke-tts"):
        modes.add_argument("--" + mode, action="store_true")
    parser.add_argument("--free-quota-confirmed", action="store_true",
                        help="确认两个固定模型均已开启免费额度用完即停且已生效")
    args = parser.parse_args(argv)
    try:
        if args.init:
            initialize(args.config)
            print("本地配置已准备；已有内容保持不变。")
            return 0
        if (args.serve or args.smoke or args.smoke_tts) and not args.free_quota_confirmed:
            print("请先在百炼控制台确认两个模型的“免费额度用完即停”已生效，"
                  "再加 --free-quota-confirmed 启动；本地无法代查该开关。")
            return 2
        if args.config.is_symlink():
            raise ValueError()
        if os.name == "posix":
            args.config.chmod(0o600)
        config = BridgeConfig.from_env_file(args.config)
        print(config.startup_summary())
        if not config.cloud_ready():
            print("请在 novel-audio.local.env 的 DASHSCOPE_API_KEY= 后填写 API Key。")
            return 2
        profile = media_profile(config)
        print("音频转换已检查；未调用云模型。")
        write_connection(config, args.config.parent)
        print("App 本地连接信息已保存至 novel-audio.local.connection.json（不含百炼 Key）。")
        if not (args.serve or args.smoke or args.smoke_tts):
            return 0
        api = BridgeApi(config, CloudWorker(config, profile), UsageGuard(config.state_path), True)
        if args.smoke or args.smoke_tts:
            directory = args.config.parent / "novel-audio.local.smoke" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
            try:
                return smoke_tts(api, directory) if args.smoke_tts else smoke(api, directory)
            finally:
                api.close()
        with create_server(config.host, config.port, api) as httpd:
            print(f"监听 http://127.0.0.1:{config.port}；连接口令由本地状态自动保存。Ctrl+C 停止。")
            httpd.serve_forever()
        return 0
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, BridgeError, subprocess.SubprocessError):
        print("本地配置、预算状态或音频工具检查未通过；未显示敏感错误内容。")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
