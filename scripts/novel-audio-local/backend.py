"""Provider-neutral local backend contracts and deterministic fake backend."""

import hashlib
import wave
from io import BytesIO

from scripts.novel_audio_server.protocol import analysis_response


class LocalModelBackend:
    profile = "local-runtime-v1"
    ready = True
    capabilities = ()

    def analyze(self, request):
        raise NotImplementedError

    def synthesize(self, request):
        raise NotImplementedError

    def close(self):
        pass


def _fake_ogg(seed):
    # Fake smoke checks transport and deterministic publication. Windows smoke
    # remains responsible for proving a real decoder accepts model output.
    digest = hashlib.sha256(seed.encode("utf-8")).digest()
    return b"OggS" + b"\x00" * 24 + digest


class FakeBackend(LocalModelBackend):
    def __init__(self, catalog, profile="fake-local-v1"):
        self.catalog = catalog
        self.profile = profile
        self.capabilities = [
            "chapter-analysis",
            "speech-synthesis",
            "voice-design",
        ]
        self.analysis_requests = []
        self.synthesis_requests = []
        self.closed = False

    def analyze(self, request):
        if self.closed:
            raise RuntimeError("closed")
        self.analysis_requests.append(request)
        speakers = ["narrator"] + [
            item["characterId"] for item in request["characters"]
        ]
        result = {
            "assignments": [
                {
                    "unitId": unit["unitId"],
                    "speakerId": speakers[index % len(speakers)],
                }
                for index, unit in enumerate(request["units"])
            ],
            "newCharacters": [],
            "aliasUpdates": [],
        }
        return analysis_response(result, request)

    def synthesize(self, request):
        if self.closed:
            raise RuntimeError("closed")
        self.catalog.asset(request["voiceAssetId"], self.capabilities)
        self.synthesis_requests.append(request)
        return _fake_ogg(request["text"] + request["voiceAssetId"])

    def close(self):
        self.closed = True
