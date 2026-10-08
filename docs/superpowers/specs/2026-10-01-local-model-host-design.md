# AI 多角色听书本地模型服务与按需预加载设计

状态：已获用户确认，2026-10-01

## 1. 目标与范围

本设计把已完成的 Android NovelAudio 管线连接到用户自己部署的 Windows 本地模型服务，
并建立按需加载、批次生成、自动卸载和可配置预加载的完整边界。

目标：

- Windows 端提供任何兼容客户端都可以连接的 NovelAudioServer 服务；
- Windows 端只常驻轻量唤醒代理，不常驻加载文本模型或 TTS 模型；
- 手机端有请求时才启动模型 Worker；
- Worker 在一个准备批次内连续完成章节分析和语音合成；
- 当前批次完成后自动卸载模型并退出 Worker；
- 普通播放默认自动准备后续 3 章；
- 用户主动预加载时可以选择任意剩余章节数量，不受 3 章窗口限制；
- 播放到连续 READY 内容的末尾时，仍有效的预加载会话自动追加后续 3 章；
- 手机端和 Windows 服务通过公开协议连接，不绑定固定设备或固定模型；
- 提供包含服务程序、运行时、模型清单和 Windows 启动脚本的本地模型包；
- 后续在线模型使用可配置的 API Key、文本模型和语音模型；
- 最终将模型提供方与人物、章节、缓存、播放等产品逻辑解耦。

本设计不改变以下权威边界：

- Android 持有正文快照、人物注册、音色绑定、章节计划、音频缓存和播放进度；
- Windows 服务不抓取书源，不自行修改正文，不持久化 Android 的人物数据库；
- 音频是否完整、是否 READY、是否可以离线播放由 Android 判定；
- NovelAudioServer v1 的六个推理接口继续兼容；
- 模型权重、API Key、服务 Token 和本机运行状态不提交 Git。

## 2. 当前基础

现有仓库已经具备：

- `NovelAudioServer v1` 六个接口及严格请求/响应校验；
- Android `NovelAudioServerClient`；
- 章节正文快照、TextUnit、人物注册和 VoiceBinding；
- Room 中的章节 plan、任务状态、音频 artifact 和 generation fence；
- 当前章优先、AUTO 后续三章、PINNED 手动下载和本地优先播放链路；
- 百炼桥接及独立开发 Mock；
- 真实百炼三角色试听证据。

当前缺口：

- 没有 Windows 本地模型服务；
- 没有轻量唤醒代理和模型 Worker 生命周期；
- Android 的自动窗口 3 仍然硬编码在播放授权逻辑中；
- 手动预加载仍带有旧的固定数量限制；
- 没有本地模型连接设置和服务能力发现；
- 现有协议适配层仍包含百炼专属音色和云额度假设；
- 在线模型配置尚未抽象为独立 Provider profile。

现有 Android 自动预取和任务恢复代码继续作为产品侧基础：

- `AudioPrefetchSession`
- `AudioPrefetchLifecycle`
- `NovelAudioAutoPrefetchScheduler`
- `NovelAudioAutoPrefetchCoordinator`
- `NovelAudioPreparationCoordinator`
- `NovelAudioDownloadCoordinator`

## 3. 总体架构

```text
Android App
  ├─ 正文快照、人物、VoiceBinding、章节计划、音频缓存
  ├─ AUTO / PINNED 预加载调度
  ├─ NovelAudioServer v1 客户端
  └─ 本地模型连接配置
             │
             │ Bearer Token + NovelAudio v1
             ▼
Windows Local Model Agent
  ├─ 轻量常驻唤醒代理
  ├─ Token、配置、能力发现和会话 Lease
  ├─ Model Worker 启动/停止
  └─ 不加载模型、不占用显存
             │
             ▼
Model Worker
  ├─ Qwen3.5 9B Q4_K_M（默认文本分析）
  ├─ Qwen3.5 4B Q4_K_M（低显存/快速模式）
  ├─ Qwen3-TTS VoiceDesign
  ├─ Qwen3-TTS Base
  ├─ 本地声库与稳定 voiceAssetId
  └─ 音频编码、profile 和错误脱敏
```

