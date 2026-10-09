# AI 多角色听书进度与验证记录

## 总体状态

### VoiceStudio 方案分支（2026-10-02）

已从 `feat/ai-audiobook` 创建 `feat/ai-audiobook-voicestudio`，目标是以
VoiceStudio 作为可替换的本地 TTS 宿主，优先在 macOS 验证，最终部署到 Windows
RTX 5090D v2。Android 继续使用 `NovelAudioServer v1`，保留章节人物、角色绑定、
Room 音频缓存、AUTO/PINNED、续播、严格离线和普通 TTS/HTTP TTS 兼容。

第一批服务端骨架已完成：通用协议校验、独立 SpeechProvider/DirectorProvider、
VoiceStudio HTTP Provider、opaque voice registry、音频边界、六端点网关、macOS/Linux
与 Windows 启动入口。VoiceStudio TTS 与章节分析已解耦；章节分析未配置时明确返回
`directorReady=false`，不会阻止 TTS 服务单独启动。

当前已完成离线契约验证、本机 ffmpeg 真实转码验证和本机真实 VoiceStudio 链路
验证（见下文），Android 真机和 Windows 部署仍未验收。新适配层目录为
`scripts/novel-audio-voicestudio/`。

本批补齐了 VoiceStudio profile 自动发现与 opaque ID 映射、首次注册表持久化、WAV
到 Android 兼容 Ogg/Opus 的受限转换、配置变更后的缓存 profile revision、空声库
失败关闭、loopback/LAN 显式开关及 HTTP 输入边界；本轮修复响应头大小写、
上游 429 状态映射和配置 repr 中 Director Token 隐藏，新增
`VOICESTUDIO_PROFILE_REVISION` 与远程 HTTPS 显式开关；本轮补齐请求上下文、
客户端断开/服务关闭取消、跨 transport 的绝对 deadline、上游 socket 中止、
Director 实际健康探测和发现失败时的 registry fail-closed。

2026-10-08 已在本机（Apple M2 16GB，MPS）接通真实 VoiceStudio 0.5.6
（源码 tag v0.5.6 headless 运行，默认引擎 OmniVoice，模型 3.27 GB 校验 SHA-256），
并通过适配层完成真实链路：`--check` 返回 `ttsReady=true`，发现 3 个中文设计
音色（旁白沉稳男声、青年男声、青年女声）加 1 个英文演示音色；preview、三角色
合成和 90 字长段落均返回 Ogg/Opus，`X-TTS-Profile` 一致。用户已于 2026-10-08
试听确认三种音色可接受。

人物分析接入百炼临时桥接（`127.0.0.1:8787`，qwen3.7-plus，用户确认免费额度保护后
授权）后，适配层健康检查 `directorReady=true`、`ttsReady=true`，满足 Android 连接
测试条件。用一段 5 句原创短文走完整链路：章节分析 1 次（6.3 秒）识别出林舟（男）
与苏晚（女）并给出逐句归属，按性别匹配到青年男声/青年女声，旁白用沉稳男声，
5 句合成均成功，拼成约 24 秒的整章试听文件。

真实联调暴露并已修复四处问题（离线测试 64 项）：

- 整请求截止时间原为固定 20 秒，90 字段落在 M2 上需约 31 秒，必定 504；新增
  `NOVEL_AUDIO_REQUEST_TIMEOUT`（默认 40 秒，须小于 Android 45 秒调用超时）。
- `/v1/audio/voices` 不带性别年龄，所有音色都是 unknown，角色自动选音无法区分
  男女；现从 `/profiles` 的设计标签补全，男女角色已能各自匹配到对应音色。
- VoiceStudio 未启动时 `--check/--serve` 打印 Python 堆栈；现输出一行提示并退出 1。
- 选音时“性别不符”和“年龄不符”同权，成年男角色被分到性别未知的英文演示音色；
  现性别优先于年龄，且分析方给出 `unknown` 时不再偏向未标注音色。

已知限制（VoiceStudio 侧，适配层无法修复）：客户端放弃请求后，VoiceStudio 的
`/v1/audio/speech` 不会中止生成；实测放弃一段长文本后，下一句短文本排队等了
约 35 秒。M2 上合成耗时约为音频时长的 1.7 倍，单段约 110 字以内才能在 40 秒内完成。

最近一次验证：VoiceStudio 64/64、百炼桥接 79/79、Mock 7/7，测试配对门禁
（含未跟踪文件）和 commit 静态门禁均 exit 0。

百炼桥接 `test_quota_reservation_atomic_across_instances` 的偶发失败已定位：
预算库首次创建时，正式路径先出现空文件、后建表，并发实例在建表前读到空库，
按损坏处理而拒绝预占。现改为在同目录临时文件建表后用硬链接原子发布，并新增
确定性复现用例。同一并发用例在旧实现下 200 次运行失败 28 次，修复后 0 次。
硬链接要求状态文件所在磁盘支持硬链接（APFS/NTFS/ext4 均支持）。

Phase 1 剩余事项：

- 2026-10-09 真机（24117RK2CC）已装本分支测试包 3.26.100823debug，经 USB 隧道
  （`adb reverse tcp:8788`）连接适配层，App 内「测试连接」显示分析与语音均就绪。
  点播放被手机端本地预算账本拦截，未发出任何网络请求：账本
  `no_backup/novel-audio-budget.json` 为百炼试用期设计（分析 20 次/24000 字、
  TTS 100 次/5000 字，换凭据不重置），当前分析已用 12 次/23316 字。该账本对所有
  服务一视同仁，本地 VoiceStudio 合成也会在累计 5000 字后被拦。
  已按用户决定修改：`/v1/health` 新增 `meteredOperations`，Android 只对服务端声明
  计费的操作扣账本（探测失败或旧服务未声明时仍全部计费）；适配层声明本地合成
  不计费、外部 Director 分析计费。人物分析仍走百炼，账本只剩约 680 字，测试书
  单章约 1950 字，分析仍会被拦。
- 2026-10-09 晚手机重试（设备账本已重置）连续暴露并修复四个问题，均已提交：
  1. 本地合成不计费后绕过了单路并发槽，与适配层单生成槽冲突返回 429
     （「家庭 AI 服务繁忙」）→ 不计费操作仍占同一并发槽。
  2. 适配层与百炼中转都没有访问日志，真机只看到笼统报错 → 两端加只含
     路由/状态/诊断码的访问日志（不含令牌、查询串、正文）。
  3. 百炼中转单次读取上限 10 秒，非流式整章分析首字节常超过 10 秒，
     被判 `cloud_timeout` → 改为以 40 秒总时限为界。
  4. 模型须原样抄写 Android 的 66 位哈希 unitId，抄错即整章 `analysis_unit_coverage`
     （Mac 冒烟用 u1..u5 未暴露）→ 中转发给模型前换成 u1/u2… 短别名并映射回原 ID；
     分析失败另按类别回传 `analysis_truncated`/`analysis_invalid_json`/
     `analysis_unit_coverage`/`analysis_unknown_speaker` 等诊断码。
     Windows 本地 Director 若也用 LLM，需要同样的别名映射。
  修复 4 之后尚未在真机重测：百炼中转本地分析额度只剩约 170 字（16 次/23828 字）。
  另见：短 UTF-8 txt 被识别成 GBK 显示乱码（Legado 原有编码探测，非本次改动），
  测试书 `dengta-short.txt`（521 字）需在阅读菜单「设置字符集」确认 UTF-8。
- 人物分析目前借用百炼临时桥接，需真实云额度；本地模型版 Director 待
  Windows 部署时替换。
- Android 完整播放验收与 Windows RTX 5090D v2 部署尚未完成。

截至 2026-09-30，NovelAudioServer 的 Android 通信层已实现并通过本地契约测试；
已包含六个 v1 接口、独立标准 TLS 客户端、鉴权、协议校验、有界超时/响应和取消。
开发 Mock 的行为测试通过。真实 Windows 服务尚未与 App 联通；2026-10-01 起
用户准备用自己的本地服务器联调，代码已同步到 GitHub（见下一节）。
百炼临时桥接已实现并通过离线测试，默认使用 `qwen3.7-plus` 和
`qwen3-tts-instruct-flash`；首次真实试听章节分析通过。后续已定位 TTS 音频地址白名单
遗漏，修复后生成一段真实音频并完整解码；2026-10-01 三角色真实试听已生成并在电脑播放，
用户确认听感可以。

