# NovelAudio Phase One Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Android NovelAudio 链路上完成可使用、可离线、可恢复、可真实联调的一期多角色听书，并交付经过设备验证的 `io.legado.miss.app.debug`。

**Architecture:** 保留现有 `NovelAudioPreparationCoordinator` 的单章准备职责、`NovelAudioDownloadCoordinator` 的 artifact 门禁和 `NovelAudioReadAloudService` 的本地播放器；新增 local-first resolver、AUTO 三章协调器、PINNED 手动下载协调器、恢复协调器和统一云调用预算闸门。AUTO 授权只存在于当前 Service Work，PINNED 任务持久化并可重启恢复，所有最终播放都从本地完整 READY manifest 读取。

**Tech Stack:** Kotlin、Room、Gson、OkHttp、Media3/ExoPlayer、现有 `Coroutine.async` 链、Android Keystore、JUnit、Android instrumentation、NovelAudioServer v1 百炼桥接、`adb reverse`。

---

## 0. 实施前文件地图与不变量

### 数据与状态

- `app/src/main/java/io/legado/app/data/entities/NovelAudioEntities.kt`
  - 保存 plan、artifact、download task、retention 和状态原因。
- `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`
  - 提供 generation CAS、PINNED 查询、恢复扫描、删除和 READY reconcile。
- `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
  - 负责 Room 事务、文件校验、状态转换和精确删除。
- `app/src/main/java/io/legado/app/data/AppDatabase.kt`
- `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt`
  - 当前数据库版本为 111；本期新增字段必须显式迁移到 112。

### 领域执行链

- `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
  - 保留用户主动当前章入口；抽出共享的 plan/download 执行环境。
- `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt`
  - 继续只处理一个冻结 plan；增加预算闸门、状态原因和 PINNED 恢复所需的可取消语义。
- `app/src/main/java/io/legado/app/help/readaloud/offline/AudioPrefetchLifecycle.kt`
  - 继续拥有 AUTO token、Work、continuation 和最多三章窗口。
- `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioServerClient.kt`
  - 不改变 v1 协议和凭据安全边界；所有远程调用由新的预算闸门包裹。

### 播放和入口

- `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioLocalPlaybackResolver.kt`
  - 新增，播放前只查本地完整 READY。
- `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioAutoPrefetchCoordinator.kt`
  - 新增，只处理当前章之后最多三章。
- `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinator.kt`
  - 新增，处理用户明确选择的范围。
- `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioRecoveryCoordinator.kt`
  - 新增，启动扫描和 Room/文件 reconcile。
- `app/src/main/java/io/legado/app/model/ReadAloud.kt`
  - 接入 local-first、AUTO 调度和手动下载调用。
- `app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt`
  - 保留本地 Media3 播放，接入跨章节 Work 和明确离线失败。
- `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt`
  - 只增加必要的受保护 hook，不改变系统 TTS/HTTP TTS 默认路径。
- `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`
- `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
  - 复用现有朗读面板，增加下载当前章、10/20 章、自定义范围和任务状态入口。

### 测试与证据

- `app/src/test/java/io/legado/app/help/readaloud/`
  - 状态、调度、预算、离线和请求身份的 JVM 测试。
- `app/src/androidTest/java/io/legado/app/`
  - migration、设备恢复、飞行模式、跨章节和用户入口测试。
- `docs/AI_AUDIOBOOK_PROGRESS.md`
- `docs/AI_AUDIOBOOK_ANDROID_PLAN.md`
- `docs/AI_AUDIOBOOK_ARCHITECTURE.md`
- `docs/AI_AUDIOBOOK_CLOUD_TRIAL.md`
- `app/src/main/assets/updateLog.md`
  - 只记录用户可感知变化，不写内部工程事务。

实施过程中只对当前任务相关路径 `git add`，禁止使用 `git add -A`，保留工作区中已有的其他改动。

---

### Task 1: 完成持久状态、状态原因和 Room 112 迁移

**Files:**

- Modify: `app/src/main/java/io/legado/app/data/entities/NovelAudioEntities.kt`
- Modify: `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/data/AppDatabase.kt`
- Modify: `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPersistenceStateTest.kt`
- Modify: `app/src/androidTest/java/io/legado/app/NovelAudioMigrationTest.kt`
- Generate: `app/schemas/io.legado.app.data.AppDatabase/112.json`

- [ ] **Step 1: 先写失败的状态和迁移测试**

在 `NovelAudioPersistenceStateTest.kt` 先锁定状态常量和状态原因：

```kotlin
@Test
fun stateReasonsDistinguishAutoExpiryFromNetworkAndUserCancellation() {
    assertEquals("PARTIAL", NovelAudioStates.PARTIAL)
    assertEquals("WAITING_NETWORK", NovelAudioStates.WAITING_NETWORK)
    assertEquals("PAUSED", NovelAudioStates.PAUSED)
    assertEquals("CANCELLED", NovelAudioStates.CANCELLED)
    assertEquals("EXPIRED", NovelAudioStates.EXPIRED)
}

@Test
fun taskDefaultsKeepReasonEmptyForExistingRows() {
    assertEquals("", NovelAudioDownloadTaskEntity().stateReason)
    assertEquals("", NovelAudioChapterPlanEntity().stateReason)
}
```

在 `NovelAudioMigrationTest.kt` 增加从 111 到 112 的真实 Room 迁移断言：

```kotlin
@Test
fun migration111To112PreservesBooksAndAddsNovelAudioReasons() {
    helper.createDatabase(TEST_DB, 111).apply {
        execSQL("INSERT INTO books(bookUrl, name, author, origin, originName) VALUES ('book://old', '旧书', '', '', '')")
        close()
    }

    helper.runMigrationsAndValidate(TEST_DB, 112, true, DatabaseMigrations.migration_111_112)
        .query("SELECT name FROM books WHERE bookUrl = 'book://old'")
        .use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧书", cursor.getString(0))
        }
}
```

运行：

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.novel.NovelAudioPersistenceStateTest' \
  --tests 'io.legado.app.NovelAudioMigrationTest'
```

预期：测试先因状态常量、字段和 migration 不存在而失败。

- [ ] **Step 2: 增加状态常量和原因字段**

在 `NovelAudioStates` 增加：

```kotlin
const val PARTIAL = "PARTIAL"
const val WAITING_NETWORK = "WAITING_NETWORK"
const val PAUSED = "PAUSED"
const val CANCELLED = "CANCELLED"
const val EXPIRED = "EXPIRED"
```

