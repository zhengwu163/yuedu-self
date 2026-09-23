# AI 多角色听书 Implementation Plan

> **For agentic workers:** 按任务逐项执行并验证；执行阶段使用 executing-plans 或
> subagent-driven-development。勾选项必须有实际代码与测试证据，不能仅依据设计勾选。

**Goal:** 在现有 Android 阅读器上实现稳定多角色连续朗读，以及自动后续 2–3 章与主动范围下载的可靠离线播放。

**Architecture:** 复用现有阅读/书源、朗读 Service、SpeechRoute、TTS Provider 和 Media3。
统一文本快照与播放计划，补充人物别名、独立声音绑定、章节 manifest 和可恢复队列。
UI、实时播放、预生成共用同一段计划。

**Tech Stack:** Kotlin、Android、Room、现有协程封装、Media3、Android Keystore、JUnit、Android instrumentation。

## 执行规则

- 上游：`github` 只读；交付：`origin/main`。保留许可证及完整历史，不 force push。
- 每次修改前复核目标文件，测试先行：写失败用例 → 运行确认 → 最小实现 → 原用例和回归通过。
- 每批仅暂存明确路径；真实用户可见变化编译前写 updateLog，内部文档/基础类型不编造产品更新。
- 本文是分阶段实施索引；每个阶段从实际代码展开小批次，不一次性跨越数据库、播放和 UI。
- 基线未构建运行成功前，不宣称 Phase 0 完成；缺失门禁不能当作通过。

## Phase 0：工程基线与审计

文件：`CURRENT_ARCHITECTURE.md`、本文件、
`docs/AI_AUDIOBOOK_ARCHITECTURE.md`、`docs/AI_AUDIOBOOK_PROGRESS.md`、`.gitignore`。

- [x] 导入主仓完整历史并首次推送 iCode main，配置 GitHub 只读推送地址。
- [x] 审计阅读、AI、角色、TTS、缓存、Room 调用链及参考项目许可。
- [x] 明确逻辑作品共享人物及一期离线范围。
- [ ] 验证 SDK 平台/Build Tools、动态 Cronet 输入与依赖可用。
- [ ] 完成未修改业务源码的单测及过程构建，记录完整失败或成功证据。
- [ ] 补齐上游缺失门禁来源，验证真实 runner、代码/测试配对及 R8 审计，不能创建恒 PASS 替代。
- [ ] 恢复门禁时先将 `/ai_tests/` 改为可遍历的父目录规则，再精确放行所需脚本、
  注册表及它们的测试；用 `git check-ignore` 和 `git ls-files` 确认纳入跟踪，继续忽略日志/虚拟环境。
- [ ] 在测试包上实际启动、导入本地测试小说、普通朗读、切章，记录设备与包身份。
- [ ] 文档变更检查后提交并同步 `origin/main`。

macOS 过程验证（路径按实际环境设置；不是交付打包）：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
# 用户本人确认 SDK 许可之后安装；Build Tools 版本来自实际 Gradle 任务图错误
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0" "platform-tools"
sh ./gradlew --version
sh ./gradlew :app:downloadCronet
sh ./gradlew :app:testAppDebugUnitTest :app:assembleAppDebug --console=plain
adb devices -l
```

预期：Gradle task 全部成功、测试报告无失败、设备实际运行测试包。
当前上游 wrapper 文件模式为 `100644`，macOS 通过 `sh` 调用，避免依赖执行位。
`--version` 成功不能替代编译结果。交付 APK 仍遵守 `build-legado.bat` 入口；
跨平台交付适配必须保留签名、Cronet、产物保护与测试门禁后单独验收。

## Phase 1：一章端到端

修改：

- `app/src/main/java/io/legado/app/help/readaloud/role/ReadAloudRolePreprocessor.kt`
- `app/src/main/java/io/legado/app/help/ai/AiReadAloudRoleService.kt`
- `app/src/main/java/io/legado/app/help/readaloud/ReadAloudSpeechPlanner.kt`
- `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt`
- `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- `app/src/main/java/io/legado/app/service/TTSReadAloudService.kt`
- `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`

新增落点：

- `app/src/main/java/io/legado/app/help/readaloud/ChapterTextSnapshot.kt`：不可变文本、段映射、绝对范围与版本。
- `app/src/main/java/io/legado/app/help/readaloud/ChapterDirector.kt`：注释校验、分块上下文、有界降级。
- `app/src/test/java/io/legado/app/help/readaloud/ChapterTextSnapshotTest.kt`
- `app/src/test/java/io/legado/app/help/readaloud/ChapterDirectorTest.kt`
- `app/src/test/java/io/legado/app/help/readaloud/ReadAloudSpeechPlannerTest.kt`