轻量 Agent 可以作为 Windows 服务或托盘启动项运行。它的职责只包括：

- 监听手机连接；
- 校验服务 Token；
- 读取本机配置；
- 启动和回收 Model Worker；
- 转发受协议约束的请求；
- 对外报告模型能力和运行状态；
- 在停止、超时或异常时终止 Worker 及其子进程。

模型只存在于 Worker 生命周期内。Agent 不导入 PyTorch，不加载 GGUF，不初始化 CUDA。

## 4. Windows 模型生命周期

### 4.1 生命周期状态

Agent 管理以下状态：

```text
IDLE
  → STARTING
  → LOADING
  → READY
  → GENERATING
  → UNLOADING
  → IDLE
```

异常状态进入 `FAILED`，清理 Worker 后允许下一次显式请求重新启动。

状态含义：

- `IDLE`：代理运行，显存中没有本地模型；
- `STARTING`：已接受会话，正在创建 Worker；
- `LOADING`：Worker 正在加载文本模型或 TTS 模型；
- `READY`：模型已加载，等待生成请求；
- `GENERATING`：至少一个请求正在处理；
- `UNLOADING`：停止生成、释放模型和 CUDA 资源；
- `FAILED`：当前批次失败，已完成内容不回滚。

### 4.2 会话 Lease

为了让连续章节共用一次模型加载，客户端先申请一个运行会话：

```http
POST /v1/runtime/acquire
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "sessionId": "android-generated-session-id",
  "purpose": "auto_prefetch",
  "expectedChapterCount": 3
}
```

服务返回一次性 Lease：

```json
{
  "leaseId": "opaque-server-scoped-id",
  "runtimeProfile": "qwen35-9b-voicedesign-<identity>",
  "runtimeProfileInfo": {
    "profileId": "qwen35-9b-voicedesign",
    "identity": "qwen35-9b-voicedesign-<identity>",
    "capabilities": [
      "chapter-analysis",
      "speech-synthesis",
      "voice-design",
      "zh-CN"
    ],
    "minVramGb": 24,
    "hardware": {
      "status": "deferred"
    }
  }
}
```

后续六个推理接口通过 `X-NovelAudio-Lease` header 关联该 Lease。
Lease 只控制模型生命周期，不改变 Android 的任务、缓存和 READY 状态。

`runtimeProfile` 保留为兼容用的字符串；`runtimeProfileInfo` 是新增的脱敏能力
描述。它不包含模型文件路径、下载来源或许可证原文。Android 可以用
`identity` 参与音频缓存隔离，模型切换后不会误复用新 Profile 的音频。

批次完成后客户端调用：

```http
POST /v1/runtime/release
Authorization: Bearer <token>
X-NovelAudio-Lease: <leaseId>
```

服务收到 release 后：

1. 拒绝该 Lease 的新生成请求；
2. 等待当前不可取消的本地推理安全结束，或执行有界取消；
3. 卸载 TTS 模型；
4. 卸载文本模型；
5. 回收 CUDA、子进程和临时文件；
6. 将状态恢复为 `IDLE`。

客户端断开、应用崩溃、Lease 超时或 Agent 关闭时执行同样的清理流程。

### 4.3 运行状态接口

```http
GET /v1/runtime/status
Authorization: Bearer <token>
```

返回：

```json
{
  "state": "idle",
  "directorLoaded": false,
  "ttsLoaded": false,
  "activeLease": false,
  "runtimeProfile": "qwen3.5-9b+qwen3-tts-v1"
}
```

运行状态只用于设置页展示、诊断和测试，不作为 Android 判断章节 READY 的依据。

### 4.4 兼容性

六个 v1 推理接口保持现有字段和鉴权规则。运行时接口属于能力扩展：

- 支持运行时 Lease 的服务可以显式控制模型加载和卸载；
- 只支持六个基础接口的服务仍可完成单次推理；
- Android 根据能力发现决定是否调用 Lease；
- 不支持 Lease 时，服务必须在首次推理时按需加载，并在有界空闲后自动卸载；
- 服务 URL 仍然是根地址，不包含 `/v1`、凭据、query 或 fragment。

## 5. 本地模型适配

### 5.1 文本分析

文本分析使用本地 Qwen3.5 GGUF 模型：

