# 百炼试用桥接

在开发电脑上提供 NovelAudioServer v1 六个接口，使用百炼完成章节分析与语音合成。
Android 继续依赖统一服务协议，云厂商 API Key 留在开发电脑。

当前已提供本地服务与独立短篇试听入口。Android 设置页、人物绑定、播放器和持久缓存
仍在开发中，填好 Key 不代表 App 已能完成完整 AI 听书。

## 只需填写的内容

根目录 `novel-audio.local.env` 已准备好，只填写：

```dotenv
DASHSCOPE_API_KEY=你的北京地域百炼通用APIKey
```

不要把 Key 发到聊天、放进命令行参数或提交 Git。
新检出的仓库可运行 `sh scripts/novel-audio-bridge/start.sh --init` 生成空配置；
这个命令不会覆盖已有内容。macOS 启动检查会将配置权限收紧为仅当前用户读写。

模型由程序确定，不需要选择：

- `qwen3.7-plus`：中文章节人物、台词归属与稳定别名建议；关闭思考，限制 JSON 输出。
  使用北京地域 OpenAI 兼容 Chat Completions 接口，单次输出最多 4096 Token。
- `qwen3-tts-instruct-flash`：中文多预置音色朗读；固定普通话指令，
  旁白使用 Ethan，人物匹配其余六个预置音色。

API Key 本身不绑定模型；一个北京地域通用 Key 即可供两个接口使用。
模型权限、免费额度和到期时间仍以用户账号控制台为准，不能使用 Coding Plan 专属 Key 代替。
Key 按 Bearer 令牌字符校验，支持点号等合法字符并原样传递；
本地格式检查通过不代表百炼鉴权已通过。

`qwen3-tts-vd-2026-01-26` 属于声音设计方案，需先创建音色并取得匹配该模型的 voice ID。
当前桥接使用预置音色，不会把现有音色 ID 直接交给 VD 模型，也不自动创建或切换音色。

## 首次检查与试听

运行环境：Python 3.10+ 标准库（无需 pip 安装 SDK）、带 libopus 的 ffmpeg。
本机已经用真实 ffmpeg 编码/解码验证。以下命令均从项目根目录执行。

```sh
# 只检查配置、随机连接口令和本机音频转换能力，不请求百炼
sh scripts/novel-audio-bridge/start.sh --check
```

在百炼电脑端控制台，分别确认 **qwen3.7-plus** 与
**qwen3-tts-instruct-flash** 的“免费额度用完即停”已开启并生效，
且这两个确切模型仍有未过期的免费额度。然后才执行：

```sh
# 一次分析 + 最多三次 TTS，只发送程序自带的原创短文
sh scripts/novel-audio-bridge/start.sh --smoke --free-quota-confirmed
```

生成的三段 Ogg 音频位于 `novel-audio.local.smoke/<时间>/`。
失败会停止并保留已完成文件，不自动重试。可以用支持 Ogg/Opus 的播放器试听。
这项检查验证云端账号、人物归属与三种声音，不等价于 Android 播放或离线验收。
该试听命令会消耗模型额度；启动服务后收到生成请求也会消耗额度。
`--check` 和离线自动测试不请求百炼，自动测试不读取本地真实 Key。

单段故障定位使用独立入口；只发送一次内置原创短句，不重新分析人物：

```sh
sh scripts/novel-audio-bridge/start.sh --smoke-tts --free-quota-confirmed
```

成功保存 `01.ogg`，失败立即停止；命令不会自动重试。执行前仍需确认调用范围及免费保护。

## 启动六接口服务

```sh
sh scripts/novel-audio-bridge/start.sh --serve --free-quota-confirmed
```

服务默认监听 `127.0.0.1:8787`，用 Ctrl+C 停止。macOS 上停止服务会一并终止
在途云工作进程及转码子进程，且拒绝新建工作进程；已发送到供应商的请求仍可能计入用量。
保留现有六个端点：

- `GET /v1/health`
- `POST /v1/chapter/analyze`
- `GET /v1/voices`
- `POST /v1/voices/match`
- `POST /v1/voices/preview`
- `POST /v1/tts/synthesize`

每个端点要求本地桥接 Bearer Token；它由程序随机生成并保存在
`novel-audio.local.state.db`，与百炼 Key 独立，不出现在启动输出中。
`--check` 通过后会自动生成权限受限且不入 Git 的
`novel-audio.local.connection.json`，可在本地打开查看 URL 与桥接 Token；
该文件不包含百炼 Key，无需手动编写或选择连接参数。
Android 设置接线后使用 `adb reverse tcp:8787 tcp:8787` 和根地址
`http://127.0.0.1:8787`，并显式允许本地 HTTP。不要把服务暴露到局域网或公网。

`health` 不调用云模型，ready 只表明本地配置/预算/音频工具准备状态，
不能证明云端 Key 有效、免费额度可用或服务已成功响应。
试听或合成遇到鉴权/限流/协议/超时错误时固定返回错误码，不暴露供应商原始错误正文。

### TTS 失败定位

| 固定错误码 | 已确定的失败阶段 |
|---|---|
| `tts_invalid_response` | TTS 接口响应长度等传输校验不通过 |
| `tts_invalid_json` | TTS 接口返回非法 JSON、UTF-8、重复键或非对象 |
| `tts_missing_audio_url` | 缺失有效的 `output.audio.url` 字符串 |
| `tts_unsafe_audio_url` | 音频地址未通过域名、协议、端口、扩展名或字符校验 |
| `tts_download_failed` | 结果音频下载失败或下载响应长度不合规 |
| `tts_invalid_wav` | 音频不是完整的受支持 PCM WAV，或超出音频限制 |
| `tts_conversion_failed` | ffmpeg 无法启动、失败或未产出合法头部的 Ogg |

