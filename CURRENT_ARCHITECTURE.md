# AI 多角色听书：现有架构审计

## 审计边界

源码基线为 `syq17496152/legado` 的 `1ff5651d10da961b66db98d39f1b5e82b6341b4e`。
本文记录静态代码事实；构建、设备运行和测试结果单独记录在
[进度文档](docs/AI_AUDIOBOOK_PROGRESS.md)，不能从“存在实现”推导“验证通过”。

本项目保留 GPL-3.0 许可证、上游历史及版权信息。GitHub 远端 `github` 用于获取上游；
开发交付目标为 `origin/main`，地址为
`ssh://wuzheng07@icode.baidu.com:8235/baidu/personal-code/reading`。

## 技术底座

- Kotlin Android，现有 View / Compose UI，Room 持久化，协程后台任务。
- 书架、书源、章节获取、阅读位置由现有 `ReadBook`、`WebBook`、Book/BookChapter 管理。
- HTTP/JS TTS、Android 系统 TTS 和 Media3 播放器已存在，增量复用。
- 构建真实配置要求 JDK 21、compileSdk 36、minSdk 23；Gradle 版本以 wrapper 为准。
  README 中较旧的工具链说明不能覆盖 `app/build.gradle`。
- 数据库版本以 `app/src/main/java/io/legado/app/data/AppDatabase.kt` 为准；
  迁移及导出 schema 随代码一起维护，不在本文复制版本号。

## 运行链路

```text
阅读入口 ReadBook → ReadAloud.play → 解析 SpeechRoute
                                      ├─ HTTP → HttpReadAloudService → Media3
                                      └─ 系统 → TTSReadAloudService → TextToSpeech

BaseReadAloudService.newReadAloud
  ├─ TextChapter → contentList → 实际播放
  └─ AiReadAloudRoleService.ensureCache → 本地 unit → LLM 归因 → Room 缓存
                                                              ↓
ReadAloudPlayerPanel → ReadAloudSpeechPlanner → 面板显示
```

关键断点：面板的 AI 分段计划和实际 Service 合成队列尚未统一。
不能把“能看到角色分段”视为“已经按照该角色音色朗读”。

| 责任 | 现有入口（均在 app/src/main/java/io/legado/app/ 下） | 复用与缺口 |
|---|---|---|
| 朗读路由 | `model/ReadAloud.kt` | 复用书级/全局路由，不创建第二播放器 |
| 后台生命周期 | `service/BaseReadAloudService.kt` | 复用通知、媒体键、焦点、阅读进度 |
| HTTP 播放 | `service/HttpReadAloudService.kt` | 目前按 contentList 和当前路由合成，不消费 AI speech plan |
| 系统多声 | `service/TTSReadAloudService.kt` | 有模板切分；HTTP/JS 路由未与系统段队列汇合 |
| AI 分析 | `help/ai/AiReadAloudRoleService.kt` | 章节缓存、分块、结构化工具返回可复用 |
| 本地切分 | `help/readaloud/role/ReadAloudRolePreprocessor.kt` | 已有段内范围；需统一快照、绝对 offset 和稳定 ID |
| 播放计划 | `help/readaloud/ReadAloudSpeechPlanner.kt` | 需成为 UI、实际播放、预生成的共同输入 |
| 预合成 | `help/readaloud/prebuild/TtsPrebuildManager.kt` | 当前单声音快照、内存 FIFO；不是完整多角色离线队列 |
| 文件键 | `help/readaloud/prebuild/TtsCacheKeys.kt` | v3 参数无显式作品、speaker、model、pitch |

## 语义与人物

1. `BookIdentity.key()` 由标准化书名、作者形成逻辑作品键；
   `Book.characterBookKey()` 已用于角色领域。角色实体的 `bookUrl` 字段在此领域可能实际存放 workKey。
   产品已选择同名、同作者作品跨书源共享人物与音色。
