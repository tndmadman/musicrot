# MusicRot V7: Composition and Real-Audio Backends

## Music engines

The Command Center offers two explicit, non-interchangeable engines.

- **MIDI / exact notes (default)**: new song-form, harmonic-cadence and phrase planning for flow mode, seeded genre-specific bass/comp patterns, MIDI expression controllers, and sampled SF2 rendering. MIDI notes, harmony labels and the video note timeline come from the same canonical events. Guitar mode intentionally retains its previous physically checked fret arrangements.
- **ACE-Step / generated audio (opt-in)**: delegates the whole instrumental recording to an independent, user-installed ACE-Step 1.5 local inference API. It downloads the locally generated audio, converts it to verified 48 kHz stereo PCM, audits the output and renders an actual audio waveform. It **does not** fabricate MIDI notes, instrument transcriptions, guitar fingerings or chord labels.

Generated MIDI songs still have **no automatic drum/clap pipeline**. Neither backend silently falls back to the other.

## Windows setup for ACE-Step (RTX 3090)

ACE-Step is a separate external application and model dependency. MusicRot does not install it, download weights, redistribute audio, or guarantee any particular model quality.

1. Follow the official ACE-Step 1.5 install guide: https://github.com/ace-step/ACE-Step-1.5
2. In the ACE-Step repository use its documented uv sync setup and uv run acestep-api server, or run MusicRot's scripts/start-ace-step.ps1 -AceStepRoot "C:\path\to\ACE-Step-1.5" helper. The expected loopback REST endpoint is http://127.0.0.1:8001; it must remain running while rendering.
3. Run the existing command_center_windows.bat launcher. Select **ACE-Step / generated audio** in the engine dropdown. Choose a style and 10–180 seconds. Exact manual MIDI patches and guitar-fret mode are not compatible with this backend.
4. For users who ONLY want neural audio and have no configured SF2, explicitly set the PowerShell environment variable CHORDFLOW_ALLOW_NEURAL_ONLY=1 before starting the batch launcher. This bypasses the SoundFont startup requirement only; MIDI sampled jobs still fail closed. Without the switch, existing one-click behavior is unchanged.

The ACE API URL can be configured with CHORDFLOW_ACE_URL, but only loopback HTTP services are accepted. CHORDFLOW_ACE_MODEL optionally selects a model recognized by that server.

ACE generation requests instrumental=true, lyrics=[Instrumental], a bounded deterministic seed, and optional BPM/key/duration. These are conditioning requests rather than guarantees of correct instrumentation, absence of unintended vocals, BPM, key or structure. **Listen before publication.**

## Higher-quality symbolic audio (MIDI)

Sampled SF2 audio remains default for production MIDI mode; CHORDFLOW_AUDIO_QUALITY=preview is explicitly synthetic test-only audio.

By default MusicRot retains the tested single-pass FluidSynth mix. For separate MIDI instrument stems and a headroom-controlled FFmpeg mix, set CHORDFLOW_STEMS=true before launching the worker. This feature requires the existing licensed SF2, FluidSynth and FFmpeg. Test it on actual hardware with the chosen SoundFont; it can sound different and increases render time. No synthetic fallback is available on failure.

Selected instruments receive MIDI program changes, pan/expression and sustained-note modulation commands where the SoundFont supports them. A basic GM bank may ignore these controllers. Expressive sample libraries and instrument-specific techniques remain important.

## Quality and verification

- MIDI originality history uses real note/harmony structure, not merely seeds.
- Neural history stores an amplitude-envelope fingerprint from actual recordings. This finds exact envelope matches, not all perceptual or copyright similarities.
- Neural output checks RMS, peaks, clipping fraction, excessive silence, generated file size, MP4 codec, frames, duration and final AAC loudness.
- Controller leases/cancel are authoritative. Failed and deleted job entries use the existing state machine.
- Neural outputs have an engine=ace-step manifest and deliberately no fictitious .mid or .composition.json files.
- The neural preview is a measured waveform, not guessed musical notes.
- CI tests use a mock local ACE API and artificial test tones, not actual model weights; they prove integration, not musical realism.

## Limits

Actual ACE-Step model output, RTX 3090 performance, selected SF2, stem renderer, potential vocal leakage and listener preference require real hardware acceptance and careful listening. Java CI cannot establish professional music quality. MusicRot does not currently guarantee music-theory correctness of generated neural audio.

## Tests

- Java 20/21 selftests: scripts/build.ps1 or scripts/build.sh.
- Offline neural endpoint integration: python tests/integration_neural_audio.py (Java and FFmpeg needed).
- Hardware audio/video acceptance: scripts/hardware-acceptance.ps1.

Existing Windows .bat launchers, controller/worker leases, random seeds, video delete/cancel/failed-dismiss and MIDI import/replay are preserved.
