# AI 多角色听书进度与验证记录

## 总体状态

当前处于 Phase 0。已完成源码静态审计与一期范围落文；
尚未完成基线编译、设备运行或业务功能交付。自动 2–3 章缓存和主动范围下载
已经纳入一期，但当前版本不能据此宣称支持可靠的多角色离线播放。

## 已有证据

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

## 分阶段状态

| 阶段 | 状态 | 退出标准 |
|---|---|---|
| 0 审计与环境 | 进行中 | 基线构建、普通朗读运行、文档同步 |
| 1 一章端到端 | 未开始 | 实际 Service 消费统一多角色计划 |
| 2 人物 Registry | 未开始 | 稳定 ID/aliases、作品隔离、可撤销合并 |
| 3 自动选音/凭据 | 未开始 | 绑定稳定、旁白独立、Key 安全迁移 |
| 4 管线/离线 | 未开始 | 2–3 章预缓存、范围下载、完整性及恢复 |
| 5 UI | 未开始 | 简单收听、人物编辑、下载管理、四态主题 |
| 6 回归交付 | 未开始 | 单测、设备、门禁、许可/签名验证 |

## 已确认产品决策

- 同名、同作者的逻辑作品共享人物与音色；物理章节和正文 hash 仍严格隔离。
- 自动后续 3 章、可设 2 章；手动后续 10/20 或自定义范围。
- 用户主动下载保留，不能被滚动缓存删除。
- 只有正文、计划及每段真实音频完整才显示可离线；静音占位不算完成。
- 下载暂停、取消、重启恢复只影响任务，不擅自删除已完成内容。
- 开发迭代同步 iCode `origin/main`，GitHub 用作上游获取。

## 现有测试（存在，尚未在本机跑通）

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

## 一期验收矩阵（全部待实现/执行）

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

这些用例目前不是已接线的 CI 门禁。合入/交付所需 runner 和用例落地后，
逐项填入真实测试路径、设备及命令结果，再将状态改为通过。

## 已识别阻断与风险

1. `AGENTS.md` 引用的统一 runner、测试配对和 Gson 审计脚本未入库；
   本地 Git 历史亦未找到对应文件。不能宣称通过、删除门禁要求或使用恒 PASS 替代。
2. Windows `build-legado.bat` 含机器绝对路径；macOS Gradle 是过程验证，
   不能直接作为满足现有规则的 APK 交付。
3. 未配置正式签名，未连接设备；签名一致性、覆盖安装和设备 E2E 尚未验证。
4. 首次构建所需 Cronet jar/so/清单未随 Git 保存，要通过既有受校验下载任务准备。
5. 真实模型/TTS 尚无授权配置的验证记录；使用假 Provider 不产生服务费用，
   不要求用户把密钥发到聊天中。
6. Android SDK 许可尚未接受。首次安装尝试请求 Build Tools 36.0.0，
   但实际 Gradle 日志明确要求 `build-tools;35.0.0` 与 `platforms;android-36`；
   后续以构建日志要求为准。此次在任务图解析阶段终止，单测、编译和 Cronet 下载任务均未执行。
   需要用户本人阅读并接受许可，或提供已获授权的 SDK 路径；不代替用户作出法律承诺。

SDK 许可确认入口（由用户本人在终端执行并阅读提示，不自动回答同意）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses
```

## 文档索引

- [现有架构审计](../CURRENT_ARCHITECTURE.md)
- [目标架构与数据/安全/离线契约](AI_AUDIOBOOK_ARCHITECTURE.md)
- [实施计划](../IMPLEMENTATION_PLAN.md)
