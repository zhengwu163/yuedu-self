"""Stable, non-model-loading checks for the local model service CLI."""

import json
import os
from dataclasses import dataclass
from pathlib import Path

from config import ConfigError, load_config
from model_registry import ModelRegistryError, sha256_file, verify_asset
from runtime_probe import RUNTIME_CODES, RUNTIME_NAMES, probe_runtime
from voices import VoiceCatalog, VoiceCatalogError


_STATUSES = {"PASS", "FAIL", "BLOCKED", "NOT_CHECKED"}
_NAMES = set(RUNTIME_NAMES) | {
    "config", "tokenFile", "voiceCatalog", "modelRegistry", "profileIdentity",
    "textModel", "textRunner", "ttsVoiceDesignModel", "ttsBaseModel", "ttsModel",
    "ttsPython", "ffmpeg", "ffprobe",
}
_ASSET_CODES = {
    "missing_sha256", "invalid_sha256", "symlink_asset", "asset_missing",
    "asset_type_invalid", "asset_unreadable", "asset_hash_mismatch",
}
_CODES = RUNTIME_CODES | _ASSET_CODES | {
    "invalid_config", "token_missing", "invalid_voice_catalog", "not_configured",
    "registry_missing", "registry_unavailable", "registry_invalid", "identity_unavailable",
    "catalog_unavailable", "not_selected", "deferred_to_windows", "not_run",
}
_EXIT_CODES = {
    "PASS": 0,
    "FAIL": 2,
    "BLOCKED": 3,
    "NOT_CHECKED": 4,
}


@dataclass(frozen=True)
class CheckResult:
    name: str
    status: str
    code: str

    def __post_init__(self):
        if self.status not in _STATUSES:
            raise ValueError("invalid check status")
        if not isinstance(self.name, str) or self.name not in _NAMES:
            raise ValueError("invalid check name")
        if not isinstance(self.code, str) or self.code not in _CODES:
            raise ValueError("invalid check code")

    def to_dict(self):
        return {"status": self.status, "code": self.code}


class CheckReport:
    def __init__(self):
        self.results = {}

    def add(self, result):
        if not isinstance(result, CheckResult):
            raise TypeError("check result required")
        self.results[result.name] = result

    def overall(self, require_windows_runtime=False):
        if any(item.status == "FAIL" for item in self.results.values()):
            return "FAIL"
        if any(item.status == "BLOCKED" for item in self.results.values()):
            return "BLOCKED"
        if require_windows_runtime and any(
            name not in self.results or self.results[name].status == "NOT_CHECKED"
            for name in RUNTIME_NAMES
        ):
            return "NOT_CHECKED"
        return "PASS"

    def exit_code(self, require_windows_runtime=False):
        overall = self.overall(require_windows_runtime)
        return _EXIT_CODES[overall]

    def to_dict(self, require_windows_runtime=False):
        return {
            "overall": self.overall(require_windows_runtime),
            "exitCode": self.exit_code(require_windows_runtime),
            "checks": {
                name: self.results[name].to_dict()
                for name in sorted(self.results)
            },
        }

    def to_json(self, require_windows_runtime=False):
        return json.dumps(
            self.to_dict(require_windows_runtime),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )


def _result(name, status, code):
    return CheckResult(name, status, code)


def _path_result(name, path, directory=False):
    exists = Path(path).is_dir() if directory else Path(path).is_file()
    return _result(name, "PASS", "ok") if exists else _result(
        name,
        "FAIL",
        "asset_missing",
    )


def _catalog_result(config):
    if not config.voice_catalog.is_file():
        return _result("voiceCatalog", "FAIL", "asset_missing")
    try:
        VoiceCatalog(config.voice_catalog, config.root)
    except (OSError, ValueError, VoiceCatalogError):
        return _result("voiceCatalog", "FAIL", "invalid_voice_catalog")
    return _result("voiceCatalog", "PASS", "ok")


