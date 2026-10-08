"""Provider-neutral NovelAudioServer v1 validation."""

import json
import math

MAX_JSON = 2 * 1024 * 1024
MAX_AUDIO = 16 * 1024 * 1024
CONTEXTUAL = {
    "师父",
    "师傅",
    "哥哥",
    "姐姐",
    "弟弟",
    "妹妹",
    "父亲",
    "母亲",
    "他",
    "她",
    "它",
    "你",
    "我",
    "老人",
    "少年",
    "男人",
    "女人",
}


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def require(condition):
    if not condition:
        raise ValueError("invalid")


def string(value, limit=128):
    require(isinstance(value, str) and bool(value.strip()))
    require(utf16_length(value) <= limit)
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
        for key, item in items:
            require(key not in result)
            result[key] = item
        return result

    def reject(_):
        raise ValueError("invalid")

    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="strict")
    require(isinstance(value, str))
    require(len(value.encode("utf-8")) <= MAX_JSON)
    try:
        result = json.loads(
            value,
            object_pairs_hook=pairs,
            parse_constant=reject,
        )
        json.dumps(
            result,
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
        return result
    except (RecursionError, OverflowError):
        raise ValueError("invalid") from None


def load_analysis_json(value):
    """Strictly load a model's analysis JSON object, tolerating a json fence."""
    string(value, MAX_JSON)
    text = value.strip()
    if text.startswith("```json\n") and text.endswith("```"):
        text = text[8:-3].strip()
    return record(strict_json_loads(text))


def parse_analysis_json(value):
    result = load_analysis_json(value)
    for key in ("assignments", "newCharacters", "aliasUpdates"):
        array(result.get(key))
    return result


def analysis_request(body):
    record(body)
    result = {
        key: string(body.get(key))
        for key in ("bookId", "chapterId", "textHash", "analysisVersion")
    }
    require(result["analysisVersion"] == "1")

    characters, character_ids = [], set()
    for item in array(body.get("characters"), 64):
        record(item)
        character_id = string(item.get("characterId"))
        require(character_id != "narrator" and character_id not in character_ids)
        character_ids.add(character_id)
        characters.append(
            {
                "characterId": character_id,
                "displayName": string(item.get("displayName")),
                "stableAliases": strings(item.get("stableAliases")),
            }
        )

    units, unit_ids = [], set()
    for item in array(body.get("units"), 64):
        record(item)
        unit_id = string(item.get("unitId"))
        require(unit_id not in unit_ids)
        unit_ids.add(unit_id)
        units.append(
            {
                "unitId": unit_id,
                "text": string(item.get("text"), 4000),
            }
        )
    require(units and sum(utf16_length(item["text"]) for item in units) <= 4000)

    previous_context = record(body.get("previousContext"))
    recent_assignments = []
    for item in array(previous_context.get("recentAssignments"), 32):
        record(item)
        unit_id = string(item.get("unitId"))
        speaker_id = string(item.get("speakerId"))
        require(speaker_id in character_ids | {"narrator"})
        recent_assignments.append(
            {"unitId": unit_id, "speakerId": speaker_id}
        )

    result.update(
        characters=characters,
        units=units,
        previousContext={"recentAssignments": recent_assignments},
    )
    require(len(json.dumps(result, ensure_ascii=False).encode()) <= 32 * 1024)
    return result


def model_analysis_request(request):
    """Build the model-facing view of a validated analysis request.

    Android unit IDs are opaque 66-character hashes. Asking a model to echo
    dozens of them multiplies output tokens and invites copy errors, so the
    model only sees short ordinal aliases. Book/chapter/text hashes carry no
    meaning for attribution and are omitted. Returns (view, alias -> unitId).
    """
    aliases = {}
    units = []
    for index, unit in enumerate(request["units"], 1):
        alias = f"u{index}"
        aliases[alias] = unit["unitId"]
        units.append({"unitId": alias, "text": unit["text"]})
    recent = [
        {"unitId": f"p{index}", "speakerId": item["speakerId"]}
        for index, item in enumerate(
            request["previousContext"]["recentAssignments"], 1
        )
    ]
    view = {
        "characters": request["characters"],
        "units": units,
        "previousContext": {"recentAssignments": recent},
    }
    return view, aliases


def restore_model_unit_ids(value, aliases):
    """Map model aliases back to request unit IDs before strict validation.

    Models answer with a compact {"u1": "narrator"} mapping (about half the
    output tokens of the list form); the list form is still accepted. Unknown
    aliases are left untouched so analysis_response still rejects them.
    """
    record(value)
    assignments = value.get("assignments")
    if isinstance(assignments, dict):
        assignments = [
            {"unitId": unit_id, "speakerId": speaker_id}
            for unit_id, speaker_id in assignments.items()
        ]
    restored = []
    for item in array(assignments):
        if isinstance(item, dict) and isinstance(item.get("unitId"), str) \
                and item["unitId"] in aliases:
            item = dict(item, unitId=aliases[item["unitId"]])
        restored.append(item)
    return dict(value, assignments=restored)


def analysis_response(value, request):
    record(value)
    known_ids = {item["characterId"] for item in request["characters"]}
    all_ids = known_ids | {"narrator"}

    created = []
    for item in array(value.get("newCharacters"), 64):
        record(item)
        temporary_id = string(item.get("temporaryId"))
        require(temporary_id not in all_ids)
        all_ids.add(temporary_id)
        created.append(
            {
                "temporaryId": temporary_id,
                "displayName": string(item.get("displayName")),
                "gender": string(item.get("gender")),
                "ageRange": string(item.get("ageRange")),
                "voicePersona": {
                    "traits": strings(
                        record(item.get("voicePersona")).get("traits")
                    )
                },
            }
        )

    unit_ids = {item["unitId"] for item in request["units"]}
    assignments, seen = [], set()
    for item in array(value.get("assignments"), 64):
        record(item)
        unit_id = string(item.get("unitId"))
        speaker_id = string(item.get("speakerId"))
        require(
            unit_id in unit_ids
            and unit_id not in seen
            and speaker_id in all_ids
        )
        seen.add(unit_id)
        assignments.append({"unitId": unit_id, "speakerId": speaker_id})
    require(seen == unit_ids)

    aliases, updated = [], set()
    for item in array(value.get("aliasUpdates"), 64):
        record(item)
        character_id = string(item.get("characterId"))
        require(
            character_id != "narrator"
            and character_id in all_ids
            and character_id not in updated
        )
        updated.add(character_id)
        stable_aliases = [
            value
            for value in strings(item.get("stableAliases"))
            if value.strip() not in CONTEXTUAL
        ]
        aliases.append(
            {
                "characterId": character_id,
                "stableAliases": stable_aliases,
            }
        )

    return {
        "assignments": assignments,
        "newCharacters": created,
        "aliasUpdates": aliases,
    }


def synthesis_request(body):
    record(body)
    text = string(body.get("text"), 1200)
    voice_asset_id = string(body.get("voiceAssetId"))
    require(body.get("language") == "zh-CN")
    speed = body.get("speed")
    require(
        type(speed) in (int, float)
        and math.isfinite(speed)
        and 0.5 <= speed <= 2.0
    )
    return {
        "text": text,
        "voiceAssetId": voice_asset_id,
        "language": "zh-CN",
        "speed": float(speed),
    }
