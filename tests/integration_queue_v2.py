import pathlib,subprocess,tempfile,urllib.request,urllib.parse,time,json
root=pathlib.Path(__file__).resolve().parents[1]
def req(path,post=False):
    q=urllib.request.Request('http://127.0.0.1:18991'+path,data=b'' if post else None,method='POST' if post else 'GET')
    return urllib.request.urlopen(q,timeout=20).read().decode()
with tempfile.TemporaryDirectory() as d:
    proc=subprocess.Popen(['java','-Djava.awt.headless=true','-cp',str(root/'build'),'chordflow.Main','serve','--port','18991','--root',d],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.STDOUT)
    try:
        for _ in range(40):
            try:req('/api/jobs');break
            except Exception:time.sleep(.2)
        assert req('/api/enqueue?count=2&seed=802&seconds=2&mode=flow&theme=gold&width=720&height=1280&encoder=x264&instrument=soft-keys',True)
        claim=req('/api/claim?worker=test-worker',True).split('|')
        assert len(claim)==10 and claim[5:7]==['720','1280'],claim
        id,token=claim[0],claim[7]
        q=lambda kw:urllib.parse.urlencode(kw)
        assert req('/api/heartbeat?'+q(dict(id=id,worker='test-worker',token='wrong')),True)=='REJECTED'
        assert req('/api/heartbeat?'+q(dict(id=id,worker='test-worker',token=token)),True)=='OK'
        assert req('/api/finish?'+q(dict(id=id,worker='test-worker',token='wrong',error='BOGUS')),True)=='STALE_LEASE'
        assert req('/api/finish?'+q(dict(id=id,worker='test-worker',token=token,error='expected fault')),True)=='FAILED'
        assert req('/api/retry?'+q(dict(id=id)),True)=='QUEUED'
        assert req('/api/pause',True)=='OK';assert req('/api/claim?worker=test-worker',True)=='NONE'
        assert req('/api/resume',True)=='OK'
        assert req('/api/drain',True)=='OK';assert req('/api/claim?worker=test-worker',True)=='NONE'
        assert req('/api/resume',True)=='OK'
        print('PASS queue settings/claim/token/retry/pause/drain')
        worker=subprocess.Popen(['java','-Djava.awt.headless=true','-cp',str(root/'build'),'chordflow.Main','worker','--url','http://127.0.0.1:18991','--id','worker-2','--out',str(pathlib.Path(d)/'output')],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            deadline=time.time()+65
            while time.time()<deadline:
                status=json.loads(req('/api/jobs'))
                if sum(j['state']=='READY' for j in status['jobs'])>=1:break
                time.sleep(.6)
            else:raise AssertionError(f'No READY result: {status}')
            assert any(j['state']=='READY' for j in status['jobs'])
            print('PASS worker rendered actual 720p MP4 / validated READY')
        finally:worker.terminate();worker.wait(timeout=10)
    finally:proc.terminate();proc.wait(timeout=10)
