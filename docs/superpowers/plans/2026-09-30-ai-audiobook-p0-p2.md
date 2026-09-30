# AI Audiobook P0-P2 Implementation Plan

> **For agentic workers:** 按任务逐项执行并验证；每个代码任务必须先写失败测试，再做最小实现。

**Goal:** 完成 AI 多角色听书一期中 P0、P1、P2 的可交付闭环：旧请求不可污染新执行，预算和离线边界可证明，AUTO/PINNED 能从阅读页进入并连续播放，最后完成 Android 回归与 debug 包证据。

**Architecture:** 沿用现有 NovelAudioPreparationCoordinator、NovelAudioRepository、NovelAudioDao、NovelAudioDownloadCoordinator、Room 和既有朗读 Service/Media3，不引入第二套播放器或 DI。内存 run token 负责及时取消，Room generation/executionAttempt 负责持久化 fencing；本地 READY manifest 在任何网络/分析/TTS 之前短路。

**Tech Stack:** Kotlin、Android、Room、现有 Coroutine 封装、Media3、JUnit、Android instrumentation、Gradle。

---

### Task 1: 核对基线并固化当前回归证据

**Files:**
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Read: `/Users/zzz/.comate-engine/store/terminals/2iscmk.output`（若仍可读取）
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioMigrationTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt`

- [ ] 读取 `2iscmk` 的完整终端输出和 XML，记录 Starting/Finished、tests、failures、errors、skipped、BUILD SUCCESSFUL 与 exit code。
- [ ] 若证据与进度文档不一致，只修正文档事实，不修改测试语义。
- [ ] 运行 `git diff --check`，确保文档更新无空白错误。

### Task 2: 为 PreparationCoordinator 增加持久化生命周期 fencing 的 RED 测试

**Files:**
- Modify: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinatorTest.kt`
- Test/fixture: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinatorTest.kt`
- Read: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
- Read: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`

- [ ] 增加同一章节重复 start 时旧 run token 失效的测试。
- [ ] 增加 request replacement 发生在 `savePlanForExecution` 前后的测试。
- [ ] 增加 cancel 后已持久化 execution 必须释放 plan/task 的测试。
- [ ] 增加异步 `onCancel` 迟到时不得释放新 execution 的测试。
- [ ] 运行定向测试，确认新增用例在生产实现下按预期失败。

### Task 3: 实现 run token、active execution ownership 和串行 mutation lane

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`
- Modify: `app/src/main/assets/updateLog.md`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinatorTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt`

- [ ] 为每次 request 创建不可复用的 run token；cancel 和 replacement 在同步区立即废弃旧 token。
- [ ] 为当前 run 保存 active `Execution`，producer 持久化后必须再次确认 token/请求仍归属当前 run。
- [ ] 将 save/release 操作放入同一条串行 IO mutation lane，避免 save 与 cancel 交错。
- [ ] cancel/replacement 在 token 仍归属当前 run 时释放对应 execution；旧 run 不得释放新 run。
- [ ] 保持 AUTO 授权检查与 PINNED 独立，不能让 cancellation fencing 破坏 PINNED。
- [ ] 定向 JVM → 全量 JVM → Android recovery 回归。

### Task 4: 增加持久预算 ledger 和硬停止策略

**Files:**
- Inspect/Modify: `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioServerConfigStore.kt`
- Inspect/Modify: `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioServerClient.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
- Create/Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioBudgetLedger.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioBudgetLedgerTest.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/server/NovelAudioServerClientTest.kt`
- Modify: `app/src/main/assets/updateLog.md`

- [ ] 先写并运行预算并发、重启恢复、重复扣费、达到任一上限立即拒绝的 RED 测试。
- [ ] 持久化请求数、字符数、并发占用、累计额度和熔断状态；状态损坏必须拒绝而不能静默归零。
- [ ] 请求发送前原子预占；成功、失败、取消和重试只能按契约结算一次。
- [ ] 达到预算后停止后续分析/TTS，不自动换模型、不自动切收费模型。
- [ ] 增加模型与预算边界日志，但不得写正文、Token 或响应正文。
- [ ] 运行预算定向测试、桥接离线测试和全量 JVM。

