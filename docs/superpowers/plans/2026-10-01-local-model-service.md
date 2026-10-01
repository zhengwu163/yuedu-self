# Local Model Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working Windows local-model service for NovelAudioServer v1, with a lightweight always-on agent, on-demand Qwen3.5/Qwen3-TTS workers, explicit runtime leases, automatic model unload, deterministic local voices, and Mac-runnable fake-backend tests.

**Architecture:** Extract the HTTP and protocol-safe server core from the Bailian bridge, then inject a provider-neutral runtime and backend interface. The Windows agent remains resident only to authenticate and start workers; the worker process loads Qwen3.5 GGUF through a configured local text runner and Qwen3-TTS through a configured Python runtime, serves one bounded generation lease, and exits after release or failure. Android behavior is not modified in this plan.

**Tech Stack:** Python 3.10+ standard library for the server core and tests; Windows PowerShell for packaging and startup; configured `llama-server.exe`-compatible text runtime; Qwen `qwen-tts` Python runtime with PyTorch for VoiceDesign/Base; existing ffmpeg Ogg/Opus conversion; `unittest`.

---

## Scope and file map

The first implementation batch changes only the computer-side service and its tests.

Create:

- `scripts/novel_audio_server/__init__.py` — importable shared server package.
- `scripts/novel_audio_server/errors.py` — provider-neutral public error types and HTTP codes.
- `scripts/novel_audio_server/protocol.py` — strict JSON, v1 request/response validation, generic voice assets, and audio bounds.
- `scripts/novel_audio_server/http.py` — hardened loopback HTTP server extracted from the current bridge.
- `scripts/novel_audio_server/api.py` — generic v1 dispatcher with injected backend, voice catalog, and runtime manager.
- `scripts/novel_audio_server/runtime.py` — one-active-lease lifecycle manager and worker cleanup contract.
- `scripts/novel-audio-local/__init__.py` — local service test/import marker.
- `scripts/novel-audio-local/config.py` — local model, runtime, agent, voice, and state configuration.
- `scripts/novel-audio-local/voices.py` — deterministic local VoiceDesign/Base catalog.
- `scripts/novel-audio-local/backend.py` — provider-neutral local backend interface and fake backend.
- `scripts/novel-audio-local/qwen_backend.py` — Qwen3.5 text adapter and Qwen3-TTS adapter boundary.
- `scripts/novel-audio-local/worker.py` — child worker entrypoint and bounded IPC.
- `scripts/novel-audio-local/agent.py` — lightweight agent CLI and worker supervisor.
- `scripts/novel-audio-local/server.py` — local service entrypoint, check, serve, and smoke modes.
- `scripts/novel-audio-local/local-model.example.json` — safe configuration template with paths only.
- `scripts/novel-audio-local/README.md` — Windows installation, model layout, runtime versions, and smoke steps.
- `scripts/novel-audio-local/test_protocol.py` — shared contract validation tests.
- `scripts/novel-audio-local/test_runtime.py` — Lease and worker lifecycle tests.
- `scripts/novel-audio-local/test_backend.py` — fake backend, voice catalog, and profile tests.
- `scripts/novel-audio-local/test_http.py` — local HTTP integration tests.
- `scripts/novel-audio-local/test_cli.py` — configuration and CLI safety tests.
- `scripts/novel-audio-local/install.ps1` — Windows runtime and directory setup.
- `scripts/novel-audio-local/start-agent.ps1` — start the lightweight Agent only.
- `scripts/novel-audio-local/stop-agent.ps1` — stop the Agent and active Worker.
- `scripts/novel-audio-local/check-models.ps1` — validate model files, runtimes, CUDA visibility, and ffmpeg.
- `scripts/novel-audio-local/voices/*.json` — versioned example voice metadata without private reference audio.

Modify:

