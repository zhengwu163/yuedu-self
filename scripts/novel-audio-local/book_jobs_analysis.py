"""Host-side port of the phone's chapter analysis for whole-book jobs.

The phone uploads text units and leaves; the host must attribute speakers,
grow the character table and bind voices exactly as the phone would. Each
function mirrors one Kotlin source of truth and must keep its behavior:

- analyze_chapter      NovelAudioAnalysisCoordinator.analyze
- resolve_characters   NovelAudioCharacterRegistry.resolve / normalizeAlias
- explicit_speaker     NovelAudioSpeakerResolver.explicitSpeaker
- bind_voice           NovelAudioVoiceBindingRegistry.bind

Lengths and slices follow Kotlin String semantics (UTF-16 code units).
Shared parity cases live in fixtures/book_jobs_parity.json; change both
implementations and that file together.
"""

import json
import re
import unicodedata

MAX_UNITS_PER_BATCH = 64
MAX_TEXT_UTF16 = 4000
MAX_JSON_BYTES = 32 * 1024
MAX_CHARACTERS = 64
MAX_ALIASES_PER_CHARACTER = 32
MAX_ALIAS_LENGTH = 128
MAX_PREVIOUS_ASSIGNMENTS = 32
MAX_CONTEXT_UNITS = 8
MAX_CONTEXT_UTF16 = 500

REJECTED_ALIASES = frozenset({
    "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们",
    "先生", "女士", "小姐", "老师", "同学", "大人", "殿下", "陛下",
    "公子", "姑娘", "夫人", "少爷", "老爷", "前辈", "后辈",
})

_VERB = "(?:说道|问道|答道|喊道|叫道|笑道|吼道|说|问|答|喊|叫|道)"
_MODIFIER = "[^\\n。！？“”「」『』]{0,12}"
# Java's \s is ASCII-only; Python's would also match the full-width space U+3000.
_SPACE = "[ \\t\\n\\x0b\\f\\r]"


class AnalysisError(RuntimeError):
    """The analysis result cannot be turned into a consistent plan."""


class AliasConflict(ValueError):
    pass


class NoUnusedVoice(RuntimeError):
    pass


def utf16_len(text):
    return len(text.encode("utf-16-le")) // 2


def utf16_take(text, count):
    return text.encode("utf-16-le")[: count * 2].decode("utf-16-le", "ignore")


def utf16_take_last(text, count):
    data = text.encode("utf-16-le")
    return data[max(0, len(data) - count * 2):].decode("utf-16-le", "ignore")


def normalize_gender(value):
    """BookCharacter.normalizeGender: male / female / empty for unknown."""
    lowered = (value or "").strip().lower()
    if lowered in {"male", "m", "man", "男", "男性", "男声"}:
        return "male"
    if lowered in {"female", "f", "woman", "女", "女性", "女声"}:
        return "female"
    return ""


def normalize_alias(alias):
    # Kotlin Regex("\\s+") uses Java's ASCII whitespace class, not Unicode \s.
    normalized = re.sub(
        r"[ \t\n\x0b\f\r]+", "", unicodedata.normalize("NFKC", alias).strip()
    ).lower()
    if not normalized or utf16_len(normalized) > 128:
        raise ValueError("invalid alias")
    if normalized in REJECTED_ALIASES:
        raise ValueError("pronoun or title cannot be a stable alias")
    return normalized


class CharacterTable:
    """One work's characters, aliases, active merges and voice bindings.

    characters: [{"id": int, "name": str, "gender": str}]
    aliases:    [{"characterId": int, "alias": str, "normalizedAlias": str}]
    merges:     {absorbedId: primaryId} (active merges only)
    bindings:   {characterId: {"voiceAssetId": str, "revision": int}}
    """

    def __init__(self, characters=(), aliases=(), merges=None, bindings=None):
        self.characters = [dict(item) for item in characters]
        self.aliases = [dict(item) for item in aliases]
        self.merges = {int(key): int(value) for key, value in (merges or {}).items()}
        self.bindings = {
            int(key): dict(value) for key, value in (bindings or {}).items()
        }

    def to_json(self):
        return {
            "characters": self.characters,
            "aliases": self.aliases,
            "merges": {str(key): value for key, value in self.merges.items()},
            "bindings": {str(key): value for key, value in self.bindings.items()},
        }

    @classmethod
    def from_json(cls, value):
        return cls(
            value.get("characters", ()),
            value.get("aliases", ()),
            value.get("merges"),
            value.get("bindings"),
        )

    def canonical(self, character_id):
        current, seen = character_id, set()
        while current not in seen:
            seen.add(current)
            following = self.merges.get(current)
            if following is None:
                return current
            current = following
        raise AliasConflict("character merge graph contains a cycle")


