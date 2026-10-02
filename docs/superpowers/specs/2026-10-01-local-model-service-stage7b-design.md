# NovelAudio 本地模型服务阶段 7B 设计

状态：已获用户确认

日期：2026-10-01

适用分支：`feat/local-model-service`

## 1. 目标

在 Windows 原生运行时阶段 0～7A 已通过的基础上，完成真正的
NovelAudio 本地服务层：

- 常驻轻量 Agent；
- 按需启动和回收 Model Worker；
- Qwen3.5 4B/9B 章节分析；
- Qwen3-TTS VoiceDesign/Base 合成；
- Profile 注册、完整性校验和能力发现；
- Runtime Lease；
- NovelAudioServer v1 六个端点；
- runtime 扩展端点；
- Windows 安装、启动、停止、检查脚本；
- macOS fake backend 测试和 Windows 真实服务 smoke。

本阶段不修改 Android 产品行为，不采用 VoiceStudio 分支设计，不把
Windows 本地模型文件或本机配置提交到 Git。

## 2. 已确认的 Windows 基线

阶段 0～7A 已由 Windows Agent 完成，作为本设计的真实性基线：

- Windows 11；
- RTX 5090 D v2，24GB，驱动 610.88；
- Windows 原生 CPython 3.12.14；
- `torch/torchaudio 2.9.1+cu130`；
- `qwen-tts 0.1.1`；
- llama.cpp b11320，`sm_120` 可用；
- ffmpeg/ffprobe n9.0.2；
- 9B + VoiceDesign 连续重复和切换通过；
- 4B + Base 连续重复和切换通过；
- 章节分析返回 v1 assignment JSON；
- 生成音频可由 Windows 原生 ffprobe 完整解码；
- release 后 Worker 级模型进程和显存回落；
- Base 必须使用：
  `D:/NovelAudioLocal/state/reference-audio/reference.wav`；
- Base 当前使用 `x_vector_only_mode=true`，没有 `ref_text`；
- 所有模型文件、目录和配置校验值保持不变。

阶段 7A 没有验证以下内容，必须由阶段 7B 完成：

- 真实 NovelAudio HTTP 服务；
- 真实 `/v1/runtime/acquire`、`release`、`status`；
- `X-NovelAudio-Lease`；
- Agent/Worker 之间的生产 IPC；
- HTTP 六端点到真实 Qwen backend 的完整链路；
- Android 到 Windows 联调。

## 3. 分支与工作区边界

- Android 主线仍为 `feat/ai-audiobook`；
- Windows 服务只在 `.worktrees/local-model-service` 的
  `feat/local-model-service` 上实现；
- `feat/ai-audiobook-voicestudio` 是独立的新设计线，本阶段不修改；
- 当前 `local-model-service` worktree 已存在其他未提交改动。
  实现时必须先区分已有改动和本阶段新增改动，不得 reset、checkout、
  覆盖或清理用户文件；
- 提交时只 `git add` 本阶段明确完成的路径，不能使用 `git add -A`；
- Android 文件不是本阶段默认修改目标，即使 worktree 中已有 Android
  未提交改动，也必须保留并避开。

## 4. 总体架构

```text
NovelAudio HTTP Client
        │
        ▼
常驻 Agent
  ├─ Bearer Token
  ├─ v1 HTTP/API dispatcher
  ├─ Model Registry / Profile
  ├─ Voice Catalog
  └─ Runtime Lease
        │ bounded JSONL IPC
        ▼
按需 Model Worker
  ├─ Qwen3.5 llama-server 子进程
  ├─ qwen-tts Python/Torch
  ├─ ffmpeg 音频转换
  └─ bounded cleanup
```

Agent 进程不得导入 `torch` 或 `qwen_tts`，不得加载模型或初始化
CUDA。只有 Worker 可以加载重型运行时。

一个 active Profile 一次只允许一个 active Lease，默认
`maxConcurrency=1`。Profile 切换必须先完成 release、Worker 退出和显存
回落，再启动新的 Profile。

## 5. Runtime Lease 设计

### 5.1 显式批次模式

```text
POST /v1/runtime/acquire
  → Worker 启动并加载 active Profile
  → 多次 analyze / preview / synthesize
POST /v1/runtime/release
  → 停止接收新请求
  → 有界等待或取消当前请求
  → 关闭 Worker 及其子进程
  → 验证进程退出和状态回到 idle
```

显式 Lease 返回：

- opaque `leaseId`；
- `runtimeProfile`；
- 脱敏的 `runtimeProfileInfo`；
- `profileId`；
- `identity`；
- `capabilities`；
- `hardware.status`。

不得返回模型路径、参考音频路径、命令行、许可证原文或本机目录。

