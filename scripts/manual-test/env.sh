# shellcheck shell=bash
# 人工测试环境的公共变量，由同目录其他脚本 source。

MT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$MT_DIR/../.." && pwd)"

export JAVA_HOME="${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

AVD_NAME="legado_test"
SYSTEM_IMAGE="system-images;android-35;google_apis_playstore;arm64-v8a"
APP_PACKAGE="io.legado.miss.app.debug"
TTS_PORT="${MOCK_TTS_PORT:-8765}"
BOOKS_SRC="$PROJECT_DIR/ai_tests/testdata/audiobook"
BOOKS_DEVICE_DIR="/sdcard/Download/legado-test"
RUN_DIR="$PROJECT_DIR/output/manual-test"
mkdir -p "$RUN_DIR"

latest_apk() {
    # 某个目录不存在时 ls 会返回非 0，调用方开了 pipefail，这里必须兜底
    ls -t "$PROJECT_DIR"/app/build/outputs/apk/app/debug/*.apk \
        "$PROJECT_DIR"/output/apk/test/*.apk 2>/dev/null | head -1 || true
}

device_ready() {
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

say_step() {
    printf '\n\033[1;34m==> %s\033[0m\n' "$*"
}