### Task 5: 完成本地优先和离线零网络守卫

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Create/Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPlaybackResolver.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioPlaybackResolverTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt`
- Modify: `app/src/main/assets/updateLog.md`

- [ ] 先写本地 READY 命中、损坏 READY 降级、缺段离线拒绝且网络调用计数为零的 RED 测试。
- [ ] resolver 先读取本地 plan/artifact，只有在线且获得授权后才允许准备远端资源。
- [ ] 离线启动只做本地校验、位置恢复和缺段标记；缺段进入 WAITING_NETWORK。
- [ ] 禁止离线路径访问书源、分析、声库、TTS 和自动 fallback。
- [ ] 验证 READY 音频不能由空文件、静音占位或不完整 artifact 冒充。
- [ ] 运行 JVM 和 Android 设备离线测试。

### Task 6: 接通 AUTO 三章和 PINNED 范围下载

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinatorTest.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioRetentionTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt`
- Modify: `app/src/main/assets/updateLog.md`

- [ ] 先写当前章、后续三章、PINNED 10/20/自定义范围和 PINNED 不依赖 AUTO 的 RED 测试。
- [ ] 当前章节主动播放时只签发当前书的 AUTO 固定三章窗口。
- [ ] PINNED 范围作为独立用户操作创建任务，不依赖 AUTO lease。
- [ ] AUTO READY 转 PINNED 只提升 retention，不重新合成。
- [ ] 暂停、停止、换书撤销 AUTO；PINNED 任务不被滚动清理。
- [ ] 运行入口 JVM、真实 Fragment/Activity AndroidTest 和恢复回归。

### Task 7: 接通 local-first 播放、跨章连续播放和恢复

**Files:**
- Modify: `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/receiver/MediaButtonReceiver.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/ReadAloudPlaybackState.kt`
- Test: `app/src/test/java/io/legado/app/service/NovelAudioPlaybackStateTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioPlaybackDeviceTest.kt`
- Modify: `app/src/main/assets/updateLog.md`

- [ ] 先写本地 READY 播放、跨章节切换、后台恢复、媒体按键和音频焦点的 RED 测试。
- [ ] 播放 resolver 命中完整本地音频时直接交给既有 Service/Media3，不重复分析或合成。
- [ ] 当前章结束后按同一 playback session 选择下一章 READY 音频。
- [ ] 持久化章节位置和 session fencing，杀进程重开后优先恢复本地音频。
- [ ] 普通系统 TTS 和 HTTP TTS 在 AI 关闭时保持原路径。
- [ ] 运行设备在线跨章、飞行模式至少三章、杀进程重开和普通朗读回归。

### Task 8: 完成工程回归、门禁与 debug APK

**Files:**
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Modify: `docs/AI_AUDIOBOOK_ARCHITECTURE.md`
- Modify: `app/src/main/assets/updateLog.md`
- Inspect: `build-legado.bat`
- Inspect: `docs/project-flow/build-apk-guide.md`
- Inspect: `app/build/outputs/apk/`
- Test: all applicable JVM and Android tests

- [ ] 运行 `:app:testAppDebugUnitTest`，记录测试数量、失败、跳过和 exit code。
- [ ] 运行适用 Room/Keystore/Recovery/Playback AndroidTest，核对 XML 与 Gradle exit code。
- [ ] 运行现有 commit gates；缺失门禁只能记录为阻断，不能伪报通过。
- [ ] 运行双包 Gson/主题/测试配对审计（脚本存在时），记录 selection colors 的独立状态。
- [ ] 做最终只读 code review，检查旧执行污染、网络越界、预算越界、普通朗读回归。
- [ ] 按项目交付规则生成 App debug APK；若 macOS 无法运行 Windows `build-legado.bat`，明确记录“过程构建通过、正式交付链阻断”，不得把手动 Gradle 包称为交付包。
- [ ] 核对包名 `io.legado.miss.app.debug`、版本、SHA-256、安装和设备运行记录。
- [ ] 更新进度文档和 updateLog，最后再决定是否 commit/push；不自动发布正式版本。

---

## 验收顺序

1. P0 生命周期 fencing 和预算 ledger 必须先绿。
2. local-first/离线零网络必须在进入真实云试听前绿。
3. AUTO/PINNED 调度与播放恢复必须通过 Android 设备回归。
4. 普通 TTS/HTTP TTS、后台、媒体键、音频焦点和通知必须无回归。
5. 所有验证证据齐全后才允许声称 P2 完成；P3 的正式 Windows 发布、正式签名和正式服务部署不纳入本次完成条件。
