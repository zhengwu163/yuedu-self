"""v1 协议校验与固定音色目录；所有正文始终来自 Android。"""
import copy
import json
import math
from urllib.parse import urlsplit, urlunsplit

MAX_JSON = 2 * 1024 * 1024
MAX_AUDIO = 16 * 1024 * 1024
TEXT_MODEL = "qwen3.7-plus"
TTS_MODEL = "qwen3-tts-instruct-flash"
AUDIO_HOSTS = {
    "dashscope-result-bj.oss-cn-beijing.aliyuncs.com",
    "dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com",
    # 2026-09-24 固定北京 HTTPS 推理端点的真实 TTS 响应使用此结果桶。
    "dashscope-a717.oss-cn-beijing.aliyuncs.com",
}
CONTEXTUAL = {"师父", "师傅", "哥哥", "姐姐", "弟弟", "妹妹", "父亲", "母亲",
              "他", "她", "它", "你", "我", "老人", "少年", "男人", "女人"}


class BridgeError(Exception):
    status, code = 503, "cloud_unavailable"

    def __init__(self):
        super().__init__(self.code)


class LocalQuotaError(BridgeError):
    status, code = 429, "local_trial_limit"


class CloudQuotaError(BridgeError):
    status, code = 429, "free_quota_only"


class CloudRateError(BridgeError):
    status, code = 429, "cloud_rate_limit"


class CloudAuthError(BridgeError):
    status, code = 401, "cloud_auth"


class CloudProtocolError(BridgeError):
    status, code = 502, "invalid_cloud_response"


# 固定诊断码可穿过 worker/HTTP/CLI；不携带供应商正文、URL 或原始异常。
class TtsResponseError(CloudProtocolError):
    code = "tts_invalid_response"


class TtsJsonError(CloudProtocolError):
    code = "tts_invalid_json"


class TtsMissingAudioUrlError(CloudProtocolError):
    code = "tts_missing_audio_url"


class TtsUnsafeAudioUrlError(CloudProtocolError):
    code = "tts_unsafe_audio_url"


class TtsDownloadError(CloudProtocolError):
    code = "tts_download_failed"


class TtsWavError(CloudProtocolError):
    code = "tts_invalid_wav"


class TtsConversionError(CloudProtocolError):
    code = "tts_conversion_failed"


class CloudTimeoutError(BridgeError):
    status, code = 504, "cloud_timeout"


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def require(condition):
    if not condition:
        raise ValueError("invalid")


def string(value, limit=128):
    require(isinstance(value, str) and bool(value.strip()) and utf16_length(value) <= limit)
    return value


def strings(value, count=32, limit=128):
    require(isinstance(value, list) and len(value) <= count)
    return [string(item, limit) for item in value]


def array(value, count=128):
    require(isinstance(value, list) and len(value) <= count)
    return value


def record(value):
    require(isinstance(value, dict))
    return value


def strict_json_loads(value):
    def pairs(items):
        result = {}
        for key, val in items:
            require(key not in result)
            result[key] = val
        return result

    def reject(_):
        raise ValueError("invalid")

    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="strict")
    require(isinstance(value, str) and len(value.encode("utf-8")) <= MAX_JSON)
    try:
        result = json.loads(value, object_pairs_hook=pairs, parse_constant=reject)
        # ensure_ascii=False 后再编码会拒绝 JSON 转义中的孤立 surrogate。
        json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return result
    except (RecursionError, OverflowError):
        raise ValueError("invalid") from None


def parse_analysis_json(value):
    string(value, MAX_JSON)
    text = value.strip()
    if text.startswith("```json\n") and text.endswith("```"):
        text = text[8:-3].strip()
    result = record(strict_json_loads(text))
    for key in ("assignments", "newCharacters", "aliasUpdates"):
        array(result.get(key))
    return result


def parse_tts_audio_url(value):
    try:
        url = record(record(record(value).get("output")).get("audio")).get("url")
        require(isinstance(url, str) and bool(url.strip()))
    except (ValueError, TypeError):
        raise TtsMissingAudioUrlError() from None
    try:
        string(url, 8192)
        # 在 urlsplit 自动剥离控制字符之前检查原文，避免改写签名或隐藏非法输入。
        require(not any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in url))
        parsed = urlsplit(url)
        require(parsed.scheme in ("http", "https") and parsed.hostname in AUDIO_HOSTS
                and not parsed.username and not parsed.password and parsed.port in (None, 443)
                and not parsed.fragment and parsed.path.endswith(".wav"))
    except (ValueError, TypeError):
        raise TtsUnsafeAudioUrlError() from None
    # 官方样例为 HTTP 的 OSS 签名 URL；签名 query 原样保留，仅以 HTTPS 访问。
    return urlunsplit(("https", parsed.netloc, parsed.path, parsed.query, ""))


