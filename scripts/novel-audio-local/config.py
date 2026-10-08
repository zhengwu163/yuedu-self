"""Validated local-model configuration with redacted diagnostics."""

import hashlib
import json
import math
import os
import re
import secrets
import stat
from dataclasses import dataclass
from pathlib import Path

from scripts.novel_audio_server.protocol import strict_json_loads


class ConfigError(ValueError):
    """Configuration is absent, malformed, unsafe, or incomplete."""


DEFAULT_WORKER_STARTUP_TIMEOUT = 600.0
DEFAULT_WORKER_IPC_TIMEOUT = 540.0
WORKER_TIMEOUT_MIN = 1.0
WORKER_TIMEOUT_MAX = 600.0


def _bounded_text(value, name, limit=1024):
    if not isinstance(value, str) or not value or len(value) > limit:
        raise ConfigError(f"invalid {name}")
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ConfigError(f"invalid {name}")
    return value


def _identifier(value, name, limit=128):
    value = _bounded_text(value, name, limit)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*", value):
        raise ConfigError(f"invalid {name}")
    return value


def _path(root, value, name, private=False):
    value = _bounded_text(value, name, 4096)
    try:
        path = Path(value).expanduser()
    except (RuntimeError, ValueError):
        raise ConfigError(f"invalid {name}") from None
    if not path.is_absolute():
        path = root / path
    path = Path(os.path.normpath(str(path)))
    if private:
        try:
            relative = path.relative_to(root)
        except ValueError:
            raise ConfigError(f"invalid {name}") from None
        component = root
        if component.is_symlink():
            raise ConfigError(f"invalid {name}")
        for part in relative.parts:
            component /= part
            if component.is_symlink():
                raise ConfigError(f"invalid {name}")
    try:
        return path.expanduser().resolve()
    except OSError as error:
        raise ConfigError(f"invalid {name}") from error


@dataclass(frozen=True)
class TextConfig:
    model: str
    model_path: Path
    runner: Path
    context_length: int
    gpu_layers: int
    backend_port: int
    start_timeout: float


@dataclass(frozen=True)
class TtsConfig:
    runtime: Path
    voice_design_model: Path
    base_model: Path
    ffmpeg: Path
    ffprobe: Path
    python_executable: Path


@dataclass(frozen=True)
class LocalModelConfig:
    root: Path
    host: str
    port: int
    allow_lan: bool
    token_file: Path
    text: TextConfig
    tts: TtsConfig
    voice_catalog: Path
    lease_ttl: float = 900.0
    model_registry_path: Path | None = None
    active_profile_id: str | None = None
    minimum_vram_gb: float = 24.0
    worker_startup_timeout: float = DEFAULT_WORKER_STARTUP_TIMEOUT
    worker_ipc_timeout: float = DEFAULT_WORKER_IPC_TIMEOUT

    @property
    def profile(self):
        digest = hashlib.sha256()
        digest.update(self.text.model.encode("utf-8"))
        digest.update(str(self.text.model_path).encode("utf-8"))
        digest.update(str(self.tts.voice_design_model).encode("utf-8"))
        digest.update(str(self.tts.base_model).encode("utf-8"))
        digest.update(str(self.voice_catalog).encode("utf-8"))
        return f"local-qwen-v1-{digest.hexdigest()[:16]}"

    def __repr__(self):
        return (
            "LocalModelConfig("
            f"host={self.host!r}, port={self.port}, "
            f"allow_lan={self.allow_lan}, profile={self.profile!r})"
        )

    def startup_summary(self):
        return (
            f"本地服务 {self.host}:{self.port}；"
            f"文本模型 {self.text.model}；"
            f"运行档案 {self.profile}"
        )

    def load_model_registry(self):
        if self.model_registry_path is None:
            return None
        if not self.model_registry_path.is_file():
            raise ConfigError("model registry unavailable")
        try:
            from model_registry import ModelRegistry

            registry = ModelRegistry.load(self.model_registry_path, root=self.root)
            registry.active_profile(self.active_profile_id)
            return registry
        except (ImportError, OSError, ValueError):
            raise ConfigError("invalid model registry") from None


