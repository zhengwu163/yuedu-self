# AI 听书 Windows 基准结果

测量日期：2026-10-29。**性能测量已完成；听感与读音验收见下文。**

## 已核验的结论

- 已完成：Qwen 串行 HTTP；VS OmniVoice 16步 三声；VS VoxCPM2 16步 三声；Qwen batch 4；Qwen batch 8；Qwen CUDA graphs 50字；Qwen CUDA graphs 300字 NSM。
- 三章热态 RTF 均 ≤ 0.5：VS OmniVoice 16步 三声；Qwen batch 8；Qwen CUDA graphs 50字；Qwen CUDA graphs 300字 NSM。
- 用户先在 A/B 选择 A（现有 Qwen）优于 B（OmniVoice），再比较 C（VoxCPM2）后选择 **C 更好**，本轮短样本偏好为 C > A > B。偏好只覆盖该段试听，不冒充全章发音验收；默认生产音色未切换。
- 加速同文试听：用户选择 **E（Qwen batch 8）可接受，F（CUDA graphs 50）较差**。因此实时路线优先 batch 8，graphs 暂不推进；未声称加速都没有音质下降。
- 单凭快速生成不能证明音质、漏读或多音字通过。下表多音字正确数尚未逐项听审，明确保持未完成，未用文件解码或 ASR 文本替代听审。听感反馈更新：2026-10-30。

## 环境、版本与边界

