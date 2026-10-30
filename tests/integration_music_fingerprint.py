"""Controller-side music fingerprint reservation, cancellation and reset regression."""
import json
import pathlib
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
PORT=18993
URL=f"http://127.0.0.1:{PORT}"

def call(route, **params):
    request=urllib.request.Request(URL+route+"?"+urllib.parse.urlencode(params),data=b"",method="POST")
    with urllib.request.urlopen(request,timeout=15) as response:
        return response.read().decode()

with tempfile.TemporaryDirectory(prefix="cf-music-ledger-") as temp:
    controller=subprocess.Popen(["java","-Djava.awt.headless=true","-cp",str(ROOT/"build"),
        "chordflow.Main","serve","--port",str(PORT),"--root",temp],
        cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            try:
                with urllib.request.urlopen(URL+"/api/jobs",timeout=2) as response:
                    json.load(response)
                break
            except Exception:
                time.sleep(.2)
        else:
            raise AssertionError("No local controller")
        ids=call("/api/enqueue",count=2,seconds=2,mode="flow",style="jazz",
                 theme="noir",width=720,height=1280,instrument="soft-keys")
        assert ids and "," in json.loads(ids)["ids"]
        first=call("/api/claim",worker="w1").split("|")
        second=call("/api/claim",worker="w2").split("|")
        assert len(first)==len(second)==10
        id1,id2=first[0],second[0]
        assert call("/api/music-style",id=id1,worker="w1",token=first[7])=="jazz"
        signature=":".join(["a"*64,"b"*64,"c"*64,"d"*64,"abcdef0123456789","123456789abcdef0"])
        data={"signature":signature,"style":"jazz","variation":0,"leadRepeats":0,"grooveRepeats":0}
        assert call("/api/music-reserve",id=id1,worker="w1",token=first[7],**data)=="RESERVED"
        assert call("/api/music-reserve",id=id2,worker="w2",token=second[7],**data)=="TOO_SIMILAR"
        assert call("/api/music-reserve",id=id2,worker="w2",token="wrong",**data)=="STALE_LEASE"
        assert call("/api/cancel",id=id1)=="CANCELLED"
        assert call("/api/music-reserve",id=id2,worker="w2",token=second[7],**data)=="RESERVED"
        assert call("/api/cancel",id=id2)=="CANCELLED"
        assert "musicFingerprint" in (pathlib.Path(temp)/"jobs"/(id2+".properties")).read_text()
        assert call("/api/music-history-reset")=="MUSIC_HISTORY_RESET"
        assert "musicFingerprint" not in (pathlib.Path(temp)/"jobs"/(id2+".properties")).read_text()
        print("PASS persistent shared music fingerprints, concurrent collision prevention, lease guard, reset")
    finally:
        controller.terminate()
        controller.wait(timeout=10)
