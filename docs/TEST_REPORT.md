# ChordFlow implementation verification — October 29, 2026

**Repository:** tndmadman/musicrot; development branch `feature/chordflow-engine-v1`.

## Observed checks on container Linux, OpenJDK 21.0.12, FFmpeg/FFprobe

- Java 21 `javac -encoding UTF-8 -d build src/chordflow/*.java`: passed.
- `java -Djava.awt.headless=true -cp build chordflow.Main selftest`: 17/17 checks passed (generated consistency, theory/pitch validity, MIDI, authorized MIDI round-trip, C/E inversion, guitar physical fret mapping, PCM production and audio QA, actual moving bitmap output, hash).
- 10s 360 × 640 `flow` H.264/AAC render: passed, 300 frames, 10.000s.
- 10s 720 × 1280 `guitar` H.264/AAC render: passed, 300 frames, 10.000s.
- 10s 1080 × 1920 `flow` H.264/AAC render: passed, 300 frames, 10.000s, 7.78s wall time, max RSS about 441 MB (the generated files and machine conditions are local to this report).
- 60s 720 × 1280 `flow` H.264/AAC render: passed, 1800 frames, 60.000s, 26.28s wall time, max RSS about 232 MB.
- 60s 720 × 1280 `guitar` H.264/AAC render: passed, 1800 frames, 60.000s, 28.72s wall time, max RSS about 255 MB. Additional C/E, F/A, G/B, C/G slash-chord/inversion demo passed at 26.67s wall time.
- `python tests/stress_test.py`: 20/20 mixed-mode 2s MP4 jobs READY with independent ffprobe validation, 32.25s total wall, invalid dimensions rejected with no MP4.
- Controller/worker HTTP integration: two queued 2-second jobs (flow and guitar) claimed by a separate JVM worker, encoded and independently verified by controller, resulting in `READY`/`READY`.
- Queue control exercise: 20 jobs enqueued; pause prevented claim; resume enabled claim; heartbeat renewed it; bogus completion was FAILED; retry returned QUEUED; drain prevented claim.
- Encoder-auto exercise on CPU-only host: `h264_nvenc` failure correctly fell back to audited `libx264` output. No RTX 3090 was available for actual NVENC verification.

## Unverified or incomplete

- A 60s full 1080 × 1920 run timed out under the container's tool limit; an abandoned `.cf-partial-*` work directory may remain from abruptly terminated process. That is **not** a final video. A 10s 1080p output succeeded. No 60s 1080p success is claimed.
- Windows scripts were authored but not executed on an actual Windows 10/11 machine or the target Threadripper/RTX 3090; NVENC speed and GPU contention need hardware verification.
- Musical novelty and human subjective quality need listening review. Procedural instrument uses additive synthesis instead of recorded sampled instruments. No integrated LUFS/true-peak mastering.
- No independent acoustic onset-vs-playhead lab synchronization measurement; shared canonical seconds/midi events are correct by construction but the end-to-end 1-frame tolerance was not independently certified.
- Controller is a shared-disk/local-only lease queue rather than secure multi-host SQLite. No crash/disk-full/full-disk failure-injection suite, no unattended 100-job run, no load-tested job history interface.
- Full guitar diagram inference for arbitrary MIDI and variable-tempo MIDI import are intentionally not implemented. Unsafe or unsupported input is rejected rather than guessed.

The purpose of this report is precise evidence, not a blanket release certification. Source or audio references were not copied. No AutoNewsRoller code was modified or merged.
