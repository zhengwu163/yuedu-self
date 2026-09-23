#!/usr/bin/env bash
# 结束测试：关闭本机 TTS 和模拟器（真机不受影响）。
set -uo pipefail
source "$(dirname "$0")/env.sh"

if [ -f "$RUN_DIR/mock_tts.pid" ]; then
    kill "$(cat "$RUN_DIR/mock_tts.pid")" 2>/dev/null && echo "本机 TTS 已关闭"
    rm -f "$RUN_DIR/mock_tts.pid"
fi
for serial in $(adb devices | awk 'NR>1 && $1 ~ /^emulator-/ {print $1}'); do
    adb -s "$serial" emu kill >/dev/null 2>&1 && echo "模拟器 $serial 已关闭"
done
