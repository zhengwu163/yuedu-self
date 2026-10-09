import copy
import hashlib
import hmac
import inspect
import json
import threading
from collections import OrderedDict

from errors import (
    AuthenticationError,
    BusyError,
    InvalidRequestError,
    NotReadyError,
    ServiceError,
)
from models import VoiceAsset
from protocol import (
    parse_analysis,
    parse_synthesis,
    parse_voice_match,
)
from registry import VoiceRegistry
from lifecycle import RequestContext

ANALYSIS_CACHE_SIZE = 32


class _PendingAnalysis:
    def __init__(self):
        self.done = threading.Event()
        self.value = None


def _analysis_key(body):
    # 请求体已通过严格解析；按规范化 JSON 取摘要，字段顺序不同也视为同一请求。
    canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class NovelAudioGateway:
    def __init__(
        self,
        speech,
        director,
        token,
        registry=None,
        request_timeout=20,
    ):
        self.speech = speech
        self.director = director
        self.token = token
        self.registry = registry or self._registry_from_provider()
        self._generation = threading.Lock()
        self.request_timeout = request_timeout
        self._contexts = set()
        self._contexts_lock = threading.Lock()
        # 只在内存中保留最近的章节分析结果，进程重启即清空，不落盘正文衍生数据。
        self._analysis_cache = OrderedDict()
        self._analysis_pending = {}
        self._analysis_lock = threading.Lock()

    def authorized(self, authorization):
        return hmac.compare_digest(
            (authorization or "").encode("utf-8"),
            ("Bearer " + self.token).encode("utf-8"),
        )

    def respond(self, method, path, authorization, body, context=None):
        if not self.authorized(authorization):
            return self._error(AuthenticationError())
        owns_context = context is None
        context = context or RequestContext(self.request_timeout)
        self._register_context(context)
        try:
            if method == "GET" and path == "/v1/health":
                return self._health()
            if method == "GET" and path == "/v1/voices":
                return self._voices()
            if method != "POST":
                return self._error(InvalidRequestError())
            if path == "/v1/chapter/analyze":
                return self._analyze(body, context)
            if path == "/v1/voices/match":
                return self._match(body)
            if path == "/v1/voices/preview":
                return self._audio(
                    self.speech.preview,
                    parse_synthesis(body),
                    context,
                )
            if path == "/v1/tts/synthesize":
                return self._audio(
                    self.speech.synthesize,
                    parse_synthesis(body),
                    context,
                )
            return self._error(InvalidRequestError())
        except (ValueError, TypeError, KeyError):
            return self._error(InvalidRequestError())
        except ServiceError as error:
            return self._error(error)
        finally:
            self._unregister_context(context)
            if owns_context:
                context.cancel()

    def _health(self):
        speech = self.speech.health().ready
        director = self.director.health().ready
        return 200, {"Content-Type": "application/json"}, {
            "status": "ok",
            "apiVersion": "1",
            "directorReady": director,
            "ttsReady": speech,
            # Android 只对这里列出的操作扣设备试用额度；未声明的提供方按计费处理。
            "meteredOperations": [
                name for name, provider in (
                    ("analysis", self.director), ("tts", self.speech),
                )
                if getattr(provider, "metered", True)
            ],
        }

    def _voices(self):
        return 200, {"Content-Type": "application/json"}, {
            "voices": self.registry.public_voices(),
        }

    def _match(self, body):
        request = parse_voice_match(body)
        candidates = self.registry.match(
            {"traits": list(request.traits)},
            list(request.already_used_voice_ids),
            request.constraints,
        )
        return 200, {"Content-Type": "application/json"}, {
            "candidates": candidates,
        }

    def _analyze(self, body, context):
        request = parse_analysis(body)
        key = _analysis_key(body)
        with self._analysis_lock:
            cached = self._analysis_cache.get(key)
            if cached is not None:
                self._analysis_cache.move_to_end(key)
                return 200, {"Content-Type": "application/json"}, copy.deepcopy(cached)
            pending = self._analysis_pending.get(key)
            owner = pending is None
            if owner:
                pending = _PendingAnalysis()
                self._analysis_pending[key] = pending
        if not owner:
            # App 取消后立即重发同一章分析时，等进行中的那次结果，而不是回 429 让整章失败。
            if not pending.done.wait(context.remaining()):
                context.remaining()
            if pending.value is None:
                raise BusyError()
            return 200, {"Content-Type": "application/json"}, copy.deepcopy(pending.value)
        try:
            if not _invoke(self.director.health, context=context).ready:
                raise NotReadyError()
            with self._generation_slot():
                value = _invoke(self.director.analyze, request, context=context)
            with self._analysis_lock:
                # 同一章节文本的分析结果可复用：后续合成失败重试时不再重复消耗分析额度。
                self._analysis_cache[key] = copy.deepcopy(value)
                while len(self._analysis_cache) > ANALYSIS_CACHE_SIZE:
                    self._analysis_cache.popitem(last=False)
            pending.value = value
            return 200, {"Content-Type": "application/json"}, value
        finally:
            with self._analysis_lock:
                self._analysis_pending.pop(key, None)
            pending.done.set()

    def _audio(self, operation, request, context):
        if not _invoke(self.speech.health, context=context).ready:
            raise NotReadyError()
        with self._generation_slot():
            result = _invoke(operation, request, context=context)
        return 200, {
            "Content-Type": result.content_type,
            "X-TTS-Profile": result.profile,
        }, result.audio

    def _generation_slot(self):
        class Slot:
            def __init__(self, lock):
                self.lock = lock

            def __enter__(self):
                if not self.lock.acquire(blocking=False):
                    raise BusyError()

            def __exit__(self, *_):
                self.lock.release()

        return Slot(self._generation)

    def _registry_from_provider(self):
        records = []
        for voice in self.speech.voices():
            if isinstance(voice, VoiceAsset):
                records.append({
                    **voice.as_dict(),
                    "providerRef": voice.voice_asset_id,
                })
        return VoiceRegistry.from_records(
            records,
            profile_revision="provider-discovered-v1",
        )

    def _register_context(self, context):
        with self._contexts_lock:
            self._contexts.add(context)

    def _unregister_context(self, context):
        with self._contexts_lock:
            self._contexts.discard(context)

    def close(self):
        with self._contexts_lock:
            contexts = list(self._contexts)
        for context in contexts:
            context.cancel()

    @staticmethod
    def _error(error):
        return error.status, {"Content-Type": "application/json"}, {
            "error": {"code": error.code},
        }


def _invoke(operation, *args, context=None):
    parameters = inspect.signature(operation).parameters.values()
    if any(parameter.kind == inspect.Parameter.VAR_KEYWORD for parameter in parameters):
        return operation(*args, context=context)
    if "context" in inspect.signature(operation).parameters:
        return operation(*args, context=context)
    return operation(*args)
