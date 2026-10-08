#!/usr/bin/env python3
"""Local NovelAudioServer agent CLI."""

import argparse
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.novel_audio_server.protocol import analysis_request

from agent import LocalAgent
from checks import run_checks
from config import ConfigError, ensure_token, load_config
from smoke_http import run_smoke
from state_lock import validate_state


DEFAULT_CONFIG = Path(__file__).resolve().parent / "local-model.json"
WINDOWS = os.name == "nt"
_STATUS_FIELDS = {
    "state",
    "profileId",
    "identity",
    "capabilities",
    "activeLease",
    "errorCode",
    "hardware",
}


def _request():
    units = [
        {"unitId": "demo-u1", "text": "黄昏的灯塔下，林舟停下脚步。"},
        {"unitId": "demo-u2", "text": "苏禾回头看向海面。"},
        {"unitId": "demo-u3", "text": "林舟说，明天一起去看海吧。"},
    ]
    return analysis_request(
        {
            "bookId": "local-fake-smoke",
            "chapterId": "chapter-1",
            "textHash": hashlib.sha256(
                json.dumps(units, ensure_ascii=False).encode("utf-8")
            ).hexdigest(),
            "analysisVersion": "1",
            "characters": [
                {"characterId": "char-a", "displayName": "林舟", "stableAliases": []},
                {"characterId": "char-b", "displayName": "苏禾", "stableAliases": []},
            ],
            "units": units,
            "previousContext": {"recentAssignments": []},
        }
    )


def _smoke(config_path):
    agent = LocalAgent(config_path, fake=True)
    output = Path(tempfile.mkdtemp(prefix="novel-audio-smoke-"))
    try:
        lease = agent.runtime.acquire("fake-smoke", "auto_prefetch", 3)
        request = _request()
        backend = agent.runtime.begin_generation(lease.lease_id)
        try:
            result = backend.analyze(request)
        finally:
            agent.runtime.end_generation(lease.lease_id)
        assignments = {item["unitId"]: item["speakerId"] for item in result["assignments"]}
        voices = [
            item["voiceAssetId"]
            for item in agent.catalog.public_voices(agent.backend.capabilities)
            if item["voiceAssetId"] != "local.qwen3-tts.narrator"
        ]
        bindings = {
            "narrator": "local.qwen3-tts.narrator",
            "char-a": voices[0],
            "char-b": voices[1],
        }
        # The fake backend deliberately exercises all three public voice paths.
        for index, unit in enumerate(request["units"], 1):
            speaker = assignments[unit["unitId"]]
            if speaker == "narrator":
                voice = bindings["narrator"]
            else:
                voice = bindings.get(speaker, voices[(index - 1) % len(voices)])
            backend = agent.runtime.begin_generation(lease.lease_id)
            try:
                audio = backend.synthesize(
                    {
                        "text": unit["text"],
                        "voiceAssetId": voice,
                        "language": "zh-CN",
                        "speed": 1.0,
                    }
                )
            finally:
                agent.runtime.end_generation(lease.lease_id)
            (output / f"{index:02d}.ogg").write_bytes(audio)
        agent.runtime.release(lease.lease_id)
        print("fake smoke passed: 3 deterministic Ogg fixtures")
        return 0
    except Exception:
        return 2
    finally:
        agent.close()


def _write_example(path):
    if path.exists():
        return
    example = Path(__file__).with_name("local-model.example.json")
    path.write_text(example.read_text(encoding="utf-8"), encoding="utf-8")


