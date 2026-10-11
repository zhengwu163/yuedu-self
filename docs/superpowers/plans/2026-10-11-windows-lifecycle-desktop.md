# Windows lifecycle and desktop follow-up

Goal: continue local-phone acceptance after the user accepted both voice profiles.
The user authorized autonomous design, implementation and verification on Windows.

Design: retain immediate model unloading after each explicit batch. Add a small
phone-side lane spanning acquire through release, shared by current, AUTO and
PINNED preparation. Coroutine cancellation alone cannot serialize server-side
cleanup. Keep the existing runtime contract and process ownership rules.
When a cancelled request leaves the backend generating, retain the lane while
polling status and retrying release of that same lease. An invalid old lease
never releases a new owner. Cancelled startup has no lease ID and only waits
for idle. Both cleanup paths have a bounded caller deadline.

Desktop: native PowerShell/WinForms tray delegates Start/Stop to the existing
operator; exiting the tray leaves the service running. A per-config mutex
prevents duplicate trays. Optional current-user login startup changes only its
matching Run value, refuses a conflicting command and supports removal.
The login command is compact and checked against Windows' 260-character Run
limit. Only the exact prior generated command can be migrated. Native execution
is verified separately from a real user logout/login.

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
- [x] Deliver original-signature debug APK via build-legado.bat and replacement install.
- [x] Reproduce busy cleanup before recovery: wiring suite 5 tests/1 failure;
  add five behavioral recovery cases. Full JVM: 1154 tests, zero failures/errors,
  8 skips. Desktop native fixture now has 13 passing assertions; real Run command
  is 252 characters and duplicate native execution exits successfully.
- [x] Repeat rolling uncached preparation across the three-chapter boundary.
  Original seven-chapter fixture reached seven READY plans and eleven READY
  artifacts. The five newly uncached chapters took 18.5/17.4/18.0/26.7/19.6
  seconds after plan creation; first audio 18.0/16.9/17.4/25.8/19.0 seconds.
  Startup and cancellation cleanup are excluded from those segment timings:
  five chapters spanned 284.3 seconds including intervening loading/cleanup.
  Current-process BUSY, release failure, startup cleanup failure and plan/audio
  decode failure counts were zero. Final runtime idle, no active lease.
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

The short-chapter test is a cancellation/handoff stress fixture, not a claim
that uncached real novels generate faster than playback. Preloading remains
necessary. The historical failed PARTIAL record is retained; the final result
uses each chapter's new READY plan, without deleting user data.

Real login startup is NOT_TESTED: registering and natively executing the exact
Run command passed, but this task did not log off the user's Windows session.
