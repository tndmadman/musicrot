# ChordFlow V3 — Original Arrangement and Editorial Visual Overhaul

**Goal:** Replace the previous one-chord-per-bar tone sequence and blue/pastel/glow backgrounds with an independent, reproducible multi-track arrangement and restrained music-studio visuals.

## Implemented

- Music.java: new deterministic generator for original 4/4 arrangements, with long and short harmonic changes, phrase-aware lead motifs, section variation, syncopation, rhythmic bass, arpeggiated or strummed accompaniment and kick/snare/hi-hat patterns. Melody, bass, accompaniment and drums do not share uniform note-onset schedules.
- Composition.java: schema-v3 **arranged** compositions with independent `harmonyEvents` (chord identity and validated guitar positions), `drumEvents`, individual `noteEvents`, and exact per-event sounding-note snapshots. Melody/bass can change while a harmony remains in force, without falsifying the active-note display.
- CompositionIO.java: strict loading and validation of new schema-v3 arrangements; existing schema-v2 imported/previous compositions remain replayable.
- Audio.java: four role-aware voiced instruments (lead, bass, accompaniment, pluck/arp), independent envelopes and deterministic synthetic drums. Existing selectable instrumental timbres and AAC loudness/decode QA remain. This remains self-contained procedural synthesis; it does **not** claim sampled piano/guitar authenticity.
- Visual.java: editorial monochrome and warm neutral palettes without neon glows or decorative blue ovals. Available themes: `noir` (default), `paper` and `olive`; previously queued legacy theme values render as restrained noir, not blue/purple slop. Note bars and onset dots follow actual composition note events. Guitar diagrams come from separately validated harmony shapes rather than misleading simultaneous-note assumptions.
- QueueServer.java: dashboard dropdown now selects Noir Studio, Ivory Score and Analog Olive; accepts older theme values for persisted jobs.
- Main.java: new default theme noir; Java 20+ support and one-click Windows launcher retained.
- SelfTest.java: 55 passing checks including melodic contours, variable note durations, nonuniform chord schedules, drum backbeats, composition schema v2/v3 replay, exact fret pitches and audio QA.

## Examples

Original 60-second H.264/AAC 720x1280 x264 renders were verified for both flow and guitar modes on Linux. They include synthesized 48kHz stereo audio, music MIDI, composition JSON, and SHA-256 manifests. Final device-specific NVENC throughput, mobile legibility and subjective musical appeal still require testing.

## Design constraints

- Never animate a pitch that is not actually sounding.
- Preserve separate harmony labels from the real sounding-note multiset (musical harmony is not an array of continuously held notes).
- Never show an invented guitar diagram: stored fret shapes must match their declared notes and actual standard tuning.
- Avoid neon gradients, blue/purple decorative palettes, bloom and giant background shapes.
- Repeat recognizable melodic motifs intentionally while varying phrase/cadence and bar-level rhythm.
- Preserve determinism by seed and serialize every sounding note/drum hit. Imported licensed MIDI should remain supported.

## Limits / follow-ups

This is a substantially improved procedural music engine, but not a high-end human-composed music production tool. The drums are synthesized, not licensed sample packs; there is not yet track-level mixing in the dashboard, per-style genre selection, full MIDI instrument maps or a scored human-listening/music-quality panel. Further musical realism can be added using licensed SoundFonts after basic arrangement quality is approved.

No AutoNewsRoller files were changed. Keep the ChordFlow pull request in draft until user review and workstation certification.
