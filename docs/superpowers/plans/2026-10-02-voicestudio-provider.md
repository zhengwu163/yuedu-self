# VoiceStudio Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated VoiceStudio-backed `NovelAudioServer v1` service that replaces the current TTS bridge without coupling Android to VoiceStudio internals, works on macOS first, and is deployable on Windows later.

**Architecture:** Keep Android on the existing six-endpoint v1 contract. Add a separate Python service under `scripts/novel-audio-voicestudio/` with generic protocol validation, `DirectorProvider`, `SpeechProvider`, a VoiceStudio HTTP transport, an opaque voice registry, audio normalization, and platform launch scripts. The existing Bailian bridge remains available as a regression reference until the new service passes its own contract and integration checks.

**Tech Stack:** Python 3.10+, standard library HTTP server and urllib, unittest, ffmpeg/libopus for output normalization, VoiceStudio Local API, existing Android `NovelAudioServer v1` client.

---

## File map

- Create `docs/superpowers/specs/2026-10-02-voicestudio-provider-design.md` — approved architecture and acceptance criteria.
- Create `scripts/novel-audio-voicestudio/__init__.py` — package marker.
- Create `scripts/novel-audio-voicestudio/models.py` — provider-facing immutable request/result models.
- Create `scripts/novel-audio-voicestudio/errors.py` — sanitized provider and gateway errors.
- Create `scripts/novel-audio-voicestudio/protocol.py` — generic v1 request/response validators with no Bailian/Qwen names.
- Create `scripts/novel-audio-voicestudio/registry.py` — opaque `voiceAssetId` registry and deterministic matching.
- Create `scripts/novel-audio-voicestudio/providers.py` — `SpeechProvider` and `DirectorProvider` protocols.
- Create `scripts/novel-audio-voicestudio/voicestudio.py` — configured VoiceStudio Local API transport and speech provider.
- Create `scripts/novel-audio-voicestudio/director.py` — adapter for the currently retained chapter-analysis implementation.
- Create `scripts/novel-audio-voicestudio/audio.py` — bounded audio validation and Ogg/Opus normalization.
- Create `scripts/novel-audio-voicestudio/gateway.py` — v1 endpoint dispatcher and request policy.
- Create `scripts/novel-audio-voicestudio/server.py` — loopback-first HTTP server and bounded connection handling.
- Create `scripts/novel-audio-voicestudio/start.sh` — macOS/Linux launcher.
- Create `scripts/novel-audio-voicestudio/start.ps1` — Windows launcher.
- Create `scripts/novel-audio-voicestudio/config.example.env` — non-secret cross-platform configuration template.
- Create `scripts/novel-audio-voicestudio/test_protocol.py` — v1 validation tests.
- Create `scripts/novel-audio-voicestudio/test_registry.py` — voice identity and deterministic matching tests.
- Create `scripts/novel-audio-voicestudio/test_gateway.py` — six-endpoint gateway tests.
- Create `scripts/novel-audio-voicestudio/test_voicestudio.py` — VoiceStudio request/response mapping tests.
- Create `scripts/novel-audio-voicestudio/test_audio.py` — audio boundary and profile tests.
- Modify `.gitignore` — ignore VoiceStudio local env, registry, model/profile paths and generated audio.
- Modify `docs/AI_AUDIOBOOK_PROGRESS.md` — record the new branch and Phase 1 status after implementation, before any build.
- Modify `app/src/main/assets/updateLog.md` only if Android code changes; do not touch it for a server-only batch.

### Task 1: Add the generic provider contract tests

**Files:**
- Create: `scripts/novel-audio-voicestudio/test_protocol.py`
- Create: `scripts/novel-audio-voicestudio/test_registry.py`
- Create: `scripts/novel-audio-voicestudio/test_gateway.py`
- Create: `scripts/novel-audio-voicestudio/test_voicestudio.py`

- [ ] **Step 1: Write a failing protocol test**

```python
def test_synthesis_request_does_not_require_a_vendor_model_name():
    request = parse_synthesis({
        "text": "测试",
        "voiceAssetId": "voicestudio.narrator",
        "language": "zh-CN",
        "speed": 1.0,
    })
    assert request.voice_asset_id == "voicestudio.narrator"
```

- [ ] **Step 2: Run the focused test and verify it fails because the generic parser is absent**

Run:

```sh
python3 -m unittest discover -s scripts/novel-audio-voicestudio -p 'test_*.py' -v
```

