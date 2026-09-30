"""每次云操作使用有硬期限的子进程，密钥经私有 stdin 传递。"""
import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

from cloud import BailianClient
from local_state import BridgeConfig
from protocol import (
    BridgeError, CloudAuthError, CloudProtocolError, CloudQuotaError, CloudRateError,
    CloudTimeoutError, MAX_AUDIO, MAX_JSON, strict_json_loads,
    TtsResponseError, TtsJsonError, TtsMissingAudioUrlError, TtsUnsafeAudioUrlError,
    TtsDownloadError, TtsWavError, TtsConversionError,
)

ERRORS = {20: CloudAuthError, 21: CloudQuotaError, 22: CloudRateError,
          23: CloudProtocolError, 24: CloudTimeoutError, 25: BridgeError,
          26: TtsResponseError, 27: TtsJsonError, 28: TtsMissingAudioUrlError,
          29: TtsUnsafeAudioUrlError, 30: TtsDownloadError, 31: TtsWavError,
          32: TtsConversionError}


def terminate(process):
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except ProcessLookupError:
        pass


class WorkerProcesses:
    """服务拥有工作进程的生命周期；关闭与新建使用同一锁，避免退出时漏登记。"""

    def __init__(self):
        self._lock = threading.Lock()
        self._active = set()
        self._closed = False

    def start(self, command):
        with self._lock:
            if self._closed:
                raise BridgeError()
            process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL,
                                       start_new_session=(os.name == "posix"))
            self._active.add(process)
            return process

    def release(self, process):
        with self._lock:
            self._active.discard(process)

    def close(self):
        with self._lock:
            self._closed = True
            processes = tuple(self._active)
        for process in processes:
            terminate(process)
        deadline = time.monotonic() + 2
        for process in processes:
            try:
                process.wait(timeout=max(.01, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass


def run_bounded_process(command, payload, timeout, scope=None):
    """期限包含 DNS、TLS、下载与 ffmpeg；POSIX 上一起终止子进程组。"""
    scope = scope or WorkerProcesses()
    process = None
    try:
        process = scope.start(command)
        with process:
            try:
                output, _ = process.communicate(payload, timeout=timeout)
            except BaseException:
                terminate(process)
                process.communicate()
                raise
            if process.returncode:
                raise ERRORS.get(process.returncode, BridgeError)()
            if len(output) > MAX_AUDIO:
                raise CloudProtocolError()
            return output
    except subprocess.TimeoutExpired:
        raise CloudTimeoutError() from None
    except OSError:
        raise BridgeError() from None
    finally:
        if process is not None:
            scope.release(process)


class CloudWorker:
    def __init__(self, config, profile):
        self.config, self.profile = config, profile
        self._processes = WorkerProcesses()

    def close(self):
        self._processes.close()

    def _call(self, operation, request, timeout):
        data = json.dumps({"key": self.config.dashscope_api_key,
                           "ffmpeg": self.config.ffmpeg_path, "request": request},
                          ensure_ascii=False).encode("utf-8")
        return run_bounded_process([sys.executable, str(Path(__file__).resolve()), operation],
                                   data, timeout, scope=self._processes)

    def analyze(self, request):
        return strict_json_loads(self._call("analyze", request, 40))

    def synthesize(self, request):
        return self._call("synthesize", request, 25)


def main():
    try:
        operation = sys.argv[1]
        value = strict_json_loads(sys.stdin.buffer.read(MAX_JSON + 1))
        config = BridgeConfig(dashscope_api_key=value["key"], ffmpeg_path=value["ffmpeg"])
        client = BailianClient(config)
        if operation == "analyze":
            output = json.dumps(client.analyze(value["request"]), ensure_ascii=False).encode()
        elif operation == "synthesize":
            output = client.synthesize(value["request"])
        else:
            raise CloudProtocolError()
        sys.stdout.buffer.write(output)
        return 0
    except BridgeError as error:
        return next((code for code, kind in ERRORS.items() if type(error) is kind), 25)
    except Exception:
        # 不把异常链/URL/Key/章节正文写入 stdout 或 stderr。
        return 25


if __name__ == "__main__":
    raise SystemExit(main())
