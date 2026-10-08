import json
import unittest

from director_http import HttpDirectorProvider
from errors import ProviderTimeoutError
from models import ChapterAnalysisRequest, Unit


class FakeTransport:
    def __init__(self, response, health_response=None):
        self.response = response
        self.health_response = health_response or (
            200,
            {"Content-Type": "application/json"},
            b'{"status":"ok","apiVersion":"1","directorReady":true}',
        )
        self.calls = []

    def __call__(self, method, path, payload):
        self.calls.append((method, path, payload))
        if path == "/v1/health":
            return self.health_response
        return self.response


def request():
    return ChapterAnalysisRequest(
        book_id="book",
        chapter_id="chapter",
        text_hash="hash",
        analysis_version="1",
        characters=(),
        units=(Unit("u1", "旁白"),),
        previous_context={"recentAssignments": []},
    )


class DirectorProviderTest(unittest.TestCase):
    def test_health_checks_upstream_readiness_and_protocol(self):
        for status, body, ready in (
            (200, {"status": "ok", "apiVersion": "1", "directorReady": True}, True),
            (200, {"status": "ok", "apiVersion": "1", "directorReady": False}, False),
            (200, {"status": "ok", "apiVersion": "2", "directorReady": True}, False),
            (200, {"status": "ok", "apiVersion": "1", "directorReady": "true"}, False),
            (200, {}, False),
            (401, {}, False),
            (503, {}, False),
        ):
            with self.subTest(status=status, body=body):
                transport = FakeTransport(
                    (200, {}, b"{}"),
                    health_response=(status, {}, json.dumps(body).encode()),
                )
                provider = HttpDirectorProvider(
                    "http://127.0.0.1:8787", "director-token", transport=transport,
                )
                self.assertEqual(ready, provider.health().ready)
                self.assertEqual([("GET", "/v1/health", None)], transport.calls)

    def test_health_timeout_is_not_ready(self):
        def transport(*args, **kwargs):
            raise ProviderTimeoutError()
        provider = HttpDirectorProvider(
            "http://127.0.0.1:8787", "director-token", transport=transport,
        )
        self.assertFalse(provider.health().ready)

    def test_projects_existing_analysis_service_response(self):
        transport = FakeTransport((
            200,
            {"Content-Type": "application/json"},
            b'{"assignments":[{"unitId":"u1","speakerId":"narrator"}],'
            b'"newCharacters":[],"aliasUpdates":[]}',
        ))
        provider = HttpDirectorProvider(
            "http://127.0.0.1:8787",
            "director-token",
            transport=transport,
        )

        result = provider.analyze(request())

        self.assertEqual(
            [{"unitId": "u1", "speakerId": "narrator"}],
            result["assignments"],
        )
        self.assertEqual(
            [("GET", "/v1/health", None), ("POST", "/v1/chapter/analyze", request().as_dict())],
            transport.calls,
        )

    def test_provider_response_cannot_rewrite_text(self):
        transport = FakeTransport((
            200,
            {"Content-Type": "application/json"},
            b'{"assignments":[{"unitId":"u1","speakerId":"narrator"}],'
            b'"newCharacters":[],"aliasUpdates":[],"text":"secret"}',
        ))
        provider = HttpDirectorProvider(
            "http://127.0.0.1:8787",
            "director-token",
            transport=transport,
        )

        result = provider.analyze(request())

        self.assertNotIn("text", result)


if __name__ == "__main__":
    unittest.main()