- Windows 原生，RTX 5090 D v2 / 24,455 MiB，驱动 610.88；全程本地推理，无在线模型 API。
- 测试前确认 ComfyUI 队列空闲，结束明确识别的 ComfyUI 进程；计时运行不与其共用显存。没有按 python.exe 名称批量结束进程。
- 源码主线 `3fc6302e`，原创三章 `7f29ef24`；新分支 `perf/qwen-batching`，只推送 `github-fork`。现有服务来自 `495c3d3b`，其 `qwen_backend.py` 与主线逐字相同。
- 基线：Qwen3-TTS 12Hz 1.7B Base；qwen-tts 0.1.1 / Transformers 4.57.3 / PyTorch 2.9.1+cu130 / bf16。现有适配器未指定 attention implementation。
- [VoiceStudio v0.5.7](https://github.com/debpalash/VoiceStudio/tree/beeaae08204f0de5f49d4a087ef2b0ea8fecfcb9)：commit `beeaae08204f0de5f49d4a087ef2b0ea8fecfcb9`。源码后端在 Windows 本机启动、健康接口识别 CUDA；桌面 GUI 未安装验收。通过其原生 engine adapter 筛选，无源码修改，无接入听书服务。
- VS OmniVoice：权重 revision `c5fdb5ccb189668d56333f77ba2629f4cd7535f4`，13 文件按现有清单回读，3 个已提供 SHA 的文件匹配；fp16、16 步、guidance 2、参考提示缓存。用户明确许可本地家庭非商用及 tokenizer 附加条款。
- VS VoxCPM2：voxcpm 2.0.3，权重 revision `32279effe8c19989596f05d353d1447f51d9e915`，文件逐个 SHA-256 存档；bf16、16 步、guidance 2，未启用 Triton/torch.compile。两个 VS 引擎均关闭 denoise，使用现有三份参考录音，不训练新声音。
- 本轮安装并实测以上两个中文克隆引擎。没有把其他未测试引擎记成安装失败；没有出现尝试半天仍无法安装而跳过的引擎。VS 仅原生后端通过，完整桌面应用安装仍未验收。
- [faster-qwen3-tts](https://github.com/andimarafioti/faster-qwen3-tts/tree/b35fdc7d71eb3e4d19e63f4b4a330a1aef31d682)：0.5.4 / commit `b35fdc7d71eb3e4d19e63f4b4a330a1aef31d682`；独立环境 qwen-tts-hf 0.1.1.post1、Transformers 5.15.1，bf16 / SDPA。未把 qwen-tts-hf 与官方 qwen-tts 放在同一环境。
- 两次 graphs 实测日志均出现 predictor 与 talker 的 CUDA graph capture 成功记录；固定源码的生成路径实际调用 graph replay。启动时提示缺少可选 SoX，未阻止本轮生成，音频均通过 FFmpeg 解码；未把该提示隐藏为安装全功能通过。
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
| Qwen batch 4 | ch01 / warm | 332.66 | 655.60 | 0.5074 | — | 16125.5 | 14.741 | 未听审 / 13 |
| Qwen batch 4 | ch02 / warm | 339.13 | 638.72 | 0.5310 | — | 16137.0 | 17.222 | 未听审 / 10 |
| Qwen batch 4 | ch03 / warm | 335.77 | 633.36 | 0.5301 | — | 16151.0 | 13.920 | 未听审 / 9 |
| Qwen batch 8 | ch01 / warm | 183.90 | 646.08 | 0.2846 | — | 19474.7 | 16.140 | 未听审 / 13 |
| Qwen batch 8 | ch02 / warm | 188.55 | 642.64 | 0.2934 | — | 20980.2 | 16.556 | 未听审 / 10 |
| Qwen batch 8 | ch03 / warm | 193.84 | 638.72 | 0.3035 | — | 20980.5 | 16.647 | 未听审 / 9 |
| Qwen CUDA graphs 300字 NSM | ch01 / warm | 163.37 | 596.08 | 0.2741 | — | 9391.7 | 12.554 | 未听审 / 13 |
| Qwen CUDA graphs 300字 NSM | ch02 / warm | 160.93 | 586.56 | 0.2744 | — | 9386.9 | 14.244 | 未听审 / 10 |
| Qwen CUDA graphs 300字 NSM | ch03 / warm | 158.79 | 578.48 | 0.2745 | — | 9386.8 | 14.676 | 未听审 / 9 |
| Qwen CUDA graphs 50字 | ch01 / warm | 185.02 | 617.36 | 0.2997 | — | 9130.4 | 1.661 | 未听审 / 13 |
| Qwen CUDA graphs 50字 | ch02 / warm | 184.39 | 611.04 | 0.3018 | — | 9332.3 | 1.832 | 未听审 / 10 |
| Qwen CUDA graphs 50字 | ch03 / warm | 175.31 | 586.40 | 0.2990 | — | 9332.4 | 1.924 | 未听审 / 9 |
| VS OmniVoice 16步 三声 | ch01 / warm | 99.21 | 676.91 | 0.1466 | — | 5892.5 | 0.681 | 未听审 / 13 |
| VS OmniVoice 16步 三声 | ch02 / warm | 92.00 | 660.11 | 0.1394 | — | 5892.7 | 0.608 | 未听审 / 10 |
| VS OmniVoice 16步 三声 | ch03 / warm | 96.62 | 653.21 | 0.1479 | — | 5893.5 | 0.621 | 未听审 / 9 |
| VS VoxCPM2 16步 三声 | ch01 / warm | 978.26 | 755.25 | 1.2953 | — | 10775.0 | 8.072 | 未听审 / 13 |
| VS VoxCPM2 16步 三声 | ch02 / warm | 896.13 | 750.15 | 1.1946 | — | 10369.9 | 8.362 | 未听审 / 10 |
| VS VoxCPM2 16步 三声 | ch03 / warm | 893.10 | 748.15 | 1.1937 | — | 10406.9 | 8.730 | 未听审 / 9 |

## 独立冷启动短句

冷短句为「清晨的钟声从远处传来。」；初始化时间包含在冷短句返回时间内。VS 延迟加载权重，因此其初始化很短，不能拿该列冒充模型加载速度。这里的冷短句不等同于完整三章冷态测试。

| 方案 | 初始化 s | 冷短句完整返回 s | 冷短句显存峰值 MiB |
|---|---:|---:|---:|
| Qwen batch 4 | 5.355 | 10.004 | 7828.6 |
| Qwen batch 8 | 19.932 | 24.781 | 7829.1 |
| Qwen CUDA graphs 50字 | 19.236 | 21.065 | 8309.8 |
| Qwen CUDA graphs 300字 NSM | 5.318 | 7.157 | 8318.9 |
| VS OmniVoice 16步 三声 | 0.082 | 7.110 | 5778.5 |
| VS VoxCPM2 16步 三声 | 0.008 | 18.718 | 9874.9 |

## 长段检查与质量边界

- 补充原段来自各章，长度 154 / 126 / 111 字，单次传入，不是完整章。已验证补充输出 6 条。原始结果可查看各 `vs-*-long/long*/result.json`。

| 引擎 | 原段 | 合成 s | 音频 s | RTF | 峰值 MiB |
|---|---|---:|---:|---:|---:|
| vs-omnivoice-long | long01 | 1.78 | 32.72 | 0.0543 | 5934.1 |
| vs-omnivoice-long | long02 | 1.09 | 27.04 | 0.0402 | 6392.6 |
| vs-omnivoice-long | long03 | 1.05 | 23.62 | 0.0445 | 6392.8 |
| vs-voxcpm2-long | long01 | 38.60 | 32.96 | 1.1711 | 11121.8 |
| vs-voxcpm2-long | long02 | 31.82 | 28.00 | 1.1365 | 11124.8 |
| vs-voxcpm2-long | long03 | 32.96 | 28.64 | 1.1509 | 11125.1 |

- 24 条完整章和 6 条长段全部通过输入覆盖、段序、SHA 与最终 PCM 回读。完整章共 2301 段（基线 518，两个 VS 各 485，batch4/8/graphs50 各 259，graphs300 共 36），能量检查无非有限采样或超过 0.1% 的削波。低能量片段核对原文后均为单独结束引号，不能记作正文漏读。
- 多音字分母：每章仅取每个上下文类别的首次出现，姓名、姓氏与多音字从 fixtures README 提取，共 13 / 10 / 9 项。数字单位和英文另外检查，不混入该分母。**正确数尚未完成听审**。
- 长段 6/6 调用成功并输出有效音频，尚不能据此认定没有漏字、重复或拖音；长句听觉稳定性仍待听审。没有用 ASR 的汉字转写给同形多音字判分。
- 各方案可直接定位的逐项听审表见 [AI_AUDIOBOOK_LISTENING_CHECKLIST.csv](AI_AUDIOBOOK_LISTENING_CHECKLIST.csv)，共 256 行，空白正确栏需要人工填写。片段内逐字听辨；CSV 的相对音频路径以 docs 目录为基准。

## 听感结论与下一步建议

| 方案 | 听感证据与结论 | 建议 |
|---|---|---|
| Qwen 串行 | 用户认为优于 OmniVoice；短样本逊于 VoxCPM2 | 保留参照；原速仅适合预生成 |
| VS OmniVoice | 用户在相同原文试听中偏好 Qwen | 速度达标，作为备选；暂不切默认 |
| VS VoxCPM2 | 用户明确选择 C 更好，本轮短样本最受偏好 | 进入后台生成的质量候选；每章约 14.9–16.3 分钟 |
| Qwen batch 4 | 同文 D 已导出，尚未获得听感判定 | RTF 0.507–0.531，未达实时线，不优先 |
| Qwen batch 8 | 用户试听同文 E 后明确表示可接受 | 优先进入边听边生成的下一轮验收；整卡峰值约 20.5 GiB、首批约 16 秒，需要预缓冲 |
| Qwen graphs 50 | 用户试听同文 F 后明确表示较差，不能通过本轮音质门槛 | 暂不推进；即使首段 1.66–1.92 秒且速度达标，也不据此切换 |
| Qwen graphs 300 NSM | 长段完整章已导出，听感未验收 | 速度达标，首段 12.55–14.68 秒；优先评估后台长段用途 |

性能结论：已有多个方案达到 RTF ≤ 0.5，无需据原始 Qwen 的速度否定边听边生成。结合用户听感，实时候选选 batch 8，后台质量候选选 VoxCPM2。batch 8 吞吐达标，但首批约 16 秒、显存余量有限；本轮只测 TTS，未测 9B 角色分析共存、手机传输及混合声线 batch 的整条产品链。以上是进入下一步的建议，尚不是生产切换结论。**多音字正确数、长句人耳稳定性仍待听审，不能宣称整个任务全部验收。**

## 后台每章时间

以下是本次原文的纯合成时间，冷启动再加对应加载；不外推为整书已验收：

| 方案（热态） | ch01 | ch02 | ch03 |
|---|---:|---:|---:|
| Qwen 串行 HTTP | 17.93 分钟 | 17.70 分钟 | 17.40 分钟 |
| VS OmniVoice 16步 三声 | 1.65 分钟 | 1.53 分钟 | 1.61 分钟 |
| VS VoxCPM2 16步 三声 | 16.30 分钟 | 14.94 分钟 | 14.88 分钟 |
| Qwen batch 4 | 5.54 分钟 | 5.65 分钟 | 5.60 分钟 |
| Qwen batch 8 | 3.06 分钟 | 3.14 分钟 | 3.23 分钟 |
| Qwen CUDA graphs 50字 | 3.08 分钟 | 3.07 分钟 | 2.92 分钟 |
| Qwen CUDA graphs 300字 NSM | 2.72 分钟 | 2.68 分钟 | 2.65 分钟 |

## 试听音频与复现

目录以仓库根为基准，均仅在本机，已被 `.gitignore` 排除：

- `bench-output/20261029/baseline-native/ch01-cold/` 等六个目录：`chapter.wav`、逐段 OGG、`result.json`。
- `bench-output/20261029/vs-omni-roles-v2/ch01/`、`vs-vox-roles/ch01/` 等：三声整章。
- `bench-output/20261029/qwen-batch4/`、`qwen-batch8/`、`qwen-graphs50/`、`qwen-graphs-long300/`：各章与冷短句。
- `bench-output/20261029/listening/`：同文、目标 -20 LUFS 的 A/B；`user-choice.json` 保存本次用户偏好；原章音频未覆盖。
- `bench-output/20261029/verified-summary.json`：回读后的实测摘要；公开精简副本见 `bench-data/2026-10-29.json`，不含个人路径、模型或令牌。

脚本入口为 `scripts/novel-audio-bench/bench.py`（现有 HTTP）、`local_bench.py`（原生引擎实验）、`verify_results.py`（拒绝缺段、改文、改音频与伪造完成标记）。参数中的配置和产物根由本机指定；原始参考录音、参考文字、环境安装日志、模型文件及令牌不提交。

验证：服务测试运行 336 项（317 通过、19 项环境跳过）；基准与证据测试 10 项通过。提交门禁通过，推送前敏感值与个人路径扫描 0 命中。硬件实测与完整章文件回读仍是主要证据，单元测试不替代质量试听。
