# NovelAudioServer Android Implementation Plan

> 按用户新方案增量实施，测试先行；Mock 通过不等于真实服务联调通过。

**Goal:** Android 通过版本化 NovelAudioServer API 完成多角色听书，并保留离线与普通阅读能力。
**Architecture:** 本地正文/人物/缓存为事实源；单一 HTTP 边界；复用现有播放器、Room 和协程。
**Tech Stack:** Kotlin、OkHttp、Gson、Room、Media3、JUnit、Python 标准库开发 Mock。

## 第一批：通信边界

文件位于 `app/src/{main,test}/java/io/legado/app/help/readaloud/server/`。

- [x] 静态核对现有网络、AI、角色、播放器和预生成入口；现有通用 HTTP 配置不适合承载新 Token。
- [x] 固定 `NOVEL_AUDIO_SERVER_API_V1.md` 六端点协议；真实 Windows 服务成功测试按用户要求暂缓。
- [x] `NovelAudioServerClientTest.kt`：先写契约用例，运行确认缺实现失败。
  用本地 HTTP 测试服务验证真实 OkHttp 请求，不请求用户设备或公网模型。
- [x] `NovelAudioModels.kt`：所有 DTO `@Keep`；请求明确 book/chapter/textHash/analysisVersion。
- [x] `NovelAudioServerClient.kt`：标准 TLS、Bearer、无重定向、总超时/取消、有界响应、
  类型校验与固定错误；不把远端正文/Token放入异常。一次调用仅一次请求。
- [x] 运行：
  `sh ./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.help.readaloud.server.*' --no-configuration-cache`。
  必须断言未知 unit/speaker、缺字段、401/429/500、非音频响应、空音频、超限和取消。
  当前 24 项通过；额外覆盖 503 不重发、Retry-After 溢出、排队超时、
  调用方 deadline、严格 UTF-8、重复 JSON 键、分块音频超限。
- [x] `scripts/novel-audio-mock/`：六端点的确定性协议模拟、故障注入及 README；
  音频成功只读取开发者明确提供的压缩音频 fixture，不伪造真人语音或离线 READY。
  Mock 行为测试 7 项通过，不代表设备或真实服务联调。
- [ ] 新管线测试小说 fixture：与 TextUnitParser 和端到端用例一起接入。
- [ ] `dev-run.sh`：复用既有 Mac 测试工具的检查/build/install/launch，不清数据、仅 debug。
- [x] 跑完整 App JVM 单测并记录：747 项，743 通过，4 既有跳过，0 失败。
- [ ] 恢复并运行既定提交/交付门禁；当前 runner、配对/Gson 审计脚本缺失，保持阻断，不跳过。

## 后续依赖顺序与退出标准

1. 连接配置：现有朗读设置增加 URL、Keystore Token、连接测试；未配置不请求服务器，
   服务不可用仍可普通阅读/朗读；设备验证保存/清除/备份排除与证书失败。
2. TextUnitParser + ChapterAnalysis：复用预处理器并统一排版前快照，UTF-16 回拼、emoji、
   长章分块、hash/version 命中及新旧数据共存有实际测试。
3. Registry + VoiceBinding：复用 BookCharacter 稳定 ID，增加稳定 aliases 和 narrator 独立绑定；
   声音仅 voiceAssetId/元数据，逻辑作品共享且跨作品隔离，迁移保留历史数据。
4. Analyze 接线：新 AI 听书经唯一服务器客户端，取消/超时有界；临时 ID 本地事务转正式 ID，
   计划冻结，迟到结果不得改变当前播放。
5. AudioSegment + TTS + 缓存：连续同 speaker 合并、实际 profile/cache key、原子文件、
   manifest 完整性；接入现有 Service，真实播放与面板同计划。
6. 后台/离线：接上已有 AUTO 授权固定后三章；手动范围下载、重启补缺、PINNED 保护，
   飞行模式不访问书源/分析/声库/TTS。
7. 人物声音 UI：旁白/人物试听换声，别名/改名/合并及撤销为高级入口，不展示内部 JSON。
8. 故障回归：普通书架/书源/进度/朗读/后台/备份回归；服务上线后补真实六端点联调。

