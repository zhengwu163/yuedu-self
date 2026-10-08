"""Provider-neutral deterministic local voice assets."""

import hashlib
import json
import os
import stat
from dataclasses import dataclass
from pathlib import Path, PureWindowsPath

from audio import AudioError, validate_reference_wav
from scripts.novel_audio_server.protocol import MAX_AUDIO


class VoiceCatalogError(ValueError):
    """A voice catalog or its private reference assets are invalid."""

    def __init__(self, code, message=None):
        self.code = code
        super().__init__(message or code)


@dataclass(frozen=True)
class VoiceAsset:
    voice_asset_id: str
    display_name: str
    gender: str
    age_range: str
    traits: tuple
    preview_available: bool
    kind: str
    prompt: str = ""
    reference_audio: Path | None = None
    reference_text: str = ""
    narrator: bool = False
    reference_error: str = ""

    def public(self):
        return {
            "voiceAssetId": self.voice_asset_id,
            "displayName": self.display_name,
            "gender": self.gender,
            "ageRange": self.age_range,
            "traits": list(self.traits),
            "previewAvailable": self.preview_available,
        }


class VoiceCatalog:
    def __init__(self, path, root=None):
        self.path = Path(path)
        self.root = Path(root or self.path.parent).expanduser()
        if not self.root.is_absolute():
            self.root = self.root.absolute()
        data = json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or not isinstance(data.get("version"), str):
            raise VoiceCatalogError("invalid_voice_catalog")
        self.version = data["version"]
        raw = data.get("voices")
        if not isinstance(raw, list) or not raw:
            raise VoiceCatalogError("invalid_voice_catalog")
        assets = []
        ids = set()
        for item in raw:
            if not isinstance(item, dict):
                raise VoiceCatalogError("invalid_voice")
            voice_id = item.get("voiceAssetId")
            if not isinstance(voice_id, str) or not voice_id.startswith("local."):
                raise VoiceCatalogError("invalid_voice_id")
            if voice_id in ids:
                raise VoiceCatalogError("duplicate_voice_id")
            ids.add(voice_id)
            kind = item.get("kind")
            if kind not in {"voicedesign", "base"}:
                raise VoiceCatalogError("invalid_voice_kind")
            prompt = item.get("prompt", "")
            if kind == "voicedesign" and (
                not isinstance(prompt, str) or not prompt.strip()
            ):
                raise VoiceCatalogError("voice_design_prompt_required")
            reference = item.get("referenceAudio", "")
            reference_path = None
            reference_error = ""
            reference_text = item.get("referenceText", "")
            if kind == "base":
                reference_path, reference_error = self._reference_path(reference)
                if not isinstance(reference_text, str):
                    raise VoiceCatalogError("invalid_reference_text")
                reference_text = reference_text.strip()
            assets.append(
                VoiceAsset(
                    voice_asset_id=voice_id,
                    display_name=str(item.get("displayName", "")),
                    gender=str(item.get("gender", "unknown")),
                    age_range=str(item.get("ageRange", "adult")),
                    traits=tuple(str(value) for value in item.get("traits", [])),
                    preview_available=kind == "voicedesign" or reference_path is not None,
                    kind=kind,
                    prompt=prompt,
                    reference_audio=reference_path,
                    reference_text=reference_text,
                    narrator=bool(item.get("narrator", False)),
                    reference_error=reference_error,
                )
            )
        self._assets = tuple(assets)

    def _reference_path(self, reference):
        """Resolve only syntactically safe, service-root-relative references."""
        if not isinstance(reference, str):
            return None, "invalid_reference_audio"
        if not reference.strip():
            return None, "missing_reference_audio"
        if any(ord(char) < 32 or ord(char) == 127 for char in reference) or ":" in reference:
            return None, "invalid_reference_audio"
        # Accept Windows separators without allowing drive-relative paths or ADS.
        relative = Path(reference.replace("\\", "/"))
        windows_relative = PureWindowsPath(reference)
        if (
            relative.is_absolute()
            or windows_relative.is_absolute()
            or windows_relative.drive
            or windows_relative.root
            or ".." in relative.parts
            or ".." in windows_relative.parts
        ):
            return None, "invalid_reference_audio"
        candidate = self.root / relative
        return candidate, ""

    def _has_symlink_component(self, path):
        try:
            relative = Path(path).relative_to(self.root)
        except ValueError:
            return True
        component = self.root
        if component.is_symlink() or os.path.isjunction(component):
            return True
        for part in relative.parts:
            component /= part
            if component.is_symlink() or os.path.isjunction(component):
                return True
        return False

    def _read_reference(self, asset):
        """Return a bounded, validated snapshot for both admission and hashing."""
        if asset.reference_error:
            raise VoiceCatalogError(asset.reference_error)
        path = asset.reference_audio
        if path is None:
            raise VoiceCatalogError("missing_reference_audio")
        try:
            if self._has_symlink_component(path):
                raise VoiceCatalogError("invalid_reference_audio")
            path.resolve().relative_to(self.root.resolve())
            metadata = path.lstat()
            if (
                not stat.S_ISREG(metadata.st_mode)
                or not 0 < metadata.st_size <= MAX_AUDIO
            ):
                raise VoiceCatalogError("invalid_reference_audio")
            # No-follow/nonblocking where supported; fstat catches replacement
            # with a special file, and the bounded read also covers file growth.
            flags = (
                os.O_RDONLY | getattr(os, "O_BINARY", 0)
                | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
            )
            with os.fdopen(os.open(path, flags), "rb") as source:
                opened = os.fstat(source.fileno())
                if (
                    not stat.S_ISREG(opened.st_mode)
                    or not 0 < opened.st_size <= MAX_AUDIO
                    or (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino)
                    or self._has_symlink_component(path)
                ):
                    raise VoiceCatalogError("invalid_reference_audio")
                content = source.read(MAX_AUDIO + 1)
            return validate_reference_wav(content)
        except VoiceCatalogError:
            raise
        except FileNotFoundError:
            raise VoiceCatalogError("missing_reference_audio") from None
        except (OSError, ValueError, RuntimeError, AudioError):
            raise VoiceCatalogError("invalid_reference_audio") from None

    def _reference_is_valid(self, asset):
        try:
            self._read_reference(asset)
            return True
        except VoiceCatalogError:
            return False

    def validate_references(self, capabilities):
        """Require valid Base assets only when the active profile can clone voices."""
        if "voice-clone" not in set(capabilities or ()):
            return
        base_assets = tuple(asset for asset in self._assets if asset.kind == "base")
        if not base_assets:
            raise VoiceCatalogError("missing_reference_audio")
        for asset in base_assets:
            self._read_reference(asset)

    def reference_audio_digest(self, capabilities):
        """Hash the active profile's Base IDs and verified reference content."""
        if "voice-clone" not in set(capabilities or ()):
            return hashlib.sha256(b"voice-design-no-reference-v1").hexdigest()
        # Hash the very bytes that passed validation, not a second unbounded
        # open of a path that could now point to different content.
        entries = [
            (asset.voice_asset_id, hashlib.sha256(self._read_reference(asset)).hexdigest())
            for asset in self._assets
            if asset.kind == "base"
        ]
        if not entries:
            raise VoiceCatalogError("missing_reference_audio")
        encoded = json.dumps(
            sorted(entries), ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @property
    def profile(self):
        identity = json.dumps(
            {
                "version": self.version,
                "voices": [asset.voice_asset_id for asset in self._assets],
            },
            ensure_ascii=False,
            sort_keys=True,
        ).encode("utf-8")
        return f"voices-{hashlib.sha256(identity).hexdigest()[:16]}"

    def public_voices(self, capabilities=None):
        return [asset.public() for asset in self._advertised(capabilities)]

    def contains(self, voice_asset_id, capabilities=None):
        return any(
            asset.voice_asset_id == voice_asset_id
            for asset in self._advertised(capabilities)
        )

    def asset(self, voice_asset_id, capabilities=None):
        # Public lists hide unusable Base entries; direct lookup must retain
        # their actionable error instead of turning absence into unknown_voice.
        for asset in self._eligible(capabilities):
            if asset.voice_asset_id == voice_asset_id:
                if asset.kind == "base":
                    self._read_reference(asset)
                return asset
        raise VoiceCatalogError("unknown_voice")

    def match(self, persona, used, constraints=None, capabilities=None):
        wanted = set(persona.get("traits", []))
        used = set(used or [])
        constraints = constraints or {}
        candidates = [
            asset
            for asset in self._advertised(capabilities)
            if not asset.narrator
        ]

        def score(asset):
            constraint_score = sum(
                getattr(asset, key) == value
                for key, value in constraints.items()
                if value
            )
            return (
                asset.voice_asset_id in used,
                -constraint_score,
                -len(wanted.intersection(asset.traits)),
                asset.voice_asset_id,
            )

        return [asset.public() for asset in sorted(candidates, key=score)]

    def _eligible(self, capabilities=None):
        active = (
            {"voice-design", "voice-clone"}
            if capabilities is None
            else set(capabilities)
        )
        return tuple(
            asset
            for asset in self._assets
            if (
                (asset.kind == "voicedesign" and "voice-design" in active)
                or (asset.kind == "base" and "voice-clone" in active)
            )
        )

    def _advertised(self, capabilities=None):
        return tuple(
            asset for asset in self._eligible(capabilities)
            if asset.preview_available
            and (asset.kind != "base" or self._reference_is_valid(asset))
        )
