import contextlib
import http.client
import json
import threading
import unittest
from types import SimpleNamespace

from scripts.novel_audio_server.api import NovelAudioApi
from scripts.novel_audio_server.http import create_server
from scripts.novel_audio_server.runtime import RuntimeState


class FakeBackend:
    profile = "fake-local-v1"
    ready = True

    def __init__(self):
        self.analysis_requests = []
        self.synthesis_requests = []
        self.fail_analysis = False
        self.fail_synthesis = False
        self.capabilities = ["chapter-analysis", "speech-synthesis", "voice-design"]

    def analyze(self, request):
        if self.fail_analysis:
            raise RuntimeError("backend secret")
        self.analysis_requests.append(request)
        return {
            "assignments": [
                {"unitId": item["unitId"], "speakerId": "narrator"}
                for item in request["units"]
            ],
            "newCharacters": [],
            "aliasUpdates": [],
        }

    def synthesize(self, request):
        if self.fail_synthesis:
            raise RuntimeError("backend secret")
        self.synthesis_requests.append(request)
        return b"OggS-fake-audio"

    def close(self):
        return None

    def runtime_metadata(self):
        return {
            "profileId": "fake-profile",
            "identity": self.profile,
            "capabilities": list(self.capabilities),
            "minVramGb": 24.0,
            "hardware": {"status": "unknown"},
        }


class FakeCatalog:
    def __init__(self):
        self.capability_calls = []

    def public_voices(self, capabilities=None):
        self.capability_calls.append(("public_voices", tuple(capabilities)))
        return [
            {
                "voiceAssetId": "local.narrator",
                "displayName": "旁白",
                "gender": "unknown",
                "ageRange": "adult",
                "traits": ["清晰"],
                "previewAvailable": True,
            }
        ]

    def match(self, persona, used, constraints=None, capabilities=None):
        self.capability_calls.append(("match", tuple(capabilities)))
        return self.public_voices(capabilities)

    def contains(self, voice_asset_id, capabilities=None):
        self.capability_calls.append(("contains", tuple(capabilities)))
        return voice_asset_id == "local.narrator"


class FakeRuntime:
    def __init__(self, backend):
        self.backend = backend
        self.lease_id = "lease-1"
        self.acquired = []
        self.begun = []
        self.ended = []
        self.released = []
        self.closed = False

    def acquire(self, session_id, purpose, expected_chapter_count):
        self.acquired.append(
            (session_id, purpose, expected_chapter_count)
        )
        return SimpleNamespace(
            lease_id=self.lease_id,
            session_id=session_id,
            purpose=purpose,
            expected_chapter_count=expected_chapter_count,
        )

    def begin_generation(self, lease_id):
        if lease_id != self.lease_id:
            raise ValueError("invalid")
        self.begun.append(lease_id)
        return self.backend

    def end_generation(self, lease_id):
        if lease_id != self.lease_id:
            raise ValueError("invalid")
        self.ended.append(lease_id)

    def validate(self, lease_id):
        if lease_id != self.lease_id:
            raise ValueError("invalid")
        return self.backend

    def release(self, lease_id):
        if lease_id != self.lease_id:
            raise ValueError("invalid")
        self.released.append(lease_id)

    def status(self):
        return SimpleNamespace(
            state=RuntimeState.IDLE,
            active_lease_count=0,
        )

    def close(self):
        self.closed = True


def analysis_request():
    return {
        "bookId": "book",
        "chapterId": "chapter",
        "textHash": "hash",
        "analysisVersion": "1",
        "characters": [],
        "units": [{"unitId": "u1", "text": "正文"}],
        "previousContext": {"recentAssignments": []},
    }


def synthesis_request():
    return {
        "text": "正文",
        "voiceAssetId": "local.narrator",
        "language": "zh-CN",
        "speed": 1.0,
    }


