#!/usr/bin/env python3
import argparse
import hashlib
import json
import select
import socket
import threading
import time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from config import load_config
from director import ConfiguredDirectorProvider
from director_http import HttpDirectorProvider
from errors import ProviderError, ProviderTimeoutError
from gateway import NovelAudioGateway
from lifecycle import RequestContext
from protocol import MAX_JSON, strict_json_loads
from registry import VoiceRegistry
from voicestudio import VoiceStudioSpeechProvider


def build_gateway(config):
    discovery = VoiceStudioSpeechProvider(
        base_url=config.voicestudio_base_url,
        token=config.voicestudio_token,
        profile=config.voicestudio_profile,
        allow_remote=config.voicestudio_allow_remote,
        speech_path=config.voicestudio_speech_path,
        model=config.voicestudio_model,
        response_format=config.voicestudio_response_format,
        ffmpeg_path=config.ffmpeg_path,
        voice_resolver=lambda _: {},
    )
    registry = _load_registry(config, discovery)
    speech = VoiceStudioSpeechProvider(
        base_url=config.voicestudio_base_url,
        token=config.voicestudio_token,
        profile=registry.profile_revision,
        allow_remote=config.voicestudio_allow_remote,
        speech_path=config.voicestudio_speech_path,
        model=config.voicestudio_model,
        response_format=config.voicestudio_response_format,
        ffmpeg_path=config.ffmpeg_path,
        voice_resolver=lambda voice_id: {
            "voice": registry.resolve(voice_id).provider_ref,
        },
    )
    if config.director_base_url and config.director_token:
        director = HttpDirectorProvider(
            config.director_base_url,
            config.director_token,
        )
    else:
        director = ConfiguredDirectorProvider()
    return NovelAudioGateway(
        speech=speech,
        director=director,
        token=config.token,
        registry=registry,
    )


def _profile_revision(config, records=()):
    value = {
        "baseUrl": config.voicestudio_base_url,
        "allowRemote": config.voicestudio_allow_remote,
        "profile": config.voicestudio_profile,
        "profileRevision": config.voicestudio_profile_revision,
        "model": config.voicestudio_model,
        "responseFormat": config.voicestudio_response_format,
        "speechPath": config.voicestudio_speech_path,
        "ffmpegPath": config.ffmpeg_path,
        "voices": sorted(records, key=lambda item: item["voiceAssetId"]),
    }
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return "voicestudio-" + hashlib.sha256(encoded).hexdigest()[:16]


def _registry_records(speech):
    voices = speech.voices()
    if not voices:
        raise ValueError("VoiceStudio returned no profiles")
    return [
        {
            **voice.as_dict(),
            "providerRef": speech.provider_ref_for(voice.voice_asset_id),
        }
        for voice in voices
    ], voices


def _profile_revision_legacy(config):
    value = "\x00".join((
        config.voicestudio_profile,
        config.voicestudio_model,
        config.voicestudio_response_format,
        config.ffmpeg_path,
    ))
    return "voicestudio-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _load_registry(config, speech):
    path = Path(config.registry_path)
    existing = None
    if path.exists():
        existing = VoiceRegistry.load(path)
    try:
        records, _ = _registry_records(speech)
    except (ProviderError, ProviderTimeoutError):
        raise
    revision = _profile_revision(config, records)
    if existing is not None and existing.profile_revision == revision:
        return existing
    registry = VoiceRegistry.from_records(
        records,
        profile_revision=revision,
    )
    registry.save(path)
    return registry


def validate_bind_host(host, allow_lan=False):
    if not host or (host != "127.0.0.1" and not allow_lan):
        raise ValueError("loopback only unless LAN binding is explicitly enabled")


