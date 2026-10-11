# Windows lifecycle and desktop follow-up

Goal: continue local-phone acceptance after the user accepted both voice profiles.
The user authorized autonomous design, implementation and verification on Windows.

Design: retain immediate model unloading after each explicit batch. Add a small
phone-side lane spanning acquire through release, shared by current, AUTO and
PINNED preparation. Coroutine cancellation alone cannot serialize server-side
cleanup. Keep the existing runtime contract and process ownership rules.

Desktop: native PowerShell/WinForms tray delegates Start/Stop to the existing
operator; exiting the tray leaves the service running. A per-config mutex
prevents duplicate trays. Optional current-user login startup changes only its
matching Run value, refuses a conflicting command and supports removal.

Delivery: extend the existing source-bundle allowlist. Ship source/templates,
never configured tokens, private machine files, runtime installations or model
weights. This bundle requires existing supported local runtimes/models; it is
not a standalone model download. Profile replacement uses the existing registry
and hash verification. Online providers remain a separate deployment using the
same authenticated v1 API; no private online key is bundled.

Verification sequence:
- [x] Record human 9B role separation/volume and 4B volume/distance acceptance.
- [x] Push/independently verify pending 2a24cbc8 on the mainline feature branch.
- [x] Import a new original five-chapter book without deleting existing data.
- [x] Real playback generates three previously uncached AUTO chapters.
- [x] Reproduce handoff regression before fixing it: wiring suite 4 tests/1 failure.
- [x] Serialize runtime batches; test cancelled predecessor cleanup, cancelled
  waiter and duplicate close. Full JVM: 1148 tests, zero failures/errors, 8 skips.
- [x] Desktop tests RED then GREEN: actual space-path command launch and isolated
  registry ownership/removal; 11 native assertions. Full Python: 328 tests,
  zero failures/errors, 19 skips.
- [x] Actual desktop adapter stop/start, duplicate tray exit and user-login Run
  registration; resident tray alive with no stderr. Private LAN 401/200 preserved.
- [ ] Deliver original-signature debug APK via build-legado.bat and replacement install.
- [ ] Repeat rolling uncached preparation across the three-chapter boundary.
- [ ] Scan secrets, run pre-commit gates, commit/push, build/verify source ZIP and
  retain a checked unified patch and private measurements.

Observed short-fixture timings before handoff fix: current PINNED chapter
121.4 seconds after plan creation; next AUTO chapters 15.8/10.9/7.9 seconds.
All three were READY 63.4 seconds after playback began. Later rolling handoffs
produced BUSY errors; these are not counted as a fully passing rolling test.
Punctuation does not consume spoken-character budget: raw lengths may exceed
50 while the actual letter/digit count remains bounded.

Lifecycle evaluation: retaining models would save roughly 20 seconds per new
batch but reserve about 11 GiB VRAM between batches. Batch sharing already
avoids per-segment loading, so immediate release remains the default on this
shared GPU host. No idle-retention code or relaxed resource gate was introduced.
