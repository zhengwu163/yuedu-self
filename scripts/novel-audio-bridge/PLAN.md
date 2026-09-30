# 百炼试用桥接实施计划

目标：用户在根目录 `novel-audio.local.env` 填写一次 API Key；
Python 桥接提供 NovelAudioServer v1 六端点，Android 继续使用统一服务协议。

技术：Python 3 标准库、SQLite 原子计数、ffmpeg WAV → Ogg/Opus。
本批只新增开发桥接，不改变 Android UI、数据库或现有播放行为。

## 已确定边界

- 文本模型 `qwen3.7-plus`；语音模型 `qwen3-tts-instruct-flash`；北京地域。
- 本地只监听 127.0.0.1:8787；Android 使用 adb reverse。
- 云 Key 仅在本地配置中；随机桥接 Token 自动存入本地状态，不打印。
- 配置检查不发云请求；真实调用需启动参数确认两个模型已开启“免费额度用完即停”。
  无法通过普通推理 Key 验证控制台开关，不能宣称本地限制保证零费用。
- 试用累计最多 20 次分析、100 次 TTS、分析正文 24000 UTF-16 字符、
  TTS 正文 5000 UTF-16 字符；重启/换日期不清零，失败请求也计数。
- 收到 `AllocationQuota.FreeTierOnly` 持久熔断所有生成请求；不重试/换模型。
- 分析每批最多 64 单元/4000 UTF-16 字符；仅允许本次 unit/角色/新 temporaryId。
  人物绑定仍由 Android 负责；旁白独立，匹配不能使用旁白的底层音色。
- TTS 请求最多 1200 UTF-16 字符，云端每次最多 600 字符；
  内部拆分最多 2 段、预占全部额度，整体 25 秒期限，下载音频无 Authorization。
- 云响应 JSON 和音频均有上限；只下载已知百炼结果域名并强制 HTTPS；
  禁止重定向、代理继承、原始错误日志。音频必须为可解析 PCM WAV。
- profile 包含模型/声音目录/转换版本及实际 FFmpeg 版本；
  稳定模型别名被供应商更新时仍需人工升级桥接版本并重新验收。
- macOS 上 Ctrl+C 关闭 HTTP 服务时终止在途工作进程及其转码子进程；
  关闭后拒绝新建工作进程。已发送到供应商的请求不承诺撤销或退还额度。

## 实施顺序与验证

1. `test_bridge.py` 先定义配置、额度、声库及六端点测试；运行失败后实现
   `bridge.py` / `protocol.py` / `local_state.py`。
2. `test_cloud.py` 验证模型请求、错误脱敏、下载限制、超时、不重试、
   WAV 合并及真实 ffmpeg 转码；实现 `cloud.py`。
3. `test_runtime.py` 验证 HTTP 鉴权、重复键、非法 UTF-8、报文长度和 loopback，
   以及安全初始化、不覆盖已有 Key、只检查模式、原创 smoke、超时和停止服务。
   `test_boundaries.py` 补充严格类型、预算并发/损坏/熔断与长文本边界。
4. 根配置、example、Git 精确忽略，补 README 启动/配额说明。
5. 运行 `python3 -m unittest discover -s scripts/novel-audio-bridge -v`、
   原 Mock 7 项及 Android server/offline JVM 回归；独立审查后更新进度记录。

真实云调用在用户填 Key 并确认控制台保护后执行；本批自动测试不读真实 Key、
不调用云模型。App 全链路、设备验收与仓库交付门禁单独跟进。

## 首次本地验证记录（2026-09-24）

- 步骤 1–4 已实现；桥接离线测试 59 项通过，启用
  `-W error::ResourceWarning`，运行无资源未关闭警告。
- 用例分布：`test_bridge.py` 15、`test_boundaries.py` 22、
  `test_cloud.py` 9、`test_runtime.py` 13。
- 停止服务的回归先复现 worker 遗留，再验证修复后 worker 及模拟转码子进程退出。
- 独立复核重跑 59 项并验证关闭/新建竞态与反向对照，未发现修复范围内残留重要问题；
  停止验收限 macOS/POSIX Ctrl+C 有序退出。