新方案优先于旧计划中的模型直连假设。旧文档保留已有实现/测试记录，不将未实现项迁移为完成。

## 第二批：服务器配置安全底座

沿用已确认的独立 Keystore 配置设计。此批不增加云厂商直连、不改播放器路由：

- [x] 先增加 `server/NovelAudioServerConfigStoreTest.kt`：缺省无配置、保存/重建读取、
  URL/Token 配对替换、无效输入拒写、加密/写入失败保留旧配置、篡改/密钥丢失拒读、
  清除失败不能伪报成功、异常与 toString 不含凭据、HTTP 必须明确允许。
- [x] `NovelAudioServerCredentials.kt`：共享地址与 Token 校验；不可变配对快照，
  不生成包含凭据的 data class toString；更换地址必须同时提交新 Token。
- [x] `NovelAudioServerConfigStore.kt`：用私有 `noBackupFilesDir` 下原子文件保存整个
  AES-GCM 加密配对；同目录暂存、fsync、rename 提交，失败不触碰旧值；损坏时固定 STORAGE 错误，
  不静默清空；清除采用原子空标记。此目录不参与 Android 自动备份及现有手工备份。
- [x] `NovelAudioServerCredentialCipher.kt`：独立 AndroidKeyStore alias，
  加密随机 IV、AAD 绑定存储格式；读取不自动创建丢失密钥。不改 Relay 存储。
- [x] 先跑新测试确认缺实现失败，再最小实现，跑 `server.*` 与全量 App JVM 单测；
  server 共 47 项通过；全量 770 项、766 通过、4 既有跳过、0 失败。
  实际 AndroidKeyStore/设备存储测试留作单独验收，不冒称 JVM 覆盖硬件安全。
- [x] 同步进度；设置页面接线是下一步。本批无用户入口，不增加用户可见更新日志条目。
- [ ] 运行 `NovelAudioServerCredentialsDeviceTest` 的两项隔离设备用例；目前仅 AndroidTest 编译通过。

## 临时云服务过渡（本地桥接已实现）

临时服务部署在开发电脑，向 App 暴露同一 `/v1` 契约，再调用官方云端分析/TTS。
云厂商 Key 只保留在临时服务端，App 只保存该服务的 URL/Token。
用户已开通百炼试用；固定采用北京 `qwen3.7-plus` 与 `qwen3-tts-instruct-flash`。
根目录 `novel-audio.local.env` 只需填写 API Key；兼容服务与离线测试见
`scripts/novel-audio-bridge/`。用户已确认两个模型有效免费额度及“免费额度用完即停”。
真实章节分析通过；修复结果主机白名单后，单段 TTS 已生成并完整解码。
未代查账户当前余量；三角色听感和 Android 接线尚未验收，本地计数不代替供应商停费保护。
首次联调仅用原创短测试章节；云端配额不足时停止，不自动切收费模型。
迁移至用户 Windows 服务时重新绑定声音并隔离服务器/profile 缓存，
保留本地人物 ID 与已下载音频；不能承诺不同后端完全同声。

## 第三批：正文快照与无损单元（本地验证完成）

范围限定在 `help/readaloud/analysis/` 及对应 JVM 测试；本批不接用户入口、
不修改旧 Service、不调用云模型，也不新增面向用户的更新日志。

- 快照接收最终正文段落及 UTF-16 坐标，复制输入集合后冻结；
  本层段落之间使用明确的 LF 分隔，不以排版页文本充当最终正文。
  实际排版前正文获取和书籍/章节身份接线仍需单独实现。
- 复用 `ReadAloudRolePreprocessor` 的对白范围作为切分依据。
  补齐其省略的空白及段落分隔；所有 unit 顺序拼接必须等于快照全文。
- 单元内上限 1200 UTF-16，跨段对白保持源坐标；长段拆分不截开 surrogate pair 或 CRLF。
  空白单元留在本地计划中，后续请求组装时不发给拒绝空白的服务端。
- 快照 SHA-256 基于完整文本；单元身份含范围和内容。
  此批不创建分析缓存，正式 cache key 还必须含作品/章节、解析规则、analysisVersion。
