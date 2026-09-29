# AI 多角色听书一期交付设计

状态：已获用户确认，2026-09-29

## 1. 目标与范围

本设计把现有 NovelAudio 链路推进到一期可使用、可验证、可交付的状态。目标不是
增加另一套播放器或重新设计现有朗读系统，而是在既有正文快照、人物/声音绑定、
章节 plan、artifact 存储、NovelAudioServer 客户端和朗读 Service 之上补齐产品闭环。

一期必须同时满足：

- 用户主动播放当前章节时，当前章节之后最多三个章节可以自动准备；
- 用户可以显式选择章节范围并手动下载；
- 手动下载内容独立于 AUTO 授权，进程重启后仍可恢复；
- 播放前优先使用本地完整 READY 内容；
- 飞行模式下可以连续播放已经完整下载的章节；
- 缺少离线内容时不访问书源、分析服务、声库或 TTS；
- 旧请求、旧 generation、旧 continuation 和迟到回调不能污染新请求；
- 使用已有 NovelAudioServer 百炼桥接，文本分析模型为 `qwen3.7-plus`，
  TTS 模型为 `qwen3-tts-instruct-flash`；
- 云请求受字符数、请求数、并发数和“用完即停”保护；
- 普通系统 TTS、HTTP TTS、后台服务、媒体按键、音频焦点和阅读进度不回退；
- 最终结果经过 Android 设备验证和 debug APK 交付门禁。

一期不包含：

- 自动下载整本小说；
- 自动切换到付费模型或未确认额度的模型；
- 真实用户正文的未经确认云端上传；
- release 签名、正式服务器部署和正式产品发布；
- RAG、知识图谱、逐句情绪、BGM/SFX、声音克隆和模型训练；
- 复杂的独立下载管理页面。

本设计细化并约束 `docs/AI_AUDIOBOOK_ARCHITECTURE.md` 中的缓存、恢复和离线契约，
服务边界以 `NOVEL_AUDIO_SERVER_API_V1.md` 和
`AI_AUDIOBOOK_ANDROID_ARCHITECTURE.md` 为准。

## 2. 方案选择

### 2.1 方案 A：分层协调器

新增边界清晰的协调器：

- `NovelAudioAutoPrefetchCoordinator`
- `NovelAudioPinnedDownloadCoordinator`
- `NovelAudioRecoveryCoordinator`
- `NovelAudioLocalPlaybackResolver`
- `NovelAudioCloudBudgetGate`

现有 `NovelAudioPreparationCoordinator` 继续负责单个章节的快照、分析、plan
生成和下载衔接；现有 `NovelAudioDownloadCoordinator` 继续负责一个已冻结 plan
的分段下载、artifact 提交和 READY 判定。

优点：

- AUTO 授权、PINNED 持久化和本地播放各自有明确生命周期；
- 复用已有数据层和 artifact 门禁，不把三章调度逻辑塞进单章下载器；
- 可以分别编写 JVM、Room 和 Android 设备测试；
- 进程重启不会意外重新签发 AUTO 授权；
- 真实云预算可以在底层统一限制。

代价是新增组件较多，需要通过明确的请求身份、任务状态和事件边界避免重复调度。

### 2.2 方案 B：单一队列协调器

使用一个队列组件同时管理 AUTO、PINNED、恢复和播放。

该方案初期文件较少，但会把播放租约、手动任务、恢复策略和云预算混在同一状态机中。
尤其是“进程重启后只恢复 PINNED、不能恢复 AUTO 授权”这一差异会被隐藏在大量条件分支
中，旧任务和新播放请求也更容易相互取消，因此不采用。

### 2.3 方案 C：全部转为 WorkManager

用 WorkManager 承担 AUTO、PINNED 和恢复。

WorkManager 适合持久后台任务，但 AUTO 的授权必须绑定本次主动播放 Work，
不能因为系统重新调度就自动延长。把主动播放、三章滚动窗口和手动下载全部转成
WorkManager 会增加生命周期、取消语义和测试复杂度，也不利于保证离线时零远程请求。
一期只保留现有 Service/协程链，不引入 WorkManager 作为强制依赖。

## 3. 总体架构

