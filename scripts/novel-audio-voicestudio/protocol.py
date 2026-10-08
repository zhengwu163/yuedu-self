import json
import math
from dataclasses import asdict

from models import ChapterAnalysisRequest, SynthesisRequest, Unit, VoiceMatchRequest


MAX_JSON = 2 * 1024 * 1024
MAX_AUDIO = 16 * 1024 * 1024
MAX_TEXT = 1200
MAX_CHARACTERS = 64
MAX_UNITS = 64
MAX_RECENT_ASSIGNMENTS = 32
MAX_ANALYSIS_METADATA = 32 * 1024
CONTEXTUAL_ALIASES = {
    "师父", "师傅", "哥哥", "姐姐", "弟弟", "妹妹", "父亲", "母亲",
    "他", "她", "它", "你", "我", "老人", "少年", "男人", "女人",
}


def utf16_length(value):
    return len(value.encode("utf-16-le")) // 2


def _require(condition):
    if not condition:
        raise ValueError("invalid")


def _string(value, limit=128):
    _require(isinstance(value, str) and value.strip())
    _require(utf16_length(value) <= limit)
    return value


def _strings(value, limit=32, item_limit=128):
    _require(isinstance(value, list) and len(value) <= limit)
    return [_string(item, item_limit) for item in value]


def _list(value, limit):
    _require(isinstance(value, list) and len(value) <= limit)
    return value


def strict_json_loads(value):
    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="strict")
    _require(isinstance(value, str))
    _require(len(value.encode("utf-8")) <= MAX_JSON)

    def pairs(items):
        result = {}
        for key, item in items:
            _require(key not in result)
            result[key] = item
        return result

    def reject_constant(_):
        raise ValueError("invalid")

    try:
        result = json.loads(
            value,
            object_pairs_hook=pairs,
            parse_constant=reject_constant,
        )
        json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return result
    except (UnicodeError, RecursionError, OverflowError):
        raise ValueError("invalid") from None


def _record(value):
    _require(isinstance(value, dict))
    return value


def parse_synthesis(body):
    _record(body)
    speed = body.get("speed")
    _require(
        type(speed) in (int, float)
        and math.isfinite(speed)
        and speed > 0
    )
    return SynthesisRequest(
        text=_string(body.get("text"), MAX_TEXT),
        voice_asset_id=_string(body.get("voiceAssetId")),
        language=_string(body.get("language"), 32),
        speed=float(speed),
    )


def parse_voice_match(body):
    _record(body)
    persona = _record(body.get("voicePersona"))
    constraints = body.get("optionalConstraints") or {}
    _record(constraints)
    _require(not set(constraints) - {"gender", "ageRange"})
    normalized_constraints = {
        key: _string(value, 64)
        for key, value in constraints.items()
    }
    return VoiceMatchRequest(
        traits=tuple(_strings(persona.get("traits", []))),
        already_used_voice_ids=tuple(
            _strings(body.get("alreadyUsedVoiceIds", []), 128)
        ),
        constraints=normalized_constraints,
    )


def parse_analysis(body):
    _record(body)
    _require({
        "bookId",
        "chapterId",
        "textHash",
        "analysisVersion",
        "characters",
        "units",
        "previousContext",
    } <= set(body))
    characters, character_ids = [], set()
    for character in _list(body["characters"], MAX_CHARACTERS):
        _record(character)
        character_id = _string(character.get("characterId"))
        _require(character_id != "narrator" and character_id not in character_ids)
        character_ids.add(character_id)
        characters.append({
            "characterId": character_id,
            "displayName": _string(character.get("displayName")),
            "stableAliases": _strings(character.get("stableAliases", [])),
        })

    units, unit_ids = [], set()
    for unit in _list(body["units"], MAX_UNITS):
        _record(unit)
        unit_id = _string(unit.get("unitId"))
        _require(unit_id not in unit_ids)
        unit_ids.add(unit_id)
        units.append(Unit(unit_id, _string(unit.get("text"), 4000)))
    _require(units and sum(utf16_length(item.text) for item in units) <= 4000)

    previous = _record(body.get("previousContext"))
    recent = []
    for item in _list(
        previous.get("recentAssignments"),
        MAX_RECENT_ASSIGNMENTS,
    ):
        _record(item)
        speaker_id = _string(item.get("speakerId"))
        _require(speaker_id in character_ids | {"narrator"})
        recent.append({
            "unitId": _string(item.get("unitId")),
            "speakerId": speaker_id,
        })

    request = ChapterAnalysisRequest(
        book_id=_string(body.get("bookId")),
        chapter_id=_string(body.get("chapterId")),
        text_hash=_string(body.get("textHash")),
        analysis_version=_string(body.get("analysisVersion")),
        characters=tuple(characters),
        units=tuple(units),
        previous_context={"recentAssignments": recent},
    )
    _require(request.analysis_version == "1")
    _require(
        len(json.dumps(
            request.as_dict(),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")) <= MAX_ANALYSIS_METADATA
    )
    return request


def project_analysis(value, original_request):
    _record(value)
    request = parse_analysis(original_request)
    known_ids = {item["characterId"] for item in request.characters}
    allowed_ids = known_ids | {"narrator"}
    new_characters = []
    for item in _list(value.get("newCharacters"), MAX_CHARACTERS):
        _record(item)
        temporary_id = _string(item.get("temporaryId"))
        _require(temporary_id not in allowed_ids)
        allowed_ids.add(temporary_id)
        persona = _record(item.get("voicePersona"))
        new_characters.append({
            "temporaryId": temporary_id,
            "displayName": _string(item.get("displayName")),
            "gender": _string(item.get("gender")),
            "ageRange": _string(item.get("ageRange")),
            "voicePersona": {
                "traits": _strings(persona.get("traits", [])),
            },
        })

    requested_units = {unit.unit_id for unit in request.units}
    assignments, seen = [], set()
    for item in _list(value.get("assignments"), MAX_UNITS):
        _record(item)
        unit_id = _string(item.get("unitId"))
        speaker_id = _string(item.get("speakerId"))
        _require(unit_id in requested_units and unit_id not in seen)
        _require(speaker_id in allowed_ids)
        seen.add(unit_id)
        assignments.append({
            "unitId": unit_id,
            "speakerId": speaker_id,
        })
    _require(seen == requested_units)

    alias_updates, updated = [], set()
    for item in _list(value.get("aliasUpdates"), MAX_CHARACTERS):
        _record(item)
        character_id = _string(item.get("characterId"))
        _require(character_id in allowed_ids and character_id != "narrator")
        _require(character_id not in updated)
        updated.add(character_id)
        aliases = [
            alias for alias in _strings(item.get("stableAliases", []))
            if alias not in CONTEXTUAL_ALIASES
        ]
        alias_updates.append({
            "characterId": character_id,
            "stableAliases": aliases,
        })

    return {
        "assignments": assignments,
        "newCharacters": new_characters,
        "aliasUpdates": alias_updates,
    }
