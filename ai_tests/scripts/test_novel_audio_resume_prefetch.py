"""Real-device regression: user resume restarts AUTO; pause cancels and unloads it.

Requires a paused frozen chapter in the debug player, an idle real server, and
uncached following chapters. Uses no mock, does not clear data, and emits only
status/timings. Keep novel text and credentials out of this test's output.
"""

import argparse
import json
import pathlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

import uiautomator2

PACKAGE = "io.legado.miss.app.debug"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-file", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--timeout", type=float, default=25)
    args = parser.parse_args()
    token = args.token_file.read_text(encoding="utf-8").strip()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    device = uiautomator2.connect(args.device)
    result = {"pass": False, "resumed": False, "cleanupIdle": False}

    def shell(*command):
        process = subprocess.run(
            [args.adb, "-s", args.device, "shell", *command],
            capture_output=True, timeout=20,
        )
        assert process.returncode == 0, "ADB_COMMAND_FAILED"
        return process.stdout.decode(errors="replace")

    def runtime():
        request = urllib.request.Request(
            args.base_url.rstrip("/") + "/v1/runtime/status",
            headers={"Authorization": "Bearer " + token},
        )
        with opener.open(request, timeout=10) as response:
            data = json.load(response)
        return data["state"], data["activeLease"]

    def controls():
        return [node for node in ET.fromstring(device.dump_hierarchy()).iter("node")
                if node.get("package") == PACKAGE]

    def tap(label):
        control = next((node for node in controls()
                        if node.get("text") == label or node.get("content-desc") == label), None)
        assert control is not None, "PLAYER_CONTROL_NOT_VISIBLE"
        left, top, right, bottom = map(int, re.findall(r"\d+", control.get("bounds")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    uid_match = re.search(r"uid:(\d+)", shell("cmd", "package", "list", "packages", "-U", PACKAGE))
    assert uid_match, "DEBUG_PACKAGE_NOT_INSTALLED"
    uid = uid_match[1]

    def actual_audio():
        return any(re.search(r"u/pid:\s*" + uid + r"/", line)
                   and "type:android.media.AudioTrack" in line and "state:started" in line
                   for line in shell("dumpsys", "audio").splitlines())

    # Never disturb another lease. Cleanup is allowed only after our own play.
    assert "NovelAudioReadAloudService" in shell("dumpsys", "activity", "services", PACKAGE), "PRECONDITION_NOT_FROZEN_PLAYER"
    assert not actual_audio(), "PRECONDITION_PLAYER_NOT_PAUSED"
    assert runtime() == ("idle", False), "PRECONDITION_SERVER_NOT_IDLE"
    start = time.monotonic()
    try:
        tap("播放")
        result["resumed"] = True
        while time.monotonic() - start < args.timeout:
            state, lease = runtime()
            elapsed = round(time.monotonic() - start, 2)
            if actual_audio() and "actualAudioSeconds" not in result:
                result["actualAudioSeconds"] = elapsed
            if (lease or state in ("starting", "generating", "ready")) and "prefetchStartedSeconds" not in result:
                result["prefetchStartedSeconds"] = elapsed
            if "actualAudioSeconds" in result and "prefetchStartedSeconds" in result:
                result["pass"] = True
                break
            time.sleep(.3)
        assert result["pass"], "RESUME_DID_NOT_RESTART_PREFETCH"
    except AssertionError as error:
        result["failureCode"] = str(error)
    except Exception as error:
        # Exception messages from transport/UI libraries can contain private data.
        result["failureCode"] = type(error).__name__
    finally:
        if result["resumed"]:
            try:
                tap("暂停")
                cleanup = time.monotonic()
                while time.monotonic() - cleanup < 90:
                    if runtime() == ("idle", False) and not actual_audio():
                        result["cleanupIdle"] = True
                        result["cleanupSeconds"] = round(time.monotonic() - cleanup, 2)
                        break
                    time.sleep(.5)
            except Exception as error:
                result["cleanupFailureCode"] = type(error).__name__
        result["pass"] = result["pass"] and result["cleanupIdle"]
        args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
