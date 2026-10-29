# Windows audiobook benchmark, steps 0–2

The user authorized autonomous implementation and troubleshooting of this bounded experiment.
Base: local-model-service 3fc6302e; original fixtures: 7f29ef24. No full-book-jobs changes.

1. Preserve the installed service. Add a local-only benchmark client with exact text coverage,
   per-segment timings, complete chapter exports, GPU memory sampling, hashes and resumable evidence.
   Run each chapter cold (acquire included) and warm (preview excluded); hold one explicit lease per chapter.
2. Install VoiceStudio v0.5.7 in isolation. Reuse the three approved voice references.
   Record actual installation failures and only benchmark engines that run offline on Windows.
3. Extend the existing Qwen adapter in a separate module for list batches 4/8, retaining order and
   role prompts. Try faster-qwen3-tts in an isolated environment; use non_streaming_mode for long clone text.
   Use matching chapter text and an aligned A/B extract. Do not change existing v1 field meanings.
4. Test text preservation, accounting, batch output order and failure cases. Decode every output,
   verify hashes, distinguish completed chapters from partial results and subjective listening from measurements.
5. Write AI_AUDIOBOOK_BENCH_RESULTS.md with all measured rows, limitations, listening directory,
   real-time eligibility and per-chapter background times. Scan the complete diff for secrets and personal
   paths; commit selected source/docs only and push perf/qwen-batching to github-fork without force.

GPU experiments are serial. Check ComfyUI queue is empty and stop its identified process before timing.
Audio, credentials, model files, private configuration and raw local logs remain outside Git.
