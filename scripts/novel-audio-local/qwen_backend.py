"""Lazy Qwen3.5/Qwen3-TTS adapters.

This module is imported by the lightweight Agent and the Worker bootstrap. It
therefore must not import torch or qwen_tts at module import time. Heavy
dependencies are imported only when the Worker starts a real generation.
"""

import base64
import json
import math
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from audio import AudioError, encode_ogg_opus
from backend import LocalModelBackend
from voices import VoiceCatalogError
from resource_gate import ensure_resources
from scripts.novel_audio_server.errors import (
    BackendError,
    InvalidBackendResponseError,
    MissingReferenceAudioError,
    RunnerIncompatibleError,
    RunnerUnavailableError,
)
from scripts.novel_audio_server.protocol import (
    MAX_JSON,
    analysis_response,
    load_analysis_json,
    model_analysis_request,
    restore_model_unit_ids,
)


SYSTEM_PROMPT = (
    "你是中文小说台词归属分析器。只返回 JSON，包含 assignments 对象与 newCharacters、"
    "aliasUpdates 两个数组。assignments 以输入 unitId 为键、speakerId 为值，"
    '例如 "assignments":{"u1":"narrator"}，每个 unitId 必须恰好出现一次；'
    "speakerId 只能是 narrator、请求中的 characterId 或本响应的 temporaryId。"
)

REQUEST_TIMEOUT = 15.0
HEALTH_TIMEOUT = 2.0
PROCESS_STOP_TIMEOUT = 5.0
HEALTH_STATUSES = {"ok", "ready", "loading"}
READY_STATUSES = {"ok", "ready"}


# The compact assignment example alone leaves new character objects ambiguous.
# Keep every required field visible to the model; validation stays strict.
SYSTEM_PROMPT += (
    '\nnewCharacters must contain objects, never names alone. '
    'Use existing characterId values or new temporaryId values in assignments. '
    'The example illustrates structure only; do not copy its sample character. '
    'Include every input unit and all three top-level fields. '
    '\nResponse shape:\n'
    + json.dumps({
        'assignments': {'u1': 'new_1'},
        'newCharacters': [{
            'temporaryId': 'new_1', 'displayName': 'Example character',
            'gender': 'male', 'ageRange': 'adult',
            'voicePersona': {'traits': ['calm']},
        }],
        'aliasUpdates': [{'characterId': 'new_1', 'stableAliases': []}],
    }, ensure_ascii=False, separators=(',', ':'))
)


def _analysis_schema(model_request):
    """Constrain syntax at generation time; protocol validation checks references."""
    def obj(properties):
        return {'type': 'object', 'properties': properties,
                'required': list(properties), 'additionalProperties': False}
    text = {'type': 'string', 'minLength': 1, 'maxLength': 128}
    new_ids = [f'new_{i}' for i in range(1, len(model_request['units']) + 1)]
    known_ids = [c['characterId'] for c in model_request['characters']]
    speaker = {'type': 'string', 'enum': ['narrator'] + known_ids + new_ids}
    strings = {'type': 'array', 'items': text, 'maxItems': 32}
    character = obj({
        'temporaryId': {'type': 'string', 'enum': new_ids},
        'displayName': text, 'gender': text, 'ageRange': text,
        'voicePersona': obj({'traits': strings}),
    })
    # Generate characters first, so later assignments can reuse their IDs.
    return obj({
        'newCharacters': {'type': 'array', 'items': character, 'maxItems': 64},
        'assignments': obj({u['unitId']: speaker for u in model_request['units']}),
        'aliasUpdates': {'type': 'array', 'maxItems': 64, 'items': obj({
            'characterId': {'type': 'string', 'enum': known_ids + new_ids},
            'stableAliases': strings,
        })},
    })


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _safe_json_bytes(value):
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, OverflowError):
        raise InvalidBackendResponseError() from None
    if len(encoded) > MAX_JSON:
        raise InvalidBackendResponseError()
    return encoded


