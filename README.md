# ChordFlow (independent implementation)

Windows-first, local, original procedural instrumental visualization engine. Not part of AutoNewsRoller. Java 21 + FFmpeg/FFprobe; no ComfyUI, TTS, external song download, copied artist handle, or soundfont required. The audio is synthesized from the canonical timed note events by ChordFlow's own additive-instrument synthesizer.

**Status: functional development build, not production-certification.** Flow and exact six-string guitar modes, deterministic original compositions, MIDI export, licensed MIDI import (restricted subset), persistent local controller/worker queue, in-dashboard seekable MP4 previews, licensed MIDI-to-video rendering, audio QA, full MP4 decode audit, SHA-256 manifests, NVENC attempt with x264 fallback, and Windows setup scripts are implemented. Remaining limitations are below.

## Windows 10 / 11 quick start

1. Install **JDK 21** (not just JRE), **FFmpeg** including ffprobe; ensure `java`, `javac`, `ffmpeg`, `ffprobe` are on `PATH`. All tools must work in PowerShell.
2. In PowerShell at the repository root:

```powershell
.\scripts\build.ps1
.\scripts\start-controller.ps1
```

3. Open `http://127.0.0.1:8791` in your local browser, configure a batch and click **Queue batch**.
4. In a **second PowerShell** window run:

```powershell
.\scripts\start-worker.ps1
```

Completed job rows include seekable video previews; the controller serves only verified READY files with matching SHA-256. Output media, `composition.json`, `.mid`, and manifest files are generated in `chordflow-data/output/`. The controller uses `chordflow-data/jobs/` and persistent on-disk job records. It binds to local loopback only and has no cloud or publish integration.

### Direct CLI commands

```powershell
java -Djava.awt.headless=true -cp build chordflow.Main selftest
java -Djava.awt.headless=true -cp build chordflow.Main render --seed 101 --seconds 60 --mode flow --theme gold --width 1080 --height 1920 --encoder auto --out output
java -Djava.awt.headless=true -cp build chordflow.Main render --seed 102 --seconds 60 --mode guitar --theme blue --width 1080 --height 1920 --encoder auto --out output
java -Djava.awt.headless=true -cp build chordflow.Main compose --seed 103 --seconds 60 --mode flow --out composition.json
java -Djava.awt.headless=true -cp build chordflow.Main import-midi --input C:\licensed\song.mid --rights 'User-owned MIDI' --seconds 60 --out imported.composition.json
java -Djava.awt.headless=true -cp build chordflow.Main import-midi-render --input C:\licensed\song.mid --rights 'User-owned MIDI' --seconds 60 --width 1080 --height 1920 --out output
```

On PowerShell, `-Djava.awt.headless=true` is passed as a single argument to `java` (quote it if PowerShell interprets the leading dash). Encoding defaults to `auto`, which tries `h264_nvenc` first, then `libx264` **only if** NVENC cannot produce valid audited output; no silent fallback to missing/blank media. Use `--encoder x264` to require CPU encoding.

## Canonical composition and audio/visual consistency

`Composition` contains schema version, seed, key/scale mode, title, rights, timing, chord events, per-voice MIDI pitches, note start/length/velocity and optional six-string fret positions. Both MIDI and locally synthesized PCM are emitted from the same note list; the Java2D renderer uses the same note pitch events and seconds for chord labels, curves and the moving grid. Guitar diagrams are constructed from recorded physical frets that are checked against every sounding MIDI pitch (low E through high E); impossible mismatches fail validation. At 30fps, frames sample the composition at `frame / 30.0` seconds. No automated note-auditory comparison (or display-panel calibration for arbitrary user MIDI) has yet been independently certified.

The built-in synthesizer has sine/harmonic additive tones, note attack/release, stereo placement and short echo/reverb; it is an **original synthesizer**, not a soundfont-based sampled piano/guitar engine. It performs PCM RMS/peak/silence audits. Exported audio is stereo 48 kHz WAV internally, encoded 192kbps AAC in the video. For enhanced musical naturalness and preset variety, a future FluidSynth/VST plug-in with independently cleared instrument rights is recommended.

## Queue/failure behavior

Each job is saved atomically as a `.properties` file before the worker claims it. Claim state is protected by synchronized controller methods, lease heartbeats expire after 120 seconds and crashed worker claims can requeue on a subsequent claim. Pause stops new claims; drain finishes active jobs but refuses additional claims. Paused/drained state persists across controller restarts. Retry requeues FAILED jobs. This is a single-controller *local shared-disk* system; remote machines, secure authentication, high-volume transactional SQLite state, more granular stage transitions and rigorous disk-exhaustion recovery remain future work.

The MP4 remains in a temporary directory until audio synthesis, FFmpeg encode, ffprobe format checks and full decoding pass. Metadata SHA-256 is recorded before final promotion. Controller-side completion rechecks video SHA, manifests, codecs and decode. No auto-publishing is performed. Output name collision fails closed instead of overwriting existing finished videos.

