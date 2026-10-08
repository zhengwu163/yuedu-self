"""Bounded, read-only Windows runtime inspection; safe to import in the Agent.

Confirmed Windows baseline: CPython 3.12.14, torch/torchaudio 2.9.1+cu130,
qwen-tts 0.1.1, llama.cpp b11320, ffmpeg/ffprobe n9.0.2. These are reference
versions, not exact patch locks: inspect the interpreter, matching wheel ABI,
CUDA runtime/device, CLI flags and codec lists instead of generating samples.
"""

import importlib
import json
import math
import os
import re
import signal
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path


PYTHON_NAMES = ("pythonRuntime", "qwenTts", "torch", "cuda")
RUNTIME_NAMES = PYTHON_NAMES + ("llamaServer", "audioCodec")
MAX_OUTPUT_BYTES = 256 * 1024
PROBE_TIMEOUT = 15.0
PYTHON_TIMEOUT = 60.0
_PROCESS_CODES = frozenset({
    "ok", "probe_timeout", "probe_output_limit", "probe_unavailable",
    "probe_failed", "probe_cleanup_failed",
})
_PYTHON_PAIRS = {
    "pythonRuntime": {("PASS", "ok"), ("FAIL", "python_runtime_incompatible")},
    "qwenTts": {("PASS", "ok"), ("FAIL", "qwen_import_failed"),
                ("BLOCKED", "python_runtime_required")},
    "torch": {("PASS", "ok"), ("FAIL", "torch_import_failed"),
              ("FAIL", "torchaudio_import_failed"), ("FAIL", "torch_version_mismatch"),
              ("FAIL", "cuda_runtime_unavailable"), ("FAIL", "cuda_runtime_mismatch"),
              ("BLOCKED", "python_runtime_required")},
    "cuda": {("PASS", "ok"), ("FAIL", "cuda_probe_failed"),
             ("BLOCKED", "python_runtime_required"), ("BLOCKED", "torch_required"),
             ("BLOCKED", "cuda_unavailable"), ("BLOCKED", "cuda_device_mismatch"),
             ("BLOCKED", "insufficient_vram")},
}
RUNTIME_CODES = _PROCESS_CODES | {
    code for pairs in _PYTHON_PAIRS.values() for _, code in pairs
} | {
    "windows_only", "invalid_probe_output", "llama_version_unavailable",
    "llama_cuda_flags_missing", "llama_cuda0_unavailable", "ffmpeg_version_unavailable",
    "ffprobe_version_unavailable", "ogg_muxer_missing", "libopus_encoder_missing",
    "opus_decoder_missing",
}


@dataclass(frozen=True)
class ProbeOutput:
    """Internal capture only. Never serialize raw bytes to the check report."""

    code: str
    output: bytes = field(default=b"", repr=False)


def _stop_process_tree(process, *, platform_name=None, command_runner=None):
    """Kill only this probe's tree, before its parent disappears, then reap it."""
    platform_name = platform_name or os.name
    command_runner = command_runner or subprocess.run
    command_ok = True
    try:
        if platform_name == "nt":
            # Do not terminate the parent first: taskkill /T needs the live root.
            completed = command_runner(
                ["taskkill", "/PID", str(int(process.pid)), "/T", "/F"],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, timeout=2, check=False,
            )
            command_ok = getattr(completed, "returncode", 0) == 0
        else:
            # Each probe starts in its own session, including host-only tests.
            os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        # A missing POSIX process group proves the owned tree is already gone.
        command_ok = platform_name != "nt"
    except (OSError, subprocess.SubprocessError):
        command_ok = False
    try:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=2)
    except (OSError, subprocess.SubprocessError):
        return False
    return command_ok and process.poll() is not None


