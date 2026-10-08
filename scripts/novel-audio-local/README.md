# NovelAudio Local Model Service

This directory contains the computer-side NovelAudioServer v1 service and the
Windows-native operator boundary. Stage 7B is the gate before Android
integration. The service binds to existing local assets; it does not download,
move, copy, rename, or modify model weights.

## Runtime boundary and safety

The resident Agent owns:

- loopback HTTP, Bearer-token authentication, and bounded request parsing;
- model-registry/profile validation and path-free runtime identity;
- voice-catalog projection;
- one active Runtime Lease service-wide;
- Worker start, IPC, cancellation, and cleanup.

The Worker owns complete model initialization, CUDA workloads,
`llama-server.exe`, and the TTS/ffmpeg generation pipeline. `--check` imports
`runtime_probe.py` and may launch bounded read-only probe subprocesses to inspect
the interpreter, Torch/CUDA, llama-server, and codecs; this is check-time
inspection, not full model loading or audio generation. HTTP responses are
allowlisted projections. They must not contain tokens, keys, model paths,
reference-audio paths, prompts, chapter text, commands, licenses, or raw
backend output.

The default bind is `127.0.0.1`. The token is read from the private
`tokenFile` under the configured service root and is accepted only in the
`Authorization` header. Never print or paste its value.

## Windows service root and config discovery

The existing Windows setup uses this service-root location:

```text
D:\NovelAudioLocal
```

Preserve that root and every existing file beneath it. Do not bulk-copy example
configuration, registry data, voice catalogs, runtime files, or model assets
over it.

Do not assume whether the existing config is at
`D:\NovelAudioLocal\local-model.json` or
`D:\NovelAudioLocal\config\local-model.json`. Discover the one existing config
file from the previous setup before invoking any entrypoint:

```powershell
$ConfigRoot = 'D:\NovelAudioLocal'
$ConfigCandidates = @(
    (Join-Path $ConfigRoot 'local-model.json')
    (Join-Path $ConfigRoot 'config\local-model.json')
)
$ConfigCandidates = @(
    $ConfigCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
)
if ($ConfigCandidates.Count -ne 1) {
    throw 'BLOCKED: expected exactly one existing local-model.json'
}
$Config = [System.IO.Path]::GetFullPath($ConfigCandidates[0])
$ConfigParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $Config))
```

`config.py` sets `LocalModelConfig.root` to the config file's parent and
resolves every relative `tokenFile`, model, runtime, ffmpeg/ffprobe, Python,
registry, and voice-catalog path against that parent. The parent may be
`D:\NovelAudioLocal` or `D:\NovelAudioLocal\config`; do not reject the latter
merely because its directory name is `config`. Preserve the discovered config
path and verify that the actual resolved settings still point to the
established service resources under the existing service root. If resolution
is ambiguous or a required resource points elsewhere, stop as `BLOCKED` with a
sanitized config-root mismatch; do not move the config/assets or reinterpret
relative resources.

The existing Base reference is the service-root-relative
`state/reference-audio/reference.wav`. It has no `refText`, so Base cloning
must use `x_vector_only_mode=true`. If a catalog entry contains an optional
transcript field, the implemented field name is `refText`, not
`referenceText`.

## Approved profiles and registry merge

The approved profile IDs are:

- `qwen35-9b-voicedesign`: text chapter analysis plus VoiceDesign;
- `qwen35-4b-base`: text chapter analysis plus Base voice cloning.

These IDs do not authorize replacing existing assets. Bind the actual existing
text/TTS assets and their verified SHA-256 manifests. For a text file, the
registry hash is the actual file hash. For a TTS directory, it is the actual
sorted directory-manifest hash. A blank or stale hash is a check failure; never
silently select another asset.

If the existing registry schema differs from the example, merge only fields
implemented by `model_registry.py`:

- model asset: `assetId`, `type`, `family`, `format`, `path`, `sha256`,
  `requiredVramGb`, `capabilities`, `adapter`, `source`, `license`, `enabled`;
- runtime profile: `profileId`, `textModel`, `ttsModel`, `minVramGb`,
  `maxConcurrency`, `capabilities`;
- registry: `version`, `models`, `profiles`, `activeProfile`.

Keep `maxConcurrency=1`, with one active Lease for the entire service rather
than one Lease per profile. `config.activeProfile` wins over the registry
`activeProfile` when selecting the effective profile. To switch profiles,
stop the Agent, release or verify no active Lease, wait for the Worker to exit,
then modify the effective `activeProfile` field in the discovered config and
rerun check/start/complete smoke. Changing only the registry default is
insufficient.

## Lifecycle command set

Run these commands from native Windows PowerShell 5.1. This is the source
entrypoint contract; native Windows execution and audio listening evidence are
still required before signoff.

```powershell
$SourceRoot = '<absolute extracted source root>'
$Local = Join-Path $SourceRoot 'scripts\novel-audio-local'

& (Join-Path $Local 'install.ps1') -ConfigPath $Config
& (Join-Path $Local 'check-models.ps1') -ConfigPath $Config
& (Join-Path $Local 'start-agent.ps1') `
    -ConfigPath $Config -StartupTimeoutSeconds 900