Expected: import failure for the not-yet-created generic protocol module.

- [ ] **Step 3: Add the remaining contract cases**

Cover:

- six routes require a Bearer token;
- invalid UTF-8, duplicate JSON keys, NaN/Infinity and surrogate text are rejected;
- `analysisVersion` must be `1`;
- every input unit must receive exactly one assignment;
- `voices/match` excludes used IDs deterministically;
- audio responses require a non-empty profile;
- provider-specific model names never appear in v1 payloads.

- [ ] **Step 4: Commit the test-only red state**

```sh
git add scripts/novel-audio-voicestudio
git commit -m "test(audiobook): define generic VoiceStudio provider contract"
```

### Task 2: Implement generic models, errors, and protocol validation

**Files:**
- Create: `scripts/novel-audio-voicestudio/models.py`
- Create: `scripts/novel-audio-voicestudio/errors.py`
- Create: `scripts/novel-audio-voicestudio/protocol.py`

- [ ] **Step 1: Implement only the types required by the red tests**

Use dataclasses for:

```python
@dataclass(frozen=True)
class SynthesisRequest:
    text: str
    voice_asset_id: str
    language: str
    speed: float
```

Add equivalent chapter-analysis, voice-profile, audio-result, and health models. Keep vendor/model fields out of the Android-facing models.

- [ ] **Step 2: Implement strict JSON and v1 request parsing**

Reuse the security properties of the existing bridge—bounded JSON, duplicate-key rejection, finite numbers, UTF-8 validation—but do not import the Bailian `VoiceCatalog`, `TEXT_MODEL`, `TTS_MODEL`, result-host allowlist, or cloud quota classes.

- [ ] **Step 3: Run the focused tests**

Run:

```sh
python3 -m unittest scripts.novel-audio-voicestudio.test_protocol -v
```

Expected: all protocol tests pass.

- [ ] **Step 4: Commit the generic contract**

```sh
git add scripts/novel-audio-voicestudio/models.py scripts/novel-audio-voicestudio/errors.py scripts/novel-audio-voicestudio/protocol.py
git commit -m "feat(audiobook): add vendor-neutral novel audio contract"
```

### Task 3: Implement the opaque voice registry

**Files:**
- Create: `scripts/novel-audio-voicestudio/registry.py`
- Modify: `scripts/novel-audio-voicestudio/test_registry.py`
- Modify: `.gitignore`

- [ ] **Step 1: Add failing persistence and matching tests**

```python
def test_registry_never_exposes_provider_path():
    registry = VoiceRegistry.from_records([{
        "voiceAssetId": "voicestudio.narrator",
        "displayName": "旁白",
        "providerRef": "/private/models/voice.wav",
        "gender": "unknown",
        "ageRange": "adult",
        "traits": ["清晰"],
    }])
    assert "providerRef" not in registry.public_voices()[0]
    assert "/private/models/voice.wav" not in str(registry.public_voices())
```

Also test stable IDs, profile revision changes, used-voice exclusion, and corrupt registry fail-closed behavior.

- [ ] **Step 2: Implement registry load/save and deterministic match**

Persist only local service data. Public responses contain `voiceAssetId`, display name, gender, age range, traits and preview availability. Internal provider references remain local.

- [ ] **Step 3: Run the registry tests**

```sh
python3 -m unittest scripts.novel-audio-voicestudio.test_registry -v
```

- [ ] **Step 4: Commit**

```sh
git add .gitignore scripts/novel-audio-voicestudio/registry.py scripts/novel-audio-voicestudio/test_registry.py
git commit -m "feat(audiobook): add isolated voice asset registry"
```

### Task 4: Implement the VoiceStudio transport and SpeechProvider

**Files:**
- Create: `scripts/novel-audio-voicestudio/providers.py`
- Create: `scripts/novel-audio-voicestudio/voicestudio.py`
- Create: `scripts/novel-audio-voicestudio/config.example.env`
- Modify: `scripts/novel-audio-voicestudio/test_voicestudio.py`

- [ ] **Step 1: Add failing transport mapping tests**

Test that a configured provider:

- sends text and the resolved local VoiceStudio voice/profile;
- never sends Android `voiceAssetId` as a provider voice unless explicitly configured;
- accepts direct audio bytes and configured JSON/base64 responses;
- maps `401`, `403`, `429`, timeout and `5xx` to sanitized errors;
- reports the configured engine/profile revision in `AudioResult`.

