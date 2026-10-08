"""Bounded waveform validation and local Ogg/Opus encoding."""

import io
import json
import math
import shutil
import struct
import subprocess
import tempfile
import wave
from pathlib import Path

from scripts.novel_audio_server.protocol import MAX_AUDIO, strict_json_loads


MAX_DURATION_SECONDS = 180
MAX_PROBE_OUTPUT = 16 * 1024
PROCESS_TIMEOUT = 30.0


class AudioError(ValueError):
    """The model waveform or encoded audio is not safe to publish."""


def _bounded_pcm(value):
    try:
        value = float(value)
    except (TypeError, ValueError, OverflowError):
        raise AudioError() from None
    if not math.isfinite(value):
        raise AudioError()
    if -1.0 <= value <= 1.0:
        value *= 32767.0
    return max(-32768, min(32767, int(round(value))))


def _as_list(value):
    for method in ("detach", "cpu", "numpy"):
        method_value = getattr(value, method, None)
        if method_value is not None:
            value = method_value()
    tolist = getattr(value, "tolist", None)
    if tolist is not None:
        value = tolist()
    return value


def _waveform_samples(value):
    value = _as_list(value)
    if isinstance(value, (bytes, bytearray, memoryview)):
        raise AudioError()
    if not isinstance(value, (list, tuple)) or not value:
        raise AudioError()
    if isinstance(value[0], (list, tuple)):
        value = value[0]
    value = _as_list(value)
    if not isinstance(value, (list, tuple)) or not value:
        raise AudioError()
    try:
        return b"".join(
            int(_bounded_pcm(sample)).to_bytes(2, "little", signed=True)
            for sample in value
        )
    except (TypeError, ValueError, OverflowError):
        raise AudioError() from None


def _validate_reference_chunks(content):
    """Check RIFF lengths and PCM fields that wave intentionally does not check."""
    if (
        content[:4] != b"RIFF" or content[8:12] != b"WAVE"
        or int.from_bytes(content[4:8], "little") + 8 != len(content)
    ):
        raise AudioError()
    offset = 12
    while offset < len(content):
        if offset + 8 > len(content):
            raise AudioError()
        kind = content[offset:offset + 4]
        size = int.from_bytes(content[offset + 4:offset + 8], "little")
        start = offset + 8
        end = start + size
        if end > len(content):
            raise AudioError()
        if kind == b"fmt ":
            if size < 16:
                raise AudioError()
            # wave rounds 9..16 bits up to two bytes and ignores byte rate and
            # block alignment. Require the approved PCM16 header exactly.
            _, _, _, byte_rate, alignment, bits = struct.unpack_from("<HHIIHH", content, start)
            if (byte_rate, alignment, bits) != (48000, 2, 16):
                raise AudioError()
        elif kind == b"data" and size % 2:
            raise AudioError()
        offset = end + (size % 2)
    if offset != len(content):
        raise AudioError()


def _validate_wav_bytes(content, *, reference=False):
    if not isinstance(content, bytes) or not 0 < len(content) <= MAX_AUDIO:
        raise AudioError()
    if reference:
        _validate_reference_chunks(content)
    try:
        with wave.open(io.BytesIO(content), "rb") as source:
            channels = source.getnchannels()
            sample_width = source.getsampwidth()
            sample_rate = source.getframerate()
            frames = source.getnframes()
            if (
                channels not in (1, 2)
                or sample_width != 2
                or not 8000 <= sample_rate <= 48000
                or not 0 < frames <= sample_rate * MAX_DURATION_SECONDS
                or (reference and (channels != 1 or sample_rate != 24000))
            ):
                raise AudioError()
            # Check header bounds before decoding. One extra frame exposes an
            # incomplete trailing PCM frame that getnframes() rounds down.
            pcm = source.readframes(frames + 1)
            if len(pcm) != frames * channels * sample_width:
                raise AudioError()
    except (AudioError, EOFError, OSError, ValueError, wave.Error):
        raise AudioError() from None
    return content


def validate_reference_wav(content):
    """Validate complete 24kHz mono PCM16 WAV, <=16 MiB and <=180 seconds.

    Reference checks run in the lightweight Agent too: use only stdlib wave,
    never ffmpeg, model imports, or a generated replacement recording.
    """
    return _validate_wav_bytes(content, reference=True)