在 `NovelAudioChapterPlanEntity` 与 `NovelAudioDownloadTaskEntity` 增加：

```kotlin
@ColumnInfo(defaultValue = "")
val stateReason: String = ""
```

状态原因只保存脱敏代码，例如 `AUTO_AUTH_EXPIRED`、`NETWORK_REQUIRED`、
`USER_CANCELLED`、`CHECKSUM_MISMATCH`；禁止保存正文、Token、完整 URL 或远端响应。

- [ ] **Step 3: 增加 111→112 显式迁移**

在 `AppDatabase.kt`：

```kotlin
@Database(version = 112, ...)
```

在 `DatabaseMigrations.kt` 的 migrations 数组中加入 `migration_111_112`，并实现：

```kotlin
val migration_111_112 = object : Migration(111, 112) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE novel_audio_chapter_plans " +
                "ADD COLUMN stateReason TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE novel_audio_download_tasks " +
                "ADD COLUMN stateReason TEXT NOT NULL DEFAULT ''"
        )
    }
}
```

不要使用 destructive migration，也不要改写已有 110→111 migration。

- [ ] **Step 4: 增加恢复和任务查询 DAO**

在 `NovelAudioDao` 增加以下能力：

```kotlin
@Query("""
    SELECT * FROM novel_audio_download_tasks
    WHERE retention = 'PINNED'
      AND state IN ('QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK', 'PAUSED')
    ORDER BY createdAt ASC, taskId ASC
""")
fun recoverablePinnedTasks(): List<NovelAudioDownloadTaskEntity>

@Query("""
    SELECT * FROM novel_audio_download_tasks
    WHERE physicalBookUrl = :physicalBookUrl
    ORDER BY chapterIndex ASC, retention = 'PINNED' DESC, updatedAt DESC
""")
fun tasksForBook(physicalBookUrl: String): List<NovelAudioDownloadTaskEntity>

@Query("""
    UPDATE novel_audio_download_tasks
    SET state = :state, stateReason = :reason, progress = :progress,
        updatedAt = :updatedAt
    WHERE taskId = :taskId AND generation = :expectedGeneration
""")
fun compareAndSetTaskState(
    taskId: String,
    expectedGeneration: Long,
    state: String,
    reason: String,
    progress: Int,
    updatedAt: Long = System.currentTimeMillis()
): Int
```

为 plan 增加同等的状态原因 CAS 方法。`NovelAudioRepository` 只通过这些 CAS 方法
修改任务状态，所有 progress 值限制在 `0..100`。

- [ ] **Step 5: 生成 schema、运行迁移和状态测试**

运行：

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.novel.NovelAudioPersistenceStateTest'
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioMigrationTest
```

预期：JVM 和 migration 设备断言通过，schema 生成版本为 112，旧书数据仍存在。

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/data/entities/NovelAudioEntities.kt \
  app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt \
  app/src/main/java/io/legado/app/data/AppDatabase.kt \
  app/src/main/java/io/legado/app/data/DatabaseMigrations.kt \
  app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPersistenceStateTest.kt \
  app/src/androidTest/java/io/legado/app/NovelAudioMigrationTest.kt \
  app/schemas/io.legado.app.data.AppDatabase/112.json
git commit -m "feat(audiobook): 完善持久任务状态与迁移"
```

---

### Task 2: 抽取共享准备环境并接入云调用预算闸门

**Files:**

- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationEnvironment.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioCloudBudgetGate.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/server/NovelAudioCloudBudgetGateTest.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioAndroidConfigStore.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationEnvironmentTest.kt`

- [ ] **Step 1: 先写预算闸门失败测试**

`NovelAudioCloudBudgetGateTest.kt` 使用内存 ledger，验证请求开始前预占、并发为一、
失败请求仍计数和达到上限后熔断：

```kotlin
@Test
fun secondConcurrentRequestIsRejected() = runTest {
    val gate = testGate(maxConcurrent = 1)
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    val first = async {
        gate.run(ANALYSIS, 10) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
    }
    firstStarted.await()
    assertFailsWith<NovelAudioBudgetExceeded> {
        gate.run(ANALYSIS, 10) { Unit }
    }
    releaseFirst.complete(Unit)
    first.await()
}

@Test
fun failedRequestStillConsumesReservedCharacters() = runTest {
    val gate = testGate(analysisCharacters = 10)
    assertFailsWith<IOException> {
        gate.run(ANALYSIS, 10) { throw IOException("test") }
    }
    assertFailsWith<NovelAudioBudgetExceeded> {
        gate.run(ANALYSIS, 1) { Unit }
    }
}
```

- [ ] **Step 2: 实现持久 ledger 和闸门接口**

定义以下接口，生产实现保存到应用 `noBackupFilesDir` 下的独立原子 JSON 文件，
测试实现使用内存 map：

```kotlin
enum class NovelAudioBudgetOperation { ANALYSIS, TTS }

data class NovelAudioBudgetLimits(
    val analysisRequests: Int = 20,
    val analysisCharacters: Int = 24_000,
    val ttsRequests: Int = 100,
    val ttsCharacters: Int = 5_000,
    val maxConcurrent: Int = 1
)

interface NovelAudioBudgetLedger {
    fun reserve(scope: String, operation: NovelAudioBudgetOperation, characters: Int): Boolean
    fun snapshot(scope: String): NovelAudioBudgetSnapshot
}

