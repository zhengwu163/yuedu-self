# 临时云模型联调可行性

候选资料核查日期：2026-09-23；当前接入更新：2026-09-24。
公开资料与本地检查不代表用户账号的免费余量或真实模型调用已验证。

## 建议路线

Android → 开发电脑上的 NovelAudioServer 兼容适配服务 → 官方文本分析/TTS API。

App 继续使用现有六个 `/v1` 接口。临时服务不加载本地大模型，
只负责转接分析、维护音色目录、将云音频转成契约支持的 Ogg/Opus 或 M4A，
并提供真实 `X-TTS-Profile`。云厂商 API Key 留在开发电脑，不写入 Android。

正式 Windows 服务准备好后替换地址与访问令牌，并运行相同的契约/设备测试。
本地人物 ID 和别名保留；声音绑定按服务器作用域重新选择，不能承诺跨厂商完全同声。
已完整下载的旧音频可按原 manifest 保留，新合成不得错误命中旧服务器/profile 缓存。

## 当前接入

用户已填写本地 API Key，并提供 `qwen3.7-plus` 与 TTS 候选。
兼容桥接位于 `scripts/novel-audio-bridge/`，当前采用：

- `qwen3.7-plus`：北京 OpenAI 兼容 Chat Completions，关闭思考、JSON 输出、4096 Token 上限。
- `qwen3-tts-instruct-flash`：按用户允许的备选，使用现有预置音色进行试听。

`qwen3-tts-vd-2026-01-26` 需先通过声音设计服务创建音色，
再用返回的 voice ID 合成，不能直接替换现有预置音色模型。
该方案需额外的音色资产保存、模型绑定和费用核验，当前没有调用或自动切换到它。

六端点、持久预算、超时和原创试听入口已实现。TTS 分阶段脱敏诊断补齐后，
桥接 78 项离线测试通过，含 urllib 包装超时分类回归；原 Mock 7 项通过。
本地配置及音频工具 `--check` 通过。
首次真实试听已在用户确认 **qwen3.7-plus 与 qwen3-tts-instruct-flash**
各自有有效免费额度且“免费额度用完即停”已生效后执行。
操作步骤见 [桥接使用说明](../scripts/novel-audio-bridge/README.md)。

### 首次真实试听结果（2026-09-24）

- 只发送程序内原创三角色短文；配置读取和本地音频转换检查通过。
- 章节分析请求通过；第一段 TTS 后返回 `invalid_cloud_response`，命令 exit 2，
  未生成可见音频，失败即停止，没有重试、换模型或继续发送后续两段。
- 该结果只能确认失败发生在 TTS 阶段，不能区分 TTS JSON、结果 URL、音频下载、
  WAV 媒体或转码原因。此前实现没有足够细的固定诊断，已在离线代码中补齐。
- 后续真实请求按单独确认的调用范围执行；诊断只返回固定阶段码，不输出 Key、正文、
  供应商原始错误或签名 URL。

### 单段修复验证（2026-09-24）

获授权的一次单段诊断返回 `tts_unsafe_audio_url`。随后额外授权最多 3 次、
合计不超过 100 字的原创短句 TTS；已用 2 次、共 30 个正文字符，无新文本分析。
固定北京 HTTPS 推理响应使用 `dashscope-a717.oss-cn-beijing.aliyuncs.com` 结果主机，
补齐该精确白名单并保持强制 HTTPS 后，`--smoke-tts` 成功。
产物为约 2.64 秒单声道 Ogg/Opus，ffmpeg 完整解码通过；三角色听感尚未验收。
其余一次额度未使用；不得将此受限授权视为整章或自动预缓存的云调用授权。

## 已核实的三个候选（历史调研）

| 方案 | 官方免费条件 | 适用性与限制 |
|---|---|---|
| 阿里云百炼文本分析 + Qwen-TTS | 北京地域部分模型有新人额度，通常有效 90 天；`qwen3-tts-instruct-flash` 定价表列 1 万计费字符 | 国内联调优先候选。中文多预置音色；试用不是永久免费，中文计费字符不能等同中文字数，具体余量须查用户账号 |
| Gemini 文本模型 + Flash Preview TTS | `gemini-2.5-flash-preview-tts` 标准接口输入/输出均列 Free of charge | 支持普通话与 30 个声音；有限配额，需支持地区及用户 Key。免费档数据可用于产品改进，仅用原创虚构测试文本 |
| 文本分析服务 + Azure Speech F0 | Neural TTS 每月 0.5 million 字符 | TTS 官方重复月额度，支持 Ogg/Opus；需能创建 F0 资源及 Key，另外需要文本分析服务 |

Gemini 官方支持地区清单未列中国大陆，不能当作所有国内用户都能开箱使用的默认方案。
第三方博客的“无限免费”和未经确认的演示接口不作为产品依赖。
开放模型权重也不等于托管推理 API 免费。

## 实际接入前的条件

1. 用户确认可用账号，自己接受服务条款；不代用户注册、认证、绑卡或开通计费。
2. 若选百炼，确认**指定文本和 TTS 模型**在北京地域仍有免费额度、尚未过期，
   且“免费额度用完即停”已生效。默认或已认证账号不能假定自动停费。
3. Key 通过本机环境变量或权限受控配置提供，不粘贴聊天、不提交仓库、不打印日志。
4. 先用原创短章节跑旁白+两个人物及跨章复现；估算额度并限制请求数/并发，
   不用免费测试账号自动下载整本小说。
5. 云端拒绝、超时、配额不足时有界失败；不自动切换付费模型。

临时云服务已完成本地实现、离线验证和单段真实音频解码；Android 播放与缓存接线尚未验收。
用户真实书籍正文上传云端的许可与供应商数据条款需在正式使用前说明并确认。
公网真实音频端到端、离线三章、暂停/恢复、进程重启都是独立验收项，Mock 不能替代。

## 官方依据

- [百炼新人额度与用完即停](https://help.aliyun.com/zh/model-studio/new-free-quota)
- [百炼模型价格，含 Qwen-TTS 免费量与字符计数](https://help.aliyun.com/zh/model-studio/model-pricing)
- [Qwen 非实时语音合成](https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide)
- [Qwen3.7-Plus](https://help.aliyun.com/zh/model-studio/qwen3-7-plus)
- [OpenAI 兼容 Chat Completions](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)
- [声音设计与目标模型绑定](https://help.aliyun.com/zh/model-studio/voice-design-user-guide)
- [Gemini API 定价](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini 语音生成与声音/语言](https://ai.google.dev/gemini-api/docs/speech-generation)
- [Gemini 支持地区](https://ai.google.dev/gemini-api/docs/available-regions?hl=zh-cn)
- [Azure Speech F0 定价](https://azure.microsoft.com/en-us/pricing/details/speech/)
- [Azure TTS REST 音频格式与鉴权](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech)