- 默认配置：9B Q4_K_M；
- 降级配置：4B Q4_K_M；
- 输入：Android 的章节单元、已知人物、别名和有限上下文；
- 输出：`assignments`、`newCharacters`、`aliasUpdates`；
- 不允许输出正文改写、音频路径、命令或配置修改；
- 服务端继续执行协议级 unit、speaker、temporaryId 和 alias 校验。

文本模型运行时通过配置的本地模型 Runner 启动，模型路径、上下文长度、
GPU 层数和端口不写死在 Android。Windows 模型包提供默认 Runner 和启动参数，
用户也可以在服务配置中替换路径。

### 5.2 VoiceDesign

VoiceDesign 声音由本地声库配置声明：

```json
{
  "voiceAssetId": "local.qwen3-tts.voice.young-male",
  "displayName": "青年男声",
  "gender": "male",
  "ageRange": "young_adult",
  "traits": ["清朗", "自然"],
  "voiceMode": "voice_design",
  "voicePrompt": "成年男性，清朗自然，语速平稳，适合中文小说对白"
}
```

`voiceAssetId` 是服务作用域内的 opaque ID。VoiceDesign 的 prompt 固定保存在
声库配置中，不能因为每次匹配而随机变化。

### 5.3 Base

Base 声音使用显式 reference audio：

```json
{
  "voiceAssetId": "local.qwen3-tts.reference.narrator",
  "displayName": "旁白参考声",
  "voiceMode": "reference_audio",
  "referenceAudio": "voices/narrator/reference.wav"
}
```

服务必须校验 reference audio 存在、格式可读且属于当前服务配置目录。
缺少参考音频时，该 voiceAsset 不出现在可用目录中，不返回“可试听但实际失败”的假能力。

### 5.4 TTS 输出

- Android 原始正文始终作为合成输入；
- 服务按本地模型限制切分，但不修改文本语义；
- 统一输出 `audio/ogg`；
- 响应必须携带 `X-TTS-Profile`；
- profile 包含文本模型、TTS 模型、声库版本、合成参数和音频编码版本；
- 模型或声库改变时必须更新 profile；
- Android 通过 profile 隔离旧缓存。

## 6. Android 预加载与播放前沿

### 6.1 普通播放

一次明确的播放动作创建 AUTO 会话。默认窗口为当前章之后的 3 章：

```text
当前章 READY
  → 追加 next + 1 .. next + 3
  → 播放推进
  → 到达连续 READY 前沿
  → 再申请一次运行 Lease
  → 追加后续 3 章
```

暂停、停止、切书、朗读服务销毁或新的显式播放动作会撤销 AUTO 会话。
AUTO 会话不因进程重启自动恢复。

### 6.2 手动预加载

预加载按钮提供：

- 后 3 章；
- 后 10 章；
- 后 20 章；
- 自定义章节数；
- 直到本书末尾。

手动选择的总章节数不受 3 章或 20 章的产品硬上限限制。
实际边界由剩余章节、磁盘空间、网络策略、用户取消和任务失败决定。

手动预加载创建 PINNED 任务，并向 Windows 服务按批次发送请求。
服务每批可以使用 3 章作为内存和恢复粒度，但不会改变用户选定的总范围。

### 6.3 触发后续三章

Android 维护“连续 READY 前沿”，而不是只检查最后一次请求是否完成。

当满足以下条件时追加窗口：

- 当前播放章节已经到达连续 READY 前沿；
- 仍有有效的 AUTO 或手动预加载会话；
- 书籍未到末尾；
- 本地没有正在处理同一范围的任务；
- 当前网络、空间和服务能力检查通过。

追加窗口固定为后续 3 章。手动预加载可以先完成用户指定的任意数量，
随后在用户继续播放并保持预加载会话有效时按 3 章滚动追加。

停止预加载后不再追加新任务，已完成的 PINNED 内容保留。

### 6.4 手机和 Windows 的职责

Android 负责：

- 选择章节范围；
- 提供稳定正文快照；
- 创建人物和音色绑定；
- 调度章节和分段；
- 保存音频文件；
- 验证文件、校验和和完整覆盖；
- 判断 READY、PARTIAL、FAILED；
- 播放和恢复位置。