def _registry_results(config, catalog, registry=None, profile=None):
    if config.model_registry_path is None:
        result = _result("modelRegistry", "NOT_CHECKED", "not_configured")
        return result, _result("profileIdentity", "NOT_CHECKED", "not_configured")
    if not config.model_registry_path.is_file():
        result = _result("modelRegistry", "FAIL", "registry_missing")
        return result, _result("profileIdentity", "NOT_CHECKED", "registry_unavailable")
    try:
        registry = registry or config.load_model_registry()
        profile = profile or registry.active_profile(config.active_profile_id)
        registry.verify_profile(
            config.active_profile_id,
            catalog=catalog,
        )
        catalog.validate_references(profile.capabilities)
        identity = registry.profile_identity(
            profile,
            sha256_file(catalog.path),
            catalog.reference_audio_digest(profile.capabilities),
        )
        if not identity:
            return (
                _result("modelRegistry", "PASS", "ok"),
                _result("profileIdentity", "FAIL", "identity_unavailable"),
            )
    except (ConfigError, ModelRegistryError, OSError, ValueError):
        result = _result("modelRegistry", "FAIL", "registry_invalid")
        return result, _result("profileIdentity", "FAIL", "identity_unavailable")
    return (
        _result("modelRegistry", "PASS", "ok"),
        _result("profileIdentity", "PASS", "ok"),
    )


def _model_results(config, profile):
    if profile is None:
        return (
            _path_result("textModel", config.text.model_path),
            _path_result("ttsVoiceDesignModel", config.tts.voice_design_model, directory=True),
            _path_result("ttsBaseModel", config.tts.base_model, directory=True),
        )
    # Registry bindings override legacy model paths. Verify only the active pair.
    results = []
    text_check = verify_asset(profile.text_asset)
    tts_check = verify_asset(profile.tts_asset)
    results.append(_result("textModel", text_check.status, text_check.code))
    operations = {"ttsVoiceDesignModel": "voice-design", "ttsBaseModel": "voice-clone"}
    for name, operation in operations.items():
        if operation in profile.capabilities:
            results.append(_result(name, tts_check.status, tts_check.code))
        else:
            results.append(_result(name, "NOT_CHECKED", "not_selected"))
    if not set(operations.values()).intersection(profile.capabilities):
        results.append(_result("ttsModel", tts_check.status, tts_check.code))
    return results


def run_checks(config_path, platform_name=None, *, runner=None):
    """No models/HTTP/audio; runner is an optional read-only subprocess double.

    CLI callers should pass the SAME require_windows_runtime flag to the
    returned report's to_json() and exit_code().
    """
    platform_name = platform_name or os.name
    report = CheckReport()
    try:
        config = load_config(config_path)
    except (ConfigError, OSError, ValueError):
        report.add(_result("config", "FAIL", "invalid_config"))
        return report

    report.add(_result("config", "PASS", "ok"))
    report.add(
        _result("tokenFile", "PASS", "ok")
        if config.token_file.is_file()
        else _result("tokenFile", "FAIL", "token_missing")
    )
    catalog = None
    if config.voice_catalog.is_file():
        try:
            catalog = VoiceCatalog(config.voice_catalog, config.root)
        except (OSError, ValueError, VoiceCatalogError):
            pass
    report.add(_catalog_result(config))
    registry = profile = None
    if config.model_registry_path is not None:
        try:
            registry = config.load_model_registry()
            profile = registry.active_profile(config.active_profile_id)
        except (ConfigError, ModelRegistryError, OSError, ValueError):
            pass
    for result in _model_results(config, profile):
        report.add(result)
    report.add(_path_result("textRunner", config.text.runner))
    report.add(_path_result("ttsPython", config.tts.python_executable))
    report.add(_path_result("ffmpeg", config.tts.ffmpeg))
    report.add(_path_result("ffprobe", config.tts.ffprobe))
    if catalog is None:
        report.add(_result("modelRegistry", "NOT_CHECKED", "catalog_unavailable"))
        report.add(_result("profileIdentity", "NOT_CHECKED", "catalog_unavailable"))
    else:
        registry_result, identity_result = _registry_results(config, catalog, registry, profile)
        report.add(registry_result)
        report.add(identity_result)

    runtime = probe_runtime(
        config, platform_name=platform_name, runner=runner,
        minimum_vram_gb=profile.required_vram_gb if profile is not None else None,
    )
    for name, (status, code) in runtime.items():
        report.add(_result(name, status, code))
    return report