class NovelAudioCloudBudgetGate(
    private val scope: String,
    private val limits: NovelAudioBudgetLimits,
    private val ledger: NovelAudioBudgetLedger
) {
    suspend fun <T> run(
        operation: NovelAudioBudgetOperation,
        characters: Int,
        block: suspend () -> T
    ): T
}
```

`run` 用 `Mutex` 限制并发；先调用 ledger `reserve`，再执行 block。block 失败时不回滚
已预占的次数和字符数。所有异常继续保留取消语义，`CancellationException` 不被包装成
额度错误。

- [ ] **Step 3: 抽出共享准备环境**

`NovelAudioPreparationEnvironment` 统一创建当前已有的：

```kotlin
class NovelAudioPreparationEnvironment(
    val repository: NovelAudioRepository,
    val runner: NovelAudioPreparationRunner,
    val downloadCoordinator: NovelAudioDownloadCoordinator
)
```

`NovelAudioPreparationRunner` 负责：

```kotlin
suspend fun run(
    snapshot: NovelAudioChapterSnapshot,
    generation: Long,
    serverScope: String,
    retention: String,
    existingCharacters: List<NovelAudioCharacter>,
    existingBindings: List<NovelAudioVoiceBinding>,
    isCurrent: () -> Boolean
): NovelAudioPreparationResult
```

把当前 `NovelAudioPreparationCoordinator.start` 中的 client、registry、analysis、
producer、repository、artifact store 和 decoder 装配迁入 environment；Coordinator
只负责 request identity、缓存快照和事件发布。

- [ ] **Step 4: 在分析与 TTS 调用处包裹预算**

分析预算使用本批 `TextUnit` 的 `text.length`；TTS 预算使用单段 text 的 UTF-16 长度。
包裹方式固定为：

```kotlin
budgetGate.run(
    operation = NovelAudioBudgetOperation.ANALYSIS,
    characters = units.sumOf { it.text.length }
) {
    client.analyze(request)
}
```

```kotlin
budgetGate.run(
    operation = NovelAudioBudgetOperation.TTS,
    characters = segment.text.length
) {
    client.synthesize(request)
}
```

不在 `NovelAudioServerClient` 内部增加自动重试或备用模型。

- [ ] **Step 5: 运行准备环境和预算测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.server.NovelAudioCloudBudgetGateTest' \
  --tests 'io.legado.app.help.readaloud.novel.NovelAudioPreparationEnvironmentTest'
```

预期：预算测试覆盖并发、上限、失败计数和取消；现有
`NovelAudioPreparationCoordinatorTest` 保持通过。

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationEnvironment.kt \
  app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioCloudBudgetGate.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationCoordinator.kt \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt \
  app/src/main/java/io/legado/app/help/readaloud/server/NovelAudioAndroidConfigStore.kt \
  app/src/test/java/io/legado/app/help/readaloud/server/NovelAudioCloudBudgetGateTest.kt \
  app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioPreparationEnvironmentTest.kt
git commit -m "feat(audiobook): 接入共享准备环境与额度闸门"
```

---

### Task 3: 实现 local-first 播放和离线缺失失败

**Files:**

- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioLocalPlaybackResolver.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioLocalPlaybackResolverTest.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadAloud.kt`
- Modify: `app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/NovelAudioPreparationState.kt`
- Modify: `app/src/test/java/io/legado/app/service/NovelAudioReadAloudServiceTest.kt`

- [ ] **Step 1: 先写 local-first 失败测试**

```kotlin
@Test
fun readyLocalPlanWinsWithoutCallingNetworkOrPreparation() {
    val calls = mutableListOf<String>()
    val resolver = resolver(
        networkAvailable = { calls += "network"; true },
        localPlan = readyPlan()
    )

    assertEquals(
        NovelAudioLocalPlaybackResolver.Result.Ready(readyPlan()),
        resolver.resolve("book://test", 3)
    )
    assertEquals(listOf("network"), calls)
}

@Test
fun offlineMissingPlanReturnsExplicitFailureWithoutRemotePreparation() {
    val preparationCalls = mutableListOf<Unit>()
    val resolver = resolver(
        networkAvailable = { false },
        localPlan = null
    )

    assertEquals(
        NovelAudioLocalPlaybackResolver.Result.OfflineMissing,
        resolver.resolve("book://test", 3)
    )
    assertTrue(preparationCalls.isEmpty())
}
```

同时为 `NovelAudioPreparationState` 增加 `OFFLINE_AUDIO_MISSING` 原因测试。

- [ ] **Step 2: 实现 resolver**

定义：

```kotlin
class NovelAudioLocalPlaybackResolver(
    private val repository: NovelAudioRepository,
    private val networkAvailable: () -> Boolean
) {
    fun resolve(
        physicalBookUrl: String,
        chapterIndex: Int
    ): Result

    sealed interface Result {
        data class Ready(val plan: NovelAudioChapterPlan) : Result
        data object NeedsOnlinePreparation : Result
        data object OfflineMissing : Result
    }
}
```

`resolve` 必须先调用 repository 的完整 READY 查询；该查询校验 plan state、
所有 artifact state、文件路径、大小、SHA-256 和 decoder。只有完整 READY 才返回
`Ready`。查不到 READY 时使用 `NetworkUtils.isAvailable()`；离线返回
`OfflineMissing`，不得调用 server client。

- [ ] **Step 3: 接入 `ReadAloud`**

在 `prepareNovelAudioIfRequested` 中，把现有无条件：

```kotlin
NovelAudioPreparationCoordinator.request(bookUrl, chapterIndex)
```

改为：

```kotlin
when (localPlaybackResolver.resolve(bookUrl, chapterIndex)) {
    is NovelAudioLocalPlaybackResolver.Result.Ready -> return
    NovelAudioLocalPlaybackResolver.Result.OfflineMissing -> {
        postEvent(
            EventBus.NOVEL_AUDIO_PREPARATION,
            NovelAudioPreparationState.offlineMissing(bookUrl, chapterIndex)
        )
        return
    }
    NovelAudioLocalPlaybackResolver.Result.NeedsOnlinePreparation -> Unit
}
NovelAudioPreparationCoordinator.request(bookUrl, chapterIndex)
```

生产环境使用 `NetworkUtils.isAvailable()`，测试使用函数注入。当前章节已经 READY
时只启动播放 Service，不重新分析、查声库或请求 TTS。

- [ ] **Step 4: 让 Service 正确消费离线失败**

`NovelAudioReadAloudService.observeLiveBus` 对 `OFFLINE_AUDIO_MISSING` 只发布错误状态
并暂停，不重新调用 preparation coordinator。`prepareAndPlay` 继续保留已有完整
artifact 校验，不允许只依据 Room 的 READY 字段播放。

- [ ] **Step 5: 运行 local-first 和服务测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioLocalPlaybackResolverTest' \
  --tests 'io.legado.app.service.NovelAudioReadAloudServiceTest'
```

预期：本地 READY、缺失在线、缺失离线、损坏文件和 decoder 失败均有明确结果。

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioLocalPlaybackResolver.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt \
  app/src/main/java/io/legado/app/model/ReadAloud.kt \
  app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt \
  app/src/main/java/io/legado/app/help/readaloud/NovelAudioPreparationState.kt \
  app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioLocalPlaybackResolverTest.kt \
  app/src/test/java/io/legado/app/service/NovelAudioReadAloudServiceTest.kt
git commit -m "feat(audiobook): 优先本地音频并封锁离线远程请求"
```

---

### Task 4: 接通后续章节快照和 AUTO 三章串行调度

**Files:**