- [x] 验证本机 JDK 21 与 SDK，3 项初步坐标/跨段测试通过。
- [x] 添加空白回拼、长段、不可变快照、非法 UTF-16/坐标及稳定身份测试，确认缺陷失败后实现。
- [x] 扩展到空段全区间回拼、200 组固定种子文本、段尾 CR 与段间 LF 边界；
  16 项中 2 项失败复现后修复，16 项通过。角色区间先协调 CRLF，再做长度切分。
- [x] 独立审查提出对象字符串包含正文；新增第 17 项先失败，再覆写为长度/数量摘要。
- [x] 2026-09-28 定向 17 项全部通过，全量 JVM 787 项：783 通过、4 既有跳过、0 失败，exit 0。
- [x] 独立只读复核确认两项修复闭环，本批范围内未发现残留重要问题；
  已同步 `AI_AUDIOBOOK_PROGRESS.md`。未接入口、未提交/推送，设备和交付门禁仍待完成。

接线前约束：

- `ANALYSIS_VERSION` 当前只标识本地解析算法，不代表完整设置签名，也不是 wire 协议字段。
  桥接目前只接受请求 `analysisVersion="1"`；请求适配器须分别处理 wire 版本与本地缓存版本。
- 预处理器仍读取当前 AppConfig；正式分析缓存接线前必须冻结有效规则并加入缓存身份。
  任意自定义引号规则（尤其非 BMP 字符）需要追加专门验证，不能沿用默认规则测试结论。
- `chapterStart/chapterEnd` 是调用方的外部源范围；允许段间间隙，不用于恢复未知正文。
  真正排版前快照入口必须显式保留分隔符，不能把当前 LF 合同当作任意源字符串的无损转换。

