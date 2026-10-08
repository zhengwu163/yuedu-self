# Windows Agent Handoff: NovelAudio Stage 7B

This is the canonical handoff document:
`scripts/novel-audio-local/WINDOWS_HANDOFF.md`.

The handoff bundle must contain the service source, `SERVICE_MANIFEST.json`,
`README.md`, `SMOKE_CHECKLIST.md`, and this document. The packaging CLI is:

```bash
/Users/zzz/baidu/personal-code/reading/ai_tests/venv/bin/python \
  scripts/novel-audio-local/package_service.py \
  --root <source-worktree> \
  --output <new-output-zip>
```

Success is the exact JSON state `{"state":"created"}`. Verify the supplied ZIP
SHA-256 before extraction. The manifest must contain the expected
`sourceBranch`, a 40-character `sourceHead`, `snapshotKind=working-tree`, and
nonempty SHA-256 entries with safe relative paths. The bundle is a
working-tree snapshot, not an immutable commit archive. Do not invent a ZIP
path, checksum, test count, or execution result.

## Exact execution prompt

Copy the prompt below to the Windows execution agent. Replace only the
angle-bracket input values with actual handoff values. Discover the existing
config path; do not assume `local-model.json` is directly under the service
root or under `config/`.

```text
You are the Windows execution agent for NovelAudio local-model-service Stage 7B.

Environment and boundaries:
- Use Windows 11 native PowerShell 5.1 and native CPython 3.12 only.
- Do not use WSL.
- The established service root is D:\NovelAudioLocal. Preserve its current files.
- Discover exactly one existing config from:
  D:\NovelAudioLocal\local-model.json
  D:\NovelAudioLocal\config\local-model.json
  Do not create, move, or rename a config to make a candidate fit.
- The source bundle path is:
  <ABSOLUTE_BUNDLE_PATH_SUPPLIED_BY_MAC>
- The supplied bundle SHA-256 is:
  <SUPPLIED_BUNDLE_SHA256>
- Extract only to this new source root, which must contain spaces:
  <ABSOLUTE_NEW_SOURCE_ROOT_WITH_SPACES>
- Preserve scripts/novel-audio-local and scripts/novel_audio_server exactly.
- Verify SERVICE_MANIFEST sourceBranch, sourceHead, snapshotKind=working-tree,
  every listed SHA-256, and every listed path's safe-relative containment.
- The handoff allowlist includes README.md, SMOKE_CHECKLIST.md,
  WINDOWS_HANDOFF.md, the service source, and SERVICE_MANIFEST.json.
- config.py resolves every relative resource against the discovered config file's
  parent. That parent may be D:\NovelAudioLocal or
  D:\NovelAudioLocal\config. Verify that the actual resolved settings still
  point to the established service resources; do not reject the config parent
  solely because it is the config directory. If resolution is ambiguous or
  points outside the established resources, stop BLOCKED with a sanitized
  config-root mismatch.
- config.activeProfile wins over registry.activeProfile for the effective
  profile. A profile switch must stop the Agent, release or verify no active
  service-wide Lease, wait for the Worker to exit, and then modify the
  effective activeProfile field in the discovered config before check/start.

Forbidden actions:
- No model downloads, moves, copies, renames, or weight modifications.
- No bulk copy over D:\NovelAudioLocal.
- No Git mutation, WSL, Android changes, or Android integration.
- Do not print or paste tokens, keys, model paths, reference-audio paths,
  prompts, chapter text, commands containing secrets, diagnostic paths, or raw
  LLM/backend text.
- Do not run broad cleanup or kill arbitrary processes.
- Stop at the first FAIL or BLOCKED stage and report only fixed/sanitized codes.

Config and schema:
- Inspect the existing config/registry before editing.
- Merge only fields implemented by model_registry.py and the approved Stage 7B
  schema; preserve all other existing valid settings.
- Bind actual existing text/TTS assets and their existing verified SHA-256
  manifests. A blank or stale hash is FAIL; never silently select another asset.
- Test exactly these profile IDs:
  qwen35-9b-voicedesign
  qwen35-4b-base
- Keep maxConcurrency=1 and the minimum VRAM requirement at or above 24 GB.
- The Base catalog entry keeps the existing reference asset, omits
  referenceText, and must use x_vector_only_mode=true. The catalog key is
  referenceText; the internal Qwen operation key is refText.
- There is one active Runtime Lease service-wide, not one per profile.

Execution order:
1. Verify the supplied ZIP SHA-256. Before extraction, require that the
   destination does not exist, reject reparse-point ancestors, and inspect ZIP
   entries with the .NET ZIP API. Reject rooted names, `..` segments, colons,
   control characters, directory entries, and case-insensitive duplicates.
   Extract only to the new source root, then verify the complete manifest and
   require that the extracted file set contains only SERVICE_MANIFEST.json and
   the manifest-listed files before using any service file.
2. The native PowerShell operator test is currently NOT_TESTED. When Windows
   execution is performed, run the source-matched test_operator_windows.ps1
   with operator-common.ps1. Record its actual result; do not substitute a
   different script or claim a pass without running it.
3. Run check-models.ps1 -ConfigPath <DISCOVERED_CONFIG_PATH>. Require a complete
   PASS report for the selected profile and Windows runtime.
4. Start with start-agent.ps1 -ConfigPath <DISCOVERED_CONFIG_PATH>
   -StartupTimeoutSeconds 900. Verify authenticated loopback status and the
   owned listener.
5. Run server.py --smoke against the real service. It must cover all nine
   endpoints, explicit acquire/release, the second acquire returning exactly
   429, no-header automatic leases, identity parity, and native ffprobe for
   actual Ogg/Opus.
6. Repeat start/stop three times with the source-matched scripts, using a
   source path containing spaces.
7. Run the COMPLETE smoke sequence for the default profile. Then stop the
   Agent, release or verify no active Lease and no Worker, modify the
   discovered config's effective activeProfile to qwen35-4b-base, run
   check/start/complete smoke, then repeat the stop/no-Lease/no-Worker/config
   update sequence for qwen35-9b-voicedesign and run check/start/complete smoke
   again. A default-only check or a registry-only edit is insufficient.
8. For audio, validate mono input at 24000 Hz and accept encoded Opus metadata
   at 48000 Hz. Require codec=opus, channels=1, finite duration, OggS output,
   and matching X-TTS-Profile.
9. Perform failure recovery only if the delivered operator code proves a Worker
   PID is a descendant of the current owned Agent and its command line is the
   Worker entrypoint. Kill only that pinned Worker tree with native
   taskkill.exe /PID <pinned-worker-pid> /T /F. Otherwise record NOT_TESTED.
10. Stop through stop-agent.ps1 -ConfigPath <DISCOVERED_CONFIG_PATH>
    -TimeoutSeconds 60. Verify the owned process tree is gone, the service port
    is free, and VRAM has fallen back. Use nvidia-smi only for sanitized
    evidence.

Diagnostics and evidence:
- smoke_http.py creates config-root diagnostics/stage7b-http-* directories.
- Use only its fixed artifact names, including
  automatic-preview.ogg, automatic-synthesize.ogg,
  round1-preview.ogg, round1-synthesize-1.ogg through
  round1-synthesize-3.ogg, and the corresponding round2 files.
- Resolve the configured ffprobe executable relative to the discovered config
  file's parent when its setting is relative.
- `--check` may import `runtime_probe.py` and launch bounded read-only runtime
  inspection subprocesses. This does not load the full TTS models or replace
  the Worker-only responsibility for model initialization and audio generation.
- Record each command, exit code, fixed status/error code, and only allowlisted
  ffprobe fields: codec, channels, sampleRate, duration.
- Record human listening only as audible=PASS or audible=FAIL after actual user
  feedback. Until then it is NOT_TESTED.
- Every required NOT_TESTED, BLOCKED, or FAIL stage blocks signoff.
- Do not write final PASS/test counts or fabricate artifacts. Use the evidence
  template in this document.
- Do not proceed to Android until all required 7B gates and cleanup evidence
  are confirmed.
```