- Create: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoader.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioAutoPrefetchCoordinator.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoaderTest.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioAutoPrefetchCoordinatorTest.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadBook.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadAloud.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/AudioPrefetchLifecycle.kt`
- Modify: `app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt`

- [ ] **Step 1: 先写快照 loader 测试**

后续章节不能调用 `ReadBook.loadContentAwait` 直接改变当前阅读页；先写测试证明 loader
只读取章节、应用同一套正文规则并返回快照：

```kotlin
@Test
fun loaderUsesProcessedFinalTextWithoutInstallingReaderChapter() = runTest {
    val loader = NovelAudioChapterSnapshotLoader(
        chapter = { BookChapter(bookUrl = "book://test", index = 2, url = "chapter://2") },
        rawContent = { "原始正文" },
        process = { "最终排版正文" }
    )

    val snapshot = loader.load("book://test", 2, generation = 9L)

    assertEquals("最终排版正文", snapshot!!.paragraphs.single())
    assertFalse(loader.installedIntoReader)
}
```

- [ ] **Step 2: 从 `ReadBook` 抽取纯快照装载入口**

在 `ReadBook` 增加一个只读异步方法，内部复用已有：

- `appDb.bookChapterDao.getChapter(book.bookUrl, index)`；
- `BookHelp.getContent(book, chapter)` 或现有 `downloadAwait(book, chapter)`；
- `ContentProcessor.get(book.name, book.origin)`；
- `ParagraphRuleProcessor.process(...)`；
- 当前正文规则版本和布局 key。

该方法只调用 `NovelAudioChapterSnapshotFactory.fromProcessedContent`，不调用
`installChapterSlot`、`upContent`、`ChapterProvider.getTextChapterAsync` 或阅读回调。
返回值必须绑定 `bookUrl`、chapter index、generation、layout/rules version；
取消或切书时丢弃结果。

- [ ] **Step 3: 定义 AUTO 协调器**

实现：

```kotlin
class NovelAudioAutoPrefetchCoordinator(
    private val lifecycle: AudioPrefetchLifecycle,
    private val snapshotLoader: NovelAudioChapterSnapshotLoader,
    private val repository: NovelAudioRepository,
    private val environment: NovelAudioPreparationEnvironment
) {
    fun start(
        work: AudioPrefetchLifecycle.Work,
        bookUrl: String,
        currentChapterIndex: Int,
        chapterCount: Int
    )

    fun cancel(work: AudioPrefetchLifecycle.Work? = null)
}
```

`start` 计算：

```kotlin
val targets = (currentChapterIndex + 1 until chapterCount)
    .take(3)
```

每个目标开始前调用 `lifecycle.isAllowed(work, target)`；目标完成后才进入下一个。
每章顺序必须为：

```text
章节快照 → plan producer → AUTO task → 缺段下载 → READY
```

每个 plan 绑定：

- `bookUrl`
- chapter index
- snapshot hash
- generation
- `continuationId`
- `retention=AUTO`

授权失效时将未完成任务标记为 `QUEUED/AUTO_AUTH_EXPIRED` 或
`EXPIRED/AUTO_AUTH_EXPIRED`，不得把迟到结果写入新 request。

- [ ] **Step 4: 把 Service Work 接入 AUTO**

在当前章节本地 READY 后调用：

```kotlin
prefetchAssembled(work, bookUrl, chapterIndex, chapterCount)
autoPrefetchCoordinator.start(
    work = work,
    bookUrl = bookUrl,
    currentChapterIndex = chapterIndex,
    chapterCount = chapterCount
)
```

`pauseReadAloud`、`playStop`、`onDestroy`、`prevChapter`、`nextChapter` 和切书路径
都调用 `autoPrefetchCoordinator.cancel(currentWork)`。跨章节继续时只复用仍有效的
Work/continuation，不无条件签发新 token。

- [ ] **Step 5: 补齐 AUTO 竞态测试**

`NovelAudioAutoPrefetchCoordinatorTest` 至少覆盖：

```kotlin
@Test fun onlyNextThreeChaptersAreScheduled()
@Test fun chaptersRunSerially()
@Test fun pauseStopsCurrentAndFutureAutoWork()
@Test fun staleWorkCannotCommitAfterNewWorkStarts()
@Test fun sameBookAndChapterStillUsesRequestIdentity()
@Test fun endOfBookTruncatesWindow()
```

现有 `AudioPrefetchLifecycleTest` 的固定三章测试必须保持通过，并新增一个设备可观察
的 AUTO task 状态测试。

- [ ] **Step 6: 运行测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.novel.NovelAudioChapterSnapshotLoaderTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioAutoPrefetchCoordinatorTest' \
  --tests 'io.legado.app.help.readaloud.offline.AudioPrefetchLifecycleTest'
```

- [ ] **Step 7: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoader.kt \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioAutoPrefetchCoordinator.kt \
  app/src/main/java/io/legado/app/model/ReadBook.kt \
  app/src/main/java/io/legado/app/model/ReadAloud.kt \
  app/src/main/java/io/legado/app/help/readaloud/offline/AudioPrefetchLifecycle.kt \
  app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt \
  app/src/test/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoaderTest.kt \
  app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioAutoPrefetchCoordinatorTest.kt
git commit -m "feat(audiobook): 接通自动后三章预缓存"
```

---

### Task 5: 实现 PINNED 范围下载和任务控制

**Files:**

- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinator.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinatorTest.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoader.kt`

- [ ] **Step 1: 先写 PINNED 行为失败测试**

```kotlin
@Test
fun pinnedRangeCreatesOnePersistentTaskPerChapter() = runTest {
    val coordinator = coordinator()
    coordinator.enqueue("book://test", 4..6)

    assertEquals(listOf(4, 5, 6), repository.tasksForBook("book://test").map { it.chapterIndex })
    assertTrue(repository.tasksForBook("book://test").all {
        it.retention == NovelAudioRetention.PINNED
    })
}

@Test
fun pinnedTaskDoesNotRequireAutoWork() = runTest {
    coordinator().enqueue("book://test", 2..2)
    runPinnedTaskWithoutAutoLifecycle()
    assertEquals(NovelAudioStates.READY, repository.task(2).state)
}

@Test
fun deletingOnePinnedGenerationCannotDeleteAnotherPlan() {
    repository.deletePinned("plan:new", "book://test", 2, generation = 9L)
    assertTrue(repository.exists("plan:old"))
}
```

- [ ] **Step 2: 实现范围入口和 coordinator**

定义：

