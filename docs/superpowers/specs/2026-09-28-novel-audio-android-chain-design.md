# NovelAudio Android 完整链路设计

## 目标

在现有 Legado Android 阅读器中接通一条可实际使用的 NovelAudio 多角色听书链路：

```text
最终排版前正文
  → 不可变章节快照
  → 本地 TextUnit
  → NovelAudioServer 分析
  → 本地人物/别名/声音绑定
  → 冻结 AudioPlan
  → 持久音频文件与 manifest
  → NovelAudio 专用朗读 Service
  → 现有通知、媒体键、音频焦点和阅读进度
```

普通阅读、系统 TTS、既有 HTTP/JS TTS 和现有媒体播放保持原有行为。新链路只有在用户明确启用并主动开始 AI 朗读时才接管。

## 验收口径

本项目本阶段以 Android 可用为完成标准：

- Mac 负责 Gradle 编译、JVM 测试、Mock/本地服务测试和 Android 模拟器过程验收。
- 使用测试包 `io.legado.miss.app.debug`；不把 Windows 作为开发或本阶段验收前置条件。
- 允许使用 `assembleAppDebug` 作为过程构建和模拟器安装来源。
- 不声称通过正式发布签名、Windows 发布脚本或正式服务器联调。
- Android 上必须能够配置 NovelAudioServer、主动播放当前章节、跨章节继续、生成后续最多 3 章、手动下载指定范围，并在飞行模式下播放已完整下载章节。

现有项目规范中与本口径冲突的部分，补充项目级例外说明；不删除通用发布规范，不把测试包冒充正式发布包。

## 不变的产品约束

- 旁白使用独立 `narrator` 绑定。
- 同名同作者的逻辑作品共享人物和声音；不同作品隔离。
- 无作者作品不按同名自动共享，使用物理书籍回退身份。
- 同一人物跨章节保持声音稳定；声音变化产生新的 binding revision。
- 自动准备仅针对用户主动播放的书，固定准备当前章之后最多 3 个完整章节，不提供数量设置。
- 暂停、停止、换书撤销 AUTO；进程恢复和系统恢复不能重新签发 AUTO 授权。
- 手动下载与 AUTO 独立，手动下载内容标记为 PINNED，不被滚动清理删除。
- `READY` 必须同时满足正文、计划、所有真实音频、文件校验和可解码性。
- 失败、超时、取消和服务不可用必须有界；不能以空音频或静音音频伪装成功。
- 离线播放优先读取本地 manifest；不得因恢复任务而访问书源、分析、声库或 TTS。
- API Token 只进入独立 Keystore 配置，不进入日志、备份、文件名、Room 角色 JSON 或缓存键。

## 方案选择

### 采用：独立 NovelAudio 领域层和播放 Service

新增 `NovelAudioReadAloudService`，继承 `BaseReadAloudService`，复用其：

- 前台 Service 生命周期；
- MediaSession 和通知；
- 耳机/媒体键；
- 音频焦点、来电和耳机拔出；
- 当前章节导航与阅读进度；
- `AudioPrefetchPlayback` 的主动播放授权和固定三章窗口。

新 Service 不复用旧 `HttpReadAloudService` 的文本列表、缓存目录、静音兜底或旧缓存键。它只消费已经冻结的 `FrozenChapterAudioPlan`。

### 不采用：改造旧 HTTP/JS TTS 文本列表

旧路径会重新从排版页生成字符串，无法保存 TextUnit 范围、人物归属、服务器作用域、binding revision 和离线 manifest。它还允许旧的静音兜底，不能满足 NovelAudio 的完整下载不变量。

## 领域对象

### 章节快照

在 `ReadBook` 的 `ContentProcessor.getContent` 和 `ParagraphRuleProcessor.process` 完成后、`ChapterProvider.getTextChapterAsync` 调用前创建：

```kotlin
data class NovelAudioChapterSnapshot(
    val workKey: String,
    val sourceBookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val finalContent: ChapterTextSnapshot,
    val localAnalysisVersion: String
)
```

快照段落使用最终 `BookContent.textList`，以固定 LF 拼接并保留空段。`sourceIndexes` 仅作为段落来源说明，不被当作字符级可逆坐标。

### 人物与声音

沿用现有 `BookCharacter.id` 作为本地稳定人物 ID，新增独立 NovelAudio 数据：

```kotlin
NovelAudioCharacterAlias(
    workKey, characterId, alias, normalizedAlias, source, updatedAt
)

NovelAudioVoiceBinding(
    workKey, subjectType, subjectId, serverScope,
    voiceAssetId, bindingRevision, updatedAt
)
```

`subjectType=narrator` 且 `subjectId=0` 表示旁白，不创建虚拟人物。

服务端返回的 `temporaryId` 只在当前分析响应内有效。应用在同一个 workKey 的串行协调范围内完成以下步骤；人物写入、绑定写入与计划提交各用短 Room 事务，HTTP 选音不得占用数据库事务：