- [ ] 先测试空行、尾空格、CRLF、跨段引号、emoji 与重复文本的 offset 回拼及稳定 ID。
- [ ] 实现快照适配层；切分、面板、缓存、播放统一使用同一快照。
- [ ] 先测试未知 unit、跨作品 characterId、重复冲突注释、缺项、恶意正文指令的拒绝。
- [ ] 实现 Director；章节级请求，长章串行传递人物决议和尾部上下文，所有输入/输出有硬上限。
- [ ] 先测试同 speaker 合并、不同声音不合并、超长段分割和播放位置回映。
- [ ] 将计划真正接到 Service 合成队列；关闭 AI 时保持普通朗读链路。
- [ ] 用固定虚构小说及本地假 Provider 验证旁白＋两人物，不调用付费服务。
- [ ] 验证首段失败、AI 超时、取消和切章；当前播放不能被迟到结果改写。

测试核心断言：

```kotlin
assertEquals(snapshot.text.substring(unit.start, unit.end), unit.text)
assertEquals(firstRun.map { it.id }, secondRun.map { it.id })
assertTrue(plan.segments.all { it.end > it.start })
assertEquals(expectedSpeakableRanges, plan.coveredRanges)
```

这些断言定义验收合同；实现中的具体测试 fixture 随该批次提交，不以本文代码块代替测试文件。
定向命令：`sh ./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.help.readaloud.*'`。
本阶段完成标准：设备上的实际发声与统一计划相符，普通朗读回归通过。

## Phase 2：Character Registry

复用 `data/entities/BookCharacter.kt`、`data/dao/BookCharacterDao.kt`、
`help/book/BookIdentity.kt`、现有身份迁移器。
新增 `help/readaloud/CharacterRegistry.kt`、`data/entities/CharacterAlias.kt`、
`data/dao/CharacterAliasDao.kt` 和合并记录；数据库改动集中到
`data/AppDatabase.kt`、`data/DatabaseMigrations.kt` 及导出 schema。

- [ ] 测试同名同作者跨源共享、不同作者隔离、作者缺失保守回退、外部 ID 拒绝。
- [ ] 测试“师父/哥哥/老师/他”不自动落 stable alias，稳定姓名 alias 去重。
- [ ] 实现本地 ID 分配、事务化 alias 更新和同作品串行决议。
- [ ] 测试改名不变 ID、合并后 alias 归属和撤销不覆盖后续人工修改。
- [ ] 增加 `CharacterRegistryTest.kt` 与 `CharacterRegistryMigrationTest.kt`，
  验证旧人物、路由 JSON 与阅读数据在升级后保留。

完成标准：重复分析和跨章不会反复建同一人物；没有历史数据破坏性迁移。

## Phase 3：自动选音与凭据

复用 `help/readaloud/speech/SpeechModels.kt`、现有声音目录和角色页面。
新增 `data/entities/VoiceBinding.kt`、`data/dao/VoiceBindingDao.kt`、
`help/readaloud/VoiceBindingResolver.kt`、`help/ai/AiCredentialStore.kt`；
修改 `help/config/AppConfig.kt`、`ui/main/ai/AiConfigModels.kt` 及请求配置读取点。

- [ ] 测试旁白独立、首次锁定、LLM 模型切换不换声、TTS 兼容 model 沿用 voice。
- [ ] 实现确定性选音与能力检查；不兼容时记录明确 fallback，不随机覆盖原绑定。
- [ ] 测试空 voiceId 恢复默认声、取消试听不影响正在播放的 voice。
- [ ] 测试 API Key 加密迁移、回读失败保留旧值、Keystore 丢失要求重新输入。
- [ ] 实现密钥引用、安全迁移、备份/导出排除和正文外传告知。
- [ ] 新增 `VoiceBindingResolverTest.kt`、设备侧 `AiCredentialStoreTest.kt`，
  所有 Gson 模型遵守 @Keep，完成双包泛型签名审计。

完成标准：自动声线稳定且可手动修正；普通 preference/导出/日志不含新保存的明文 Key。

## Phase 4：后台管线与离线

复用 `help/readaloud/prebuild/TtsPrebuildManager.kt`、`TtsCacheKeys.kt`、
既有 HttpTTS/JS/系统合成调用。新增：