```kotlin
class NovelAudioPinnedDownloadCoordinator(
    private val chapterLoader: NovelAudioChapterSnapshotLoader,
    private val environment: NovelAudioPreparationEnvironment,
    private val repository: NovelAudioRepository
) {
    fun enqueue(bookUrl: String, chapters: IntRange)
    fun pause(taskId: String)
    fun resume(taskId: String)
    fun cancel(taskId: String)
    fun delete(taskId: String)
    fun tasks(bookUrl: String): List<NovelAudioDownloadTaskEntity>
}
```

`chapters` 必须经过：

```kotlin
require(!chapters.isEmpty())
require(chapters.first >= 0)
require(chapters.last - chapters.first < MAX_PINNED_RANGE)
```

`MAX_PINNED_RANGE` 固定为 20，既支持“后续 10/20 章”又阻止一期入口扩展成整本下载。
范围超限直接拒绝，不截断用户选择。

每章独立生成 snapshot、plan 和 PINNED task；已有 AUTO 完整 READY 章节执行 retention
升级，不重复分析和 TTS；部分完成章节只补缺段。

- [ ] **Step 3: 完成暂停、继续、取消和删除**

状态转换固定为：

```text
QUEUED → RUNNING → READY
RUNNING → PAUSED
PAUSED → QUEUED
QUEUED/RUNNING/PAUSED/PARTIAL → CANCELLED
FAILED → QUEUED
```

取消保留已提交的 READY artifact；删除才删除对应 plan、artifact、manifest 和 task。
删除操作使用 `planId + generation + taskId` 三重匹配，Room 删除和 artifact 删除顺序为：

1. CAS 标记 `CANCELLED/USER_CANCELLED`；
2. 删除精确匹配的 Room artifact/plan/task；
3. 删除同 plan 目录下文件；
4. 重新 reconcile，发现其他 generation 时不得触碰。

- [ ] **Step 4: 让 DownloadCoordinator 支持恢复**

增加可选的 task state callback：

```kotlin
suspend fun downloadPlan(
    plan: NovelAudioChapterPlan,
    retention: String,
    isAutoAllowed: () -> Boolean,
    isCancelled: () -> Boolean = { false }
): Result
```

PINNED 不读取 AUTO lifecycle；取消只在分段边界停止，已提交的 artifact 不删除。
所有 segment 仍必须经过 artifact store、SHA-256、大小和 decoder 检查。

- [ ] **Step 5: 运行 PINNED 测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioPinnedDownloadCoordinatorTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioArtifactStoreTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioArtifactRepairTest'
```

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinator.kt \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioDownloadCoordinator.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt \
  app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioChapterSnapshotLoader.kt \
  app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinatorTest.kt
git commit -m "feat(audiobook): 支持固定章节范围下载"
```

---

### Task 6: 实现启动恢复和 Room/artifact reconcile

**Files:**

- Create: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioRecoveryCoordinator.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioRecoveryCoordinatorTest.kt`
- Create: `app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt`
- Modify: `app/src/main/java/io/legado/app/App.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt`
- Modify: `app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt`

- [ ] **Step 1: 先写恢复失败测试**

```kotlin
@Test
fun recoveryMarksAutoTaskExpiredWithoutRestoringAuthorization() {
    repository.insertTask(autoTask(state = NovelAudioStates.RUNNING))
    recovery.scan(networkAvailable = true)

    val task = repository.task("auto-task")
    assertEquals(NovelAudioStates.EXPIRED, task.state)
    assertEquals("AUTO_AUTH_EXPIRED", task.stateReason)
}

@Test
fun recoveryRequeuesPinnedPartialTaskButDoesNothingRemoteWhenOffline() {
    repository.insertTask(pinnedTask(state = NovelAudioStates.RUNNING))
    recovery.scan(networkAvailable = false)

    assertEquals(NovelAudioStates.WAITING_NETWORK, repository.task("pinned-task").state)
    assertEquals(0, fakeRemote.calls)
}
```

- [ ] **Step 2: 实现本地 reconcile**

`NovelAudioRecoveryCoordinator.scan(networkAvailable)` 顺序固定为：

1. 查询 `QUEUED/RUNNING/PARTIAL/WAITING_NETWORK/PAUSED`；
2. 读取 plan JSON，generation 无效则标记 `FAILED/INVALID_PLAN`；
3. 检查每个 artifact 的路径、大小、SHA-256、decoder；
4. 文件完整但 Room 未 READY 时调用 repository 的 CAS 完成提交；
5. Room READY 但文件缺失/损坏时调用 `invalidateReadyState`，状态变为 PARTIAL；
6. AUTO 任务统一标记 `EXPIRED/AUTO_AUTH_EXPIRED`；
7. PINNED 完整任务保留 READY，缺段任务根据 network 状态进入 QUEUED 或 WAITING_NETWORK。

恢复扫描必须幂等，重复执行不增加 task、不重复 TTS、不删除有效文件。

- [ ] **Step 3: 接入应用启动**

在 `App.onCreate` 或应用初始化完成后调用一次：

```kotlin
NovelAudioRecoveryCoordinator(appDb, appCtx.filesDir).start()
```

使用进程内原子标记避免多次启动重复扫描；扫描只访问本地 Room 和文件系统。远程
恢复只能由用户主动播放或用户明确开启 PINNED 任务时触发。

- [ ] **Step 4: 增加设备恢复测试**

`NovelAudioRecoveryDeviceTest` 使用独立数据库、独立 artifact 根目录和独立 Keystore alias，
覆盖：

- 旧书/旧角色/旧朗读缓存仍保留；
- PINNED partial 只补缺失段；
- AUTO 任务不恢复；
- 文件已提交但 Room 未完成；
- Room READY 但文件被删除；
- 飞行模式启动不产生网络请求。

- [ ] **Step 5: 运行测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioRecoveryCoordinatorTest'
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioRecoveryDeviceTest
```

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioRecoveryCoordinator.kt \
  app/src/main/java/io/legado/app/App.kt \
  app/src/main/java/io/legado/app/help/readaloud/novel/NovelAudioRepository.kt \
  app/src/main/java/io/legado/app/data/dao/NovelAudioDao.kt \
  app/src/test/java/io/legado/app/help/readaloud/offline/NovelAudioRecoveryCoordinatorTest.kt \
  app/src/androidTest/java/io/legado/app/NovelAudioRecoveryDeviceTest.kt