def create_server(config, gateway):
    validate_bind_host(config.host, config.allow_lan)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(5)
            self._input_timer = threading.Timer(10, self._close_input)
            self._input_timer.daemon = True
            self._input_timer.start()

        def finish(self):
            self._input_timer.cancel()
            try:
                super().finish()
            except OSError:
                pass

        def _close_input(self):
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        def do_GET(self):
            self._handle()

        def do_POST(self):
            self._handle()

        def _handle(self):
            try:
                authorizations = self.headers.get_all("Authorization", [])
                if len(authorizations) != 1 or not gateway.authorized(authorizations[0]):
                    self._write(401, {"Content-Type": "application/json"}, {
                        "error": {"code": "unauthorized"},
                    })
                    return
                body = None
                if self.command == "POST":
                    lengths = self.headers.get_all("Content-Length", [])
                    if self.headers.get("Transfer-Encoding") or len(lengths) != 1:
                        self._write(400, {"Content-Type": "application/json"}, {
                            "error": {"code": "invalid_framing"},
                        })
                        return
                    content_length = lengths[0]
                    if (
                        not content_length.isascii()
                        or not content_length.isdecimal()
                        or len(content_length) > 10
                    ):
                        self._write(400, {"Content-Type": "application/json"}, {
                            "error": {"code": "invalid_framing"},
                        })
                        return
                    if self.headers.get_content_type() != "application/json":
                        self._write(400, {"Content-Type": "application/json"}, {
                            "error": {"code": "invalid_content_type"},
                        })
                        return
                    length = int(content_length)
                    if length > MAX_JSON:
                        self._write(413, {"Content-Type": "application/json"}, {
                            "error": {"code": "too_large"},
                        })
                        return
                    body = self._read_body(length)
                self._input_timer.cancel()
                context = RequestContext(gateway.request_timeout)
                watcher_stop = threading.Event()
                watcher = threading.Thread(
                    target=self._watch_client,
                    args=(context, watcher_stop),
                    daemon=True,
                )
                watcher.start()
                try:
                    self._write(*gateway.respond(
                        self.command,
                        self.path,
                        authorizations[0],
                        body,
                        context=context,
                    ))
                except OSError:
                    context.cancel()
                finally:
                    watcher_stop.set()
                    context.cancel()
            except TimeoutError:
                self._write(408, {"Content-Type": "application/json"}, {
                    "error": {"code": "request_timeout"},
                })
            except (OSError, ValueError):
                self._write(400, {"Content-Type": "application/json"}, {
                    "error": {"code": "invalid_request"},
                })

        def _watch_client(self, context, stop):
            while not stop.wait(0.05):
                try:
                    readable, _, _ = select.select(
                        [self.connection],
                        [],
                        [],
                        0,
                    )
                    if readable and not self.connection.recv(
                        1,
                        socket.MSG_PEEK,
                    ):
                        context.cancel()
                        return
                except (OSError, ValueError):
                    context.cancel()
                    return

        def _read_body(self, length):
            raw = bytearray()
            deadline = time.monotonic() + 5
            while len(raw) < length:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError()
                self.connection.settimeout(remaining)
                chunk = self.rfile.read1(min(65536, length - len(raw)))
                if not chunk:
                    raise ValueError()
                raw.extend(chunk)
            return strict_json_loads(bytes(raw))

        def _write(self, status, headers, body):
            data = body if isinstance(body, bytes) else json.dumps(
                body,
                ensure_ascii=False,
                allow_nan=False,
            ).encode("utf-8")
            self.send_response(status)
            for key, value in headers.items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)

    class Server(ThreadingHTTPServer):
        daemon_threads = True
        slots = threading.BoundedSemaphore(8)

        def handle_error(self, *_):
            pass

        def shutdown(self):
            gateway.close()
            super().shutdown()

        def server_close(self):
            gateway.close()
            super().server_close()

        def process_request(self, request, address):
            if not self.slots.acquire(blocking=False):
                self.shutdown_request(request)
                return
            try:
                super().process_request(request, address)
            except Exception:
                self.slots.release()
                raise

        def process_request_thread(self, request, address):
            try:
                super().process_request_thread(request, address)
            finally:
                self.slots.release()

    return Server((config.host, config.port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--serve", action="store_true")
    args = parser.parse_args(argv)
    config = load_config()
    if args.check:
        gateway = build_gateway(config)
        print(json.dumps(gateway._health()[2], ensure_ascii=False))
        return 0
    if args.serve:
        server = create_server(config, build_gateway(config))
        try:
            print("NovelAudio VoiceStudio server ready")
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
        return 0
    parser.error("choose --check or --serve")


if __name__ == "__main__":
    raise SystemExit(main())