1. 根据稳定角色 ID、显示名和 stable aliases 查找现有人物；
2. 为新人物创建 `BookCharacter`；
3. 写入允许持久化的 stable aliases；
4. 校验或创建当前服务器作用域下的声音绑定；
5. 将本次 assignment 转换为本地人物 ID；
6. 冻结计划。

“他、哥哥、师父”等上下文称谓不自动写入稳定 alias。服务器给出的 stable alias 仍需本地称谓过滤和冲突校验；用户手动建立的称谓关系单独标记来源。

### 冻结计划与音频片段

```kotlin
data class FrozenChapterAudioPlan(
    val planId: String,
    val workKey: String,
    val sourceBookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val snapshotHash: String,
    val localAnalysisVersion: String,
    val serverScope: String,
    val planRevision: Long,
    val segments: List<AudioSegmentIntent>
)

data class AudioSegmentIntent(
    val segmentId: String,
    val orderedRanges: List<ReadAloudRoleRange>,
    val text: String,
    val speakerType: String,
    val speakerId: Long,
    val voiceAssetId: String,
    val bindingRevision: Long,
    val language: String,
    val speed: Double
)

data class AudioSegmentArtifact(
    val segmentId: String,
    val cacheKey: String,
    val ttsProfile: String,
    val contentType: String,
    val relativePath: String,
    val byteSize: Long,
    val sha256: String
)
```

连续且声音参数相同的 TextUnit 可以合并为一个 segment，但合并后的文本必须通过快照范围回拼验证。播放、面板、AUTO 和手动下载只接收同一 `planId`，不得各自重新分析或重新选音。`AudioSegmentArtifact` 只在真实合成成功并完成文件验证后生成；实际 `ttsProfile` 变化只产生新的 artifact，不改变已冻结的角色、声音和范围意图。

## Room 持久化

冻结计划只含合成意图；真实 profile、媒体类型和校验属于响应成功并验证后的 artifact，不预填未知元数据、不回写改变当前计划身份。

数据库版本以 `AppDatabase.kt` 为准；新增表统一递增一次版本并显式迁移，导出对应 schema：

- `novel_audio_character_aliases`
- `novel_audio_voice_bindings`
- `novel_audio_chapter_plans`
- `novel_audio_segments`
- `novel_audio_download_tasks`（独立任务表）
- `novel_audio_character_merges`（合并撤销记录）

章节计划保存：

- 物理章节身份；
- 逻辑作品身份；
- 快照文本 hash 和本地解析版本；
- 计划 revision；
- 服务器作用域；
- 计划状态；
- 保留类型 `AUTO/PINNED`；
- generation、完成进度和错误阶段。

segment 保存：

- 顺序；
- 范围 JSON；
- speaker 与 voiceAssetId；
- binding revision；
- 合成意图的语言与速度；
- 成功产物的 `ttsProfile`、媒体类型；
- cacheKey、文件路径、字节数、SHA-256；
- `QUEUED/PREPARING/GENERATING/VERIFYING/READY/PARTIAL/FAILED` 状态。

文件内容不写入 Room。正文快照和计划元数据必须能在本地重建播放所需文本；音频文件存放在应用私有持久目录，不使用 `cacheDir`。

## Repository 和任务协调

新增 `NovelAudioRepository` 作为领域入口，组合以下可独立测试的职责组件：

- `NovelAudioChapterSnapshotFactory`：创建最终正文快照并冻结有效预处理规则；
- `NovelAudioAnalysisCoordinator`：按协议分批分析、校验覆盖范围和迟到结果；
- `NovelAudioCharacterRegistry`：串行完成正式人物、稳定 alias、旁白和声音绑定；
- `NovelAudioPlanStore`：创建/读取冻结计划与 generation；
- `NovelAudioArtifactStore`：执行真实合成、原子文件提交、校验和 READY 状态更新；
- `NovelAudioDownloadCoordinator`：统一 AUTO/PINNED 下载、暂停、继续、取消和清理。

Repository 不负责 UI，不直接持有 Service，也不绕过客户端读取 Token；远端 HTTP 不放进 Room 事务。

### 分析规则

- wire `analysisVersion` 固定为 `"1"`；
- 本地 `TextUnitParser.ANALYSIS_VERSION` 单独进入缓存身份；
- 单章按协议限制分批，前一批已确认人物和有限 assignment 上下文传给后一批；
- 每批响应只允许覆盖本批 unit，缺失或未知 unit 直接拒绝；
- 当前播放计划创建后冻结；迟到分析只能影响下一次计划；
- 分析超时、取消和协议错误不能无限重试；
- 失败时不得静默生成“成功”的静音或空音频；如产品允许只含旁白的降级计划，必须把降级原因写入计划状态并在 UI 明示，且不把降级计划标为完整 `READY`。

## 文件缓存与离线

每个音频片段使用：

```text
files/novel-audio/scope-<serverScopeHash>/work-<workKeyHash>/chapter-<physicalChapterHash>/<cacheKey>.<ext>
```

目录和文件名只使用带域区分的 UTF-8 SHA-256 派生标识，不包含 Token、正文、URL 明文或未经编码的作品名。`workKey` 仍用于逻辑匹配，但不得直接作为文件系统路径。