def _put_alias(index, alias, character_id):
    previous = index.get(alias)
    if previous is not None and previous != character_id:
        raise AliasConflict("alias is already assigned to another character")
    index[alias] = character_id


def default_allocator(ids):
    return (max(ids) if ids else 0) + 1


def resolve_characters(table, new_characters, alias_updates, allocator=default_allocator):
    """Return (temporary_to_id, created, aliases) and apply them to table."""
    index = {}
    for character in table.characters:
        _put_alias(index, normalize_alias(character["name"]), table.canonical(character["id"]))
    for alias in table.aliases:
        _put_alias(
            index,
            normalize_alias(alias.get("normalizedAlias") or alias["alias"]),
            table.canonical(alias["characterId"]),
        )

    aliases_by_temporary = {}
    for update in alias_updates:
        aliases_by_temporary.setdefault(update["characterId"], []).append(update)
    ids = {character["id"] for character in table.characters}
    temporary_to_id, created, generated = {}, [], []

    for candidate in new_characters:
        if not candidate["temporaryId"].strip() or not candidate["displayName"].strip():
            raise ValueError("blank temporary character")
        names = [candidate["displayName"]] + [
            alias
            for update in aliases_by_temporary.get(candidate["temporaryId"], ())
            for alias in update["stableAliases"]
        ]
        names = list(dict.fromkeys(normalize_alias(name) for name in names))
        matched = list(dict.fromkeys(index[name] for name in names if name in index))
        if len(matched) > 1:
            raise AliasConflict("one temporary character matches multiple formal characters")
        if matched:
            character_id = matched[0]
        else:
            character_id = allocator(ids)
            ids.add(character_id)
        canonical_id = table.canonical(character_id)
        temporary_to_id[candidate["temporaryId"]] = canonical_id
        if not matched:
            formal = {
                "id": canonical_id,
                "name": candidate["displayName"].strip(),
                "gender": normalize_gender(candidate.get("gender")),
            }
            created.append(formal)
            _put_alias(index, normalize_alias(formal["name"]), canonical_id)
        for alias in names:
            _put_alias(index, alias, canonical_id)
            generated.append(
                {"characterId": canonical_id, "alias": alias, "normalizedAlias": alias}
            )

    for update in alias_updates:
        if len(update["stableAliases"]) > MAX_ALIASES_PER_CHARACTER:
            raise ValueError("too many stable aliases for one character")
        reference = update["characterId"]
        if _is_long(reference):
            character_id = int(reference)
        elif reference in temporary_to_id:
            character_id = temporary_to_id[reference]
        else:
            raise ValueError("unknown character reference")
        canonical_id = table.canonical(character_id)
        if canonical_id == 0:
            raise ValueError("narrator cannot own aliases")
        for raw in update["stableAliases"]:
            alias = normalize_alias(raw)
            _put_alias(index, alias, canonical_id)
            generated.append(
                {"characterId": canonical_id, "alias": raw.strip(), "normalizedAlias": alias}
            )

    distinct, seen = [], set()
    for item in generated:
        # Kotlin distinctBy keeps the first alias row for each normalized key.
        if item["normalizedAlias"] not in seen:
            seen.add(item["normalizedAlias"])
            distinct.append(item)
    table.characters.extend(created)
    known = {item["normalizedAlias"] for item in table.aliases}
    table.aliases.extend(item for item in distinct if item["normalizedAlias"] not in known)
    return temporary_to_id, created, distinct


def _is_long(value):
    # Kotlin String.toLongOrNull(): optional sign, ASCII digits, 64-bit range.
    if not re.fullmatch(r"[+-]?[0-9]+", value or ""):
        return False
    return -(2 ** 63) <= int(value) < 2 ** 63