过程验证命令（不产出交付 APK）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
sh ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.analysis.*' --no-configuration-cache
```

## 剩余批次开工依赖（2026-09-28）

用户已授权连续完成剩余产品功能；每个组件完成后不再重复请求继续。
用户已进一步授权使用 Mac 构建 Android debug 并补齐符合实际的规范；执行口径见 `AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md`。以下记录区分已确认的权限与仍需实际完成的验收。

- [x] 只读定位最终正文入口：`ReadBook` 两处章节装配在段落处理后、页面排版前取得
  `BookContent`。最终正文坐标与阅读器渲染坐标应分别建模，`sourceIndexes` 不等于字符映射。
- [x] 确认人物复用边界：保留 `BookCharacter` ID；新增稳定别名、旁白独立绑定、
  服务作用域和 revision。旧 `BookIdentity` 的无作者同名回退不得直接用于新数据。
- [x] 定位历史规范：提交 `2726d50fe8e3ac4716d37c7188f30c029e6ec843`
  有 24 份旧规范与测试 SOP；已只读加载开工检查、数据库迁移安全及 SOP。
  原文可按需恢复，但不是 9 月 23 日最新卡点全集。
- [ ] 补建当前缺失门禁：`run_gates.py`、`gate_registry.json`、
  `audit_code_change_has_test.py`、`audit_gson_generic_signature.py`、
  `audit_theme_token_violation.py`、`audit_host_refresh_coverage.py`、
  `theme-consistency-iron-rule.md`、`process-gate-architecture.md` 及其所需配置/挂载。
  本地所有 refs/reflog 可达历史未找到；SSH 只读核实 iCode 当前唯一分支
  `main=0003fdd` 也不含上述关键门禁。按用户授权在当前仓库补建实际可执行版本。
- [ ] 按 `AI_AUDIOBOOK_ANDROID_DELIVERY_RULES.md` 完成 UI K1，并补建可执行 K2/K3/K4 检查。
- [ ] 补齐正式自动 E2E 的专用 venv、固定入口/库及设备适配。本机有 `legado_test`
  AVD 和手工测试工具；此次没有启动设备或运行 AndroidTest。
- [x] Windows 发布脚本不作为本阶段 Android debug 阻断；签名一致性、覆盖安装和正式发布仍单独记录。
- [x] 重跑现有回归：App JVM 787 项（783 通过、4 跳过）、桥接 78 项、
  Mock 7 项，命令均 exit 0；本轮未新增产品代码、云请求或交付 APK。

依赖解除后的实施顺序仍为：最终正文与规则冻结 → 分批分析与人物事务 →
声音绑定/共享 AudioSegment → 持久音频与下载队列 → 设置/人物/播放接线 →
设备与离线回归 → 门禁及交付。每一步沿用上文退出标准。

## 当前修复与设备验收批次（2026-09-29）

以下是当前代码核查后的实施顺序；上文测试数量属于各批次历史记录，不代表当前 APK 验收结果。

1. 数据恢复：先跑隔离 Room/Keystore 设备测试，验证旧 generation 不改变计划、
   artifact 和下载任务，READY 查询去重，坏音频下载可重新排队且保留 PINNED，
   升级保留书籍、阅读进度、人物和旧朗读缓存。
2. 用户入口：在现有朗读设置内提供 URL/Token 配对保存、清除与连接测试；
   朗读引擎菜单可选择 AI 多角色听书。编码前补齐实际组件/token 的 K1 记录。
3. 播放闭环：自动跨章沿用有效的播放授权，定位/选句走本地音频播放器；
   普通恢复、过期票据、暂停和切书不得新增服务器请求。
4. 本地优先与恢复：已验证的 READY 计划不重复分析/合成；仅补缺段，
   未完成下载持久化恢复，AUTO 仍需本进程有效授权，PINNED 不受自动窗口淘汰。
5. 连续验收：真实压缩音频 fixture + 本地协议服务验证用户入口、当前位置、
   跨章、后台/媒体键、后三章窗口、手动下载、飞行模式和重启；普通朗读单独回归。

已取证的工程回归：`scripts/test_cronet_preparation.py` 两项通过，
实际执行两轮强制 Cronet 任务并验证第二轮 configuration cache 重用；
`scripts/test_project_gates.py` 五项通过，空门禁阶段按失败处理。
这些结果不替代以上设备和产品验收。本批未使用真实云模型调用。

### 设备回归证据

- 2026-09-29：Android 15 `legado_test` 实际执行 14 项，失败/错误/跳过均为 0。
  包含迁移 8 项、损坏恢复与代际隔离 4 项、AndroidKeyStore 2 项。
- 旧库书籍、阅读进度、人物和角色缓存保留断言已实际执行；损坏下载重新排队且保留
  PINNED，RUNNING、其他代际与其他计划的任务保持不变。
- 同批次新计划身份 JVM 用例先失败，证明服务器/声音设置身份冲突。
  对应生产修复在该批编译后写入，需独立重新编译回归，不能引用上述设备结果证明它。

2026-09-29 后续验证：

- 计划身份修复独立重编译通过；该批全量 JVM 848 项，844 通过、4 既有跳过、0 失败。
- 配置与播放目录新增 JVM 10 项通过（Settings 8、PlaybackCatalog 2）。
- 配置 UI 的首轮 3 项均停在测试框架初始化，未执行业务断言。
  设备错误及 R8 usage 报告确认主包移除了测试 APK 调用的共享协程/Compose API。
  Debug 保留共享运行库边界后继续设备回归；正式包规则不变。
- 独立审查要求补真实 Fragment 的保存/清除/重建、实际 Context 目录隔离、
  选中文字的主题色与 AI 引擎摘要断言；这些补验不计为已完成。
- 当前全量 JVM 已更新为 858 项：854 通过、4 既有跳过、0 失败。
  Context 目录隔离设备 1 项通过；Fragment 测试的旧引擎空值恢复编译错误已修正，
  并增加初始化失败保护。配置保存/清除遇视图重建的通知、失败保留和迟到 health
  回调已有专门设备用例，等待实际运行结果。
- 配置 Content 3 项业务用例与 Context 目录隔离已在设备通过，选区像素断言实际失败。
  已按 `AppDialogStyle.accent` 显式设置选区，等待重编译回归。
  首个 Fragment 用例启动时遇到 `DesugarCollections.synchronizedMap` 缺失；
  APK 反汇编确认方法在主包存在、测试包缺失。测试 APK 独立 L8 产物抢先加载，
  因此在测试宿主前显式使用该 JDK API，保持两包所需运行时方法可达，继续设备验证。
- 后续 14 项已完整执行：11 通过、3 失败。选区与 Fragment 启动问题已设备复验通过；
  保存/清除提交后重建的通知 2 项超时，引擎摘要 1 项断言失败，已获得业务 RED。
  通知移到同步提交后的 IO 块、摘要补 NovelAudio 分支；隔离测试清理等待旧视图 Job 完成，
  分项恢复资源并汇总异常。上述修复等待独立重编译与设备 GREEN。
- 2026-09-29 12:38：上述修复独立重编译后 14 项设备全部 GREEN，无失败/跳过；
  同批全量 JVM 858 项（854 通过、4 既有跳过、0 失败），组合命令 exit 0。
  四态用例仍单独待验；新增实际协调器身份测试 3 项未包含在这批 JVM 数量中。
- 实际协调器请求身份 3 项 JVM 已运行：同章重复请求仍被判为当前、旧完成清掉新请求
  两项失败，取消用例通过。生产比较改为引用身份后，定向 3 项全通过、命令 exit 0，
  正在重新执行全量回归；
  此项不代替准备回调事件/播放授权/跨章控制链设备测试。
- 2026-09-29 12:49：实际协调器修复后的全量 JVM 861 项（857 通过、4 跳过、0 失败），
  四态主题 AndroidTest 编译通过，组合命令 exit 0；设备执行仍需独立验证。
- 坏音频目标替换新增 4 项 JVM 全部失败后，实施校验 staging 再原子替换；
  12:59 与原存储用例合计 13 项全部通过。失败/取消保留旧目标，禁止先删再写。
  Android 完整解码、提交后取消与并发复用边界仍需补验。

### 用户入口 K1 与实施卡

- [x] 已查阅 `ServerConfigDialog.kt` 的 `ComposeDialogFragment` 表单、
  `ReadAloudConfigDialog.kt` 的 `SettingItemSpec` action、
  `AppComposeDialogs.kt` 的 `AppDialogFrame` / `AppDialogStyle`。
- [x] 新增 `NovelAudioServerConfigDialog` 登记为 Form 弹框：
  背景/输入面/正文/次文/描边/强调/错误分别使用
  `surface/fieldSurface/primaryText/secondaryText/stroke/accent/danger`；
  按钮用 `LegadoMiuixActionButton`，圆角用 `actionRadius`。
  不新增颜色常量或 M3 派生色。
- [x] 选区补验的取色归属：`AppDialogStyle.accent` 来自 `context.accentColor`，
  与光标/聚焦描边同源；选区覆盖层使用该色的 40% alpha，底面为 `fieldSurface`。
  已检查通用 Form 的 `AppComposeDialogs` 与现有输入框，未发现已封装的选区 token；
  采用字段级显式选区设置，不引入另一套 Material 配色。设备像素断言和四态仍须通过。
- [x] 刷新沿用 `rememberAppDialogStyle` → `rememberThemeUiPalette`，
  已核对后者监听主题偏好与 ThemeStore；新增弹框显式消费 `ThemeSync.version`。
  四态截图及宿主刷新仍待设备验证，未标验收通过。
- [x] 六维盘点：入口位于朗读设置/多角色；接口仅 health 测试；
  不改 Room schema；覆盖安装保留旧设置；保存与清除不授权播放；
  成功后用既有配置变更通知刷新引擎摘要。
- [ ] 先写 `NovelAudioPlaybackCatalogTest`：独立播放目录有唯一 NovelAudio，
  原系统/HTTP组顺序与内容不变，角色可分配声源不含 NovelAudio。
- [ ] 先写 `NovelAudioServerSettingsTest`：更换地址清空旧 Token/HTTP 许可、
  HTTP 未确认拒绝、草稿 health 不写配置、未就绪不报成功、错误不带凭据。
- [ ] 实现草稿模型及连接测试；弹框仅内存保存草稿，关闭即丢弃；
  加载/保存/清除走 IO 和独立 Keystore，失败保留旧配置并显示固定文案。
- [ ] 在 `SpeakEngineDialog`、`ReadAloudPlayerPanel` 接入播放专用目录；
  人物发言人目录继续沿用 `allGroups`，避免把整章引擎当单角色声源。
- [ ] 定向 JVM → 全量 JVM → 配置/入口 AndroidTest → 默认/自定义色/主题包/夜间四态；
  运行适用门禁，实际证据齐备后再提交与交付。