- `scripts/novel-audio-bridge/server.py` — keep the existing CLI, but use the extracted shared HTTP server.
- `scripts/novel-audio-bridge/protocol.py` — retain a compatibility layer for cloud-only parsing while delegating generic v1 validation to the shared package.
- `scripts/novel-audio-bridge/bridge.py` — adapt the existing Bailian implementation to the provider-neutral dispatcher without changing its public six-endpoint behavior.
- `scripts/novel-audio-bridge/test_bridge.py`
- `scripts/novel-audio-bridge/test_boundaries.py`
- `scripts/novel-audio-bridge/test_runtime.py`
- `scripts/novel-audio-bridge/test_cloud.py`
- `scripts/novel-audio-bridge/test_protocol.py` if created by the extraction.
- `.gitignore` — ignore local model paths, worker state, logs, connection snapshots, and generated audio.
- `app/src/main/assets/updateLog.md` — only if the service change is user-visible in the app release log; follow the existing daily entry gate before any Android build.

The following Android files are intentionally outside this first plan:

- `AudioPrefetchSession.kt`
- `AudioPrefetchLifecycle.kt`
- `NovelAudioAutoPrefetchScheduler.kt`
- `NovelAudioAutoPrefetchCoordinator.kt`
- Android settings UI and Room migrations

Those files belong to the second plan after the local service has passed Windows smoke.

## Task 1: Extract the provider-neutral protocol core

**Files:**

- Create: `scripts/novel_audio_server/__init__.py`
- Create: `scripts/novel_audio_server/errors.py`
- Create: `scripts/novel_audio_server/protocol.py`
- Modify: `scripts/novel-audio-bridge/protocol.py`
- Test: `scripts/novel-audio-local/test_protocol.py`
- Test: existing `scripts/novel-audio-bridge/test_bridge.py`
- Test: existing `scripts/novel-audio-bridge/test_boundaries.py`

- [ ] **Step 1: Write failing shared contract tests**

Add tests that assert the shared protocol returns the server-scoped `voiceAssetId`
unchanged and validates the existing v1 bounds:

```python
def test_synthesis_request_preserves_opaque_voice_asset_id():
    request = synthesis_request({
        "text": "测试",
        "voiceAssetId": "local.qwen3-tts.voice.young-male",
        "language": "zh-CN",
        "speed": 1.0,
    })
    self.assertEqual("local.qwen3-tts.voice.young-male", request["voiceAssetId"])
    self.assertEqual("zh-CN", request["language"])
    self.assertEqual(1.0, request["speed"])

def test_generic_response_rejects_unknown_assignment_unit():
    request = analysis_request(valid_analysis_request())
    with self.assertRaises(ValueError):
        analysis_response({
            "assignments": [{"unitId": "not-in-request", "speakerId": "narrator"}],
            "newCharacters": [],
            "aliasUpdates": [],
        }, request)
```

Also copy the existing duplicate-key, invalid UTF-8, surrogate, non-finite number,
audio size, and exact assignment coverage assertions into the shared test file.

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local -p 'test_*.py' -v
```

Expected: import or symbol failures because the shared package does not yet exist.

- [ ] **Step 3: Implement the shared protocol surface**

Move or reproduce only provider-neutral logic in `scripts/novel_audio_server/protocol.py`:

```python
def synthesis_request(body):
    record(body)
    text = string(body.get("text"), 1200)
    voice_asset_id = string(body.get("voiceAssetId"))
    require(body.get("language") == "zh-CN")
    speed = body.get("speed")
    require(type(speed) in (int, float) and math.isfinite(speed)
            and 0.5 <= speed <= 2.0)
    return {
        "text": text,
        "voiceAssetId": voice_asset_id,
        "language": "zh-CN",
        "speed": float(speed),
    }
