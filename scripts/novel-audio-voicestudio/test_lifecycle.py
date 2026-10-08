import http.client
import threading
import time
import unittest
from dataclasses import replace

from config import load_config
from gateway import NovelAudioGateway
from lifecycle import RequestCancelled
from models import AudioResult, HealthStatus, VoiceAsset
from registry import VoiceRegistry
from server import create_server
from lifecycle import DeadlineExceeded, RequestContext


class BlockingSpeech:
    def __init__(self):
        self.started = threading.Event()
        self.cancelled = threading.Event()

    def health(self):
        return HealthStatus(ready=True)

    def voices(self):
        return [
            VoiceAsset(
                voice_asset_id="voice",
                display_name="旁白",
                gender="unknown",
                age_range="adult",
                traits=("清晰",),
                preview_available=True,
            )
        ]

    def match(self, request):
        return self.voices()

    def preview(self, request, context=None):
        return self.synthesize(request, context=context)

    def synthesize(self, request, context=None):
        self.started.set()
        while True:
            try:
                context.wait(0.01)
            except RequestCancelled:
                self.cancelled.set()
                raise


class ReadyDirector:
    def health(self):
        return HealthStatus(ready=True)


class LifecycleTest(unittest.TestCase):
    def test_cancel_closes_registered_resources_and_marks_context(self):
        closed = threading.Event()
        context = RequestContext(timeout=10)
        context.register_closer(closed.set)

        context.cancel()

        self.assertTrue(context.cancelled)
        self.assertTrue(closed.is_set())

    def test_deadline_is_absolute(self):
        context = RequestContext(timeout=0.01)
        time.sleep(0.03)

        with self.assertRaises(DeadlineExceeded):
            context.remaining()

    def test_client_disconnect_cancels_inflight_provider(self):
        speech = BlockingSpeech()
        gateway = self._gateway(speech)
        server = self._server(gateway)
        thread = threading.Thread(
            target=server.serve_forever,
            kwargs={"poll_interval": 0.01},
        )
        thread.start()
        connection = http.client.HTTPConnection(
            *server.server_address,
            timeout=2,
        )
        try:
            connection.connect()
            body = b'{"text":"test","voiceAssetId":"voice","language":"zh-CN","speed":1}'
            connection.putrequest("POST", "/v1/tts/synthesize")
            connection.putheader("Authorization", "Bearer gateway-token")
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Content-Length", str(len(body)))
            connection.endheaders(body)
            self.assertTrue(speech.started.wait(1))
            connection.close()

            self.assertTrue(speech.cancelled.wait(1))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_server_shutdown_cancels_inflight_provider(self):
        speech = BlockingSpeech()
        gateway = self._gateway(speech)
        server = self._server(gateway)
        thread = threading.Thread(
            target=server.serve_forever,
            kwargs={"poll_interval": 0.01},
        )
        thread.start()
        connection = http.client.HTTPConnection(
            *server.server_address,
            timeout=2,
        )
        try:
            connection.connect()
            body = b'{"text":"test","voiceAssetId":"voice","language":"zh-CN","speed":1}'
            connection.putrequest("POST", "/v1/tts/synthesize")
            connection.putheader("Authorization", "Bearer gateway-token")
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Content-Length", str(len(body)))
            connection.endheaders(body)
            self.assertTrue(speech.started.wait(1))

            server.shutdown()

            self.assertTrue(speech.cancelled.wait(1))
        finally:
            connection.close()
            server.server_close()
            thread.join(2)

    @staticmethod
    def _gateway(speech):
        registry = VoiceRegistry.from_records([{
            "voiceAssetId": "voice",
            "displayName": "旁白",
            "gender": "unknown",
            "ageRange": "adult",
            "traits": ["清晰"],
            "providerRef": "local-profile",
        }])
        return NovelAudioGateway(
            speech=speech,
            director=ReadyDirector(),
            token="gateway-token",
            registry=registry,
        )

    @staticmethod
    def _server(gateway):
        config = replace(load_config({
            "NOVEL_AUDIO_TOKEN": "gateway-token",
            "VOICESTUDIO_BASE_URL": "http://127.0.0.1:3900",
            "VOICESTUDIO_TOKEN": "provider-token",
        }), port=0)
        return create_server(config, gateway)


if __name__ == "__main__":
    unittest.main()
