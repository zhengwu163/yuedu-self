# NovelAudio Local Model Service Stage7B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已通过 Windows 原生阶段 0～7A 的基础上，完成可真实运行的 NovelAudio 本地 Agent、Worker、Qwen adapter、双模式 Runtime Lease、HTTP v1 服务和 Windows 运维脚本。

**Architecture:** 常驻 Agent 只负责鉴权、HTTP、模型注册表、Voice Catalog 和 Lease；按需 Worker 使用 Windows 原生 tts-venv 加载 Qwen3-TTS，并管理 Windows 原生 llama-server 子进程和 ffmpeg。现有六个 v1 推理端点兼容无 Lease 的单请求自动生命周期，同时支持显式 `acquire → 多次生成 → release` 批次模式。

**Tech Stack:** Python 3.12 on Windows, Python standard library, `unittest`, `llama-server.exe` OpenAI-compatible localhost API, `qwen-tts==0.1.1`, PyTorch/torchaudio CUDA runtime, Windows PowerShell, ffmpeg/ffprobe, existing NovelAudioServer v1 protocol.

---

## Scope and worktree rules

本计划只针对：

```text
/Users/zzz/baidu/personal-code/reading/.worktrees/local-model-service
branch: feat/local-model-service
```

保留以下边界：

- Android 产品主线 `feat/ai-audiobook` 不在本计划修改；
- `feat/ai-audiobook-voicestudio` 不在本计划修改；
- 当前 worktree 已有用户未提交改动，实施前必须记录并避开；
- 禁止 `git reset --hard`、`git checkout --`、删除未跟踪文件或清理已有改动；
- 禁止 `git add -A`；
- 每个提交只加入本任务已经完成的明确路径；
- Windows 模型权重、Token、参考音频、日志和本机 JSON 不进入 Git；
- 每个生产文件必须有配对测试，提交前运行项目注册门禁。

阶段 7A 的事实基线：

- default：9B + VoiceDesign，两轮和切换通过；
- fallback：4B + Base，两轮和切换通过；
- Base 使用 `D:/NovelAudioLocal/state/reference-audio/reference.wav`；
- Base 当前使用 `x_vector_only_mode=True`，没有 `ref_text`；
- 4B/9B 章节分析返回合法 assignment JSON；
- VoiceDesign/Base 音频均为可解码 WAV/PCM；
- release 后进程和显存回落；
- 阶段 7B 尚未验证真实 HTTP 服务、生产 Worker IPC、Runtime Lease 和 Android 联调。

## Execution order and handoff gates

按以下顺序执行，Windows 真实模型验证不会提前开始：

本次交付采用带逐文件 SHA-256 的源码工作树快照，不执行提交或推送。
下文各批次的 commit 步骤保留为后续获准提交时的参考，不作为本次交付动作。

1. 本分支先记录 dirty worktree、修正本计划，建立可复现的
   macOS 专用 Python 测试入口。
2. 在本分支完成 registry、Catalog、Lease、Worker IPC、Qwen fake
   contract、CLI 和 HTTP 的实现；每个生产改动先有失败测试，再运行对应
   focused tests。
3. macOS local service suite、bridge suite、fake three-role smoke 和项目
   gates 全部通过后，才生成 Windows handoff。
4. Windows Agent 只使用 `feat/local-model-service` 已交付的脚本和配置，
   使用 Windows 原生运行时执行真实 4B/9B、VoiceDesign/Base、Profile
   切换、进程树、端口和显存验收；不修改 Git。
5. 我收到 Windows 完整报告后，只在本分支回填证据和进度文档；若有
   `FAIL`、`BLOCKED` 或 `NOT_TESTED`，先修复或补验收，不能进入 Android。
6. 阶段 7B 全部通过后，停止本分支工作，另开 `feat/ai-audiobook`
   的 Android 集成批次。

## File map

### Existing service files to continue

- `scripts/novel_audio_server/protocol.py`
  - Keep strict JSON, v1 request validation and response validation.
- `scripts/novel_audio_server/errors.py`
  - Add fixed public error codes only; never expose raw exception text.
  - Keep `busy` and `invalid_lease` as the public 429/409 codes used by the
    existing protocol; add `runner_incompatible`, `missing_reference_audio`,
    `capability_unavailable` and `worker_unavailable` only where the API needs
    to distinguish those cases.
  - Add `RunnerUnavailableError` with code `runner_unavailable` and
    `RunnerIncompatibleError` with code `runner_incompatible`.
- `scripts/novel_audio_server/runtime.py`
  - Keep one active Lease and bounded lifecycle; add profile-aware worker startup and cancellation state.
- `scripts/novel_audio_server/api.py`
  - Implement dual-mode Lease, metadata allowlist, response MIME/profile handling and request cleanup.
- `scripts/novel_audio_server/http.py`
  - Preserve loopback, framing, body limits and auth-before-body parsing; connect client disconnect to cancellation.
- `scripts/novel-audio-local/model_registry.py`
  - Add real file/directory hash verification and Profile identity inputs.
  - Allow explicitly configured absolute model paths outside the service root;
    apply containment only to service-owned private assets such as reference
    audio, state and logs.
- `scripts/novel-audio-local/config.py`
  - Load Windows binding configuration without exposing paths through `repr`, HTTP or normal status.
- `scripts/novel-audio-local/voices.py`
  - Filter voices by active Profile capability and validate Base reference audio.
- `scripts/novel-audio-local/backend.py`
  - Keep deterministic fake backend and provider-neutral backend contract.
- `scripts/novel-audio-local/qwen_backend.py`
  - Replace placeholder text/TTS calls with real Worker-local adapters.
- `scripts/novel-audio-local/worker.py`
  - Launch the Worker with the configured Windows tts-venv Python and manage subprocess trees.
- `scripts/novel-audio-local/agent.py`
  - Construct profile-aware registry, catalog, worker factory, runtime and API.
- `scripts/novel-audio-local/server.py`
  - Implement real `--check`, `--status`, `--stop`, `--serve`, `--smoke` and fake smoke behavior.

### Files to create

- `scripts/novel-audio-local/checks.py`
  - Shared static/runtime check functions and stable result/exit-code mapping.
- `scripts/novel-audio-local/process.py`
  - Windows-safe process-tree termination and bounded wait helpers, with test doubles.
- `scripts/novel-audio-local/test_checks.py`
- `scripts/novel-audio-local/test_process.py`
- `scripts/novel-audio-local/test_profile_modes.py`
- `scripts/novel-audio-local/install.ps1`
- `scripts/novel-audio-local/start-agent.ps1`
- `scripts/novel-audio-local/stop-agent.ps1`
- `scripts/novel-audio-local/check-models.ps1`
- `scripts/novel-audio-local/README.md`
- `scripts/novel-audio-local/SMOKE_CHECKLIST.md`

### Files to modify only if required by regression tests

- `scripts/novel-audio-bridge/protocol.py`
- `scripts/novel-audio-bridge/server.py`
- `scripts/novel-audio-bridge/bridge.py`
- existing bridge tests

Do not modify Android files during this plan.

### Canonical capability and operation names

The registry, Voice Catalog and HTTP metadata use these hyphenated capability
IDs:

```text
chapter-analysis
voice-design
voice-clone
speech-synthesis
```

`zh-CN` is a locale capability, not a model operation. Internal adapter
operation values remain `voice_design` and `voice_clone`; they are not exposed
as capability IDs.

The registry loader accepts the legacy underscore spellings
`chapter_analysis`, `voice_design`, `voice_clone` and `speech_synthesis` as
input aliases, normalizes them to the four hyphenated IDs above, and emits
only the canonical hyphenated IDs in public metadata. This preserves the
approved design vocabulary while keeping the existing fixtures and HTTP
contract interoperable.

---

### Task 0: Record and isolate the existing dirty worktree

