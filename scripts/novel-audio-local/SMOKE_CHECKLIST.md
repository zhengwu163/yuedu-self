# NovelAudio Stage 7B Windows Smoke Checklist

Use only Windows 11 native PowerShell 5.1 and native CPython 3.12. Do not use
WSL. Stop at the first `FAIL` or `BLOCKED` stage. Record fixed error codes and
sanitized evidence only; never record tokens, keys, model paths,
reference-audio paths, prompts, chapter text, commands containing secrets, raw
LLM/backend responses, or diagnostic paths.

This checklist does not authorize implementation changes. Do not download,
move, copy, rename, re-hash by replacing manifests, or modify model weights.
Do not run Git or broad cleanup commands. Do not begin Android integration
until every required 7B gate has actual evidence.

## 0. Discover the existing setup

The established Windows service root is `D:\NovelAudioLocal`. Preserve it and
its current files. Discover the existing config rather than assuming its
location:

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

`config.py` resolves all relative resources against `$ConfigParent`, because
the config file's parent is `LocalModelConfig.root`. `$ConfigParent` may be
`D:\NovelAudioLocal` or `D:\NovelAudioLocal\config`; do not reject the
`config` parent merely because its directory name differs. Preserve the
discovered path and verify that the actual token, model, runtime, executable,
registry, and catalog settings still resolve to the established resources
under the existing service root. If resolution is ambiguous or a required
resource points elsewhere, stop as `BLOCKED` with a sanitized config-root
mismatch; do not move files or reinterpret resource paths.

```powershell
$SourceRoot = '<new absolute extraction root; include spaces>'
$Local = Join-Path $SourceRoot 'scripts\novel-audio-local'
$Server = Join-Path $Local 'server.py'
$Bundle = '<absolute ZIP path supplied by the Mac agent>'
$BundleSha256Expected = '<supplied ZIP SHA-256>'
```

## 1. Verify the supplied bundle and manifest

Verify the supplied ZIP before extraction. Do not print the path or hash:

```powershell
$BundleSha256Actual = (
    Get-FileHash -LiteralPath $Bundle -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($BundleSha256Actual -cne $BundleSha256Expected.ToLowerInvariant()) {
    throw 'FAIL: bundle_sha256_mismatch'
}

$SourceRootFullPath = [System.IO.Path]::GetFullPath($SourceRoot)
if (Test-Path -LiteralPath $SourceRootFullPath) {
    throw 'BLOCKED: extraction_root_exists'
}
$Ancestor = [System.IO.DirectoryInfo]$SourceRootFullPath
while ($null -ne $Ancestor) {
    if (Test-Path -LiteralPath $Ancestor.FullName) {
        $AncestorItem = Get-Item -LiteralPath $Ancestor.FullName
        if (($AncestorItem.Attributes -band `
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'BLOCKED: extraction_reparse_ancestor'
        }
    }
    $Ancestor = $Ancestor.Parent
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [System.IO.Compression.ZipFile]::OpenRead($Bundle)
try {
    $SeenZipNames = New-Object `
        'System.Collections.Generic.HashSet[string]' `
        ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($ZipEntry in @($Archive.Entries)) {
        $ZipName = ([string]$ZipEntry.FullName).Replace('\', '/')
        if (
            [string]::IsNullOrWhiteSpace($ZipName) -or
            $ZipName.EndsWith('/') -or
            [System.IO.Path]::IsPathRooted($ZipName) -or
            $ZipName -match '(^|/)\.\.(/|$)' -or
            $ZipName -match ':' -or
            $ZipName -match '[\x00-\x1f]' -or
            -not $SeenZipNames.Add($ZipName)
        ) {
            throw 'FAIL: unsafe_or_duplicate_zip_entry'
        }
    }
} finally {
    $Archive.Dispose()
}

try {
    Expand-Archive -LiteralPath $Bundle -DestinationPath $SourceRootFullPath `
        -ErrorAction Stop
} catch {
    throw 'FAIL: bundle_extract_failed'
}

$ManifestPath = Join-Path $SourceRootFullPath 'SERVICE_MANIFEST.json'
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw 'FAIL: manifest_missing_after_extract'
}
$Manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if ([string]$Manifest.sourceBranch -cne 'feat/local-model-service') {
    throw 'FAIL: manifest_source_branch'
}
if ([string]$Manifest.sourceHead -notmatch '^[0-9a-f]{40}$') {
    throw 'FAIL: manifest_source_head'
}
if ([string]$Manifest.snapshotKind -cne 'working-tree') {
    throw 'FAIL: manifest_snapshot_kind'
}

$Entries = @($Manifest.sha256.PSObject.Properties)
if ($Entries.Count -eq 0) {
    throw 'FAIL: manifest_empty'
}
$SeenManifestNames = New-Object `
    'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
$RootFull = $SourceRootFullPath.TrimEnd('\') + '\'
foreach ($Entry in $Entries) {
    $Name = [string]$Entry.Name
    $Digest = [string]$Entry.Value
    if (
        [string]::IsNullOrWhiteSpace($Name) -or
        [System.IO.Path]::IsPathRooted($Name) -or
        $Name -match '(^|[\\/])\.\.([\\/]|$)' -or
        $Name -match '^[\\/]' -or
        $Name -match ':' -or
        $Name -match '[\x00-\x1f]' -or
        $Digest -notmatch '^[0-9a-fA-F]{64}$' -or
        $Name -ieq 'SERVICE_MANIFEST.json' -or
        -not $SeenManifestNames.Add(($Name -replace '\\', '/'))
    ) {
        throw 'FAIL: manifest_unsafe_entry'
    }
    $File = [System.IO.Path]::GetFullPath(
        (Join-Path $SourceRootFullPath ($Name -replace '/', '\'))
    )
    if (-not $File.StartsWith($RootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'FAIL: manifest_path_escape'
    }
    if (-not (Test-Path -LiteralPath $File -PathType Leaf)) {
        throw 'FAIL: manifest_file_missing'
    }
    $Actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Actual -cne $Digest.ToLowerInvariant()) {
        throw 'FAIL: manifest_hash_mismatch'
    }
}
$ExpectedArchiveFiles = New-Object `
    'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($Entry in $Entries) {
    [void]$ExpectedArchiveFiles.Add(([string]$Entry.Name).Replace('\', '/'))
}
[void]$ExpectedArchiveFiles.Add('SERVICE_MANIFEST.json')
$RootPrefix = $SourceRootFullPath.TrimEnd('\')
$ActualArchiveFiles = New-Object `
    'System.Collections.Generic.HashSet[string]' `
    ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($FileInfo in @(Get-ChildItem -LiteralPath $SourceRootFullPath `
    -File -Recurse)) {
    [void]$ActualArchiveFiles.Add(
        $FileInfo.FullName.Substring($RootPrefix.Length + 1).Replace('\', '/')
    )
}
if (-not $ExpectedArchiveFiles.SetEquals($ActualArchiveFiles)) {
    throw 'FAIL: archive_file_set_mismatch'
}
foreach ($Required in @(
    'scripts/novel-audio-local/README.md',
    'scripts/novel-audio-local/SMOKE_CHECKLIST.md',
    'scripts/novel-audio-local/WINDOWS_HANDOFF.md',
    'scripts/novel_audio_server/__init__.py'
)) {
    if (@($Entries | Where-Object { $_.Name -ieq $Required }).Count -ne 1) {
        throw 'FAIL: required_bundle_file_missing'
    }
}
'PASS: bundle_manifest'
```

The manifest is a current working-tree snapshot. `sourceHead` is not proof of
an immutable commit snapshot. The handoff allowlist includes this checklist and
`WINDOWS_HANDOFF.md`; do not accept a bundle that omits either document.

## 2. Merge the existing config and registry

Do not bulk-copy templates over the service root. Make only reviewed,
minimal merges:

- preserve existing token, executable paths, model paths, voice catalog,
  runtime directories, and unrelated accepted fields;
- bind actual existing model assets and their verified SHA-256 manifests;
- keep `maxConcurrency=1` and the minimum VRAM requirement at or above 24 GB;
- use only `qwen35-9b-voicedesign` and `qwen35-4b-base`;
- keep the Base reference asset and omit its `referenceText`;
- use the catalog key `referenceText` when a transcript is present; the
  internal Qwen operation key is `refText`.

If the current schema cannot be merged without replacing unknown fields, record
`BLOCKED` and stop. A blank/stale hash, missing reference audio, or capability
mismatch is a failure. Never silently select another model.

## 3. Native Windows operator contract

Current handoff status: `NOT_TESTED`. Do not claim a native PowerShell result
until the source-matched command has actually run.

Do not dot-source `operator-common.ps1` or execute any operator script before
the ZIP and manifest checks above have passed. The operator scripts must come
from the verified source extraction.

When Windows execution is performed, use:

```powershell
& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass `
    -File (Join-Path $Local 'test_operator_windows.ps1')
```

The command must cover exact `server.py --serve --config <path>` ownership,
paths containing spaces, PID creation identity, state-owner checks, concurrent
operator-lock rejection, reparse-point rejection, and owned descendant
termination. Record only its sanitized status and exit code. A missing or
unverified native test remains `NOT_TESTED`.

## 4. Read-only runtime check

```powershell
& (Join-Path $Local 'check-models.ps1') -ConfigPath $Config
$CheckCode = $LASTEXITCODE
if ($CheckCode -ne 0) { throw 'FAIL: model_check_failed' }
```

Require a complete check for the selected profile and Windows runtime probes.
`checks.py` may import `runtime_probe.py` and launch bounded read-only
subprocesses for interpreter, Torch/CUDA, llama-server, and codec inspection.
This does not load full models, start HTTP, or generate audio. Record fixed
check names/status/codes only.

## 5. Start, status, listener, and repeatability

```powershell
& (Join-Path $Local 'start-agent.ps1') `
    -ConfigPath $Config -StartupTimeoutSeconds 900
if ($LASTEXITCODE -ne 0) { throw 'FAIL: agent_start_failed' }

. (Join-Path $Local 'operator-common.ps1')
$Context = Get-OperatorContext -ConfigPath $Config
$Python = Get-ConfiguredPython -Context $Context
& $Python $Context.ScriptPath --status --config $Context.ConfigPath
if ($LASTEXITCODE -ne 0) { throw 'FAIL: status_failed' }

$ConfigJson = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
$Port = [int]$ConfigJson.port
$Listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
    -ErrorAction SilentlyContinue)
if ($Listeners.Count -eq 0) { throw 'FAIL: loopback_listener_missing' }
'PASS: start_status_listener'
```

Repeat start/stop three times. A reused Agent passes only when ownership,
config hash, listener, and authenticated health all match:

```powershell
foreach ($Round in 1..3) {
    & (Join-Path $Local 'start-agent.ps1') `
        -ConfigPath $Config -StartupTimeoutSeconds 900
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: repeated_start' }
    & (Join-Path $Local 'stop-agent.ps1') `
        -ConfigPath $Config -TimeoutSeconds 60
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: repeated_stop' }
}
'PASS: repeated_start_stop'
```

## 6. Real Stage 7B HTTP smoke

Start the Agent and run the real service smoke:

```powershell
& (Join-Path $Local 'start-agent.ps1') `
    -ConfigPath $Config -StartupTimeoutSeconds 900
if ($LASTEXITCODE -ne 0) { throw 'FAIL: smoke_start' }

& $Python $Context.ScriptPath --smoke --config $Context.ConfigPath
$SmokeCode = $LASTEXITCODE
if ($SmokeCode -ne 0) { throw 'FAIL: http_smoke_failed' }
```

The smoke must cover this exact nine-route set:

| Route | Required evidence |
|---|---|
| `GET /v1/health` | authenticated `200`, API version `1`, director/TTS ready |
| `GET /v1/runtime/status` | path-free metadata and identity parity |
| `GET /v1/voices` | active-profile capability filtering |
| `POST /v1/voices/match` | candidates are active-profile voice IDs |
| `POST /v1/runtime/acquire` | opaque Lease and runtime identity |
| `POST /v1/runtime/release` | explicit Lease released and state returns idle |
| `POST /v1/chapter/analyze` | valid response under explicit or automatic Lease |
| `POST /v1/voices/preview` | actual Ogg/Opus and matching `X-TTS-Profile` |
| `POST /v1/tts/synthesize` | actual Ogg/Opus and matching `X-TTS-Profile` |

The service has one active Lease globally. The smoke must prove:

1. explicit `acquire -> analyze/preview/synthesize -> release`;
2. a second acquire while the first is active returns exactly HTTP `429`;
3. no-header analyze, preview, and synthesize each acquire one request-scoped
   Lease and release it in `finally`, with an idle status check afterward;
4. `runtimeProfileInfo.identity`, Worker identity, and `X-TTS-Profile` match.

The smoke prints fixed JSON Lines only. Record step, status, fixed error code,
and allowlisted audio fields; never record request bodies or paths.

## 7. Diagnostics, ffprobe, and human listening

`smoke_http.py` creates the newest diagnostics directory with prefix
`stage7b-http-*`. The only expected audio filenames are:

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

Select an artifact under the newest directory without printing its path.
Resolve the configured native `ffprobe.exe` relative to the config file's
parent when the config value is relative:

```powershell
$ConfigJson = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
$ConfigParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $Config))
$FfprobeSetting = [string]$ConfigJson.tts.ffprobe
if ([System.IO.Path]::IsPathRooted($FfprobeSetting)) {
    $Ffprobe = [System.IO.Path]::GetFullPath($FfprobeSetting)
} else {
    $Ffprobe = [System.IO.Path]::GetFullPath(
        (Join-Path $ConfigParent $FfprobeSetting)
    )
}

$DiagnosticDir = Get-ChildItem `
    -LiteralPath (Join-Path $ConfigParent 'diagnostics') `
    -Directory -Filter 'stage7b-http-*' |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
$Audio = Get-ChildItem -LiteralPath $DiagnosticDir.FullName `
    -Filter 'automatic-synthesize.ogg' -File | Select-Object -First 1
if ($null -eq $Audio) { throw 'FAIL: synthesized_artifact_missing' }

& $Ffprobe -v quiet -protocol_whitelist file -f ogg `
    -select_streams a:0 `
    -show_entries stream=codec_name,channels,sample_rate:format=duration `
    -of json $Audio.FullName
if ($LASTEXITCODE -ne 0) { throw 'FAIL: native_ffprobe_failed' }
```

Acceptance is exact: source/input is mono 24,000 Hz; encoded output is Opus,
and native ffprobe may report Opus metadata at 48,000 Hz. The validated
listener channel count is `1`, duration is finite and within the service limit,
and the container begins with `OggS`. Human listening is `NOT_TESTED` until the
user actually listens and reports only `audible=PASS` or `audible=FAIL`.

## 8. Complete profile switch sequence

Profile switching is not a default-only check. `config.activeProfile` wins over
the registry `activeProfile`. For each of these profiles, stop the Agent,
release or verify no active Lease, wait for the Worker to exit, modify the
effective `activeProfile` field in the discovered config, run `--check`,
start, and run the complete real Stage 7B smoke. Do not change only the
registry default:

```text
qwen35-9b-voicedesign
qwen35-4b-base
qwen35-9b-voicedesign
```

The 9B run must expose VoiceDesign voices and exercise VoiceDesign. The 4B run
must expose only the usable Base voice, retain the existing reference asset,
omit `referenceText`, and exercise clone mode with `x_vector_only_mode=true`.
Cross-profile voice IDs must be rejected. The final default is acceptable only
after the third complete smoke passes. Otherwise leave the current config
untouched and report the blocking stage.

## 9. Controlled failure recovery

Do not edit Python/config, change model files, delete state, or kill arbitrary
processes to create a fault. Test recovery only when the operator code proves
that a Worker PID is a descendant of the current owned Agent and its command
line is the Worker entrypoint. Kill only that pinned tree:

```powershell
& "$env:SystemRoot\System32\taskkill.exe" `
    /PID <pinned-worker-pid> /T /F
```

Then verify a fixed sanitized Worker error, no secret/path/raw backend text,
no stale healthy Lease identity, owned Agent cleanup, and a fresh
`--check` plus complete smoke. If no safe deterministic injection exists, mark
this stage `NOT_TESTED` or `BLOCKED`; do not improvise.

## 10. Final cleanup

```powershell
& (Join-Path $Local 'stop-agent.ps1') `
    -ConfigPath $Config -TimeoutSeconds 60
if ($LASTEXITCODE -ne 0) { throw 'FAIL: process_tree_cleanup' }

& $Python $Context.ScriptPath --status --config $Context.ConfigPath
$Listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
    -ErrorAction SilentlyContinue)
if ($Listeners.Count -ne 0) { throw 'FAIL: service_port_still_listening' }

& nvidia-smi --query-compute-apps=pid,used_memory `
    --format=csv,noheader,nounits
```

Record only owned process-tree state, port state, and sanitized VRAM evidence.
If `nvidia-smi` is unavailable, record VRAM as `NOT_TESTED`; do not infer VRAM
release from process exit alone.

## Signoff rule and evidence template

Every required stage must have actual `PASS` evidence. Any required
`NOT_TESTED`, `BLOCKED`, or `FAIL` blocks Stage 7B signoff. Do not pre-fill
statuses, counts, ZIP paths, or artifacts.

```text
Stage:
Status: PASS | FAIL | BLOCKED | NOT_TESTED
Command(s):
Exit code(s):
Sanitized evidence:
Profile:
HTTP routes/Lease evidence:
Audio: codec= ; channels= ; inputSampleRate=24000 ; ffprobeSampleRate= ; duration= ; audible=
Process/port/VRAM evidence:
Blocking error code or reason:
Artifacts retained under config-root diagnostics: yes/no
```
