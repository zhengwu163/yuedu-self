#!/usr/bin/env bash
# 首次准备人工测试环境（可重复执行，已完成的步骤会跳过）。
set -euo pipefail
source "$(dirname "$0")/env.sh"

say_step "检查 Java 与 Android SDK"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
command -v sdkmanager >/dev/null || { echo "缺少 sdkmanager：brew install --cask android-commandlinetools"; exit 1; }
command -v ffmpeg >/dev/null || echo "提示：未安装 ffmpeg，本机 TTS 会输出 wav（brew install ffmpeg 可改为 mp3）"

say_step "安装模拟器、系统镜像和构建所需 SDK 包（已安装的会跳过）"
# yes 在管道关闭时会被 SIGPIPE 终止，需吞掉它的退出码，否则 pipefail 会误判失败
(yes || true) | sdkmanager --sdk_root="$ANDROID_HOME" \
    "platform-tools" "emulator" "platforms;android-36" "build-tools;35.0.0" "$SYSTEM_IMAGE" >/dev/null 2>&1
echo "SDK 包就绪"

say_step "创建模拟器 $AVD_NAME"
if avdmanager list avd -c 2>/dev/null | grep -qx "$AVD_NAME"; then
    echo "已存在，跳过创建"
else
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_7 >/dev/null 2>&1
    echo "已创建"
fi
# 4G 内存；打开实体键盘，方便在模拟器里直接用电脑打字；
# 去掉 <temp> 数据分区，否则每次重启模拟器都会清空已装应用和导入的书
python3 - "$HOME/.android/avd/$AVD_NAME.avd/config.ini" <<'PY'
import sys
path = sys.argv[1]
wanted = {"hw.ramSize": "4096", "hw.keyboard": "yes", "hw.audioOutput": "yes"}
drop = {"disk.dataPartition.path"}
lines, seen = [], set()
for raw in open(path, encoding="utf-8").read().splitlines():
    key = raw.split("=", 1)[0].strip()
    if key in drop or key in seen:
        continue
    if key in wanted:
        raw = f"{key}={wanted[key]}"
    seen.add(key)
    lines.append(raw)
for key, value in wanted.items():
    if key not in seen:
        lines.append(f"{key}={value}")
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
echo "模拟器配置已更新"

say_step "生成测试小说"
python3 "$MT_DIR/make_test_books.py"

say_step "完成。之后每次测试运行：scripts/manual-test/start.sh"
