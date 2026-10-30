"""Integration checks for random batch seeds and safe permanent video deletion."""
import json
import pathlib
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
url = 'http://127.0.0.1:18992'


def request(path, post=False, allow_error=False):
    req = urllib.request.Request(url + path, data=b'' if post else None,
                                 method='POST' if post else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as e:
        if allow_error:
            return e.code, e.read().decode()
        raise


def q(**k):
    return urllib.parse.urlencode(k)


def enqueue(**options):
    defaults = dict(count=1, seconds=2, mode='flow', theme='noir',
                    width=720, height=1280, encoder='x264', instrument='soft-keys')
    defaults.update(options)
    _, body = request('/api/enqueue?' + q(**defaults), post=True)
    return json.loads(body)['ids'].split(',')


def jobs():
    return {j['id']: j for j in json.loads(request('/api/jobs')[1])['jobs']}


def wait_for_server():
    for _ in range(80):
        try:
            request('/api/jobs')
            return
        except Exception:
            time.sleep(.25)
    raise RuntimeError('controller failed to start')


with tempfile.TemporaryDirectory(prefix='cf-delete-seed-') as tmp:
    work = pathlib.Path(tmp)
    command = ['java', '-Djava.awt.headless=true', '-cp', str(root / 'build'),
               'chordflow.Main', 'serve', '--port', '18992', '--root', tmp]
    controller = subprocess.Popen(command, cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    worker = None
    try:
        wait_for_server()
        ids = enqueue(count=8, randomSeed='true', seed='not-a-number')
        a = jobs()
        seeds = [a[i]['seed'] for i in ids]
        assert len(set(seeds)) == 8, seeds
        assert all(0 <= seed <= 2**63-1 for seed in seeds)
        assert all('randomSeed=true' in (work / 'jobs' / (i + '.properties')).read_text() for i in ids)
        print('PASS 8 distinct random seeds, ignoring invalid manual seed field')

        ids2 = enqueue(count=8, randomSeed='true')
        assert set(seeds).isdisjoint({jobs()[i]['seed'] for i in ids2})
        print('PASS second batch receives fresh random seeds')

        manual = enqueue(count=3, randomSeed='false', seed='987654321')
        a = jobs()
        assert [a[i]['seed'] for i in manual] == [987654321, 987654322, 987654323]
        assert all('randomSeed=false' in (work / 'jobs' / (i + '.properties')).read_text() for i in manual)
        status, body = request('/api/enqueue?' + q(count=1, randomSeed='bogus'), post=True, allow_error=True)
        assert status == 400 and 'BAD_RANDOM_SEED_FLAG' in body
        status, body = request('/api/enqueue?' + q(count=2, randomSeed='false', seed=2**63-1), post=True, allow_error=True)
        assert status == 400 and 'SEED_RANGE_OVERFLOW' in body
        big = enqueue(count=1, randomSeed='false', seed='9007199254740997')[0]
        assert jobs()[big]['seedText'] == '9007199254740997'
        print('PASS manual sequential seed mode, exact large seed display and invalid seed rejection')

        # Nothing is rendered yet. Deletion cannot remove queued or running media.
        assert request('/api/delete?' + q(id=manual[-1]), post=True)[1] == 'NOT_READY'
        status, _ = request('/api/delete?' + q(id='../sneaky'), post=True, allow_error=True)
        assert status == 400
        status, _ = request('/api/delete?' + q(id=manual[-1]), allow_error=True)
        assert status == 400
        print('PASS delete rejects non-READY states, traversal and GET requests')

        all_ids = ids + ids2 + manual + [big]
        output_job = manual[-1]
        for i in all_ids:
            if i != output_job:
                assert request('/api/cancel?' + q(id=i), post=True)[1] == 'CANCELLED'
        worker = subprocess.Popen(['java', '-Djava.awt.headless=true', '-cp', str(root / 'build'),
                                   'chordflow.Main', 'worker', '--url', url, '--id', 'test-seed-delete',
                                   '--out', str(work / 'output')],
                                  cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            item = jobs()[output_job]
            if item['state'] in ('READY', 'FAILED'):
                break
            time.sleep(.6)
        assert item['state'] == 'READY', item
        basename = item['output'][:-4]
        files = [work/'output'/(basename+ext) for ext in ('.mp4', '.manifest.json', '.mid', '.composition.json')]
        assert all(f.is_file() and f.stat().st_size > 0 for f in files), files
        print('PASS actual video READY with all four bundle files')

        video = files[0]
        original = video.read_bytes()
        video.write_bytes(original + b'TAMPER')
        status, body = request('/api/delete?' + q(id=output_job), post=True, allow_error=True)
        assert status == 400 and 'DELETE_VIDEO_HASH_MISMATCH' in body, (status, body)
        assert all(f.exists() for f in files)
        assert jobs()[output_job]['state'] == 'READY'
        video.write_bytes(original)
        print('PASS tampered output fails closed with no deletion')

        assert request('/api/delete?' + q(id=output_job), post=True)[1] == 'DELETED'
        assert all(not f.exists() for f in files)
        assert jobs()[output_job]['state'] == 'DELETED'
        assert not jobs()[output_job]['output']
        assert request('/api/delete?' + q(id=output_job), post=True)[1] == 'DELETED'
        assert request('/api/retry?' + q(id=output_job), post=True)[1] == 'NOT_FAILED'
        status, body = request('/api/media/' + basename + '.mp4', allow_error=True)
        assert status == 400 and 'PREVIEW_NOT_READY' in body
        print('PASS permanent bundle deletion, idempotency and rejected stale preview')

        # Deleted status must persist across an actual controller restart.
        worker.terminate(); worker.wait(timeout=10); worker = None
        controller.terminate(); controller.wait(timeout=10)
        controller = subprocess.Popen(command, cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        wait_for_server()
        assert jobs()[output_job]['state'] == 'DELETED'
        print('PASS deleted state durable across controller restart')
    finally:
        if worker is not None:
            worker.terminate()
            worker.wait(timeout=10)
        controller.terminate()
        controller.wait(timeout=10)