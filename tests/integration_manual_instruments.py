"""Manual instrument, BPM and key options survive the controller's job lease."""
import json
import pathlib
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:18996"


def post(path, **args):
    qs = urllib.parse.urlencode(args)
    req = urllib.request.Request(BASE + path + ("?" + qs if qs else ""),
                                 method="POST", data=b"")
    try:
        with urllib.request.urlopen(req, timeout=10) as reply:
            return reply.status, reply.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode()


with tempfile.TemporaryDirectory(prefix="cf-manual-instruments-") as work:
    cmd = ["java", "-Djava.awt.headless=true", "-cp", str(ROOT / "build"),
           "chordflow.Main", "serve", "--port", "18996", "--root", work]
    service = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.DEVNULL,
                               stderr=subprocess.STDOUT)
    try:
        for _ in range(80):
            try:
                code, _ = post("/api/instruments")
                if code == 200:
                    break
            except OSError:
                time.sleep(.25)
        else:
            raise RuntimeError("controller did not start")

        status, catalog = post("/api/instruments")
        roles = json.loads(catalog)
        assert status == 200 and any(p["value"] == 65 for p in roles["lead"])
        assert any(p["value"] == -1 for p in roles["arp"])
        status, suggested = post("/api/instrument-suggestions", style="jazz")
        assert status == 200 and json.loads(suggested)["bass"] == 32
        print("PASS selectable GM catalog and genre starter recommendations")

        options = dict(count=1, randomSeed="false", seed=8080,
                       seconds=20, style="jazz", bpm=112, key=5,
                       instruments="lead:65,bass:32,pad:4,arp:-1")
        code, reply = post("/api/enqueue", **options)
        assert code == 200, reply
        job = json.loads(reply)["ids"]
        journal = (pathlib.Path(work) / "jobs" / (job + ".properties")).read_text()
        for expected in ("requestedBpm=112", "requestedKey=5",
                         "instrumentOverrides=lead\\:65,bass\\:32,pad\\:4,arp\\:-1"):
            assert expected in journal, (expected, journal)
        code, claim = post("/api/claim", worker="manual-tester")
        assert code == 200 and claim.split("|")[0] == job, claim
        lease = claim.split("|")[7]
        leaseArgs = dict(id=job, worker="manual-tester", token=lease)
        assert post("/api/job-music-options", **leaseArgs)[1] == "112|5"
        assert post("/api/job-instruments", **leaseArgs)[1] == options["instruments"]
        print("PASS exact program, BPM and key choices survive durable job persistence and lease")

        badRequests = [
            {"instruments": "lead:200"},
            {"instruments": "lead:65,lead:65"},
            {"bpm": 300},
            {"key": 12},
            {"mode": "guitar", "key": 7},
        ]
        for invalid in badRequests:
            opts = {**options, **invalid, "seed": 8081}
            code, _ = post("/api/enqueue", **opts)
            assert code == 400, invalid
        print("PASS unsafe/incompatible instrument and timing options rejected")
    finally:
        service.terminate()
        try:
            service.wait(timeout=5)
        except subprocess.TimeoutExpired:
            service.kill()