def run_bounded(argv, *, timeout=PROBE_TIMEOUT, max_output_bytes=MAX_OUTPUT_BYTES,
                popen_factory=None):
    """Capture merged streams incrementally; no communicate() or unbounded buffer.

    The reader stops at the cap, including stderr. A wall-clock deadline covers
    both pipe EOF and process exit. Cleanup has its own bounded allowance.
    """
    if (not math.isfinite(timeout) or not 0 < timeout <= PYTHON_TIMEOUT
            or type(max_output_bytes) is not int or not 0 < max_output_bytes <= MAX_OUTPUT_BYTES):
        return ProbeOutput("probe_failed")
    popen_factory = popen_factory or subprocess.Popen
    options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
               if os.name == "nt" else {"start_new_session": True})
    env = dict(os.environ, HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
               HF_HUB_DISABLE_TELEMETRY="1", PYTHONDONTWRITEBYTECODE="1")
    try:
        process = popen_factory(
            list(argv), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, shell=False, bufsize=0, env=env, **options,
        )
    except (OSError, ValueError, subprocess.SubprocessError):
        return ProbeOutput("probe_unavailable")

    buffer = bytearray()
    done = threading.Event()
    read_error = threading.Event()

    def read_output():
        try:
            while True:
                chunk = process.stdout.read(min(4096, max_output_bytes + 1 - len(buffer)))
                if not chunk:
                    break
                buffer.extend(chunk)
                if len(buffer) > max_output_bytes:
                    break
        except (OSError, ValueError):
            read_error.set()
        finally:
            done.set()

    reader = threading.Thread(target=read_output, name="runtime-probe-reader", daemon=True)
    deadline = time.monotonic() + timeout
    reader.start()
    code = "ok"
    finished = False
    try:
        if not done.wait(max(0, deadline - time.monotonic())):
            code = "probe_timeout"
        elif len(buffer) > max_output_bytes:
            code = "probe_output_limit"
        elif read_error.is_set():
            code = "probe_failed"
        else:
            try:
                returncode = process.wait(timeout=max(0, deadline - time.monotonic()))
                finished = True
                if returncode != 0:
                    code = "probe_failed"
            except subprocess.TimeoutExpired:
                code = "probe_timeout"
            except OSError:
                code = "probe_failed"
    finally:
        if not finished:
            if not _stop_process_tree(process):
                code = "probe_cleanup_failed"
        # FileIO (bufsize=0), not BufferedReader: close has no reader-held lock.
        process.stdout.close()
        reader.join(timeout=0.5)
        if reader.is_alive():
            code = "probe_cleanup_failed"
    return ProbeOutput(code, bytes(buffer) if code == "ok" else b"")


def _capture(runner, argv, timeout=PROBE_TIMEOUT):
    try:
        result = runner(argv, timeout=timeout, max_output_bytes=MAX_OUTPUT_BYTES)
    except (TimeoutError, subprocess.TimeoutExpired):
        return ProbeOutput("probe_timeout")
    except OSError:
        return ProbeOutput("probe_unavailable")
    except Exception:
        return ProbeOutput("probe_failed")
    if not isinstance(result, ProbeOutput) or result.code not in _PROCESS_CODES:
        return ProbeOutput("invalid_probe_output")
    if not isinstance(result.output, bytes):
        return ProbeOutput("invalid_probe_output")
    if len(result.output) > MAX_OUTPUT_BYTES:
        return ProbeOutput("probe_output_limit")
    return result


def _python_results(output):
    if output.code != "ok":
        return {name: ("FAIL", output.code) for name in PYTHON_NAMES}
    try:
        # Duplicate JSON fields are not a valid child protocol.
        def unique(pairs):
            value = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError()
                value[key] = item
            return value
        value = json.loads(output.output, object_pairs_hook=unique)
        if not isinstance(value, dict) or set(value) != set(PYTHON_NAMES):
            raise ValueError()
        result = {}
        for name in PYTHON_NAMES:
            item = value[name]
            if (not isinstance(item, list) or len(item) != 2
                    or not all(isinstance(part, str) for part in item)
                    or tuple(item) not in _PYTHON_PAIRS[name]):
                raise ValueError()
            result[name] = tuple(item)
        return result
    except (ValueError, TypeError, RecursionError):
        return {name: ("FAIL", "invalid_probe_output") for name in PYTHON_NAMES}


def _listing_check(runner, commands):
    for argv, patterns, missing_code in commands:
        output = _capture(runner, argv)
        if output.code != "ok":
            return "FAIL", output.code
        text = output.output.decode("utf-8", errors="replace")
        if isinstance(patterns, str):
            patterns = (patterns,)
        if not all(re.search(pattern, text, re.MULTILINE) for pattern in patterns):
            return "FAIL", missing_code
    return "PASS", "ok"


def probe_runtime(config, *, platform_name=None, runner=None, minimum_vram_gb=None):
    """Return fixed (status, code) pairs; Windows branches accept runner doubles.

    runner(argv, *, timeout, max_output_bytes) -> ProbeOutput. Supplying only a
    platform override on macOS cannot launch a Windows executable.
    """
    platform_name = platform_name or os.name
    if platform_name != "nt" or (runner is None and os.name != "nt"):
        return {name: ("NOT_CHECKED", "windows_only") for name in RUNTIME_NAMES}
    runner = runner or run_bounded
    minimum = max(24.0, config.minimum_vram_gb, minimum_vram_gb or 0)
    results = _python_results(_capture(runner, [
        str(config.tts.python_executable), "-I", "-B", str(Path(__file__).resolve()),
        "--python-probe", str(float(minimum)),
    ], PYTHON_TIMEOUT))

    llama = str(config.text.runner)
    results["llamaServer"] = _listing_check(runner, [
        ([llama, "--version"], r"(?im)^\s*(?:llama(?:\.cpp|-server)?\s+)?version:\s*b?\d+\b",
         "llama_version_unavailable"),
        ([llama, "--help"], (
            r"(?<![\w-])-ngl(?=[,\s])",
            r"(?<![\w-])-dev(?=[,\s])",
        ),
         "llama_cuda_flags_missing"),
        ([llama, "--list-devices"], r"^\s*CUDA0:\s+\S+", "llama_cuda0_unavailable"),
    ])
    ffmpeg, ffprobe = str(config.tts.ffmpeg), str(config.tts.ffprobe)
    decoder = r"^\s*A[.A-Z]{5}\s+(?:opus|libopus)\s+\S+"
    results["audioCodec"] = _listing_check(runner, [
        ([ffmpeg, "-version"], r"^ffmpeg version \S*\d\S*", "ffmpeg_version_unavailable"),
        ([ffprobe, "-version"], r"^ffprobe version \S*\d\S*", "ffprobe_version_unavailable"),
        ([ffmpeg, "-hide_banner", "-muxers"], r"^\s*E\s+ogg\s+\S+", "ogg_muxer_missing"),
        ([ffmpeg, "-hide_banner", "-encoders"], r"^\s*A[.A-Z]{5}\s+libopus\s+\S+",
         "libopus_encoder_missing"),
        ([ffmpeg, "-hide_banner", "-decoders"], decoder, "opus_decoder_missing"),
        ([ffprobe, "-hide_banner", "-decoders"], decoder, "opus_decoder_missing"),
    ])
    return results


