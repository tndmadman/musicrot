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

## Sound quality — setup and licensing

Sample rendering is **optional**; no SF2 file, sample recordings or native binaries are redistributed.

On Windows 10, install FluidSynth locally and obtain a sound bank whose license permits your intended use (including distribution or monetization of rendered music if applicable). Confirm the library is actually licensed. An arbitrary free download is not evidence of commercial rights.

In the same Command Prompt that launches ChordFlow:

    set "CHORDFLOW_SOUNDFONT=C:\soundbanks\licensed-bank.sf2"
    set "CHORDFLOW_AUDIO_QUALITY=sampled"
    set "CHORDFLOW_FLUIDSYNTH=fluidsynth"

Optionally set CHORDFLOW_FLUIDSYNTH to the full local .exe path. Sampled mode strictly requires a valid SF2 and FluidSynth; it never silently falls back to oscillator audio. Auto (default) uses samples if CHORDFLOW_SOUNDFONT is configured, otherwise uses self-contained preview synthesis. Preview forces the fallback engine.

FluidSynth creates a temporary WAV, then FFmpeg converts and trims it to the exact composition length, 48-kHz stereo PCM. The production pipeline continues verifying the final AAC video after mixing.

No claim of instrument realism should be made solely from measured loudness, file sizes or checksums. Use actual listening samples and reference comparison before publishing large batches.

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

- No licensed sound bank is bundled, and sample playback has not been certified on the specific Windows/RTX 3090 workstation.
- The style and arrangement planners are lightweight, not a full professional music-theory, orchestration, or composition model. Intelligent local repair currently changes deterministic composition variants, not individual conflicting musical cells.
- No automated timbral perceptual model or independent human listening study is included.
- Full stereo stem separation, per-track EQ/compression and independent rendered stem files are not implemented. The fallback still uses procedural percussion.
- The existing composition v2/v3 event serialization remains intact for compatibility; a full expanded v4 metadata schema has not been deployed. Chosen style/version/variation are recorded in durable queue journals.
- No auto-queuing previews or regeneration button has been added to the dashboard, and no verified 20-video listening comparison is claimed.
- No copyright originality certification is possible using fingerprints alone.

Those limitations must be resolved or explicitly accepted before claiming a full production-grade audio overhaul.
