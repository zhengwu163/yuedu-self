"""Actual loopback upstream sockets; no real model or credential is used."""
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from errors import ProtocolError, ProviderTimeoutError
from lifecycle import RequestCancelled, RequestContext
from models import SynthesisRequest
from voicestudio import VoiceStudioSpeechProvider


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.started = threading.Event()
        self.release = threading.Event()
        self.mode = "blocked"
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                test.started.set()
                try:
                    if test.mode == "blocked":
                        test.release.wait(3)
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/ogg")
                    if test.mode != "no-length":
                        self.send_header("Content-Length", "40")
                    self.end_headers()
                    self.wfile.write(b"OggS")
                    self.wfile.flush()
                    if test.mode == "truncated":
                        return
                    if test.mode == "trickle":
                        for _ in range(36):
                            if test.release.wait(.04):
                                return
                            self.wfile.write(b"x")
                            self.wfile.flush()
                except OSError:
                    pass

        self.upstream = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(
            target=self.upstream.serve_forever, kwargs={"poll_interval": .01},
        )
        self.thread.start()
        self.addCleanup(self.stop_upstream)
        self.provider = VoiceStudioSpeechProvider(
            f"http://127.0.0.1:{self.upstream.server_port}",
            "fixture-token", "fixture-profile", lambda _: {"voice": "fixture"},
            response_format="ogg", request_timeout=2,
        )
        self.request = SynthesisRequest("测试", "voice", "zh-CN", 1.0)

    def stop_upstream(self):
        self.release.set()
        self.upstream.shutdown()
        self.upstream.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())

    def test_cancel_interrupts_upstream_before_response_headers(self):
        context = RequestContext(2)
        errors = []
        done = threading.Event()

        def run():
            try:
                self.provider.synthesize(self.request, context=context)
            except Exception as error:
                errors.append(error)
            finally:
                done.set()

        client = threading.Thread(target=run)
        client.start()
        try:
            self.assertTrue(self.started.wait(1))
            context.cancel()
            self.assertTrue(done.wait(.6), "cancel did not interrupt upstream I/O")
            self.assertIsInstance(errors[0], RequestCancelled)
        finally:
            self.release.set()
            client.join(3)
            self.assertFalse(client.is_alive())

    def test_trickle_response_obeys_absolute_deadline(self):
        self.mode = "trickle"
        started = time.monotonic()
        with self.assertRaises(ProviderTimeoutError):
            self.provider.synthesize(self.request, context=RequestContext(.15))
        self.assertLess(time.monotonic() - started, .65)

    def test_no_content_length_is_accepted(self):
        self.mode = "no-length"
        self.assertEqual(b"OggS", self.provider.synthesize(self.request).audio)

    def test_truncated_content_length_is_rejected(self):
        self.mode = "truncated"
        with self.assertRaises(ProtocolError):
            self.provider.synthesize(self.request)
