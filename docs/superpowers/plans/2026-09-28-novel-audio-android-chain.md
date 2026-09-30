# NovelAudio Android Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Legado 中完成可配置、可播放、可恢复、可离线的 NovelAudio 多角色听书 Android debug 链路，并保持旧朗读路线不回归。

**Architecture:** NovelAudio 使用独立领域层和独立 Service，正文来自 `ReadBook` 的最终 `BookContent`，人物/别名/声音绑定和冻结计划进入 Room，音频文件采用原子提交和 manifest 完整性校验。计划冻结合成意图，真实 `ttsProfile`、媒体类型和校验只属于合成产物。

**Tech Stack:** Kotlin、Room、Gson、OkHttp、Media3、JUnit、AndroidTest、现有协程和 `AudioPrefetchLifecycle`。

---

### Task 1: 固化交付规则与缓存身份

**Files:**
- Modify: `.gitignore`
- Modify: `docs/superpowers/specs/2026-09-28-novel-audio-android-chain-design.md`
- Create: `docs/AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md`
- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPathCodec.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioPathCodecTest.kt`

- [ ] 先写路径和计划身份测试：同一输入稳定、不同 server scope/work/chapter 隔离、结果不含 URL/正文明文或路径分隔符。
- [ ] 运行定向测试，确认新 codec 尚不存在且测试按预期失败。
- [ ] 实现基于 UTF-8 SHA-256 的目录和文件名编码，只输出固定小写十六进制。
- [ ] 运行定向测试和 `git diff --check`。
- [ ] 将文档、计划和新增文件精确放行到 Git。

### Task 2: 建立领域模型与 Room 迁移

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioModels.kt`
- Create: `app/src/main/java/io/legado/app/data/entities/NovelAudioEntities.kt`
- Create: `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`
- Modify: `app/src/main/java/io/legado/app/data/AppDatabase.kt`
- Modify: `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioModelTest.kt`
- Test: `app/src/androidTest/java/io/legado/app/NovelAudioMigrationTest.kt`

- [ ] 先写模型状态流转、旁白 subject、PINNED/AUTO 清理边界和生成号拒绝旧提交的失败测试。
- [ ] 用独立表保存 aliases、voice bindings、plans、segments、download tasks；所有实体字段提供默认值并使用现有 Room 风格。
- [ ] 将 `AppDatabase` 版本从代码当前值递增一个版本，加入实体、DAO 和显式 migration；不在文档或测试中硬编码旧快照版本。
- [ ] migration 测试验证旧表数据保留、新表可读写和重复执行不会破坏数据。
- [ ] 运行 Room schema 生成、定向 JVM 测试和 AndroidTest 编译。

### Task 3: 接入最终正文与规则冻结

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotFactory.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadBook.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotFactoryTest.kt`

- [ ] 先写最终 `BookContent.textList` 保留空段/LF、物理章节身份和规则 hash 的失败测试。
- [ ] 在 `ReadBook` 两个 `ParagraphRuleProcessor.process` 完成点调用快照工厂；不从 `getNeedReadAloud()` 或渲染页文本取源。
- [ ] 取消或 generation 变化时拒绝迟到快照提交。
- [ ] 运行正文定向测试和现有 `ReadBook` 回归测试。

### Task 4: 分批分析与人物/声音注册

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioAnalysisCoordinator.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioCharacterRegistry.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioModels.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioAnalysisCoordinatorTest.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioCharacterRegistryTest.kt`

- [ ] 先写 `<=64 units`、`<=4000 UTF-16`、规范化 JSON `<=32 KiB`、previous assignments `<=32` 的失败测试。
- [ ] 校验每批 assignment 只覆盖本批 unit，拒绝未知/缺失/重复 unit。
- [ ] 同一 workKey 串行创建正式人物 ID；无作者作品使用物理书籍身份；稳定 alias 去重，拒绝称谓自动落库。
- [ ] narrator 使用独立 binding；voice match 的 HTTP 调用不放进 Room 事务。
- [ ] 测试迟到分析不能覆盖已冻结计划，并验证同作者共享、跨作者隔离。