## Development tests

```powershell
.\scripts\build.ps1
python tests\stress_test.py
```

The 17 Java checks exercise deterministic seed output, seed variation, chord/note validation, 200 generated compositions, exact fretboard pitch matching, invalid MIDI rejection, standard MIDI file writing, non-silent 48kHz synthesis, raster drawing, provenance hashing and 20 full 2s MP4 QA render jobs. Queue HTTP integration should additionally be performed after starting controller and worker; `stress_test.py` is a **preview-resolution** test, not a GPU hardware stress test.

## Rights and limits

- All built-in melodies/progressions are procedurally generated from original patterns. No music/audio, creator name, artwork, image assets or copyrighted recording was extracted from the references.
- MIDI import requires an explicit ownership/license statement. Only constant-tempo PPQ MIDI with balanced note-on/off events is supported. Tempo changes are deliberately rejected rather than guessed; imported JSON may have generic unclassified chord symbols and needs more chord analysis before graphical publishing.
- Guitar mode uses a curated set of authentic six-string chord shapes, currently based around C-major-family progressions. Known F and Bb barre shapes and C/E, F/A, G/B, C/G inversion diagrams are supported; arbitrary user-specified inversions and solver coverage for all voicings are **not yet complete**. Do not claim guitar mode matches arbitrary imported MIDI chords.
- The native 2D renderer deliberately resembles the *genre* of flowing harmony charts without reproducing the reference creator's distinctive wording, music or identity. It does not use image-generation models.
- Current audio presets are limited: mode/theme affect arrangement and appearance; themes alone do not imply a different instrument. No LUFS-based mastering or true-peak oversampling yet.
- CI/Linux rendering here does **not** prove RTX 3090 NVENC, Windows 10/11 compatibility, exact sub-frame acoustic onset sync, third-party soundfont/VST compliance, sustained 100-job unattended production, hard-disk exhaustion, or arbitrary licensed MIDI correctness. Those require direct on-device testing.

Detailed measured container test results and release limitations: [docs/TEST_REPORT.md](docs/TEST_REPORT.md). GitHub Actions workflow tests Linux and Windows x264 smoke builds after pushes/PRs; consult the actual workflow results before claiming Windows compatibility.

## Windows hardware acceptance (operator-run)

After installing JDK 21 and FFmpeg/ffprobe, run `powershell -ExecutionPolicy Bypass -File scripts/hardware-acceptance.ps1` from the repository root. It runs 33 self-tests and two independent 60s 1080x1920 original renders, records actual wall-clock durations, codec probes and SHA-256 manifests. Use `-SkipFullRender` for a 10-second 720p preflight only. This is an operator-driven acceptance command: **no RTX 3090 or full 20-job 1080p batch has been certified remotely**, and the script never uploads media.
### Conservative exact-guitar solver

`import-midi-render --input owned.mid --rights "Owned by author" --mode guitar` now searches standard-tuning fretboard fingerings matching each **exact MIDI pitch and octave**. Restricts frets to 0–12, fret span to four, and four distinct fretted positions; omitted diagrams are logged as `GUITAR_CHARTS_OMITTED_UNPLAYABLE` while the audio/notes remain unchanged. This is a conservative mathematical solver, not universal human-playability certification. For atypical chords use flow mode or manually review the output. Encoded AAC uses `loudnorm` targeting -15 LUFS, -2.5 dBTP, followed by independent decoded AAC audit.


## Development additions: saved-composition replay and cancellation

The canonical schema-v2 composition JSON can now be reloaded without silently
regenerating notes from its seed. The parser rejects missing fields, malformed
or duplicate JSON keys, unsupported schema versions, excessive input sizes
and invalid MIDI/chord/fret events. The JSON writer escapes controls and quotes.

```powershell
.\scripts\build.ps1
java -cp build chordflow.Main compose --seed 123 --seconds 60 --mode flow --out saved.composition.json
java -cp build chordflow.Main render-json --input saved.composition.json --width 1080 --height 1920 --encoder auto --out output
```

The local controller supports `POST /api/cancel?id=<job-id>` for QUEUED or
RUNNING jobs. Cancellation revokes the lease, so stale workers cannot mark
canceled jobs READY. Heartbeats request interruption when revoked; active
FFmpeg renders should stop, but distributed crash recovery and orphan-output
garbage collection remain future work. The dashboard provides Cancel actions.

The guitar renderer only draws exact fret markers: hard-coded chord-name-based
barre indications were removed because fret coordinates alone do not prove
finger assignment.

Local verification added schema-roundtrip/corruption regression tests and
queue cancellation/fencing tests. The output tests and hosted CI are not
substitutes for full 1080p 20-job Windows/RTX3090 acceptance.