模型推理端的鉴权、免费额度、限流与整体超时仍使用原固定码。
音频存储端的 403/429 只归类为下载失败，不视为模型 Key 失效或触发模型配额熔断。
这些码经 worker 退出码传递，HTTP 与试听命令可以保留阶段；不打印原始响应排查。

2026-09-24 首次真实 smoke 的章节分析通过，第一段 TTS 返回旧码
`invalid_cloud_response`，exit 2，未生成可见音频，未重试。
随后获授权的单段请求返回 `tts_unsafe_audio_url`。固定北京 HTTPS 推理端点实际返回
`dashscope-a717.oss-cn-beijing.aliyuncs.com` 的 WAV 地址；该精确主机此前未在白名单中。
补入此主机后，单段命令成功生成约 2.64 秒的单声道 Ogg/Opus，ffmpeg 完整解码 exit 0。
这验证了单段合成、下载和转码，不代表三角色声音效果或 Android 端到端通过。

## 试用限制与费用边界

本地累计上限为分析 20 次/24000 UTF-16 正文字符、TTS 100 次/5000 UTF-16 正文字符。
这不是百炼的账单口径，也不是免费剩余额度查询：
分析的提示词、人物元数据与输出仍可能计入供应商 Token 用量。

- 请求在联网前用 SQLite 事务预占；失败、超时也计数，重启/换日期不重置。
- 收到确切 `AllocationQuota.FreeTierOnly` 后持久停止全部生成请求。
- 普通 429 只报告限流，不误判为免费额度已用完；不自动重试或换模型。
- 同时最多一个生成请求，其他请求返回 429，不建立后台排队。
- 分析每批最多 64 单元/4000 UTF-16 正文字符，人物元数据总请求不超过 32 KiB。
- TTS 单段接受最多 1200 UTF-16 字符，内部至多拆成两个 600 字符云请求；
  合并 PCM 后一次编码为 Ogg/Opus。当前只支持 zh-CN 和 0.5–2.0 倍速。
- 分析硬期限 40 秒，合成含下载/转码 25 秒；macOS 上超时终止云工作进程及子进程组。
- 客户端断开不能退还已经发送到供应商的请求额度；已开始的工作仍受硬期限约束。

**本地程序无法通过通用推理 Key 代查或打开控制台停费开关。**
`--free-quota-confirmed` 是用户确认，不是自动验证证明。
没有开启供应商保护时，即使本地上限很低也可能按量扣费。
不得通过删除状态文件绕过上限或恢复损坏状态；额度调整需单独核对账号余量与授权。

## 安全与正式服务替换

- Key 仅发送到固定北京百炼推理端点，不跟随重定向、不继承系统代理。
- 音频下载不携带 Key，精确允许 `dashscope-result-bj.oss-cn-beijing.aliyuncs.com`、
  `dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com`、
  `dashscope-a717.oss-cn-beijing.aliyuncs.com` 三个结果主机，强制 HTTPS。
- 只接受完整 PCM WAV，重建无元数据 WAV 后传入受限 ffmpeg，不让它处理远端 URL/播放列表。
- 拒绝非法 UTF-8、孤立 surrogate、重复 JSON 键、非有限数、越界 speaker/unit 和不完整分析。
- 云端只返回经过投影的协议字段，正文始终来自 Android；不会保存云端人物数据库。
- `X-TTS-Profile` 含模型、转换版本、声音目录/指令及 FFmpeg 构建指纹。
  稳定模型别名由供应商升级时无法自动获知；须升级桥接 profile 版本并重新验收。
- 本地 env、连接快照、状态 DB 与试听音频均被 Git 忽略。机器文件备份仍需自行保护这些文件。

正式 Windows NovelAudioServer 上线后替换 Android 地址和桥接 Token，沿用六端点；
人物 ID 保留，VoiceBinding 和缓存必须按服务器作用域/profile 隔离并重新绑定。
不同后端不能保证完全相同的声音。

## 离线自动测试

```sh
python3 -W error::ResourceWarning -m unittest discover -s scripts/novel-audio-bridge -p 'test_*.py' -v
python3 -m unittest discover -s scripts/novel-audio-mock -p 'test_*.py' -v
```

覆盖配置、预算事务、协议校验、模拟百炼响应、音频编解码、六端点 loopback HTTP、
命令行防误调用、进程硬期限与 HTTP 生成中 Ctrl+C 的进程组回收。
2026-09-24 TTS 阶段诊断、传输异常（含 urllib 包装超时）、WAV 边界及精确结果主机修复后桥接 78 项通过；
原 Mock 最近一次验证为 7 项通过。
测试中的 cloud 响应是假数据，声音效果需真实试听验证。TTS 失败会固定区分
响应 JSON、音频 URL、下载、WAV 校验和转码阶段，不输出供应商原文、正文、Key 或签名 URL。

官方接口依据（2026-09-24 核对）：

- [免费额度与用完即停](https://help.aliyun.com/zh/model-studio/new-free-quota)
- [Qwen 文本生成](https://help.aliyun.com/zh/model-studio/text-generation)
- [Qwen3.7-Plus 模型能力](https://help.aliyun.com/zh/model-studio/qwen3-7-plus)
- [OpenAI 兼容 Chat Completions](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)
- [Qwen-TTS HTTP API](https://help.aliyun.com/zh/model-studio/qwen-tts-api)
- [预置音色清单](https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list)
- [声音设计与目标模型绑定](https://help.aliyun.com/zh/model-studio/voice-design-user-guide)