git commit -m "feat(audiobook): 增加持久任务启动恢复"
```

---

### Task 7: 接通阅读页 PINNED 入口和任务管理

**Files:**

- Create: `app/src/main/java/io/legado/app/ui/book/read/config/NovelAudioDownloadDialog.kt`
- Create: `app/src/test/java/io/legado/app/ui/book/read/config/NovelAudioDownloadDialogTest.kt`
- Create: `app/src/androidTest/java/io/legado/app/NovelAudioDownloadDeviceTest.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinator.kt`

- [ ] **Step 1: 完成 UI K1 开工检查**

在实现前把以下内容记录到任务注释或对应测试说明中：

- 复用 `ReadAloudPlayerPanel` 现有按钮、弹框和 `showDialogFragment`；
- 复用现有面 token，不新增硬编码颜色；
- 覆盖默认、自定义 accent、本地主题包、夜间四态；
- 新增入口对应 `NovelAudioDownloadDeviceTest`；
- 下载状态使用已有 `PlayerUiState`/EventBus 观察机制，不引入新的全局 UI 状态单例。

- [ ] **Step 2: 先写对话框逻辑失败测试**

```kotlin
@Test
fun presetActionsCreateExpectedRanges() {
    assertEquals(5..5, NovelAudioDownloadDialog.rangeFor(CURRENT = 5, count = 6, preset = CURRENT))
    assertEquals(6..15, NovelAudioDownloadDialog.rangeFor(5, 30, AFTER_10))
    assertEquals(6..25, NovelAudioDownloadDialog.rangeFor(5, 30, AFTER_20))
}

@Test
fun rangeCannotCrossBookEndOrBecomeWholeBook() {
    assertEquals(6..9, NovelAudioDownloadDialog.rangeFor(5, 10, AFTER_20))
    assertNull(NovelAudioDownloadDialog.validateRange(0..29, chapterCount = 30))
}
```

`NovelAudioDownloadDialog` 提供本章、后续 10 章、后续 20 章、自定义范围四项，
自定义范围最多 20 章；当前章不在“后续”范围内，避免重复生成。

- [ ] **Step 3: 实现入口和任务列表**

在 `ReadAloudPlayerPanel` 扩展区域增加：

- “下载本章”
- “下载后续 10 章”
- “下载后续 20 章”
- “自定义范围”
- “查看本书下载任务”

按钮调用 `NovelAudioPinnedDownloadCoordinator.enqueue`，不调用
`AudioPrefetchLifecycle.requestUserPlay`。任务列表显示章节标题、状态、进度、原因，
并提供暂停、继续、取消和删除。

`ReadBookActivity` 只负责把当前 book URL、章节数和 `appDb.bookChapterDao.getChapter`
提供给面板；不要把 Room 查询、TTS 请求或文件删除直接写入 Activity。

- [ ] **Step 4: 添加 UI 状态事件**

新增或扩展一个不含正文和凭据的状态事件，例如：

```kotlin
data class NovelAudioDownloadUiState(
    val bookUrl: String,
    val taskId: String,
    val chapterIndex: Int,
    val state: String,
    val progress: Int,
    val stateReason: String
)
```

任务状态变化后由 coordinator 发布事件；面板只过滤当前 book URL。切书时取消订阅，
不取消 PINNED 任务本身。

- [ ] **Step 5: 运行 UI 测试**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.ui.book.read.config.NovelAudioDownloadDialogTest'
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -PuseThemeDeviceTestRunner=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioDownloadDeviceTest
```

预期：四态主题 runner 单独执行；下载入口能够打开、关闭、提交范围，任务状态不跨书污染。

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/ui/book/read/config/NovelAudioDownloadDialog.kt \
  app/src/main/java/io/legado/app/ui/book/read/ReadAloudPlayerPanel.kt \
  app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt \
  app/src/main/java/io/legado/app/help/readaloud/offline/NovelAudioPinnedDownloadCoordinator.kt \
  app/src/test/java/io/legado/app/ui/book/read/config/NovelAudioDownloadDialogTest.kt \
  app/src/androidTest/java/io/legado/app/NovelAudioDownloadDeviceTest.kt
git commit -m "feat(audiobook): 增加手动下载入口与任务管理"
```

---

### Task 8: 完成跨章节播放、Work 绑定和普通朗读回归

**Files:**

- Modify: `app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadAloud.kt`
- Modify: `app/src/main/java/io/legado/app/receiver/MediaButtonReceiver.kt`
- Modify: `app/src/main/java/io/legado/app/help/readaloud/ReadAloudPlaybackState.kt`
- Modify: `app/src/test/java/io/legado/app/service/NovelAudioReadAloudServiceTest.kt`
- Create: `app/src/test/java/io/legado/app/help/readaloud/ReadAloudRegressionTest.kt`
- Create: `app/src/androidTest/java/io/legado/app/NovelAudioPlaybackDeviceTest.kt`

- [ ] **Step 1: 先写跨章和普通路线失败测试**

```kotlin
@Test
fun nextChapterKeepsContinuationOnlyWhenWorkIsCurrent() {
    assertTrue(NovelAudioReadAloudService.canContinueWithWork(current = true, requested = true))
    assertFalse(NovelAudioReadAloudService.canContinueWithWork(current = false, requested = true))
}

@Test
fun ordinaryRoutesStillResolveToExistingServices() {
    assertEquals(HttpReadAloudService::class.java, SpeechRouteServiceResolver.routeToClass(httpRoute))
    assertEquals(TTSReadAloudService::class.java, SpeechRouteServiceResolver.routeToClass(systemRoute))
}
```

设备测试场景固定为：

- 系统 TTS 播放/暂停/恢复；
- HTTP TTS 播放/暂停/恢复；
- NovelAudio 当前章 → 下一章；
- 媒体按键下一章/上一章；
- 音频焦点丢失/恢复；
- 阅读进度落库和重新打开。

- [ ] **Step 2: 绑定 Service 当前 Work**

`NovelAudioReadAloudService` 保存当前有效 Work 和 `continuationId`，所有
`prepareAndPlay`、`nextChapter`、`prevChapter`、media resume 和准备结果回调都先调用：

```kotlin
if (work != null && !AudioPrefetchPlayback.lifecycle.isCurrent(work)) return
```

章节切换成功后由当前 Work 重新组装窗口，不调用 `requestUserPlay`。pause、stop、
book switch 和 destroy 仍调用 lifecycle revoke。

- [ ] **Step 3: 让跨章播放先查本地 READY**

在 `nextChapter` 的准备路径中，先使用 local-first resolver 查询下一章：

```text
READY → 直接组装下一章 MediaItem
缺失且在线 → 保持当前 Work，等待 AUTO/PINNED 准备
缺失且离线 → 发布 OFFLINE_AUDIO_MISSING，停止，不拉正文
```

播放进度事件必须包含 book URL、chapter index、chapter URL、plan key、segment key、
chapter position；旧章节事件不得覆盖新章节进度。

- [ ] **Step 4: 保持 Base service 默认行为**

只增加受保护的可选 hook，例如：

```kotlin
protected open fun onNovelAudioChapterBoundary(): Boolean = false
```

默认返回 false，系统 TTS/HTTP TTS 继续走原有 contentList、通知、音频焦点和媒体键。
禁止把 NovelAudio 分支写进普通 TTS 的文本循环。

- [ ] **Step 5: 运行 JVM 和设备回归**

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.service.NovelAudioReadAloudServiceTest' \
  --tests 'io.legado.app.help.readaloud.ReadAloudRegressionTest'
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioPlaybackDeviceTest
```