Windows 服务负责：

- 加载本地模型；
- 处理分析和合成请求；
- 在 Lease 内连续执行批次；
- 返回音频和模型 profile；
- 管理显存、子进程和临时文件；
- 在批次完成后卸载模型。

## 7. 本地模型连接与可部署性

Android 设置增加“本地模型服务”配置：

- 服务地址；
- Bearer Token；
- 是否允许明文 HTTP；
- 测试连接；
- 查看服务能力；
- 查看模型 profile；
- 查看当前运行状态；
- 重新加载音色目录。

连接信息按服务器作用域保存。更换服务地址或 `serverId` 后：

- 不复用旧服务器的音频；
- 不自动沿用不兼容的 voiceAssetId；
- 保留人物 ID；
- 要求重新匹配本地音色；
- 旧 PINNED 音频仍可按原服务器作用域离线播放。

任何能够实现 NovelAudioServer v1 和运行时能力扩展的服务都可以连接，
不要求使用本仓库提供的 Windows 包。

## 8. Windows 本地模型包

发布包采用以下结构：

```text
NovelAudioLocal/
├─ agent/
├─ worker/
├─ runtime/
├─ config/
├─ models/
│  ├─ qwen3.5-4b-q4_k_m/
│  ├─ qwen3.5-9b-q4_k_m/
│  ├─ qwen3-tts-voicedesign/
│  └─ qwen3-tts-base/
├─ voices/
├─ licenses/
├─ install.ps1
├─ start-agent.ps1
├─ stop-agent.ps1
├─ check-models.ps1
└─ README.md
```

模型文件不进入 Git。包内提供：

- 模型清单；
- SHA-256；
- 许可文件；
- CUDA/PyTorch/Runner 兼容说明；
- 24GB 显存配置；
- 4B/9B 切换说明；
- 首次启动检查；
- 本地 Token 生成；
- 服务连接配置导出；
- 三角色原创 smoke；
- 模型卸载验证。

部署脚本必须支持已有模型目录，不强制重复下载。

### 8.1 用户绑定模型与未来切换

发布包不携带模型权重。桌面控制端提供模型绑定引导，用户从模型的官方来源
自行下载后选择本地文件或目录。绑定信息写入本机状态目录，不进入 Git，不写入
Android 数据库，也不通过服务 API 暴露绝对路径。

模型注册表把每个模型描述为独立资产：

```json
{
  "assetId": "user-text-model",
  "type": "text",
  "family": "任意模型家族",
  "format": "gguf",
  "path": "D:/模型/...",
  "adapter": "llama.cpp-openai-compatible",
  "requiredVramGb": 24,
  "capabilities": ["chapter-analysis", "zh-CN"]
}
```

Runtime Profile 只引用文本资产和 TTS 资产，并声明最低显存、并发限制和能力。
当前 Qwen3.5/Qwen3-TTS 组合是推荐模板，不是代码白名单。未来同一适配器支持
的新模型只需重新绑定；全新模型家族只需增加对应 Worker adapter，Android v1
协议和章节计划不变。

每个 Profile 生成稳定 identity，Android 将其纳入音频缓存和 generation key。
模型、适配器、声库或合成参数变化时，旧音频不会被误认为新 Profile 的产物，
但完整的旧音频仍可离线播放。

## 9. 在线模型 Provider

本地服务完成后，产品层引入统一 Provider 抽象：

```text
ChapterDirector
SpeechSynthesizer
VoiceCatalog
ProviderProfile
```

在线 Provider profile 支持用户配置：

- API Endpoint；
- API Key；
- 文本模型；
- TTS 模型；
- 音色目录或音色 ID；
- 语种、速度和模型 profile。

API Key 使用 Android Keystore 加密保存，不写入普通 JSON、日志、导出或音频缓存键。
在线 Provider 可以直接从 Android 调用，也可以通过受授权的远程服务调用；
产品层不再根据“百炼/本地”写分支。

本地 Provider 的服务 Token 和在线 Provider 的 API Key 分开管理，
不能相互复用或写入同一个明文配置。

## 10. 安全与资源边界