当前仍未达到完整产品验收：正文来源、章节分析、人物/声音绑定、Room 计划、
音频下载和本地播放已有实现，服务设置与全局引擎选择入口已写入，正在验证。
Keystore、数据库迁移、坏音频重新排队、旧代际隔离和准备生命周期局部回归已通过对应测试。
截至 2026-10-01，一期剩余的 P0/P1 逻辑层均已落地并有测试：设备本地预算硬停止、
AUTO 后三章串行准备、当前章与 AUTO 的车道仲裁、local-first 闸门（离线不建云客户端）、
PINNED 10/20/自定义范围与手动下载入口、启动后续传未完成的固定下载、
后台播放位置持久化、跨章续播补救准备。全量 App JVM 1075 项与 Android 15
`legado_test` 上 `NovelAudioRecoveryDeviceTest` 13 项均为 0 失败。
但这些接线的**真实运行时行为一律未经设备验收**：飞行模式三章连播且零网络请求、
PINNED 入口点击链路、杀进程后续播、启动续传、普通系统 TTS / HTTP TTS 不回归
五项都卡在缺少 AI 听书服务凭据（本批未发起任何真实云请求）。
没有交付可用新 APK。

## 代码同步与本地服务器联调交接（2026-10-01）

### 用哪个分支

- `feat/ai-audiobook` 包含全部代码，是联调基准。本地 `main`（与 iCode `origin/main` 一致）
  停在 `0003fdd6`（2026-09-23 架构文档），是 `feat/ai-audiobook` 的祖先，
  缺少其后全部 34 个提交，即 AI 听书的全部实现、测试和本地桥接/Mock 脚本，不能用于联调。