```text
阅读页主动播放
  → AudioPrefetchLifecycle 建立 Work / AUTO 授权窗口
  → NovelAudioLocalPlaybackResolver
      ├─ 本地完整 READY：直接交给 NovelAudio Service
      ├─ 在线但缺失：NovelAudioPreparationCoordinator 准备当前章
      └─ 离线且缺失：本地失败，不启动任何远程链路
  → NovelAudioAutoPrefetchCoordinator
      └─ 当前章之后最多 3 章，逐章串行准备和下载

阅读页“下载章节”
  → NovelAudioPinnedDownloadCoordinator
  → 逐章生成 PINNED plan/task
  → NovelAudioCloudBudgetGate
  → 现有 DownloadCoordinator
  → artifact 校验、manifest 提交、READY

应用/服务启动
  → NovelAudioRecoveryCoordinator
  → Room 与本地文件 reconcile
  → 只恢复 PINNED
  → AUTO 任务失效，不重新签发授权
```

所有路径最终都必须经过同一个 artifact 存储和真实解码门禁。播放和下载可以共享
同一个冻结 plan，但不能共享“谁有权启动任务”的授权判断。

## 4. AUTO 预缓存设计

### 4.1 调度范围

当前章节仍由现有主动播放准备路径处理。AUTO 调度器只负责当前章节之后最多三个
章节，即：

```text
当前章节 + 1
当前章节 + 2
当前章节 + 3
```

如果章节列表不足三个，按实际可用章节结束；不跨越书籍末尾，不遍历全书。

调度器每次启动前获取：

- 当前 `AudioPrefetchLifecycle.Work`；
- 当前 book URL；
- 当前章节索引；
- 当前 `currentWindow()`；
- 当前 continuation ID；
- 当前请求对象身份。

每个目标章节都要重新读取真实章节正文并生成自己的不可变快照，不能用当前章节
文本或旧章节索引代替。

### 4.2 串行与取消

一期采用“章节串行、分段复用现有下载器”的策略。任意时刻最多只有一个章节处于
远程准备/下载状态。这样可以控制免费额度、避免桥接并发超限，也让章节顺序和失败
边界容易验证。

以下事件立即阻止尚未开始的 AUTO 章节，并取消当前可取消的工作：

- 用户暂停或停止朗读；
- 切换书籍；
- 当前播放 Work 失效；
- continuation ID 改变；
- 当前章节不再属于授权窗口；
- 用户主动改用 PINNED 下载；
- 应用进入明确的离线状态。

已经成功提交且完整 READY 的 artifact 不因授权撤销而删除。授权撤销只阻止新的
AUTO 远程工作和未完成内容的提交。

### 4.3 请求身份与提交门禁

AUTO 请求身份至少包含：

- `bookUrl`
- `chapterIndex`
- `generation`
- `continuationId`
- `request identity`
- `retention=AUTO`

任何快照、plan、下载结果和回调提交前都要同时检查当前请求对象身份和 generation。
同书同章的新请求也不能仅靠值相等判断为旧请求；旧请求不得取消新请求，也不得把
旧结果写入新 plan。

## 5. PINNED 手动下载设计

### 5.1 用户入口

一期复用现有阅读页朗读面板，不新增独立页面。面板提供：

- 下载当前章节；
- 下载后续 10 章；
- 下载后续 20 章；
- 自定义章节范围；
- 查看当前书的 PINNED 下载任务。

范围选择只产生用户明确指定的章节集合。它不因当前播放位置变化而自动扩大，
也不触发整本下载。

### 5.2 任务生命周期

`NovelAudioPinnedDownloadCoordinator` 为每个章节独立创建 PINNED plan/task，
并复用现有准备和下载实现。任务支持：

- 排队；
- 开始；
- 暂停；
- 继续；
- 取消；
- 删除；
- 进程重启后恢复。

暂停和取消不会删除已经完整提交的 artifact。删除由用户明确发起，同时删除
对应的 manifest、artifact 和任务记录；删除必须按 plan/generation 精确匹配，
不得误删其他版本或其他书籍的文件。