- `help/readaloud/offline/OfflineChapterManifest.kt`：完整性与有序片段元数据。
- `help/readaloud/offline/AudioCacheStore.kt`：原子文件提交、校验、租约与清理。
- `help/readaloud/offline/AudioDownloadCoordinator.kt`：优先级、去重、窗口、恢复。
- `data/entities/AudioDownloadTask.kt`、`data/dao/AudioDownloadTaskDao.kt`：持久状态。
- 对应 `app/src/test/java/io/legado/app/help/readaloud/offline/` 下单测；
  `app/src/androidTest/java/io/legado/app/AudioOfflinePlaybackTest.kt` 做设备连播。

- [ ] 缓存键变更测试：每一项实际合成参数变化必须改变键，凭据轮换不改变键。
- [ ] 完整性测试：空文件、截断、校验错误、静音占位、缺段和无正文均不得 READY。
- [ ] 实现同目录临时文件→校验→原子 rename→DB 完成状态；故障注入覆盖每个边界。
- [ ] 恢复测试：进程退出后只补缺失段，暂停/取消不自动恢复，旧 generation 无权提交。
- [ ] 调度测试：当前播放优先、Provider 限流、合成全局最多 2、同段多个消费者只合成一次。
- [ ] 实现默认后续 3 章、可切 2 章、滚动补充，以及 10/20/自定义范围下载。
- [ ] 保留测试：AUTO 升级 PINNED 不重生；滚动/容量清理不能删除 PINNED 或当前播放租约。
- [ ] 实现仅 Wi-Fi、空间不足暂停、暂停/继续/取消及真实进度。
- [ ] 已缓存路径在任何网络请求前短路；离线不拉书源、不调 LLM/TTS/voice catalog。
- [ ] 离线启动恢复仅做本地校验和位置恢复，缺段转 WAITING_NETWORK；补段和 fallback 亦不得联网。
- [ ] 飞行模式播完至少 3 章，再杀进程重开，从持久位置继续；删除一段后显示 PARTIAL。

核心完整性断言：

```kotlin
assertFalse(incompleteChapter.isOfflineReady)
assertFalse(placeholderChapter.isOfflineReady)
assertEquals(setOf(missingSegmentKey), resumedRequests)
assertTrue(pinnedKeys.intersect(evictedKeys).isEmpty())
assertEquals(0, offlinePlayback.networkRequestCount)
```

完成标准：上述设备离线测试通过，并在正文/音频局部失败时保持真实状态。

## Phase 5：精简 UI

主要入口：`ui/book/read/ReadAloudPlayerPanel.kt`、现有人物页与模型配置页；
复用现有主题和组件，不新建阅读器。UI 改动前加载主题归属规范并完成四态检查。

- [ ] 播放面板增加 AI 多角色开关、准备/缓存进度和离线可播状态。
- [ ] 下载入口提供 10/20/自定义范围、仅 Wi-Fi、暂停继续取消、剩余空间。
- [ ] 人物页支持试听、换声、改名、aliases、合并；高级区放撤销、重分析和重置。
- [ ] 清理界面分别呈现自动缓存、保留下载；删除操作展示范围和占用，保护当前播放。
- [ ] 用户操作测试覆盖失败、空状态、重开恢复、无障碍标签与默认/自定义/主题包/夜间。

完成标准：普通收听流程不要求审核每句或每章，长操作有进度且可取消。

## Phase 6：回归、优化与交付

- [ ] 全量 `testAppDebugUnitTest`、Room 升级、R8/Gson 双包、主题及代码测试配对门禁通过。
- [ ] 设备覆盖在线跨章、飞行模式跨章、断网/恢复、强停/重开、换源、改音色、低空间。
- [ ] 记录首播耗时、每章分析请求数、合成并发峰值、缓存命中率、存储量和失败推进时间。
- [ ] 普通朗读、书架、阅读位置、通知媒体键、蓝牙/音频焦点回归。
- [ ] 有限真实 Provider 验证仅使用用户已授权且安全配置的凭据，记录是否可能计费。
- [ ] 按交付入口产包，明确签名与安装包身份；审计、计划、进度按事实同步。
- [ ] 分批 Conventional Commits，推送后核验 `HEAD == origin/main`；不自动发布公开版本。

## 当前阻断处理

缺失门禁、SDK 许可/下载、签名和设备分别记录，不能混为业务实现失败。
本地静态检查与假 Provider 测试可独立推进；不能以它们代替工程全量单测、
正式交付门禁或设备验证。恢复缺失门禁需要原始脚本来源，跨平台适配要保留原校验语义。
