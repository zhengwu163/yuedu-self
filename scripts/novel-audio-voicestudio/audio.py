import subprocess
import io
import wave

from errors import AudioError
from protocol import MAX_AUDIO


SUPPORTED_AUDIO_TYPES = {"audio/ogg", "audio/mp4", "audio/aac"}
SUPPORTED_WAV_TYPES = {"audio/wav", "audio/x-wav", "audio/wave"}


def validate_audio(content_type, body):
    if content_type not in SUPPORTED_AUDIO_TYPES:
        raise AudioError()
    if not isinstance(body, bytes) or not 0 < len(body) <= MAX_AUDIO:
        raise AudioError()
    if content_type == "audio/ogg" and not body.startswith(b"OggS"):
        raise AudioError()
    if content_type == "audio/mp4" and b"ftyp" not in body[:64]:
        raise AudioError()
    if content_type == "audio/aac" and not (body.startswith(b"\xff\xf1") or body.startswith(b"\xff\xf9")):
        raise AudioError()
    return content_type, body


def normalize_audio(
    content_type,
    body,
    ffmpeg_path="ffmpeg",
    runner=None,
    timeout=5,
):
    if content_type in SUPPORTED_AUDIO_TYPES:
        return validate_audio(content_type, body)
    if content_type not in SUPPORTED_WAV_TYPES:
        raise AudioError()
    if not isinstance(body, bytes) or not 0 < len(body) <= MAX_AUDIO:
        raise AudioError()

    try:
        with wave.open(io.BytesIO(body), "rb") as source:
            channels = source.getnchannels()
            sample_width = source.getsampwidth()
            sample_rate = source.getframerate()
            frames = source.getnframes()
            if (
                channels not in (1, 2)
                or sample_width != 2
                or not 8000 <= sample_rate <= 48000
                or not 0 < frames <= sample_rate * 180
            ):
                raise AudioError()
            pcm = source.readframes(frames)
            if len(pcm) != frames * channels * sample_width:
                raise AudioError()
    except (AudioError, EOFError, OSError, ValueError, wave.Error):
        raise AudioError() from None

    clean_wav = io.BytesIO()
    with wave.open(clean_wav, "wb") as target:
        target.setparams((
            channels,
            sample_width,
            sample_rate,
            0,
            "NONE",
            "not compressed",
        ))
        target.writeframes(pcm)

    command = [
        ffmpeg_path,
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-protocol_whitelist",
        "pipe",
        "-i",
        "pipe:0",
        "-map_metadata",
        "-1",
        "-vn",
        "-threads",
        "1",
        "-ac",
        "1",
        "-ar",
        "24000",
        "-c:a",
        "libopus",
        "-b:a",
        "48k",
        "-f",
        "ogg",
        "pipe:1",
    ]
    process_runner = runner or subprocess.run
    try:
        result = process_runner(
            command,
            input=clean_wav.getvalue(),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise AudioError() from None
    if result.returncode != 0:
        raise AudioError()
    return validate_audio("audio/ogg", result.stdout)