2. `BookCharacter.id` 是稳定 Room 主键，`speechRouteJson` 嵌入声音路由；
   没有独立稳定 alias 表、旁白 VoiceBinding 或可撤销合并记录。
3. 现有 `BookIdentity` 在作者缺失时退化为仅书名，存在同名作品误共享风险；
   新功能须保守隔离缺失身份信息的作品，并单独处理历史数据，不能全局静默重映射。
4. `ReadAloudRolePreprocessor` 输出 unit 和段内范围，ID 含 `text.hashCode()`；
   角色服务中的去尾空白、过滤空段和 UI 的另一条清理链可能造成坐标差异。
5. LLM 工具 `confirm_read_aloud_role_units` 已限制 requestedUnitIds；
   但 characterId 必须在本地再次校验所属作品。LLM 返回值不能直接取得数据库写权限。
6. 默认分块有 target unit 和上下文长度限制；full 模式缺少同等长章硬上限，
   并发分块之间不传递最新人物决议，可能产生重复人物或身份漂移。

## 缓存、恢复与失败

- `AiReadAloudRoleCache` 有正文 hash、segmentsJson 和分析状态，
  但分析命中维度还需绑定物理章节身份及协议/切分器版本。
- `HttpReadAloudService.preDownloadAudios()` 只预取下一章前十个非空段，
  不等于当前主动播放书籍后续 3 个完整章节的离线准备。
- HTTP 队列异常分支会 `pauseReadAloud()` 后返回；单段失败可能中断连续收听。
- 部分分支写静音占位。静音不能证明正文已经生成，更不能计为完整离线下载。
- `TtsPrebuildManager` 已有进度、有限重试、临时文件提交及保留键文件；
  主队列仍在内存，保留键有期限，不能保证用户主动下载永久免于滚动淘汰。
- HTTP 文件和 Media3 SimpleCache 位于 `cacheDir`。Android 可回收 cacheDir；
  用户明确保留的离线下载需要应用持久文件目录及完整 manifest。

### 开发分支的播放授权接线

`AudioPrefetchLifecycle` 已接入阅读按钮、媒体键、通知恢复与 Service 装配，
由用户请求、Service 所属权和单次 continuation 隔离迟到工作。`ChapterReadAloudRequest`
按书籍、章节及加载代次传递跨章续播；`ReadAloudAssemblyState` 隔离尚未提交的装配结果。
通知票据签发和发布在主线程串行，显式身份解析失败的投递不会作为普通播放执行。
目标章就绪后起播包含最后一章，书末停止仍由 Service 播放完成后的推进负责。
暂停会撤销预缓存授权，但不会丢弃已完成的本地朗读装配；同章选句位置在
`ReadBook` 锁内更新并通过现有 `saveRead(true)` 保存，恢复从章内字符位置重新装配。

这部分只控制内存授权及播放入口，不生成整章离线音频，也未接入持久下载队列。
另有已确认缺口：脱离阅读跟随后，跨章仍依赖 `ReadBook` 可视章节推进；
统一文本/播放计划阶段需要独立朗读游标，并补真实跨章行为测试。
- 已有 `DownloadState` / `DownloadService` 展示 Room 恢复思路；它们是现有下载业务，
  不应直接把音频任务混入其表而破坏语义。

## 数据与安全边界

| 资源/边界 | 当前事实 | 新能力必须补上的校验 |
|---|---|---|
| Book/BookChapter | 实体按物理 bookUrl、章节身份关联 | 换源不能复用不同正文的章节计划 |
| 人物/声音 | workKey 作用域；声音在角色 JSON 中 | 当前作品归属、稳定 aliases、旁白独立绑定 |
| LLM | AiChatService 发送配置的模型请求 | 正文外传告知、响应白名单校验、取消/超时 |
| TTS Provider | 接收待合成文字及声音参数 | 持久化能力、格式、长度上限、错误分类 |
| API Key | AiProviderConfig.apiKey 被写入普通 SharedPreferences JSON | Keystore 加密存储、迁移、备份排除、日志脱敏 |
| 后台生成 | Service/协程与内存状态 | 持久任务、代际检查、限流、暂停恢复、配额控制 |

