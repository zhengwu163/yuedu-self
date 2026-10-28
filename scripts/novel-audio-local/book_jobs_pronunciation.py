"""Pronunciation dictionary applied to synthesis text only.

Display text, highlights and text ranges stay on the phone; this only changes
what the TTS model reads. Entries replace whole words, longest first, scanning
left to right without re-reading replaced output.

File format (config/pronunciation.json, private to the service root):
{"version": 1, "entries": [
  {"source": "单牧", "target": "善牧", "scope": "global"},
  {"source": "解老板", "target": "谢老板", "scope": "<workKey>"}
]}
"""

import hashlib
import json

MAX_ENTRIES = 5000
MAX_WORD = 64


class PronunciationError(ValueError):
    pass


class PronunciationDictionary:
    def __init__(self, entries=()):
        self._entries = []
        for entry in entries:
            source = entry.get("source")
            target = entry.get("target")
            scope = entry.get("scope", "global")
            if (
                not isinstance(source, str) or not source.strip()
                or not isinstance(target, str) or not target.strip()
                or not isinstance(scope, str) or not scope
                or len(source) > MAX_WORD or len(target) > MAX_WORD
            ):
                raise PronunciationError("invalid pronunciation entry")
            self._entries.append((source, target, scope))
        if len(self._entries) > MAX_ENTRIES:
            raise PronunciationError("too many pronunciation entries")

    @classmethod
    def load(cls, path):
        if path is None or not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise PronunciationError("unreadable pronunciation dictionary") from None
        if not isinstance(data, dict) or not isinstance(data.get("entries"), list):
            raise PronunciationError("invalid pronunciation dictionary")
        return cls(data["entries"])

    def digest_for(self, work_key):
        """Changes only when entries visible to this work change."""
        visible = sorted(item for item in self._entries if item[2] in ("global", work_key))
        canonical = json.dumps(visible, ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]

    def apply(self, text, work_key):
        # A work-specific entry overrides a global entry with the same source.
        mapping = {}
        for source, target, scope in self._entries:
            if scope == "global":
                mapping.setdefault(source, target)
        for source, target, scope in self._entries:
            if scope == work_key:
                mapping[source] = target
        if not mapping:
            return text
        sources = sorted(mapping, key=len, reverse=True)
        output, position = [], 0
        while position < len(text):
            for source in sources:
                if text.startswith(source, position):
                    output.append(mapping[source])
                    position += len(source)
                    break
            else:
                output.append(text[position])
                position += 1
        return "".join(output)