- [ ] **Step 2: Implement the transport with explicit configuration**

Use standard-library `urllib` with no redirects, bounded response size, explicit timeouts and an Authorization header. Support the first VoiceStudio speech route used by the local installation; keep route and request field names configurable rather than hard-coding a model name.

The provider must expose:

```python
class VoiceStudioSpeechProvider:
    def health(self): ...
    def voices(self): ...
    def match(self, request): ...
    def preview(self, request): ...
    def synthesize(self, request): ...
```

- [ ] **Step 3: Run the transport tests without a live VoiceStudio process**

```sh
python3 -m unittest scripts.novel-audio-voicestudio.test_voicestudio -v
```

Expected: all request/response mapping tests pass using local HTTP fixtures.

- [ ] **Step 4: Commit**

```sh
git add scripts/novel-audio-voicestudio/providers.py scripts/novel-audio-voicestudio/voicestudio.py scripts/novel-audio-voicestudio/config.example.env scripts/novel-audio-voicestudio/test_voicestudio.py
git commit -m "feat(audiobook): add configurable VoiceStudio speech provider"
```

### Task 5: Preserve chapter analysis behind DirectorProvider

**Files:**
- Create: `scripts/novel-audio-voicestudio/director.py`
- Modify: `scripts/novel-audio-voicestudio/test_gateway.py`
- Modify: `scripts/novel-audio-voicestudio/config.example.env`

- [ ] **Step 1: Add a test proving TTS health is independent from director health**

```python
def test_tts_can_be_ready_while_director_is_unavailable():
    health = gateway.health()
    assert health.tts_ready is True
    assert health.director_ready is False
```

- [ ] **Step 2: Implement the DirectorProvider boundary**

Start with a configured HTTP director endpoint compatible with the existing chapter-analysis request/response shape. Do not move Qwen model constants into the generic protocol. If no director endpoint is configured, return `directorReady=false` and a bounded `503` for analysis.

- [ ] **Step 3: Run gateway tests**

```sh
python3 -m unittest scripts.novel-audio-voicestudio.test_gateway -v
```

- [ ] **Step 4: Commit**

```sh
git add scripts/novel-audio-voicestudio/director.py scripts/novel-audio-voicestudio/test_gateway.py scripts/novel-audio-voicestudio/config.example.env
git commit -m "feat(audiobook): isolate chapter director provider"
```

### Task 6: Add audio normalization and v1 gateway

**Files:**
- Create: `scripts/novel-audio-voicestudio/audio.py`
- Create: `scripts/novel-audio-voicestudio/gateway.py`
- Modify: `scripts/novel-audio-voicestudio/test_audio.py`
- Modify: `scripts/novel-audio-voicestudio/test_gateway.py`

- [ ] **Step 1: Add failing audio and endpoint tests**

Cover:

- Ogg/Opus pass-through when valid;
- WAV conversion through a bounded ffmpeg process;
- empty, oversized and unsupported audio rejection;
- all six endpoints and auth;
- no raw provider error text in HTTP responses;
- profile changes invalidate the response identity.

- [ ] **Step 2: Implement the gateway**

The dispatcher must preserve the existing v1 response envelope exactly:

```text
GET  /v1/health
POST /v1/chapter/analyze
GET  /v1/voices
POST /v1/voices/match
POST /v1/voices/preview
POST /v1/tts/synthesize
```

Use one non-queued generation slot, bounded body/audio reads, no redirects and cancellation-aware provider calls.

- [ ] **Step 3: Run focused tests**

```sh
python3 -m unittest scripts.novel-audio-voicestudio.test_audio scripts.novel-audio-voicestudio.test_gateway -v
```

- [ ] **Step 4: Commit**

```sh
git add scripts/novel-audio-voicestudio/audio.py scripts/novel-audio-voicestudio/gateway.py scripts/novel-audio-voicestudio/test_audio.py scripts/novel-audio-voicestudio/test_gateway.py
git commit -m "feat(audiobook): expose VoiceStudio through NovelAudioServer v1"
```

### Task 7: Add macOS/Linux and Windows launchers

**Files:**
- Create: `scripts/novel-audio-voicestudio/server.py`
- Create: `scripts/novel-audio-voicestudio/start.sh`
- Create: `scripts/novel-audio-voicestudio/start.ps1`
- Modify: `.gitignore`
- Modify: `scripts/novel-audio-voicestudio/test_gateway.py`