def explicit_speaker(units, index, characters, aliases):
    """Reporting clauses such as 「林舟说」 override the model; ambiguity keeps it."""
    if not 0 <= index < len(units):
        return None
    unit = units[index]
    if unit["roleType"] == "narrator":
        return 0
    before_unit = next((item for item in reversed(units[:index]) if item["text"].strip()), None)
    before = (
        utf16_take_last(before_unit["text"], 80)
        if before_unit and before_unit["roleType"] == "narrator"
        else ""
    )
    after_unit = next((item for item in units[index + 1:] if item["text"].strip()), None)
    after = (
        utf16_take(after_unit["text"], 80)
        if after_unit and after_unit["roleType"] == "narrator"
        else ""
    )
    hits = []
    for character in characters:
        names = [character["name"]] + [
            alias["alias"] for alias in aliases if alias["characterId"] == character["id"]
        ]
        for name in (value for value in names if value.strip()):
            stem = re.escape(name) + _SPACE + "*" + _MODIFIER + _VERB
            if re.search(stem + "[：:，," + _SPACE[1:-1] + "]*$", before) or re.search(
                "^[，,。！？" + _SPACE[1:-1] + "]*" + stem, after
            ):
                if character["id"] not in hits:
                    hits.append(character["id"])
                break
    return hits[0] if len(hits) == 1 else None


def bind_voice(character_id, existing, candidates):
    """Keep an existing binding; otherwise take the first voice unused by others."""
    current = existing.get(character_id)
    if current is not None:
        return current
    used_by_others = {
        binding["voiceAssetId"]
        for other_id, binding in existing.items()
        if other_id != character_id
    }
    for candidate in candidates:
        voice_id = candidate.get("voiceAssetId", "")
        if voice_id and voice_id not in used_by_others:
            return {"voiceAssetId": voice_id, "revision": 1}
    raise NoUnusedVoice()


def partition(units):
    batches, current, length = [], [], 0
    for unit in units:
        size = utf16_len(unit["text"])
        if size > MAX_TEXT_UTF16:
            raise AnalysisError("one text unit exceeds the server UTF-16 budget")
        if current and (
            len(current) >= MAX_UNITS_PER_BATCH - MAX_CONTEXT_UNITS
            or length + size > MAX_TEXT_UTF16 - MAX_CONTEXT_UTF16
        ):
            batches.append(current)
            current, length = [], 0
        current.append(unit)
        length += size
    if current:
        batches.append(current)
    return batches


def build_request(work_key, chapter_id, text_hash, characters, aliases, units, previous):
    normalized = []
    for character in characters[-MAX_CHARACTERS:]:
        names = [character["name"]] + [
            alias["alias"] for alias in aliases if alias["characterId"] == character["id"]
        ]
        names = list(dict.fromkeys(names))[:MAX_ALIASES_PER_CHARACTER]
        normalized.append({
            "characterId": str(character["id"]),
            "displayName": character["name"],
            "stableAliases": [utf16_take(name, MAX_ALIAS_LENGTH) for name in names],
        })
    request_units = [{"unitId": unit["unitId"], "text": unit["text"]} for unit in units]
    for count in range(len(normalized), -1, -1):
        selected = normalized[len(normalized) - count:] if count else []
        for recent in range(min(len(previous), MAX_PREVIOUS_ASSIGNMENTS), -1, -1):
            request = {
                "bookId": work_key,
                "chapterId": chapter_id,
                "textHash": text_hash,
                "analysisVersion": "1",
                "characters": selected,
                "units": request_units,
                "previousContext": {
                    "recentAssignments": previous[len(previous) - recent:] if recent else []
                },
            }
            if _gson_size(request) <= MAX_JSON_BYTES:
                return request
    raise AnalysisError("analysis request exceeds JSON budget")


def _gson_size(value):
    """UTF-8 size of the phone's Gson encoding (HTML-safe escaping is on)."""
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    size = len(encoded.encode("utf-8"))
    # Gson writes these as six-byte \\uXXXX escapes; Python writes them raw.
    size += 5 * sum(encoded.count(char) for char in "<>&='")
    size += 3 * sum(encoded.count(char) for char in "\u2028\u2029")
    return size


def _formal(speaker_id, temporary_to_id, error):
    if speaker_id == "narrator":
        return "narrator"
    if _is_long(speaker_id):
        return speaker_id
    if speaker_id in temporary_to_id:
        return str(temporary_to_id[speaker_id])
    raise AnalysisError(error)


