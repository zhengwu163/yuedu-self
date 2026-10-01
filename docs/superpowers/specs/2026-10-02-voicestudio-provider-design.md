# VoiceStudio 本地语音引擎替换方案

## 目标

在 `feat/ai-audiobook-voicestudio` 分支中，把 VoiceStudio 作为可替换的本地语音引擎宿主，替代当前百炼桥接中的 TTS 模型、音色目录和部分生成服务能力，同时保持 Android 端已有 AI 听书体验与 `NovelAudioServer v1` 契约不变。

第一阶段优先在当前 macOS Apple Silicon 环境验证，最终把同一套服务部署到 Windows + RTX 5090D v2。模型不绑定 Qwen；优先使用 VoiceStudio 当前可用且实际试听通过的引擎。

## 已确认的边界

### Android 端保留

- `NovelAudioServer v1` 六个 HTTP 端点及 Bearer 鉴权。
- 章节正文、章节快照、人物 Registry、稳定别名和角色绑定持久化。
- Room 计划、音频 artifact、音频下载、坏音频修复和 profile 缓存隔离。
- 当前章准备、AUTO 后三章预取、PINNED 手动下载和重启恢复。
- 后台播放位置持久化、跨章续播、飞行模式播放和普通 TTS/HTTP TTS 兼容。
- 本地预算账本、取消传播、严格离线门禁和 Android 侧协议校验。

Android 不直接知道 VoiceStudio 的内部模型名、端口、文件路径或 profile 格式。

### 服务端替换

新建独立的 `scripts/novel-audio-voicestudio/` 服务端适配目录，提供：

- `NovelAudioServer v1` 协议网关；
- VoiceStudio Local API 传输适配；
- 可替换的 TTS Provider；
- 可替换的 Director Provider；
- 服务端自己的 voiceAssetId 注册表；
- 音频格式归一化和 `X-TTS-Profile` 生成；
- Mac 本地验证入口和 Windows 部署入口。

VoiceStudio 作为外部进程运行，不把其 Electron、AGPL 应用代码或 Python 依赖嵌入 Android 工程。

## 架构

```text
Android Legado
    │
    │ NovelAudioServer v1
    ▼
NovelAudio v1 Gateway
    ├── DirectorProvider
    │     └── 当前阶段：复用现有章节分析实现
    │
    ├── SpeechProvider
    │     └── VoiceStudioProvider
    │           └── VoiceStudio Local API
    │                 └── 可选 OmniVoice / CosyVoice / VoxCPM / 其他引擎
    │
    ├── VoiceRegistry
    │     └── voiceAssetId → VoiceStudio profile/model/reference voice
    │
    ├── AudioNormalizer
    │     └── Ogg/Opus 或 AAC
    │
    └── RequestPolicy
          └── 鉴权、大小、超时、取消、并发、错误脱敏
```

### Provider 接口

TTS Provider 只处理通用语音能力：

```python
class SpeechProvider(Protocol):
    def health(self) -> SpeechHealth: ...
    def voices(self) -> list[VoiceProfile]: ...
    def match(self, request: VoiceMatchInput) -> list[VoiceProfile]: ...
    def preview(self, request: SpeechRequest) -> AudioResult: ...
    def synthesize(self, request: SpeechRequest) -> AudioResult: ...
```

Director Provider 单独处理章节人物分析：

```python
class DirectorProvider(Protocol):
    def health(self) -> bool: ...
    def analyze(self, request: ChapterAnalysisInput) -> ChapterAnalysisOutput: ...
```

这样 VoiceStudio 不需要承担小说语义理解；未来可以把 Director 替换成本地 LLM、在线 LLM 或规则/混合实现，而不修改 TTS 代码。

### VoiceStudio 绑定

Android 使用的 `voiceAssetId` 是适配器生成的 opaque ID。服务端内部保存：

```json
{
  "voiceAssetId": "voicestudio.profile.narrator",
  "displayName": "旁白",
  "engine": "voicestudio",
  "model": "configured-by-voicestudio",
  "profile": "local-profile-id",
  "referenceAudio": "local-only-path-or-profile-ref",
  "language": "zh-CN"
}
```