- GitHub [`zhengwu163/yuedu-self`](https://github.com/zhengwu163/yuedu-self) 的
  `feat/ai-audiobook` 与 `main` 均指向同一提交，克隆任一分支得到的代码相同。
  iCode `origin/main` 未同步，仍停在 `0003fdd6`。

### 仓库里没有、需要在联调机器上自备的内容

| 内容 | 原因 | 怎么得到 |
|---|---|---|
| `novel-audio.local.env`（百炼 Key） | 凭据，Git 忽略 | `sh scripts/novel-audio-bridge/start.sh --init` 生成空模板后填写 |
| `novel-audio.local.state.db`、`novel-audio.local.connection.json` | 桥接随机 Token 与本地额度账本，Git 忽略 | 首次 `--check` 自动生成；账本不可删除重置 |
| `app/so/` | Cronet 五个 ABI 的原生库，体积大（jar 已在 `app/cronetlib/` 入库） | Gradle 构建期由 `app/download.gradle` 自动下载 |
| `ai_tests/venv/`、`ai_tests/testdata/audiobook/` | 本机 Python 环境与测试书 | 按 `ai_tests/README.md` 重建；测试书可由 `scripts/manual-test/make_test_books.py` 生成 |

### App 连接本地服务器

- 入口：朗读设置里的「AI 多角色听书服务」，填写根地址与 Access Token。
  根地址不含 `/v1`、query、fragment 和账号密码；App 自动追加 `/v1/...`。
- `http://` 地址必须在该页勾选「允许明文 HTTP」，否则保存被拒绝；明文下正文与令牌不加密，仅限可信局域网。
- 服务端须实现 [NovelAudioServer v1 协议](NOVEL_AUDIO_SERVER_API_V1.md) 的六个端点；
  百炼临时桥接就是这份协议的参考实现，见 [桥接说明](../scripts/novel-audio-bridge/README.md)。
- 桥接只监听 `127.0.0.1:8787`，手机通过 `adb reverse tcp:8787 tcp:8787` 访问
  `http://127.0.0.1:8787`。若改用局域网里的 Windows 服务器，App 直接填该机地址；
  不要把服务暴露到公网。

### 联调时优先验收的项目

即下一节「仍未闭环」五项：飞行模式三章连播且零网络请求、PINNED 入口点击链路、
杀进程后续播、启动续传未完成的固定下载、普通系统 TTS / HTTP TTS 不回归；
以及三角色声音的真实听感。设备本地预算账本为硬上限，达到后停止生成请求。

## 百炼三角色真实试听（2026-10-01）

- 用户确认 `qwen3.7-plus` 与 `qwen3-tts-instruct-flash` 均已开启「免费额度用完即停」后执行
  `start.sh --check`（exit 0）与 `start.sh --smoke --free-quota-confirmed`（exit 0）。
- 云调用：章节分析 1 次 + TTS 3 次，均在桥接本地预算内；未重试。
- 内置原创三句分别被分配给旁白、林舟、苏禾，三角色要求一次满足；匹配音色为旁白固定音色、
  成年男声、成年女声各一。
- 产物：三段单声道 48 kHz Ogg/Opus，时长约 2.88 / 3.36 / 4.96 秒，ffprobe 可完整解析，
  已在开发 Mac 上用 ffplay 依次播放。文件在 Git 忽略的 `novel-audio.local.smoke/` 下。
- 已知听感边界：分析粒度是整句，第 2、3 句的「林舟说」「苏禾笑着回答」由角色声音读出，
  而非旁白；是否需要句内拆分待用户试听后决定。
- 用户试听后确认「感觉还可以」，三角色声音效果主观验收通过；句内拆分暂不作为阻断。
  本结果不代表 Android 端到端或离线链路通过。

## P1 自动/手动准备链路与接线（2026-10-01）

本节覆盖 `64a427c6..ac5c1b26` 共 26 个 commit（`64a427c6` 本身的 P0 预算闸门见上一节）。
所有判定、调度、仲裁都有 JVM 覆盖，关键持久化行为有真实 Room 设备覆盖。
本批 Android 定向设备回归已完成：NovelAudio 相关回归 55 项全部通过；
四态主题专项测试 1 项全部通过。飞行模式连续播放、PINNED 点击、
杀进程续播等完整用户链路仍待真实验收。

### 2026-10-01 Android 定向设备验收

- 设备：`legado_test(AVD) - 15`。
- NovelAudio 定向回归任务实际执行 55 项，0 failures、0 errors、0 skipped，
  Gradle exit 0；覆盖 `NovelAudioMigrationTest`、
  `NovelAudioCatalogDeviceTest`、`NovelAudioServerCredentialsDeviceTest`、
  `NovelAudioDecoderDeviceTest`、`NovelAudioConfigDialogDeviceTest`、
  `NovelAudioSettingsUiTest`、`NovelAudioRecoveryDeviceTest`。
  XML 时间戳为 `2026-10-01T09:44:04`。
- 四态主题专项使用 `NovelAudioThemeTestRunner` 单独执行，
  实际执行 1 项，0 failures、0 errors、0 skipped，Gradle exit 0；
  测试方法为 `sameConfigFragmentRefreshesDefaultAccentPackageAndNightAndRestoresState`，
  XML 时间戳为 `2026-10-01T09:47:57`。
- 默认 runner 的 79 项基线任务中有 8 项失败，集中在 Rhino、
  通用 Compose、既有迁移基线和示例 ContentProvider；这些失败不属于本批
  NovelAudio 改动，不能替代上述定向范围证据。
- 本批 Android debug APK 过程构建成功，Python 本地模型服务回归 37/37 通过，
  commit gates exit 0。正式交付 APK 仍须遵循项目规定的 Windows
  `build-legado.bat` 流程。

### AUTO 后三章

- 纠正一个先前的误判：AUTO 窗口计算并非缺失，`AudioPrefetchSession.updatePosition()`
  早已算出 `currentIndex+1 .. min(currentIndex+3, count-1)`（含 Long 提升防溢出），
  并由 `BaseReadAloudService.prefetchAssembled()` 调用；缺的只是消费端。
- 新增 `NovelAudioAutoPrefetchScheduler` 串行推进状态机：失败只前进不重试，
  旧窗口回调与他书回调都不推进。`NovelAudioAutoPrefetchCoordinator` 为执行循环，
  `isAllowed`/`isLocallyReady`/`prepare` 三点注入，取消可传播、异常终止整窗。
- `NovelAudioChapterSnapshotLoader` 取后续章只读快照。预处理规则改为构造参数注入
  （`rules: () -> FrozenReadAloudPreprocessRules`），否则 JVM 下会因
  `ReadAloudPreprocessRuleConfig.current()` 触发 AppConfig 初始化而
  `ExceptionInInitializerError`。
- `NovelAudioPreparationLane` 做车道仲裁：`beginCurrent` 抢占 AUTO；`beginAuto` 在
  当前章在途、已有 AUTO 在途或目标就是刚播过的那章时拒绝。这一层是必需的——
  协调器只有一个请求槽、预算账本只允许一个生成请求在途，当前章与 AUTO 必须互斥。
- `NovelAudioFollowingChapterPreparer` 是 AUTO 侧单章入口，代次由快照散列派生
  （`snapshotHash.hashCode().toLong() and 0x3FFF_FFFFL`）。其 `finally` 里的
  `lane.finishAuto(...)` 是正确性关键：变异验证删掉该行后 8 项中 6 项失败。
- `NovelAudioChapterPreparer` 抽出当前章与 AUTO 共用的固定准备顺序
  （produce → 取执行令牌 → download）；`NovelAudioPreparationEnvironment` 抽出共享装配，
  并由调用方注入 persist（当前章走 run token fencing，后续章走车道）。
- `NovelAudioAutoPrefetchDriver` 是服务侧唯一接入点，在 READY 分支
  `prefetchAssembled(...)` 之后调用；`playStop`/`pauseReadAloud`/`onDestroy` 调 `revoke()`。

### local-first 与严格离线

- `NovelAudioLocalFirstPolicy` 给出三态 `PLAY_LOCAL` / `PREPARE_REMOTE` /
  `WAIT_FOR_NETWORK`，复用条件为「计划 READY + 全部 artifact 就绪 + 代次与期望一致」。
- `NovelAudioLocalFirstGate` 排在 `NovelAudioPreparationEnvironment.open()` **之前**，
  因此离线判定命中时根本不会构造云客户端。读取计划用
  `kotlin.runCatching { plan(...) }.getOrNull()`，读失败不会退化成「按本地播放」。
- 协调器新增 `PreparationResult.Local(generation)` 分支。

### PINNED 手动下载

- `NovelAudioPinnedRangePolicy`：`MAX_CHAPTERS = 10_000`，预设（当前章 / 后 10 / 后 20）
  被书末收窄，自定义范围超限直接 `TOO_MANY_CHAPTERS` 拒绝而不静默截断；
  手动 PINNED 范围不再受旧的 20 章上限限制。
- `NovelAudioPinnedDownloader` 不依赖播放授权，只有 `isCancelled()` 能中断它，
  以满足「PINNED 不依赖 AUTO lease」。`NovelAudioPinnedDownloadPresenter` 持有界面状态，
  `options()` 按真实章数裁剪，`start()` 入口重置取消标记。
- `NovelAudioPinnedDownloadLabels` 单独承载文案：章号对用户 +1，三种拒绝给可行动说明
  而不暴露枚举名。
- UI 入口按最小侵入方案落在 `ReadAloudConfigDialog`：新增
  `KEY_NOVEL_AUDIO_PINNED_DOWNLOAD` action 项（`visible = isNovelAudioRoute`），
  复用既有 `showComposeChoiceListDialog` + `toastOnUi`，未新增绘制组件。

### 重启恢复、播放位置与跨章续播

- `NovelAudioPinnedRecovery` 只接 `retention == PINNED` 且状态在
  QUEUED / RUNNING / PARTIAL / WAITING_NETWORK 的任务；`NovelAudioPinnedRecoveryStarter`
  用 `AtomicBoolean` 做一次性保护、离线跳过，并在 `App.kt` 的
  `AutoTask.refreshSchedule()` 之后启动。
- `NovelAudioProgressPersister` 做位置持久化节流（10 秒；换章/换书立即写；
  同章回退视为迟到回调不写；停止/暂停/销毁调 `flush()`）。服务侧由
  `publishSegmentProgress()` 驱动，校验 book/chapter 匹配后写 `ReadBook.durChapterPos`
  并 `saveRead(true)`。这修掉一个既存缺陷：原实现依赖 Activity 观察事件，
  后台播放时这条链是断开的，所以后台听完的位置根本没落盘。
- `NovelAudioContinuationPolicy` 修跨章续播静默卡死。根因查证：跨章进入时
  `readAloud(userInitiated = false, prefetchRequest = null)` 使 `shouldPrepare`
  必然为 false，于是 Blocked 分支既不准备也不报错，界面一直停在「准备中」。
  现在 Blocked 分支在「缺段或未就绪 + 有当前章工作」时触发
  `requestContinuationPreparation()`。

### 自己引入的两个缺陷（都违背「暂停/取消不自动重启」）

- PAUSED 一度被纳入自动恢复。核对本文件「下载恢复」一行的验收口径
  （「暂停/取消不自动重启」）后，先补失败用例再修。
- FAILED 一度被纳入自动恢复，**由新增的真实 Room 设备测试抓到**，设备 RED：
  `expected:<[100, 101, 102, 103]> but was:<[100, 101, 102, 103, 104]>`。
  纯逻辑测试测不出来——我构造 `PendingTask` 时就带着同一个错误假设，
  只有真实 Room 查询才会暴露。这是本批里设备测试相对 JVM 测试唯一不可替代的一次。

### 工程踩坑（已二次复现，需视为常规约束）

- **R8 裁剪 Kotlin 默认参数桥接**：AndroidTest 调生产方法必须显式传全部参数。
  本批第二次撞到（`create$default`，`NovelAudioSegmentIntent.create` 五个默认参数只传两个），
  上一轮是 `persist$default`。
- JUnit `TemporaryFolder` 加 `@JvmField` 会让规则失效（20 项全败，报
  `the temporary folder has not yet been created`）；去掉即可。
- suspend 测试最后一行是表达式会被 JUnit 判为 `Method should be void`，末尾补 `Unit`。

### 本批验证结果

- 全量 App JVM：`tests=1075 failures=0 errors=0 skipped=4`（4 项为既有 ignored）。
- Android 15 `legado_test` 上 `NovelAudioRecoveryDeviceTest`：
  `tests=13 failures=0 errors=0 skipped=0`，Gradle exit 0。新增三项真实 Room 用例：
  启动恢复只接固定且非用户中断的任务、local-first 闸门仅在全部 artifact 就绪时本地播放、
  固定一个已完整的 AUTO 章节只升 retention。
- 提交门禁 exit 0（生产文件测试配对、硬编码颜色、宿主刷新覆盖）。
- 新增 23 个 JVM 测试文件，合计 166 项用例；`NovelAudioRecoveryDeviceTest`
  从 10 项扩到 13 项。`app/src/main/assets/updateLog.md` 已按 2026/10/01
  追加 4 条新增 + 2 条修复。

### 仍未闭环（均需真机 + AI 听书服务凭据）

- 飞行模式连续播放至少三章且网络请求数为零
- PINNED 入口点击链路（弹窗、选项文案、下载实际发生、toast）
- 杀进程后从听到位置续播
- 启动续传未完成的固定下载
- 普通系统 TTS / HTTP TTS 不回归
- debug APK 构建与安装验收（须走 `build-legado.bat`，但该脚本硬编码 Windows 路径，
  在当前 macOS 环境不可直接执行）

本批未发起任何真实云请求，未消耗任何云额度，未交付新 APK。


- 扩展迁移/恢复回归任务 `2iscmk` 在 Android 15 `legado_test` 上实际
  `Starting 30 tests`、`Finished 30 tests`，Gradle `BUILD SUCCESSFUL`，
  exit 0。该命令覆盖 `NovelAudioMigrationTest` 与
  `NovelAudioRecoveryDeviceTest`；后台输出未生成独立 XML 摘要，因此当前记录
  以 instrumentation 启动/完成数量和 Gradle 结果为准，未额外推断 XML 统计。
- Android 15 `legado_test` 定向任务 `c9vjsi` 实际启动并完成 4 项，XML
  failures/errors/skipped 均为 0，exit 0。覆盖 READY/RUNNING 损坏复用、
  READY/READY 损坏复用、执行尝试号迁移默认值及并发领取。
- 完整迁移/恢复任务 `ayt9an` 实际完成 26 项，10 项因测试 APK 调用主包已裁剪的
  `saveNewPlan$default` / `invalidateReadyState$default` 失败，exit 1。
  R8 usage 清单还确认 `saveNewPlan` 本体被裁剪；设备测试改用生产入口
  `savePlanForExecution` 并显式传参，原有状态、代次及 retention 断言保留。
- ABI 修复后的 `76kddl` 实际启动并完成 26 项，25 通过、1 失败、0 errors/skipped，
  exit 1。失败为真实业务断言：播放器损坏失效错误地修改了同计划的额外 RUNNING
  `active-task`。下载执行修复与播放器报错共用批量 SQL，是此次范围扩大回归的根因。
- 已补下载执行仅修改所属 task、所属 task 尝试号不匹配时整笔事务回滚两条设备测试。
  修复前 `y0xyjt` 实际启动/完成 28 项，25 通过、3 失败、0 errors/skipped，
  exit 1；两条新增用例与原有 RUNNING 保护断言均取得业务 RED。
- 修复将播放器失效恢复为仅修改 READY 任务，保留历史任务 ID 兼容性；
  下载执行失效单独限定 taskId、planId、generation、executionAttempt，
  允许所属 READY/RUNNING 任务修复且匹配失败会回滚。
- 修复后 `wsiyo7` 实际启动并完成 28 项 Android 15 回归，XML
  `tests=28 failures=0 errors=0 skipped=0`，Gradle exit 0。该结果覆盖迁移、
  READY 损坏复用、旧代次隔离、活动任务保护、所属 task 修复和 attempt 回滚。
  仍需全量 JVM、最终门禁及取消生命周期闭环，不能据此交付一期 APK。
- 复现任务 `wns8ab` 实际启动并完成 29 项，XML `tests=29 failures=1 errors=0 skipped=0`，
  确认旧执行在计划已被播放器降级后仍会把完整 artifact 误标为 FAILED。
  修复将计划 READY ownership CAS 前移，CAS 失败直接无写返回；随后
  `04dz98` 实际启动并完成 29 项，XML `tests=29 failures=0 errors=0 skipped=0`，
  Gradle exit 0。该修复只闭合旧执行失效事务边界，不能替代全链路验收。
- 修复后的全量 App JVM 任务 `ato2cz` 为 878 项，0 failures、4 个既有 ignored，
  Gradle exit 0。提交门禁任务 `t60330` 为 exit 0：62 个生产文件测试配对、
  硬编码颜色规则及 4 个宿主刷新覆盖均通过；该门禁明确未覆盖 selection colors。
- `NovelAudioPreparationLifecycle` 已接入准备协调器，覆盖每次 start 的唯一 run token、
  cancel/replacement 内存失效、串行 IO mutation、persist 后 ownership 复核、
  异步 release、finally cleanup 及 stale callback 隔离。新增 8 项 JVM 用例，
  定向 Gradle 任务 exit 0；该结果仍需真实 Room/下载链路集成回归。
- 追加全量 App JVM 回归实际完成 879 项，0 failures、4 个既有 ignored，
  Gradle `BUILD SUCCESSFUL`，exit 0；该结果不替代 Android 设备和 APK 交付验收。
- 针对 stale repair 的独立只读审查无 actionable findings：确认旧执行在计划
  已降级后是无写返回，generation/executionAttempt、精确 taskId/planId
  及事务回滚边界均有设备断言覆盖。该审查不替代完整产品验收。
- 新增 `finalArtifactDoesNotPromotePlanBeforeDownloadTaskFinishes` 先在
  `7f9n8n` 实际 23 项设备回归中取得业务 RED：最后 artifact 提交把 plan
  错误提升为 READY，而 download task 仍为 RUNNING。修复移除
  `saveArtifactAndUpdatePlan()` 的提前 READY 转换，统一由 `finishExecution()`
  在 task、全部 artifact 和最终文件校验完成后提交；`yi2uws` 随后实际 23/23
  通过，XML `tests=23 failures=0 errors=0 skipped=0`，Gradle exit 0。
- 生命周期 helper 的 JVM 回归已通过，但真实 Room/下载链路的取消与持久化交错、
  Android 预算闸门、AUTO/PINNED 调度和 local-first 离线链路仍未闭环；
  本批未新增真实云请求，未交付新 APK。下一步继续做集成回归与 P0 门禁。

## P0 生命周期集成回归与预算闸门（2026-09-30）

- 新增三项真实 Room/Repository 生命周期集成设备测试：persist 后只释放自己的
  execution、新 run 的 persist 等待旧 cleanup 完成、READY/PINNED 不因普通
  cleanup 失败降级。首次设备任务 `q3q2kw` 实际启动并完成 10 项，3 项因
  `NoSuchMethodError: persist$default` 失败，Gradle exit 1。根因是 R8 裁剪
  Kotlin 默认参数桥接方法，测试 APK 仍依赖默认参数 ABI；修复为显式传入
  `retention` 与 `isAutoAllowed`。
- 修复后的定向设备任务 `u7ibxt` 实际启动并完成 10 项，XML
  `tests=10 failures=0 errors=0 skipped=0`，Gradle `BUILD SUCCESSFUL`，exit 0。
  该 XML 已包含三项新增生命周期用例名。
- 新增 `NovelAudioBudgetLedger`：独立于凭据的 `noBackupFilesDir` 账本，
  schemaVersion=2，分析/TTS 各自请求数与 UTF-16 字符上限之外再加组合总上限
  （默认 120 次 / 29000 字符）；预占在真实 HTTP 之前原子落盘，失败、超时、
  取消和重试都不退款；并发为 1，拿不到许可立即 `CONCURRENCY_LIMIT`。
- 账本 fail-closed 面：损坏 JSON、重复键、未知键、缺字段、错类型、尾随内容、
  负值、超上限、超大文件、符号链接、初始化后账本被删除、以及迟滞的持久
  `inFlight` 标记全部抛 `LOCAL_BUDGET_UNAVAILABLE`，不会自动归零。
  云免费额度与桥接本地额度各有持久熔断位，落盘失败仍保留进程内熔断。
- 预算闸门下沉到 `NovelAudioServerClient` 的 `analyze`、`synthesize`、
  `preview`，因此下载器的三次重试每次物理发送都会重新预占，无法绕过计数；
  `health`、`voices`、`match` 不计费。TTS 请求数按 Unicode code point 每 600
  拆一次（与桥接 `len(text)` 一致），字符额度按 UTF-16 `length`。
- 修复 429 误分类：网络拦截器此前读不到桥接错误码，导致 `free_quota_only` 与
  `local_trial_limit` 都退化为可重试的 `RATE_LIMIT`。临时探针实测拦截器位于
  OkHttp 透明 gzip 解码之下，只能读到压缩字节；改为显式
  `Accept-Encoding: identity` 并只读有界前缀解析固定 `error.code`，
  普通 429 仍为 `RATE_LIMIT`。探针文件已删除。
- 定向 JVM 任务 `eau81k`：`NovelAudioBudgetLedgerTest` XML
  `tests=20 failures=0 errors=0`，`NovelAudioServerClientTest` XML
  `tests=35 failures=0 errors=0`，Gradle exit 0。
- 全量 App JVM 任务 `5zm1n6` 汇总 106 份 XML 共 `tests=909 failures=0
  errors=0 skipped=4`（4 项为既有 ignored），Gradle `BUILD SUCCESSFUL`。
- 提交门禁 exit 0：64 个生产文件测试配对、硬编码颜色规则、4 个宿主刷新覆盖
  均通过；该门禁仍未覆盖 selection colors。
- 仍未闭环：AUTO 三章与 PINNED 10/20/自定义范围入口、local-first 严格离线、
  跨章连续播放、杀进程恢复、飞行模式三章连播、普通 TTS/HTTP TTS 设备回归与
  debug APK 交付。本批未发起真实云请求。


## Android 修复与入口验证（2026-09-29）

- Android 15 `legado_test` 实际执行 Room 迁移 8 项、恢复与隔离 4 项、
  Keystore 2 项，共 14 项全部通过。升级保留旧书、进度、人物与旧缓存；
  音频损坏同步失效同代已完成任务，可重新排队且保留 PINNED。
- 主包 Debug R8 曾裁掉测试需要的 Room/SQLite 调用；定向保留后真实业务断言已执行，
  不再把 AndroidTest 编译成功当作设备运行成功。
- 新增计划身份用例先复现服务器/语速/旁白绑定冲突，加入冻结片段等身份后通过。
  随后全量 App JVM：848 项，844 通过、4 既有跳过、0 失败，命令 exit 0。
- 服务设置新增 8 项 JVM、播放目录新增 2 项 JVM，定向均通过；
  随后的全量 App JVM 共 858 项，854 通过、4 既有跳过、0 失败。
- 配置批次先取得 14 项中 3 项业务 RED：保存/清除提交后重建时漏配置通知 2 项、
  引擎摘要错误 1 项。通知移到同步提交后，摘要补 NovelAudio 分支；
  测试清理增强旧协程等待及异常后状态恢复。
  2026-09-29 12:38 独立重编译后 Content 4 项、Context 目录 1 项、
  真实 Fragment 9 项共 14 项全部通过，无失败或跳过，命令 exit 0。
  同批全量 JVM 858 项：854 通过、4 既有跳过、0 失败。
- 主题门禁已补基线、暂存/未暂存/未跟踪文件发现及 Git 失败阻断；
  主线程独立复跑 42 项专项自检通过，runner 6 项自检通过（包括主题依赖被忽略的 RED/GREEN）。
  当前工作树门禁 exit 1，命中 `ReadAloudPlayerPanel` 与 `ReadBookActivity` 的既有颜色常量，
  未放宽规则或加入整文件豁免。宿主/测试配对检查已补同一基线、净差异、NUL 文件名、
  非忽略未跟踪文件与 Git 失败阻断；当前工作树两门禁均 exit 1，仍需核对实际行为覆盖与名称配对。
- 桥接 78 项、Mock 7 项离线复验均 exit 0，无 ResourceWarning。Gson 源码保留标记
  静态检查 exit 0，此结果不等于 APK 泛型签名审计。
- 实际准备协调器请求隔离的 3 项 JVM 用例先取得 2 项失败：同章新旧请求结构相等，
  导致旧请求仍有效且能清掉新请求。改为引用身份后定向 3 项全部通过，命令 exit 0。
  与上述 858 项不同批次；回调授权、跨章与控制链仍需单独验证。
- 2026-09-29 12:49：引用身份修复后的全量 JVM 861 项，857 通过、4 既有跳过、0 失败；
  同批四态主题 AndroidTest 编译通过，尚未执行设备业务断言，组合命令 exit 0。
- 宿主/测试配对文件发现自检主线程独立复跑 64 项通过；
  主题 42 项及 runner 6 项再次通过。实际 deliver runner exit 1：
  配对、硬编码颜色、宿主覆盖三项仍阻断，Gson 源码标记检查通过。
- 坏目标修复新增 `NovelAudioArtifactRepairTest` 4 项，先全部失败，再实现新文件校验后
  原子替换；解码失败、校验后取消与提交失败保留旧目标并清理临时文件。
  12:59 定向 13 项（修复 4、存储 9）全部通过，exit 0。
  这些用例使用注入 decoder，仅证明文件发布行为；Android 完整解码与全量回归另验。
- UI 开工卡已记录于 `AI_AUDIOBOOK_ANDROID_PLAN.md`；复用既有弹框和主题 token，
  更换地址清空旧令牌/HTTP许可，草稿 health 不写入配置，不上传正文或发起 TTS。
- 提交/交付门禁仍有未通过项；四态界面和完整播放路径尚未验收。未提交推送，
  本批未发起真实云模型调用。

## 剩余实施前置核查（2026-09-28）

本轮没有新增产品代码；只读核查接入位置、原规范恢复来源并重新执行现有本地测试。
完整功能仍未实现，不能把本轮回归结果算作新管线的产品验收。

- 最终排版前正文入口已定位：`ReadBook` 中 `ContentProcessor.getContent` →
  `ParagraphRuleProcessor.process` 之后、`ChapterProvider.getTextChapterAsync` 之前。
  `BookContent.sourceIndexes` 仅是段落映射，不是字符级坐标；读取页面的
  `getNeedReadAloud()` 不能作为新正文快照源。
- 人物可复用本地 `BookCharacter` ID，但新别名、声音绑定及音频持久化仍未接入。
  无作者作品必须按已确认方案采用物理书籍回退，不能直接沿用旧 `BookIdentity` 的同名合并。
- 原规范并非全部无法找回：本地提交
  `2726d50fe8e3ac4716d37c7188f30c029e6ec843` 保留 24 份
  `docs/project-rules/` 旧规范及 `ai_tests/docs/fixed_test_workflow.md`、
  测试入口和库。已通过 `git show` 读取开工检查、数据库安全及测试 SOP 原文；
  本轮没有将 9 月 14 日历史文件写回为现行规范。
- 统一 runner/registry、测试配对、Gson 双包审计、主题取色/宿主刷新审计，以及主题铁律/
  流程卡点总纲，在当前树与本地所有 refs/reflog 可达历史均未找到。
  `c849386` 仅包含 AGENTS、忽略规则和 Trae Hook 变更，不包含这些原文件。
  随后通过 SSH 只读查询 iCode 远端：`git ls-remote --heads origin` exit 0，
  仅有 `main=0003fdd6c7b7f2f51479bc5c5847b0ab225b4e98`；该提交已在本地历史中，
  精确查询其目录树也没有上述关键门禁。未查询其他机器或备份。
- UI 的 K1 开工卡缺少现行主题规范，提交与交付也缺强制检查。
  需要原工作区/备份提供原件，或明确批准重建检查契约后实施；
  当前未绕过门禁、未重写项目规则、未提交推送。
- 本机已有 `legado_test` AVD 和 `scripts/manual-test/`；核查时无连接设备。
  不能把“设备未运行”写成“没有模拟器”。正式自动 E2E 仍缺项目 venv、
  测试入口/复用库及 Windows/MEmu 路径适配。当前未执行设备验收。
- 交付仍受 `build-legado.bat` 的 Windows 执行环境约束；
  发布器使用 `cmd /c` 和 Windows 工具名。签名未读取，状态待验证。
  Mac JVM/编译通过不等于现行 APK 交付链通过。

本轮复验：

- `:app:testAppDebugUnitTest --rerun --no-configuration-cache --console=plain`：
  exit 0，1 分 31 秒；HTML 报告 787 项、0 失败、4 跳过，即 783 项通过。
- `python3 -W error::ResourceWarning -m unittest discover`：
  桥接 78 项、Mock 7 项全部通过，组合命令 exit 0。
- 本轮没有新增/修改测试文件，执行的是现有回归；强制配对、Gson 和统一门禁没有运行，
  因而没有这些门禁的通过退出码。未新增云请求，短句 TTS 授权仍剩 1 次。

## 正文快照与无损切分验证（2026-09-28）

- 新增 `help/readaloud/analysis/TextUnitParser.kt` 与对应 `TextUnitParserTest.kt`。
  输入段落复制冻结，校验 UTF-16、源坐标与跨段连续性；固定 LF 拼接，保留空白和空段。
  每个单元可以从源范围回拼，全部单元顺序拼接等于完整快照。
- 单元最长 1200 UTF-16，不截开 surrogate pair 或 CRLF；内容 hash 和单元 ID 使用 SHA-256。
  ID 区分相同内容的不同位置，不依赖 Java `String.hashCode()`。
- 边界测试先复现“段尾 CR + 段间 LF”使切分抛异常，修复为先协调角色边界，再按上限切分。
  独立审查也指出该问题。新增对象/集合诊断字符串测试先失败，再覆写为不含正文的长度摘要。
  最终独立只读复核确认这两项修复闭环，当前范围内未发现残留重要问题。
- 定向 17 项全部通过，包含固定种子的 200 组混合文本，以及空段快照所有合法 UTF-16 区间回拼。
  全量 `:app:testAppDebugUnitTest --no-configuration-cache --console=plain` exit 0：
  787 项，783 通过、4 既有跳过、0 失败；解析器 XML 为 17 项、0 failures/errors/skipped。
- 桥接 78 项与原 Mock 7 项离线复验均 exit 0，无 ResourceWarning。
  本轮未读取 Key、未新增云请求；短句 TTS 追加授权仍剩 1 次。
- 日志检查：`^import android\.util\.Log$|AppLog\.|println\(` 在新 `analysis/` 源码中 0 命中；
  `git diff --check` exit 0（仅已跟踪差异）。没有新增用户入口，因此不追加用户更新日志。
- 仍缺既定 `run_gates.py`、测试配对及双包 Gson 审计脚本，未运行或伪报门禁通过，
  未提交/推送、未交付 APK。本地测试不能替代设备及真实服务验收。
- 本批没有建立分析缓存。当前解析器仍读取 AppConfig 的预处理规则；有效规则冻结、配置签名、
  wire `analysisVersion="1"` 与本地算法版本的分离，必须在请求/缓存接线时处理。

## 模型与 Key 兼容验证（2026-09-24）

- 按用户指定更新文本模型为 `qwen3.7-plus`，采用北京 OpenAI 兼容 Chat Completions；
  关闭思考/流式、JSON 模式及 4096 Token 上限已由离线请求断言验证。
- 首次试听采用用户允许的 `qwen3-tts-instruct-flash`；VD 模型需额外创建专属音色，
  目前不自动切换或创建云端音色资产。
- 修复本地 Key 字符校验过严的问题，按 Bearer 字符规则允许点号等字符，
  保持原值、不打印凭据，继续拒绝空白/换行/非法字符及超长输入。
- 修改 `test_bridge.py`（17 项）、`test_cloud.py`（9 项）和
  `test_runtime.py`（13 项）；加上 `test_boundaries.py`（22 项），
  共 61 项通过，unittest exit 0，无 ResourceWarning；原 Mock 7 项通过。
  正常 Key 字符、默认模型及新响应结构均在实现前复现失败。
- 根配置 `--check` exit 0：Key 已配置，音频转换能力通过，连接快照已生成；
  不读取或显示 Key 原文到会话，不调用云模型，不代表云端鉴权成功。
- 用户随后确认两个所选模型的有效免费额度与停费开关后，已执行一次原创三角色真实试听；
  Android 构建/设备验收、提交推送和 APK 交付仍未执行。

## 百炼单段音频验证（2026-09-24）

- 用户同意一次最小单段请求，结果为 `tts_unsafe_audio_url`；未进入下载与转码。
- 随后授权最多 3 次额外原创短句 TTS、合计不超过 100 字，不分析、不换模型、不自动重试。
  已使用其中 2 次，共 30 个正文字符，剩余 1 次尚未使用。
- 第一次仅输出脱敏地址结构，确认固定北京 HTTPS 推理端点返回了精确结果主机
  `dashscope-a717.oss-cn-beijing.aliyuncs.com`；原白名单遗漏该主机。
  仅补入该主机，HTTP 返回地址在下载前强制升级 HTTPS，不转发 Key、不跟随重定向。
- 第二次使用新增 `--smoke-tts --free-quota-confirmed`，exit 0；
  私有目录 `novel-audio.local.smoke/20260924-163907-898040/01.ogg` 生成成功。
  ffprobe 确认 Opus、单声道、2.641542 秒、15444 字节；ffmpeg 完整解码 exit 0。
- 独立审查发现并修复 HTTP 截断/错误体读取异常、越界 WAV 子块 RuntimeError，
  以及顶层 FreeTierOnly 与 `error:null` 同时出现时的熔断漏判。
  新增测试先复现，再修复；独立复核确认三项闭环后，继续补齐 urllib 包装超时的分类。
  API 与下载两个阶段均先复现失败；最新桥接 78 项及 Mock 7 项通过，exit 0，无 ResourceWarning。
  用例分布：bridge 19、boundaries 22、cloud 19、runtime 18。本轮复验未新增云调用。
- 以上只验证单段真实音频链路；未验收三角色听感、跨章同声或 Android 播放。
  未追加文本分析，未变更模型/预算，也未提交、推送或交付 APK。

## 百炼首次真实试听失败与诊断补强（2026-09-24，历史记录）

- 试听只发送程序内原创三角色短文；配置读取、ffmpeg 检查和章节分析通过。
- 第一段 TTS 生成失败，命令 exit 2，返回 `invalid_cloud_response`；没有生成可见音频，
  没有重试、换模型或继续请求后两段。
- 旧实现将 TTS JSON、`output.audio.url`、结果音频下载、WAV 校验和 ffmpeg
  转码共用一个错误码，所以这次结果不能证明具体根因。
- 已按阶段增加固定脱敏错误码：`tts_invalid_json`、`tts_invalid_response`、
  `tts_missing_audio_url`、`tts_unsafe_audio_url`、`tts_download_failed`、
  `tts_invalid_wav`、`tts_conversion_failed`。错误码不含 Key、正文、签名 URL
  或供应商原始错误；音频下载端的 403/429 不再触发模型鉴权或免费额度熔断。
- 官方接口文档核对显示非流式响应确实使用 `output.audio.url`，并返回百炼结果域名的
  WAV URL；在没有新的真实请求前，不能据此确定本次账号响应或媒体处理的具体阶段。
- 先运行新增诊断测试，复现错误分类缺失及音频存储 403/429 的错误归因，再补实现；
  `test_bridge.py` 18 项、`test_cloud.py` 13 项、`test_runtime.py` 16 项，
  加上 `test_boundaries.py` 22 项，共 69 项通过，exit 0，无 ResourceWarning；
  原 Mock 7 项通过，exit 0。
- 本轮未追加真实模型请求，不读取真实 Key，不调整预算或模型；没有 Android 改动、
  设备验收或 APK 交付。统一 runner 与测试配对门禁仍缺失，未绕过或声称提交门禁通过。

## 百炼桥接首次回归验证（2026-09-24，历史记录）

本批新增 `scripts/novel-audio-bridge/`，在开发电脑提供同一套六端点，
不改 Android UI、Room 或现有播放行为。云 Key 不进入 APK，
本地配置、随机连接 Token、累计预算状态和试听音频均被 Git 忽略。
下表保留首次离线回归当时的结果；后续真实调用见上方最新记录。

| 检查 | 结果 | 证据 |
|---|---|---|
| 配置/预算/声库/六端点 | 15 项通过 | `scripts/novel-audio-bridge/test_bridge.py` |
| 严格协议与费用边界 | 22 项通过 | `scripts/novel-audio-bridge/test_boundaries.py`；含多实例预算并发、缺表/缺行/截空文件拒绝与持久熔断 |
| 百炼适配与音频转换 | 9 项通过 | `scripts/novel-audio-bridge/test_cloud.py`；模拟云响应，真实 ffmpeg 编码/解码 |
| HTTP/CLI/工作进程 | 13 项通过 | `scripts/novel-audio-bridge/test_runtime.py`；含服务停止回收进程组 |
| 桥接完整离线测试 | 59 项，0 失败/错误/跳过 | 下列 unittest 命令 exit 0；无资源未关闭警告 |
| 停止行为独立复核 | 未发现残留重要问题 | 独立重跑 59 项；另验证创建/关闭竞态、重复关闭和关闭后拒绝；禁用清理的内存对照再次复现原缺陷 |
| 原开发 Mock | 7 项通过 | `scripts/novel-audio-mock/test_server.py`；unittest exit 0 |
| Android 全量 JVM 回归 | 770 项：766 通过、4 既有跳过、0 失败 | `:app:testAppDebugUnitTest --rerun --no-configuration-cache` exit 0；HTML/XML 报告见 `app/build/` |
| 配置隔离与启动检查 | 通过 | `git check-ignore` 覆盖 env、状态 DB/journal、连接快照/暂存与试听目录；`git ls-files` 对这些路径 0 命中；`sh -n` exit 0 |
| 真实云试听 / Android E2E | 当时未执行 | 当时尚未确认两个固定模型的有效免费额度及停费保护；App 接线仍未完成 |
| 提交与 APK 交付 | 未执行 | 统一 runner、测试配对与双包审计脚本仍缺失，不绕过 |

```sh
python3 -W error::ResourceWarning -m unittest discover -s scripts/novel-audio-bridge -p 'test_*.py' -v
python3 -m unittest discover -s scripts/novel-audio-mock -p 'test_*.py' -v
```

停止服务回归先复现了 Ctrl+C 后 worker 遗留，再验证修复后 HTTP 在途 worker
和模拟 ffmpeg 子进程均退出。关闭与新建共用生命周期锁，停止后不再派发新工作进程。
这不承诺撤销供应商已经收到的请求或退还其计费用量。
本次停止验收限 macOS/POSIX 的 Ctrl+C 有序退出，不覆盖 Windows 进程树、
SIGTERM/SIGKILL 或宿主崩溃。独立复核的额外竞态/反向对照为临时验证，
未计入仓库的 59 项固定用例。

费用保护包括本地累计请求/字符上限、单请求并发、联网前持久预占、不重试/换模型，
以及收到 `AllocationQuota.FreeTierOnly` 后持久熔断。状态损坏直接拒绝，不补零重置。
分析硬期限 40 秒、合成含下载/转码 25 秒；JSON/音频下载有上限并禁重定向。
这些本地保护不能代替供应商控制台的“免费额度用完即停”。

使用步骤见 [百炼桥接说明](../scripts/novel-audio-bridge/README.md)。
先填根目录 `novel-audio.local.env`，再做不联网的 `--check`；
真实试听只发送程序内的原创短文；后续单段验证及受限调用范围见上方最新记录。
本批属于开发工具和内部工程变化，不新增 App 用户更新日志条目。

## Android 通信与凭据验证（2026-09-23，历史记录）

| 检查 | 结果 | 证据 |
|---|---|---|
| 客户端契约 | 25 项，0 失败/错误/跳过 | `NovelAudioServerClientTest.kt`；包含换服务器后的 URL/Token 快照隔离 |
| 配置加密/失败策略 | 15 项通过 | `NovelAudioServerConfigStoreTest.kt`；真实 JVM AES-GCM，测试密钥代替 AndroidKeyStore |
| 原子文件与备份路径 | 7 项通过 | `NovelAudioAndroidConfigStoreTest.kt`；临时目录真实文件测试及工厂路径源码断言 |
| 授权与朗读装配回归 | 105 项，0 失败/错误/跳过 | `help/readaloud/offline/` 五个测试类；与通信/配置 47 项合计 152 项 |
| 开发 Mock | 7 项通过 | `scripts/novel-audio-mock/test_server.py`；unittest exit 0 |
| 全量 App JVM 单测 | 770 项：766 通过、4 既有跳过、0 失败 | `:app:testAppDebugUnitTest` exit 0；跳过项均在 `RhinoClassShutterTest` |
| 凭据设备测试编译 | 通过，未执行 | `:app:compileAppDebugAndroidTestKotlin` exit 0；新增隔离 alias/file 的 2 项设备测试 |
| 差异空白检查 | 通过 | `git diff --check` exit 0（仅已跟踪差异） |
| 临时日志检查 | 新客户端 0 命中 | `^import android\.util\.Log$|AppLog\.|println\(`，范围 `help/readaloud/server/` |
| 真实服务成功联调 | 暂缓，未执行 | Windows NovelAudioServer 未部署 |
| 设备 E2E / 双包 R8 审计 | 未执行 | 缺失既定门禁脚本且本批未交付双包 |
| 提交与远端同步 | 本批未提交/推送 | 提交门禁恢复前不绕过检查 |

修复前新增 6 项回归均失败；修复后全部通过，覆盖：

- `503 + Retry-After: 0` 不重复发送 GET/合成请求，超大 Retry-After 不逃逸为线程异常。
- 请求排队计入总超时，取消请求不会转为配置错误，调用方自己的 deadline 保留取消语义。
- 非法 UTF-8 与嵌套重复 JSON 键被拒绝，不允许人物/音色 ID 被静默替换或覆盖。

其余用例包含身份覆盖、版本/类型错误、401/403/429/500、重定向、
请求和响应超限、固定长度与分块音频、空音频、媒体类型及合成 profile。
音频夹具仅验证传输，不验证真实语音质量、解码与完整章可离线状态。
客户端尚未接入用户操作入口，因此本批通信层和测试变化不新增用户更新日志条目；
既有朗读定位、媒体按键等可感知修复条目保留。
取消或提前拒绝响应时，NanoHTTPD 可能输出测试端 socket closed 日志；
以 JUnit failure/error 和 Gradle 退出码判断测试，不将该日志当作设备运行结果。

配置安全底座：

- 地址与 Token 一起加密保存，API 不提供单独改地址/令牌；客户端持有不可变配对，
  更换配置不会让旧客户端读取新令牌。
- 使用独立 AndroidKeyStore alias、AES-GCM 随机 IV 与版本 AAD；
  解密不补建丢失密钥，错误不带原始异常/地址/Token。
- 密文在 `noBackupFilesDir`，不写默认偏好、Room 或角色 JSON；
  现有手工备份只导出列举的配置/数据，不复制该目录。
- 同目录暂存、文件 fsync、检查 rename 返回值，以 rename 为唯一提交点；
  损坏/孤立暂存显式失败。仅承诺同进程互斥与进程中断不发布半文件，
  不承诺跨进程并发或突然断电零丢失。
- 核心配置测试先出现 14/15 失败后修复；文件层新增两个边界先 2/7 失败后修复。
  独立复查未发现残留重要问题。设备备份行为和 Keystore 硬件实现仍待验收。

2026-09-23 当时仅核查了官方免费/试用资料，未实现云服务适配器；
2026-09-24 已完成百炼桥接本地实现，见上方验证记录及
[云模型联调可行性](AI_AUDIOBOOK_CLOUD_TRIAL.md)。单段真实音频验证见最新记录。
整体产品打通仍需设置、Registry、AudioSegment、播放器及缓存接线，不能把免费 API 可用性当成交付。

## 初始审计证据（历史记录）

下表记录首次环境审计，不代表当前阻断。之后用户已完成 SDK 许可，
当前 JDK/SDK 可以完成本地编译和 JVM 单测。

| 检查 | 结果 | 证据 |
|---|---|---|
| 上游基线 | 已导入完整历史 | `1ff5651d10da961b66db98d39f1b5e82b6341b4e` |
| iCode 首次同步 | 成功，main 跟踪 origin/main | 实际 SSH push exit 0 |
| GitHub 写保护 | 已配置 | push URL 为 `disabled://github-read-only` |
| 工作区基线 | 业务源码未修改 | 文档批次开始前 `git status --short` 为空 |
| JDK | 可运行 Java/Javac 21 | Homebrew JDK 21 实际版本输出 |
| Gradle wrapper | 通过 sh 可启动 | `sh ./gradlew --version` exit 0；不代表构建通过 |
| Android 命令行工具 | 已安装 sdkmanager | 实际命令定位成功 |
| Android 设备 | 未连接 | `adb devices -l` 无设备 |
| SDK 平台及 Build Tools | 未安装，许可未接受 | sdkmanager 明确跳过所请求的三个包；exit 0 不代表安装成功 |
| 基线启动首次尝试 | 未启动，exit 126 | wrapper 的 Git 模式为 100644，直接执行 Permission denied |
| 基线依赖/单测/编译重试 | 失败，exit 1，用时 2m46s | 任务图解析要求 Build Tools 35.0.0 和 Platform 36，SDK 许可未接受 |
| 业务功能测试 | 未执行 | 尚无新增业务实现 |
| 文档审阅 | 已完成，补充两项边界 | 离线恢复禁网；门禁恢复时精确放行 Git 忽略规则 |
| 文档/忽略检查 | 初检通过 | `git diff --check` exit 0；仅目标两份 docs 文件放行 |

## 按新方案分阶段状态

| 阶段 | 状态 | 退出标准 |
|---|---|---|
| 通信契约/Mock | 本地验证通过 | 六端点请求、身份和异常边界有实际测试 |
| 百炼临时桥接 | 78 项离线测试通过；单段真实音频生成和解码通过 | 同一六端点、Key 隔离、预算保护、原创三角色真实试听 |
| 连接配置 | 安全底座已实现，页面/设备验收未完成 | 朗读设置入口、Keystore Token、health/voices 测试 |
| 本地正文/分析 | 快照与切分 17 项通过；正文入口/分批分析未接线 | 统一快照、UTF-16 无损切分、分块分析、本地校验 |
| 人物/声音绑定 | 未完成 | 稳定 ID/aliases、作品隔离、旁白独立、稳定 voiceAssetId |
| 一章端到端 | 未完成 | 实际 Service 消费同一 AudioSegment 计划 |
| 自动/手动离线 | 授权层已实现，下载未接线 | 主动播放固定后三章、范围下载、manifest、恢复禁网 |
| 人物声音 UI | 未完成 | 简单收听、试听换声、别名高级入口、四态主题 |
| 回归交付 | JVM 已有验证，设备和门禁待完成 | 单测、设备、门禁、正式签名/覆盖安装 |

## 已确认产品决策

- 同名、同作者的逻辑作品共享人物与音色；物理章节和正文 hash 仍严格隔离。
- 自动固定准备当前主动播放书籍的后续最多 3 个完整章节，不提供数量设置。
- 浏览书架或文字阅读不触发；暂停、停止、换书撤销 AUTO，系统/进程恢复不签发新授权。
- 手动后续 10/20 或自定义范围，与 AUTO 授权独立。
- 用户主动下载保留，不能被滚动缓存删除。
- 只有正文、计划及每段真实音频完整才显示可离线；静音占位不算完成。
- 下载暂停、取消、重启恢复只影响任务，不擅自删除已完成内容。
- 开发迭代同步 iCode `origin/main`，GitHub 用作上游获取；2026-10-01 起联调用代码
  另同步到 GitHub `zhengwu163/yuedu-self`（`feat/ai-audiobook` 与 `main`）。

## 既有测试覆盖入口

| 文件（相对 app/src/test/java/io/legado/app/） | 现有覆盖方向 |
|---|---|
| `help/readaloud/prebuild/TtsCacheKeysTest.kt` | 现有预合成缓存键 |
| `help/readaloud/prebuild/TtsPrebuildLeaseTest.kt` | 现有预合成租约 |
| `help/readaloud/speech/SpeechRouteResolveTest.kt` | 声音路由解析 |
| `help/readaloud/casting/TtsTagSplitterTest.kt` | 模板标签切分 |
| `service/ReadAloudSentenceAlignerTest.kt` | 朗读句子对齐 |
| `service/SpeechFollowStateTest.kt` | 跟读状态 |
| `service/DownloadStateTest.kt` | 现有下载状态 |

Room instrumentation `MigrationTest` 亦已存在；新表迁移须追加旧数据保留断言。

## 一期产品验收矩阵（端到端待完成）

| 用例 | 验证类型 | 通过条件 | 失败/拒绝条件 |
|---|---|---|---|
| 本地 TextUnit | JVM 单测 | 原文回拼、范围准确、ID 稳定 | 空白/emoji 不丢字符，不重复朗读 |
| LLM 注释 | JVM/集成 | 仅接受当前 unit 和作品人物 | 越界 ID/重复冲突/改写正文被拒绝 |
| 长章分块 | JVM/假 Provider | 上块人物与尾文传给下一块 | full 模式不能绕过长度上限 |
| alias | JVM/Room | 稳定姓名合并到同一 ID | 场景称谓不默认永久化 |
| 声音绑定 | JVM/设备 | 首次锁定，旁白独立，兼容 model 沿用 | 不随机换声、不沿用上一人物声音 |
| 连续播放 | 假 Provider/设备 | 超时和失败有界推进 | 无无限等待/无限重试 |
| 缓存身份 | JVM | 参数/正文/作品变化正确失效 | 不串音、不串书 |
| 完整下载 | JVM/故障注入 | manifest 和每段文件一致 | 缺段、临时文件、占位不能 READY |
| 飞行模式 | 设备 | 连续播放至少 3 章 | 网络请求数为零 |
| 离线重启 | 设备 | 杀进程重开后续播 | 不重新拉正文/模型/音色列表 |
| 下载恢复 | Room/故障注入 | 只补缺失段，旧 worker 无法提交 | 暂停/取消不自动重启 |
| 缓存清理 | JVM/设备 | AUTO 回收，PINNED/当前播放保护 | 不能静默删手动下载 |
| 配额/空间 | 假 Provider/设备 | Wi-Fi 策略、低空间暂停 | 不全书自动生成，不无限消耗 |
| 密钥迁移 | 设备 | Keystore 加密，日志/导出无明文 | 失败不丢配置、不降级明文 |
| Room 升级 | instrumentation | 旧书、角色、进度保留 | 禁止 destructive migration |
| 普通朗读回归 | JVM/设备 | AI 关闭行为保持 | 媒体键/通知/音频焦点不能回退 |

上表是完整产品验收目标；当前 JVM 契约与授权测试仅覆盖其中部分边界。
合入/交付所需 runner 和用例落地后，
逐项填入真实测试路径、设备及命令结果，再将状态改为通过。

## 已识别阻断与风险

1. `AGENTS.md` 引用的新门禁及主题开工规范缺少原件，本地可达 Git 历史未找到；
   9 月 14 日旧规范和 SOP 有精确恢复来源，但不能替代 9 月 23 日新增要求。
   恢复原件或批准重建前，相关开工、提交和交付检查保持阻断。
2. Windows `build-legado.bat` 含机器绝对路径；macOS Gradle 是过程验证，
   不能直接作为满足现有规则的 APK 交付。
3. 正式签名一致性、覆盖安装和新管线设备 E2E 尚未验证；本批没有进行设备操作。
4. SDK 许可问题已解除，不再作为当前阻断；本地 Gradle/JVM 通过不等于 APK 交付通过。
5. 百炼真实章节分析及单段 TTS 生成/解码已有成功记录；三角色听感、跨章同声、
   Android 全链路仍未验收，正式 Windows 服务尚未联调（2026-10-01 起用户开始本地联调）。
   本地 loopback/Mock 不请求用户服务器或付费模型，真实调用继续受既有额度授权限制。

## 文档索引

- [现有架构审计](../CURRENT_ARCHITECTURE.md)
- [目标架构与数据/安全/离线契约](AI_AUDIOBOOK_ARCHITECTURE.md)
- [实施计划](../IMPLEMENTATION_PLAN.md)
- [Android 新服务边界](AI_AUDIOBOOK_ANDROID_ARCHITECTURE.md)
- [NovelAudioServer v1 协议](NOVEL_AUDIO_SERVER_API_V1.md)
- [新方案实施计划](AI_AUDIOBOOK_ANDROID_PLAN.md)
- [开发 Mock 运行与测试](../scripts/novel-audio-mock/README.md)
- [百炼桥接配置、运行与测试](../scripts/novel-audio-bridge/README.md)
- [临时云模型联调可行性](AI_AUDIOBOOK_CLOUD_TRIAL.md)
