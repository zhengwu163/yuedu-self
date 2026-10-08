"""Hardened loopback HTTP transport for NovelAudioServer."""

import json
import select
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .protocol import MAX_JSON, strict_json_loads
from .api import RequestContext


def create_server(host, port, api, allow_lan=False):
    if host != "127.0.0.1" and not allow_lan:
        raise ValueError("loopback only")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

            def close_input():
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            self.input_timer = threading.Timer(10, close_input)
            self.input_timer.daemon = True
            self.input_timer.start()

        def finish(self):
            self.input_timer.cancel()
            try:
                super().finish()
            except OSError:
                pass

        def do_GET(self):
            self.handle_api()

        def do_POST(self):
            self.handle_api()

        def handle_api(self):
            self.request_context = RequestContext()
            self.monitor_stop = threading.Event()
            self.monitor = None
            try:
                result = self.prepare()
                self.input_timer.cancel()
                code, headers, body = result
                data = body if isinstance(body, bytes) else json.dumps(
                    body,
                    ensure_ascii=False,
                    allow_nan=False,
                ).encode("utf-8")
                self.send_response(code)
                for key, value in headers.items():
                    self.send_header(key, value)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)
                self.wfile.flush()
                self.request_context.complete()
            except OSError:
                self.request_context.cancel()
            finally:
                self.monitor_stop.set()
                if self.monitor is not None:
                    self.monitor.join(timeout=0.2)
                self.request_context.complete()

        def watch_disconnect(self):
            # Body parsing is finished; do not consume bytes belonging to it.
            # This server closes every connection, so later input is irrelevant.
            while not self.monitor_stop.wait(0.05):
                try:
                    readable, _, _ = select.select([self.connection], [], [], 0)
                    if readable and not self.connection.recv(4096):
                        self.request_context.cancel()
                        return
                except OSError:
                    self.request_context.cancel()
                    return

        def prepare(self):
            auths = self.headers.get_all("Authorization", [])
            if len(auths) != 1 or not api.authorized(auths[0]):
                return api.error(401, "unauthorized")

            sizes = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(sizes) > 1:
                return api.error(400, "invalid_framing")
            lease_headers = self.headers.get_all("X-NovelAudio-Lease", [])
            if len(lease_headers) > 1:
                return api.error(400, "invalid_framing")
            if sizes and (
                not sizes[0].isascii()
                or not sizes[0].isdecimal()
                or len(sizes[0]) > 10
            ):
                return api.error(400, "invalid_framing")
            size = int(sizes[0]) if sizes else 0
            if size > MAX_JSON:
                return api.error(413, "too_large")
            bodyless_release = (
                self.command == "POST"
                and self.path == "/v1/runtime/release"
                and size == 0
            )
            if (
                self.command == "POST"
                and not bodyless_release
                and self.headers.get_content_type() != "application/json"
            ):
                return api.error(400, "invalid_content_type")

            try:
                raw = bytearray()
                deadline = time.monotonic() + 5
                while len(raw) < size:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        return api.error(408, "request_timeout")
                    self.connection.settimeout(remaining)
                    chunk = self.rfile.read1(min(65536, size - len(raw)))
                    if not chunk:
                        return api.error(400, "incomplete_body")
                    raw.extend(chunk)
                body = strict_json_loads(bytes(raw)) if raw else None
            except (ValueError, TimeoutError):
                return api.error(400, "invalid_json")

            self.input_timer.cancel()
            self.connection.settimeout(5)
            contextual = getattr(api, "respond_with_context", None)
            args = (
                self.command,
                self.path,
                auths[0],
                body,
                lease_headers[0] if lease_headers else None,
            )
            if callable(contextual):
                self.monitor = threading.Thread(
                    target=self.watch_disconnect, daemon=True,
                    name="novel-audio-disconnect",
                )
                self.monitor.start()
                return contextual(*args, request_context=self.request_context)
            # BridgeApi predates contexts; never force new arguments onto it.
            return api.respond(*args)

    class Server(ThreadingHTTPServer):
        slots = threading.BoundedSemaphore(8)
        daemon_threads = True

        def server_close(self):
            api.close()
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

        def handle_error(self, *_):
            pass

    return Server((host, port), Handler)
