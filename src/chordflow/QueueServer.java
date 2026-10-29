package chordflow;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Restartable, file-transaction-backed local queue with time-bound leases and an HTTP UI. */
public final class QueueServer {
    private final Path root,dir;private volatile boolean paused=false,draining=false;
    private static final long LEASE_MS=120_000;
    private QueueServer(Path root)throws IOException{this.root=root.toAbsolutePath();dir=this.root.resolve("jobs");Files.createDirectories(dir);Files.createDirectories(this.root.resolve("output"));Path state=this.root.resolve("control.properties");if(Files.exists(state)){Properties p=new Properties();try(InputStream in=Files.newInputStream(state)){p.load(in);}paused=Boolean.parseBoolean(p.getProperty("paused"));draining=Boolean.parseBoolean(p.getProperty("draining"));}}
    private synchronized void control()throws IOException{Properties p=new Properties();p.setProperty("paused",Boolean.toString(paused));p.setProperty("draining",Boolean.toString(draining));Path tmp=Files.createTempFile(root,".control-",".tmp");try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"queue control");}Files.move(tmp,root.resolve("control.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private record Job(String id,Properties p){}
    private synchronized List<Job> all()throws IOException{
        List<Job> out=new ArrayList<>();try(var ls=Files.list(dir)){for(Path f:ls.filter(x->x.toString().endsWith(".properties")).sorted().toList()){Properties p=new Properties();try(InputStream in=Files.newInputStream(f)){p.load(in);}out.add(new Job(f.getFileName().toString().replace(".properties",""),p));}}return out;
    }
    private synchronized void save(Job j)throws IOException{Path tmp=Files.createTempFile(dir,".job-",".tmp");try(OutputStream out=Files.newOutputStream(tmp)){j.p.store(out,"ChordFlow durable job");}Files.move(tmp,dir.resolve(j.id+".properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private synchronized List<String> enqueue(int count,long seed,int seconds,String mode,String theme)throws IOException{
        if(count<1||count>100||seconds<2||seconds>180||!Set.of("flow","guitar").contains(mode)||!Set.of("gold","blue","violet").contains(theme))throw new IllegalArgumentException("BAD_JOB_CONFIG");
        List<String> ids=new ArrayList<>();for(int i=0;i<count;i++){String id=UUID.randomUUID().toString();Properties p=new Properties();p.setProperty("seed",Long.toString(seed+i));p.setProperty("seconds",Integer.toString(seconds));p.setProperty("mode",mode);p.setProperty("theme",theme);p.setProperty("state","QUEUED");p.setProperty("attempts","0");p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(new Job(id,p));ids.add(id);}return ids;
    }
    private synchronized void recover()throws IOException{for(Job j:all())if(j.p.getProperty("state").equals("RUNNING")&&Long.parseLong(j.p.getProperty("leaseUntil","0"))<System.currentTimeMillis()){j.p.setProperty("state","QUEUED");j.p.setProperty("error","WORKER_LEASE_EXPIRED");save(j);}}
    private synchronized Job claim(String worker)throws IOException{
        recover();if(paused||draining)return null;
        for(Job j:all())if(j.p.getProperty("state").equals("QUEUED")){j.p.setProperty("state","RUNNING");j.p.setProperty("worker",worker);j.p.setProperty("leaseUntil",Long.toString(System.currentTimeMillis()+LEASE_MS));j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));j.p.setProperty("attempts",Integer.toString(Integer.parseInt(j.p.getProperty("attempts"))+1));save(j);return j;}return null;
    }
    private synchronized String heartbeat(String id,String worker)throws IOException{Job j=get(id);if(!j.p.getProperty("state").equals("RUNNING")||!j.p.getProperty("worker").equals(worker))return "REJECTED";j.p.setProperty("leaseUntil",Long.toString(System.currentTimeMillis()+LEASE_MS));save(j);return "OK";}
    private synchronized Job get(String id)throws IOException{if(!id.matches("[a-zA-Z0-9-]{1,90}"))throw new IllegalArgumentException("BAD_JOB_ID");Path file=dir.resolve(id+".properties");if(!Files.exists(file))throw new IOException("JOB_NOT_FOUND");Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return new Job(id,p);}
    private synchronized String finish(String id,String worker,String filename,String sha,String error)throws Exception{
        Job j=get(id);if(!j.p.getProperty("state").equals("RUNNING")||!j.p.getProperty("worker").equals(worker))return "STALE_LEASE";
        if(error!=null&&!error.isBlank()){j.p.setProperty("state","FAILED");j.p.setProperty("error",error.substring(0,Math.min(400,error.length())));save(j);return "FAILED";}
        if(filename.contains("/")||filename.contains("\\")||!filename.endsWith(".mp4"))throw new IOException("INVALID_OUTPUT_BASENAME");
        Path output=root.resolve("output").resolve(filename);Path manifest=output.resolveSibling(filename.replace(".mp4",".manifest.json"));
        try{
            if(!Files.isRegularFile(manifest)||!Files.readString(manifest).contains(sha))throw new IOException("MISSING_VERIFIED_MANIFEST");
            if(!Production.hash(output).equals(sha))throw new IOException("SHA256_MISMATCH");
            Production.audit(output,Double.parseDouble(j.p.getProperty("seconds")),720,1280,30);
            j.p.setProperty("state","READY");j.p.setProperty("output",filename);j.p.setProperty("sha256",sha);
        }catch(Exception ex){j.p.setProperty("state","FAILED");j.p.setProperty("error","FINAL_QA_REJECTED: "+ex.getMessage());}
        j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(j);return j.p.getProperty("state");
    }
    private synchronized String retry(String id)throws IOException{Job j=get(id);if(!j.p.getProperty("state").equals("FAILED"))return "NOT_FAILED";j.p.setProperty("state","QUEUED");j.p.remove("worker");j.p.remove("leaseUntil");save(j);return "QUEUED";}
    private String json()throws IOException{StringBuilder b=new StringBuilder("{\"paused\":"+paused+",\"draining\":"+draining+",\"jobs\":[");boolean first=true;for(Job j:all()){if(!first)b.append(',');first=false;Properties p=j.p;b.append("{\"id\":\"").append(j.id).append("\",\"state\":\"").append(clean(p.getProperty("state"))).append("\",\"seed\":").append(p.getProperty("seed")).append(",\"mode\":\"").append(clean(p.getProperty("mode"))).append("\",\"attempts\":").append(p.getProperty("attempts")).append(",\"output\":\"").append(clean(p.getProperty("output",""))).append("\",\"error\":\"").append(clean(p.getProperty("error",""))).append("\"}");}return b.append("]}").toString();}
    private static String clean(String s){return s.replace("\\","/").replace("\"","'").replace("\n"," ").replace("\r"," ");}
    private static Map<String,String> query(String url){Map<String,String> m=new HashMap<>();int index=url.indexOf('?');if(index<0)return m;for(String pair:url.substring(index+1).split("&")){String[] kv=pair.split("=",2);if(kv.length==2)m.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return m;}
    private static String arg(Map<String,String> q,String key,String def){return q.getOrDefault(key,def);}
    private static void reply(HttpExchange x,int code,String body)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",body.startsWith("<")?"text/html; charset=utf-8":"application/json; charset=utf-8");x.sendResponseHeaders(code,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}}
    public static void serve(int port,Path root)throws Exception{
        QueueServer store=new QueueServer(root);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",x->{try{String path=x.getRequestURI().getPath();Map<String,String> q=query(x.getRequestURI().toString());String response;
            switch(path){
                case "/"->response=HTML;
                case "/api/jobs"->response=store.json();
                case "/api/enqueue"->{if(!x.getRequestMethod().equals("POST"))throw new IllegalArgumentException("POST_REQUIRED");response="{\"ids\":\""+String.join(",",store.enqueue(Integer.parseInt(arg(q,"count","1")),Long.parseLong(arg(q,"seed","1")),Integer.parseInt(arg(q,"seconds","60")),arg(q,"mode","flow"),arg(q,"theme","gold")))+"\"}";}
                case "/api/claim"->{Job j=store.claim(arg(q,"worker","worker"));response=j==null?"NONE":j.id+"|"+j.p.getProperty("seed")+"|"+j.p.getProperty("seconds")+"|"+j.p.getProperty("mode")+"|"+j.p.getProperty("theme");}
                case "/api/heartbeat"->response=store.heartbeat(arg(q,"id",""),arg(q,"worker",""));
                case "/api/finish"->response=store.finish(arg(q,"id",""),arg(q,"worker",""),arg(q,"filename",""),arg(q,"sha",""),arg(q,"error",""));
                case "/api/retry"->response=store.retry(arg(q,"id",""));
                case "/api/pause"->{store.paused=true;store.control();response="OK";}
                case "/api/resume"->{store.paused=false;store.draining=false;store.control();response="OK";}
                case "/api/drain"->{store.draining=true;store.control();response="OK";}
                default->{reply(x,404,"NOT_FOUND");return;}
            }reply(x,200,response);
        }catch(Exception e){reply(x,400,"ERROR: "+clean(e.getMessage()==null?e.toString():e.getMessage()));}});
        server.setExecutor(Executors.newFixedThreadPool(8));server.start();System.out.println("ChordFlow console: http://127.0.0.1:"+port+" output="+root.toAbsolutePath().resolve("output"));
    }
    public static void worker(String url,String id,Path out)throws Exception{
        HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();Files.createDirectories(out);
        while(!Thread.currentThread().isInterrupted()){
            String data=get(client,url+"/api/claim?worker="+encode(id));if(data.equals("NONE")){Thread.sleep(1500);continue;}
            String[] v=data.split("\\|");if(v.length!=5)throw new IOException("INVALID_CLAIM_RESPONSE: "+data);
            String job=v[0];System.out.println("CLAIM "+job+" seed="+v[1]);
            ScheduledExecutorService heart=Executors.newSingleThreadScheduledExecutor();heart.scheduleAtFixedRate(()->{try{get(client,url+"/api/heartbeat?id="+encode(job)+"&worker="+encode(id));}catch(Exception e){System.err.println("HEARTBEAT_FAILED "+e);}},20,20,TimeUnit.SECONDS);
            try{
                Composition c=Music.generate(Long.parseLong(v[1]),Integer.parseInt(v[2]),v[3],v[4]);Production.Result result=Production.render(c,out,720,1280,30,"auto");
                System.out.println("COMPLETE "+job+" server="+get(client,url+"/api/finish?id="+encode(job)+"&worker="+encode(id)+"&filename="+encode(result.video().getFileName().toString())+"&sha="+result.sha256()));
            }catch(Exception e){System.err.println("FAILED "+job+": "+e);get(client,url+"/api/finish?id="+encode(job)+"&worker="+encode(id)+"&error="+encode(e.toString()));}
            finally{heart.shutdownNow();}
        }
    }
    private static String get(HttpClient c,String uri)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create(uri)).timeout(java.time.Duration.ofSeconds(120)).GET().build();HttpResponse<String> r=c.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IOException("HTTP_"+r.statusCode()+": "+r.body());return r.body();}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    // Minimal no-dependency dashboard, interactive via browser JS; starts paused only when user clicks pause.
    private static final String HTML="""
        <!doctype html><html><head><meta charset="utf-8"><title>ChordFlow Command Center</title><style>
        body{font:15px system-ui;background:#080d1b;color:#dae4ff;max-width:1100px;margin:32px auto}button,input,select{background:#1a2440;border:1px solid #4e6386;border-radius:6px;color:white;padding:8px;margin:4px}button{cursor:pointer}table{border-collapse:collapse;width:100%}td,th{padding:12px;text-align:left;border-bottom:1px solid #34415e}.small{color:#8ea3c5}h1{letter-spacing:1px}
        </style></head><body><h1>ChordFlow / Command Center</h1><p class="small">Local-first original harmonic video production · Independent of AutoNewsRoller</p>
        <section>Batch count <input id="count" type="number" min="1" max="100" value="2"> Seed <input id="seed" type="number" value="101"> Seconds <input id="seconds" type="number" min="2" max="180" value="60"><select id="mode"><option>flow</option><option>guitar</option></select><select id="theme"><option>gold</option><option>blue</option><option>violet</option></select><button onclick="enqueue()">Queue batch</button></section>
        <section><button onclick="call('/api/pause')">Pause claims</button><button onclick="call('/api/resume')">Resume</button><button onclick="call('/api/drain')">Drain</button><span id="summary"></span></section>
        <table><thead><tr><th>Job</th><th>Seed</th><th>Mode</th><th>State</th><th>Attempts</th><th>Output / error</th><th>Action</th></tr></thead><tbody id="jobs"></tbody></table>
        <script>async function call(url){await fetch(url,{method:'POST'});refresh()}
        async function enqueue(){const p=new URLSearchParams();for(const k of ['count','seed','seconds','mode','theme'])p.set(k,document.getElementById(k).value);await call('/api/enqueue?'+p)}
        async function refresh(){const data=await(await fetch('/api/jobs')).json();document.getElementById('summary').textContent='  '+(data.paused?'PAUSED':'ACTIVE')+(data.draining?' / DRAINING':'')+' · '+data.jobs.length+' jobs';document.getElementById('jobs').innerHTML=data.jobs.map(j=>`<tr><td>${j.id.substring(0,8)}</td><td>${j.seed}</td><td>${j.mode}</td><td>${j.state}</td><td>${j.attempts}</td><td>${j.output||j.error}</td><td>${j.state==='FAILED'?`<button onclick="call('/api/retry?id=${j.id}')">Retry</button>`:''}</td></tr>`).join('')}
        refresh();setInterval(refresh,2500);</script></body></html>
        """;
}