```

Keep the six v1 endpoint field names unchanged. Move Bailian-only URL allowlists,
provider model constants, and cloud diagnostic errors into the cloud adapter module.

- [ ] **Step 4: Add compatibility imports to the existing bridge**

Update the existing bridge imports so its tests continue to import their current symbols.
The cloud adapter must translate the generic `voiceAssetId` into its provider voice only
inside the Bailian implementation. The generic protocol must never know about Bailian IDs.

- [ ] **Step 5: Run both local and bridge protocol tests**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local -p 'test_*.py' -v
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover -s scripts/novel-audio-bridge -p 'test_*.py' -v
```

Expected: all existing bridge tests remain green and the new shared tests pass.

- [ ] **Step 6: Commit the extraction**

```sh
git add scripts/novel_audio_server scripts/novel-audio-local/test_protocol.py \
  scripts/novel-audio-bridge/protocol.py scripts/novel-audio-bridge/test_bridge.py \
  scripts/novel-audio-bridge/test_boundaries.py
git commit -m "refactor(audiobook): extract generic audio server protocol"
```

## Task 2: Implement the runtime Lease manager

**Files:**

- Create: `scripts/novel_audio_server/runtime.py`
- Create: `scripts/novel-audio-local/test_runtime.py`
- Modify: `scripts/novel_audio_server/errors.py`

- [ ] **Step 1: Write failing Lease tests**

Cover the complete state machine:

```python
def test_acquire_starts_one_worker_and_returns_opaque_lease(self):
    runtime = RuntimeManager(FakeWorkerFactory())
    lease = runtime.acquire("session-1", "auto_prefetch", 3)
    self.assertTrue(lease.lease_id)
    self.assertEqual(RuntimeState.READY, runtime.state)
    self.assertEqual(1, runtime.factory.start_count)

def test_release_unloads_worker_and_returns_idle(self):
    factory = FakeWorkerFactory()
    runtime = RuntimeManager(factory)
    lease = runtime.acquire("session-1", "auto_prefetch", 3)
    runtime.release(lease.lease_id)
    self.assertEqual(RuntimeState.IDLE, runtime.state)
    self.assertEqual(1, factory.close_count)

def test_second_active_lease_is_rejected_without_starting_worker(self):
    factory = FakeWorkerFactory()
    runtime = RuntimeManager(factory)
    runtime.acquire("session-1", "auto_prefetch", 3)
    with self.assertRaises(BusyError):
        runtime.acquire("session-2", "pinned", 10)
    self.assertEqual(1, factory.start_count)
```

Also test expired leases, duplicate release, worker-start failure, worker-close failure,
agent close, and cleanup when a request raises.

- [ ] **Step 2: Run the tests and verify failure**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_runtime.py' -v
```

Expected: import failure for `RuntimeManager` and `RuntimeState`.

- [ ] **Step 3: Implement the minimal runtime interface**

Define the worker factory boundary without importing Qwen or PyTorch:

```python
class WorkerFactory(Protocol):
    def start(self, profile: str): ...

class RuntimeManager:
    def acquire(self, session_id: str, purpose: str, expected_chapter_count: int) -> Lease: ...
    def release(self, lease_id: str) -> None: ...
    def validate(self, lease_id: str) -> WorkerHandle: ...
    def status(self) -> RuntimeStatus: ...
    def close(self) -> None: ...
```

Use `threading.RLock`, `secrets.token_urlsafe`, monotonic deadlines, and one active lease.
Never wait indefinitely for a worker. On any close/error path, terminate the child process,
wait a bounded time, and clear the active lease before returning an error.

- [ ] **Step 4: Run runtime tests**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_runtime.py' -v
```

Expected: all lifecycle tests pass.

- [ ] **Step 5: Commit the runtime manager**

```sh
git add scripts/novel_audio_server/runtime.py scripts/novel_audio_server/errors.py \
  scripts/novel-audio-local/test_runtime.py
git commit -m "feat(audiobook): add local model runtime leases"
```

## Task 3: Build the generic API dispatcher and HTTP server

**Files:**