. (Join-Path $Local 'operator-common.ps1')
$Context = Get-OperatorContext -ConfigPath $Config
$Python = Get-ConfiguredPython -Context $Context
& $Python $Context.ScriptPath --status --config $Context.ConfigPath
& $Python $Context.ScriptPath --smoke --config $Context.ConfigPath

& (Join-Path $Local 'stop-agent.ps1') `
    -ConfigPath $Config -TimeoutSeconds 60
& $Python $Context.ScriptPath --status --config $Context.ConfigPath
```

The current PowerShell parameters are:

- `install.ps1 -ConfigPath`;
- `check-models.ps1 -ConfigPath`;
- `start-agent.ps1 -ConfigPath -StartupTimeoutSeconds`;
- `stop-agent.ps1 -ConfigPath -TimeoutSeconds`.

`--check` is read-only with respect to model assets. It validates configuration,
registry/profile bindings, hashes, Base reference audio, CPython/Torch/CUDA,
llama-server flags/device visibility, and Ogg/Opus tools. Its bounded
`runtime_probe.py` subprocess inspection may touch runtime-related imports, but
it does not load full models, start HTTP, or generate audio.

`--smoke` targets an already running real Agent. It is not the fake backend. It
exercises the nine HTTP endpoints, explicit and request-scoped automatic
leases, the second-acquire `429`, identity parity, native ffprobe validation,
and two explicit lease rounds. Native PowerShell tests are not claimed here;
the Windows handoff must record them as `NOT_TESTED` until actually executed.

## HTTP contract

| Method | Route | Lease behavior |
|---|---|---|
| GET | `/v1/health` | authenticated; no Worker lease |
| GET | `/v1/runtime/status` | authenticated; path-free runtime metadata |
| GET | `/v1/voices` | authenticated; active-profile capability projection |
| POST | `/v1/voices/match` | authenticated; active-profile voice IDs |
| POST | `/v1/runtime/acquire` | explicit service-wide Lease |
| POST | `/v1/runtime/release` | releases `X-NovelAudio-Lease` |
| POST | `/v1/chapter/analyze` | explicit or request-scoped automatic Lease |
| POST | `/v1/voices/preview` | explicit or request-scoped automatic Lease |
| POST | `/v1/tts/synthesize` | explicit or request-scoped automatic Lease |

An explicit lifecycle is:

```text
acquire -> analyze/preview/synthesize -> release
```

The caller sends `X-NovelAudio-Lease` on requests that reuse an explicit Lease.
Without that header, each generation request performs
`acquire -> one request -> finally release`. A second active acquire must return
HTTP `429`. `runtimeProfileInfo.identity`, the Worker identity, and
`X-TTS-Profile` must agree.

## Audio and diagnostics

The Worker validates mono PCM input at 24,000 Hz before native encoding. The
encoded Ogg/Opus result must be checked with the configured native `ffprobe.exe`
resolved relative to the config file's parent:

- container begins with `OggS`;
- codec is `opus`;
- channels are exactly `1`;
- the source/input sample rate is `24000`;
- Opus metadata may report `48000` after encoding and must be accepted;
- duration is finite, greater than zero, and no greater than 180 seconds.

`smoke_http.py` creates unique diagnostics directories with the
`stage7b-http-*` prefix. Its fixed artifact names are:

```text
automatic-preview.ogg
automatic-synthesize.ogg
round1-preview.ogg
round1-synthesize-1.ogg
round1-synthesize-2.ogg
round1-synthesize-3.ogg
round2-preview.ogg
round2-synthesize-1.ogg
round2-synthesize-2.ogg
round2-synthesize-3.ogg
```

Record only allowlisted ffprobe fields and `audible=PASS|FAIL` after an actual
human listening check. Do not claim listening evidence from file creation.

## macOS contract gate

From the repository root, use the project Python environment:

```bash
/Users/zzz/baidu/personal-code/reading/ai_tests/venv/bin/python -m unittest discover \
  -s scripts/novel-audio-local \
  -p 'test_*.py'
```

These tests cover contract behavior such as registry/hash/profile identity,
capability filtering, reference-audio rules, fake Worker/IPC, leases, HTTP
framing/redaction, process cleanup, CLI behavior, and bundle manifest logic.
They do not establish Windows CUDA, Qwen-TTS, llama-server, native ffmpeg,
native PowerShell, or human-listening evidence. No macOS full-suite result is
pre-filled by this document.

## Source bundle contract

`package_service.py` accepts `--root` and `--output`, writes an exclusive ZIP,
and reports `{"state":"created"}` only after the output is created. The fixed
handoff allowlist includes the service source plus:

- `scripts/novel-audio-local/README.md`;
- `scripts/novel-audio-local/SMOKE_CHECKLIST.md`;
- `scripts/novel-audio-local/WINDOWS_HANDOFF.md`;
- `SERVICE_MANIFEST.json`.

The manifest contains `sourceBranch`, `sourceHead`, `snapshotKind` set to
`working-tree`, and a SHA-256 for every allowlisted source file. Verify the
supplied ZIP SHA-256 before extraction, then verify every manifest hash and
safe relative path. `sourceHead` identifies repository HEAD, but the bytes are
read from the working tree; this is not an immutable commit archive.

## Non-goals

This stage does not change Android production behavior, merge the VoiceStudio
line, download or alter model weights, expose a LAN endpoint by default, or
publish an Android package. Every required Windows stage must have actual
`PASS` evidence before Android integration begins.
