# VoiceStudio 本地服务适配

本目录提供 `NovelAudioServer v1` 到 VoiceStudio Local API 的独立适配服务。

Android 继续连接本目录提供的六个端点；VoiceStudio 的模型、profile、API
地址和内部 voice/profile 引用只保留在服务端。

## 当前阶段

- macOS 优先验证；
- 默认只绑定 `127.0.0.1`；
- 局域网监听必须显式设置 `NOVEL_AUDIO_ALLOW_LAN=true`，并由外部 TLS/VPN 或可信网络保护；
- TTS 使用 VoiceStudio Local API；
- 章节人物分析保留独立 DirectorProvider，未配置时 `directorReady=false`；
- 不固定 Qwen 模型，实际引擎由 VoiceStudio 配置决定；
- VoiceStudio 默认返回 WAV，服务端使用 ffmpeg 转为 Android 兼容的 Ogg/Opus；
- Windows 使用同一套 v1 服务和 PowerShell 启动入口。

当前为开发中的适配层：离线协议/HTTP 夹具验证与真实模型试听分开验收。
2026-10-08 已在 Apple M2 上接通真实 VoiceStudio 0.5.6，完成音色发现、试听与
三角色合成；Android 真机验收和 Windows 部署验收仍未完成。

## 配置

复制 `config.example.env` 为
`scripts/novel-audio-voicestudio/config.local.env`，并以环境变量方式加载；
该路径已加入 Git 忽略。不要把 Token、voice registry、音频和模型路径写入其他
未忽略文件。

VoiceStudio 的消费接口必须使用受保护的本地或可信局域网地址。不要向公网暴露
VoiceStudio 管理接口。

默认只允许 loopback VoiceStudio 地址；远程地址必须设置
`VOICESTUDIO_ALLOW_REMOTE=true`，且使用 HTTPS。

默认需要系统可执行的 `ffmpeg`；可通过 `FFMPEG_PATH` 指定 Windows 上的完整路径。
默认 `VOICESTUDIO_RESPONSE_FORMAT=wav`，服务端健康检查会确认 ffmpeg 可执行；
如果 VoiceStudio 直接返回 Android 兼容格式，可按实际 API 配置改用其他格式。
重新生成同一 VoiceStudio profile 后，必须递增 `VOICESTUDIO_PROFILE_REVISION`，
并重启适配服务，以隔离 Android 端已有音频缓存。

`NOVEL_AUDIO_REQUEST_TIMEOUT` 是单个请求的整体截止时间（秒，默认 40，取值
1–44），必须小于 Android 端 45 秒的调用超时。超过截止时间返回 504
`provider_timeout`。实测 Apple M2（MPS）上 OmniVoice 约需音频时长的 1.7 倍，
单段约 110 字以内才能在 40 秒内完成；更长的段落需要更快的 GPU。

Android 现有连接测试要求 `directorReady` 和 `ttsReady` 同时为 true。
仅做 TTS 独立开发可以不配置 Director；接入 Android 前须配置并实际验证
提供 `/v1/chapter/analyze` 的 `DIRECTOR_BASE_URL` 与 `DIRECTOR_TOKEN`。
已验证可用百炼临时桥接充当 Director：`DIRECTOR_BASE_URL=http://127.0.0.1:8787`，
`DIRECTOR_TOKEN` 取 `novel-audio.local.connection.json` 中的 `token`；每次章节分析
都会消耗百炼额度。

## 启动

以下命令在仓库根目录执行。启动器使用 PATH 中的 Python，请先激活项目专用
虚拟环境（macOS：`ai_tests/venv`；Windows：`ai_tests/venv`）。
注册表相对路径按进程工作目录解析；任务计划/服务化部署应将
`NOVEL_AUDIO_VOICE_REGISTRY` 配为受保护的绝对路径。

macOS/Linux：

```sh
set -a
. ./scripts/novel-audio-voicestudio/config.local.env
set +a
sh scripts/novel-audio-voicestudio/start.sh --check
sh scripts/novel-audio-voicestudio/start.sh --serve
```

Windows PowerShell：

```powershell
Get-Content .\scripts\novel-audio-voicestudio\config.local.env |
  ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
      [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), "Process")
    }
  }
.\scripts\novel-audio-voicestudio\start.ps1 --check
.\scripts\novel-audio-voicestudio\start.ps1 --serve
```

## v1 端点

```text
GET  /v1/health
POST /v1/chapter/analyze
GET  /v1/voices
POST /v1/voices/match
POST /v1/voices/preview
POST /v1/tts/synthesize
```

`/v1/health` 额外返回 `meteredOperations`，列出会产生外部计费的操作。Android
只对列出的操作扣设备端试用额度：本地 VoiceStudio 合成不计费；配置了外部
Director（如百炼桥接）时 `analysis` 计费。旧服务不返回该字段时，Android 按全部
计费处理。

`/v1/chapter/analyze` 按请求体摘要在内存中缓存最近 32 章的结果（重启清空）：
合成失败后 App 重试同一章不会再次调用 Director；同一章分析进行中又收到相同请求时，
等待前一次结果而不是返回 429。不同章节仍共用单生成槽。

## 本机 VoiceStudio（已验证 0.5.6）

上游为开源项目 `debpalash/VoiceStudio`（AGPL-3.0，默认引擎 OmniVoice，
Apache-2.0，模型约 3.27 GB）。本机验证采用源码 headless 方式，不依赖桌面 UI：

```sh
uv sync --frozen --no-dev --python 3.11
OMNIVOICE_DATA_DIR=<数据目录> HF_ENDPOINT=https://hf-mirror.com \
  uv run --frozen --no-sync uvicorn main:app --app-dir backend \
  --host 127.0.0.1 --port 3900
```

国内网络下 GitHub 与 huggingface.co 很慢或不可达：依赖可把 `uv.lock` 中
`files.pythonhosted.org/packages/` 换成清华镜像同路径（uv 仍按锁文件 sha256 校验），
模型经 `HF_ENDPOINT` 镜像下载后应按 HF 元数据核对 sha256。

音色需在 VoiceStudio 中建成 profile 才会被发现。`/v1/audio/voices` 不带性别年龄，
适配层会读取 `/profiles` 的设计标签（如 `male, middle-aged, low pitch`）补全，
供角色自动选音使用；没有标签的克隆音色显示为 `unknown`。

已知限制：客户端放弃请求后，VoiceStudio 的 `/v1/audio/speech` 不会中止生成，
后续请求需排队等它完成。

## 测试

```sh
ai_tests/venv/bin/python -m unittest discover \
  -s scripts/novel-audio-voicestudio -p 'test_*.py' -v
```

Windows 使用 `ai_tests\venv\Scripts\python.exe -m unittest discover -s scripts/novel-audio-voicestudio -p "test_*.py" -v`。