def _python_checks(minimum_vram_gb):
    """Child-only imports. Called directly in unit tests with import doubles."""
    results = {name: ("BLOCKED", "python_runtime_required") for name in PYTHON_NAMES}
    if (sys.implementation.name != "cpython" or sys.platform != "win32"
            or struct.calcsize("P") != 8 or tuple(sys.version_info[:2]) != (3, 12)):
        results["pythonRuntime"] = ("FAIL", "python_runtime_incompatible")
        return results
    results["pythonRuntime"] = ("PASS", "ok")
    try:
        importlib.import_module("qwen_tts")
        results["qwenTts"] = ("PASS", "ok")
    except Exception:
        results["qwenTts"] = ("FAIL", "qwen_import_failed")
    results["cuda"] = ("BLOCKED", "torch_required")
    try:
        torch = importlib.import_module("torch")
    except Exception:
        results["torch"] = ("FAIL", "torch_import_failed")
        return results
    try:
        audio = importlib.import_module("torchaudio")
    except Exception:
        results["torch"] = ("FAIL", "torchaudio_import_failed")
        return results
    # Matching public release AND CUDA wheel tags are ABI requirements, not pins.
    wheel_pattern = r"(\d+\.\d+\.\d+)\+([a-z0-9]+)"
    tv = re.fullmatch(wheel_pattern, str(getattr(torch, "__version__", "")))
    av = re.fullmatch(wheel_pattern, str(getattr(audio, "__version__", "")))
    if not tv or not av or tv[1] != av[1]:
        results["torch"] = ("FAIL", "torch_version_mismatch")
        return results
    cuda_version = getattr(getattr(torch, "version", None), "cuda", None)
    cv = re.fullmatch(r"(\d+)\.(\d+)", str(cuda_version))
    if cv is None:
        results["torch"] = ("FAIL", "cuda_runtime_unavailable")
        return results
    if tv[2] != av[2] or tv[2] != "cu" + cv[1] + cv[2]:
        results["torch"] = ("FAIL", "cuda_runtime_mismatch")
        return results
    results["torch"] = ("PASS", "ok")
    try:
        if not torch.cuda.is_available():
            results["cuda"] = ("BLOCKED", "cuda_unavailable")
        else:
            device = torch.cuda.get_device_properties(0)
            memory = device.total_memory
            if type(memory) is not int or memory <= 0:
                raise ValueError()
            if not re.fullmatch(r"(?:NVIDIA\s+)?(?:GeForce\s+)?RTX\s*5090\s+D\s+V2",
                                device.name.strip(), re.IGNORECASE):
                results["cuda"] = ("BLOCKED", "cuda_device_mismatch")
            # NVIDIA reports bytes while the hardware requirement is stated
            # in decimal GB (the marketed 24 GB class). Do not convert to
            # GiB here and reject a nominal 24 GB device at the boundary.
            elif memory / 1_000_000_000 < max(24.0, minimum_vram_gb):
                results["cuda"] = ("BLOCKED", "insufficient_vram")
            else:
                results["cuda"] = ("PASS", "ok")
    except Exception:
        results["cuda"] = ("FAIL", "cuda_probe_failed")
    return results


def _child_main():
    """Suppress even native import logs at FD level, never buffer or echo them."""
    if len(sys.argv) != 3 or sys.argv[1] != "--python-probe":
        return 2
    try:
        minimum = float(sys.argv[2])
        if not math.isfinite(minimum) or not 24 <= minimum <= 256:
            return 2
    except ValueError:
        return 2
    output_fd = os.dup(1)
    with open(os.devnull, "wb") as sink:
        os.dup2(sink.fileno(), 1)
        os.dup2(sink.fileno(), 2)
    try:
        value = _python_checks(minimum)
        os.write(output_fd, json.dumps(value, sort_keys=True).encode("ascii"))
    finally:
        os.close(output_fd)
    return 0


if __name__ == "__main__":
    raise SystemExit(_child_main())