class QwenTextAdapter:
    """Windows-native llama-server adapter with bounded readiness polling."""

    def __init__(
        self,
        config,
        opener=None,
        runner=subprocess.Popen,
        request=None,
        profile=None,
        sleeper=time.sleep,
        clock=time.monotonic,
    ):
        self.config = config
        self.profile = profile
        self.runner = runner
        self.process = None
        self.opener = opener or build_opener(ProxyHandler({}), _NoRedirect())
        self.request = request or self._request
        self.sleeper = sleeper
        self.clock = clock
        self.base_url = f"http://127.0.0.1:{self.config.text.backend_port}"

    @property
    def model_name(self):
        return (
            self.profile.text_asset.asset_id
            if self.profile is not None
            else self.config.text.model
        )

    @property
    def model_path(self):
        return (
            self.profile.text_asset.path
            if self.profile is not None
            else self.config.text.model_path
        )

    def _command(self):
        return [
            str(self.config.text.runner),
            "--model",
            str(self.model_path),
            "--ctx-size",
            str(self.config.text.context_length),
            "--n-gpu-layers",
            str(self.config.text.gpu_layers),
            "--host",
            "127.0.0.1",
            "--port",
            str(self.config.text.backend_port),
        ]

    def analyze(self, value):
        # 模型只看短序号，回写后再按原请求严格校验，避免逐个复写哈希 ID。
        model_request, aliases = model_analysis_request(value)
        payload = {
            "model": self.model_name,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(model_request, ensure_ascii=False)},
            ],
            "temperature": 0.1,
            "stream": False,
            "response_format": {"type": "json_object", "schema": _analysis_schema(model_request)},
            "chat_template_kwargs": {"enable_thinking": False},
        }
        response = self.request(payload)
        try:
            content = response["choices"][0]["message"]["content"]
            parsed = restore_model_unit_ids(load_analysis_json(content), aliases)
            return analysis_response(parsed, value)
        except (KeyError, IndexError, TypeError, ValueError, OverflowError):
            raise InvalidBackendResponseError() from None

    def _probe_health(self):
        request = Request(
            self.base_url + "/health",
            headers={"Accept": "application/json"},
            method="GET",
        )
        try:
            with self.opener.open(request, timeout=HEALTH_TIMEOUT) as response:
                body = response.read(MAX_JSON + 1)
                status = getattr(response, "status", 200)
        except HTTPError as error:
            try:
                body = error.read(MAX_JSON + 1)
                status = error.code
            finally:
                error.close()
        except (OSError, URLError, ValueError):
            return None
        if not isinstance(body, bytes) or len(body) > MAX_JSON:
            return False
        if status < 200 or status >= 300:
            return False
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return False
        if not isinstance(value, dict):
            return False
        status = value.get("status")
        return status if status in HEALTH_STATUSES else False

    def _health(self):
        return self._probe_health() in READY_STATUSES

    def start(self):
        if self.process is not None:
            return

        # A healthy or HTTP-speaking process already owns this fixed port. Do
        # not mistake it for the process spawned by this adapter.
        if self._probe_health():
            raise RunnerUnavailableError()

        ensure_resources("text", profile_id=getattr(self.profile, "profile_id", None))
        command = self._command()
        try:
            process = self.runner(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except (OSError, ValueError):
            raise RunnerUnavailableError() from None
        self.process = process

        deadline = self.clock() + float(self.config.text.start_timeout)
        try:
            while self.clock() < deadline:
                if self._health():
                    return
                if self._process_exited():
                    raise RunnerIncompatibleError()
                self.sleeper(0.05)
        except (RunnerIncompatibleError, RunnerUnavailableError):
            self.close()
            raise
        self.close()
        raise RunnerUnavailableError()

    def _process_exited(self):
        poll = getattr(self.process, "poll", None)
        if poll is None:
            return False
        try:
            result = poll()
        except OSError:
            return True
        return result is not None

    def close(self):
        process, self.process = self.process, None
        if process is None:
            return
        try:
            if getattr(process, "poll", lambda: None)() is not None:
                return
        except OSError:
            return
        try:
            process.terminate()
            process.wait(timeout=PROCESS_STOP_TIMEOUT)
            return
        except (OSError, subprocess.TimeoutExpired):
            pass
        try:
            process.kill()
            process.wait(timeout=PROCESS_STOP_TIMEOUT)
        except (OSError, subprocess.TimeoutExpired):
            pass

    def _request(self, payload):
        payload = dict(payload)
        payload.setdefault(
            "response_format",
            {"type": "json_object"},
        )
        payload.setdefault(
            "chat_template_kwargs",
            {"enable_thinking": False},
        )
        body = _safe_json_bytes(payload)
        request = Request(
            self.base_url + "/v1/chat/completions",
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=REQUEST_TIMEOUT) as response:
                raw = response.read(MAX_JSON + 1)
                status = getattr(response, "status", 200)
        except HTTPError as error:
            try:
                if error.code in {301, 302, 303, 307, 308}:
                    raise RunnerUnavailableError() from None
            finally:
                error.close()
            raise RunnerUnavailableError() from None
        except (OSError, URLError, ValueError):
            raise RunnerUnavailableError() from None
        if status in {301, 302, 303, 307, 308}:
            raise RunnerUnavailableError()
        if not isinstance(raw, bytes) or len(raw) > MAX_JSON:
            raise InvalidBackendResponseError()
        try:
            result = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise InvalidBackendResponseError() from None
        if not isinstance(result, dict):
            raise InvalidBackendResponseError()
        return result


class QwenTtsAdapter:
    """Lazy Qwen3-TTS adapter with explicit VoiceDesign/Base dispatch."""

    def __init__(
        self,
        config,
        catalog,
        invoke=None,
        profile=None,
        model_loader=None,
        audio_encoder=None,
    ):
        self.config = config
        self.catalog = catalog
        self.profile = profile
        self.invoke = invoke or self._invoke
        self.model_loader = model_loader
        self.audio_encoder = audio_encoder or encode_ogg_opus
        self.model = None
        self.closed = False
        self.last_operation = None

    def _capabilities(self):
        return self.profile.capabilities if self.profile is not None else None

    def _model_path(self, kind):
        if self.profile is not None:
            return self.profile.tts_asset.path
        tts = getattr(self.config, "tts", None)
        if tts is None:
            return None
        if kind == "voicedesign":
            return tts.voice_design_model
        return tts.base_model

    def build_operation(self, request):
        try:
            asset = self.catalog.asset(request["voiceAssetId"], self._capabilities())
        except VoiceCatalogError as error:
            if error.code == "missing_reference_audio":
                raise MissingReferenceAudioError() from None
            raise
        except (KeyError, TypeError, ValueError):
            raise InvalidBackendResponseError() from None
        if asset.kind == "voicedesign":
            operation = {
                "operation": "voice_design",
                "text": request["text"],
                "language": "Chinese",
                "instruct": asset.prompt,
            }
        elif asset.kind == "base":
            if asset.reference_audio is None or asset.reference_error:
                raise MissingReferenceAudioError()
            operation = {
                "operation": "voice_clone",
                "text": request["text"],
                "language": "Chinese",
                "refAudio": str(asset.reference_audio),
                "refText": asset.reference_text or None,
                "xVectorOnlyMode": not bool(asset.reference_text),
            }
        else:
            raise RunnerIncompatibleError()
        operation["modelAssetId"] = (
            self.profile.tts_asset.asset_id
            if self.profile is not None
            else None
        )
        model_path = self._model_path(asset.kind)
        if model_path is not None:
            operation["modelPath"] = str(model_path)
        return operation

    def synthesize(self, request):
        if self.closed:
            raise BackendError()
        operation = self.build_operation(request)
        self.last_operation = dict(operation)
        result = self.invoke(operation)
        if isinstance(result, dict) and "audio" in result:
            try:
                audio = base64.b64decode(result["audio"], validate=True)
            except (TypeError, ValueError):
                raise InvalidBackendResponseError() from None
            if not audio:
                raise InvalidBackendResponseError()
            return audio
        try:
            return self.audio_encoder(
                result,
                request["speed"],
                self.config.tts.ffmpeg,
                self.config.tts.ffprobe,
                self.config.root / "state" / "audio-tmp",
            )
        except AudioError:
            raise InvalidBackendResponseError() from None

    def _load_model(self, model_path):
        if self.model is not None:
            return self.model
        if self.model_loader is not None:
            self.model = self.model_loader(model_path)
            return self.model
        ensure_resources("tts", profile_id=getattr(self.profile, "profile_id", None))
        try:
            import torch
            from qwen_tts import Qwen3TTSModel
        except ImportError:
            raise RunnerUnavailableError() from None
        try:
            self.model = Qwen3TTSModel.from_pretrained(
                str(model_path),
                device_map="cuda:0",
                dtype=torch.bfloat16,
                local_files_only=True,
            )
        except Exception:
            raise RunnerUnavailableError() from None
        return self.model

    def _invoke(self, operation):
        model = self._load_model(operation["modelPath"])
        try:
            if operation["operation"] == "voice_design":
                waveform = model.generate_voice_design(
                    text=operation["text"],
                    instruct=operation["instruct"],
                    language=operation["language"],
                )
            else:
                waveform = model.generate_voice_clone(
                    text=operation["text"],
                    language=operation["language"],
                    ref_audio=operation["refAudio"],
                    ref_text=operation["refText"],
                    x_vector_only_mode=operation["xVectorOnlyMode"],
                )
            return waveform
        except Exception:
            raise RunnerUnavailableError() from None

    def close(self):
        if self.closed:
            return
        self.closed = True
        model, self.model = self.model, None
        if model is None:
            return
        close = getattr(model, "close", None)
        if close is not None:
            try:
                close()
            except Exception:
                pass


class QwenBackend(LocalModelBackend):
    def __init__(self, config, catalog, text=None, tts=None, profile=None):
        self.runtime_profile = profile
        self.profile = (
            profile.identity
            if profile is not None
            else f"{config.profile}-{catalog.profile}"
        )
        self.catalog = catalog
        self.capabilities = (
            list(profile.capabilities)
            if profile is not None
            else ["chapter-analysis", "speech-synthesis", "voice-design", "voice-clone"]
        )
        self.text = text or QwenTextAdapter(config, profile=profile)
        self.tts = tts or QwenTtsAdapter(config, catalog, profile=profile)
        self.closed = False

    def analyze(self, request):
        if self.closed:
            raise BackendError()
        start = getattr(self.text, "start", None)
        if start is not None:
            start()
        result = self.text.analyze(request)
        return analysis_response(result, request)

    def synthesize(self, request):
        if self.closed:
            raise BackendError()
        try:
            self.catalog.asset(request["voiceAssetId"], self.capabilities)
        except VoiceCatalogError as error:
            if error.code == "missing_reference_audio":
                raise MissingReferenceAudioError() from None
            raise
        return self.tts.synthesize(request)

    def runtime_metadata(self):
        if self.runtime_profile is None:
            return {
                "profileId": self.profile,
                "identity": self.profile,
                "capabilities": [],
                "minVramGb": None,
                "hardware": {"status": "deferred"},
            }
        return {
            "profileId": self.runtime_profile.profile_id,
            "identity": self.profile,
            "capabilities": list(self.runtime_profile.capabilities),
            "minVramGb": self.runtime_profile.required_vram_gb,
            "hardware": {"status": "deferred"},
        }

    def close(self):
        if self.closed:
            return
        self.closed = True
        for adapter in (self.text, self.tts):
            close = getattr(adapter, "close", None)
            if close:
                close()
