"""Explicit-lease preview warmup and bounded long-segment timing; no text logs."""

import argparse
import json
import time
from pathlib import Path

from smoke_http import (
    SmokeError, _Client, _diagnostics, _header_value, _identity, _probe,
    _read_token, _require, load_config,
)
from scripts.novel_audio_server.protocol import MAX_AUDIO, synthesis_request


def han_count(text):
    return sum("\u3400" <= char <= "\u9fff" for char in text)


def split_text(text):
    """Target 50 Han characters, merging a short tail up to 60; <=1200 UTF-16."""
    chunks, current, han, units = [], [], 0, 0
    for char in text.strip():
        width = len(char.encode("utf-16-le")) // 2
        if current and (han >= 50 or units + width > 1200):
            chunks.append("".join(current))
            current, han, units = [], 0, 0
        current.append(char)
        han += han_count(char)
        units += width
    if current:
        tail = "".join(current)
        if chunks and han_count(chunks[-1] + tail) <= 60 and len((chunks[-1] + tail).encode("utf-16-le")) // 2 <= 1200:
            chunks[-1] += tail
        else:
            chunks.append(tail)
    return chunks


def prewarm_cycle(client, config, output, identity, voice, chunks, warmup=True):
    _require(bool(chunks) and all(0 < len(text.encode("utf-16-le")) // 2 <= 1200 and han_count(text) <= 60 for text in chunks))
    lease = None
    report = {"warmup": warmup, "synthesizeSeconds": [], "audio": []}
    try:
        started = time.monotonic()
        value = client.json("POST", "/v1/runtime/acquire", {
            "sessionId": "prewarm-" + str(time.time_ns()), "purpose": "auto_prefetch", "expectedChapterCount": 1,
        })
        lease = value.get("leaseId")
        _require(_header_value(lease))
        _identity(value, identity)
        report["acquireSeconds"] = round(time.monotonic() - started, 1)

        def audio(route, text, filename):
            payload = synthesis_request({"text": text, "voiceAssetId": voice, "language": "zh-CN", "speed": 1.0})
            started = time.monotonic()
            status, headers, raw = client.request("POST", route, payload, lease, audio=True)
            finished = time.monotonic()
            _require(status == 200, "http_error")
            _require(headers.get("x-tts-profile") == identity, "identity_mismatch")
            _require(headers.get("content-type", "").split(";")[0] == "audio/ogg" and 27 <= len(raw) <= MAX_AUDIO and raw.startswith(b"OggS"), "invalid_audio")
            path = output / filename
            with path.open("xb") as stream:
                stream.write(raw)
            return finished, round(finished - started, 1), _probe(config, path)

        preview_finished = None
        if warmup:
            preview_finished, report["previewSeconds"], metrics = audio("/v1/voices/preview", chunks[0][:8], "preview.ogg")
            report["previewAudio"] = metrics
        for number, text in enumerate(chunks, 1):
            finished, seconds, metrics = audio("/v1/tts/synthesize", text, f"long-{number}.ogg")
            report["synthesizeSeconds"].append(seconds)
            report["audio"].append(metrics)
            if number == 1 and preview_finished is not None:
                report["firstLongFromPreviewSeconds"] = round(finished - preview_finished, 1)
        report["ttsWithin30Seconds"] = all(seconds < 30 for seconds in report["synthesizeSeconds"])
        return report
    finally:
        if lease is not None:
            released = client.json("POST", "/v1/runtime/release", lease=lease)
            _require(released.get("state") == "idle", "release_failed")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--text-file", required=True)
    parser.add_argument("--voice", required=True)
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--cold", action="store_true")
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        path = Path(args.text_file)
        _require(path.resolve().is_relative_to(config.root / "diagnostics") and path.is_file(), "diagnostics_failed")
        _require(path.stat().st_size <= 64 * 1024)
        chunks = split_text(path.read_text(encoding="utf-8"))
        _require(1 <= args.cycles <= 10)
        client = _Client(config, _read_token(config))
        identity = _identity(client.json("GET", "/v1/runtime/status"))
        output = _diagnostics(config)
        report = {"profile": config.active_profile_id, "hanCounts": [han_count(text) for text in chunks], "cycles": []}
        for number in range(1, args.cycles + 1):
            directory = output / f"cycle-{number}"
            directory.mkdir()
            result = prewarm_cycle(client, config, directory, identity, args.voice, chunks, warmup=not args.cold)
            report["cycles"].append(result)
            (output / "timings.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(json.dumps({"cycle": number, "profile": config.active_profile_id, **result}), flush=True)
        return 0
    except SmokeError as error:
        print(json.dumps({"status": "FAIL", "errorCode": error.code}))
        return 2
    except Exception:
        print(json.dumps({"status": "FAIL", "errorCode": "internal_error"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
