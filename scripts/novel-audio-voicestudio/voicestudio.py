import base64
import http.client
import inspect
import ipaddress
import json
import socket
import shutil
from dataclasses import dataclass, field
from urllib.parse import urlsplit, urlunsplit

from audio import normalize_audio
from errors import (
    AuthenticationError,
    BusyError,
    ProviderError,
    ProviderTimeoutError,
    ProtocolError,
)
from lifecycle import DeadlineExceeded, RequestCancelled, RequestContext
from models import AudioResult, HealthStatus, SynthesisRequest, VoiceAsset
from protocol import strict_json_loads


@dataclass(frozen=True)
class VoiceStudioConfig:
    base_url: str
    token: str = field(repr=False)
    profile: str
    allow_remote: bool = False
    speech_path: str = "/v1/audio/speech"
    model: str = "tts-1"
    response_format: str = "wav"
    ffmpeg_path: str = "ffmpeg"
    request_timeout: int = 20
    health_timeout: int = 5
    conversion_timeout: int = 5


class VoiceStudioSpeechProvider:
    # 本地或自建 VoiceStudio 合成不消耗云额度，Android 不应扣设备试用额度。
    metered = False

    def __init__(
        self,
        base_url,
        token,
        profile,
        voice_resolver,
        transport=None,
        allow_remote=False,
        speech_path="/v1/audio/speech",
        model="tts-1",
        response_format="wav",
        ffmpeg_path="ffmpeg",
        audio_runner=None,
        request_timeout=20,
        health_timeout=5,
        conversion_timeout=5,
    ):
        self.config = VoiceStudioConfig(
            base_url=_base_url(base_url, allow_remote),
            token=token,
            profile=profile,
            allow_remote=allow_remote,
            speech_path=speech_path,
            model=model,
            response_format=response_format,
            ffmpeg_path=ffmpeg_path,
            request_timeout=request_timeout,
            health_timeout=health_timeout,
            conversion_timeout=conversion_timeout,
        )
        self.voice_resolver = voice_resolver
        self.transport = transport or self._request
        self.audio_runner = audio_runner
        self._profile_refs = {}

    def health(self, context=None):
        if not self.config.base_url or not self.config.token:
            return HealthStatus(ready=False)
        if (
            self.config.response_format in {"wav", "wave", "x-wav"}
            and shutil.which(self.config.ffmpeg_path) is None
        ):
            return HealthStatus(ready=False)
        owns_context = context is None
        context = context or RequestContext(self.config.health_timeout)
        try:
            status, _, _ = _invoke_transport(
                self.transport,
                "GET",
                "/system/info",
                None,
                context,
            )
            return HealthStatus(ready=status == 200)
        except RequestCancelled:
            raise
        except (ProviderError, ProviderTimeoutError):
            return HealthStatus(ready=False)
        finally:
            if owns_context:
                context.cancel()

    def voices(self):
        status, _, body = self.transport("GET", "/v1/audio/voices", None)
        self._raise_for_status(status)
        try:
            payload = strict_json_loads(body)
            items = payload["voices"]
            if not isinstance(items, list):
                raise ValueError()
        except (KeyError, TypeError, ValueError):
            raise ProtocolError() from None

        result = []
        tags = self._profile_tags()
        for item in items:
            if not isinstance(item, dict) or item.get("type") != "profile":
                continue
            voice_id = item.get("voice_id")
            name = item.get("name")
            if not isinstance(voice_id, str) or not voice_id.strip():
                continue
            if not isinstance(name, str) or not name.strip():
                name = voice_id
            voice_asset_id = "voicestudio.profile." + voice_id
            self._profile_refs[voice_asset_id] = voice_id
            profile_tags = tags.get(voice_id, ())
            result.append(VoiceAsset(
                voice_asset_id=voice_asset_id,
                display_name=name,
                gender=_optional_string(
                    item.get("gender"),
                    _tag_value(profile_tags, _GENDER_TAGS, "unknown"),
                ),
                age_range=_optional_string(
                    item.get("ageRange", item.get("age_range")),
                    _tag_value(profile_tags, _AGE_TAGS, "adult"),
                ),
                traits=tuple(_optional_strings(item.get("traits", []))),
                preview_available=True,
            ))
        return result

    def _profile_tags(self):
        """读取 /profiles 的 instruct 标签；只用于补全匹配元数据，失败不影响发现。"""
        try:
            status, _, body = self.transport("GET", "/profiles", None)
            if status != 200:
                return {}
            profiles = strict_json_loads(body)
        except (ProviderError, ProtocolError, ValueError):
            return {}
        if not isinstance(profiles, list):
            return {}
        tags = {}
        for profile in profiles:
            if not isinstance(profile, dict):
                continue
            profile_id, instruct = profile.get("id"), profile.get("instruct")
            if isinstance(profile_id, str) and isinstance(instruct, str):
                tags[profile_id] = tuple(
                    tag.strip().lower()
                    for tag in instruct.replace("，", ",").split(",")
                )
        return tags

    def provider_ref_for(self, voice_asset_id):
        try:
            return self._profile_refs[voice_asset_id]
        except KeyError:
            raise KeyError(voice_asset_id) from None

    def match(self, request):
        return []

    def preview(self, request, context=None):
        return self._synthesize(request, context)

    def synthesize(self, request, context=None):
        return self._synthesize(request, context)

    def _synthesize(self, request, context=None):
        owns_context = context is None
        context = context or RequestContext(self.config.request_timeout)
        try:
            context.check()
            try:
                resolved = self.voice_resolver(request.voice_asset_id)
            except (KeyError, TypeError, ValueError):
                raise ProtocolError() from None
            if not isinstance(resolved, dict) or not resolved.get("voice"):
                raise ProtocolError()
            payload = {
                "model": self.config.model,
                "voice": resolved["voice"],
                "input": request.text,
                "response_format": self.config.response_format,
                "language": request.language,
                "speed": request.speed,
            }
            status, headers, body = _invoke_transport(
                self.transport,
                "POST",
                self.config.speech_path,
                payload,
                context,
            )
            context.check()
            self._raise_for_status(status)
            content_type = _header(headers, "content-type").split(";", 1)[0].lower()
            if content_type == "application/json":
                try:
                    value = json.loads(body.decode("utf-8"))
                    content_type = value["contentType"]
                    body = base64.b64decode(value["audio"], validate=True)
                except (ValueError, KeyError, TypeError):
                    raise ProtocolError() from None
            context.check()
            content_type, body = normalize_audio(
                content_type,
                body,
                ffmpeg_path=self.config.ffmpeg_path,
                runner=self.audio_runner,
                timeout=min(
                    self.config.conversion_timeout,
                    context.remaining(),
                ),
            )
            context.check()
            return AudioResult(body, content_type, self.config.profile)
        except DeadlineExceeded:
            raise ProviderTimeoutError() from None
        finally:
            if owns_context:
                context.cancel()

    @staticmethod
    def _raise_for_status(status):
        if status in (401, 403):
            raise AuthenticationError()
        if status == 429:
            raise BusyError()
        if status >= 500 or status != 200:
            raise ProviderError()

    def _request(self, method, path, payload=None, context=None):
        parsed = urlsplit(self.config.base_url)
        body = None
        headers = {
            "Authorization": "Bearer " + self.config.token,
            "Accept": "audio/wav, audio/ogg, audio/mp4, audio/aac, application/json",
        }
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        owns_context = context is None
        context = context or RequestContext(self.config.request_timeout)
        connection = None
        try:
            timeout = (
                self.config.health_timeout
                if method == "GET" and path == "/system/info"
                else self.config.request_timeout
            )
            context.check()
            connection_class = (
                http.client.HTTPSConnection
                if parsed.scheme == "https"
                else http.client.HTTPConnection
            )
            connection = connection_class(
                parsed.hostname,
                parsed.port,
                timeout=min(timeout, context.remaining()),
            )
            abort = lambda: _abort_connection(connection)
            context.register_closer(abort)
            request_path = (parsed.path.rstrip("/") or "") + path
            if parsed.query:
                request_path += "?" + parsed.query
            connection.request(method, request_path, body=body, headers=headers)
            context.check()
            if connection.sock is not None:
                connection.sock.settimeout(context.remaining())
            response = connection.getresponse()
            response_headers = dict(response.getheaders())
            response_body = _read_limited(response, context)
            return response.status, response_headers, response_body
        except DeadlineExceeded:
            raise ProviderTimeoutError() from None
        except RequestCancelled:
            raise
        except (socket.timeout, TimeoutError):
            if context.cancelled:
                raise RequestCancelled() from None
            raise ProviderTimeoutError() from None
        except OSError:
            if context.cancelled:
                raise RequestCancelled() from None
            raise ProviderError() from None
        finally:
            if connection is not None:
                context.unregister_closer(abort)
                connection.close()
            if owns_context:
                context.cancel()