本次范围是设备内阅读与音频生成，不新增服务端账号、云数据库或角色权限系统。
本地应用仍必须限制章节/角色作用域；不能以“单用户”省略数据完整性检查。
自动化输出只是注释建议，最终文件路径、Provider、任务状态及持久化由应用控制。

## 现有验证与缺口

仓库已有 `TtsCacheKeysTest`、`TtsPrebuildLeaseTest`、`SpeechRouteResolveTest`、
`TtsTagSplitterTest`、`ReadAloudSentenceAlignerTest`、`SpeechFollowStateTest`、
`DownloadStateTest` 和 Room `MigrationTest`。这些测试存在，不代表本机已经执行通过。

以下一期规则尚无对应完整验证：统一快照回拼、跨作品 characterId 拒绝、
临时称谓不落 stable alias、跨块人物稳定、旁白绑定、手动下载保留、
完整 manifest、飞行模式连播、进程重启恢复、API Key 安全迁移。

`AGENTS.md` 引用的 `ai_tests/scripts/run_gates.py`、
`audit_code_change_has_test.py`、`audit_gson_generic_signature.py` 和
`ai_tests/config/gate_registry.json` 在当前 Git 树与本地历史中未找到。
不能报告这些门禁已通过。`build-legado.bat` 是 Windows 交付入口；
macOS 直接 Gradle 仅用于过程验证，不作为 APK 交付替代。

## 参考项目与许可

本次只参考设计，不复制未经许可确认的代码，也不整合完整 fork。

| 项目 | 固定快照 | 许可核验 | 借鉴点 |
|---|---|---|---|
| Autsunset/legado-vox | `d109cf5a18112641d4082ee55eaeccd94e906df9` | GPL-3.0 | SHA-256 身份、旁白/人物绑定、prepare 与生成分离 |
| q1781756566/audiobook | `2950ab69c9c54dda34fe28655e9acadb5b4d02d7` | 未找到完整许可证，不复制 | 两阶段生成、章节级恢复、连续同 speaker 合并 |
| Finrandojin/alexandria-audiobook | `be6343d95d94db5f88973aa25575a390f3ce46aa` | MIT | 显式片段状态、取消恢复、固定绑定后合成 |
| jing332/tts-server-android | `49b4a7c33496110b62a5e727b76458bdec0aa6e7` | README 标 MIT，完整许可未核实，不复制 | Provider 参数和流式取消边界 |

关键参考：

- [legado-vox SpeechIdentity](https://github.com/Autsunset/legado-vox/blob/d109cf5a18112641d4082ee55eaeccd94e906df9/app/src/main/java/io/legado/app/domain/model/readaloud/SpeechIdentity.kt)
- [legado-vox PrepareChapterSpeechPlanUseCase](https://github.com/Autsunset/legado-vox/blob/d109cf5a18112641d4082ee55eaeccd94e906df9/app/src/main/java/io/legado/app/domain/usecase/PrepareChapterSpeechPlanUseCase.kt)
- [audiobook prepare/generate](https://github.com/q1781756566/audiobook/blob/2950ab69c9c54dda34fe28655e9acadb5b4d02d7/audiobook_maker.py)
- [alexandria task state](https://github.com/Finrandojin/alexandria-audiobook/blob/be6343d95d94db5f88973aa25575a390f3ce46aa/app/project.py)
- [tts-server-android snapshot](https://github.com/jing332/tts-server-android/tree/49b4a7c33496110b62a5e727b76458bdec0aa6e7)

## 相关文档

- [目标架构与一期契约](docs/AI_AUDIOBOOK_ARCHITECTURE.md)
- [增量实施计划](IMPLEMENTATION_PLAN.md)
- [实施状态与验证记录](docs/AI_AUDIOBOOK_PROGRESS.md)