- Create: `scripts/novel_audio_server/api.py`
- Create: `scripts/novel_audio_server/http.py`
- Create: `scripts/novel-audio-local/test_http.py`
- Modify: `scripts/novel-audio-bridge/server.py`
- Modify: `scripts/novel-audio-bridge/bridge.py`
- Modify: `scripts/novel-audio-bridge/test_runtime.py`

- [ ] **Step 1: Write failing API tests**

Use fake backend and fake catalog objects to cover:

- all six existing endpoints;
- `/v1/runtime/acquire`;
- `/v1/runtime/release`;
- `/v1/runtime/status`;
- auth before body parsing;
- one active generation lease;
- direct Ogg response and `X-TTS-Profile`;
- unknown runtime route returns 404;
- cloud bridge behavior remains unchanged.

Example:

```python
def test_runtime_acquire_and_release_over_http(self):
    status, _, body = self.request(
        "POST", "/v1/runtime/acquire",
        {"sessionId": "s1", "purpose": "auto_prefetch", "expectedChapterCount": 3},
    )
    self.assertEqual(200, status)
    lease_id = json.loads(body)["leaseId"]

    status, _, body = self.request(
        "POST", "/v1/runtime/release",
        headers={"X-NovelAudio-Lease": lease_id},
    )
    self.assertEqual(200, status)
    self.assertEqual("idle", json.loads(body)["state"])
```

- [ ] **Step 2: Run the tests and verify failure**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_http.py' -v
```

Expected: missing generic API or runtime routes.

- [ ] **Step 3: Extract the current hardened HTTP handler**

Move `create_server` behavior from `scripts/novel-audio-bridge/server.py` into
`scripts/novel_audio_server/http.py` without weakening:

- loopback-only default;
- duplicate `Content-Length` rejection;
- `Transfer-Encoding` rejection;
- JSON and body size limits;
- slow body deadline;
- auth before body parsing;
- no raw exception logging;
- bounded connection slots;
- explicit `server_close()` cleanup.

Keep `server.create_server` as a compatibility wrapper so the existing bridge CLI and tests
continue to call the same symbol.

- [ ] **Step 4: Implement the injected API dispatcher**

The dispatcher receives:

```python
NovelAudioApi(
    token=...,
    backend=...,
    catalog=...,
    runtime=...,
)
```

For six inference routes it validates the request, checks the optional Lease header,
calls `backend.analyze()` or `backend.synthesize()`, projects the response through the
shared protocol, and returns only public fields. For runtime routes it delegates to
`RuntimeManager`.

The dispatcher must not import `cloud.py`, `BailianClient`, `DASHSCOPE_API_KEY`,
or trial-budget code.

- [ ] **Step 5: Run local and bridge HTTP suites**

Run:

```sh
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-local -p 'test_*.py' -v
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-bridge -p 'test_*.py' -v
```

Expected: new runtime endpoints pass and all 78 existing bridge tests remain green.

- [ ] **Step 6: Commit the generic server**

```sh
git add scripts/novel_audio_server/api.py scripts/novel_audio_server/http.py \
  scripts/novel-audio-local/test_http.py scripts/novel-audio-bridge/server.py \
  scripts/novel-audio-bridge/bridge.py scripts/novel-audio-bridge/test_runtime.py
git commit -m "refactor(audiobook): share NovelAudio HTTP server core"
```

## Task 4: Add local configuration and deterministic voice catalog

**Files:**

- Create: `scripts/novel-audio-local/config.py`
- Create: `scripts/novel-audio-local/voices.py`
- Create: `scripts/novel-audio-local/local-model.example.json`
- Create: `scripts/novel-audio-local/voices/standard.json`
- Create: `scripts/novel-audio-local/test_backend.py`
- Modify: `.gitignore`

- [ ] **Step 1: Write failing configuration and catalog tests**

Cover:

- default 9B text model and 4B fallback;
- explicit model paths;
- no secret/path echo in `repr()` or startup summary;
- loopback default;
- invalid public bind rejected unless an explicit LAN mode is enabled;
- deterministic voice IDs;
- narrator excluded from character matching;
- VoiceDesign entries require a nonempty prompt;
- Base entries require a relative reference audio path;
- missing reference audio is not advertised;
- profile changes when model or voice catalog version changes.

- [ ] **Step 2: Run the tests and verify failure**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_backend.py' -v
```

