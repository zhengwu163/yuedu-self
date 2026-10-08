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
客户端取消传播、整个请求的绝对超时、Director 实际健康探测和发现失败时的
旧注册表缓存处理已有离线与 loopback 回归覆盖；仍不能替代真实模型试听、Android
真机验收或 Windows 部署验收。

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

Android 现有连接测试要求 `directorReady` 和 `ttsReady` 同时为 true。
仅做 TTS 独立开发可以不配置 Director；接入 Android 前须配置并实际验证
提供 `/v1/chapter/analyze` 的 `DIRECTOR_BASE_URL` 与 `DIRECTOR_TOKEN`。

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

## 测试

```sh
ai_tests/venv/bin/python -m unittest discover \
  -s scripts/novel-audio-voicestudio -p 'test_*.py' -v
```

Windows 使用 `ai_tests\venv\Scripts\python.exe -m unittest discover -s scripts/novel-audio-voicestudio -p "test_*.py" -v`。
