import hmac
import inspect
import threading

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
        if not _invoke(self.director.health, context=context).ready:
            raise NotReadyError()
        with self._generation_slot():
            value = _invoke(self.director.analyze, request, context=context)
        return 200, {"Content-Type": "application/json"}, value

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