写入流程：

1. 服务器返回音频 body；
2. 校验允许的媒体类型和非空内容；
3. 写入同目录 `.part`；
4. 校验字节数、SHA-256 和可解码性；
5. 原子 rename；
6. Room 事务标记 segment `READY`；
7. 所有 segment READY 后，章节 manifest 才标记 `READY`。

恢复流程：

- 应用启动只扫描 manifest 和文件完整性；
- 文件缺失或损坏的 segment 降为 `PARTIAL`；
- 离线状态下只标记缺段，不发起网络；
- 网络恢复且用户授权后才补缺；
- 手动 `PINNED` 内容不参与 AUTO 滚动清理；
- AUTO 清理不能删除当前播放租约和 PINNED 计划。

## 播放与现有 UI

### 路由

在 `SpeechRoute` 中增加 NovelAudio engine type。`ReadAloud.routeToClass` 仅在该 route 明确选择时启动 `NovelAudioReadAloudService`；未配置或服务不可用时保留旧朗读路线。

在 Base 的私有装配入口增加 protected 计划装配、准备态、段落导航 hook，默认实现保持旧服务行为；NovelAudio hook 不调用 `TextChapter.getNeedReadAloud()`，而是：

1. 查找当前章节完整 FrozenPlan；
2. 缺失时调用 Repository 建计划；
3. 交给 Repository 补齐当前播放需要的 segment；
4. 使用 Media3 播放持久文件；
5. 按 segment/cue 发布带 planId、segmentId、chapterPosition 的 typed 进度事件。

旧 `BaseReadAloudService` 的媒体键、通知、音频焦点和阅读进度行为保持不变；新 Service 只覆盖计划加载和 segment 播放实现。

### 设置和人物入口

第一阶段 UI 必须提供：

- NovelAudioServer URL；
- Token 输入、保存、清除；
- 显式允许局域网 HTTP，并展示明文传输提示；
- 连接测试；
- AI 多角色朗读开关；
- 当前书籍的旁白/人物声音列表和试听；
- 手动下载后续 10 章、20 章或自定义范围；
- 下载进度、暂停、继续、取消、删除。

本期人物高级入口同时交付：

- 改名；
- 添加/删除 alias；
- 合并与撤销合并。

这些操作必须通过 Registry/Repository 完成，UI 不直接修改角色表。合并前保存可撤销的关系快照；撤销只恢复本地关系和 binding 指向，不自动重写已经生成的音频文件。

## Mac Android 验收

新增 Mac 过程入口，遵循以下边界：

- 只构建 debug 测试包；
- 安装到 `legado_test` AVD 或唯一连接的 Android 设备；
- 不清理用户数据，除非测试用例明确要求；
- 不读取或打印真实 Token；
- 启动本地 Mock/桥接服务时使用随机测试 Token；
- 固定执行 JVM、协议 Mock、Repository fixture、Room migration 和 AndroidTest；
- 设备验收至少覆盖：
  - 配置保存/清除；
  - 主动播放才能签发 AUTO；
  - 当前章播放和跨章；
  - 三章窗口；
  - 计划和音频缓存；
  - 进程重启恢复；
  - 飞行模式播放完整章节；
  - 缺段不伪报 READY；
  - 普通 TTS/HTTP TTS 回归。

Windows 发布链、正式签名和真实 Windows 服务成功联调单独记录为发布阶段事项，不阻断本阶段 Android debug 可用验收。

## 测试策略

### JVM

- 快照工厂使用最终 `BookContent` 的段落保留空段和 LF；
- workKey 无作者回退与同名作者共享/跨作者隔离；
- temporaryId 转正式人物 ID；
- stable alias 去重和上下文称谓不持久化；
- narrator 独立绑定；
- serverScope、bindingRevision 和实际 ttsProfile 参与 cacheKey；
- plan 冻结后迟到分析不能覆盖；
- segment 状态流转和 generation；
- `.part`、损坏文件、缺段和离线禁网；
- AUTO 与 PINNED 清理边界；
- 旧 Service 路由不受影响。

### Room/AndroidTest

- 当前数据库版本到下一版本的迁移保留旧书、旧人物、旧朗读缓存；
- Keystore 配置保存、清除和密钥丢失；
- debug 包覆盖安装；
- 模拟器上完整配置、播放、重启、飞行模式流程。

### Mock/本地服务

- 六端点契约继续使用已有 Mock；
- 新增固定测试小说和确定性压缩音频 fixture；
- Mock 只用于验证协议、状态和解码，不替代真实角色音质验收；
- 云调用不因本链路开发自动增加。

## 规范补充

新增项目规则文档 `docs/AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md`，作为 AI 多角色听书的项目级补充：

- Mac debug 构建和 Android 设备/模拟器验收是本阶段交付口径；
- Windows 发布脚本不作为本阶段阻断；
- 仍保留测试配对、日志安全、Token 隔离、Room migration、离线禁网和真实音频完整性要求；
- 任何跳过都必须在进度文档记录实际命令、退出码和影响范围；
- debug 可用不等于 release 发布完成。