Expected: missing local configuration and catalog modules.

- [ ] **Step 3: Implement safe JSON configuration**

Use a strict allowlist and resolve all configured paths relative to the service directory.
The configuration must include:

```json
{
  "host": "127.0.0.1",
  "port": 8787,
  "tokenFile": "state/agent-token",
  "text": {
    "model": "qwen3.5-9b-q4_k_m",
    "modelPath": "models/qwen3.5-9b-q4_k_m/model.gguf",
    "runner": "runtime/llama-server.exe",
    "contextLength": 8192,
    "gpuLayers": 999
  },
  "tts": {
    "runtime": "runtime/tts-python",
    "voiceDesignModel": "models/qwen3-tts-voicedesign",
    "baseModel": "models/qwen3-tts-base",
    "ffmpeg": "runtime/ffmpeg/bin/ffmpeg.exe"
  },
  "voiceCatalog": "voices/standard.json"
}
```

Do not allow arbitrary shell fragments. Store command arguments as structured arrays or
fixed templates generated from validated fields.

- [ ] **Step 4: Implement deterministic local voice assets**

Expose the public fields required by v1:

```python
VoiceAsset(
    voice_asset_id="local.qwen3-tts.voice.young-male",
    display_name="青年男声",
    gender="male",
    age_range="young_adult",
    traits=("清朗", "自然"),
    preview_available=True,
)
```

Use stable sorting by constraint match, unused status, trait overlap, and asset ID.
Never include filesystem paths, model paths, prompts, or reference transcripts in `/v1/voices`.

- [ ] **Step 5: Run configuration and catalog tests**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_backend.py' -v
```

Expected: all configuration, voice, and profile tests pass.

- [ ] **Step 6: Commit local configuration**

```sh
git add scripts/novel-audio-local/config.py scripts/novel-audio-local/voices.py \
  scripts/novel-audio-local/local-model.example.json \
  scripts/novel-audio-local/voices/standard.json \
  scripts/novel-audio-local/test_backend.py .gitignore
git commit -m "feat(audiobook): add local model configuration and voices"
```

## Task 5: Implement fake and Qwen backend adapters

**Files:**

- Create: `scripts/novel-audio-local/backend.py`
- Create: `scripts/novel-audio-local/qwen_backend.py`
- Create: `scripts/novel-audio-local/test_backend.py` additions
- Create: `scripts/novel-audio-local/test_qwen_backend.py`

- [ ] **Step 1: Write failing backend contract tests**

Define the provider-neutral interface:

```python
class LocalModelBackend(Protocol):
    profile: str

    def analyze(self, request: dict) -> dict: ...
    def synthesize(self, request: dict) -> bytes: ...
    def close(self) -> None: ...
```

Tests must assert:

- fake analyze returns a complete v1 analysis response;
- fake synthesize returns nonempty Ogg with a profile;
- invalid voice IDs are rejected before model invocation;
- backend exceptions become sanitized public errors;
- `close()` is idempotent;
- the backend never logs request text or credentials.

- [ ] **Step 2: Run tests and verify failure**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_qwen_backend.py' -v
```

Expected: missing backend classes.

- [ ] **Step 3: Implement the fake backend**

The fake backend is the default Mac test backend. It must produce deterministic,
small Ogg fixture bytes and assignments derived from the supplied units, without requiring
PyTorch, CUDA, `llama-server.exe`, or model files.

