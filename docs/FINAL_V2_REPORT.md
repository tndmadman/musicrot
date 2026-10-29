# ChordFlow — Implementation and release verification report

**Date:** 2026-10-29  
**Repository:** https://github.com/tndmadman/musicrot  
**Branch:** `feature/chordflow-engine-v1`  
**Pull request:** https://github.com/tndmadman/musicrot/pull/1 (draft; not merged)  
**Latest implementation commit as of this report:** `89f61c92f81ada1026303ef03b3a0e0e4176ab9e`  
**Separate repository:** `tndmadman/AutoNewsRoller` was not modified.

## Result

A complete runnable **development build** is present in the repository. The Java 21/FFmpeg local renderer now produces full-length **60-second 1080 × 1920 H.264/AAC** original music videos in both Flow and Guitar Flow modes. Both final demos have independently decoded successfully and their SHA-256 manifests match the files. A controller, browser queue dashboard, Windows startup scripts, MIDI import, five synthesis presets, audio mastering, and a conservative exact-MIDI-note guitar fingering solver are implemented. **This is not yet certified as a full unattended production release on the user's Windows/RTX 3090 machine.** Remaining release gates are documented below.

## Delivered MP4s (local Linux/container x264)

| Output | Duration | Frame dimensions/FPS | Audio | Integrated loudness | True peak | Render wall (pipeline) | SHA-256 |
|---|---:|---|---|---:|---:|---:|---|
| `chordflow-314-flow-1080-5b9a1f9d.mp4` | 60.000s | 1080 × 1920 at 30fps | AAC stereo 48kHz | -15.2 LUFS | -4.4 dBFS | 34.717s | `e873361a2f2fd4af20d537da97d3af6e8f59469597944aa3701660c98ccda1f4` |
| `chordflow-315-guitar-1080-e1e8f94d.mp4` | 60.000s | 1080 × 1920 at 30fps | AAC stereo 48kHz | -16.0 LUFS | -2.3 dBFS | 44.758s | `31f01aaaf69cb35b16baeb2cd585df43d715fd77a1cdab8e69df2778fa9039a5` |

Both H.264 streams use `yuv420p`, both audio/video tracks start at container time 0, and FFmpeg full decoding completed without errors. The production pipeline performed its media audit before writing the final verified manifest. Visual and musical material are originally synthesized and rendered; neither reference artist audio nor branding is copied.

Additional 10-second 1080p and 60-second 720p demos were tested earlier. The two final full-resolution examples use the updated one-pass FFmpeg loudness normalization with `I=-15:TP=-2.5:LRA=11`; measured post-AAC loudness can differ slightly from targets.

## Implemented code/modules

- `Composition.java`: versioned canonical composition JSON and immutable/fenced chord note data; valid pitch/range and timing checks; variable tempo-map conversion, exact active-note multiset validation throughout each chord interval; original rights metadata and instrument presets.
- `Music.java`: deterministic original chord compositions/voice leading; accurate predefined six-string guitar templates; standard MIDI writing; authorized multitrack PPQ MIDI import with tempo events and held-pedal note lengths; note-event-derived rests and truthful generic chord labels for uncertain imported harmonies.
- `GuitarSolver.java`: conservatively solves actual imported MIDI note pitches against standard-tuning guitar strings with fret/stretch limits, suppresses impossible charts; no note pitch replacement.
- `Audio.java`: real local 48kHz stereo additive synthesis, five distinct timbres (warm pad, keys, nylon-like pluck, glass and layered), envelopes, panning, soft echo/reverb, silence/peak checks. No AI music generation or third-party audio downloads.
- `Visual.java`: continuously animated 2D dark-grid chord/note graphics from authoritative note-event timing; stationary playhead and accurate note labels, six-string diagrams from exact fret arrays, frame-by-frame streaming to FFmpeg.
- `Production.java`: NVENC-first if requested/auto with x264 fallback, full MP4 decode and ffprobe checks, post-encoded AAC LUFS/true-peak audit, file hashes, unique job names, temporary staging and verified manifest-last promotion. Explicit failures do not produce READY manifests.
- `QueueServer.java`: persistent local file-backed controller, local-only browser UI, batch settings with mode/theme/instrument/1080p/720p/codec, pause/resume/drain/retry, per-worker claims and token-fenced heartbeats/completion, worker restart lease expiry, hash-verified previews.
- `Main.java`: compose, render, import MIDI, import-and-render MIDI (including optional conservative guitar mode), controller, worker and self-test CLI.
- `.github/workflows/chordflow-ci.yml`, `tests/integration_queue_v2.py`, `tests/stress_test.py`, `scripts/build.ps1`, `scripts/start-controller.ps1`, `scripts/start-worker.ps1`, `scripts/hardware-acceptance.ps1`: Windows/Linux build, queue tests, stress runner and operator-side verification.

