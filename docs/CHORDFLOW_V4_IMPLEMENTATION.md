# ChordFlow V4 composition branch — implementation and operating notes

Branch: feature/chordflow-v4-composition, based on feature/chordflow-engine-v1.
This is an engineering working branch, **not a certified production-quality music engine**.

## What changed

- Music.generate now uses MusicV4 by default; legacy generation remains private for comparison.
- Shared harmonic context informs lead, bass, arpeggios and accompaniment. Guitar mode stays in C-major-compatible shapes rather than generating a melody in an unrelated key.
- Eight arrangement profiles (lofi, ambient, pop, rock, funk, electronic, jazz, cinematic) have distinct tempo, melodic density, chord rhythm, groove and percussion behavior.
- Eight-beat melodic phrases have rests, changed note lengths, changing contours and chord-tone anchors. Sectional percussion development, per-song kick/snare identities, hat accents and variations replace a single global drum loop.
- MusicQuality.analyze reports repeated two-bar lead and drum windows. It is a **diagnostic**, not an objective musical-quality or human-listening score.
- MusicFingerprint normalizes melodic intervals, note timing and rhythm by beat, and combines melody, drums, harmony and structure signatures. Seed-only changes and global transposition do not bypass melodic exact-match checks.
- The controller reserves signatures via an authenticated worker lease **before** rendering. Signatures live in durable job journals; deleted READY outputs retain musical history. Cancelled/failed jobs do not monopolize signatures. Workers try at most six deterministic variants before returning MUSIC_TOO_SIMILAR_AFTER_BOUNDED_RECOMPOSITION.
- A music-style selector is available on the Command Center. An explicit history reset is allowed only when no jobs are QUEUED or RUNNING.
- MIDI export uses separate instrument tracks with General MIDI programs, including standard channel 10 percussion. Canonical timing is unchanged.
- New optional SoundFont renderer through local FluidSynth. The existing portable preview synthesizer remains available; its preset tones are now more differentiated across roles.
- Existing queue controls, randomized/manual seeds, permanent deletion, NVENC/x264 selection and output audit remain in place. The original 10-field worker claim protocol is retained.

## Music generation

The Command Center's Music style field chooses auto, lofi, ambient, pop, rock, funk, electronic, jazz, or cinematic. Existing clients that omit the field use auto.

For command-line callers, the existing Music.generate(seed, seconds, mode, theme) remains valid, and a five-argument overload accepts a style. Controller retries with a variation number chosen after cross-batch similarity checks; both the real note events and the original seed are saved into the composition JSON.

A reset can be requested in the dashboard using Reset music history. This explicitly removes fingerprint reservations/history; the deleted music could then be generated again.

## Natural instruments versus MIDI note pitches

MIDI note events already represent exact musical pitches (C, D, E, F#, etc.). Previously, published video audio came from sine-wave and noise synthesis unless an external sound bank was manually configured. This created the repetitive synthetic tone heard in real renders. **Production now requires real sampled instruments by default**; preview synthesis is an explicit opt-in.

The V4 composer was also corrected to construct scale-appropriate major, minor, diminished, seventh and half-diminished chord intervals. This improves musical correctness but does not establish professional songwriting quality.

### First-time Windows 10 setup

1. Download an SF2 SoundFont whose license covers the intended output. A commonly used option is GeneralUser GS (https://www.schristiancollins.com/generaluser), whose author permits personal and commercial musical recordings, but notes uncertainty about some original sample sources. Read the included license. MuseScore's sound bank list (https://musescore.org/en/node/101) has other options.
2. Install or extract the official FluidSynth Windows release from https://github.com/FluidSynth/fluidsynth/releases. The extracted fluidsynth.exe must retain its DLL dependencies.
3. Open PowerShell in the musicrot repository. Run:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/configure-real-instruments.ps1

4. Supply the full path to the SF2 file, then the full path to fluidsynth.exe when prompted. The script tests FluidSynth and saves the two paths in the ignored chordflow-data directory.
5. Restart the controller and worker by launching command_center_windows.bat.

Alternative: place a licensed SF2 at soundfonts/default.sf2 and install FluidSynth on PATH (or tools/fluidsynth/bin/fluidsynth.exe).

Configured paths survive restarts. Audio is rendered locally, offline, through FluidSynth into a WAV, which is then trimmed and converted using FFmpeg before output verification. Neither a sound bank nor the FluidSynth binary is redistributed by this repository.

**Fail closed:** Without an SF2 and FluidSynth, the normal Windows launcher stops with actionable setup steps rather than silently generating another sine-wave track.

### Explicit old-style synthetic preview

Only use if deliberately evaluating layouts, timing, or video encoding without sampled instruments:

    $env:CHORDFLOW_AUDIO_QUALITY = "preview"
    .\command_center_windows.bat

That override applies only to the current PowerShell process and children. Unset it before creating publishable videos:

    Remove-Item Env:CHORDFLOW_AUDIO_QUALITY -ErrorAction SilentlyContinue

CI uses explicit preview mode for sample-free integration checks, plus a check that sampled production fails clearly when an SF2 is unavailable.

### Sample provenance

Recording notes generated by ChordFlow is distinct from redistributing a sampled sound bank. Verify licenses when using third-party assets, especially in a commercial workflow. No sound bank is bundled and no downloads occur without the operator's action.

A sampled piano or guitar will sound more natural than an additive oscillator, but sound-bank quality, phrasing, velocity and mix still determine whether the finished video sounds musical. Real instrument samples are not a promise of a professionally mixed track.

## Verification

Run on Windows:

    ./scripts/build.ps1

Or on Linux:

    bash scripts/build.sh

Then:

    python tests/integration_music_fingerprint.py
    python tests/integration_queue_delete_seed.py
    python tests/integration_queue_v2.py

CI tests Java 20 and 21 on Linux and Windows. The new fingerprint test exercises two live leases, a deliberate collision, cancellation, and explicit history reset. Java self-tests include seed-metadata invariance, octave-shift invariance, a 20-song same-style fingerprint comparison and new multitrack MIDI expectations.

For audio-quality verification, listen to at least twenty 60-second same-style outputs and twenty mixed-style outputs, with selected guitar, cinematic, lo-fi and percussion-heavy examples. Evaluate melodic hooks, chord coherence, audible groove differences, dissonance, transitions, endings, sampled instruments and speaker/headphone playback. Record subjective ratings separately from the code's heuristic diagnostics.

## Limitations and planned follow-up

This branch does **not** yet deliver the entirety of the proposed V4 specification:

- No licensed sound bank is bundled; the operator must configure one, and sample playback is not certified on the specific Windows/RTX 3090 workstation.
- The style and arrangement planners are lightweight, not a full professional music-theory, orchestration, or composition model. Intelligent local repair currently changes deterministic composition variants, not individual conflicting musical cells.
- No automated timbral perceptual model or independent human listening study is included.
- Full stereo stem separation, per-track EQ/compression and independent rendered stem files are not implemented. The fallback still uses procedural percussion.
- The existing composition v2/v3 event serialization remains intact for compatibility; a full expanded v4 metadata schema has not been deployed. Chosen style/version/variation are recorded in durable queue journals.
- No auto-queuing previews or regeneration button has been added to the dashboard, and no verified 20-video listening comparison is claimed.
- No copyright originality certification is possible using fingerprints alone.

Those limitations must be resolved or explicitly accepted before claiming a full production-grade audio overhaul.