### 5.2 兼容请求模式

现有六个 v1 端点没有 `X-NovelAudio-Lease` 时，服务自动执行：

```text
acquire
  → 单次请求
  → finally release
```

带有有效 `X-NovelAudio-Lease` 时复用该 Lease，不重复加载模型。

这样可以兼容现有 Android v1 客户端，同时为后续章节批处理提供显式
Lease。请求级 Lease 的清理必须放在 `finally`，包括业务异常、超时和
客户端连接断开。

### 5.3 状态和错误

Runtime 状态至少包含：

- `idle`；
- `starting`；
- `ready`；
- `generating`；
- `unloading`；
- `failed`。

固定错误语义：

- 第二个 active Lease：HTTP 429；
- Lease 无效或过期：HTTP 401/409，使用固定错误码；
- Base 缺少参考音频：`missing_reference_audio`；
- Profile 能力不足：`capability_unavailable`；
- Worker 启动或退出失败：`worker_unavailable`；
- backend 响应非法：`invalid_backend_response`。

错误响应不得包含原始异常、正文、模型路径、Token 或命令行。

## 6. Model Registry 与 Profile

### 6.1 资产完整性

注册表中的每个资产必须包含：

- `assetId`；
- `type`、`family`、`format`；
- Windows 路径；
- `sha256` 或目录文件清单；
- `source`、`license`；
- `requiredVramGb`；
- `capabilities`；
- `adapter`；
- `enabled`。

校验规则：

- GGUF 使用文件 SHA-256；
- TTS 目录使用逐文件 SHA-256 清单；
- 路径存在且可读；
- 哈希缺失或不匹配时不能进入 `ready`；
- Base 参考音频必须存在、可读、可解码，并位于允许的服务目录；
- 禁止使用 `..` 越出服务根目录；
- 不能通过检查后再静默使用另一份模型。

### 6.2 能力校验

不对文本模型和 TTS 模型简单求数组交集，而是按操作归属校验：

- text asset 必须提供 `chapter_analysis`；
- VoiceDesign TTS asset 必须提供 `voice_design`；
- Base TTS asset 必须提供 `voice_clone`；
- voice catalog 中的 voice 必须匹配当前 TTS capability；
- Base 缺少 `reference.wav` 时 Profile 为
  `blocked_missing_reference_audio`；
- VoiceDesign voice 不得发送到 Base；
- Base voice 不得发送到 VoiceDesign。

### 6.3 Profile identity

Profile identity 必须包含：

- registry version；
- text asset ID、实际 hash、adapter；
- TTS asset ID、实际 hash、adapter；
- Voice Catalog hash/version；
- Base `reference.wav` hash；
- Profile capabilities；
- 编码和合成参数版本。

因此模型、音色 prompt、参考音频或编码行为变化后，都会产生新的
`X-TTS-Profile`。Android 后续应将它纳入 artifact/cache key。

## 7. `--check`

`--check` 是只检查，不加载完整模型、不启动 HTTP 服务、不生成音频。

静态检查：

- JSON 配置和注册表；
- active Profile；
- 资产存在性、可读性和 hash；
- text/TTS 类型；
- adapter；
- Profile capability；
- Base reference audio；
- Profile identity；
- 旧配置冲突。

运行时检查：

- Windows CPython 版本和架构；
- `qwen_tts` 导入；
- torch/torchaudio 版本；
- `torch.cuda.is_available()`；
- `cuda:0` 是否为 RTX 5090 D v2；
- llama-server 版本和 CUDA 参数；
- ffmpeg/ffprobe 版本及 Ogg/Opus 能力。

结果使用固定字段和状态：

- `PASS`；
- `FAIL`；
- `BLOCKED`；
- `NOT_CHECKED`。

退出码必须反映全部检查结果，不能只根据 voice catalog 是否存在决定
成功。CLI 可以为操作员显示实际路径；HTTP 响应不允许显示绝对路径。

## 8. Qwen Adapter 与 Worker

### 8.1 文本 adapter

文本 adapter 通过 Windows 原生 llama-server 的 localhost
OpenAI-compatible 接口工作：

- 使用 Profile 选定的 4B 或 9B；
- CUDA0；
- 固定 loopback；
- 结构化命令参数；
- `enable_thinking=false`；
- JSON response format；
- 有界连接、读取、响应大小和超时；
- 拒绝重定向；
- 通过共享 `analysis_response` 做最终校验。

必须真正启动并管理 llama-server，不能保留永远抛出
`BackendError` 的占位 `_request()`，也不能只启动 `--port 0` 而不读取
实际端口。

### 8.2 TTS adapter

TTS adapter 只在 Worker 内运行：