## Actual testing

- **38/38 Java self-tests** passed locally after adding the exact-pitch guitar solver, including deterministic seeds, 200 composition fixtures, MIDI export/import, tempo changes, guitar fingering mismatch rejection, sounding-note timeline rejection, five distinct synthesized timbres, raster motion and SHA-256.
- **Queue integration:** controller API tested with 720p selection, claim/lease token fencing, rejecting stale completions, pause/drain/retry and actual worker-produced verified MP4. Two main integration assertions passed locally; the same scenario is included in CI.
- **20/20 preview stress jobs:** 2-second 240 × 426 H.264/AAC previews, mixed modes/themes, 30.15-second total reported, with intentionally invalid dimensions rejected. **This is not a 20-job 60-second 1080p production benchmark.**
- **Two final full-resolution MP4s:** 60 seconds each, both visual modes, independently verified H.264/AAC, 1080 × 1920, 30fps, stereo 48kHz, decoded audio metrics and complete FFmpeg decode, correct source hash in manifest.
- **Windows & Linux hosted CI:** check the final commit's GitHub Actions result; successful hosted runs validate the build, self-tests, short render and queue integration but do not validate an actual RTX 3090.

## Remaining limitations / release gates

1. **Live Windows workstation:** test on Threadripper 3960X / RTX 3090, verify `h264_nvenc` encode and safe x264 fallback, measure real performance with other GPU workloads running. Neither this machine nor CUDA/NVENC was available to this session.
2. **20-job full-scale batch:** run 20 real 60-second 1080p videos with planned controller restarts, worker interruptions, disk-full, slow network, invalid MIDI, and failed encoders. Preview-only stress success is not an unattended production certification.
3. **Independent sub-frame A/V note-onset analysis:** common event timestamps, video and audio stream starts and decoded integrity have been audited; acoustic note-onset versus pixel/frame edge measurements across 60–180 seconds have not established the strict ±33.33ms acceptance claim.
4. **Music breadth:** the deterministic harmonic engine still offers a limited selection of progression/rhythmic patterns and no full mood/arrangement system, melodic phrasing optimizer, arbitrary meter, complete chords/extensions, or near-duplicate musical fingerprint library. The five presets are local additive synthesis rather than licensed SF2/VST instruments.
5. **Guitar chart reach:** initial guitar templates and an optional conservative exact-pitch standard-tuning solver are validated, but not every possible voicing or ergonomic real-world fingering is certified; charts are omitted rather than invented.
6. **Production controller storage/UX:** file-atomic property storage is persistent but not SQLite transactional; queue settings and actions work, while complete job event history, job-specific cancellation, richer user settings and multi-worker crash/fault soak are not fully implemented.
7. **Security/release:** local-only UI is intended for 127.0.0.1, not public exposure. Further adversarial UI, file-system recovery and crash testing required before broad deployment.
8. **Sound quality:** numeric QA passes, but human listening review of each instrument/theme and phone-sized legibility review are still necessary; the project has not been tested for platform-specific publishing rules.

The draft PR is intentionally **not merged to main** and no automatic publishing was performed.

## Windows setup and operator acceptance

Prerequisites: JDK 21, `ffmpeg`/`ffprobe` in PATH, and NVIDIA GPU driver for NVENC.

```powershell
# From the cloned musicrot feature branch
.\scripts\build.ps1
.\scripts\start-controller.ps1
# Open http://127.0.0.1:8791
# In another PowerShell window:
.\scripts\start-worker.ps1

# Execute workstation acceptance and write local detailed results:
.\scripts\hardware-acceptance.ps1
# Short 720p smoke instead of full 1080p run:
.\scripts\hardware-acceptance.ps1 -SkipFullRender
```

Do not merge or deploy as unattended production until hardware acceptance, full-batch stress and exact audio/visual event synchronization gates are verified.

## Git development checkpoints

1. `1045aa4` — note-event validation, accurate trajectories, final AAC audit, unique output/manifest handling.
2. `c2b1f58` — multitrack tempo-changing MIDI import, sustain and tempo-aware export.
3. `5d22ab9` — 1080p queue configuration, token-fenced leases, POST control.
4. `7ea0785` — improved tests and unique-output checks.
5. `56391e6` — FFmpeg pipe-deadlock fix.
6. `ebc5bb7` — five instrumental presets, rendering optimization.
7. `1db5917` — Windows hardware acceptance script, CI queue tests.
8. `f29f715` — delivered AAC normalization.
9. `54f6c24` — conservative exact-pitch solver for authorized MIDI.
10. `89f61c9` — Windows test teardown race correction.