## Current Windows entrypoint parameters

Use the discovered config path with the source-matched scripts:

```powershell
& (Join-Path $Local 'install.ps1') -ConfigPath $Config
& (Join-Path $Local 'check-models.ps1') -ConfigPath $Config
& (Join-Path $Local 'start-agent.ps1') `
    -ConfigPath $Config -StartupTimeoutSeconds 900
& (Join-Path $Local 'stop-agent.ps1') `
    -ConfigPath $Config -TimeoutSeconds 60
```

The current handoff status for native PowerShell execution and human listening
is `NOT_TESTED`. No Windows run, macOS full-suite result, ZIP artifact, or
test count is asserted by this document.

## Required report template

Use one block per stage and stop when a required stage fails:

```text
Stage:
Status: PASS | FAIL | BLOCKED | NOT_TESTED
Command(s):
Exit code(s):
Sanitized evidence:
Profile:
HTTP routes/Lease evidence:
Audio: codec= ; channels= ; inputSampleRate=24000 ; ffprobeSampleRate= ; duration= ; audible=
Process tree:
Port:
VRAM:
Blocking error code/reason:
Artifacts retained under config-root diagnostics: yes/no
```

## Schema and command safety notes

- `package_service.py` has CLI parameters `--root` and `--output`; successful
  creation returns `{"state":"created"}` and refuses to overwrite an existing
  output.
- `snapshotKind=working-tree` describes the bytes read from the current
  working tree. It does not make the bundle an immutable commit archive.
- The config path is discovered from the existing Windows setup. Its parent may
  be the service root or its `config` subdirectory. Since `config.py` resolves
  relative resources against that parent, validate the actual resolved settings
  against the established resources; do not move assets or reject a valid
  `config` parent by directory-name equality alone.
- The example config and registry are templates. Existing assets and verified
  hashes are authoritative.
- A deterministic Worker-fault injector is not part of the operator surface.
  Failure recovery is `NOT_TESTED` unless a pinned, owned Worker descendant can
  be safely terminated and the recovery evidence is observed.