class HttpServerTest(unittest.TestCase):
    def setUp(self):
        self.backend = FakeBackend()
        self.runtime = FakeRuntime(self.backend)
        self.catalog = FakeCatalog()
        self.api = NovelAudioApi(
            token="local-token",
            backend=self.backend,
            catalog=self.catalog,
            runtime=self.runtime,
        )
        self.server = create_server("127.0.0.1", 0, self.api)
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            daemon=True,
        )
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(
        self,
        method,
        path,
        body=None,
        token="Bearer local-token",
        headers=None,
        payload=None,
    ):
        headers = dict(headers or {})
        headers["Authorization"] = token
        if payload is None and body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if payload is not None and "Content-Type" not in headers:
            headers["Content-Type"] = "application/json"
        with contextlib.closing(
            http.client.HTTPConnection(
                *self.server.server_address,
                timeout=3,
            )
        ) as connection:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def test_all_v1_routes_and_runtime_routes_over_http(self):
        self.assertEqual(200, self.request("GET", "/v1/health")[0])
        self.assertEqual(200, self.request("GET", "/v1/voices")[0])
        status, _, raw = self.request("GET", "/v1/runtime/status")
        self.assertEqual(200, status)
        status_payload = json.loads(raw)
        self.assertEqual("fake-local-v1", status_payload["runtimeProfile"])
        self.assertEqual(
            ["chapter-analysis", "speech-synthesis", "voice-design"],
            status_payload["runtimeProfileInfo"]["capabilities"],
        )
        # Phones split chapter text by this limit; it must be advertised on
        # both status and acquire so either entry point can configure it.
        self.assertEqual(50, status_payload["maxSegmentChars"])
        status, _, raw = self.request(
            "POST",
            "/v1/runtime/acquire",
            {
                "sessionId": "s1",
                "purpose": "auto_prefetch",
                "expectedChapterCount": 3,
            },
        )
        self.assertEqual(200, status)
        acquire_payload = json.loads(raw)
        lease_id = acquire_payload["leaseId"]
        self.assertEqual(
            "fake-local-v1",
            acquire_payload["runtimeProfileInfo"]["identity"],
        )
        self.assertEqual(50, acquire_payload["maxSegmentChars"])
        # Phones skip the metered cloud budget only for self-hosted leases.
        self.assertIs(True, acquire_payload["selfHosted"])
        lease_headers = {"X-NovelAudio-Lease": lease_id}
        self.assertEqual(
            200,
            self.request(
                "POST",
                "/v1/chapter/analyze",
                analysis_request(),
                headers=lease_headers,
            )[0],
        )
        self.assertEqual(
            200,
            self.request(
                "POST",
                "/v1/voices/match",
                {"voicePersona": {"traits": []}, "alreadyUsedVoiceIds": []},
            )[0],
        )
        for route in ("/v1/voices/preview", "/v1/tts/synthesize"):
            status, headers, audio = self.request(
                "POST",
                route,
                synthesis_request(),
                headers=lease_headers,
            )
            self.assertEqual(200, status)
            self.assertEqual("audio/ogg", headers["Content-Type"])
            self.assertEqual("fake-local-v1", headers["X-TTS-Profile"])
            self.assertEqual(b"OggS-fake-audio", audio)
        self.assertIn(
            (
                "contains",
                ("chapter-analysis", "speech-synthesis", "voice-design"),
            ),
            self.catalog.capability_calls,
        )

        status, _, raw = self.request(
            "POST",
            "/v1/runtime/release",
            headers={"X-NovelAudio-Lease": "lease-1"},
        )
        self.assertEqual(200, status)
        self.assertEqual("lease-1", self.runtime.released[0])
        self.assertEqual("idle", json.loads(raw)["state"])

    def test_unleased_generation_acquires_and_releases_automatically(self):
        status, _, body = self.request(
            "POST",
            "/v1/chapter/analyze",
            analysis_request(),
        )
        self.assertEqual(200, status)
        self.assertEqual(
            "narrator",
            json.loads(body)["assignments"][0]["speakerId"],
        )
        self.assertEqual(1, len(self.runtime.acquired))
        self.assertEqual(1, len(self.runtime.begun))
        self.assertEqual(1, len(self.runtime.ended))
        self.assertEqual(1, len(self.runtime.released))

    def test_unleased_backend_failure_still_releases_automatic_lease(self):
        self.backend.fail_analysis = True
        status, _, body = self.request(
            "POST",
            "/v1/chapter/analyze",
            analysis_request(),
        )

        self.assertEqual(503, status)
        self.assertEqual(
            {"error": {"code": "backend_unavailable"}},
            json.loads(body),
        )
        self.assertEqual(1, len(self.runtime.acquired))
        self.assertEqual(1, len(self.runtime.ended))
        self.assertEqual(1, len(self.runtime.released))

    def test_explicit_lease_does_not_acquire_or_release_automatically(self):
        status, _, _ = self.request(
            "POST",
            "/v1/chapter/analyze",
            analysis_request(),
            headers={"X-NovelAudio-Lease": "lease-1"},
        )

        self.assertEqual(200, status)
        self.assertEqual([], self.runtime.acquired)
        self.assertEqual(["lease-1"], self.runtime.begun)
        self.assertEqual(["lease-1"], self.runtime.ended)
        self.assertEqual([], self.runtime.released)

    def test_backend_failure_is_sanitized(self):
        self.backend.fail_analysis = True
        status, _, body = self.request(
            "POST",
            "/v1/chapter/analyze",
            analysis_request(),
            headers={"X-NovelAudio-Lease": "lease-1"},
        )
        self.assertEqual(503, status)
        self.assertEqual(
            {"error": {"code": "backend_unavailable"}},
            json.loads(body),
        )

    def test_runtime_metadata_rejects_unlisted_fields(self):
        self.backend.runtime_metadata = lambda: {
            "profileId": "fake-profile",
            "identity": self.backend.profile,
            "capabilities": ["chapter-analysis"],
            "minVramGb": 24.0,
            "hardware": {"status": "unknown"},
            "modelPath": "D:/private/model.gguf",
        }

        status, _, body = self.request("GET", "/v1/runtime/status")

        self.assertEqual(502, status)
        self.assertEqual(
            {"error": {"code": "invalid_backend_response"}},
            json.loads(body),
        )

    def test_unknown_voice_is_rejected_before_synthesis(self):
        status, _, body = self.request(
            "POST",
            "/v1/tts/synthesize",
            {
                **synthesis_request(),
                "voiceAssetId": "unknown.voice",
            },
            headers={"X-NovelAudio-Lease": "lease-1"},
        )
        self.assertEqual(400, status)
        self.assertEqual(
            {"error": {"code": "invalid_request"}},
            json.loads(body),
        )

    def test_lease_header_routes_generation_to_runtime_worker(self):
        status, _, _ = self.request(
            "POST",
            "/v1/chapter/analyze",
            analysis_request(),
            headers={"X-NovelAudio-Lease": "lease-1"},
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(self.backend.analysis_requests))

    def test_auth_is_checked_before_malformed_json(self):
        status, _, _ = self.request(
            "POST",
            "/v1/chapter/analyze",
            token="wrong",
        )
        self.assertEqual(401, status)

    def test_release_with_body_still_requires_json_content_type(self):
        status, _, _ = self.request(
            "POST",
            "/v1/runtime/release",
            token="Bearer local-token",
            headers={
                "X-NovelAudio-Lease": "lease-1",
                "Content-Type": "text/plain",
            },
            payload=b"not-json",
        )
        self.assertEqual(400, status)

    def test_duplicate_lease_headers_are_rejected(self):
        with contextlib.closing(
            http.client.HTTPConnection(*self.server.server_address, timeout=3)
        ) as connection:
            connection.putrequest("POST", "/v1/runtime/release")
            connection.putheader("Authorization", "Bearer local-token")
            connection.putheader("X-NovelAudio-Lease", "lease-1")
            connection.putheader("X-NovelAudio-Lease", "lease-2")
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(400, response.status)

    def test_public_bind_is_rejected(self):
        with self.assertRaises(ValueError):
            create_server("0.0.0.0", 0, self.api)

    def test_unknown_route_returns_not_found(self):
        self.assertEqual(
            404,
            self.request("GET", "/v1/runtime/status?secret=hidden")[0],
        )


class SegmentLimitTest(unittest.TestCase):
    def api(self, backend):
        api = NovelAudioApi.__new__(NovelAudioApi)
        api.backend = backend
        return api

    def test_backend_may_override_but_not_corrupt_the_segment_limit(self):
        from scripts.novel_audio_server.errors import InvalidBackendResponseError

        self.assertEqual(50, self.api(SimpleNamespace())._max_segment_chars())
        self.assertEqual(
            80, self.api(SimpleNamespace(max_segment_chars=80))._max_segment_chars()
        )
        for value in (0, 1201, True, "50", 5.0):
            with self.subTest(value=value), self.assertRaises(InvalidBackendResponseError):
                self.api(SimpleNamespace(max_segment_chars=value))._max_segment_chars()


if __name__ == "__main__":
    unittest.main()