如果 AUTO 已经生成了同一章节的完整内容，用户将其固定为 PINNED 时只做 retention
升级，不重复分析和合成。若 AUTO 只有部分内容，PINNED 任务继续补齐缺失分段。

### 5.3 与 AUTO 的隔离

AUTO 和 PINNED 可以共用 Room 表、plan 结构和 artifact 目录，但必须隔离：

- retention；
- task identity；
- 授权检查；
- 取消语义；
- 进程恢复策略；
- 清理策略。

用户显式启动 PINNED 下载时，当前正在播放的缺段优先级最高；不影响当前播放的
情况下，PINNED 优先于滚动 AUTO，避免用户的明确操作被后台预缓存长期占用。

## 6. 本地优先播放与离线行为

新增 `NovelAudioLocalPlaybackResolver`，在任何远程准备之前执行本地查询：

1. 按物理章节、正文 hash、plan generation 和 voice binding revision 查找本地 plan；
2. 校验 manifest；
3. 校验所有可朗读分段的文件大小、SHA-256 和真实解码；
4. 只有完整 READY 才将章节交给 NovelAudio 播放 Service。

本地命中时不访问网络，也不刷新服务声库，不重新执行章节分析。

本地未命中时：

- 在线：继续当前章节准备，并由 AUTO 协调器按授权处理后续章节；
- 离线：立即返回明确的“缺少离线音频”状态，不访问书源、分析、声库或 TTS；
- 有缺段的 PINNED 任务：保留已有完整分段，标记 PARTIAL 或 WAITING_NETWORK，
  不伪造 READY。

飞行模式验收要求至少连续播放三章已完整下载内容，并记录网络请求数为零。
离线重启验收要求杀死进程后重新打开应用仍能从本地 manifest 恢复章节和阅读位置。

## 7. 进程恢复与文件数据库 reconcile

新增 `NovelAudioRecoveryCoordinator`，在应用启动或听书 Service 启动时执行一次本地
恢复扫描：

1. 扫描 `QUEUED`、`RUNNING`、`PARTIAL` 和未完成任务；
2. 对每个 plan 检查 generation、manifest 和 artifact；
3. 文件完整但 Room 未提交时，补齐数据库状态；
4. Room 标记完成但文件缺失或损坏时，降为 PARTIAL，只重排缺失分段；
5. PINNED 任务在允许联网且满足用户策略时重新排队；
6. AUTO 遗留任务不恢复远程工作，标记为过期/取消；
7. 离线时只做本地 reconcile，不启动任何远程工作。

任务状态必须能够区分：

- 正在排队；
- 正在运行；
- 部分完成；
- 等待网络；
- 用户暂停；
- 用户取消；
- 远程或校验失败；
- AUTO 授权过期；
- 完整 READY。

旧 worker 的任何迟到提交都必须通过 generation 和 request identity 校验。

## 8. 云调用与额度保护

新增 `NovelAudioCloudBudgetGate`，作为文本分析和 TTS 调用的共同入口：

- 一期最大并发为 1；
- 每次请求前检查总请求数、字符数和单请求上限；
- 请求开始前持久预占预算，避免并发竞态超额；
- 达到任意上限立即熔断；
- 不自动换模型；
- 不把认证错误、额度耗尽或服务不可用无限重试；
- 不把音频下载错误误判成模型鉴权或免费额度错误；
- 记录脱敏的章节、segment、模型、请求次数、字符数、阶段和停止原因；
- 日志不记录正文、响应正文、Token 或签名 URL。

真实云验收使用程序内原创的三角色短章，分阶段记录：

```text
Android 配置
  → bridge health
  → 章节分析
  → 人物/声音绑定
  → 冻结 plan
  → 三角色 TTS
  → 音频下载
  → artifact 提交
  → Android 解码
  → 实际播放与听感验收
```

真实联调继续沿用已确认模型：

- 文本分析：`qwen3.7-plus`
- TTS：`qwen3-tts-instruct-flash`

本次联调只能使用已经确认的额度和停费保护，不扩大到整章批量或用户真实书籍。

## 9. Service 与现有朗读回归

NovelAudio 播放继续使用既有朗读 Service、通知、Media3/播放器、媒体按键、
音频焦点和阅读进度机制，不引入第二套播放器。