### Task 5: 计划冻结与真实音频产物

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPlanRepository.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioArtifactStore.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioArtifactStoreTest.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPlanRepositoryTest.kt`

- [ ] 先写合成返回后才产生 profile/content type/cache key 的失败测试。
- [ ] 实现 `.part` 写入、长度/sha256/媒体类型校验、可解码校验、原子 rename 和 READY 事务边界。
- [ ] 缺任一 segment、正文或计划时保持 `PARTIAL/FAILED`，不得 READY。
- [ ] generation 不匹配或取消时删除临时文件，不提交旧 worker 结果。
- [ ] 运行文件故障、重复提交、损坏恢复和离线禁网测试。

### Task 6: AUTO/PINNED 下载协调

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/AudioPrefetchLifecycle.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinatorTest.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/offline/AudioPrefetchWiringTest.kt`

- [ ] 先写主动播放才签发、窗口最多当前章后 3 章、暂停/停止/换书撤销、系统恢复不签发的失败测试。
- [ ] 手动范围下载创建 PINNED 任务，与 AUTO 任务独立；暂停、继续、取消和删除保持幂等。
- [ ] 清理仅回收过期 AUTO，不删除当前租约和 PINNED。
- [ ] 运行现有固定三章测试并补充新协调器测试。

### Task 7: 新 Service 路由和播放

**Files:**
- Create: `app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/speech/SpeechModels.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadAloud.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/io/legado/app/help/readaloud/speech/SpeechRouteTest.kt`
- Test: `app/src/test/java/io/legado/app/service/NovelAudioReadAloudServiceTest.kt`

- [ ] 先写 NovelAudio route 显式选择、未配置回退旧 route、旧 HTTP/system route 不变的失败测试。
- [ ] 在 Base service 增加 protected hook，默认实现保持现有 `contentList` 行为；新 Service 只消费完整 FrozenPlan。
- [ ] 新 Service 加载本地 artifact，按 segment 发布 typed progress，复用通知、MediaSession、音频焦点、媒体键和现有 AUTO 授权。
- [ ] 缺失音频时进入有界准备状态，不以静音继续。
- [ ] 运行旧 Service 回归和新 Service 定向测试。

### Task 8: 设置、人物和下载 UI

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`
- Create/Modify: existing character management screen files under `app/src/main/java/io/legado/app/ui/book/character/`
- Test: corresponding JVM/AndroidTest files

- [ ] 完成 K1 记录：复用既有设置组件和主题 token，列出四态检查项。
- [ ] 接入 URL/Token/HTTP 显式确认、连接测试和 NovelAudio 开关。
- [ ] 接入旁白/人物声音、试听、10/20/自定义范围下载及任务控制。
- [ ] 接入改名、alias 增删、合并和最近一次撤销，UI 只调用 Registry/Repository。
- [ ] 运行 UI 逻辑测试和四态手工检查，记录未执行的旧主题 runner 证据。

### Task 9: Android 验收、更新日志和交付审计

**Files:**
- Modify: `app/src/main/assets/updateLog.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Modify: `docs/AI_AUDIOBOOK_ANDROID_PLAN.md`
- Create/Modify: `scripts/novel-audio-mock/` fixtures and device runner

- [ ] 运行定向测试、完整 `:app:testAppDebugUnitTest`、Mock/bridge tests。
- [ ] 启动 `legado_test` AVD，安装 `io.legado.miss.app.debug`，验证配置、播放、跨章、三章窗口、重启、飞行模式和普通朗读回归。
- [ ] 记录缺失 Windows runner、正式签名和正式服务联调为未执行边界，不改写成通过。
- [ ] 根据用户可感知变化更新一条当天 updateLog，逐文件审计。
- [ ] 运行可用的代码/日志/Room/Gson 审计，输出真实退出码。