- [ ] **Step 4: Implement the Qwen3.5 text adapter**

Start the configured `llama-server.exe` with a fixed argument list and communicate through
its localhost OpenAI-compatible endpoint. The adapter must:

- send the existing director system prompt;
- request JSON output;
- set a bounded timeout;
- reject redirects and oversized responses;
- parse only the assistant JSON;
- pass the result through shared `analysis_response`;
- terminate the text runner during backend close.

The adapter must support both configured model directories:

```text
qwen3.5-9b-q4_k_m
qwen3.5-4b-q4_k_m
```

Model selection is configuration data, not an Android or protocol branch.

- [ ] **Step 5: Implement the Qwen3-TTS adapter**

Launch the configured Python worker with:

- Python 3.12 environment;
- `qwen-tts`;
- matching PyTorch and torchaudio;
- CUDA device selection;
- `--no-flash-attn` as the native-Windows baseline;
- no public Gradio sharing;
- bounded stdin/stdout IPC.

Use separate methods:

```python
generate_voice_design(text, language, instruct)
generate_voice_clone(text, language, ref_audio, ref_text)
```

VoiceDesign uses the catalog prompt. Base uses a validated reference audio path and transcript.
The adapter must not pass a Base reference audio to VoiceDesign or a VoiceDesign prompt to Base.
Normalize the returned waveform into a safe PCM/WAV stream, then use the existing constrained
ffmpeg path to produce Ogg/Opus.

- [ ] **Step 6: Run fake backend tests without local model dependencies**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_backend.py' -v
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_qwen_backend.py' -v
```

Expected: all fake backend tests pass; Qwen adapter tests use subprocess and HTTP doubles,
not real model files.

- [ ] **Step 7: Commit backend adapters**

```sh
git add scripts/novel-audio-local/backend.py scripts/novel-audio-local/qwen_backend.py \
  scripts/novel-audio-local/test_backend.py scripts/novel-audio-local/test_qwen_backend.py
git commit -m "feat(audiobook): add Qwen local backend adapters"
```

## Task 6: Implement Agent, Worker, CLI, and local service smoke

**Files:**

- Create: `scripts/novel-audio-local/worker.py`
- Create: `scripts/novel-audio-local/agent.py`
- Create: `scripts/novel-audio-local/server.py`
- Create: `scripts/novel-audio-local/test_cli.py`
- Modify: `scripts/novel-audio-local/test_http.py`

- [ ] **Step 1: Write failing lifecycle/CLI tests**

Cover:

- `--init` creates configuration and token files with restrictive permissions;
- `--check` validates configuration without loading models;
- `--serve` starts only the lightweight Agent;
- first acquire starts exactly one Worker;
- release causes Worker close and process exit;
- a second request while busy receives 429;
- a Worker exception returns a fixed error and resets the Agent;
- Ctrl+C closes the HTTP server and active Worker;
- smoke mode produces three fake Ogg files and no generated file after the first failure;
- output never contains token, model path, prompt, reference transcript, or chapter text.

- [ ] **Step 2: Run tests and verify failure**

Run:

```sh
PYTHONPATH=. python3 -m unittest discover -s scripts/novel-audio-local \
  -p 'test_cli.py' -v