- Agent 默认只监听 loopback；跨设备连接时必须显式配置局域网地址和防火墙规则；
- 所有接口使用 Bearer Token；
- Token 不进入 URL、正文日志、错误正文或音频文件名；
- 服务不记录正文、模型原始响应、参考音频内容或 API Key；
- Worker 请求、子进程和音频转码均有硬超时；
- 一个服务默认只允许一个模型生成批次；
- Worker 异常、客户端断开和 Lease 超时都必须回收模型；
- 临时音频使用同目录临时文件，校验后原子替换；
- 模型包安装前校验文件哈希；
- 24GB 显存配置必须明确限制并发，避免文本模型和 TTS 模型同时超出显存；
- 本地模型许可证和第三方运行时许可证随包保存。

## 11. 测试与验收

### 11.1 Windows 服务测试

使用假的 Director 和 TTS backend 覆盖：

- Agent 鉴权、能力发现和连接配置；
- acquire/release Lease；
- 重复 acquire、过期 Lease 和重复 release；
- Worker 启动失败、模型加载失败和 TTS 失败；
- 生成批次完成后 Worker 退出；
- 客户端断开和 Agent 关闭时子进程回收；
- 模型未加载时 health 仍能报告服务能力；
- 生成过程中不允许第二个批次越过并发限制；
- voiceAssetId、profile 和音频 MIME 校验；
- 所有错误不泄露正文、路径、Token 或模型原始响应。

### 11.2 Windows 真实 smoke

在 RTX 5090D v2 服务器上验证：

1. Agent 启动但显存中没有模型；
2. 手机或命令行 acquire 后加载 Qwen3.5 和 Qwen3-TTS；
3. 完成原创三角色章节分析；
4. 生成三段 Ogg 音频并完整解码；
5. release 后模型进程退出；
6. 显存回落到空闲基线；
7. 再次请求可以重新加载并生成；
8. 4B/9B 和 VoiceDesign/Base 配置切换有效。

### 11.3 Android 测试

新增或更新工程级测试覆盖：

- 自定义预加载数量；
- 预加载范围不受三章和二十章硬限制；
- 连续 READY 前沿触发后续三章；
- 同一前沿只触发一次；
- AUTO 和 PINNED 会话的取消与释放；
- runtime Lease acquire/release；
- 服务不支持 Lease 时的降级；
- 播放到前沿时重复触发保护；
- 手动预加载重启恢复；
- 飞行模式下只播放已有本地内容；
- Provider/profile/server scope 改变后不命中旧缓存。

AndroidTest 调生产方法时显式传入全部参数，避免 R8 裁剪 Kotlin 默认参数桥接方法。

## 12. 实施阶段

### 阶段一：Windows 本地服务

- 抽取百炼专属协议适配；
- 建立本地 Agent 和 Model Worker；
- 实现 runtime Lease；
- 接入 Qwen3.5 4B/9B；
- 接入 Qwen3-TTS VoiceDesign/Base；
- 实现本地声库、profile 和音频编码；
- 增加 fake backend 测试和 Windows smoke 入口；
- 编写本地模型包和部署文档。

### 阶段二：Android 预加载能力

- 把自动窗口从固定常量改为显式策略；
- 增加预加载按钮和自定义数量；
- 移除旧的手动数量硬上限；
- 增加连续 READY 前沿；
- 接入 runtime Lease；
- 播放到前沿时滚动追加；
- 补齐 JVM、Room、AndroidTest 和真实设备验证。

### 阶段三：模型 Provider 解耦

- 抽象文本分析、TTS、声库和 Provider profile；
- 增加在线 API Endpoint、API Key、文本模型和 TTS 模型配置；
- 使用 Keystore 保存在线密钥；
- 按 server/provider/profile 隔离缓存；
- 删除产品流程中对具体云厂商和本地实现的硬编码分支。

### 阶段四：交付与验收

- 构建 Windows 本地模型包；
- 验证模型加载/卸载、显存回收和多次唤醒；
- 构建 Android debug 包；
- 真机验证本地服务连接、三章自动续接、自定义预加载和离线播放；
- 更新交接文档、进度记录、问题记录和发布说明。

第一批实现只进入阶段一，不提前修改 Android 预加载产品行为；阶段一完成后再按本设计推进阶段二。
