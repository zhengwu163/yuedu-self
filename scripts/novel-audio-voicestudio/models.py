from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class SynthesisRequest:
    text: str
    voice_asset_id: str
    language: str
    speed: float


@dataclass(frozen=True)
class Unit:
    unit_id: str
    text: str


@dataclass(frozen=True)
class ChapterAnalysisRequest:
    book_id: str
    chapter_id: str
    text_hash: str
    analysis_version: str
    characters: tuple[dict[str, Any], ...]
    units: tuple[Unit, ...]
    previous_context: dict[str, Any]

    def as_dict(self):
        return {
            "bookId": self.book_id,
            "chapterId": self.chapter_id,
            "textHash": self.text_hash,
            "analysisVersion": self.analysis_version,
            "characters": list(self.characters),
            "units": [
                {"unitId": unit.unit_id, "text": unit.text}
                for unit in self.units
            ],
            "previousContext": self.previous_context,
        }


@dataclass(frozen=True)
class VoiceMatchRequest:
    traits: tuple[str, ...]
    already_used_voice_ids: tuple[str, ...]
    constraints: dict[str, str]


@dataclass(frozen=True)
class VoiceAsset:
    voice_asset_id: str
    display_name: str
    gender: str
    age_range: str
    traits: tuple[str, ...]
    preview_available: bool

    def as_dict(self):
        return {
            "voiceAssetId": self.voice_asset_id,
            "displayName": self.display_name,
            "gender": self.gender,
            "ageRange": self.age_range,
            "traits": list(self.traits),
            "previewAvailable": self.preview_available,
        }


@dataclass(frozen=True)
class AudioResult:
    audio: bytes
    content_type: str
    profile: str


@dataclass(frozen=True)
class HealthStatus:
    ready: bool
