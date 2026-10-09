# AI 听书：Windows agent 全面接管说明（2026-10-09）

自 2026-10-09 起，AI 听书的全部后续开发、联调、真机验收和调试由 Windows agent 独立完成，
Mac 端不再参与。本文件是接管时的权威事实来源；与仓库里更早的交接文档冲突时以本文件为准。
`scripts/novel-audio-local/WINDOWS_HANDOFF.md` 是阶段 7B 的历史交接，其中「首个 FAIL 即停」等
条款不再适用。

## 1. 产品目标

- 手机（Legado 测试包 `io.legado.miss.app.debug`）把小说章节交给家庭 Windows 电脑，
  电脑用本地大模型做角色分析、用本地 TTS 按角色合成，手机下载音频后离线多角色听书。
- Windows 主机常驻（桌面开关 + 托盘），模型按需加载、生成完卸载，不长期占显存。
- 手机侧：可「预加载到第 N 章」；播放到已准备的最后一章时自动唤醒电脑续写后 3 章。
- 解耦：手机⇄电脑不绑定（任何人可自部署本地服务）；在线模型也可自填 Key 替代；模型可替换。
- 连接方式（用户已定）：同一家庭 Wi‑Fi 局域网直连 + 访问令牌，http，不走公网/隧道。

## 2. 代码与分支（GitHub `zhengwu163/yuedu-self`）

- `feat/local-model-service`：**主线**。本地服务 `scripts/novel-audio-local/` +
  `scripts/novel_audio_server/`，Android 侧 Runtime Lease、按服务端上限分段、
  自托管免额度等都在这里。后续开发全部在此分支进行。
- `feat/ai-audiobook-voicestudio`：**参考分支，不合并**。是 Mac 上的另一套方案
  （百炼云端桥接 + VoiceStudio 本地 TTS 适配）。其中有几项手机端通用修复，见第 5 节，
  需要评估后单独移植。
- `main` / `feat/ai-audiobook`：旧基线，不在其上开发，不推送。

## 3. 已完成

本地服务（阶段 7B，Windows 实测）：
- 9B 分析 + Qwen3-TTS 合成功能通过，用户试听 9B 认可；4 轮修复已合入
  （venv/进程清理、IPC、资源门禁 `insufficient_resources`、显式租约预热、4B 低音量归一化）。
- 本次新增：`/v1/runtime/status` 与 `/v1/runtime/acquire` 声明 `maxSegmentChars=50`，
  acquire 声明 `selfHosted=true`。

Android（本次，`feat/local-model-service`）：
- 解析租约里的 `maxSegmentChars` / `selfHosted`（严格类型校验）。
- 分析后把超过上限的单元按句读切成多段（句末优先，逗号次之，最后硬切），
  每段带精确正文 range，高亮与回写不变；未声明上限的服务（云端桥接）行为不变。
- `selfHosted` 租约下的分析与合成不扣云端试用额度，但仍走单飞许可（一次只生成一段）；
  释放租约后恢复计额。
- 合成/试听单次超时 30s→60s；服务端 `insufficient_resources` / `resource_check_failed`
  映射为不可重试的「家庭 AI 电脑显卡或内存不足，请关闭占用显卡的程序后重试」。
- 验证：Mac 上 JVM 单测 `io.legado.app.help.readaloud.*` 507/507 通过，全量
  `testAppDebugUnitTest` + `assembleAppDebug` 通过；Python 本地服务 322 项中 2 项失败，
  原因是 Mac 上 8787 端口被百炼桥接占用（环境问题）。**尚未在真机上运行。**

## 4. 实测约束（代码里看不出来）

- 单段合成控制在 30 秒内的前提：acquire 后先短预览预热、整批持有同一显式租约、每段约 50 汉字。
  自动租约冷启动约 33 秒。
