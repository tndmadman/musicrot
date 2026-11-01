import pathlib,subprocess,tempfile,urllib.request,urllib.parse,urllib.error,time,json
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
        assert req('/api/enqueue?count=1&seed=802&seconds=2&mode=flow&theme=gold&width=720&height=1280&encoder=x264&instrument=soft-keys',True)
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
        # Cancel the claimed job: revoked token cannot heartbeat or finalize READY.
        assert req('/api/claim?worker=test-worker',True).split('|')[0]==id
        current=json.loads(req('/api/jobs'))['jobs'][0]
        assert current['state']=='RUNNING'
        assert req('/api/cancel?'+q(dict(id=id)),True)=='CANCELLED'
        assert id not in {j['id'] for j in json.loads(req('/api/jobs'))['jobs']}, 'cancelled running row must disappear immediately'
        assert 'state=CANCELLED' in (pathlib.Path(d)/'jobs'/(id+'.properties')).read_text(), 'cancel tombstone must persist'
        assert req('/api/heartbeat?'+q(dict(id=id,worker='test-worker',token=token)),True)=='REJECTED'
        assert req('/api/finish?'+q(dict(id=id,worker='test-worker',token=token,filename='faked.mp4',sha='bad')),True)=='STALE_LEASE'
        assert req('/api/cancel?'+q(dict(id=id)),True)=='CANCELLED'
        assert id not in {j['id'] for j in json.loads(req('/api/jobs'))['jobs']}, 'idempotent cancellation stays hidden'
        assert req('/api/retry?'+q(dict(id=id)),True)=='NOT_FAILED'
        print('PASS queue settings/claim/token/retry/pause/drain/cancellation')
        # A failed attempt can be deleted from the UI without affecting other jobs.
        assert req('/api/enqueue?count=1&seed=804&seconds=2&mode=flow&theme=gold&width=720&height=1280&encoder=x264&instrument=soft-keys',True)
        failed_claim=req('/api/claim?worker=test-worker',True).split('|')
        assert len(failed_claim)==10,failed_claim
        failed_id,failed_token=failed_claim[0],failed_claim[7]
        assert req('/api/finish?'+q(dict(id=failed_id,worker='test-worker',token=failed_token,error='deliberate failure')),True)=='FAILED'
        assert failed_id in {j['id'] for j in json.loads(req('/api/jobs'))['jobs']}
        assert req('/api/delete-failed?'+q(dict(id=id)),True)=='NOT_FAILED', 'cancelled job must not be changed'
        assert req('/api/delete-failed?'+q(dict(id=failed_id)),True)=='DISMISSED'
        assert failed_id not in {j['id'] for j in json.loads(req('/api/jobs'))['jobs']}
        assert 'state=DISMISSED' in (pathlib.Path(d)/'jobs'/(failed_id+'.properties')).read_text()
        assert req('/api/delete-failed?'+q(dict(id=failed_id)),True)=='DISMISSED', 'dismissal must be idempotent'
        assert req('/api/retry?'+q(dict(id=failed_id)),True)=='NOT_FAILED'
        try:
            req('/api/delete-failed?'+q(dict(id=failed_id)))
            raise AssertionError('GET unexpectedly dismissed an entry')
        except urllib.error.HTTPError as e:
            assert e.code==400
        print('PASS failed entries can be deleted, hidden and cannot be retried; nonfailed jobs are safe')
        # A fresh job can still be claimed and completed after cancellation.
        assert req('/api/enqueue?count=1&seed=803&seconds=2&mode=flow&theme=gold&width=720&height=1280&encoder=x264&instrument=soft-keys',True)
        worker=subprocess.Popen(['java','-Djava.awt.headless=true','-cp',str(root/'build'),'chordflow.Main','worker','--url','http://127.0.0.1:18991','--id','worker-2','--out',str(pathlib.Path(d)/'output')],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            deadline=time.time()+65
            while time.time()<deadline:
                status=json.loads(req('/api/jobs'))
                if sum(j['state']=='READY' for j in status['jobs'])>=1:break
                time.sleep(.6)
            else:raise AssertionError(f'No READY result: {status}')
            assert any(j['state']=='READY' for j in status['jobs'])
            assert id not in {j['id'] for j in status['jobs']}, 'canceled row reappeared after subsequent completion'
            print('PASS worker rendered actual 720p MP4 / validated READY')
        finally:worker.terminate();worker.wait(timeout=10)
    finally:proc.terminate();proc.wait(timeout=10)