def load_config(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024:
        raise ConfigError("invalid config file")
    root = path.parent.resolve()
    try:
        data = strict_json_loads(path.read_bytes())
    except (OSError, ValueError):
        raise ConfigError("invalid config file") from None
    if not isinstance(data, dict):
        raise ConfigError("invalid config")
    allowed = {
        "host",
        "port",
        "allowLan",
        "tokenFile",
        "text",
        "tts",
        "voiceCatalog",
        "leaseTtl",
        "workerStartupTimeout",
        "workerIpcTimeout",
        "modelRegistry",
        "activeProfile",
        "minimumVramGb",
    }
    if set(data) - allowed:
        raise ConfigError("unknown config field")

    host = _bounded_text(data.get("host", "127.0.0.1"), "host", 64)
    allow_lan = data.get("allowLan", False)
    if not isinstance(allow_lan, bool):
        raise ConfigError("invalid allowLan")
    if host != "127.0.0.1" and not allow_lan:
        raise ConfigError("public bind requires allowLan")
    if host not in {"127.0.0.1", "0.0.0.0", "::1", "::"}:
        raise ConfigError("unsupported host")
    port = data.get("port", 8787)
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ConfigError("invalid port")
    lease_ttl = data.get("leaseTtl", 900.0)
    if type(lease_ttl) not in (int, float) or not 30.0 <= float(lease_ttl) <= 86400.0:
        raise ConfigError("invalid leaseTtl")
    minimum_vram_gb = data.get("minimumVramGb", 24.0)
    if (
        isinstance(minimum_vram_gb, bool)
        or not isinstance(minimum_vram_gb, (int, float))
        or not 24.0 <= float(minimum_vram_gb) <= 256.0
    ):
        raise ConfigError("invalid minimumVramGb")
    worker_startup_timeout = data.get(
        "workerStartupTimeout", DEFAULT_WORKER_STARTUP_TIMEOUT
    )
    worker_ipc_timeout = data.get(
        "workerIpcTimeout", DEFAULT_WORKER_IPC_TIMEOUT
    )
    for timeout, name in (
        (worker_startup_timeout, "workerStartupTimeout"),
        (worker_ipc_timeout, "workerIpcTimeout"),
    ):
        if (
            isinstance(timeout, bool)
            or not isinstance(timeout, (int, float))
            or not math.isfinite(float(timeout))
            or not WORKER_TIMEOUT_MIN <= float(timeout) <= WORKER_TIMEOUT_MAX
        ):
            raise ConfigError(f"invalid {name}")
    model_registry = data.get("modelRegistry")
    if model_registry is not None and not isinstance(model_registry, str):
        raise ConfigError("invalid modelRegistry")
    active_profile = data.get("activeProfile")
    if active_profile is not None:
        active_profile = _identifier(active_profile, "activeProfile")

    text = data.get("text")
    tts = data.get("tts")
    if not isinstance(text, dict) or not isinstance(tts, dict):
        raise ConfigError("invalid model config")
    if set(text) - {
        "model",
        "modelPath",
        "runner",
        "contextLength",
        "gpuLayers",
        "backendPort",
        "startTimeout",
    }:
        raise ConfigError("unknown text field")
    if set(tts) - {
        "runtime",
        "voiceDesignModel",
        "baseModel",
        "ffmpeg",
        "ffprobe",
        "pythonExecutable",
    }:
        raise ConfigError("unknown tts field")

    model = _identifier(text.get("model", "qwen3.5-9b-q4_k_m"), "text model")
    context_length = text.get("contextLength", 8192)
    gpu_layers = text.get("gpuLayers", 999)
    if type(context_length) is not int or not 1024 <= context_length <= 131072:
        raise ConfigError("invalid contextLength")
    if type(gpu_layers) is not int or not 0 <= gpu_layers <= 999:
        raise ConfigError("invalid gpuLayers")
    backend_port = text.get("backendPort", 11435)
    if type(backend_port) is not int or not 1 <= backend_port <= 65535:
        raise ConfigError("invalid backendPort")
    start_timeout = text.get("startTimeout", 180)
    if (
        isinstance(start_timeout, bool)
        or not isinstance(start_timeout, (int, float))
        or not math.isfinite(float(start_timeout))
        or not 1.0 <= float(start_timeout) <= 600.0
    ):
        raise ConfigError("invalid startTimeout")
    python_executable = tts.get("pythonExecutable", "runtime/tts-python/python.exe")
    return LocalModelConfig(
        root=root,
        host=host,
        port=port,
        allow_lan=allow_lan,
        token_file=_path(
            root,
            data.get("tokenFile", "state/agent-token"),
            "tokenFile",
            private=True,
        ),
        text=TextConfig(
            model=model,
            model_path=_path(root, text.get("modelPath", f"models/{model}/model.gguf"), "modelPath"),
            runner=_path(root, text.get("runner", "runtime/llama-server.exe"), "runner"),
            context_length=context_length,
            gpu_layers=gpu_layers,
            backend_port=backend_port,
            start_timeout=float(start_timeout),
        ),
        tts=TtsConfig(
            runtime=_path(root, tts.get("runtime", "runtime/tts-python"), "runtime"),
            voice_design_model=_path(
                root, tts.get("voiceDesignModel", "models/qwen3-tts-voicedesign"),
                "voiceDesignModel",
            ),
            base_model=_path(
                root, tts.get("baseModel", "models/qwen3-tts-base"), "baseModel"
            ),
            ffmpeg=_path(root, tts.get("ffmpeg", "runtime/ffmpeg/bin/ffmpeg.exe"), "ffmpeg"),
            ffprobe=_path(
                root,
                tts.get(
                    "ffprobe",
                    str(
                        Path(
                            tts.get("ffmpeg", "runtime/ffmpeg/bin/ffmpeg.exe")
                        ).with_name("ffprobe.exe")
                    ),
                ),
                "ffprobe",
            ),
            python_executable=_path(root, python_executable, "pythonExecutable"),
        ),
        voice_catalog=_path(
            root,
            data.get("voiceCatalog", "voices/standard.json"),
            "voiceCatalog",
            private=True,
        ),
        lease_ttl=float(lease_ttl),
        model_registry_path=(
            _path(root, model_registry, "modelRegistry")
            if model_registry is not None
            else None
        ),
        active_profile_id=active_profile,
        minimum_vram_gb=float(minimum_vram_gb),
        worker_startup_timeout=float(worker_startup_timeout),
        worker_ipc_timeout=float(worker_ipc_timeout),
    )


def ensure_token(path):
    path = Path(path)
    if path.is_symlink():
        raise ConfigError("token path is a symlink")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        value = path.read_text(encoding="ascii").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,4096}", value):
            raise ConfigError("invalid token")
        return value
    value = secrets.token_urlsafe(32)
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    fd = os.open(path, flags, stat.S_IRUSR | stat.S_IWUSR)
    try:
        with os.fdopen(fd, "w", encoding="ascii") as stream:
            stream.write(value + "\n")
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return value