- [ ] **Step 6: 提交本批**

```sh
git add \
  app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt \
  app/src/main/java/io/legado/app/service/BaseReadAloudService.kt \
  app/src/main/java/io/legado/app/model/ReadAloud.kt \
  app/src/main/java/io/legado/app/receiver/MediaButtonReceiver.kt \
  app/src/main/java/io/legado/app/help/readaloud/ReadAloudPlaybackState.kt \
  app/src/test/java/io/legado/app/service/NovelAudioReadAloudServiceTest.kt \
  app/src/test/java/io/legado/app/help/readaloud/ReadAloudRegressionTest.kt \
  app/src/androidTest/java/io/legado/app/NovelAudioPlaybackDeviceTest.kt
git commit -m "feat(audiobook): 完成跨章节本地播放与朗读回归"
```

---

### Task 9: 完成 NovelAudioServer Android 真实桥接联调

**Files:**

- Modify: `docs/AI_AUDIOBOOK_CLOUD_TRIAL.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Modify: `scripts/novel-audio-bridge/README.md`
- Create: `app/src/androidTest/java/io/legado/app/NovelAudioServerEndToEndDeviceTest.kt`
- Modify: `app/src/test/java/io/legado/app/help/readaloud/server/NovelAudioServerClientTest.kt`

- [ ] **Step 1: 先完成不联网契约和设备 Mock 测试**

`NovelAudioServerEndToEndDeviceTest` 默认使用本地 Mock，不读取真实 Key，验证：

- health；
- chapter analyze；
- voices/match；
- TTS 返回真实非空音频夹具；
- artifact SHA-256/大小/decoder；
- 三角色 plan 的 speaker/voice binding 稳定；
- server scope 变化使旧 plan 不复用；
- Android 配置更换地址时旧 token 不混用。

运行：

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.server.NovelAudioServerClientTest'
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioServerEndToEndDeviceTest
```

- [ ] **Step 2: 启动现有百炼桥接**

只在用户已经确认免费额度和“用完即停”生效后执行：

```sh
sh scripts/novel-audio-bridge/start.sh --check
sh scripts/novel-audio-bridge/start.sh --serve --free-quota-confirmed
adb reverse tcp:8787 tcp:8787
```

Android 配置使用：

```text
http://127.0.0.1:8787
```

Token 从本地 `novel-audio.local.connection.json` 读取，不写入命令行、仓库、日志
或会话输出。服务只监听 loopback，不暴露到局域网或公网。

- [ ] **Step 3: 执行一次受限真实三角色短章**

只使用程序内原创短章，固定：

- 分析模型 `qwen3.7-plus`；
- TTS 模型 `qwen3-tts-instruct-flash`；
- 三角色；
- 当前桥接的分析 20 次/24000 字符、TTS 100 次/5000 字符本地上限；
- 最大并发 1；
- 不重试、不换模型、不扩大到用户正文。

记录以下脱敏证据：

- Android 配置成功；
- health 成功；
- analysis 请求次数和字符数；
- 三段 TTS 请求次数和字符数；
- 每段 content type、size、SHA-256 和 decoder 结果；
- 实际播放结果；
- 三种声音听感；
- 失败阶段和停止原因。

- [ ] **Step 4: 关闭桥接并保存结果**

停止服务后确认在途 worker 退出；不把供应商已经接收的请求误写成可退回。把结果追加到
`AI_AUDIOBOOK_CLOUD_TRIAL.md` 和 `AI_AUDIOBOOK_PROGRESS.md`，不记录 Key、正文、
签名 URL 或完整响应。

- [ ] **Step 5: 提交本批**

```sh
git add \
  docs/AI_AUDIOBOOK_CLOUD_TRIAL.md \
  docs/AI_AUDIOBOOK_PROGRESS.md \
  scripts/novel-audio-bridge/README.md \
  app/src/androidTest/java/io/legado/app/NovelAudioServerEndToEndDeviceTest.kt \
  app/src/test/java/io/legado/app/help/readaloud/server/NovelAudioServerClientTest.kt
git commit -m "test(audiobook): 完成真实桥接端到端试听记录"
```

---

### Task 10: 完成全量验证、门禁、文档和 debug APK 交付

**Files:**

- Modify: `app/src/main/assets/updateLog.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Modify: `docs/AI_AUDIOBOOK_ANDROID_PLAN.md`
- Modify: `docs/AI_AUDIOBOOK_ARCHITECTURE.md`
- Modify: `CURRENT_ARCHITECTURE.md`
- Create/Modify: `issues-found.md` 或现有 issues 记录文件
- Verify: `ai_tests/config/gate_registry.json`
- Verify: `ai_tests/scripts/run_gates.py`

- [ ] **Step 1: 逐批运行 JVM**

先运行本期新增定向测试，再运行全量：

```sh
bash ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.novel.NovelAudioPersistenceStateTest' \
  --tests 'io.legado.app.help.readaloud.server.NovelAudioCloudBudgetGateTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioLocalPlaybackResolverTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioAutoPrefetchCoordinatorTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioPinnedDownloadCoordinatorTest' \
  --tests 'io.legado.app.help.readaloud.offline.NovelAudioRecoveryCoordinatorTest'
