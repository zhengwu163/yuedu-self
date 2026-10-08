import json
import os
from dataclasses import dataclass
from pathlib import Path

from models import VoiceAsset, VoiceMatchRequest
from protocol import strict_json_loads


@dataclass(frozen=True)
class _VoiceRecord:
    public: VoiceAsset
    provider_ref: str


class VoiceRegistry:
    def __init__(self, records, profile_revision):
        if not profile_revision:
            raise ValueError("missing profile revision")
        self._records = tuple(records)
        self.profile_revision = profile_revision
        ids = [record.public.voice_asset_id for record in self._records]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate voice asset id")

    @classmethod
    def from_records(cls, records, profile_revision="voice-studio-profile-v1"):
        result = []
        for item in records:
            if not isinstance(item, dict):
                raise ValueError("invalid voice record")
            public = VoiceAsset(
                voice_asset_id=_required_string(item.get("voiceAssetId")),
                display_name=_required_string(item.get("displayName")),
                gender=_required_string(item.get("gender")),
                age_range=_required_string(item.get("ageRange")),
                traits=tuple(_required_strings(item.get("traits", []))),
                preview_available=bool(item.get("previewAvailable", True)),
            )
            provider_ref = _required_string(item.get("providerRef"))
            result.append(_VoiceRecord(public, provider_ref))
        return cls(result, profile_revision)

    @classmethod
    def load(cls, path):
        raw = Path(path).read_bytes()
        value = strict_json_loads(raw)
        if not isinstance(value, dict):
            raise ValueError("invalid registry")
        return cls.from_records(
            value.get("voices", []),
            profile_revision=value.get("profileRevision"),
        )

    def save(self, path):
        path = Path(path)
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        payload = {
            "profileRevision": self.profile_revision,
            "voices": [
                {
                    **record.public.as_dict(),
                    "providerRef": record.provider_ref,
                }
                for record in self._records
            ],
        }
        pending = path.with_name(path.name + ".new")
        flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
        descriptor = os.open(pending, flags, 0o600)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump(payload, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(pending, path)
        finally:
            if pending.exists():
                pending.unlink()

    def public_voices(self):
        return [record.public.as_dict() for record in self._records]

    def resolve(self, voice_asset_id):
        for record in self._records:
            if record.public.voice_asset_id == voice_asset_id:
                return record
        raise KeyError(voice_asset_id)

    def match(self, request_or_persona, used=None, constraints=None):
        if isinstance(request_or_persona, VoiceMatchRequest):
            request = request_or_persona
        else:
            persona = request_or_persona
            if not isinstance(persona, dict):
                raise TypeError("expected voice persona")
            request = VoiceMatchRequest(
                traits=tuple(persona.get("traits", [])),
                already_used_voice_ids=tuple(used or []),
                constraints=dict(constraints or {}),
            )
        wanted = set(request.traits)
        used = set(request.already_used_voice_ids)

        def score(record):
            voice = record.public
            constraint_misses = sum(
                value != getattr(voice, _constraint_attribute(key))
                for key, value in request.constraints.items()
            )
            return (
                voice.voice_asset_id in used,
                constraint_misses,
                -len(wanted & set(voice.traits)),
                voice.voice_asset_id,
            )

        return [
            record.public.as_dict()
            for record in sorted(self._records, key=score)
            if record.public.voice_asset_id not in used
        ]


def _constraint_attribute(key):
    return {
        "gender": "gender",
        "ageRange": "age_range",
    }[key]


def _required_string(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("invalid voice record")
    return value


def _required_strings(value):
    if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
        raise ValueError("invalid voice traits")
    return value