- [ ] **Step 1: Add launcher and binding tests**

Test loopback default, explicit LAN bind requirement, missing token failure, and graceful shutdown.

- [ ] **Step 2: Implement the HTTP server**

Use the existing bridge’s bounded connection pattern as a reference, but keep the VoiceStudio service independent. Default to `127.0.0.1`; require an explicit opt-in for LAN binding and an API key/token. Never print secrets.

- [ ] **Step 3: Add platform commands**

macOS/Linux:

```sh
sh scripts/novel-audio-voicestudio/start.sh --check
sh scripts/novel-audio-voicestudio/start.sh --serve
```

Windows PowerShell:

```powershell
.\scripts\novel-audio-voicestudio\start.ps1 -Check
.\scripts\novel-audio-voicestudio\start.ps1 -Serve
```

- [ ] **Step 4: Run all new service tests**

```sh
python3 -m unittest discover -s scripts/novel-audio-voicestudio -p 'test_*.py' -v
```

- [ ] **Step 5: Commit**

```sh
git add .gitignore scripts/novel-audio-voicestudio
git commit -m "feat(audiobook): add cross-platform VoiceStudio service launcher"
```

### Task 8: Run a real Mac VoiceStudio smoke test

**Files:**
- Modify: `scripts/novel-audio-voicestudio/README.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`

- [ ] **Step 1: Document exact local prerequisites**

Record the VoiceStudio version, selected engine/profile, MLX/CUDA/CPU backend, model source, disk usage, ffmpeg version, and API URL without recording tokens or private paths.

- [ ] **Step 2: Run one real voice preview**

Generate a short Chinese sentence with the configured VoiceStudio engine and confirm:

- non-empty decodable audio;
- response within the v1 timeout;
- profile header is stable across repeated calls;
- no raw provider secret or path appears in logs.

- [ ] **Step 3: Run the three-role local smoke**

Generate narrator, adult male and adult female samples. Save outputs only under ignored local directories and record only summarized evidence in the progress document.

- [ ] **Step 4: Run Android protocol compatibility tests**

Use the existing Android client contract tests against the local service. Do not claim full device acceptance until the real Windows service and real credentials/voice outputs are tested on the device.

- [ ] **Step 5: Commit documentation**

```sh
git add scripts/novel-audio-voicestudio/README.md docs/AI_AUDIOBOOK_PROGRESS.md
git commit -m "docs(audiobook): record VoiceStudio Mac smoke verification"
```

### Task 9: Audit, verify, and prepare Windows handoff

**Files:**
- Modify: `scripts/novel-audio-voicestudio/README.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`

- [ ] **Step 1: Run service tests and existing bridge tests**

```sh
python3 -m unittest discover -s scripts/novel-audio-voicestudio -p 'test_*.py' -v
python3 -m unittest discover -s scripts/novel-audio-bridge -p 'test_*.py' -v
python3 -m unittest discover -s scripts/novel-audio-mock -p 'test_*.py' -v
```

- [ ] **Step 2: Run the code-change/test pairing audit**

Use the project’s configured Python environment and gate runner. Any missing test pairing blocks the batch.

- [ ] **Step 3: Verify no Android behavior was changed accidentally**

```sh
git diff --name-only HEAD~1
git status --short
```

Confirm the server-only batch did not alter Android source, Room schemas, release assets or unrelated files.

- [ ] **Step 4: Document Windows deployment**

Include:

- Python/VoiceStudio installation prerequisites;
- RTX 5090D 24GB model selection and VRAM observations;
- model/profile directory configuration;
- Windows service startup and shutdown;
- LAN/TLS/token setup;
- Android connection root URL;
- required real-device acceptance cases.

- [ ] **Step 5: Commit the handoff**

```sh
git add scripts/novel-audio-voicestudio/README.md docs/AI_AUDIOBOOK_PROGRESS.md
git commit -m "docs(audiobook): prepare VoiceStudio Windows deployment handoff"
```

## Verification gates

- No new production code is merged without a test that first failed for the missing behavior.
- Existing Bailian bridge tests remain green.
- New VoiceStudio tests remain vendor-neutral and do not require a live model for protocol/unit coverage.
- Real Mac smoke evidence is recorded separately from offline/mock tests.
- Android full-device claims remain blocked until the Windows service is actually deployed and the previously defined five real-device scenarios plus three-role listening are executed.
- Before claiming completion, run the verification commands and report exact counts and exit codes.