def _base_url(value, allow_remote=False):
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("invalid VoiceStudio base URL")
    hostname = parsed.hostname
    if not _is_loopback(hostname):
        if not allow_remote or parsed.scheme != "https":
            raise ValueError("remote VoiceStudio URL requires explicit HTTPS opt-in")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def _is_loopback(hostname):
    if hostname == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def _header(headers, name):
    wanted = name.lower()
    for key, value in headers.items():
        if isinstance(key, str) and key.lower() == wanted:
            return value if isinstance(value, str) else ""
    return ""


def _read_limited(response, context=None):
    content_length = _header(dict(response.headers.items()), "content-length")
    if not content_length:
        content_length = None
    if content_length is not None and (
        not content_length.isdecimal() or int(content_length) > 16 * 1024 * 1024
    ):
        raise ProtocolError()
    data = bytearray()
    while True:
        if context is not None:
            context.check()
        chunk = response.read1(min(65536, 16 * 1024 * 1024 + 1 - len(data)))
        if not chunk:
            if content_length is not None and len(data) != int(content_length):
                raise ProtocolError()
            return bytes(data)
        data.extend(chunk)
        if len(data) > 16 * 1024 * 1024:
            raise ProtocolError()


def _abort_connection(connection):
    try:
        if connection.sock is not None:
            connection.sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    connection.close()


def _invoke_transport(transport, method, path, payload, context):
    parameters = inspect.signature(transport).parameters
    if "context" in parameters or any(
        parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    ):
        return transport(method, path, payload, context=context)
    return transport(method, path, payload)


def _optional_string(value, fallback):
    return value if isinstance(value, str) and value.strip() else fallback


# VoiceStudio 音色设计标签（英文与中文两套写法）到 v1 匹配约束取值的映射。
_GENDER_TAGS = {"male": "male", "男": "male", "female": "female", "女": "female"}
_AGE_TAGS = {
    "child": "child", "儿童": "child",
    "teenager": "teen", "少年": "teen",
    "young adult": "young_adult", "青年": "young_adult",
    "middle-aged": "adult", "中年": "adult",
    "elderly": "elderly", "老年": "elderly",
}


def _tag_value(tags, mapping, fallback):
    for tag in tags:
        if tag in mapping:
            return mapping[tag]
    return fallback


def _optional_strings(value):
    if not isinstance(value, list):
        return []
    return [
        item for item in value
        if isinstance(item, str) and item.strip()
    ]