```

Expected: missing local CLI and Agent entrypoints.

- [ ] **Step 3: Implement bounded Worker IPC**

Use the existing `WorkerProcesses` cleanup pattern as the starting point, but make the child
worker load only the selected backend profile. The parent sends JSON requests through stdin,
reads one bounded response from stdout, and terminates the worker on timeout or close.

The child process must:

- never log to stdout;
- never print raw exceptions;
- accept only a validated operation name;
- close the model backend in `finally`;
- return sanitized error codes only.

- [ ] **Step 4: Implement the Agent**

The Agent owns:

- the HTTP server;
- token validation;
- `RuntimeManager`;
- local catalog;
- Worker factory;
- server close cleanup.

It must not import the Qwen Python model modules in the Agent process. Worker startup is the only
place allowed to import the heavy runtime.

- [ ] **Step 5: Implement CLI modes**

Provide:

```text
--init
--check
--serve
--smoke
--status
--stop
--config <path>
--fake-backend
```

`--check` validates files, ffmpeg, runner presence, model paths, Python runtime metadata,
and CUDA visibility without starting a model. `--smoke --fake-backend` is runnable on Mac.
Real `--smoke` requires the configured Windows runtimes and creates only original short text.

- [ ] **Step 6: Run the local service suite**

Run:

```sh
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-local -p 'test_*.py' -v
```

Expected: all local service tests pass with no ResourceWarning.

- [ ] **Step 7: Commit the service entrypoints**

```sh
git add scripts/novel-audio-local/worker.py scripts/novel-audio-local/agent.py \
  scripts/novel-audio-local/server.py scripts/novel-audio-local/test_cli.py \
  scripts/novel-audio-local/test_http.py
git commit -m "feat(audiobook): add on-demand local model agent"
```

## Task 7: Add Windows installation, model checks, and operator documentation

**Files:**

- Create: `scripts/novel-audio-local/install.ps1`
- Create: `scripts/novel-audio-local/start-agent.ps1`
- Create: `scripts/novel-audio-local/stop-agent.ps1`
- Create: `scripts/novel-audio-local/check-models.ps1`
- Create: `scripts/novel-audio-local/README.md`
- Modify: `.gitignore`

- [ ] **Step 1: Write script behavior tests**

Use static script checks and a temporary directory to verify:

- scripts use explicit paths;
- no script contains hard-coded API Keys or tokens;
- install creates the documented directories;
- start launches Agent only;
- stop requests Agent shutdown and does not delete model files;
- model check reports missing paths without printing secret values;
- generated local state is ignored by Git.

- [ ] **Step 2: Implement installation script**

`install.ps1` must:

- require Windows PowerShell 5.1+;
- create `config`, `state`, `logs`, `models`, `voices`, and `runtime`;
- preserve existing model directories;
- write a restrictive local token;
- write `local-model.json` only when absent;
- print the next command without printing the token.

- [ ] **Step 3: Implement model/runtime checks**

`check-models.ps1` must validate:

- Python 3.12 executable;
- `qwen-tts` import;
- matching `torch` and `torchaudio`;
- `torch.cuda.is_available()`;
- `llama-server.exe`;
- selected GGUF file;
- VoiceDesign and Base model directories;
- ffmpeg with Ogg/Opus support.

The check must distinguish missing files, CPU-only PyTorch, unavailable CUDA, and invalid audio
encoder capability using fixed messages.

- [ ] **Step 4: Document the Windows package**

Document:

- RTX 5090D v2 24GB profile;
- Qwen3.5 9B/4B selection;
- Qwen3-TTS VoiceDesign versus Base;
- Python 3.12 and matching PyTorch/torchaudio;
- native Windows baseline with FlashAttention disabled;
- model file placement and SHA-256 manifest;
- first `--check`;
- `start-agent.ps1`;
- Android connection fields;
- `adb reverse` for USB testing;
- three-role fake smoke and real smoke;
- model unload verification;
- local network firewall/token handling;
- license files and model redistribution obligations.

- [ ] **Step 5: Run PowerShell syntax and static checks**

Run on Windows:

```powershell
Get-ChildItem scripts\novel-audio-local\*.ps1 | ForEach-Object {
  [System.Management.Automation.Language.Parser]::ParseFile(
    $_.FullName,
    [ref]$null,
    [ref]$null
  ) | Out-Null
}
.\scripts\novel-audio-local\check-models.ps1 -Config .\scripts\novel-audio-local\local-model.json
```

Expected: no parser errors; the checker reports explicit pass/fail fields without secrets.

- [ ] **Step 6: Commit Windows packaging**

```sh
git add scripts/novel-audio-local/install.ps1 scripts/novel-audio-local/start-agent.ps1 \
  scripts/novel-audio-local/stop-agent.ps1 scripts/novel-audio-local/check-models.ps1 \
  scripts/novel-audio-local/README.md .gitignore
