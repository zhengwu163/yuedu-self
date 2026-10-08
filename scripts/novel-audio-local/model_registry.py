"""User-managed model assets and runtime profiles.

The registry deliberately knows model metadata and compatibility contracts, not
the implementation details of a particular model family. Concrete adapters are
selected by the ``adapter`` field and can be added without changing the
Android-facing protocol.
"""

import hashlib
import json
import math
import os
import re
from dataclasses import dataclass
from pathlib import Path

from scripts.novel_audio_server.protocol import strict_json_loads


class ModelRegistryError(ValueError):
    """The model registry is missing, unsafe, or internally inconsistent."""

    def __init__(self, code, message=None):
        self.code = code
        super().__init__(message or code)


_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
_AUDIO_ENCODING_REVISION = "ogg-opus-48khz-v1"
_SYNTHESIS_PARAMETER_REVISION = "qwen-tts-v1"
_CAPABILITY_ALIASES = {
    "chapter_analysis": "chapter-analysis",
    "voice_design": "voice-design",
    "voice_clone": "voice-clone",
    "speech_synthesis": "speech-synthesis",
}


def _text(value, name, limit=256, required=True):
    if value is None and not required:
        return ""
    if not isinstance(value, str) or (required and not value.strip()):
        raise ModelRegistryError(f"invalid {name}")
    if len(value) > limit or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ModelRegistryError(f"invalid {name}")
    return value


def _id(value, name):
    value = _text(value, name, 128)
    if not _ID.fullmatch(value):
        raise ModelRegistryError(f"invalid {name}")
    return value


def _capabilities(value, name):
    if not isinstance(value, list) or not value:
        raise ModelRegistryError(f"invalid {name}")
    result = []
    for item in value:
        capability = _text(item, name, 64)
        result.append(_CAPABILITY_ALIASES.get(capability, capability))
    if len(set(result)) != len(result):
        raise ModelRegistryError(f"duplicate {name}")
    return tuple(result)


def _has_symlink_component(path, trusted_root):
    path = Path(path)
    trusted_root = Path(trusted_root)
    try:
        common = Path(os.path.commonpath((path, trusted_root)))
    except ValueError:
        common = Path(path.anchor)
    component = common
    for part in path.relative_to(common).parts:
        component /= part
        if component.is_symlink():
            return True
    return False


