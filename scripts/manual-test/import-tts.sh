#!/usr/bin/env bash
# 在应用里弹出「导入本机测试 TTS 引擎」确认框，用户在设备上点确定即可。
set -euo pipefail
source "$(dirname "$0")/env.sh"

curl -fs "http://127.0.0.1:$TTS_PORT/health" >/dev/null || { echo "本机 TTS 未运行，请先执行 start.sh"; exit 1; }
adb reverse "tcp:$TTS_PORT" "tcp:$TTS_PORT" >/dev/null
adb shell am start -a android.intent.action.VIEW \
    -d "legado://import/httpTTS?src=http://127.0.0.1:$TTS_PORT/engine.json" \
    "$APP_PACKAGE" >/dev/null
echo "已在设备上弹出导入确认框，请在设备上点「确定」。"
echo "之后在：阅读界面 → 朗读 → 设置 → 朗读引擎，选择「本机测试 TTS（Mac 语音）」。"
