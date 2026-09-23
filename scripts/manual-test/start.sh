#!/usr/bin/env bash
# 每天开始测试时运行：启动模拟器 → 启动本机 TTS → 安装最新测试包 → 放入测试小说 → 打开应用。
# 连着真机时（USB 调试已开启）会直接用真机，不启动模拟器。
set -euo pipefail
source "$(dirname "$0")/env.sh"

say_step "准备设备"
if adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "已检测到设备：$(adb devices | awk 'NR>1 && $2=="device"{print $1}' | head -1)"
else
    echo "启动模拟器 $AVD_NAME（首次启动约 1-2 分钟）"
    nohup emulator -avd "$AVD_NAME" -netdelay none -netspeed full \
        > "$RUN_DIR/emulator.log" 2>&1 &
    adb wait-for-device
fi
until device_ready; do sleep 2; done
echo "设备已开机"

say_step "启动本机测试 TTS（端口 $TTS_PORT）"
if curl -fs "http://127.0.0.1:$TTS_PORT/health" >/dev/null 2>&1; then
    echo "已在运行"
else
    nohup python3 "$MT_DIR/mock_tts_server.py" > "$RUN_DIR/mock_tts.log" 2>&1 &
    echo $! > "$RUN_DIR/mock_tts.pid"
    for _ in $(seq 1 20); do
        curl -fs "http://127.0.0.1:$TTS_PORT/health" >/dev/null 2>&1 && break
        sleep 0.5
    done
fi
# 让设备里的 127.0.0.1:端口 指向电脑，模拟器和真机都适用
adb reverse "tcp:$TTS_PORT" "tcp:$TTS_PORT" >/dev/null
echo "控制台：http://127.0.0.1:$TTS_PORT/admin"

say_step "安装测试包"
"$MT_DIR/install.sh"

say_step "放入测试小说到设备「下载/legado-test」"
adb shell mkdir -p "$BOOKS_DEVICE_DIR/副本"
find "$BOOKS_SRC" -name '*.txt' | while read -r f; do
    rel="${f#"$BOOKS_SRC"/}"
    adb push "$f" "$BOOKS_DEVICE_DIR/$rel" >/dev/null
done
adb shell ls -R "$BOOKS_DEVICE_DIR"

say_step "打开应用和 TTS 控制台"
adb shell monkey -p "$APP_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
open "http://127.0.0.1:$TTS_PORT/admin" || true

cat <<EOF

一切就绪。
- 首次使用需要在应用里导入本机朗读引擎：运行 scripts/manual-test/import-tts.sh，然后在手机上点「确定」
- 测试步骤见 docs/AI_AUDIOBOOK_MANUAL_TEST.md
- 发现问题立刻运行 scripts/manual-test/collect.sh "一句话描述"
- 结束测试运行 scripts/manual-test/stop.sh
EOF
