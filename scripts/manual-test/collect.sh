#!/usr/bin/env bash
# 发现问题时运行：保存截图、应用日志、本机 TTS 请求记录，打包到 output/manual-test/bugs/ 下。
#   collect.sh "切章后声音变成了旁白"
set -uo pipefail
source "$(dirname "$0")/env.sh"

note="${1:-未填写描述}"
dir="$RUN_DIR/bugs/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$dir"

echo "$note" > "$dir/描述.txt"
{
    echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo "分支：$(git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null)"
    echo "提交：$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null)（工作区未提交改动 $(git -C "$PROJECT_DIR" status --short | wc -l | tr -d ' ') 个）"
    echo "测试包：$(adb shell dumpsys package "$APP_PACKAGE" | grep -m1 versionName | tr -d ' \r')"
    echo "设备：$(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
} > "$dir/环境.txt"

adb exec-out screencap -p > "$dir/截图.png" 2>/dev/null
pid="$(adb shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r')"
adb logcat -d -v time > "$dir/logcat-全部.txt" 2>/dev/null
if [ -n "$pid" ]; then
    adb logcat -d -v time --pid="$pid" > "$dir/logcat-应用.txt" 2>/dev/null
fi
tail -n 300 "$RUN_DIR/mock_tts.log" > "$dir/本机TTS请求.txt" 2>/dev/null
curl -fs "http://127.0.0.1:$TTS_PORT/admin" > "$dir/本机TTS控制台.html" 2>/dev/null

echo "已保存到：${dir#"$PROJECT_DIR"/}"
echo "把这个文件夹路径发给 AI 即可，它能看到截图和日志。"
open "$dir" 2>/dev/null || true
