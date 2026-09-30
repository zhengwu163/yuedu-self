# Android AI 多角色听书架构

本方案按 2026-09-23 用户提供的 NovelAudioServer 边界实施。本文描述目标，
实际完成度记录在 `AI_AUDIOBOOK_PROGRESS.md`。接口唯一来源为
[NovelAudioServer v1 契约](NOVEL_AUDIO_SERVER_API_V1.md)。

## 职责与复用

- Android 保留书架、书源、阅读器、正文、稳定人物 ID、声音绑定、音频缓存与播放。
- 新 AI 听书的章节归因、声库、选音候选和语音合成都只调用 NovelAudioServer。
  Android 不依赖模型名称、Windows 路径或服务端推理框架。
- 保留既有普通 HTTP/JS/系统朗读及通用 AI 功能。新管线未接通前不自动切换用户现有引擎。
- 本地 `ReadAloudRolePreprocessor`、`BookIdentity`/`BookCharacter`、
  `ReadAloudSpeechPlanner`、`TtsSynthesizer` 和 Media3 是增量复用点。
  其中当前 SpeechPlan 仅驱动面板，接入实际 Service 是独立待完成步骤。
- 沿用“同名＋作者”的逻辑作品共享人物；音频按物理章节、正文和真实合成参数隔离。

## 数据与播放

`Book/Chapter → ChapterTextSnapshot → TextUnit → /chapter/analyze → 本地校验 →
CharacterRegistry → VoiceBinding → AudioSegment → /tts/synthesize → 持久缓存 → 既有播放器`。

正文以排版前最终处理文本为准，UTF-16 范围必须可回拼。服务端只返回 unit 与 speaker
归属；本地分配正式人物 ID。临时 ID 只在一次分析响应内有效；上下文称谓不自动变为永久别名。
旁白固定 `narrator`，拥有独立绑定。角色绑定 opaque `voiceAssetId`，不保存服务端路径。

分析缓存按物理章节、textHash、analysisVersion 隔离。音频身份另含服务器作用域、
speaker、voiceAssetId、绑定版本、ttsProfile、合成速度与格式。
不同服务器不能仅因使用同一个 voiceAssetId 而共享声音缓存。

## 网络与安全

客户端复用项目 OkHttp/Gson 依赖；采用独立、标准证书校验的客户端配置：
现有书源客户端允许不安全 TLS 且有 URL/正文诊断拦截器，不适合携带家庭服务 Token。
不继承该配置、不修改原书源网络行为。只向用户填写的 base URL 发请求，不跟随重定向，
错误不回显请求正文、Token 或服务端原始错误文本。所有请求有总超时和响应体大小上限；
协程取消必须取消 HTTP 调用。

连接配置安全底座使用独立 Keystore AES-GCM 密钥，
将 URL/Token 配对密文原子存入 noBackupFilesDir，Token 不进入普通备份、角色 JSON 或日志。
客户端持有固定配对快照，更换地址必须重新提供 Token，不动态读取其他服务器的凭据。
页面接线和设备端 Keystore/备份验收尚未完成。
局域网 HTTP 为显式用户选择，需提示明文传输风险；推荐 HTTPS 或可信 VPN。
不静默导入通用 AI API Key；临时云模型的厂商 Key 留在兼容服务端。

## 缓存与失败

自动预生成仍固定为主动播放当前书之后最多 3 个完整章节，无数量设置。
暂停/停止/换书撤销 AUTO；系统恢复与进程重启不签发授权。手动下载独立保留。
manifest、音频完整校验、离线禁网、缺段恢复与 PINNED 保护继续遵守一期离线合同。

服务不可用时提供重试/普通朗读。客户端每次操作只发一次请求；上层任务以后统一管理
最多一次重试及退避，禁止客户端和队列叠加无限重试。分析失败可降级旁白；
音频失败不能以空音频、静音或 Mock 提示音计为完整下载。

## 验证边界

用户服务尚未部署：暂缓真实服务成功联调，不伪报通过。
本地契约响应、鉴权失败、版本不兼容、超时、取消、坏 JSON、坏媒体类型必须工程验证。
开发 Mock 只返回确定性演示归属与测试音频，不提供另一套正式 AI。
Mac 启动入口只安装 debug 包、不清用户数据；正式 APK 交付仍走既有规定链路。
