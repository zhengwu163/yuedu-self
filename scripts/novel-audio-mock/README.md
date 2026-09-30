# NovelAudioServer v1 开发 Mock

仅用于 Android 通信契约和故障验证，不加载 LLM/TTS 模型。正式服务接口见
[`docs/NOVEL_AUDIO_SERVER_API_V1.md`](../../docs/NOVEL_AUDIO_SERVER_API_V1.md)。

## 运行与测试

在仓库根目录运行（Python 3 标准库，无额外依赖）：

```sh
python3 -m unittest discover -s scripts/novel-audio-mock -p 'test_*.py' -v
NOVEL_AUDIO_MOCK_TOKEN=local-dev-only python3 scripts/novel-audio-mock/server.py --port 8787
```

`local-dev-only` 仅为本地测试口令，不能用于正式部署。服务只监听 `127.0.0.1`；
不记录请求 URL、Token 或正文。进程前台运行，按 Ctrl+C 停止。

未指定音频时，`/v1/health` 返回 `directorReady=true, ttsReady=false`，
合成和试听返回 503。章节分析按输入顺序生成确定性旁白/人物分配，
不识别故事语义。声库匹配仅将未使用声音排在前面。

要测试音频传输，可明确传入有使用权的本地 Ogg/Opus、M4A 或 AAC 文件：

```sh
NOVEL_AUDIO_MOCK_TOKEN=local-dev-only python3 scripts/novel-audio-mock/server.py \
  --port 8787 --audio "/absolute/path/to/fixture.ogg"
```

单文件须非空且不超过 16 MiB。两个音频端点原样返回同一文件，
带 `X-TTS-Profile: mock-fixture-v1`；不代表请求文字已被合成。
格式按扩展名声明，可解码性仍需播放器/缓存层检查，不能直接标记完整章可离线。

## 故障注入

```sh
NOVEL_AUDIO_MOCK_TOKEN=local-dev-only python3 scripts/novel-audio-mock/server.py --fault 429
NOVEL_AUDIO_MOCK_TOKEN=local-dev-only python3 scripts/novel-audio-mock/server.py --delay 12
```

- `--fault` 支持 401、429、500、503；鉴权后每个端点都返回指定错误。
- `--delay` 支持 0–60 秒，可验证客户端超时与取消。
- Token 缺失或不匹配时所有端点均返回 401。

## Android 验证入口

无需启动 Python Mock，JUnit 自行启动 NanoHTTPD loopback 服务：

```sh
sh ./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.help.readaloud.server.*' --no-configuration-cache
```

后续接入设备配置页时可使用 `adb reverse tcp:8787 tcp:8787`，Android 根地址填写
`http://127.0.0.1:8787`，不要追加 `/v1`。当前批次只有通信客户端；
连接配置页、播放接线和离线缓存尚未完成。