def _vram(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelRegistryError(f"invalid {name}")
    value = float(value)
    if not 1.0 <= value <= 256.0:
        raise ModelRegistryError(f"invalid {name}")
    return value


def sha256_file(path, chunk_size=1024 * 1024):
    """Return a streaming SHA-256 digest for a regular, non-symlinked file."""
    path = Path(path)
    if path.is_symlink():
        raise ModelRegistryError("symlink_asset")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(chunk_size), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_directory_manifest(path):
    """Hash a directory's files as a sorted relative POSIX-path manifest."""
    original_root = Path(path)
    if original_root.is_symlink():
        raise ModelRegistryError("symlink_asset")
    root = original_root.resolve()
    entries = []
    for child in root.rglob("*"):
        relative = child.relative_to(root).as_posix()
        if child.is_symlink():
            raise ModelRegistryError("symlink_asset")
        if child.is_file():
            entries.append((relative, sha256_file(child)))
    entries.sort(key=lambda item: item[0])
    encoded = json.dumps(
        entries,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


@dataclass(frozen=True)
class AssetCheck:
    status: str
    code: str


@dataclass(frozen=True)
class ProfileCheck:
    status: str
    code: str
    asset_checks: tuple


@dataclass(frozen=True)
class ModelAsset:
    asset_id: str
    model_type: str
    family: str
    model_format: str
    path: Path
    sha256: str
    required_vram_gb: float
    capabilities: tuple
    adapter: str
    source: str
    license: str
    enabled: bool

    def public(self):
        return {
            "assetId": self.asset_id,
            "type": self.model_type,
            "family": self.family,
            "format": self.model_format,
            "adapter": self.adapter,
            "requiredVramGb": self.required_vram_gb,
            "capabilities": list(self.capabilities),
            "enabled": self.enabled,
        }


def verify_asset(asset):
    """Check that one configured asset still matches its declared content hash."""
    if not isinstance(asset.sha256, str) or not asset.sha256:
        return AssetCheck("FAIL", "missing_sha256")
    if not _SHA256.fullmatch(asset.sha256):
        return AssetCheck("FAIL", "invalid_sha256")
    try:
        if asset.path.is_symlink():
            return AssetCheck("FAIL", "symlink_asset")
        if not asset.path.exists():
            return AssetCheck("FAIL", "asset_missing")
        if asset.model_type == "text":
            if not asset.path.is_file():
                return AssetCheck("FAIL", "asset_type_invalid")
            actual = sha256_file(asset.path)
        elif asset.model_type == "tts":
            if not asset.path.is_dir():
                return AssetCheck("FAIL", "asset_type_invalid")
            actual = sha256_directory_manifest(asset.path)
        else:
            return AssetCheck("FAIL", "asset_type_invalid")
    except ModelRegistryError as error:
        return AssetCheck("FAIL", error.code)
    except OSError:
        return AssetCheck("FAIL", "asset_unreadable")
    if actual.lower() != asset.sha256.lower():
        return AssetCheck("FAIL", "asset_hash_mismatch")
    return AssetCheck("PASS", "ok")


@dataclass(frozen=True)
class RuntimeProfile:
    profile_id: str
    text_model_id: str
    tts_model_id: str
    min_vram_gb: float
    max_concurrency: int
    capabilities: tuple
    registry_version: str
    text_asset: ModelAsset
    tts_asset: ModelAsset

    @property
    def required_vram_gb(self):
        return max(
            self.min_vram_gb,
            self.text_asset.required_vram_gb,
            self.tts_asset.required_vram_gb,
        )

    @property
    def identity(self):
        payload = {
            "registryVersion": self.registry_version,
            "profileId": self.profile_id,
            "text": {
                "assetId": self.text_asset.asset_id,
                "sha256": self.text_asset.sha256,
                "adapter": self.text_asset.adapter,
            },
            "tts": {
                "assetId": self.tts_asset.asset_id,
                "sha256": self.tts_asset.sha256,
                "adapter": self.tts_asset.adapter,
            },
            "minVramGb": self.min_vram_gb,
            "capabilities": self.capabilities,
        }
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
        return f"{self.profile_id}-{hashlib.sha256(encoded).hexdigest()[:16]}"

    def public(self):
        return {
            "profileId": self.profile_id,
            "identity": self.identity,
            "textModel": self.text_model_id,
            "ttsModel": self.tts_model_id,
            "minVramGb": self.required_vram_gb,
            "maxConcurrency": self.max_concurrency,
            "capabilities": list(self.capabilities),
        }


def verify_profile(profile, catalog=None, available_vram_gb=None):
    """Validate the provider-neutral runtime contract for one profile."""
    checks = (verify_asset(profile.text_asset), verify_asset(profile.tts_asset))
    failed_check = next((item for item in checks if item.status != "PASS"), None)
    if failed_check is not None:
        return ProfileCheck("FAIL", failed_check.code, checks)
    if (
        profile.text_asset.model_type != "text"
        or profile.tts_asset.model_type != "tts"
    ):
        return ProfileCheck("FAIL", "asset_type_invalid", checks)
    if "chapter-analysis" not in profile.text_asset.capabilities:
        return ProfileCheck("FAIL", "capability_unavailable", checks)
    if "speech-synthesis" not in profile.tts_asset.capabilities:
        return ProfileCheck("FAIL", "capability_unavailable", checks)
    if profile.max_concurrency != 1:
        return ProfileCheck("FAIL", "invalid_max_concurrency", checks)
    if available_vram_gb is not None:
        if (
            isinstance(available_vram_gb, bool)
            or not isinstance(available_vram_gb, (int, float))
            or not math.isfinite(float(available_vram_gb))
        ):
            return ProfileCheck("FAIL", "invalid_available_vram", checks)
        if float(available_vram_gb) < profile.required_vram_gb:
            return ProfileCheck("BLOCKED", "insufficient_vram", checks)
    for operation in ("voice-design", "voice-clone"):
        if (
            operation in profile.capabilities
            and operation not in profile.tts_asset.capabilities
        ):
            return ProfileCheck("FAIL", "capability_unavailable", checks)
    return ProfileCheck("PASS", "ok", checks)


class ModelRegistry:
    def __init__(self, version, assets, profiles, active_profile_id):
        self.version = version
        self._assets = dict(assets)
        self._profiles = dict(profiles)
        self.active_profile_id = active_profile_id

    @classmethod
    def load(cls, path, root=None):
        path = Path(path)
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 256 * 1024:
            raise ModelRegistryError("invalid model registry")
        try:
            value = strict_json_loads(path.read_bytes())
        except (OSError, ValueError):
            raise ModelRegistryError("invalid model registry") from None
        if not isinstance(value, dict):
            raise ModelRegistryError("invalid model registry")

        allowed = {"version", "models", "profiles", "activeProfile"}
        if set(value) - allowed:
            raise ModelRegistryError("unknown model registry field")
        version = _text(value.get("version"), "version", 64)
        raw_assets = value.get("models")
        raw_profiles = value.get("profiles")
        if not isinstance(raw_assets, list) or not raw_assets:
            raise ModelRegistryError("invalid models")
        if not isinstance(raw_profiles, list) or not raw_profiles:
            raise ModelRegistryError("invalid profiles")

        assets = {}
        root = Path(root or path.parent).expanduser()
        if not root.is_absolute():
            root = root.absolute()
        asset_fields = {
            "assetId",
            "type",
            "family",
            "format",
            "path",
            "sha256",
            "requiredVramGb",
            "capabilities",
            "adapter",
            "source",
            "license",
            "enabled",
        }
        for raw in raw_assets:
            if not isinstance(raw, dict) or set(raw) - asset_fields:
                raise ModelRegistryError("invalid model asset")
            asset_id = _id(raw.get("assetId"), "assetId")
            if asset_id in assets:
                raise ModelRegistryError("duplicate assetId")
            model_type = _text(raw.get("type"), "model type", 16)
            if model_type not in {"text", "tts"}:
                raise ModelRegistryError("invalid model type")
            model_path = Path(_text(raw.get("path"), "model path", 4096)).expanduser()
            if not model_path.is_absolute():
                model_path = root / model_path
            if _has_symlink_component(model_path, root):
                raise ModelRegistryError("symlink_asset")
            try:
                model_path = model_path.resolve()
            except OSError:
                raise ModelRegistryError("invalid model path") from None
            checksum = raw.get("sha256", "")
            if checksum and (
                not isinstance(checksum, str) or not _SHA256.fullmatch(checksum)
            ):
                raise ModelRegistryError("invalid sha256")
            enabled = raw.get("enabled", True)
            if not isinstance(enabled, bool):
                raise ModelRegistryError("invalid enabled")
            assets[asset_id] = ModelAsset(
                asset_id=asset_id,
                model_type=model_type,
                family=_text(raw.get("family"), "family", 128),
                model_format=_text(raw.get("format"), "format", 64),
                path=model_path,
                sha256=checksum.lower(),
                required_vram_gb=_vram(raw.get("requiredVramGb"), "requiredVramGb"),
                capabilities=_capabilities(raw.get("capabilities"), "capabilities"),
                adapter=_text(raw.get("adapter"), "adapter", 128),
                source=_text(raw.get("source", ""), "source", 2048, required=False),
                license=_text(raw.get("license", ""), "license", 256, required=False),
                enabled=enabled,
            )

        profiles = {}
        profile_fields = {
            "profileId",
            "textModel",
            "ttsModel",
            "minVramGb",
            "maxConcurrency",
            "capabilities",
        }
        for raw in raw_profiles:
            if not isinstance(raw, dict) or set(raw) - profile_fields:
                raise ModelRegistryError("invalid runtime profile")
            profile_id = _id(raw.get("profileId"), "profileId")
            if profile_id in profiles:
                raise ModelRegistryError("duplicate profileId")
            text_model_id = _id(raw.get("textModel"), "textModel")
            tts_model_id = _id(raw.get("ttsModel"), "ttsModel")
            text_asset = assets.get(text_model_id)
            tts_asset = assets.get(tts_model_id)
            if (
                text_asset is None
                or tts_asset is None
                or text_asset.model_type != "text"
                or tts_asset.model_type != "tts"
                or not text_asset.enabled
                or not tts_asset.enabled
            ):
                raise ModelRegistryError("runtime profile references invalid model")
            max_concurrency = raw.get("maxConcurrency", 1)
            if type(max_concurrency) is not int or max_concurrency != 1:
                raise ModelRegistryError("invalid maxConcurrency")
            profiles[profile_id] = RuntimeProfile(
                profile_id=profile_id,
                text_model_id=text_model_id,
                tts_model_id=tts_model_id,
                min_vram_gb=_vram(raw.get("minVramGb"), "minVramGb"),
                max_concurrency=max_concurrency,
                capabilities=_capabilities(raw.get("capabilities"), "capabilities"),
                registry_version=version,
                text_asset=text_asset,
                tts_asset=tts_asset,
            )

        active = value.get("activeProfile")
        if active is None:
            active = next(iter(profiles))
        active = _id(active, "activeProfile")
        if active not in profiles:
            raise ModelRegistryError("unknown active profile")
        return cls(version, assets, profiles, active)

    def asset(self, asset_id):
        try:
            return self._assets[asset_id]
        except KeyError:
            raise ModelRegistryError("unknown model asset") from None

    def active_profile(self, profile_id=None):
        profile_id = profile_id or self.active_profile_id
        try:
            return self._profiles[profile_id]
        except KeyError:
            raise ModelRegistryError("unknown runtime profile") from None

    def profiles(self):
        return tuple(self._profiles.values())

    def public_profiles(self):
        return [profile.public() for profile in self._profiles.values()]

    def validate_hardware(self, available_vram_gb, profile_id=None):
        if (
            isinstance(available_vram_gb, bool)
            or not isinstance(available_vram_gb, (int, float))
            or not math.isfinite(float(available_vram_gb))
        ):
            raise ModelRegistryError("invalid available vram")
        profile = self.active_profile(profile_id)
        if float(available_vram_gb) < profile.required_vram_gb:
            raise ModelRegistryError("insufficient vram")
        return profile

    def verify_profile(self, profile_id=None, catalog=None, available_vram_gb=None):
        check = verify_profile(
            self.active_profile(profile_id),
            catalog=catalog,
            available_vram_gb=available_vram_gb,
        )
        if check.status != "PASS":
            raise ModelRegistryError(check.code)
        return check

    def profile_identity(self, profile, catalog_sha256, reference_sha256):
        """Build a path-free identity from verified runtime inputs."""
        check = verify_profile(profile)
        if check.status != "PASS":
            raise ModelRegistryError(check.code)
        if not isinstance(catalog_sha256, str) or not _SHA256.fullmatch(catalog_sha256):
            raise ModelRegistryError("invalid_catalog_sha256")
        if not isinstance(reference_sha256, str) or not _SHA256.fullmatch(reference_sha256):
            raise ModelRegistryError("invalid_reference_sha256")

        asset_hashes = {}
        for asset in (profile.text_asset, profile.tts_asset):
            if asset.model_type == "text":
                asset_hashes[asset.asset_id] = sha256_file(asset.path)
            else:
                asset_hashes[asset.asset_id] = sha256_directory_manifest(asset.path)
        payload = {
            "registryVersion": self.version,
            "profileId": profile.profile_id,
            "assets": [
                {
                    "assetId": asset.asset_id,
                    "type": asset.model_type,
                    "family": asset.family,
                    "format": asset.model_format,
                    "adapter": asset.adapter,
                    "sha256": asset_hashes[asset.asset_id],
                    "capabilities": sorted(set(asset.capabilities)),
                }
                for asset in (profile.text_asset, profile.tts_asset)
            ],
            "minVramGb": profile.min_vram_gb,
            "maxConcurrency": profile.max_concurrency,
            "capabilities": sorted(set(profile.capabilities)),
            "catalogSha256": catalog_sha256.lower(),
            "referenceSha256": reference_sha256.lower(),
            "audioEncodingRevision": _AUDIO_ENCODING_REVISION,
            "synthesisParameterRevision": _SYNTHESIS_PARAMETER_REVISION,
        }
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
        return f"{profile.profile_id}-{hashlib.sha256(encoded).hexdigest()[:16]}"