- 原 Mock 7 项通过；Android 全量 JVM 770 项：766 通过、4 既有跳过、0 失败。
- 根配置/预算/连接快照/试听音频的 Git 忽略与启动脚本语法检查通过。
- 真实百炼账号、三角色声音效果、Android 全链路及提交/交付门禁尚未验收。

## 模型与 Key 兼容调整（2026-09-24）

- 按用户提供的模型更新文本分析为 `qwen3.7-plus`，采用北京 OpenAI 兼容
  Chat Completions；顶层明确关闭思考/流式并限制 JSON 输出与 4096 Token。
- 首次试听采用用户允许的 `qwen3-tts-instruct-flash` 预置音色方案；
  VD 方案需额外创建并持久保存专属音色 ID，不在本次接入范围。
- Key 使用有长度上限的 Bearer 字符校验，保留点号等合法字符，
  不修改本地密钥内容，不放松 header 注入防护。
- 测试先复现旧字符限制、旧默认模型及响应结构不兼容；调整后 61 项通过。
  `test_bridge.py` 增至 17 项，其余数量不变；原 Mock 7 项通过。
- 本地 `--check` exit 0，确认配置可读及真实音频转换能力，不请求百炼。
  用户已确认两个所选模型的有效免费额度与停费保护后，首次真实试听已执行；
  结果见下方失败记录。

## 首次真实试听结果（2026-09-24）

- 用户确认 `qwen3.7-plus` 与 `qwen3-tts-instruct-flash` 的免费额度及
  “免费额度用完即停”已生效；随后执行一次原创三角色 smoke。
- 配置读取和本地 ffmpeg 检查通过；章节分析请求通过；第一段 TTS 请求后返回
  `invalid_cloud_response`，进程 exit 2，未生成可见试听音频。
- 当时旧诊断把 TTS 响应、结果音频下载、WAV 校验和 ffmpeg 转码统一折叠为一个错误码，
  因而不能据此判断是字段、地址、媒体、权限还是额度问题；没有自动重试或切换模型。
- 已补充固定脱敏阶段码，并用离线响应覆盖各阶段；再次试听前需由用户确认只执行
  一次最小原创单段 TTS，失败仍立即停止。

## TTS 脱敏诊断验证（2026-09-24）

- 先复现各阶段折叠为通用错误、下载端 403/429 被误判、URL 控制字符校验缺口，
  再修改 `protocol.py`、`cloud.py`、`worker.py`，不改变模型、额度、重试和超时边界。
- 7 个固定阶段码以 worker 退出码 26–32 传递，HTTP 与 CLI 保留原错误对象结构。
  不输出 Key、正文、供应商原始错误或签名 URL。
- 测试配对：`test_bridge.py` 18 项、`test_cloud.py` 13 项、
  `test_runtime.py` 16 项；加上边界测试 22 项，共 69 项，exit 0，
  无 ResourceWarning；原 Mock 7 项，exit 0。
- 本轮无真实云请求。真实试听任务仍未完成；没有 Android 构建、设备验收、
  提交推送或 APK 交付，统一门禁缺失状态未解除。

## 受限单段定位与验证（2026-09-24）

- 已新增 `--smoke-tts`：沿用预算、worker 硬期限、阶段码与退出清理；
  只调用一次原创短句 TTS，不调用分析；CLI 成功/失败/缺保护参数均有测试。
- 单段诊断确认音频 URL 安全校验拒绝；追加授权最多 3 次、总正文不超过 100 字。
  第 1 次结构检查确认实际返回主机为 `dashscope-a717.oss-cn-beijing.aliyuncs.com`。
  将此精确主机加入允许列表，保持强制 HTTPS、不转发凭据和禁止重定向。
- 第 2 次 `--smoke-tts` exit 0；产出 2.641542 秒、15444 字节单声道 Opus，
  本地 ffmpeg 完整解码 exit 0。此次额外授权已用 2 次/30 字，剩余 1 次未使用。
- 修复独立审查发现的截断 HTTP、HTTPError 体读取异常、WAV 越界块 RuntimeError，
  以及顶层 FreeTierOnly 被非对象 error 字段干扰的问题，均有失败复现和回归测试。
- 桥接 77 项通过：bridge 19、boundaries 22、cloud 18、runtime 18；
  尚未完成三角色听感、Android 端到端和门禁/交付验收。