def waveform_to_wav(waveform, sample_rate=24000):
    """Convert the supported Qwen return shapes into validated PCM/WAV bytes."""
    if isinstance(waveform, bytes) and waveform.startswith(b"RIFF"):
        return _validate_wav_bytes(waveform)

    if (
        isinstance(waveform, tuple)
        and len(waveform) == 2
        and isinstance(waveform[1], (int, float))
        and not isinstance(waveform[1], bool)
    ):
        waveform, sample_rate = waveform
    if (
        isinstance(sample_rate, bool)
        or not isinstance(sample_rate, (int, float))
        or not math.isfinite(float(sample_rate))
        or int(sample_rate) != sample_rate
        or not 8000 <= int(sample_rate) <= 48000
    ):
        raise AudioError()

    pcm = _waveform_samples(waveform)
    output = io.BytesIO()
    try:
        with wave.open(output, "wb") as target:
            target.setnchannels(1)
            target.setsampwidth(2)
            target.setframerate(int(sample_rate))
            target.writeframes(pcm)
    except (OSError, wave.Error):
        raise AudioError() from None
    return _validate_wav_bytes(output.getvalue())


def _run(runner, command, timeout):
    try:
        result = runner(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired, ValueError):
        raise AudioError() from None
    if getattr(result, "returncode", 1) != 0:
        raise AudioError()
    return result


def _probe(path, ffprobe_path, runner, timeout):
    result = _run(
        runner,
        [
            str(ffprobe_path),
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=codec_type,channels,sample_rate,duration",
            "-of",
            "json",
            str(path),
        ],
        timeout,
    )
    output = getattr(result, "stdout", b"")
    if isinstance(output, str):
        output = output.encode("utf-8")
    if not isinstance(output, bytes) or len(output) > MAX_PROBE_OUTPUT:
        raise AudioError()
    try:
        value = strict_json_loads(output)
        if not isinstance(value, dict):
            raise AudioError()
        streams = value.get("streams")
        stream = streams[0] if isinstance(streams, list) and streams else None
        if not isinstance(stream, dict):
            raise AudioError()
        channels = int(stream.get("channels"))
        sample_rate = int(stream.get("sample_rate"))
        duration = float(stream.get("duration"))
        if (
            stream.get("codec_type") != "audio"
            or channels not in (1, 2)
            or not 8000 <= sample_rate <= 48000
            or not math.isfinite(duration)
            or not 0.0 < duration <= MAX_DURATION_SECONDS
        ):
            raise AudioError()
    except (AudioError, KeyError, IndexError, TypeError, ValueError, OverflowError):
        raise AudioError() from None


def _atempo_filter(speed):
    if (
        isinstance(speed, bool)
        or not isinstance(speed, (int, float))
        or not math.isfinite(float(speed))
        or not 0.5 <= float(speed) <= 2.0
    ):
        raise AudioError()
    speed = float(speed)
    if speed == 1.0:
        return None
    return f"atempo={speed:.6g}"


def encode_ogg_opus(
    waveform,
    speed,
    ffmpeg_path,
    ffprobe_path,
    temp_root,
    runner=None,
    timeout=PROCESS_TIMEOUT,
):
    """Encode one waveform, deleting all private temporary files on every path."""
    wav = waveform_to_wav(waveform)
    atempo = _atempo_filter(speed)
    process_runner = runner or subprocess.run
    temp_root = Path(temp_root)
    temp_root.mkdir(parents=True, exist_ok=True)
    directory = tempfile.mkdtemp(prefix=".novelaudio-", dir=str(temp_root))
    try:
        wav_path = Path(directory) / "input.wav"
        ogg_path = Path(directory) / "output.ogg"
        wav_path.write_bytes(wav)
        _probe(wav_path, ffprobe_path, process_runner, timeout)
        command = [
            str(ffmpeg_path),
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(wav_path),
        ]
        if atempo is not None:
            command.extend(["-filter:a", atempo])
        command.extend(
            [
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
                str(ogg_path),
            ]
        )
        _run(process_runner, command, timeout)
        _probe(ogg_path, ffprobe_path, process_runner, timeout)
        try:
            encoded = ogg_path.read_bytes()
        except OSError:
            raise AudioError() from None
        if not 0 < len(encoded) <= MAX_AUDIO or not encoded.startswith(b"OggS"):
            raise AudioError()
        return encoded
    except (AudioError, OSError, ValueError, TypeError):
        raise AudioError() from None
    finally:
        shutil.rmtree(directory, ignore_errors=True)