git commit -m "feat(audiobook): add Windows local model package scripts"
```

## Task 8: Run Mac verification and prepare Windows real smoke

**Files:**

- Modify: `scripts/novel-audio-local/README.md`
- Modify: `docs/AI_AUDIOBOOK_PROGRESS.md`
- Modify: `docs/AI_AUDIOBOOK_HANDOFF.md`
- Create: `scripts/novel-audio-local/SMOKE_CHECKLIST.md`

- [ ] **Step 1: Run the complete computer-side offline suite**

Run:

```sh
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-local -p 'test_*.py' -v
PYTHONPATH=. python3 -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-bridge -p 'test_*.py' -v
```

Expected: local tests pass and the existing 78 bridge tests remain green.

- [ ] **Step 2: Run fake three-role smoke**

Run:

```sh
PYTHONPATH=. python3 scripts/novel-audio-local/server.py \
  --config scripts/novel-audio-local/local-model.example.json \
  --smoke --fake-backend
```

Expected:

- one runtime Lease;
- three distinct deterministic voice selections;
- three nonempty Ogg files;
- Worker close after release;
- no model path, token, prompt, or source text in stdout/stderr.

- [ ] **Step 3: Write the Windows real smoke checklist**

The checklist must record:

1. Agent starts with zero local model processes and no model GPU allocation.
2. `check-models.ps1` passes.
3. `--status` reports `idle`.
4. `--smoke` loads Qwen3.5 and Qwen3-TTS.
5. One original three-role chapter produces three decodable Ogg files.
6. `--status` reports `idle` after release.
7. Worker processes are gone.
8. GPU memory returns to the recorded idle baseline.
9. A second run reloads models and succeeds.
10. 4B/9B and VoiceDesign/Base selections are independently testable.

- [ ] **Step 4: Update progress and handoff from evidence**

Only record real Windows results after the commands above run on the RTX 5090D machine.
Do not mark Android preload, full mobile playback, or online Provider configuration complete
in this first plan.

- [ ] **Step 5: Run repository gates for the changed computer-side files**

Run the project’s Python environment and gate commands applicable to the batch:

```sh
ai_tests/venv/bin/python ai_tests/scripts/audit_code_change_has_test.py
ai_tests/venv/bin/python ai_tests/scripts/run_gates.py --stage commit
git diff --check
git status --short
```

Expected: every changed production file has a paired test, registered commit gates pass, and
only intended local-model files remain changed.

- [ ] **Step 6: Commit evidence updates**

```sh
git add scripts/novel-audio-local/SMOKE_CHECKLIST.md \
  docs/AI_AUDIOBOOK_PROGRESS.md docs/AI_AUDIOBOOK_HANDOFF.md
git commit -m "docs(audiobook): record local model service validation"
```

## Completion criteria for this plan

The first plan is complete only when all of the following are true:

- Mac offline tests pass for the local service and the existing Bailian bridge;
- fake three-role smoke creates and validates three Ogg files;
- the Agent remains available while no model process is running;
- acquire starts one Worker and release unloads it;
- invalid or duplicate leases cannot start extra Workers;
- Qwen3.5 4B/9B and Qwen3-TTS VoiceDesign/Base are configurable through validated paths;
- Windows scripts validate Python, PyTorch, CUDA, model paths, runner, and ffmpeg;
- the RTX 5090D Windows smoke proves model load, three-role audio generation, Worker exit,
  and GPU memory recovery;
- progress/handoff documents report only verified states;
- every changed production file has paired tests and the commit gate passes;
- no Android preload behavior is changed by this plan.
