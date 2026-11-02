"""Local ACE-Step HTTP fixture: test real Java end-to-end audio-to-video path.

No model weights, network downloads, or copyrighted audio are required.
"""
import http.server
import io
import json
import math
import os
import pathlib
import subprocess
import tempfile
import threading
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
buffer = io.BytesIO()
with wave.open(buffer, "wb") as output:
    output.setnchannels(2)
    output.setsampwidth(2)
    output.setframerate(48000)
    samples = bytearray()
    import struct
    for i in range(48000 * 10):
        t = i / 48000
        attack = min(1, t * 8, (10 - t) * 4)
        x = math.sin(2 * math.pi * (220 if int(t) % 2 else 330) * t)
        v = int(x * 0.13 * max(0, attack) * 32767)
        samples.extend(struct.pack("<hh", v, v))
    output.writeframes(samples)
WAV = buffer.getvalue()


class Handler(http.server.BaseHTTPRequestHandler):
    submission = {}

    def log_message(self, *_args):
        pass

    def do_POST(self):
        count = int(self.headers.get("Content-Length", "0"))
        data = json.loads(self.rfile.read(count) or b"{}")
        if self.path == "/release_task":
            Handler.submission = data
            result = {"code": 200, "data": {"task_id": "mock-fixture-1"}}
        elif self.path == "/query_result":
            assert data["task_id_list"] == ["mock-fixture-1"]
            result = {"code": 200, "data": [{"status": 1,
                "result": json.dumps([{"file": "/v1/audio?path=mock-fixture"}])}]}
        else:
            self.send_error(404)
            return
        encoded = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if not self.path.startswith("/v1/audio?path=mock-fixture"):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(WAV)))
        self.end_headers()
        self.wfile.write(WAV)


with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = dict(os.environ)
    env["CHORDFLOW_ACE_URL"] = "http://127.0.0.1:%d" % server.server_port
    with tempfile.TemporaryDirectory(prefix="chordflow-neural-integration-") as work:
        cmd = [
            "java", "-Djava.awt.headless=true", "-cp", str(ROOT / "build"),
            "chordflow.Main", "render", "--engine", "ace", "--seconds", "10",
            "--seed", "123", "--style", "jazz", "--bpm", "110",
            "--key", "0", "--scale", "minor",
            "--width", "240", "--height", "426", "--fps", "10",
            "--encoder", "x264", "--out", work,
        ]
        result = subprocess.run(cmd, cwd=ROOT, env=env,
                                capture_output=True, text=True, timeout=180)
        assert result.returncode == 0, result.stdout + "\n" + result.stderr
        assert Handler.submission["instrumental"] is True
        assert Handler.submission["lyrics"] == "[Instrumental]"
        assert Handler.submission["key_scale"] == "C Minor"
        assert Handler.submission["audio_duration"] == 10
        assert Handler.submission["use_random_seed"] is False
        video = list(pathlib.Path(work).glob("*.mp4"))
        manifest = list(pathlib.Path(work).glob("*.manifest.json"))
        assert len(video) == len(manifest) == 1
        assert json.loads(manifest[0].read_text())["engine"] == "ace-step"
        assert not list(pathlib.Path(work).glob("*.mid")), "Neural audio is not fictitious MIDI"
        assert not list(pathlib.Path(work).glob("*.composition.json"))
        print("PASS ACE-Step protocol, audio download, verified waveform video, no fake MIDI")
