# Windows audiobook takeover implementation plan

> For agentic workers: use executing-plans inline; the user assigned one Windows executor.

**Goal:** Complete real-model home-LAN audiobook preparation, playback, offline listening and process recovery on the debug package, then evaluate lifecycle improvements.

**Architecture:** Keep the authenticated resident Agent and explicit Worker lease. Port only verified Android defects from the reference branch. Preserve existing phone data, encrypted credentials and exact text ranges.

**Tech Stack:** Native Windows PowerShell, CPython 3.12, JDK 21, Android SDK 36, Gradle wrapper, Kotlin/JVM, Room and real Qwen models.

**Spec:** docs/AI_AUDIOBOOK_WINDOWS_TAKEOVER.md; AGENTS.md; docs/NOVEL_AUDIO_SERVER_API_V1.md.

## Global constraints

- Baseline is 64fc8b7a80f7b32783575830a70b4cb9bae5ea08 on feat/local-model-service.
- Reference 5b6e1e472f957fccb036b70da5f3b9335000b52f is inspected, never merged wholesale.
- Debug package only: io.legado.miss.app.debug; replace-install without data clearing.
- No secrets or novel text in console, reports, commits or pushes.
- Home WLAN, Private firewall only, no public exposure or forwarding.
- Every production change has paired tests; actual pre-commit runner is mandatory.
- build-legado.bat produces the delivery APK. User-facing updateLog has one entry per day, at most 40 characters per entry.
- Real device/model verification and human listening remain distinct from unit tests.

## M1/M2: deploy and verify the service

- [x] Clone an isolated repository and verify HEAD against the baseline.
- [x] Read the takeover, repository and API instructions.
- [x] Run all local-service unit tests and native operator tests.
- [x] Stop the proven old Agent using its matching operator script; start the new Agent with the existing config.
- [x] Complete server.py --smoke; verify nine routes, explicit/automatic leases, repeated acquire 429 and audio format.
- [x] Verify current Private WLAN firewall rule and LAN address.
- [x] Run private-token LAN client: health 401/200, maxSegmentChars 50, acquire selfHosted true, short preview, 50-character synthesis, release. Record timings and keep Agent resident.

## M3: Android prerequisites and independently verified fixes

Files: build-legado.bat; scripts/test_build_legado_windows.py if portability changes are needed; app/src/main/java/io/legado/app/data/entities/NovelAudioEntities.kt; app/src/test/java/io/legado/app/data/entities/NovelAudioPlanJsonTest.kt; app/src/main/java/io/legado/app/service/NovelAudioReadAloudService.kt and its paired test; the continuation, preparation and download coordinator files/tests named by the reference commits.

- [x] Install isolated ai_tests/venv and native JDK 21 / SDK 36 if missing. Wire .git/hooks/pre-commit to that venv and the existing fail-closed run_gates.py.
- [x] Make build-legado.bat use the actual repository/toolchain without bypassing Cronet preparation, protected output copying or build gates. Test actual script execution paths before changing it.
- [x] eeb1ae2b: run a mainline test that encodes an otherwise valid plan and requires decode to return its playable segment. Observe runtime assertion failure, then port the unvalidated storage DTO followed by validated construction. Test malformed plans and retained generics too.
- [x] 4897dc10: reproduce a READY event whose plan competes with an older-process higher-generation stored plan. Assert playback selects the event plan for the same book/chapter and falls back only for missing/mismatched identity. Port only the verified selection and event wiring.
- [x] 5b6e1e47: reproduce duplicate preparation while the current chapter is already preparing; verify continuation decisions and in-flight query behavior before porting.
- [x] 14ce0d52: reproduce loss of a typed backend failure in the download coordinator; preserve only allowlisted error classification, never backend response text.
- [ ] Run targeted JVM tests, full testAppDebugUnitTest and commit/deliver gates. Add applicable Room/media device tests with all production parameters explicit.
- [ ] Update the existing daily updateLog before compilation; keep one concise user-facing daily entry.
- [ ] Build delivery with build-legado.bat, audit package/signature/content, install -r without clearing data.
- [ ] Configure the debug app with the LAN address, encrypted existing token and insecure-HTTP consent without displaying credentials.

## M4: real device evidence

- [ ] Record baseline phone state and debug package; leave the release package untouched.
- [ ] PINNED current chapter -> analysis -> segmented real synthesis -> READY; record generation time, first segment wait and typed failures without text.
- [ ] Verify multivoice playback and exact source highlighting.
- [ ] Enter airplane mode, verify consecutive cached playback, restore original network state.
- [ ] Force-stop only the debug package, reopen and verify position recovery without new network generation.
- [ ] Verify ordinary reading and configured HTTP TTS separately.
- [ ] Request human listening only after all available automated evidence is ready.

## M5: lifecycle and productization

- [ ] Compare immediate unload versus bounded idle retention using real load/generation/VRAM measurements; preserve the single-lease and resource gates.
- [ ] Verify prepare-to-N and playback-triggered next-three preparation using real cached and missing boundaries.
- [ ] Design and implement only the lifecycle/product changes supported by the measurements, with red-green tests and device checks.
- [ ] Secret-scan only explicit staged paths, run pre-commit gates, commit conventionally and push feat/local-model-service without force. If authentication blocks pushing, retain commits and a SHA256-stamped patch.
- [ ] Save checkpoint and milestone report with verified SHA, LAN endpoint, token-file path, timing evidence, remaining issues and executable next actions.

## Evidence update (2026-10-10)