def _pid_is_running(pid):
    if type(pid) is not int or pid <= 0:
        return False
    if WINDOWS:
        return _windows_pid_is_running(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def _windows_pid_is_running(pid):
    # os.kill(pid, 0) is not a safe existence probe on Windows.
    import ctypes
    from ctypes import wintypes

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return ctypes.get_last_error() == 5  # Access denied is not proof of exit.
    try:
        exit_code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
            return True
        return exit_code.value == 259  # STILL_ACTIVE
    finally:
        kernel.CloseHandle(handle)


def _state_dir(config):
    path = config.root / "state"
    validate_state(path)
    return path


def _read_pid(state_dir):
    try:
        value = (state_dir / "agent.pid").read_text(encoding="ascii").strip()
        pid = int(value)
    except (OSError, TypeError, ValueError):
        return None
    return pid if pid > 0 else None


def _safe_status(value):
    if not isinstance(value, dict):
        return {}
    result = {}
    for key in _STATUS_FIELDS:
        item = value.get(key)
        if key in {"state", "profileId", "identity", "errorCode"}:
            if isinstance(item, str) and item and "\x00" not in item:
                result[key] = item
        elif key == "capabilities":
            if isinstance(item, list) and all(
                isinstance(entry, str) and "\x00" not in entry for entry in item
            ):
                result[key] = list(item)
        elif key == "activeLease":
            if isinstance(item, bool):
                result[key] = item
        elif key == "hardware":
            if (
                isinstance(item, dict)
                and isinstance(item.get("status"), str)
                and "\x00" not in item["status"]
            ):
                result[key] = {"status": item["status"]}
    return result


def _read_status(config):
    state_dir = _state_dir(config)
    pid = _read_pid(state_dir)
    try:
        value = json.loads(
            (state_dir / "agent.status.json").read_text(encoding="utf-8")
        )
    except (OSError, ValueError):
        value = {}
    result = _safe_status(value)
    if pid is None or not _pid_is_running(pid):
        if result.get("state") == "failed":
            return result
        result["state"] = "stopped"
        result["activeLease"] = False
    else:
        result.setdefault("state", "unknown")
        result["pid"] = pid
    return result


def _write_stopped_status(state_dir):
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "agent.status.json").write_text(
        json.dumps({"state": "stopped"}, sort_keys=True),
        encoding="utf-8",
    )


def _stop_windows_agent(config_path, timeout):
    # PowerShell validates command line, creation time and owned descendants.
    script = Path(__file__).with_name("stop-agent.ps1")
    try:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-File", str(script),
             "-ConfigPath", str(config_path.resolve()),
             "-TimeoutSeconds", str(max(1, int(timeout)))],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=timeout + 15, check=False,
        )
        code = 0 if result.returncode == 0 else 2
    except (OSError, subprocess.TimeoutExpired):
        code = 2
    value = {"state": "stopped"} if code == 0 else {
        "state": "failed", "errorCode": "worker_stop_failed",
    }
    print(json.dumps(value, sort_keys=True))
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    modes = parser.add_mutually_exclusive_group()
    for mode in ("init", "check", "serve", "smoke", "status", "stop"):
        modes.add_argument("--" + mode, action="store_true")
    parser.add_argument("--fake-backend", action="store_true")
    parser.add_argument("--require-windows-runtime", action="store_true")
    parser.add_argument("--stop-timeout", type=float, default=10.0)
    args = parser.parse_args(argv)
    if not 0.01 <= args.stop_timeout <= 60:
        parser.error("invalid stop timeout")
    try:
        if args.init:
            _write_example(args.config)
            config = load_config(args.config)
            ensure_token(config.token_file)
            print("local model configuration initialized")
            return 0
        if args.stop:
            config = load_config(args.config)
            state_dir = _state_dir(config)
            if WINDOWS:
                return _stop_windows_agent(args.config, args.stop_timeout)
            state_dir.mkdir(parents=True, exist_ok=True)
            (state_dir / "agent.stop").touch()
            pid = _read_pid(state_dir)
            deadline = time.monotonic() + args.stop_timeout
            while pid is not None and _pid_is_running(pid):
                if time.monotonic() >= deadline:
                    print(json.dumps({
                        "state": "failed", "errorCode": "worker_stop_failed"
                    }, sort_keys=True))
                    return 2
                time.sleep(0.05)
            if pid is None or not _pid_is_running(pid):
                _write_stopped_status(state_dir)
                (state_dir / "agent.pid").unlink(missing_ok=True)
                print(json.dumps({"state": "stopped"}, sort_keys=True))
            else:
                print(json.dumps({"state": "stop_requested"}, sort_keys=True))
            return 0
        if args.status:
            config = load_config(args.config)
            print(json.dumps(_read_status(config), sort_keys=True))
            return 0
        if args.check:
            report = run_checks(args.config)
            required = WINDOWS or args.require_windows_runtime
            print(report.to_json(require_windows_runtime=required))
            return report.exit_code(require_windows_runtime=required)
        config = load_config(args.config)
        if args.smoke:
            if args.fake_backend:
                return _smoke(args.config)
            return run_smoke(args.config)
        if args.serve:
            LocalAgent(args.config, fake=args.fake_backend).serve()
            return 0
        parser.error("choose --init, --check, --serve, --smoke, --status, or --stop")
    except (ConfigError, OSError, ValueError):
        print("local model configuration check failed")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
