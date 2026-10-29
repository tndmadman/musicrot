"""Twenty original rendered MP4 stress jobs and independent media validation.
Run after Java build: python tests/stress_test.py. This intentionally uses fast 240x426
previews; it is not a substitute for Windows 1080x1920 NVENC hardware benchmarking.
"""
import json, subprocess, time, pathlib, sys, tempfile, shutil, hashlib
root=pathlib.Path(__file__).resolve().parents[1]
output=pathlib.Path(tempfile.mkdtemp(prefix='cf-stress-',dir=root))
summary=[];begin=time.perf_counter()
for i in range(20):
    mode='guitar' if i%2 else 'flow';theme=['gold','blue','violet'][i%3];seed=35000+i
    t=time.perf_counter()
    cmd=['java','-Djava.awt.headless=true','-cp','build','chordflow.Main','render','--seed',str(seed),'--seconds','2','--mode',mode,'--theme',theme,'--width','240','--height','426','--fps','30','--encoder','x264','--out',str(output)]
    p=subprocess.run(cmd,cwd=root,capture_output=True,text=True,timeout=90)
    matches=list(output.glob(f'chordflow-{seed}-{mode}-240-*.mp4'))
    assert len(matches)==1, f'Expected one output for {seed}, got {len(matches)}'
    v=matches[0]
    success=p.returncode==0 and v.exists()
    if success:
        info=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,codec_type:format=duration','-of','json',str(v)],capture_output=True,text=True)
        if info.returncode:success=False
        else:
            data=json.loads(info.stdout);success=abs(float(data['format']['duration'])-2)<.2 and {x['codec_name'] for x in data['streams']}=={'h264','aac'}
    summary.append({'job':i,'seed':seed,'mode':mode,'theme':theme,'status':'READY' if success else 'FAILED','wall_seconds':round(time.perf_counter()-t,2),'diagnostic':p.stderr[-500:]})
    print(f"JOB {i+1}/20 {summary[-1]['status']}",flush=True)
    if not success: break
# Failure injection must not leave any READY output.
bad=subprocess.run(['java','-Djava.awt.headless=true','-cp','build','chordflow.Main','render','--seed','987654','--seconds','2','--mode','flow','--width','241','--height','426','--out',str(output)],cwd=root,capture_output=True,text=True)
failure_rejected=bad.returncode!=0 and not list(output.glob('chordflow-987654-flow-241-*.mp4'))
report={'jobs':summary,'ready':sum(x['status']=='READY' for x in summary),'expected':20,'failure_injection_rejected':failure_rejected,'elapsed_seconds':round(time.perf_counter()-begin,2),'hardware':'CI/container CPU; not Windows/RTX 3090'}
(root/'stress-report.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='jobs'},indent=2))
shutil.rmtree(output)
sys.exit(0 if report['ready']==20 and failure_rejected else 1)