bash ./gradlew :app:testAppDebugUnitTest --no-configuration-cache --console=plain
```

预期：退出码为 0；4 个既有跳过项若仍存在必须原样记录，不得改写成全量无跳过。

- [ ] **Step 2: 运行 Android 定向套件**

确认 `adb devices -l` 中只有目标 `legado_test` AVD/设备，禁止清除用户数据，禁止安装正式包。
运行：

```sh
bash ./gradlew :app:assembleAppDebug :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioMigrationTest,io.legado.app.NovelAudioRecoveryDeviceTest,io.legado.app.NovelAudioServerCredentialsDeviceTest,io.legado.app.NovelAudioDownloadDeviceTest,io.legado.app.NovelAudioPlaybackDeviceTest,io.legado.app.NovelAudioServerEndToEndDeviceTest
```

另行执行四态主题 runner，不与默认 runner 混跑：

```sh
bash ./gradlew :app:connectedAppDebugAndroidTest \
  -PuseThemeDeviceTestRunner=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.NovelAudioThemeDeviceTest
```

记录实际执行数量、XML/HTML 路径、失败/跳过数量和设备网络状态。

- [ ] **Step 3: 执行飞行模式与进程恢复手工验收**

验收顺序：

1. 在线配置 bridge，准备当前章和后续三章；
2. 确认四个章节均有完整 READY manifest、文件大小、SHA-256 和 decoder 证据；
3. 打开飞行模式；
4. 连续播放当前章、下一章、再下一章；
5. 检查 logcat 和桥接日志，网络请求数为零；
6. 杀死 debug 进程；
7. 保持飞行模式重开应用；
8. 从本地 manifest 恢复章节和阅读位置；
9. 在缺少 READY 的章节尝试播放，确认只显示缺失错误；
10. 关闭飞行模式，确认 PINNED 缺段可以继续。

- [ ] **Step 4: 运行交付门禁**

```sh
ai_tests/venv/bin/python ai_tests/scripts/run_gates.py --stage commit
ai_tests/venv/bin/python ai_tests/scripts/run_gates.py --stage deliver
```

若门禁失败，记录真实失败项并修复；不得通过删除检查、恒 PASS、整文件豁免或
`SKIP_GATES=1` 把失败改成通过。涉及 Gson DTO 时额外执行：

```sh
ai_tests/venv/bin/python ai_tests/scripts/audit_gson_generic_signature.py \
  app/build/outputs/apk/app/debug/*.apk \
  app/build/outputs/apk/app/release/*.apk
```

涉及 UI 时额外执行主题 token、host refresh 和测试配对审计。

- [ ] **Step 5: 更新用户可感知更新日志和工程文档**

基于最终 `git diff` 更新 `app/src/main/assets/updateLog.md`，只写用户能感知的：

- AI 多角色朗读当前章与后续三章预缓存；
- 手动章节离线下载与恢复；
- 飞行模式连续播放；
- 普通朗读兼容性。

内部 coordinator、迁移、测试和重构不写进用户更新日志。同步：

- `docs/AI_AUDIOBOOK_PROGRESS.md`：逐项填写真实证据；
- `docs/AI_AUDIOBOOK_ANDROID_PLAN.md`：更新计划完成度和剩余边界；
- `docs/AI_AUDIOBOOK_ARCHITECTURE.md`：记录实际状态机和运行限制；
- `CURRENT_ARCHITECTURE.md`：补当前入口、Service、Room 和恢复关系；
- issues 记录：所有设备失败、未执行项和真实云额度用量。

- [ ] **Step 6: 构建并验证 debug APK**

过程构建：

```sh
bash ./gradlew :app:assembleAppDebug
```

验证：

```sh
APK="$(ls -t app/build/outputs/apk/app/debug/*.apk | head -n 1)"
adb install -r -d "$APK"
adb shell pm list packages | grep 'io.legado.miss.app.debug'
adb shell am start -n io.legado.miss.app.debug/io.legado.app.ui.main.MainActivity
shasum -a 256 "$APK"
```

检查：

- 包名必须为 `io.legado.miss.app.debug`；
- 只覆盖安装，不卸载、不清除数据；
- 启动焦点为 `MainActivity`；
- APK 内存在 `assets/cronet.json`；
- 交付记录包含版本、SHA-256、安装结果和设备启动结果；
- 不把 release 包或正式签名状态写成已完成。

- [ ] **Step 7: 完成最终提交**

提交前：

```sh
git diff --check
git status --short --untracked-files=all
```

确认只将本期实际修改和新增文件加入暂存区，然后：

```sh
git add \
  app/src/main/assets/updateLog.md \
  docs/AI_AUDIOBOOK_PROGRESS.md \
  docs/AI_AUDIOBOOK_ANDROID_PLAN.md \
  docs/AI_AUDIOBOOK_ARCHITECTURE.md \
  CURRENT_ARCHITECTURE.md \
  issues-found.md
git commit -m "feat(audiobook): 完成一期 Android 交付验收"
```

如果 `issues-found.md` 在当前仓库不存在，则使用仓库现有问题记录文件，不新建第二份并行索引。

---

## 计划自检

### 规范覆盖

- AUTO 当前章后最多三章：Task 4。
- PINNED 当前章、10/20 章、自定义范围、暂停/继续/取消/删除：Task 5、Task 7。
- 进程重启只恢复 PINNED、不恢复 AUTO 授权：Task 6。
- local-first READY 和离线零远程请求：Task 3、Task 6、Task 10。
- Room/artifact reconcile 和只补缺段：Task 1、Task 6。
- 请求 identity、generation、continuation 和迟到回调隔离：Task 4、Task 8。
- 统一云预算、并发和用完即停：Task 2、Task 9。
- NovelAudioServer 百炼真实 Android 联调：Task 9。
- 普通系统 TTS、HTTP TTS、媒体按键、后台、音频焦点、阅读进度：Task 8、Task 10。
- 设备测试、JVM、门禁、APK 包名/安装/启动/SHA-256/Cronet：Task 10。
- 文档和用户更新日志：Task 10。

### 关键类型一致性

- `NovelAudioStates.stateReason` 同时存在于 plan 和 task，迁移 111→112 一致；
- `NovelAudioCloudBudgetGate.run` 是分析/TTS 的唯一预算入口；
- `NovelAudioPreparationEnvironment` 为当前章、AUTO、PINNED 共用同一 runner；
- AUTO 使用 `AudioPrefetchLifecycle.Work`，PINNED 不调用 lifecycle；
- `NovelAudioLocalPlaybackResolver.Result` 只有 `Ready`、`NeedsOnlinePreparation`、
  `OfflineMissing` 三种结果；
- `NovelAudioRecoveryCoordinator` 只从 DAO 查询持久状态，不读取或重建 AUTO token。

### 无占位项

计划中的路径、状态、迁移版本、测试入口、命令、预期结果和提交边界均已明确；
不依赖未定义的后续步骤。
