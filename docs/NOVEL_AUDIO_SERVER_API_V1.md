# NovelAudioServer HTTP API v1

协议修订：`1.0.0`（2026-09-23，Android 初版）；URL 主版本：`/v1`。
这是 Android 与开发 Mock 的实现契约，真实 Windows 服务尚未联调。
Windows Agent 应实现本文件；字段意义/必填项/编码的破坏性变化须升级主版本并记录迁移。

## 公共约定

- Server URL 是根地址，可含反向代理前缀，不含 `/v1`、凭据、query 或 fragment。
  Android 会追加 `/v1/...`。
- 全部端点 `Authorization: Bearer <Access Token>`，JSON UTF-8。
  Token 只在 header；不允许 URL 参数、正文或日志携带。
- 客户端不跟随 3xx；服务端必须直接响应。未知 JSON 字段可忽略，缺少必填字段/类型不符拒绝。
  响应必须是有效 UTF-8；任何层级的重复 JSON 键均拒绝，避免版本与身份歧义。
- JSON 请求与响应最大 2 MiB；单段音频最大 16 MiB。空音频拒绝。
- 总超时：health 10 秒，analyze 45 秒，voices/match 15 秒，preview/synthesize 30 秒。
  总超时包含客户端请求队列等待。一个客户端操作仅一次请求，不自动重试，
  包括 `503 + Retry-After: 0`；取消调用即取消传输。
- HTTP 401/403=鉴权失败；429=限流；5xx=服务不可用；其他非 200=请求失败。
  错误响应可包含 `{"error":{"code":"...","message":"..."}}`，客户端不直接展示/记录原文。
- `bookId` 是 Android 逻辑作品身份；`chapterId` 是 Android 物理章节身份，不是裸章节索引。
  `characterId` 在请求所属作品内有效；`voiceAssetId` 是当前服务器内 opaque ID。

## GET /v1/health

```json
{"status":"ok","apiVersion":"1","directorReady":true,"ttsReady":true}
```

四个字段必填。ready=false 表示连通但对应能力未就绪，不代表可开始听书。
`status` 非 `ok` 或 apiVersion 非 `1` 不视为健康。

## POST /v1/chapter/analyze

```json
{
  "bookId":"work-1","chapterId":"chapter-1","textHash":"sha256",
  "analysisVersion":"1",
  "characters":[{"characterId":"char_001","displayName":"萧炎","stableAliases":["炎儿"]}],
  "units":[{"unitId":"u001","text":"萧炎望着老人。"},{"unitId":"u002","text":"“师傅，你终于醒了。”"}],
  "previousContext":{"recentAssignments":[]}
}
```

所有顶层字段必填；characters/recentAssignments 可空，units 非空、ID 唯一且 text 非空。
长章由 Android 串行分块，每块携带已确认人物和有限上下文；仍属于同一 ChapterAnalysis。
recentAssignments 元素为 `{"unitId":"...","speakerId":"..."}`，只作上下文，不计本块覆盖。

```json
{
  "assignments":[{"unitId":"u001","speakerId":"narrator"},{"unitId":"u002","speakerId":"char_001"}],
  "newCharacters":[],
  "aliasUpdates":[{"characterId":"char_001","stableAliases":["炎儿"]}]
}
```

三个数组必填，可以为空；每个本次 unit 必须恰好出现一次。speakerId 只能是 narrator、
请求内 characterId 或本响应 newCharacters.temporaryId，不能引用其他作品人物。
newCharacters 元素：
`{"temporaryId":"tmp_1","displayName":"老人","gender":"unknown","ageRange":"unknown","voicePersona":{"traits":[]}}`。
temporaryId 不与现有 ID/narrator 冲突，不持久化；Android 事务内分配正式 ID并改写归属。
aliasUpdates.characterId 可引用已知或本响应临时 ID。
服务端仅建议稳定别名，Android 仍排除“师父/哥哥/他”等上下文称谓。
不返回或使用改写正文；合成文字始终由 Android 原始 TextUnit 截取。

## GET /v1/voices

```json
{"voices":[{"voiceAssetId":"M017","displayName":"青年男声","gender":"male","ageRange":"young_adult","traits":["清朗"],"previewAvailable":true,"narrator":false}]}
```

`narrator` 为可选布尔字段，省略时按 false 处理。true 标记保留的旁白音色；
手机为旁白优先从 voices 目录选择这些音色，对白使用 voices/match。
本地 voices/match 不返回保留旁白，避免旁白占用对白音色；旧服务未声明该字段时保持原匹配行为。

voices 数组及每个元素除 narrator 外的上述字段必填，ID 唯一。没有音色可返回空数组。
所有元信息为展示/匹配信息，不包含服务器磁盘路径或模型调用字段。

## POST /v1/voices/match

```json
{"voicePersona":{"traits":["清朗"]},"alreadyUsedVoiceIds":["M041"],"optionalConstraints":{"gender":"male","ageRange":"young_adult"}}
```

optionalConstraints 可省略；只能含 gender/ageRange。
返回 `{"candidates":[<与 voices 相同的 VoiceAsset 对象>]}`，候选按服务端推荐序排列。
Android 最终选择并持久化绑定；空候选由上层显式降级，不随机改已有绑定。

## POST /v1/voices/preview

```json
{"voiceAssetId":"M017","text":"这是声音试听。","language":"zh-CN","speed":1.0}
```

## POST /v1/tts/synthesize

```json
{"text":"萧炎望着老人。","voiceAssetId":"M017","language":"zh-CN","speed":1.0}
```

两个音频端点使用相同字段。text/voiceAssetId/language 非空，speed 为有限正数。
建议每段不超过 1200 UTF-16 字符；客户端拒绝更长请求，上层 Segment Builder 负责拆分。
Android `Accept: audio/ogg, audio/mp4, audio/aac`。
返回 HTTP 200 直接音频 body（不返回待轮询 URL），Content-Type 为 Ogg/Opus `audio/ogg`、
AAC/M4A `audio/mp4` 或 ADTS AAC `audio/aac`。
必须有 `X-TTS-Profile`（后端合成行为版本）header；后端换模型/合成语义须更新它。
缓存管线必须保存实际 profile，声音切换不得命中旧版本。
客户端边界检查非空/大小/类型/profile；可解码性和完整章校验由后续缓存层负责。

## 修订记录

- 1.0.0：固定六端点、Bearer、声库 envelope、音频直接响应、profile header、
  身份校验、超时与大小上限；与用户示例相容的必填字段补充在本文件公开定义。
