"""NovelAudioServer v1 的业务适配边界。"""
import hmac
import threading

from local_state import BridgeConfig, UsageGuard
from protocol import (
    BridgeError, CloudQuotaError, CloudProtocolError, LocalQuotaError, MAX_AUDIO,
    VoiceCatalog, analysis_request, analysis_response, synthesis_request, utf16_length,
    parse_analysis_json, parse_tts_audio_url, strict_json_loads as _strict_json_loads,
)


class BridgeApi:
    def __init__(self, config, cloud, usage, ffmpeg_available):
        self.config, self.cloud, self.usage = config, cloud, usage
        self.ffmpeg_available = ffmpeg_available
        self.catalog = VoiceCatalog()
        self._generation = threading.Lock()
        self._quota_blocked = False
        self._closed = False

    def close(self):
        self._closed = True
        close = getattr(self.cloud, "close", None)
        if close:
            close()

    @staticmethod
    def error(status, code):
        return status, {"Content-Type": "application/json"}, {"error": {"code": code}}

    def authorized(self, authorization):
        return hmac.compare_digest((authorization or "").encode("utf-8"),
                                   ("Bearer " + self.config.bridge_token).encode("utf-8"))

    def respond(self, method, path, authorization, body, lease_id=None):
        if not self.authorized(authorization):
            return self.error(401, "unauthorized")
        if self._closed:
            return self.error(503, "stopped")
        if lease_id:
            return self.error(409, "invalid_lease")
        headers = {"Content-Type": "application/json"}
        if method == "GET" and path == "/v1/health":
            ready = self.config.cloud_ready() and not self._quota_blocked
            return 200, headers, {"status": "ok", "apiVersion": "1",
                                 "directorReady": ready and self.usage.available("analysis"),
                                 "ttsReady": ready and self.ffmpeg_available and self.usage.available("tts")}
        if method == "GET" and path == "/v1/voices":
            voices = self.catalog.public_voices()
            for voice in voices:
                voice["previewAvailable"] = self.config.cloud_ready() and self.ffmpeg_available
            return 200, headers, {"voices": voices}
        if method != "POST" or path not in (
                "/v1/voices/match", "/v1/chapter/analyze", "/v1/tts/synthesize",
                "/v1/voices/preview"):
            return self.error(404, "not_found")
        try:
            if path == "/v1/voices/match":
                if not isinstance(body, dict):
                    raise ValueError()
                return 200, headers, {"candidates": self.catalog.match(
                    body.get("voicePersona"), body.get("alreadyUsedVoiceIds"),
                    body.get("optionalConstraints"))}
            request = analysis_request(body) if path == "/v1/chapter/analyze" else synthesis_request(body)
        except (ValueError, TypeError, OverflowError):
            return self.error(400, "invalid_request")
        if self._quota_blocked:
            return self.error(429, "free_quota_only")
        if not self.config.cloud_ready() or (path != "/v1/chapter/analyze" and not self.ffmpeg_available):
            return self.error(503, "not_ready")
        # 单生成请求，不等待本地队列，避免客户端已超时后后台继续批量消耗。
        if not self._generation.acquire(blocking=False):
            return self.error(429, "busy")
        try:
            if path == "/v1/chapter/analyze":
                self.usage.reserve("analysis", sum(utf16_length(u["text"]) for u in request["units"]))
                response = self.cloud.analyze(request)
                return 200, headers, analysis_response(response, request)
            text = request["text"]
            self.usage.reserve("tts", utf16_length(text), requests=(len(text) + 599) // 600)
            audio = self.cloud.synthesize(request)
            if not isinstance(audio, bytes) or not 0 < len(audio) <= MAX_AUDIO:
                raise CloudProtocolError()
            return 200, {"Content-Type": "audio/ogg",
                         "X-TTS-Profile": getattr(self.cloud, "profile",
                                                 "bailian-qwen3-tts-instruct-flash-v1-test")}, audio
        except CloudQuotaError:
            # 写盘失败也不能重新放行；内存熔断优先于持久化。
            self._quota_blocked = True
            try:
                self.usage.block_cloud_quota()
            except LocalQuotaError:
                pass
            return self.error(429, "free_quota_only")
        except BridgeError as error:
            return self.error(error.status, error.code)
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            return self.error(502, "invalid_cloud_response")
        finally:
            self._generation.release()


# 公共入口兼容：CLI 和单测可以从 bridge 导入客户端，内部网络模块与协议隔离。
from cloud import BailianClient
