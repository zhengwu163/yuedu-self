"""Read-only, bounded admission checks for CUDA device zero on Windows."""

import ctypes
import re
import subprocess
from dataclasses import dataclass

from scripts.novel_audio_server.errors import ResourceCheckError, ResourceUnavailableError


@dataclass(frozen=True)
class ResourceSnapshot:
    gpu_free_mib: int
    ram_free_mib: int
    commit_free_mib: int


def _windows_memory():
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
            (name, ctypes.c_ulonglong) for name in (
                "total_phys", "avail_phys", "total_page", "avail_page",
                "total_virtual", "avail_virtual", "avail_extended",
            )
        ]
    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        raise OSError()
    return status.avail_phys // 1048576, status.avail_page // 1048576


def resource_snapshot():
    try:
        ram, commit = _windows_memory()
        result = subprocess.run(
            ["nvidia-smi", "--id=0", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=3, check=False,
        )
        value = result.stdout.strip()
        if result.returncode or len(value) > 1024 or not re.fullmatch(rb"[0-9]+", value):
            raise ValueError()
        return ResourceSnapshot(int(value), ram, commit)
    except (OSError, AttributeError, ValueError, subprocess.TimeoutExpired):
        raise ResourceCheckError() from None


def ensure_resources(stage="combined", probe=None, profile_id=None):
    # Measured combined 9B + TTS occupancy is about 11 GiB. Reserve 12 GiB
    # before spawning; recheck remaining headroom immediately before each load.
    limits = {"combined": (12288, 8192, 8192), "text": (7168, 4096, 4096), "tts": (5120, 4096, 4096)}
    if profile_id == "qwen35-4b-base":
        limits.update(combined=(10240, 8192, 8192), text=(4096, 4096, 4096))
    minimum = limits[stage]
    snapshot = (probe or resource_snapshot)()
    actual = (snapshot.gpu_free_mib, snapshot.ram_free_mib, snapshot.commit_free_mib)
    if any(value < limit for value, limit in zip(actual, minimum)):
        raise ResourceUnavailableError()
    return snapshot