- Service: 322 tests, 19 host skips; operator 12 pass / 1 skip; real nine-route smoke PASS.
- LAN: unauthorized 401, authorized 200; acquire 19.0s, preview 10.9s, 50-character synthesis 19.3s, release 0.6s. Phone WLAN also returns 401/200.
- Baseline regression: four assertion failures (one round-trip boundary, three service-wiring defects). Only the four targeted reference diffs were ported.
- Targeted Android JVM tests: 23 PASS; full JVM: 1113 tests, zero failures/errors, eight host skips.
- Windows playback fixtures use opaque paths; POSIX replacement and unavailable symlink capabilities are explicitly reported as skips. Production credential semantics remain unchanged.
- build-legado.bat debug and deliver gates PASS. Device APK signature differs from the installed app; no replacement or data clearing was attempted.
- Device Room schema version and identity match the mainline. Original debug signing key is required to continue M3/M4.

## Real-device follow-up (2026-10-10)

- Original signing key verified; matching debug APK installed with replacement/downgrade flags, without uninstalling or clearing data. GitHub authorization completed and 24031453 pushed.
- Device Room plan storage and isolated Keystore credential tests: 3 PASS. Phone connection test to the Private LAN endpoint: PASS, 1.2s; configuration encrypted locally.
- First PINNED attempt failed before plan persistence: NoUnusedVoice. Local catalog has one reserved narrator and two dialogue voices; match excludes the narrator, but Android had matched all three speakers through that endpoint.
- New regression tests reproduced three Android failures and one Python missing-field error before the fix. Add optional narrator metadata, strictly decode it, and select a flagged narrator from the catalog while preserving old-server matching behavior.
- After correction: Python 323 tests / zero failures and errors / 19 skips; Android JVM 1117 tests / zero failures and errors / eight host skips. Real generation retry remains pending the updated installation.
### 2026-10-10 PINNED acquisition follow-up

Real device 10472: selecting PINNED while AUTO synthesis held the lease raised a
CONCURRENCY_LIMIT server exception from openBatch outside the presenter error boundary.
The UI coroutine crashed and the cancelled AUTO cleanup left an active lease.
Two red JVM cases reproduce unhandled acquisition and skipped release in a cancelled job.
Report acquisition failure using the fixed service-kind message; cancellation still
propagates. Runtime release runs in NonCancellable IO with its existing 15-second HTTP
limit, so cancellation does not prevent server cleanup. Never expose response bodies.
Device retry, cached offline playback and process-restart resume remain required.
### 2026-10-10 offline restart follow-up

Real PINNED current chapter: READY, 529 characters, 28 intents (21 spoken), 3 voices,
max spoken length 48, first artifact 39.9s, complete batch 207.5s. Ogg/Opus mono
samples exported under diagnostics/phone-e2e-72769071. Airplane+Wi-Fi-off playback
advanced 122->239->303->324, but a process restart produced WAITING_NETWORK.
Root cause: transient reader generation is compared with the content-derived PINNED
batch generation. Three red assertions cover same content across generations,
changed content despite equal generation, and missing verified identity.
Local gate now compares the existing snapshot hash (book/chapter/text/rules identity),
with READY + all artifacts still mandatory. Legacy callers without a snapshot retain
generation checking. The current chapter passes its frozen snapshot before any remote
client is created; actual offline restart needs another signed device delivery.

### 2026-10-10 Windows-closed HTTP TTS follow-up

- Final c137884f debug package passed actual airplane-mode force-stop/resume:
  playback restarted in 3.7s and the saved reading position advanced without network.
- Ordinary system TTS passed with init/start/done callbacks and zero utterance errors.
- The existing unauthenticated HTTP provider returned JSON errors containing 502/404
  hints. Its error body entered the exception and silent fallback advanced playback;
  the two other configured providers require a user login. Provider availability is
  not repaired by replacing it with a mock or inventing credentials.
- Bounded client fix: validate status and content type before exposing either URL
  template or script-engine response to the decoder; close rejected bodies without
  reading them, use fixed codes only, and pause/show the error instead of silent
  fallback for these deterministic rejections. Preserve successful binary streams
  and legacy responses with no Content-Type. Remove chapter text from fallback and
  playback-error logs. No local-model protocol or model lifecycle changes.
- Red evidence: eight response-validation assertions, four integration/privacy
  assertions, then one explicit media-pause wiring assertion. Green checks include
  the full JVM suite, pre-commit gates and original-signature Windows debug build.
- Actual new-package phone playback remains required if ADB is disconnected; never
  promote JVM response fixtures to a real model/device acceptance claim.

### 2026-10-11 connected-device follow-up

- Installed the original-signature 0b492795 debug APK by replacement; existing data
  retained. Imported an original six-chapter test EPUB for multi-chapter acceptance.
- Real startup was refused after the recorded Agent exited but left its PID and
  owner files. Recover only absent PIDs with matching complete ownership under
  the lifecycle command lock and exclusive state lock; recheck before removal.
  Live/reused PIDs, changed/missing ownership and busy state remain protected.
  Native red case reproduced missing recovery; 16 cases passed, one link-privilege
  skip. Full service Python suite: 324, zero failures/errors, 19 skipped.
- A red JVM queue-wiring case exposed a swallowed validated HTTP TTS rejection.
  Propagate that typed rejection to the existing main-thread pause/error presenter.
  Full JVM suite: 1144, zero failures/errors, eight skipped. Real playback remains
  to be checked on the new signed package; service smoke and phone batches follow.
