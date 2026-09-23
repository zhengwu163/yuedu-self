#!/usr/bin/env bash
# 安装最新测试包（覆盖安装，保留应用数据）。
#   install.sh           安装已打好的最新包
#   install.sh --build   先用当前代码重新打包再安装
#   install.sh --fresh   卸载后全新安装（会清空应用里的书架和设置）
set -euo pipefail
source "$(dirname "$0")/env.sh"

build=0
fresh=0
for arg in "$@"; do
    case "$arg" in
        --build) build=1 ;;
        --fresh) fresh=1 ;;
        *) echo "未知参数：$arg"; exit 1 ;;
    esac
done

if [ "$build" = 1 ]; then
    say_step "用当前代码打测试包（仅供本机人工测试，不是交付包）"
    (cd "$PROJECT_DIR" && sh ./gradlew :app:assembleAppDebug --no-configuration-cache)
fi

apk="$(latest_apk)"
[ -n "$apk" ] || { echo "没有找到测试包，请先运行：scripts/manual-test/install.sh --build"; exit 1; }
echo "安装包：${apk#"$PROJECT_DIR"/}（$(date -r "$apk" '+%m-%d %H:%M') 打包）"

if [ "$fresh" = 1 ]; then
    adb uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
fi
adb install -r -d "$apk"
# 提前授予通知权限，避免朗读时通知栏控制不出现
adb shell pm grant "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
echo "已安装：$(adb shell dumpsys package "$APP_PACKAGE" | grep -m1 versionName | tr -d ' \r')"