- VoiceDesign 调用 `generate_voice_design`；
- Base 调用 `generate_voice_clone`；
- Base 校验 `reference.wav`；
- 没有 `ref_text` 时显式使用 `x_vector_only_mode=true`；
- 不安装或依赖 Windows 缺失的 FlashAttention；
- 先生成并校验 WAV/PCM；
- 使用 Windows 原生 ffmpeg 转 Ogg/Opus；
- 校验非空、大小、可解码性；
- 返回实际 MIME 和 `X-TTS-Profile`。

不得把 Base 的参考音频传给 VoiceDesign，也不得把 VoiceDesign prompt
传给 Base。

### 8.3 Worker IPC

Worker 使用有界 JSON Lines：

- stdout 仅输出协议响应；
- 单行大小有限制；
- 单请求有超时；
- 非法 operation 立即拒绝；
- Worker 的 `finally` 必须关闭 backend；
- Agent 关闭时必须回收 Windows 子进程树；
- close 幂等；
- Worker 失败后清除 Lease，Agent 保持可重新 acquire。

Windows 进程回收不能只调用 Python 父进程 `kill` 后就宣布成功，必须验证
llama-server、TTS Python 和相关子进程都已退出。

## 9. HTTP 端点

保留 v1 六端点及字段：

- `GET /v1/health`；
- `POST /v1/chapter/analyze`；
- `GET /v1/voices`；
- `POST /v1/voices/match`；
- `POST /v1/voices/preview`；
- `POST /v1/tts/synthesize`。

新增：

- `POST /v1/runtime/acquire`；
- `POST /v1/runtime/release`；
- `GET /v1/runtime/status`。

公共安全边界保持：

- 默认 `127.0.0.1`；
- Bearer Token 只在 header；
- 鉴权先于 body 解析；
- 拒绝重复 Content-Length、Transfer-Encoding 和重定向；
- JSON/音频大小上限；
- 不记录正文、Token、参考文本和原始模型响应；
- runtime metadata 只经过白名单投影。

## 10. 测试与验收

### 10.1 macOS 测试

不依赖真实模型的测试覆盖：

- registry/hash/profile identity；
- voice capability 过滤；
- `--check` 状态和退出码；
- fake backend；
- Runtime Lease 状态机；
- 请求级和显式 Lease；
- HTTP 六端点和三个 runtime 端点；
- Worker IPC 超时、非法响应和 close；
- 配置、CLI 和脱敏；
- 既有 Bailian bridge 回归。

Qwen adapter 使用 subprocess/HTTP doubles，不加载真实 Windows 模型。

### 10.2 Windows 阶段 7B

Windows Agent 只执行，不修改 Git，记录：

- 每一步完整命令；
- exit code；
- 关键输出；
- 4B/9B 分析结果；
- VoiceDesign/Base 音频属性；
- `acquire → generate → release`；
- 无 Lease 请求的自动生命周期；
- 第二次 acquire；
- Profile 切换；
- Worker 失败后的恢复；
- `--status`、`--stop`；
- 进程树、端口和显存回收；
- 模型 hash、配置、防火墙和网络状态。

阶段 7B 只有在 macOS 测试全绿、Windows 真实服务 smoke 全绿、无残留
进程和显存回落均有证据时，才允许进入 Android 集成。

### 10.3 Android 延后门禁

在阶段 7B 通过前，不修改 Android 生产行为。通过后再在
`feat/ai-audiobook` 开新批次处理：

- runtime profile info；
- profile/server scope 缓存隔离；
- `X-TTS-Profile`；
- 能力刷新；
- 明确错误；
- Lease 接线；
- 离线旧音频保留；
- Android 真机到 Windows 服务联调。

## 11. 非目标

本阶段不做：

- VoiceStudio 分支合并；
- Android 预加载策略改造；
- 新模型下载；
- 模型权重复制；
- Windows 托盘 UI 最终验收；
- 在线 Provider 统一；
- 云端 API Key 接入；
- Android 正式包发布。

## 12. 完成定义

阶段 7B 完成必须同时满足：

- 服务代码不再依赖占位 Qwen 调用；
- 4B/9B 和 VoiceDesign/Base 通过真实 Worker；
- Base 参考音频缺失时明确阻塞；
- registry/hash/profile identity 有真实校验；
- `--check` 能发现缺失、哈希不匹配、CUDA 不可用和能力冲突；
- 双模式 Lease 通过 Mac contract tests 和 Windows smoke；
- HTTP 六端点和 runtime 端点通过；
- Worker 进程树、端口和显存回收通过；
- 既有 Bailian bridge 无回归；
- Windows 报告保留命令、exit code、音频属性和显存证据；
- Android 未在本阶段提前改动。