- 合成慢于实时约 1.6 倍：一章约 3800 字约需 28 分钟生成、18 分钟播放。本地模式只适合提前准备。
- 9B+TTS 需约 12GB 空闲显存；ComfyUI 等常驻程序会挤占显存导致 Worker 崩溃。
- 4B Base 克隆的参考录音偏小（约 -38 LUFS），代码已做峰值归一化；若仍有「远处感」，
  需用户重录参考（10–30 秒、24kHz、单声道、近距离、低底噪）。
- 硬件：RTX 5090 D v2 24GB（sm_120），运行时根 `D:\NovelAudioLocal`，Windows 原生，不用 WSL。
- 本地服务 `config` 支持 `host: "0.0.0.0"` + `allowLan: true`（非 127.0.0.1 时必须开 allowLan）。

## 5. 参考分支中的手机端修复候选

在 `feat/ai-audiobook-voicestudio` 上，基于真机问题修过。移植前先在本分支写失败用例确认问题存在：
- `eeb1ae2b` 章节计划从数据库读回时解码必然失败导致无法播放（**疑似阻断播放，优先核实**）
- `4897dc10` 重启后准备完成却不开始播放（本分支 `prepareAndPlay` 代码路径相同，已试过可干净应用，
  仅 updateLog 冲突）
- `5b6e1e47` 本章准备进行中时不再补发准备，避免同一章重复分析
- `14ce0d52` 音频下载失败原因带上服务端错误类型
- `034a929f` / `f5d54965` / `8dd3624d`：超时与「按服务端声明计费」机制，和本分支的
  60s 超时、`selfHosted` 租约方案重叠，**不要照搬**，只借鉴思路。

## 6. 后续工作（按优先级）

1. 部署与联网：部署本分支最新提交；开放局域网访问（只放行专用网络、只开服务端口）；
   从局域网带令牌自测 health / acquire / 50 字合成 / release。
2. 手机端阻断项：核实并移植第 5 节前四项；构建 debug 包装到手机（覆盖安装，不清数据），
   配置 `http://<电脑局域网IP>:<端口>`、令牌、允许不安全 HTTP。
3. 真机端到端：PINNED 当前章节 → 分析 → 分段合成 → READY → 播放 → 飞行模式离线连听 →
   杀进程续听；记录整章耗时、首段等待、失败原因。普通朗读/HTTP TTS 不回归。
4. 人耳验收：多角色效果、4B 音量复听（需要用户试听时才停下来）。
5. 生命周期：评估「批次后立即卸载」改为「空闲超时卸载」；预加载到第 N 章、
   播放触发续写 3 章的手机端行为在本地模式下的真实表现。
6. 产品化：托盘/开机自启、下载即用的 Windows 包、模型可替换、在线模型自填 Key。
7. 方案收敛：本地主线稳定后，再决定云端桥接方案如何并入（届时统一额度/超时机制）。

## 7. 规范与红线

- 遵循仓库 `AGENTS.md`：只用测试包 `io.legado.miss.app.debug`；不清除手机数据；
  代码变更必须配套测试（pre-commit 门禁 `ai_tests/scripts/run_gates.py`，`SKIP_GATES=1` 不能跳过
  测试配对）；`app/src/main/assets/updateLog.md` 一天一条、单条 ≤40 字、只写用户可感知变化；
  Gson 模型加 `@Keep`；AndroidTest 调生产方法显式写全参数；交付 APK 走 `build-legado.bat`。
- 真机验收使用真实本地模型，不用 mock 顶替。
- 不打印、不提交、不回传令牌、API Key、正文内容；仓库是公开的，推送前扫描密钥。
- 防火墙不放行公用网络，不做路由器端口映射，不开公网。
- 不推 `main`，不强推；提交只 add 指定路径，Conventional Commits。
- 需要停下来找用户的情形：需要人耳试听或人工操作手机/UAC；必须越过红线；
  同一问题换过 3 种以上方案仍不通。其余卡点自行排查解决，阶段性写结论后继续推进。