def analyze_chapter(table, work_key, chapter_id, text_hash, units, analyze,
                    voices, match, allocator=default_allocator):
    """Attribute every unit and bind voices; mutates table like the phone store.

    units:   [{"unitId", "text", "roleType"}] in reading order (blank allowed)
    analyze: callable(validated v1 request) -> validated v1 response
    voices:  callable() -> public voice list (with optional "narrator")
    match:   callable(used_voice_ids, constraints) -> ordered candidates
    Returns {"speakers": {unitId: characterId}, "bindings": {id: binding}}.
    """
    assignments = {}
    known = [dict(item) for item in table.characters]
    temporary_to_id = {}
    previous, context = [], []
    for batch in partition([unit for unit in units if unit["text"].strip()]):
        window = list(context)
        while window and (
            len(window) + len(batch) > MAX_UNITS_PER_BATCH
            or sum(utf16_len(item["text"]) for item in window + batch) > MAX_TEXT_UTF16
        ):
            window.pop(0)
        request = build_request(
            work_key, chapter_id, text_hash, known, table.aliases, window + batch, previous
        )
        response = analyze(request)
        _validate(request, response, known)
        if response["newCharacters"] or response["aliasUpdates"]:
            mapped, created, _ = resolve_characters(
                table, response["newCharacters"], response["aliasUpdates"], allocator
            )
            temporary_to_id.update(mapped)
            known.extend(created)
        fresh = [item for item in response["assignments"] if item["unitId"] not in assignments]
        for item in fresh:
            formal = _formal(item["speakerId"], temporary_to_id, "unresolved temporary character")
            assignments[item["unitId"]] = "0" if formal == "narrator" else formal
        previous = [
            {"unitId": item["unitId"], "speakerId": _formal(
                item["speakerId"], temporary_to_id, "unresolved context character"
            )}
            for item in previous + fresh
        ][-MAX_PREVIOUS_ASSIGNMENTS:]
        context = (context + batch)[-MAX_CONTEXT_UNITS:]
        while sum(utf16_len(item["text"]) for item in context) > MAX_CONTEXT_UTF16:
            context = context[1:]

    for position, unit in enumerate(units):
        explicit = explicit_speaker(units, position, known, table.aliases)
        if explicit is not None:
            assignments[unit["unitId"]] = str(explicit)

    speaker_ids = list(dict.fromkeys(
        int(value) for value in assignments.values() if _is_long(value)
    ))
    bindings = bind_voices(table, speaker_ids, known, voices, match)
    speakers = {}
    for unit in units:
        # NovelAudioAnalysisCoordinator maps an unassigned unit to 0 (narrator).
        value = assignments.get(unit["unitId"])
        if unit["roleType"] == "narrator" or value is None or not _is_long(value):
            speakers[unit["unitId"]] = 0
        else:
            speakers[unit["unitId"]] = int(value)
    for character_id, binding in bindings.items():
        # rememberVoiceBindings keeps the first stable assignment per character.
        table.bindings.setdefault(character_id, binding)
    return {"speakers": speakers, "bindings": bindings}


def bind_voices(table, speaker_ids, characters, voices, match):
    all_voices = voices()
    narrator_voices = [voice for voice in all_voices if voice.get("narrator")]
    by_id = {character["id"]: character for character in characters}
    result = {}
    for speaker_id in [0] + [value for value in speaker_ids if value != 0]:
        if speaker_id in table.bindings:
            result[speaker_id] = table.bindings[speaker_id]
            continue
        existing = dict(table.bindings)
        existing.update(result)
        if speaker_id == 0 and narrator_voices:
            candidates = narrator_voices
        else:
            gender = normalize_gender(by_id.get(speaker_id, {}).get("gender"))
            used = list(dict.fromkeys(binding["voiceAssetId"] for binding in existing.values()))
            candidates = match(used, {"gender": gender} if gender else None)
        result[speaker_id] = bind_voice(speaker_id, existing, candidates)
    return result


def _validate(request, response, known):
    expected = {unit["unitId"] for unit in request["units"]}
    actual = [item["unitId"] for item in response["assignments"]]
    if len(actual) != len(expected) or set(actual) != expected:
        raise AnalysisError("analysis response must cover each unit exactly once")
    known_ids = {str(character["id"]) for character in known} | {"narrator"}
    temporary = [item["temporaryId"] for item in response["newCharacters"]]
    if len(temporary) != len(set(temporary)) or any(item in known_ids for item in temporary):
        raise AnalysisError("analysis response contains duplicate or colliding temporary ids")
    allowed = known_ids | set(temporary)
    if any(item["speakerId"] not in allowed for item in response["assignments"]):
        raise AnalysisError("analysis response contains an unknown speaker")
    if any(
        item["characterId"] not in allowed or item["characterId"] == "narrator"
        for item in response["aliasUpdates"]
    ):
        raise AnalysisError("analysis response contains an unknown alias owner")
    if any(not item["displayName"].strip() for item in response["newCharacters"]):
        raise AnalysisError("analysis response contains a blank character name")
