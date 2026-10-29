# AI 听书 Windows 基准结果

测量日期：2026-10-29。**性能测量进行中，以下只列已完整验证的结果；其余方案继续串行运行。**

## 已核验的结论

- 已完成：Qwen 串行 HTTP；VS OmniVoice 16步 三声。
- 三章热态 RTF 均 ≤ 0.5：VS OmniVoice 16步 三声。
- 用户已试听相同原文、音量对齐的 A/B，选择 **A：现有 Qwen3-TTS 更适合听书**，B 为 VoiceStudio OmniVoice。此偏好只覆盖该段试听，不冒充全章发音验收；默认生产音色未切换。
- 单凭快速生成不能证明音质、漏读或多音字通过。下表多音字正确数尚未逐项听审，明确保持未完成，未用文件解码或 ASR 文本替代听审。

## 环境、版本与边界

- Windows 原生，RTX 5090 D v2 / 24,455 MiB，驱动 610.88；全程本地推理，无在线模型 API。
- 测试前确认 ComfyUI 队列空闲，结束明确识别的 ComfyUI 进程；计时运行不与其共用显存。没有按 python.exe 名称批量结束进程。
- 源码主线 `3fc6302e`，原创三章 `7f29ef24`；新分支 `perf/qwen-batching`，只推送 `github-fork`。现有服务来自 `495c3d3b`，其 `qwen_backend.py` 与主线逐字相同。
- 基线：Qwen3-TTS 12Hz 1.7B Base；qwen-tts 0.1.1 / Transformers 4.57.3 / PyTorch 2.9.1+cu130 / bf16。现有适配器未指定 attention implementation。
- [VoiceStudio v0.5.7](https://github.com/debpalash/VoiceStudio/tree/beeaae08204f0de5f49d4a087ef2b0ea8fecfcb9)：commit `beeaae08204f0de5f49d4a087ef2b0ea8fecfcb9`。源码后端在 Windows 本机启动、健康接口识别 CUDA；桌面 GUI 未安装验收。通过其原生 engine adapter 筛选，无源码修改，无接入听书服务。
- VS OmniVoice：权重 revision `c5fdb5ccb189668d56333f77ba2629f4cd7535f4`，13 文件按现有清单回读，3 个已提供 SHA 的文件匹配；fp16、16 步、guidance 2、参考提示缓存。用户明确许可本地家庭非商用及 tokenizer 附加条款。
- VS VoxCPM2：voxcpm 2.0.3，权重 revision `32279effe8c19989596f05d353d1447f51d9e915`，文件逐个 SHA-256 存档；bf16、16 步、guidance 2，未启用 Triton/torch.compile。两个 VS 引擎均关闭 denoise，使用现有三份参考录音，不训练新声音。
- [faster-qwen3-tts](https://github.com/andimarafioti/faster-qwen3-tts/tree/b35fdc7d71eb3e4d19e63f4b4a330a1aef31d682)：0.5.4 / commit `b35fdc7d71eb3e4d19e63f4b4a330a1aef31d682`；独立环境 qwen-tts-hf 0.1.1.post1、Transformers 5.15.1，bf16 / SDPA。未把 qwen-tts-hf 与官方 qwen-tts 放在同一环境。
- 既有 `/v1` 字段含义与 `api.py` 未改，未合并或修改 `feat/full-book-jobs`。批量与 graphs 属于自有适配器/编码链的实验入口，尚未让手机自动走批量 HTTP，也未改变当前服务默认。

## 口径

1. 三章原文件保持不变。基线、batch 4/8、graphs50 构成最多 50 个原文字符、旁白固定音色的主对照，按句末、逗号等切分；graphs-long300 是最多 300 字的另一组，不能把其变化全部归因于 graphs。VS 三声使用固定 `roles.json` 拆出直接引语，叙述、术语引号、转述用旁白，存在更多短段。不同切分及角色数量会影响速度。输入字符和各段索引均回读验证。
2. 基线每章从已卸载状态 acquire；cold 保留加载时间，warm 在独立 acquire 后短句预热。每章持有同一租约、逐段串行，release 后卸载。统一控制入口首轮 acquire 曾超时、0 段生成，该失败保留且不计入速度；有效基线直连同一现有 Agent 的本机原生端口 8788。
3. `合成 s` 为每次引擎调用到完整音频返回之和，含现有 Opus 编码；`RTF = 合成 s / 最终 WAV 实际时长`。cold 另列 `(合成+acquire)/实际时长`。逐段校验和整章拼接另记录在原始清单，不混作模型速度。
4. 本地加速/VS 热态先完整预热所有实际角色及实际 batch size；每个方案的模型初始化、冷短句首音单独保存在 `settings.json`，不冒充完整章冷态测速。首段指完整首段/首批可返回的时刻，不是流式第一个采样点。
5. 显存为 NVML 100ms 采样的**整卡已用峰值**，含桌面及 CUDA 上下文；实验另存 PyTorch peak allocated。WDDM 单进程显存不可得，不能把整卡峰值称为模型净占用。
6. Qwen batch 4/8 是单次 generate 接收多段与对应提示，并保持输出顺序；没有用多线程冒充 batch。graphs 与 batch 分开测，未声称已组合。300字长段克隆显式 `non_streaming_mode=True`。

## 完整章实测

| 方案 | 章 / 状态 | 合成 s | 音频 s | RTF | 含冷加载 RTF | 显存峰值 MiB | 首段 s | 多音正确 / 总数 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Qwen 串行 HTTP | ch01 / cold | 1087.45 | 655.36 | 1.6593 | 1.6900 | 9574.3 | 37.123 | 未听审 / 13 |
| Qwen 串行 HTTP | ch01 / warm | 1075.65 | 651.68 | 1.6506 | — | 9177.5 | 10.962 | 未听审 / 13 |
| Qwen 串行 HTTP | ch02 / cold | 1083.05 | 652.40 | 1.6601 | 1.6895 | 9344.1 | 37.741 | 未听审 / 10 |
| Qwen 串行 HTTP | ch02 / warm | 1062.00 | 646.32 | 1.6432 | — | 8727.2 | 11.420 | 未听审 / 10 |
| Qwen 串行 HTTP | ch03 / cold | 1052.27 | 633.68 | 1.6606 | 1.6908 | 9429.0 | 38.442 | 未听审 / 9 |
| Qwen 串行 HTTP | ch03 / warm | 1044.08 | 636.72 | 1.6398 | — | 9003.4 | 12.192 | 未听审 / 9 |
| VS OmniVoice 16步 三声 | ch01 / warm | 99.21 | 676.91 | 0.1466 | — | 5892.5 | 0.681 | 未听审 / 13 |
| VS OmniVoice 16步 三声 | ch02 / warm | 92.00 | 660.11 | 0.1394 | — | 5892.7 | 0.608 | 未听审 / 10 |
| VS OmniVoice 16步 三声 | ch03 / warm | 96.62 | 653.21 | 0.1479 | — | 5893.5 | 0.621 | 未听审 / 9 |
| VS VoxCPM2 16步 三声 | ch01 / warm | 978.26 | 755.25 | 1.2953 | — | 10775.0 | 8.072 | 未听审 / 13 |

## 长段检查与质量边界

- 补充原段来自各章，长度 154 / 126 / 111 字，单次传入，不是完整章。已验证补充输出 0 条。原始结果可查看各 `vs-*-long/long*/result.json`。
- 基线 518 段、OmniVoice 三声 485 段已做 SHA、覆盖和最终 PCM 回读；初次能量检查无非有限采样或明显削波。低能量片段核对原文后均为单独结束引号，不能记作正文漏读。
- 多音字分母：每章仅取每个上下文类别的首次出现，姓名、姓氏与多音字从 fixtures README 提取，共 13 / 10 / 9 项。数字单位和英文另外检查，不混入该分母。**正确数尚未完成听审**。
- 听感：Qwen 基线获本次 A/B 偏好，保留为质量参照；OmniVoice 速度通过，但用户仍偏好 Qwen，暂不替换默认；VoxCPM2、Qwen batch 与 graphs 的听感以匹配试听结果回填，不预先声称无下降。

## 后台每章时间

以下是本次原文的纯合成时间，冷启动再加对应加载；不外推为整书已验收：

| 方案（热态） | ch01 | ch02 | ch03 |
|---|---:|---:|---:|
| Qwen 串行 HTTP | 17.93 分钟 | 17.70 分钟 | 17.40 分钟 |
| VS OmniVoice 16步 三声 | 1.65 分钟 | 1.53 分钟 | 1.61 分钟 |

## 试听音频与复现

目录以仓库根为基准，均仅在本机，已被 `.gitignore` 排除：

- `bench-output/20261029/baseline-native/ch01-cold/` 等六个目录：`chapter.wav`、逐段 OGG、`result.json`。
- `bench-output/20261029/vs-omni-roles-v2/ch01/`、`vs-vox-roles/ch01/` 等：三声整章。
- `bench-output/20261029/qwen-batch4/`、`qwen-batch8/`、`qwen-graphs50/`、`qwen-graphs-long300/`：各章与冷短句。
- `bench-output/20261029/listening/`：同文、目标 -20 LUFS 的 A/B；`user-choice.json` 保存本次用户偏好；原章音频未覆盖。
- `bench-output/20261029/verified-summary.json`：回读后的实测摘要；公开精简副本见 `bench-data/2026-10-29.json`，不含个人路径、模型或令牌。

脚本入口为 `scripts/novel-audio-bench/bench.py`（现有 HTTP）、`local_bench.py`（原生引擎实验）、`verify_results.py`（拒绝缺段、改文、改音频与伪造完成标记）。参数中的配置和产物根由本机指定；原始参考录音、参考文字、环境安装日志、模型文件及令牌不提交。

验证：服务测试 336 项通过，19 项环境跳过；基准与证据测试 10 项通过。提交门禁通过，推送前敏感值与个人路径扫描 0 命中。硬件实测与完整章文件回读仍是主要证据，单元测试不替代质量试听。
