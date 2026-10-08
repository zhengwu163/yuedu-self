from typing import Protocol

from models import (
    AudioResult,
    ChapterAnalysisRequest,
    HealthStatus,
    SynthesisRequest,
    VoiceAsset,
    VoiceMatchRequest,
)


class SpeechProvider(Protocol):
    def health(self) -> HealthStatus: ...

    def voices(self) -> list[VoiceAsset]: ...

    def match(self, request: VoiceMatchRequest) -> list[VoiceAsset]: ...

    def preview(self, request: SynthesisRequest) -> AudioResult: ...

    def synthesize(self, request: SynthesisRequest) -> AudioResult: ...


class DirectorProvider(Protocol):
    def health(self) -> HealthStatus: ...

    def analyze(self, request: ChapterAnalysisRequest) -> dict: ...
