import os
from dataclasses import dataclass, field
from pathlib import Path

# Android 端 NovelAudioServerClient 的 callTimeout 为 45 秒；服务端整请求截止
# 时间必须更短，才能在客户端放弃前返回明确的 504，而不是被客户端单方面断开。
ANDROID_CALL_TIMEOUT = 45


@dataclass(frozen=True)
class ServiceConfig:
    host: str
    allow_lan: bool
    port: int
    token: str = field(repr=False)
    voicestudio_base_url: str
    voicestudio_token: str = field(repr=False)
    voicestudio_allow_remote: bool
    voicestudio_profile: str
    voicestudio_profile_revision: str
    voicestudio_model: str
    voicestudio_response_format: str
    voicestudio_speech_path: str
    ffmpeg_path: str
    registry_path: Path
    director_base_url: str
    director_token: str = field(repr=False)
    request_timeout: int


def load_config(env=None):
    values = os.environ if env is None else env
    return ServiceConfig(
        host=values.get("NOVEL_AUDIO_HOST", "127.0.0.1"),
        allow_lan=_boolean(
            values.get("NOVEL_AUDIO_ALLOW_LAN", "false"),
            "NOVEL_AUDIO_ALLOW_LAN",
        ),
        port=_port(values.get("NOVEL_AUDIO_PORT", "8788")),
        token=_required(values.get("NOVEL_AUDIO_TOKEN"), "NOVEL_AUDIO_TOKEN"),
        voicestudio_base_url=_required(
            values.get("VOICESTUDIO_BASE_URL"),
            "VOICESTUDIO_BASE_URL",
        ),
        voicestudio_token=_required(
            values.get("VOICESTUDIO_TOKEN"),
            "VOICESTUDIO_TOKEN",
        ),
        voicestudio_allow_remote=_boolean(
            values.get("VOICESTUDIO_ALLOW_REMOTE", "false"),
            "VOICESTUDIO_ALLOW_REMOTE",
        ),
        voicestudio_profile=_required(
            values.get("VOICESTUDIO_PROFILE", "voicestudio-local-v1"),
            "VOICESTUDIO_PROFILE",
        ),
        voicestudio_profile_revision=_required(
            values.get("VOICESTUDIO_PROFILE_REVISION", "1"),
            "VOICESTUDIO_PROFILE_REVISION",
        ),
        voicestudio_model=_required(
            values.get("VOICESTUDIO_MODEL", "tts-1"),
            "VOICESTUDIO_MODEL",
        ),
        voicestudio_response_format=_required(
            values.get("VOICESTUDIO_RESPONSE_FORMAT", "wav"),
            "VOICESTUDIO_RESPONSE_FORMAT",
        ),
        voicestudio_speech_path=_required(
            values.get("VOICESTUDIO_SPEECH_PATH", "/v1/audio/speech"),
            "VOICESTUDIO_SPEECH_PATH",
        ),
        ffmpeg_path=_required(
            values.get("FFMPEG_PATH", "ffmpeg"),
            "FFMPEG_PATH",
        ),
        registry_path=Path(
            values.get(
                "NOVEL_AUDIO_VOICE_REGISTRY",
                "novel-audio-voicestudio.local.voices.json",
            )
        ),
        director_base_url=values.get("DIRECTOR_BASE_URL", ""),
        director_token=values.get("DIRECTOR_TOKEN", ""),
        request_timeout=_request_timeout(
            values.get("NOVEL_AUDIO_REQUEST_TIMEOUT", "40")
        ),
    )


def _request_timeout(value):
    if not isinstance(value, str) or not value.isdecimal():
        raise ValueError("NOVEL_AUDIO_REQUEST_TIMEOUT is invalid")
    seconds = int(value)
    if not 1 <= seconds < ANDROID_CALL_TIMEOUT:
        raise ValueError("NOVEL_AUDIO_REQUEST_TIMEOUT is invalid")
    return seconds


def _required(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(name + " is required")
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError(name + " contains control characters")
    return value


def _port(value):
    if not isinstance(value, str) or not value.isdecimal():
        raise ValueError("NOVEL_AUDIO_PORT is invalid")
    port = int(value)
    if not 1 <= port <= 65535:
        raise ValueError("NOVEL_AUDIO_PORT is invalid")
    return port


def _boolean(value, name):
    if value not in {"true", "false"}:
        raise ValueError(name + " is invalid")
    return value == "true"