绝不把本地磁盘路径返回给 Android。模型切换、音色重新生成或 VoiceStudio profile 变化时，必须改变 `X-TTS-Profile`，让 Android 缓存自动隔离。

### VoiceStudio API 适配

第一阶段不假设所有 VoiceStudio 引擎共享完全相同的参数。适配器通过配置描述：

- VoiceStudio API 根地址；
- API key 或局域网认证方式；
- TTS 路径；
- 语音/profile 参数；
- 返回音频格式；
- 可选模型或 engine 参数；
- 超时和最大并发。

默认优先使用本机 loopback；Windows 手机联调时使用可信局域网或 TLS/反向代理，禁止公网暴露。

## 请求流程

### 章节分析

```text
POST /v1/chapter/analyze
  → RequestPolicy 校验
  → DirectorProvider.analyze
  → v1 响应投影
```

服务端不改写正文，Android 仍使用原始 TextUnit 作为 TTS 输入。

### 语音生成

```text
POST /v1/tts/synthesize
  → RequestPolicy 校验
  → VoiceRegistry 解析 voiceAssetId
  → VoiceStudioProvider.synthesize
  → AudioNormalizer 统一格式
  → 返回音频 + X-TTS-Profile
```

单次请求严格遵守 Android 当前 1200 UTF-16 字符和 16 MiB 音频上限。适配器可以在服务端继续分块，但不能改变 Android 的章节/segment 身份。

## 错误与安全

- 不自动重试 TTS 或章节分析请求，避免客户端超时后重复生成。
- 取消请求必须传递到 VoiceStudio HTTP 请求或 worker。
- VoiceStudio 原始错误正文不返回 Android，不记录正文、Token、参考音频路径或签名 URL。
- `401/403` 归一化为鉴权错误；`429` 归一化为繁忙/限流；`5xx` 归一化为服务不可用。
- VoiceStudio 的管理端点不暴露给 Android；适配器只允许语音消费端点。
- 本地 voice registry、API key、profile 配置和模型路径不进入 Git。
- VoiceStudio AGPL 代码保持进程边界；模型权重许可证单独记录并在选择引擎前审查。

## 分阶段交付

### Phase 1：Mac 协议与短句验证

- 创建独立分支和服务目录。
- 完成 v1 网关、Provider 接口、VoiceStudio transport、voice registry。
- 用 VoiceStudio 当前可用引擎生成旁白/男声/女声三段中文短句。
- 完成六端点离线契约测试和真实本机 TTS 烟测。
- 不修改 Android 业务链路。

### Phase 2：Android 联调

- 用当前 Android debug 包连接 Mac 服务。
- 验证角色绑定、单章生成、下载、播放、profile 缓存隔离。
- 补齐 Android 侧必要的服务能力展示，但不泄漏 VoiceStudio 内部字段。

### Phase 3：Windows 部署

- 提供 Windows PowerShell 启动和健康检查入口。
- 在 RTX 5090D v2 上验证 VoiceStudio 引擎、模型常驻/切换和显存回收。
- 通过局域网安全连接完成 Android 真机验收。
- 保留 Mac 与 Windows 的同一 v1 协议和服务配置语义。

## 验收标准

### 协议

- 六个 v1 端点全部通过认证、参数、大小、错误和取消测试。
- `voices/match` 候选稳定、排除已使用音色。
- 音频响应有合法 Content-Type、非空 body 和非空 `X-TTS-Profile`。

### 真实语音

- 至少三种可区分的中文角色声音。
- 旁白、成年男声、成年女声短句试听成功。
- 不强制指定 Qwen；实际使用的 VoiceStudio 引擎和模型以实测结果为准。
- 需要用户真实试听确认，测试音不能替代真实模型结果。

### Android 产品要求

- 现有 Android JVM 和定向设备测试不因服务端替换回归。
- 飞行模式下已下载音频可连续播放且不发起服务请求。
- 杀进程后能恢复播放位置和未完成的固定下载。
- AUTO/PINNED、坏音频重排队、旧 generation 隔离和普通 TTS/HTTP TTS 不回归。

### 平台

- Mac 可独立启动并完成 Phase 1。
- Windows 启动方式、模型目录、端口、鉴权和日志行为有文档。
- Windows 服务不要求 Android 安装 Python、VoiceStudio 或模型。