def analysis_request(body):
    record(body)
    result = {key: string(body.get(key)) for key in
              ("bookId", "chapterId", "textHash", "analysisVersion")}
    require(result["analysisVersion"] == "1")
    characters, ids = [], set()
    for item in array(body.get("characters"), 64):
        record(item)
        key = string(item.get("characterId"))
        require(key != "narrator" and key not in ids)
        ids.add(key)
        characters.append({"characterId": key, "displayName": string(item.get("displayName")),
                           "stableAliases": strings(item.get("stableAliases"))})
    units, unit_ids = [], set()
    for item in array(body.get("units"), 64):
        record(item)
        key = string(item.get("unitId"))
        require(key not in unit_ids)
        unit_ids.add(key)
        units.append({"unitId": key, "text": string(item.get("text"), 4000)})
    require(units and sum(utf16_length(item["text"]) for item in units) <= 4000)
    recent = []
    for item in array(record(body.get("previousContext")).get("recentAssignments"), 32):
        record(item)
        key, speaker = string(item.get("unitId")), string(item.get("speakerId"))
        require(speaker in ids | {"narrator"})
        recent.append({"unitId": key, "speakerId": speaker})
    result.update(characters=characters, units=units, previousContext={"recentAssignments": recent})
    # 元数据同样受限，防正文很短但 aliases 极大导致分析输入膨胀。
    require(len(json.dumps(result, ensure_ascii=False).encode()) <= 32 * 1024)
    return result


def analysis_response(value, request):
    record(value)
    known = {c["characterId"] for c in request["characters"]}
    all_ids = known | {"narrator"}
    created = []
    for item in array(value.get("newCharacters"), 64):
        record(item)
        key = string(item.get("temporaryId"))
        require(key not in all_ids)
        all_ids.add(key)
        created.append({
            "temporaryId": key, "displayName": string(item.get("displayName")),
            "gender": string(item.get("gender")), "ageRange": string(item.get("ageRange")),
            "voicePersona": {"traits": strings(record(item.get("voicePersona")).get("traits"))},
        })
    unit_ids = {u["unitId"] for u in request["units"]}
    assignments, seen = [], set()
    for item in array(value.get("assignments"), 64):
        record(item)
        unit, speaker = string(item.get("unitId")), string(item.get("speakerId"))
        require(unit in unit_ids and unit not in seen and speaker in all_ids)
        seen.add(unit)
        assignments.append({"unitId": unit, "speakerId": speaker})
    require(seen == unit_ids)
    aliases, updated = [], set()
    for item in array(value.get("aliasUpdates"), 64):
        record(item)
        key = string(item.get("characterId"))
        require(key != "narrator" and key in all_ids and key not in updated)
        updated.add(key)
        stable = [v for v in strings(item.get("stableAliases")) if v.strip() not in CONTEXTUAL]
        aliases.append({"characterId": key, "stableAliases": stable})
    return {"assignments": assignments, "newCharacters": created, "aliasUpdates": aliases}


def synthesis_request(body):
    record(body)
    text, voice = string(body.get("text"), 1200), string(body.get("voiceAssetId"))
    require(body.get("language") == "zh-CN")
    speed = body.get("speed")
    # 试用范围明确受限；以后扩语种/速度时须升级 profile。
    require(type(speed) in (int, float) and math.isfinite(speed) and 0.5 <= speed <= 2.0)
    return {"text": text, "voice": VoiceCatalog().provider_voice(voice),
            "language": "zh-CN", "speed": float(speed)}


class VoiceCatalog:
    narrator_id = "bailian.narrator"
    entries = (
        ("narrator", "Ethan", "旁白·晨煦", "male", "adult", ["温暖", "清晰"]),
        ("cherry", "Cherry", "芊悦", "female", "young_adult", ["阳光", "亲切"]),
        ("serena", "Serena", "苏瑶", "female", "adult", ["温柔", "自然"]),
        ("mochi", "Mochi", "沙小弥", "male", "child", ["聪明", "灵动"]),
        ("vincent", "Vincent", "田叔", "male", "adult", ["沙哑", "豪情"]),
        ("bellona", "Bellona", "燕铮莺", "female", "adult", ["洪亮", "清晰"]),
        ("chelsie", "Chelsie", "千雪", "female", "young_adult", ["轻快", "灵动"]),
    )

    def public_voices(self):
        return [{"voiceAssetId": "bailian." + key, "displayName": name, "gender": gender,
                 "ageRange": age, "traits": copy.copy(traits), "previewAvailable": True}
                for key, _, name, gender, age, traits in self.entries]

    def provider_voice(self, voice_id):
        for key, provider, *_ in self.entries:
            if voice_id == "bailian." + key:
                return provider
        raise ValueError("invalid")

    def match(self, persona, used, constraints=None):
        wanted = set(strings(record(persona).get("traits")))
        used = set(strings(used, 128))
        constraints = {} if constraints is None else record(constraints)
        require(not set(constraints) - {"gender", "ageRange"})
        for value in constraints.values():
            require(isinstance(value, str) and len(value) <= 64)
        candidates = [v for v in self.public_voices() if v["voiceAssetId"] != self.narrator_id]
        def score(v):
            matches = sum(v[k] == val for k, val in constraints.items() if val)
            return (v["voiceAssetId"] in used, -matches, -len(wanted & set(v["traits"])),
                    v["voiceAssetId"])
        return sorted(candidates, key=score)