**Files:**

- Read only: current `git status`, `git diff --name-status`
- Create: `docs/superpowers/plans/2026-10-01-local-model-service-stage7b.md`

- [ ] **Step 1: Record the pre-existing change set**

Run:

```sh
git status --short --branch
git diff --name-status
git ls-files --others --exclude-standard
```

Save the output outside the repository or in the task handoff, and classify paths into:

- existing user changes;
- stage7b service files;
- unrelated Android files.

Do not stage or modify any file in this step.

- [ ] **Step 2: Establish the baseline test commands**

Run without model loading:

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-local -p 'test_*.py' -v
PYTHONPATH=.:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-bridge -p 'test_*.py' -v
```

Record the current failures. Do not convert a pre-existing failure into a
stage7b success claim.

- [ ] **Step 3: Commit only the approved plan document**

The design is approved; this plan must be committed separately from the dirty
implementation worktree after the plan self-review passes:

```sh
git add -- docs/superpowers/plans/2026-10-01-local-model-service-stage7b.md
git commit -m "docs(audiobook): plan local model service stage7b"
```

Expected: no Android files, VoiceStudio files, model files or unrelated
worktree changes are included.

---

### Task 1: Implement real model registry integrity and Profile identity

**Files:**

- Modify: `scripts/novel-audio-local/model_registry.py`
- Modify: `scripts/novel-audio-local/config.py`
- Create/modify: `scripts/novel-audio-local/test_models.py`
- Create/modify: `scripts/novel-audio-local/test_backend.py`

- [ ] **Step 1: Write failing hash and path-containment tests**

Extend `scripts/novel-audio-local/test_models.py` with concrete temporary fixtures.
The registry fixture must point at files created inside the temporary directory,
and the test must call the public verification method that the implementation
adds:

```python
def test_file_asset_hash_mismatch_blocks_profile(self):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        value = self.registry_with_real_assets(root)
        value["models"][0]["sha256"] = "0" * 64
        registry = ModelRegistry.load(self.write_registry(root, value), root=root)
        with self.assertRaises(ModelRegistryError) as caught:
            registry.verify_profile()
        self.assertEqual("asset_hash_mismatch", caught.exception.code)

def test_directory_asset_hash_manifest_detects_changed_file(self):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        value = self.registry_with_real_assets(root)
        model_dir = root / "models" / "future-tts"
        registry = ModelRegistry.load(self.write_registry(root, value), root=root)
        registry.verify_profile()
        (model_dir / "weights.bin").write_bytes(b"weights-v2")
        with self.assertRaises(ModelRegistryError) as caught:
            registry.verify_profile()
        self.assertEqual("asset_hash_mismatch", caught.exception.code)

def test_absolute_model_path_outside_service_root_is_allowed(self):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory) / "service"
        root.mkdir()
        outside = Path(directory) / "outside.gguf"
        outside.write_bytes(b"model")
        value = self.valid_registry()
        value["models"][0]["path"] = str(outside)
        registry = ModelRegistry.load(self.write_registry(root, value), root=root)
        self.assertEqual(outside.resolve(), registry.asset("text-future-1").path)

def test_profile_identity_changes_when_catalog_or_reference_changes(self):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        catalog = root / "voices.json"
        reference = root / "reference.wav"
        catalog.write_text('{"version":"1","voices":[]}', encoding="utf-8")
        reference.write_bytes(b"reference-v1")
        value = self.registry_with_real_assets(root)
        first = ModelRegistry.load(self.write_registry(root, value), root=root)
        first_identity = first.profile_identity(
            first.active_profile(),
            sha256_file(catalog),
            sha256_file(reference),
        )
        catalog.write_text('{"version":"2","voices":[]}', encoding="utf-8")
        second = ModelRegistry.load(self.write_registry(root, value), root=root)
        second_identity = second.profile_identity(
            second.active_profile(),
            sha256_file(catalog),
            sha256_file(reference),
        )
        self.assertNotEqual(
            first_identity,
            second_identity,
        )
        reference.write_bytes(b"reference-v2")
        third = ModelRegistry.load(self.write_registry(root, value), root=root)
        self.assertNotEqual(
            second_identity,
            third.profile_identity(
                third.active_profile(),
                sha256_file(catalog),
                sha256_file(reference),
            ),
        )
```

The tests must use temporary files only and must not access Windows model paths.
Add the fixture helper used above to the same test class:

```python
def registry_with_real_assets(self, root):
    text = root / "models" / "future-text.gguf"
    tts = root / "models" / "future-tts"
    tts.mkdir(parents=True)
    text.parent.mkdir(parents=True, exist_ok=True)
    text.write_bytes(b"text-v1")
    (tts / "config.json").write_text("{}", encoding="utf-8")
    (tts / "weights.bin").write_bytes(b"weights-v1")
    value = self.valid_registry()
    value["models"][0]["path"] = "models/future-text.gguf"
    value["models"][0]["sha256"] = sha256_file(text)
    value["models"][1]["path"] = "models/future-tts"
    value["models"][1]["sha256"] = sha256_directory_manifest(tts)
    return value
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" -m unittest \
  test_models -v
```

Expected: the new verification methods do not exist or the current empty
checksum behavior allows the invalid fixtures.

- [ ] **Step 3: Add explicit asset verification**

Implement these provider-neutral operations:

```python
def sha256_file(path, chunk_size=1024 * 1024) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(chunk_size), b""):
            digest.update(block)
    return digest.hexdigest()

