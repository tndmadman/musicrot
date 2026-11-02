"""Reproducible symbolic music benchmark for comparing composition revisions.

Generates MIDI + score JSON across genres/seeds. Requires Java 20+ build.
This is NOT a substitute for human audio-listening evaluation.
"""
import argparse
import collections
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
STYLES = ("lofi", "ambient", "pop", "rock", "funk", "electronic", "jazz", "cinematic")


def fingerprint(score):
    notes = sorted((n for n in score["noteEvents"]
                    if n["voiceId"].startswith("lead")), key=lambda n: n["startSeconds"])
    bpm = score["bpm"]
    rhythm = tuple(round((n["startSeconds"] - notes[0]["startSeconds"]) * bpm / 60 * 4)
                   for n in notes) if notes else ()
    contour = tuple(notes[i]["midiPitch"] - notes[i-1]["midiPitch"]
                    for i in range(1, len(notes)))
    harmonic = tuple(h["symbol"] for h in score.get("harmony", []))
    return (rhythm, contour, harmonic)


def run(count, seconds, output):
    output.mkdir(parents=True, exist_ok=True)
    summary = {}
    for genre in STYLES:
        fingerprints = []
        rhythms = set()
        contours = set()
        harmonies = set()
        for i in range(count):
            path = output / ("%s-%04d.composition.json" % (genre, i))
            command = [
                "java", "-Djava.awt.headless=true", "-cp", str(ROOT / "build"),
                "chordflow.Main", "compose", "--seed", str(100000 + i),
                "--seconds", str(seconds), "--style", genre, "--mode", "flow",
                "--out", str(path),
            ]
            subprocess.run(command, cwd=ROOT, capture_output=True,
                           text=True, check=True, timeout=90)
            score = json.loads(path.read_text(encoding="utf-8"))
            identity = fingerprint(score)
            fingerprints.append(identity)
            rhythms.add(identity[0])
            contours.add(identity[1])
            harmonies.add(identity[2])
        summary[genre] = {
            "tracks": count,
            "distinct_full_scores": len(set(fingerprints)),
            "distinct_lead_rhythms": len(rhythms),
            "distinct_lead_contours": len(contours),
            "distinct_harmonic_progressions": len(harmonies),
            "output_directory": str(output),
        }
    report = output / "benchmark.json"
    report.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print("MIDI and JSON saved. Listen to real SF2/ACE output before claiming production quality.")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--per-style", type=int, default=12)
    p.add_argument("--seconds", type=int, default=40)
    p.add_argument("--out", type=pathlib.Path, default=pathlib.Path("benchmark-output"))
    args = p.parse_args()
    if not 2 <= args.per_style <= 50 or not 10 <= args.seconds <= 180:
        raise SystemExit("Choose 2–50 tracks per style and 10–180 seconds")
    run(args.per_style, args.seconds, args.out)