服务接线要求：

- 播放前 capture 当前 Work 和 continuation ID；
- 章节切换沿用同一有效 Work，不能重新无条件签发 AUTO；
- 事件携带 book URL、chapter index、generation 和 continuation ID；
- pause、stop、book change、destroy 都撤销 AUTO；
- 本地 READY 播放不启动远程准备；
- AI 听书失败时不能破坏普通系统 TTS 或 HTTP TTS。

必须专门回归：

- 系统 TTS；
- HTTP TTS；
- 媒体播放/暂停/下一章/上一章按键；
- 后台播放；
- 音频焦点丢失与恢复；
- 阅读进度保存和恢复；
- AI 听书关闭后的原有路径。

## 10. 测试设计

### 10.1 JVM 测试

新增或扩展测试覆盖：

- AUTO 窗口最多后续三章；
- 章节串行调度；
- pause/stop/book switch/continuation 变化取消 AUTO；
- 同章新旧 request identity 隔离；
- generation 不匹配拒绝提交；
- PINNED 不依赖 AUTO 授权；
- AUTO 和 PINNED retention 隔离；
- PINNED 任务暂停、继续、取消和删除；
- 预算预占、熔断和并发上限；
- READY manifest 缺段、损坏和真实解码失败；
- 离线状态不调用任何远程依赖；
- 普通 TTS 和 HTTP TTS 回归。

### 10.2 Room 与设备测试

设备测试覆盖：

- PINNED 任务进程重启后恢复；
- AUTO 遗留任务不重新授权；
- 文件已提交但数据库未更新；
- 数据库已 READY 但文件丢失或损坏；
- 只补缺失分段；
- 删除 PINNED 不误删其他 generation；
- 当前播放文件受到清理保护；
- Android Service 跨章节连续播放；
- 后台、媒体按键、音频焦点和进度恢复。

### 10.3 离线和真实云验收

必须分别保留两类证据：

1. 可重复的 Mock/设备证据：飞行模式三章播放、网络请求数为零、进程重启续播；
2. 一次受预算限制的真实云证据：三角色原创短章、三种声音实际听感、完整音频
   校验和解码、调用次数与字符数报告。

单段 TTS 成功不能替代整章、跨章或 Android 端到端验收。

## 11. 实施顺序

按以下顺序实现，每一阶段都先补失败测试再写生产代码：

1. 固化任务状态、请求 identity 和 AUTO/PINNED 共享的调度接口；
2. 实现 local-first resolver，并接入当前章节播放；
3. 实现 AUTO 三章协调器和 Work/continuation 校验；
4. 实现 PINNED 范围入口、手动协调器和任务管理；
5. 实现启动恢复与 Room/artifact reconcile；
6. 接入统一云预算闸门；
7. 接通跨章节 Service 播放和阅读进度；
8. 完成真实桥接 Android 端到端试听；
9. 补齐普通朗读和系统能力回归；
10. 更新进度、架构、Android 计划、updateLog 和验收证据；
11. 执行 JVM、定向设备测试、delivery gates 和最终 debug APK 验证。

任何阶段发现旧请求、文件校验、离线禁网或普通朗读回归问题，都必须先停在该阶段
修复并补测试，不能用后续 APK 构建结果覆盖未解决的问题。

## 12. 交付判定

一期只有在以下条件全部满足时才可称为可交付：

- AUTO 当前章节之后最多三章真实可用；
- PINNED 范围下载、暂停、继续、取消、删除和重启恢复真实可用；
- 本地 READY 优先且飞行模式三章连续播放通过；
- 离线缺失时远程调用数为零；
- 旧请求、旧 generation、旧 Work 和迟到回调隔离通过；
- 真实百炼桥接完成原创三角色 Android 端到端试听；
- 普通 TTS、HTTP TTS、媒体按键、后台、音频焦点和阅读进度回归通过；
- Android 定向测试、JVM 测试和交付门禁通过；
- debug APK 包名为 `io.legado.miss.app.debug`，安装、启动、Cronet、
  SHA-256 和覆盖安装验证通过；
- 交付记录明确列出已验证项、未验证项、真实云调用用量和剩余限制。