def sha256_directory_manifest(path) -> str:
    entries = []
    root = Path(path).resolve()
    for child in sorted(root.rglob("*")):
        if child.is_symlink():
            raise ModelRegistryError("symlink_asset")
        if child.is_file():
            relative = child.relative_to(root).as_posix()
            entries.append((relative, sha256_file(child)))
    encoded = json.dumps(
        entries,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()

def verify_asset(asset: ModelAsset) -> AssetCheck:
    if not asset.sha256:
        return AssetCheck("FAIL", "missing_sha256")
    if asset.path.is_symlink() or not asset.path.exists():
        return AssetCheck("FAIL", "asset_missing")
    if asset.path.is_file():
        actual = sha256_file(asset.path)
    elif asset.path.is_dir():
        actual = sha256_directory_manifest(asset.path)
    else:
        return AssetCheck("FAIL", "asset_type_invalid")
    if actual.lower() != asset.sha256.lower():
        return AssetCheck("FAIL", "asset_hash_mismatch")
    return AssetCheck("PASS", "ok")
```

Rules:

- regular GGUF files require a nonempty 64-character SHA-256;
- TTS directories require a complete deterministic file manifest;
- symlinks are rejected for model assets and manifest entries;
- directory traversal order is sorted by relative POSIX path;
- only the final status and fixed reason code are emitted in HTTP metadata;
- CLI diagnostics may show the local path;
- no model file is copied or modified.

- [ ] **Step 4: Add profile-level validation**

Add:

```python
def verify_profile(profile, catalog=None, available_vram_gb=None) -> ProfileCheck:
    checks = [verify_asset(profile.text_asset), verify_asset(profile.tts_asset)]
    if any(item.status != "PASS" for item in checks):
        return ProfileCheck("FAIL", "asset_invalid", tuple(checks))
    if "chapter-analysis" not in profile.text_asset.capabilities:
        return ProfileCheck("FAIL", "capability_unavailable", tuple(checks))
    if profile.max_concurrency != 1:
        return ProfileCheck("FAIL", "invalid_max_concurrency", tuple(checks))
    if available_vram_gb is not None and available_vram_gb < profile.required_vram_gb:
        return ProfileCheck("BLOCKED", "insufficient_vram", tuple(checks))
    if catalog is not None:
        required = "voice-clone" if "voice-clone" in profile.capabilities else "voice-design"
        if required not in profile.tts_asset.capabilities:
            return ProfileCheck("FAIL", "capability_unavailable", tuple(checks))
    return ProfileCheck("PASS", "ok", tuple(checks))
```

It must validate:

- text asset type is `text`;
- TTS asset type is `tts`;
- text capability contains `chapter-analysis`;
- VoiceDesign profile contains `voice-design`;
- Base profile contains `voice-clone`;
- `maxConcurrency == 1`;
- required VRAM does not exceed configured minimum;
- Base reference audio exists and is readable before advertising `voice-clone`;
- all asset checks pass before `ready`.

Define the result records in `model_registry.py` before using them elsewhere:

```python
@dataclass(frozen=True)
class AssetCheck:
    status: str
    code: str

@dataclass(frozen=True)
class ProfileCheck:
    status: str
    code: str
    asset_checks: tuple
```

`ModelRegistry.verify_profile(profile_id=None, catalog=None,
available_vram_gb=None)` calls the function above and raises
`ModelRegistryError` with the fixed `code` from a non-PASS `ProfileCheck`; the
CLI consumes the structured result without printing exception text.
`ModelRegistry.profile_identity(profile, catalog_sha256, reference_sha256)`
builds the public digest from the registry/profile fields plus those two
verified input hashes and the fixed encoding/synthesis revision strings.
`ModelRegistry.load()` must preserve explicitly configured absolute model paths,
including paths outside the service root. It must reject symlinked model assets
and symlinked entries inside model directories. Private service-owned assets
such as Base reference audio must separately resolve below the service root and
reject `..` traversal or symlinked parents.

- [ ] **Step 5: Expand Profile identity**

The identity digest must include:

- registry version;
- asset IDs;
- verified file or directory hashes;
- adapter IDs;
- Voice Catalog content/version digest;
- Base reference audio digest when used;
- capability set;
- audio encoding revision;
- synthesis parameter revision.

Do not include absolute paths in the digest payload exposed through HTTP.

- [ ] **Step 6: Run focused tests**

Run:

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" -m unittest \
  test_models test_backend -v
```

Expected: all registry, profile, catalog and fake backend tests pass.

- [ ] **Step 7: Commit the registry batch**

```sh
git add -- scripts/novel-audio-local/model_registry.py \
  scripts/novel-audio-local/config.py \
  scripts/novel-audio-local/test_models.py \
  scripts/novel-audio-local/test_backend.py
git commit -m "feat(audiobook): verify local model bindings"
```

Do not stage existing Android changes.

---

### Task 2: Make Voice Catalog and metadata capability-safe

**Files:**

- Modify: `scripts/novel-audio-local/voices.py`
- Modify: `scripts/novel-audio-local/agent.py`
- Modify: `scripts/novel_audio_server/api.py`
- Modify: `scripts/novel-audio-local/test_backend.py`
- Modify: `scripts/novel-audio-local/test_http.py`

- [ ] **Step 1: Write failing capability-filter tests**

Extend the existing catalog and HTTP fixtures with an explicit `capabilities`
argument. Each assertion must verify both the advertised IDs and the dispatch
guard before backend invocation:

```python
def test_voicedesign_profile_does_not_advertise_base_voice(self):
    catalog = self.catalog_with_voicedesign_and_base()
    voices = catalog.public_voices({"voice-design"})
    self.assertIn("local.voicedesign", {item["voiceAssetId"] for item in voices})
    self.assertNotIn("local.base", {item["voiceAssetId"] for item in voices})

def test_base_profile_does_not_advertise_voicedesign_voice(self):
    catalog = self.catalog_with_voicedesign_and_base()
    voices = catalog.public_voices({"voice-clone"})
    self.assertIn("local.base", {item["voiceAssetId"] for item in voices})
    self.assertNotIn("local.voicedesign", {item["voiceAssetId"] for item in voices})

def test_missing_reference_audio_is_not_advertised(self):
    catalog = self.catalog_with_missing_base_reference()
    self.assertFalse(catalog.contains("local.base", {"voice-clone"}))
    self.assertEqual([], catalog.public_voices({"voice-clone"}))

def test_runtime_metadata_rejects_path_fields(self):
    metadata = self.backend_with_sensitive_metadata().runtime_metadata()
    self.assertEqual(
        {"profileId", "identity", "capabilities", "minVramGb", "hardware"},
        set(metadata),
    )
    self.assertNotIn("modelPath", metadata)
    self.assertNotIn("referenceAudioPath", metadata)

def test_config_repr_contains_no_absolute_root(self):
    config = self.load_config_with_root("/private/windows/model-root")
    rendered = repr(config) + config.startup_summary()
    self.assertNotIn("/private/windows/model-root", rendered)
```

The fixture helpers must create the catalog JSON and Base reference file under
`tempfile.TemporaryDirectory()`; no test may use a real Windows binding.

- [ ] **Step 2: Implement profile-aware catalog projection**

Make catalog operations accept the active TTS capabilities:

```python
catalog.public_voices(capabilities)
catalog.contains(voice_asset_id, capabilities)
catalog.asset(voice_asset_id, capabilities)
catalog.match(persona, used, constraints, capabilities)
```

Use these rules:

- `kind == "voicedesign"` requires `voice-design`;
- `kind == "base"` requires `voice-clone`;
- Base voice requires a valid in-root reference audio;
- unavailable assets are absent from `/v1/voices` and `/v1/voices/match`;
- `previewAvailable` is false when its input is unavailable.

- [ ] **Step 3: Add a strict runtime metadata allowlist**

Project only:

```python
{
    "profileId": str,
    "identity": str,
    "capabilities": list[str],
    "minVramGb": number | None,
    "hardware": {"status": fixed_status_code},
}
```

Reject or omit every other field, including `path`, `modelPath`,
`referenceAudioPath`, `command`, `source`, `license` and prompt text.

- [ ] **Step 4: Run HTTP and catalog tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" -m unittest \
  test_backend test_http -v
```

- [ ] **Step 5: Commit the capability batch**

```sh
git add -- scripts/novel-audio-local/voices.py \
  scripts/novel-audio-local/agent.py \
  scripts/novel_audio_server/api.py \
  scripts/novel-audio-local/test_backend.py \
  scripts/novel-audio-local/test_http.py
git commit -m "feat(audiobook): gate local voices by profile"
```

---

### Task 3: Implement dual-mode Runtime Lease and request cleanup

**Files:**

- Modify: `scripts/novel_audio_server/runtime.py`
- Modify: `scripts/novel_audio_server/api.py`
- Modify: `scripts/novel_audio_server/http.py`
- Modify: `scripts/novel-audio-local/test_runtime.py`
- Modify: `scripts/novel-audio-local/test_http.py`

Before adding the cases below, extend the existing `FakeRuntime` fixture with
the exact state used by the assertions:

```python
class FakeRuntime:
    def __init__(self, backend):
        self.backend = backend
        self.lease_id = "lease-1"
        self.released = []
        self.acquired_and_released = []
        self.start_count = 0
        self.generation_started = False
        self.clock_value = 0.0

    def advance_clock(self, seconds):
        self.clock_value += seconds

    def acquire(self, session_id, purpose, expected_chapter_count):
        self.start_count += 1
        lease = SimpleNamespace(
            lease_id=self.lease_id,
            session_id=session_id,
            purpose=purpose,
            expected_chapter_count=expected_chapter_count,
        )
        self.acquired_and_released.append(self.lease_id)
        return lease

    def begin_generation(self, lease_id):
        if lease_id != self.lease_id:
            raise LeaseError()
        self.generation_started = True
        return self.backend

    def end_generation(self, lease_id):
        if lease_id != self.lease_id:
            raise LeaseError()
        self.generation_started = False

    def release(self, lease_id):
        if lease_id != self.lease_id:
            raise LeaseError()
        if self.generation_started:
            raise ServiceBusyError()
        self.released.append(lease_id)
```

The real test fixture must record `acquired_and_released` only after a
request-scoped Lease has reached `release`; the shortened example above may
use a separate `pending_auto_leases` list to implement that distinction.
Add these helpers to the HTTP test class:

```python
def acquire_runtime_lease(self):
    status, _, body = self.request(
        "POST",
        "/v1/runtime/acquire",
        {
            "sessionId": "s1",
            "purpose": "batch",
            "expectedChapterCount": 1,
        },
    )
    self.assertEqual(200, status)
    return json.loads(body)["leaseId"]
```

- [ ] **Step 1: Write failing dual-mode Lease tests**

Add tests for:

```python
def test_unleased_analysis_acquires_and_releases_automatically(self):
    status, _, body = self.request("POST", "/v1/chapter/analyze", analysis_request())
    self.assertEqual(200, status)
    self.assertEqual("narrator", json.loads(body)["assignments"][0]["speakerId"])
    self.assertEqual(["lease-1"], self.runtime.acquired_and_released)

def test_unleased_synthesis_releases_after_backend_error(self):
    self.backend.fail_synthesis = True
    status, _, body = self.request("POST", "/v1/tts/synthesize", synthesis_request())
    self.assertEqual(503, status)
    self.assertEqual({"error": {"code": "backend_unavailable"}}, json.loads(body))
    self.assertEqual(["lease-1"], self.runtime.acquired_and_released)

def test_explicit_lease_is_reused_without_second_worker_start(self):
    lease = self.acquire_runtime_lease()
    self.assertEqual(200, self.request(
        "POST", "/v1/chapter/analyze", analysis_request(),
        headers={"X-NovelAudio-Lease": lease},
    )[0])
    self.assertEqual(200, self.request(
        "POST", "/v1/tts/synthesize", synthesis_request(),
        headers={"X-NovelAudio-Lease": lease},
    )[0])
    self.assertEqual(1, self.runtime.start_count)

def test_second_acquire_returns_busy_without_starting_worker(self):
    self.acquire_runtime_lease()
    status, _, body = self.request(
        "POST",
        "/v1/runtime/acquire",
        {"sessionId": "s2", "purpose": "batch", "expectedChapterCount": 2},
    )
    self.assertEqual(429, status)
    self.assertEqual({"error": {"code": "busy"}}, json.loads(body))
    self.assertEqual(1, self.runtime.start_count)

def test_release_during_generation_is_rejected(self):
    lease = self.acquire_runtime_lease()
    self.runtime.generation_started = True
    status, _, body = self.request(
        "POST",
        "/v1/runtime/release",
        headers={"X-NovelAudio-Lease": lease},
    )
    self.assertEqual(429, status)
    self.assertEqual({"error": {"code": "busy"}}, json.loads(body))

def test_expired_lease_closes_worker_and_returns_idle(self):
    lease = self.acquire_runtime_lease()
    self.runtime.advance_clock(301)
    status, _, body = self.request(
        "POST",
        "/v1/chapter/analyze",
        analysis_request(),
        headers={"X-NovelAudio-Lease": lease},
    )
    self.assertEqual(409, status)
    self.assertEqual({"error": {"code": "lease_expired"}}, json.loads(body))
    self.assertEqual("idle", self.runtime.status().state.value)

def test_invalid_lease_never_reaches_backend(self):
    status, _, body = self.request(
        "POST",
        "/v1/chapter/analyze",
        analysis_request(),
        headers={"X-NovelAudio-Lease": "not-valid"},
    )
    self.assertEqual(409, status)
    self.assertEqual({"error": {"code": "invalid_lease"}}, json.loads(body))
    self.assertEqual([], self.backend.analysis_requests)
```

- [ ] **Step 2: Run the tests and verify failure**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" -m unittest \
  test_runtime test_http -v
```

Expected: current API rejects unleased generation with a Lease error.

- [ ] **Step 3: Implement request-scoped Lease ownership**

Add an internal request lease helper:

```python
class RequestLease:
    def __init__(self, runtime, lease_id, session_id, purpose):
        self.runtime = runtime
        self.lease_id = lease_id
        self.session_id = session_id
        self.purpose = purpose
        self.lease = None
        self.request_lease = False

    def __enter__(self):
        if self.lease_id:
            return self.runtime.begin_generation(self.lease_id)
        self.lease = self.runtime.acquire(self.session_id, self.purpose, 1)
        self.lease_id = self.lease.lease_id
        self.request_lease = True
        try:
            return self.runtime.begin_generation(self.lease_id)
        except BaseException:
            try:
                self.runtime.release(self.lease_id)
            except BaseException:
                pass
            self.request_lease = False
            raise

    def __exit__(self, exc_type, exc, traceback):
        # Preserve the backend exception; cleanup failures are handled by API.
        cleanup_error = None
        try:
            self.runtime.end_generation(self.lease_id)
        except BaseException as error:
            cleanup_error = error
        finally:
            if self.request_lease:
                try:
                    self.runtime.release(self.lease_id)
                except BaseException as error:
                    cleanup_error = cleanup_error or error
        if exc_type is not None:
            return False
        if cleanup_error is not None:
            raise cleanup_error
        return False
```

The API constructs this helper with the incoming
`X-NovelAudio-Lease` value (or `None`) and wraps exactly one backend call in
the context. If backend work raises, `__exit__` preserves that exception; if
automatic cleanup raises after a successful backend call, the API maps it to
`worker_unavailable`.

Rules:

- explicit valid Lease calls `begin_generation` and `end_generation`;
- no Lease calls `acquire`, `begin_generation`, `end_generation`, `release`;
- cleanup errors must not hide the original backend error;
- request-scoped Lease uses a unique server-generated session ID;
- request cleanup is bounded;
- runtime endpoints remain explicit-only;
- `/v1/voices` and `/v1/voices/match` remain metadata-only and do not load models.

- [ ] **Step 4: Add disconnect cancellation hooks**

The HTTP handler must mark the request cancelled when writing the response
raises `OSError`. The API/backend call receives a cancellation event or bounded
operation context. On cancellation:

- stop accepting new work;
- terminate the Worker after the configured timeout;
- clear the active Lease;
- expose only `request_cancelled` or `worker_unavailable`.

- [ ] **Step 5: Run focused tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest \
  test_runtime test_http -v
```

- [ ] **Step 6: Commit Lease behavior**

```sh
git add -- scripts/novel_audio_server/runtime.py \
  scripts/novel_audio_server/api.py \
  scripts/novel_audio_server/http.py \
  scripts/novel-audio-local/test_runtime.py \
  scripts/novel-audio-local/test_http.py
git commit -m "feat(audiobook): support dual mode runtime leases"
```

---

### Task 4: Implement Windows process-tree cleanup and Worker startup

**Files:**

- Create/modify: `scripts/novel-audio-local/process.py`
- Modify: `scripts/novel-audio-local/worker.py`
- Modify: `scripts/novel-audio-local/agent.py`
- Create/modify: `scripts/novel-audio-local/test_process.py`
- Create/modify: `scripts/novel-audio-local/test_worker.py`

- [ ] **Step 1: Write failing process cleanup tests**

Use fake process objects and a fake command runner to assert:

```python
def test_windows_tree_termination_uses_taskkill_with_tree_flag(self):
    process = FakeProcess(pid=4242, poll_result=None)
    commands = []
    terminate_process_tree(
        process,
        timeout=2,
        command_runner=lambda argv: commands.append(argv),
        platform_name="nt",
    )
    self.assertEqual(["taskkill", "/PID", "4242", "/T", "/F"], commands[0])
    self.assertEqual(1, process.wait_count)

def test_cleanup_waits_for_parent_and_children_to_exit(self):
    process = FakeProcess(pid=4242, poll_result=0)
    terminate_process_tree(
        process,
        timeout=2,
        command_runner=lambda argv: None,
        platform_name="nt",
    )
    self.assertTrue(process.wait_called)
    self.assertTrue(process_is_gone(4242, lambda pid: False))

def test_cleanup_is_idempotent(self):
    process = FakeProcess(pid=4242, poll_result=0)
    runner = RecordingRunner()
    terminate_process_tree(process, 2, runner, platform_name="nt")
    terminate_process_tree(process, 2, runner, platform_name="nt")
    self.assertEqual(1, runner.taskkill_count)

def test_worker_start_uses_configured_tts_python_not_agent_python(self):
    factory, runner = factory_with_python("D:/NovelAudioLocal/tts-venv/Scripts/python.exe")
    factory.start("profile-default")
    self.assertEqual(
        "D:/NovelAudioLocal/tts-venv/Scripts/python.exe",
        runner.commands[0][0],
    )

def test_worker_stdout_is_protocol_only(self):
    worker = worker_with_stdout_lines(
        '{"ready":true}\n',
        '{"debug":"must-not-be-written-to-stdout"}\n',
    )
    self.assertEqual({"ready": True}, worker.read_protocol_response())
    self.assertEqual([], worker.protocol_errors)
```

- [ ] **Step 2: Implement bounded process helpers**

Provide:

```python
def wait_for_exit(process, timeout):
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        return False
    return process.poll() is not None

def terminate_process_tree(process, timeout, command_runner, platform_name=os.name):
    if process.poll() is not None:
        return True
    pid = int(process.pid)
    if platform_name == "nt":
        command_runner(["taskkill", "/PID", str(pid), "/T", "/F"])
    else:
        os.killpg(os.getpgid(pid), signal.SIGTERM)
    if wait_for_exit(process, timeout):
        return True
    if platform_name != "nt":
        os.killpg(os.getpgid(pid), signal.SIGKILL)
    return wait_for_exit(process, timeout)

def process_is_gone(pid, process_probe):
    try:
        return not bool(process_probe(int(pid)))
    except (OSError, ProcessLookupError):
        return True
```

On Windows use a fixed executable invocation:

```text
taskkill /PID <validated numeric pid> /T /F
```

Do not build shell strings from configuration. On POSIX tests use process-group
behavior. Always verify the parent has exited and report a fixed failure code if
the tree remains.

- [ ] **Step 3: Make WorkerFactory profile-aware**

`SubprocessWorkerFactory.start(profile)` must:

- load the validated active Profile;
- launch `config.tts.python_executable`;
- run `worker.py --worker --config <path> --profile <profileId>`;
- pass `--fake-backend` only in explicit fake mode;
- use `stdin/stdout` pipes with UTF-8;
- send no secrets or model paths to normal stdout;
- use a new process group/job cleanup strategy appropriate to Windows.

- [ ] **Step 4: Add Worker readiness handshake**

Worker startup must return a machine-readable readiness response only after:

- registry/profile validation;
- llama-server health if text adapter is enabled;
- TTS runtime import and model load;
- capability checks;
- no unexpected stdout/stderr protocol pollution.

Startup failure returns a fixed error code and the parent clears the Lease.

- [ ] **Step 5: Run Worker tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest \
  test_process test_worker test_runtime -v
```

- [ ] **Step 6: Commit Worker lifecycle**

```sh
git add -- scripts/novel-audio-local/process.py \
  scripts/novel-audio-local/worker.py \
  scripts/novel-audio-local/agent.py \
  scripts/novel-audio-local/test_process.py \
  scripts/novel-audio-local/test_worker.py
git commit -m "feat(audiobook): harden local worker lifecycle"
```

---

### Task 5: Replace placeholder Qwen text adapter with real llama-server adapter

**Files:**

- Modify: `scripts/novel-audio-local/qwen_backend.py`
- Modify: `scripts/novel-audio-local/config.py`
- Modify: `scripts/novel_audio_server/errors.py`
- Create/modify: `scripts/novel-audio-local/test_backend.py`
- Create/modify: `scripts/novel-audio-local/test_worker.py`

- [ ] **Step 1: Write failing subprocess/HTTP-double tests**

Cover:

```python
def test_text_adapter_starts_configured_runner_and_waits_for_health(self):
    runner = FakeRunner()
    opener = FakeOpener(health_responses=[{"status": 200}])
    adapter = QwenTextAdapter(self.config(), runner=runner, opener=opener)
    adapter.start()
    self.assertEqual("127.0.0.1", runner.commands[0][runner.commands[0].index("--host") + 1])
    self.assertEqual(1, opener.health_requests)

def test_text_adapter_uses_profile_model_and_fixed_cuda_arguments(self):
    runner = FakeRunner()
    adapter = QwenTextAdapter(self.config(), runner=runner, profile=self.profile())
    adapter.start()
    command = runner.commands[0]
    self.assertIn(str(self.profile().text_asset.path), command)
    self.assertIn("CUDA0", command)
    self.assertIn(str(self.config().text.gpu_layers), command)

def test_text_adapter_uses_enable_thinking_false_and_json_response(self):
    requests = []
    adapter = QwenTextAdapter(
        self.config(),
        request=lambda payload: requests.append(payload) or self.valid_completion(),
    )
    adapter.analyze(self.analysis_request())
    self.assertFalse(requests[0]["enable_thinking"])
    self.assertEqual({"type": "json_object"}, requests[0]["response_format"])
    self.assertFalse(requests[0]["stream"])

def test_text_adapter_rejects_redirect_and_oversized_response(self):
    redirecting = QwenTextAdapter(
        self.config(),
        opener=FakeOpener(
            request_error=HTTPError(
                "http://127.0.0.1",
                302,
                "redirect",
                {},
                None,
            )
        ),
    )
    with self.assertRaises(RunnerUnavailableError) as redirect_error:
        redirecting._request({"messages": []})
    self.assertEqual("runner_unavailable", redirect_error.exception.code)
    oversized = QwenTextAdapter(
        self.config(),
        opener=FakeOpener(response_body=b"x" * (MAX_JSON + 1)),
    )
    with self.assertRaises(InvalidBackendResponseError):
        oversized._request({"messages": []})

def test_text_adapter_close_terminates_runner(self):
    process = FakeProcess()
    adapter = QwenTextAdapter(self.config(), runner=lambda *args, **kwargs: process)
    adapter.start()
    adapter.close()
    adapter.close()
    self.assertEqual(1, process.terminate_count)

def test_text_adapter_does_not_log_prompt_or_raw_response(self):
    logs = RecordingLogger()
    adapter = QwenTextAdapter(
        self.config(),
        logger=logs,
        request=lambda payload: self.valid_completion(),
    )
    adapter.analyze(self.analysis_request())
    rendered = "\n".join(logs.messages)
    self.assertNotIn("正文", rendered)
    self.assertNotIn("assignments", rendered)
```

The HTTP doubles must model `/health`, `/v1/chat/completions`, a redirect, an
oversized body and a malformed JSON completion. They must not open a network
socket.

- [ ] **Step 2: Implement fixed command construction**

Build an argument list, never a shell command string:

```python
[
    str(config.text.runner),
    "-m", str(profile.text_asset.path),
    "--host", "127.0.0.1",
    "--port", str(config.text.backend_port),
    "-ngl", str(config.text.gpu_layers),
    "-dev", "CUDA0",
    "-c", str(config.text.context_length),
    "--no-webui",
    "-a", profile.text_asset.asset_id,
    "-t", "8",
]
```

The actual flag set must be checked against the Windows b11320 `--help`
output recorded in the stage7A report. If a configured flag is unavailable,
return a fixed `runner_incompatible` error rather than silently changing
the device or falling back to CPU.

Extend `TextConfig` with `backend_port` (JSON `backendPort`, default `11435`,
integer `1..65535`, distinct from the public Agent port) and `start_timeout`
(JSON `startTimeout`, default `180`, finite seconds `1..600`). Add those keys to
strict config validation and test both defaults and invalid values. Worker
startup timeout must exceed the combined text and TTS load allowance.

Define the adapter errors in `scripts/novel_audio_server/errors.py`:

```python
class RunnerUnavailableError(BackendError):
    code = "runner_unavailable"


class RunnerIncompatibleError(BackendError):
    code = "runner_incompatible"
```

- [ ] **Step 3: Implement bounded health and OpenAI-compatible request**

Implement:

```python
def start(self):
    # spawn runner, poll GET /health, fail within bounded timeout
    self.process = self.runner(self._command(), stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + self.config.text.start_timeout
    while time.monotonic() < deadline:
        if self._health() is True:
            return
        time.sleep(0.05)
    self.close()
    raise RunnerUnavailableError()

def analyze(self, request):
    # POST /v1/chat/completions
    # enable_thinking=false
    # response_format=json_object
    # parse assistant content only
    # call parse_analysis_json and analysis_response
    payload = self._payload(request)
    response = self._request(payload)
    content = response["choices"][0]["message"]["content"]
    parsed = parse_analysis_json(content)
    return analysis_response(parsed, request)
```

Use no redirects, fixed localhost, bounded connect/read timeout and
`MAX_JSON` response limit. The runner must be closed in `QwenBackend.close()`.

- [ ] **Step 4: Run adapter tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" -m unittest \
  test_backend test_worker -v
```

- [ ] **Step 5: Commit text adapter**

```sh
git add -- scripts/novel-audio-local/qwen_backend.py \
  scripts/novel-audio-local/config.py \
  scripts/novel-audio-local/test_backend.py \
  scripts/novel-audio-local/test_worker.py
git commit -m "feat(audiobook): connect local qwen text runner"
```

---

### Task 6: Replace placeholder Qwen TTS adapter and produce Android audio

**Files:**

- Modify: `scripts/novel-audio-local/qwen_backend.py`
- Modify: `scripts/novel-audio-local/worker.py`
- Modify: `scripts/novel-audio-local/config.py`
- Create/modify: `scripts/novel-audio-local/test_backend.py`
- Create/modify: `scripts/novel-audio-local/test_worker.py`

- [ ] **Step 1: Write failing TTS adapter tests**

Cover:

```python
def test_voicedesign_builds_only_voice_design_operation(self):
    operation = self.adapter_for("local.voicedesign").build_operation(self.request())
    self.assertEqual("voice_design", operation["operation"])
    self.assertIn("instruct", operation)
    self.assertNotIn("refAudio", operation)
    self.assertNotIn("refText", operation)

def test_base_requires_in_root_reference_audio(self):
    adapter = self.adapter_for("local.base", reference_audio="../outside.wav")
    with self.assertRaises(BackendError) as caught:
        adapter.synthesize(self.request())
    self.assertEqual("missing_reference_audio", caught.exception.code)

def test_base_without_ref_text_uses_x_vector_only_mode(self):
    operation = self.adapter_for("local.base", reference_text="").build_operation(self.request())
    self.assertEqual("voice_clone", operation["operation"])
    self.assertTrue(operation["xVectorOnlyMode"])
    self.assertIsNone(operation["refText"])

def test_tts_normalizes_wav_and_calls_ffmpeg_with_fixed_arguments(self):
    runner = RecordingRunner()
    adapter = self.adapter_for("local.voicedesign", runner=runner)
    audio = adapter.synthesize(self.request())
    self.assertTrue(audio.startswith(b"OggS"))
    self.assertEqual(["-ac", "1", "-ar", "24000", "-c:a", "libopus", "-f", "ogg"],
                     runner.ffmpeg_codec_arguments)

def test_tts_rejects_empty_or_undecodable_audio(self):
    for waveform in (b"", b"not-a-wav"):
        adapter = self.adapter_for("local.voicedesign", waveform=waveform)
        with self.assertRaises(InvalidBackendResponseError):
            adapter.synthesize(self.request())

def test_tts_close_releases_model_and_is_idempotent(self):
    model = FakeTtsModel()
    adapter = self.adapter_for("local.voicedesign", model=model)
    adapter.close()
    adapter.close()
    self.assertEqual(1, model.close_count)
```

Use fake `Qwen3TTSModel`, fake waveform output, fake ffmpeg and ffprobe
processes. Do not import torch or qwen_tts on macOS tests.

- [ ] **Step 2: Implement lazy Windows TTS loading**

Only Worker-local code may import:

```python
import torch
from qwen_tts import Qwen3TTSModel
```

Load the model selected by the active Profile on `cuda:0`, using the proven
Windows stage7A constructor and method signatures:

- `generate_voice_design(text, instruct, language="Chinese")`;
- `generate_voice_clone(text, language="Chinese", ref_audio=reference_path,
  ref_text=reference_text_or_none, x_vector_only_mode=True)`.

The implementation must preserve the actual verified API shape from the
Windows smoke script. Do not introduce a second guessed API path.

Record the verified constructor and method keyword mapping in a small adapter
test fixture. The production adapter calls only that mapping; if the Windows
smoke fixture changes, update the fixture and the corresponding Windows
handoff evidence in the same commit.

- [ ] **Step 3: Implement safe Base/VoiceDesign dispatch**

Before loading or generating:

- VoiceDesign voice requires a VoiceDesign Profile;
- Base voice requires a Base Profile;
- Base reference audio must be resolved below the configured service root;
- reference audio must pass existence, read, decode and duration checks;
- absence returns `missing_reference_audio`;
- no `ref_text` means `x_vector_only_mode=True`;
- VoiceDesign never receives `ref_audio`;
- Base never receives `instruct`.

- [ ] **Step 4: Implement WAV and Ogg/Opus conversion**

The Worker must:

1. receive the waveform from Qwen;
2. write a same-directory temporary WAV;
3. validate the WAV with ffprobe;
4. call the configured Windows ffmpeg executable with fixed argument arrays:

```text
-i <validated temp wav>
-ac 1
-ar 24000
-c:a libopus
-f ogg
<temporary ogg>
```

5. validate the Ogg output with ffprobe;
6. read bounded bytes;
7. atomically publish the result to the parent IPC response;
8. delete only its own temporary files.

The HTTP response is `audio/ogg` and carries a profile string containing the
verified Profile identity and Voice Catalog identity.

- [ ] **Step 5: Run TTS tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest \
  test_backend test_worker -v
```

- [ ] **Step 6: Commit TTS adapter**

```sh
git add -- scripts/novel-audio-local/qwen_backend.py \
  scripts/novel-audio-local/worker.py \
  scripts/novel-audio-local/config.py \
  scripts/novel-audio-local/test_backend.py \
  scripts/novel-audio-local/test_worker.py
git commit -m "feat(audiobook): connect local qwen tts adapter"
```

---

### Task 7: Complete CLI checks, status files and Windows PowerShell scripts

**Files:**

- Create: `scripts/novel-audio-local/checks.py`
- Modify: `scripts/novel-audio-local/server.py`
- Modify: `scripts/novel-audio-local/agent.py`
- Create: `scripts/novel-audio-local/install.ps1`
- Create: `scripts/novel-audio-local/start-agent.ps1`
- Create: `scripts/novel-audio-local/stop-agent.ps1`
- Create: `scripts/novel-audio-local/check-models.ps1`
- Create/modify: `scripts/novel-audio-local/test_checks.py`
- Modify: `scripts/novel-audio-local/test_cli.py`

- [ ] **Step 1: Write failing CLI/check tests**

Cover:

```python
def test_check_returns_nonzero_when_text_hash_is_wrong(self):
    config_path = self.write_fixture_registry_with_wrong_text_hash()
    output = self.run_cli(["--check", "--config", str(config_path)])
    self.assertEqual(2, output.returncode)
    payload = json.loads(output.stdout)
    self.assertEqual("FAIL", payload["checks"]["sha256"]["status"])
    self.assertEqual("asset_hash_mismatch", payload["checks"]["sha256"]["code"])

def test_check_reports_cuda_as_not_checked_on_non_windows_fake_run(self):
    output = self.run_cli(["--check", "--config", str(self.valid_config_path())])
    payload = json.loads(output.stdout)
    self.assertEqual("NOT_CHECKED", payload["checks"]["cuda"]["status"])
    self.assertEqual("windows_only", payload["checks"]["cuda"]["code"])

def test_check_reports_missing_base_reference_audio(self):
    config_path = self.write_fixture_config(reference_audio="missing.wav")
    output = self.run_cli(["--check", "--config", str(config_path)])
    self.assertEqual(2, output.returncode)
    payload = json.loads(output.stdout)
    self.assertEqual("FAIL", payload["checks"]["baseReferenceAudio"]["status"])
    self.assertEqual("missing_reference_audio", payload["checks"]["baseReferenceAudio"]["code"])

def test_status_reads_pid_and_state_files(self):
    state_dir = self.make_state(
        pid=1234,
        state={"state": "ready", "profileId": "default", "activeLease": False},
    )
    output = self.run_cli(["--status", "--config", str(self.config_path_for(state_dir))])
    self.assertEqual(0, output.returncode)
    self.assertEqual(1234, json.loads(output.stdout)["pid"])
    self.assertEqual("ready", json.loads(output.stdout)["state"])

def test_stop_requests_agent_shutdown_and_waits_for_pid_exit(self):
    state_dir = self.make_state(pid=1234, state={"state": "ready"})
    output = self.run_cli(["--stop", "--config", str(self.config_path_for(state_dir))])
    self.assertEqual(0, output.returncode)
    self.assertTrue((state_dir / "agent.stop").exists())
    self.assertEqual("stopped", json.loads(output.stdout)["state"])

def test_init_does_not_overwrite_existing_config_or_token(self):
    config_path, token_path = self.write_existing_init_files()
    before = (config_path.read_bytes(), token_path.read_bytes())
    output = self.run_cli(["--init", "--config", str(config_path)])
    self.assertEqual(0, output.returncode)
    self.assertEqual(before, (config_path.read_bytes(), token_path.read_bytes()))
```

- [ ] **Step 2: Define stable check result schema**

CLI output must be JSON with:

```json
{
  "overall": "PASS",
  "checks": {
    "config": {"status": "PASS", "code": "ok"},
    "activeProfile": {"status": "PASS", "code": "ok"},
    "models": {"status": "PASS", "code": "ok"},
    "sha256": {"status": "PASS", "code": "ok"},
    "ttsPython": {"status": "PASS", "code": "ok"},
    "qwenTtsImport": {"status": "PASS", "code": "ok"},
    "cuda": {"status": "NOT_CHECKED", "code": "windows_only"},
    "llamaServer": {"status": "PASS", "code": "ok"},
    "ffmpeg": {"status": "PASS", "code": "ok"}
  }
}
```

Exit codes:

- `0`: every required check is `PASS`;
- `2`: configuration or asset `FAIL`;
- `3`: runtime `BLOCKED`;
- `4`: runtime `NOT_CHECKED` in a command requiring Windows verification.

Never print tokens, raw paths in HTTP, prompt text or model output.

Implement `CheckResult` and `CheckReport` as dataclasses in `checks.py`.
`CheckReport.exit_code(require_windows_runtime=False)` applies the mapping
above: configuration/asset `FAIL` wins over runtime `BLOCKED`, and
`NOT_CHECKED` becomes exit code `4` only when the caller explicitly requires
Windows verification. The serialized JSON keeps stable key names and excludes
diagnostic paths unless the caller is the local CLI diagnostic path.

- [ ] **Step 3: Implement PID/state/log conventions**

Use:

```text
state/agent.pid
state/agent.status.json
state/agent.stop
logs/agent.log
logs/worker-<lease-id>.log
```

State JSON contains only:

- process state;
- profile ID and identity;
- capability list;
- timestamps;
- active lease boolean;
- fixed error code;
- hardware status.

It must not contain model paths, reference audio path, token or prompt.

- [ ] **Step 4: Implement PowerShell scripts**

`install.ps1`:

- require Windows PowerShell 5.1+;
- create `config`, `state`, `logs`, `diagnostics`, `manifests`, `runtime`;
- preserve existing model directories;
- create config only when absent;
- create a restrictive local token without printing it;
- never download model weights.

`start-agent.ps1`:

- run `--check` first;
- refuse start on failed required checks;
- start only the lightweight Agent;
- write PID/status files.

`stop-agent.ps1`:

- request graceful stop;
- wait bounded time;
- terminate the process tree if needed;
- verify no Agent/Worker/llama-server remains.

`check-models.ps1`:

- call the Python `--check`;
- preserve fixed PASS/FAIL/BLOCKED messages;
- never expose secret values;
- never modify firewall rules.

Each script must use a parameterized `$ConfigPath` default, invoke the exact
configured Python executable, propagate the Python exit code with
`exit $LASTEXITCODE`, and write errors to stderr without printing token
contents. `start-agent.ps1` must not launch a Worker directly; it starts only
`server.py --serve`.

- [ ] **Step 5: Run CLI tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest \
  test_checks test_cli -v
```

- [ ] **Step 6: Commit CLI and scripts**

```sh
git add -- scripts/novel-audio-local/checks.py \
  scripts/novel-audio-local/server.py \
  scripts/novel-audio-local/agent.py \
  scripts/novel-audio-local/install.ps1 \
  scripts/novel-audio-local/start-agent.ps1 \
  scripts/novel-audio-local/stop-agent.ps1 \
  scripts/novel-audio-local/check-models.ps1 \
  scripts/novel-audio-local/test_checks.py \
  scripts/novel-audio-local/test_cli.py
git commit -m "feat(audiobook): add local service checks and scripts"
```

---

### Task 8: Run the complete macOS contract suite and fake smoke

**Files:**

- Modify: `scripts/novel-audio-local/README.md`
- Create/modify: `scripts/novel-audio-local/SMOKE_CHECKLIST.md`
- Modify: only service tests found failing in previous tasks

- [ ] **Step 1: Run all local service tests**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-local -p 'test_*.py' -v
```

Expected:

- registry and hash tests pass;
- profile capability tests pass;
- dual-mode Lease tests pass;
- Worker IPC tests pass;
- fake backend tests pass;
- CLI/check tests pass;
- no `ResourceWarning`.

The run is acceptable only when unittest exits `0`; record the exact test count,
the command, and any skipped Windows-only checks in
`scripts/novel-audio-local/SMOKE_CHECKLIST.md`.

- [ ] **Step 2: Run the existing bridge suite**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-bridge "$PYTHON_BIN" \
  -W error::ResourceWarning -m unittest discover \
  -s scripts/novel-audio-bridge -p 'test_*.py' -v
```

All existing bridge tests must remain green.

- [ ] **Step 3: Run fake three-role smoke**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
PYTHONPATH=.:scripts/novel-audio-local:scripts/novel-audio-bridge \
  "$PYTHON_BIN" scripts/novel-audio-local/server.py \
  --config scripts/novel-audio-local/local-model.example.json \
  --smoke --fake-backend
```

Verify:

- one explicit Lease;
- three deterministic voice selections;
- three nonempty fake Ogg outputs;
- release returns runtime to idle;
- no token, path, prompt or full source text in output;
- Worker close is observed.

The smoke command writes three fixtures under the system temporary directory
using the `novel-audio-smoke-*` prefix. The isolated package test supplies a
temporary parent, verifies all three fixtures, and cleans that parent on exit.
The command must not write model files, token files or audio artifacts into the
repository.

- [ ] **Step 4: Run repository gates**

```sh
REPO_ROOT="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
PYTHON_BIN="$REPO_ROOT/ai_tests/venv/bin/python"
"$PYTHON_BIN" "$REPO_ROOT/ai_tests/scripts/audit_code_change_has_test.py"
"$PYTHON_BIN" "$REPO_ROOT/ai_tests/scripts/run_gates.py" --stage commit
git diff --check
git status --short
```

If the audit includes unrelated pre-existing files, do not stage or alter
them; document the conflict and run the gate against the intended staged
service paths where the project tooling supports it.

- [ ] **Step 5: Commit fake-suite evidence**

```sh
git add -- scripts/novel-audio-local/README.md \
  scripts/novel-audio-local/SMOKE_CHECKLIST.md
git commit -m "docs(audiobook): record local service fake validation"
```

---

### Task 9: Prepare the Windows 7B real-service smoke

**Files:**

- Modify: `scripts/novel-audio-local/README.md`
- Modify: `scripts/novel-audio-local/SMOKE_CHECKLIST.md`
- Create: `scripts/novel-audio-local/WINDOWS_HANDOFF.md`

- [ ] **Step 1: Document the real Windows config mapping**

The handoff must map the user binding files without copying models:

```text
D:/模型/05_language/qwen3.5/Qwen3.5-4B-GGUF/Qwen3.5-4B-Q4_K_M.gguf
D:/模型/05_language/qwen3.5/Qwen3.5-9B-GGUF/Qwen3.5-9B-Q4_K_M.gguf
D:/模型/04_audio/qwen3-tts/Qwen3-TTS-12Hz-1.7B-Base
D:/模型/04_audio/qwen3-tts/Qwen3-TTS-12Hz-1.7B-VoiceDesign
D:/NovelAudioLocal/state/reference-audio/reference.wav
```

The document must state that these absolute paths stay in local Windows
configuration and CLI diagnostics only.

- [ ] **Step 2: Document the 7B command matrix**

The Windows Agent must run:

1. `--check`;
2. start Agent with no model GPU allocation;
3. `/v1/health`;
4. explicit acquire;
5. chapter analyze under the same Lease;
6. VoiceDesign synthesize under the same Lease;
7. release and process/VRAM verification;
8. second acquire and generation;
9. fallback 4B + Base with `reference.wav`;
10. default/fallback/default switch;
11. no-Lease legacy endpoint auto lifecycle;
12. second concurrent acquire returns 429;
13. Worker failure recovery;
14. `--status` and `--stop`;
15. final model hash, port, firewall and process checks.

- [ ] **Step 3: Commit handoff documentation**

```sh
git add -- scripts/novel-audio-local/README.md \
  scripts/novel-audio-local/SMOKE_CHECKLIST.md \
  scripts/novel-audio-local/WINDOWS_HANDOFF.md
git commit -m "docs(audiobook): prepare windows service smoke"
```

---

### Task 10: Windows 7B execution and evidence reconciliation

**Files:**

- Windows-only generated evidence:
  `<config-parent>\diagnostics\stage7b-http-*\`
- Modify after evidence review:
  `docs/AI_AUDIOBOOK_PROGRESS.md`
  `scripts/novel-audio-local/WINDOWS_HANDOFF.md`

- [ ] **Step 1: Send the Windows Agent the 7B checklist**

The Agent must not modify Git. It must use the configured Windows-native
Python, llama-server, ffmpeg and ffprobe, and must record each command,
exit code, key output, audio metadata, process PID and VRAM before/after/peak.

Use the source-matched command blocks in
`scripts/novel-audio-local/WINDOWS_HANDOFF.md` and
`scripts/novel-audio-local/SMOKE_CHECKLIST.md`. Verify the ZIP and manifest before
executing any bundled script. Discover the existing config without moving it;
`config.py` resolves relative resources against the config file's parent.
`Get-ConfiguredPython` resolves the configured native Python by that same rule.

The remaining requests must use the same token header, never put the token in
a URL or JSON body, and save only redacted response metadata in the report.
Executable/model/reference paths are local execution inputs and must not be
copied into Git evidence.

- [ ] **Step 2: Verify the Agent starts idle**

Required evidence:

- Agent process exists;
- `/v1/health` responds;
- active model process count is zero;
- GPU used memory is at the recorded idle baseline;
- 8787 is listening only on the configured host;
- no firewall rule was added automatically.

- [ ] **Step 3: Verify explicit Lease**

Required evidence:

- `POST /v1/runtime/acquire` returns opaque Lease and sanitized profile info;
- no response contains `D:\`, model path, reference path, token or prompt;
- same Lease completes analyze and VoiceDesign synthesize;
- `X-TTS-Profile` is present and stable within the batch.

- [ ] **Step 4: Verify release and reload**

Required evidence:

- release returns idle;
- Worker, llama-server and TTS processes exit;
- port 11435 is released;
- GPU memory returns to idle baseline;
- second acquire starts fresh and succeeds.

- [ ] **Step 5: Verify fallback and profile switching**

Run:

```text
default  = 9B + VoiceDesign
fallback = 4B + Base + reference.wav
sequence = default → release → fallback → release → default → release
```

Confirm:

- capability filtering;
- distinct Profile identity;
- Base missing-reference error using a separate diagnostic config pointing to a
  nonexistent file; preserve the user's actual `reference.wav`;
- no silent VoiceDesign fallback;
- no simultaneous text/TTS model pair from different Profiles.

- [ ] **Step 6: Verify compatibility and failure recovery**

Confirm:

- no-Lease analyze, preview and synthesize each auto acquire and release;
- health, voices and voice matching remain model-free metadata requests;
- second concurrent acquire returns 429;
- malformed/oversized JSON is rejected before model invocation;
- Worker failure returns a fixed error;
- Agent remains available for a fresh acquire;
- `--status` reflects actual state;
- `--stop` exits the Agent and Worker tree.

- [ ] **Step 7: Reconcile evidence**

Only after reading the complete Windows report:

- mark each item `PASS`, `FAIL`, `BLOCKED` or `NOT_TESTED`;
- update progress/handoff with actual commands and results;
- do not claim Android or service delivery if any service item remains unverified.

- [ ] **Step 8: Commit only evidence documents**

```sh
git add -- docs/AI_AUDIOBOOK_PROGRESS.md \
  scripts/novel-audio-local/WINDOWS_HANDOFF.md \
  scripts/novel-audio-local/SMOKE_CHECKLIST.md
git commit -m "docs(audiobook): record windows local service validation"
```

Do not commit Windows logs, model manifests containing local paths, audio
outputs, tokens or private reference material.

---

## Completion criteria

Stage 7B is complete only when:

- registry checks actual file/directory hashes;
- Profile identity includes model, catalog, reference audio and encoding inputs;
- Voice Catalog is capability-safe;
- `--check` returns meaningful status and exit codes;
- Agent remains lightweight and model-free while idle;
- Worker loads the verified Windows Profile and returns readiness;
- real llama-server analysis works through the service;
- real VoiceDesign and Base synthesis works through the service;
- output is valid Android-compatible Ogg/Opus;
- both explicit and implicit Lease modes work;
- Worker process tree, port and VRAM cleanup have Windows evidence;
- `--status` and `--stop` reflect real state;
- existing Bailian bridge tests remain green;
- no absolute path or secret is exposed through HTTP;
- Android remains unchanged during stage 7B;
- Windows report records command, exit code, output, audio metadata and VRAM;
- every changed production file has paired tests and gates pass.

After these criteria pass, stop and open a separate implementation plan for
Android integration on `feat/ai-audiobook`. Do not modify Android as part of
this plan.
