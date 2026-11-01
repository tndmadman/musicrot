package chordflow;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

/** Restartable, file-transaction-backed local queue with time-bound leases and an HTTP UI. */
public final class QueueServer {
    private final Path root,dir;private volatile boolean paused=false,draining=false;
    private static final long LEASE_MS=120_000;
    private static final SecureRandom RANDOM_SEEDS=new SecureRandom();
    private static final Set<String> MUSIC_STYLES=Set.of("auto","lofi","ambient","pop","rock","funk","electronic","jazz","cinematic");
    private QueueServer(Path root)throws IOException{this.root=root.toAbsolutePath();dir=this.root.resolve("jobs");Files.createDirectories(dir);Files.createDirectories(this.root.resolve("output"));Path state=this.root.resolve("control.properties");if(Files.exists(state)){Properties p=new Properties();try(InputStream in=Files.newInputStream(state)){p.load(in);}paused=Boolean.parseBoolean(p.getProperty("paused"));draining=Boolean.parseBoolean(p.getProperty("draining"));}}
    private synchronized void control()throws IOException{Properties p=new Properties();p.setProperty("paused",Boolean.toString(paused));p.setProperty("draining",Boolean.toString(draining));Path tmp=Files.createTempFile(root,".control-",".tmp");try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"queue control");}Files.move(tmp,root.resolve("control.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private record Job(String id,Properties p){}
    private synchronized List<Job> all()throws IOException{
        List<Job> out=new ArrayList<>();try(var ls=Files.list(dir)){for(Path f:ls.filter(x->x.toString().endsWith(".properties")).sorted().toList()){Properties p=new Properties();try(InputStream in=Files.newInputStream(f)){p.load(in);}out.add(new Job(f.getFileName().toString().replace(".properties",""),p));}}return out;
    }
    private synchronized void save(Job j)throws IOException{Path tmp=Files.createTempFile(dir,".job-",".tmp");try(OutputStream out=Files.newOutputStream(tmp)){j.p.store(out,"ChordFlow durable job");}Files.move(tmp,dir.resolve(j.id+".properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private synchronized List<String> enqueue(int count,long seed,int seconds,String mode,String theme,int width,int height,String encoder,String instrument,boolean randomSeeds,String musicStyle,String instrumentOverrides)throws IOException{
        if(count<1||count>100||seconds<2||seconds>180||!Set.of("flow","guitar").contains(mode)||!Set.of("noir","paper","olive","gold","blue","violet").contains(theme)||!(width==720&&height==1280||width==1080&&height==1920)||!Set.of("auto","x264","nvenc").contains(encoder)||!Composition.PRESETS.contains(instrument)||!MUSIC_STYLES.contains(musicStyle))throw new IllegalArgumentException("BAD_JOB_CONFIG");
        Map<String,Integer> exact=InstrumentRegistry.parse(instrumentOverrides);
        if(mode.equals("guitar")&&exact.containsKey("lead"))InstrumentRegistry.validate("guitar",exact.get("lead"));
        if(!randomSeeds){try{Math.addExact(seed,count-1);}catch(ArithmeticException ex){throw new IllegalArgumentException("SEED_RANGE_OVERFLOW",ex);}}
        Set<Long> usedSeeds=new HashSet<>();
        List<String> ids=new ArrayList<>();for(int i=0;i<count;i++){
            long jobSeed=randomSeeds?RANDOM_SEEDS.nextLong()&((1L<<53)-1):seed+i;
            if(randomSeeds){while(!usedSeeds.add(jobSeed))jobSeed=RANDOM_SEEDS.nextLong()&((1L<<53)-1);}
            String id=UUID.randomUUID().toString();Properties p=new Properties();p.setProperty("seed",Long.toString(jobSeed));p.setProperty("randomSeed",Boolean.toString(randomSeeds));p.setProperty("seconds",Integer.toString(seconds));p.setProperty("mode",mode);p.setProperty("theme",theme);p.setProperty("width",String.valueOf(width));p.setProperty("height",String.valueOf(height));p.setProperty("encoder",encoder);p.setProperty("instrument",instrument);p.setProperty("requestedMusicStyle",musicStyle);p.setProperty("instrumentOverrides",instrumentOverrides);p.setProperty("state","QUEUED");p.setProperty("attempts","0");p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(new Job(id,p));ids.add(id);}return ids;
    }
    private synchronized void recover()throws IOException{for(Job j:all())if(j.p.getProperty("state").equals("RUNNING")&&Long.parseLong(j.p.getProperty("leaseUntil","0"))<System.currentTimeMillis()){j.p.setProperty("state",Integer.parseInt(j.p.getProperty("attempts","0"))>=3?"FAILED":"QUEUED");j.p.setProperty("error","WORKER_LEASE_EXPIRED");j.p.remove("leaseToken");j.p.remove("worker");save(j);}}
    private synchronized Job claim(String worker)throws IOException{
        recover();if(paused||draining)return null;
        for(Job j:all())if(j.p.getProperty("state").equals("QUEUED")){j.p.setProperty("state","RUNNING");j.p.setProperty("worker",worker);j.p.setProperty("leaseToken",UUID.randomUUID().toString());j.p.setProperty("leaseUntil",Long.toString(System.currentTimeMillis()+LEASE_MS));j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));j.p.setProperty("attempts",Integer.toString(Integer.parseInt(j.p.getProperty("attempts"))+1));save(j);return j;}return null;
    }
    private synchronized String heartbeat(String id,String worker,String token)throws IOException{Job j=get(id);if(!validLease(j,worker,token))return "REJECTED";j.p.setProperty("leaseUntil",Long.toString(System.currentTimeMillis()+LEASE_MS));save(j);return "OK";}
    private boolean validLease(Job j,String worker,String token){return j.p.getProperty("state").equals("RUNNING")&&j.p.getProperty("worker","").equals(worker)&&!token.isBlank()&&j.p.getProperty("leaseToken","").equals(token)&&Long.parseLong(j.p.getProperty("leaseUntil","0"))>=System.currentTimeMillis();}
    private synchronized Job get(String id)throws IOException{if(!id.matches("[a-zA-Z0-9-]{1,90}"))throw new IllegalArgumentException("BAD_JOB_ID");Path file=dir.resolve(id+".properties");if(!Files.exists(file))throw new IOException("JOB_NOT_FOUND");Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return new Job(id,p);}
    /**
     * Controller-owned persistent musical originality ledger. The job journal is
     * itself the index: deleted READY videos retain their signatures, failed and
     * expired jobs do not block later production, and synchronized reservations
     * prevent concurrent workers from claiming the same composition.
     */
    private synchronized String musicStyle(String id,String worker,String token)throws IOException{
        Job job=get(id);
        if(!validLease(job,worker,token))return "STALE_LEASE";
        return job.p.getProperty("requestedMusicStyle","auto");
    }
    private synchronized String instrumentOverrides(String id,String worker,String token)throws IOException{
        Job job=get(id);
        if(!validLease(job,worker,token))return "STALE_LEASE";
        return job.p.getProperty("instrumentOverrides","");
    }
    private synchronized String resetMusicHistory()throws IOException{
        for(Job job:all())if("RUNNING".equals(job.p.getProperty("state"))||
            "QUEUED".equals(job.p.getProperty("state")))
            throw new IOException("DRAIN_QUEUE_BEFORE_MUSIC_HISTORY_RESET");
        for(Job job:all())if(job.p.containsKey("musicFingerprint")){
            job.p.remove("musicFingerprint");save(job);
        }
        return "MUSIC_HISTORY_RESET";
    }
    private synchronized String reserveMusic(String id,String worker,String token,String encoded,int variation,
                                              String style,int leadRepeats,int grooveRepeats)throws IOException{
        Job job=get(id);
        if(!validLease(job,worker,token))return "STALE_LEASE";
        if(encoded==null||encoded.length()>400||variation<0||variation>12||
            style==null||!style.matches("[a-z]{2,20}"))throw new IllegalArgumentException("BAD_MUSIC_RESERVATION");
        MusicFingerprint.Signature sig=MusicFingerprint.Signature.parse(encoded);
        for(Job other:all()){
            if(other.id.equals(id))continue;
            String state=other.p.getProperty("state","");
            if(!Set.of("READY","RUNNING","DELETED","DELETING","DELETE_FAILED").contains(state))continue;
            String saved=other.p.getProperty("musicFingerprint","");
            if(saved.isBlank())continue;
            try{
                if(MusicFingerprint.nearDuplicate(sig,MusicFingerprint.Signature.parse(saved)))return "TOO_SIMILAR";
            }catch(IllegalArgumentException ex){throw new IOException("CORRUPT_MUSIC_HISTORY",ex);}
        }
        job.p.setProperty("musicFingerprint",encoded);
        job.p.setProperty("musicVersion","4");
        job.p.setProperty("musicStyle",style);
        job.p.setProperty("musicVariation",Integer.toString(variation));
        job.p.setProperty("musicLeadRepeats",Integer.toString(leadRepeats));
        job.p.setProperty("musicGrooveRepeats",Integer.toString(grooveRepeats));
        save(job);
        return "RESERVED";
    }
    private synchronized String finish(String id,String worker,String token,String filename,String sha,String error)throws Exception{
        Job j=get(id);if(!validLease(j,worker,token))return "STALE_LEASE";
        if(error!=null&&!error.isBlank()){j.p.setProperty("state","FAILED");j.p.setProperty("error",error.substring(0,Math.min(400,error.length())));save(j);return "FAILED";}
        if(filename.contains("/")||filename.contains("\\")||!filename.endsWith(".mp4"))throw new IOException("INVALID_OUTPUT_BASENAME");
        Path output=root.resolve("output").resolve(filename);Path manifest=output.resolveSibling(filename.replace(".mp4",".manifest.json"));
        try{
            if(!Files.isRegularFile(manifest)||!Files.readString(manifest).contains(sha))throw new IOException("MISSING_VERIFIED_MANIFEST");
            if(!Production.hash(output).equals(sha))throw new IOException("SHA256_MISMATCH");
            Production.audit(output,Double.parseDouble(j.p.getProperty("seconds")),Integer.parseInt(j.p.getProperty("width","720")),Integer.parseInt(j.p.getProperty("height","1280")),30);
            j.p.setProperty("state","READY");j.p.setProperty("output",filename);j.p.setProperty("sha256",sha);
        }catch(Exception ex){j.p.setProperty("state","FAILED");j.p.setProperty("error","FINAL_QA_REJECTED: "+ex.getMessage());}
        j.p.remove("leaseToken");j.p.remove("worker");j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(j);return j.p.getProperty("state");
    }
    private synchronized String retry(String id)throws IOException{Job j=get(id);if(!j.p.getProperty("state").equals("FAILED"))return "NOT_FAILED";j.p.setProperty("state","QUEUED");j.p.remove("worker");j.p.remove("leaseToken");j.p.remove("leaseUntil");save(j);return "QUEUED";}
    /** Hide a failed attempt without touching any files or active work. Keep a durable tombstone. */
    private synchronized String dismissFailed(String id)throws IOException{
        Job j=get(id);String state=j.p.getProperty("state","");
        if(state.equals("DISMISSED"))return "DISMISSED";
        if(!state.equals("FAILED"))return "NOT_FAILED";
        j.p.setProperty("state","DISMISSED");
        j.p.remove("error");j.p.remove("worker");j.p.remove("leaseToken");j.p.remove("leaseUntil");
        j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(j);
        return "DISMISSED";
    }
    /** Revoke the lease first; a stale worker can no longer mark this job READY. */
    private synchronized String cancel(String id)throws IOException{
        Job j=get(id);String state=j.p.getProperty("state");
        if(state.equals("CANCELLED"))return "CANCELLED";
        if(!state.equals("QUEUED")&&!state.equals("RUNNING"))return "NOT_CANCELLABLE";
        j.p.setProperty("state","CANCELLED");j.p.setProperty("error","OPERATOR_CANCELLED");
        j.p.remove("leaseToken");j.p.remove("leaseUntil");j.p.remove("worker");
        j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));save(j);return "CANCELLED";
    }
    /** Permanent removal of a verified READY output and its matching generated sidecars.
     * A DELETING journal state prevents an interrupted delete from remaining READY.
     * DELETE_FAILED can be retried without requiring already-deleted files.
     */
    private synchronized String deleteOutput(String id)throws Exception{
        Job j=get(id);String state=j.p.getProperty("state","");
        if(state.equals("DELETED"))return "DELETED";
        if(!Set.of("READY","DELETING","DELETE_FAILED").contains(state))return "NOT_READY";
        String filename=j.p.getProperty("output","");
        if(!filename.matches("chordflow-[a-zA-Z0-9-]{1,100}\\.mp4"))throw new IOException("INVALID_STORED_OUTPUT");
        for(Job other:all()){
            if(!other.id.equals(j.id)&&other.p.getProperty("state","").equals("READY")&&filename.equals(other.p.getProperty("output")))
                throw new IOException("OUTPUT_REFERENCED_BY_ANOTHER_JOB");
        }
        Path outputDir=root.resolve("output");
        if(Files.isSymbolicLink(outputDir)||!Files.isDirectory(outputDir,LinkOption.NOFOLLOW_LINKS))throw new IOException("OUTPUT_DIRECTORY_INVALID");
        String base=filename.substring(0,filename.length()-4);
        Path video=outputDir.resolve(filename);
        Path manifest=outputDir.resolve(base+".manifest.json");
        Path composition=outputDir.resolve(base+".composition.json");
        Path midi=outputDir.resolve(base+".mid");
        List<Path> targets=List.of(video,manifest,composition,midi);
        // Reject symbolic links, special files and wrong content before any deletion.
        for(Path target:targets){
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("DELETE_UNSAFE_TARGET: "+target.getFileName());
        }
        if(state.equals("READY")){
            if(!Files.isRegularFile(video,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("DELETE_MISSING_VERIFIED_VIDEO");
            String sha=j.p.getProperty("sha256","");
            if(!sha.matches("[0-9a-f]{64}")||!sha.equals(Production.hash(video)))throw new IOException("DELETE_VIDEO_HASH_MISMATCH");
            if(!Files.readString(manifest).contains(sha))throw new IOException("DELETE_MANIFEST_MISMATCH");
            j.p.setProperty("state","DELETING");
            j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));
            save(j);
        }
        try{
            for(Path target:targets)Files.deleteIfExists(target);
            j.p.setProperty("state","DELETED");
            j.p.setProperty("deletedOutput",filename);
            j.p.remove("output");j.p.remove("sha256");j.p.remove("error");
            j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));
            save(j);
            return "DELETED";
        }catch(IOException ex){
            j.p.setProperty("state","DELETE_FAILED");
            j.p.setProperty("error",("DELETE_FAILED: "+ex.getMessage()).substring(0,Math.min(400,("DELETE_FAILED: "+ex.getMessage()).length())));
            j.p.setProperty("updated",Long.toString(System.currentTimeMillis()));
            save(j);
            return "DELETE_FAILED";
        }
    }
    private String json()throws IOException{StringBuilder b=new StringBuilder("{\"paused\":"+paused+",\"draining\":"+draining+",\"jobs\":[");boolean first=true;for(Job j:all()){String state=j.p.getProperty("state","");if(state.equals("DELETED")||state.equals("CANCELLED")||state.equals("DISMISSED"))continue;if(!first)b.append(',');first=false;Properties p=j.p;b.append("{\"id\":\"").append(j.id).append("\",\"state\":\"").append(clean(p.getProperty("state"))).append("\",\"seed\":").append(p.getProperty("seed")).append(",\"seedText\":\"").append(p.getProperty("seed")).append("\",\"mode\":\"").append(clean(p.getProperty("mode"))).append("\",\"style\":\"").append(clean(p.getProperty("musicStyle",""))).append("\",\"musicVariation\":").append(p.getProperty("musicVariation","0")).append(",\"attempts\":").append(p.getProperty("attempts")).append(",\"output\":\"").append(clean(p.getProperty("output",""))).append("\",\"error\":\"").append(clean(p.getProperty("error",""))).append("\"}");}return b.append("]}").toString();}
    private static String clean(String s){return s.replace("\\","/").replace("\"","'").replace("\n"," ").replace("\r"," ");}
    private static Map<String,String> query(String url){Map<String,String> m=new HashMap<>();int index=url.indexOf('?');if(index<0)return m;for(String pair:url.substring(index+1).split("&")){String[] kv=pair.split("=",2);if(kv.length==2)m.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return m;}
    private void media(HttpExchange x,String filename)throws Exception{
        if(!x.getRequestMethod().equals("GET")||!filename.matches("chordflow-[a-zA-Z0-9-]{1,100}\\.mp4"))throw new IOException("BAD_PREVIEW_REQUEST");
        boolean approved=false;String expectedHash=null;
        for(Job j:all())if(j.p.getProperty("state").equals("READY")&&filename.equals(j.p.getProperty("output"))){approved=true;expectedHash=j.p.getProperty("sha256");break;}
        if(!approved)throw new IOException("PREVIEW_NOT_READY");
        Path file=root.resolve("output").resolve(filename);if(!Files.isRegularFile(file)||!Production.hash(file).equals(expectedHash))throw new IOException("PREVIEW_HASH_MISMATCH");
        long size=Files.size(file),start=0,end=size-1;String range=x.getRequestHeaders().getFirst("Range");
        if(range!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);if(!m.matches())throw new IOException("BAD_VIDEO_RANGE");start=Long.parseLong(m.group(1));if(!m.group(2).isEmpty())end=Math.min(end,Long.parseLong(m.group(2)));if(start>=size||end<start)throw new IOException("INVALID_VIDEO_RANGE");}
        x.getResponseHeaders().set("Content-Type","video/mp4");x.getResponseHeaders().set("Accept-Ranges","bytes");
        int code=range==null?200:206;if(code==206)x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+size);
        x.sendResponseHeaders(code,end-start+1);
        try(InputStream in=Files.newInputStream(file);OutputStream out=x.getResponseBody()){in.skipNBytes(start);byte[] buffer=new byte[64*1024];long remaining=end-start+1;while(remaining>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("MEDIA_TRUNCATED");out.write(buffer,0,n);remaining-=n;}}
    }
    private static String arg(Map<String,String> q,String key,String def){return q.getOrDefault(key,def);}
    private static void reply(HttpExchange x,int code,String body)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",body.startsWith("<")?"text/html; charset=utf-8":"application/json; charset=utf-8");x.sendResponseHeaders(code,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}}
    public static void serve(int port,Path root)throws Exception{
        QueueServer store=new QueueServer(root);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",x->{try{String path=x.getRequestURI().getPath();
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.matches("https?://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?"))throw new IOException("CROSS_SITE_REQUEST_BLOCKED");
            if(path.startsWith("/api/")&&!path.equals("/api/jobs")&&!path.startsWith("/api/media/")&&!x.getRequestMethod().equals("POST"))throw new IOException("POST_REQUIRED");Map<String,String> q=query(x.getRequestURI().toString());String response;
            if(path.startsWith("/api/media/")){store.media(x,path.substring("/api/media/".length()));return;}
            switch(path){
                case "/"->response=HTML;
                case "/api/jobs"->response=store.json();
                case "/api/enqueue"->{
                    String randomFlag=arg(q,"randomSeed","false");
                    if(!Set.of("true","false").contains(randomFlag))throw new IllegalArgumentException("BAD_RANDOM_SEED_FLAG");
                    boolean randomSeeds=Boolean.parseBoolean(randomFlag);
                    long seed=randomSeeds?0L:Long.parseLong(arg(q,"seed","1"));
                    response="{\"ids\":\""+String.join(",",store.enqueue(Integer.parseInt(arg(q,"count","1")),seed,Integer.parseInt(arg(q,"seconds","60")),arg(q,"mode","flow"),arg(q,"theme","noir"),Integer.parseInt(arg(q,"width","1080")),Integer.parseInt(arg(q,"height","1920")),arg(q,"encoder","auto"),arg(q,"instrument","auto-ensemble"),randomSeeds,arg(q,"style","auto"),arg(q,"instruments","")))+"\"}";
                }
                case "/api/claim"->{Job j=store.claim(arg(q,"worker","worker"));response=j==null?"NONE":j.id+"|"+j.p.getProperty("seed")+"|"+j.p.getProperty("seconds")+"|"+j.p.getProperty("mode")+"|"+j.p.getProperty("theme")+"|"+j.p.getProperty("width","720")+"|"+j.p.getProperty("height","1280")+"|"+j.p.getProperty("leaseToken")+"|"+j.p.getProperty("encoder","auto")+"|"+j.p.getProperty("instrument","warm-pad");}
                case "/api/heartbeat"->response=store.heartbeat(arg(q,"id",""),arg(q,"worker",""),arg(q,"token",""));
                case "/api/music-style"->response=store.musicStyle(arg(q,"id",""),arg(q,"worker",""),arg(q,"token",""));
                case "/api/job-instruments"->response=store.instrumentOverrides(arg(q,"id",""),arg(q,"worker",""),arg(q,"token",""));
                case "/api/instruments"->response=InstrumentRegistry.catalogJson();
                case "/api/instrument-suggestions"->response=InstrumentRegistry.suggestionJson(arg(q,"style","pop"));
                case "/api/music-history-reset"->response=store.resetMusicHistory();
                case "/api/music-reserve"->response=store.reserveMusic(arg(q,"id",""),arg(q,"worker",""),arg(q,"token",""),
                    arg(q,"signature",""),Integer.parseInt(arg(q,"variation","0")),arg(q,"style","unknown"),
                    Integer.parseInt(arg(q,"leadRepeats","0")),Integer.parseInt(arg(q,"grooveRepeats","0")));
                case "/api/finish"->response=store.finish(arg(q,"id",""),arg(q,"worker",""),arg(q,"token",""),arg(q,"filename",""),arg(q,"sha",""),arg(q,"error",""));
                case "/api/retry"->response=store.retry(arg(q,"id",""));
                case "/api/cancel"->response=store.cancel(arg(q,"id",""));
                case "/api/delete"->response=store.deleteOutput(arg(q,"id",""));
                case "/api/delete-failed"->response=store.dismissFailed(arg(q,"id",""));
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
            String data=post(client,url+"/api/claim?worker="+encode(id));if(data.equals("NONE")){Thread.sleep(1500);continue;}
            String[] v=data.split("\\|");if(v.length!=10)throw new IOException("INVALID_CLAIM_RESPONSE: "+data);
            String job=v[0],token=v[7];System.out.println("CLAIM "+job+" seed="+v[1]);
            Thread renderThread=Thread.currentThread();
            java.util.concurrent.atomic.AtomicBoolean revoked=new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean heartbeatStopping=new java.util.concurrent.atomic.AtomicBoolean(false);
            ScheduledExecutorService heart=Executors.newSingleThreadScheduledExecutor();
            heart.scheduleAtFixedRate(()->{
                try{
                    String reply=post(client,url+"/api/heartbeat?id="+encode(job)+"&worker="+encode(id)+"&token="+encode(token));
                    if(!reply.equals("OK")&&!heartbeatStopping.get()){
                        revoked.set(true);renderThread.interrupt();
                    }
                }catch(InterruptedException e){
                    // shutdownNow() interrupts an in-flight HTTP request during normal
                    // job cleanup. This is not a lost lease or a failed video.
                    Thread.currentThread().interrupt();
                    if(!heartbeatStopping.get())System.err.println("HEARTBEAT_FAILED "+e);
                }catch(Exception e){
                    if(!heartbeatStopping.get())System.err.println("HEARTBEAT_FAILED "+e);
                }
            },2,2,TimeUnit.SECONDS);
            try{
                String requestedStyle=post(client,url+"/api/music-style?id="+encode(job)+"&worker="+encode(id)+"&token="+encode(token));
                if(!MUSIC_STYLES.contains(requestedStyle))throw new IOException("UNSUPPORTED_STYLE_OR_STALE_LEASE");
                String rawOverrides=post(client,url+"/api/job-instruments?id="+encode(job)+
                    "&worker="+encode(id)+"&token="+encode(token));
                if(rawOverrides.equals("STALE_LEASE"))throw new IOException("STALE_INSTRUMENT_SELECTION_LEASE");
                Map<String,Integer> exact=InstrumentRegistry.parse(rawOverrides);
                Composition c=null;
                // Repair a collision by composing a new, coherent variant. Never
                // scramble notes, change only metadata or render before reservation.
                for(int variation=0;variation<6;variation++){
                    Composition candidate=MusicV4.generate(Long.parseLong(v[1]),Integer.parseInt(v[2]),v[3],v[4],
                        requestedStyle,variation,v[9],exact);
                    MusicQuality.Report quality=MusicQuality.analyze(candidate);
                    String signature=MusicFingerprint.of(candidate).encode();
                    String style=candidate.title.split(" ")[1];
                    String result=post(client,url+"/api/music-reserve?id="+encode(job)+"&worker="+encode(id)+
                        "&token="+encode(token)+"&signature="+encode(signature)+"&variation="+variation+
                        "&style="+encode(style)+"&leadRepeats="+quality.repeatedMelodyWindows()+
                        "&grooveRepeats="+quality.repeatedGrooveWindows());
                    if(result.equals("RESERVED")){c=candidate;break;}
                    if(!result.equals("TOO_SIMILAR"))throw new IOException("MUSIC_RESERVATION_FAILED: "+result);
                }
                if(c==null)throw new IOException("MUSIC_TOO_SIMILAR_AFTER_BOUNDED_RECOMPOSITION");
                Production.Result result=Production.render(c,out,Integer.parseInt(v[5]),Integer.parseInt(v[6]),30,v[8]);
                if(revoked.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("LEASE_REVOKED");
                String finalState=post(client,url+"/api/finish?id="+encode(job)+"&worker="+encode(id)+"&token="+encode(token)+"&filename="+encode(result.video().getFileName().toString())+"&sha="+result.sha256());
                System.out.println("COMPLETE "+job+" server="+finalState);
                if(!finalState.equals("READY"))System.err.println("OUTPUT_NOT_APPROVED "+result.video());
            }catch(Exception e){
                System.err.println("FAILED "+job+": "+e);
                // If canceled, finish() returns STALE_LEASE and preserves CANCELLED.
                try{post(client,url+"/api/finish?id="+encode(job)+"&worker="+encode(id)+"&token="+encode(token)+"&error="+encode(e.toString()));}
                catch(Exception network){System.err.println("FAIL_REPORT_UNDELIVERED "+network);}
            }finally{
                heartbeatStopping.set(true);
                heart.shutdownNow();
                Thread.interrupted();
            }
        }
    }
    private static String get(HttpClient c,String uri)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create(uri)).timeout(java.time.Duration.ofSeconds(120)).GET().build();HttpResponse<String> r=c.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IOException("HTTP_"+r.statusCode()+": "+r.body());return r.body();}
    private static String post(HttpClient c,String uri)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create(uri)).timeout(java.time.Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.noBody()).build();HttpResponse<String> r=c.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IOException("HTTP_"+r.statusCode()+": "+r.body());return r.body();}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    // Minimal no-dependency dashboard, interactive via browser JS; starts paused only when user clicks pause.
    private static final String HTML="""
        <!doctype html><html><head><meta charset="utf-8"><title>ChordFlow Command Center</title><style>
        body{font:15px system-ui;background:#080d1b;color:#dae4ff;max-width:1100px;margin:32px auto}button,input,select{background:#1a2440;border:1px solid #4e6386;border-radius:6px;color:white;padding:8px;margin:4px}button{cursor:pointer}table{border-collapse:collapse;width:100%}td,th{padding:12px;text-align:left;border-bottom:1px solid #34415e}.small{color:#8ea3c5}h1{letter-spacing:1px}
        </style></head><body><h1>ChordFlow / Command Center</h1><p class="small">Local-first original harmonic video production · Independent of AutoNewsRoller</p>
        <section>Batch count <input id="count" type="number" min="1" max="100" value="2"> Seed <input id="seed" type="number" value="101" disabled> <label style="white-space:nowrap"><input id="randomSeed" type="checkbox" checked onchange="updateSeedMode()"> Random seed for every video</label> Seconds <input id="seconds" type="number" min="2" max="180" value="60"><select id="mode"><option>flow</option><option>guitar</option></select><select id="style"><option value="auto">Auto music style</option><option value="lofi">Lo-fi</option><option value="ambient">Ambient</option><option value="pop">Pop instrumental</option><option value="rock">Rock</option><option value="funk">Funk</option><option value="electronic">Electronic</option><option value="jazz">Jazz-influenced</option><option value="cinematic">Cinematic</option></select><select id="theme"><option value="noir">Noir Studio</option><option value="paper">Ivory Score</option><option value="olive">Analog Olive</option></select><select id="resolution"><option value="1080">1080p final</option><option value="720">720p preview</option></select><select id="encoder"><option>auto</option><option>x264</option><option>nvenc</option></select><select id="instrument"><option value="auto-ensemble">Auto ensemble (genre-matched)</option><option value="warm-pad">Warm / atmospheric</option><option value="soft-keys">Piano / keys</option><option value="nylon-pluck">Guitar / plucked</option><option value="glass">Bell / glass-like</option><option value="layered">Organic layered</option></select><button onclick="enqueue()">Queue batch</button></section>
        <p class="small">To keep the musical theme consistent across a batch, choose a specific Music style instead of Auto. Every seed now selects a curated, genre-compatible band with appropriate bass, melody and accompaniment; no automatic drums or claps are generated. The Noir / Ivory / Olive theme only changes the visuals.</p>
        <section><button onclick="call('/api/pause')">Pause claims</button><button onclick="call('/api/resume')">Resume</button><button onclick="call('/api/drain')">Drain</button><button onclick="resetMusicHistory()">Reset music history</button><span id="summary"></span></section>
        <section id="preview" style="display:none;margin:20px 0"><h2>Output preview</h2><video id="video" controls playsinline style="max-height:640px;max-width:100%"></video></section>
        <table><thead><tr><th>Job</th><th>Seed</th><th>Mode</th><th>State</th><th>Attempts</th><th>Output / error</th><th>Action</th></tr></thead><tbody id="jobs"></tbody></table>
        <script>async function call(url){await fetch(url,{method:'POST'});refresh()}
        function updateSeedMode(){document.getElementById('seed').disabled=document.getElementById('randomSeed').checked}
        async function enqueue(){const p=new URLSearchParams();for(const k of ['count','seconds','mode','style','theme','encoder','instrument'])p.set(k,document.getElementById(k).value);const random=document.getElementById('randomSeed').checked;p.set('randomSeed',String(random));if(!random)p.set('seed',document.getElementById('seed').value);const width=Number(document.getElementById('resolution').value);p.set('width',width);p.set('height',width===1080?1920:1280);await call('/api/enqueue?'+p)}
        async function resetMusicHistory(){if(!confirm('Reset all musical fingerprints? Deleted music can then be generated again. All queued and running jobs must finish or be cancelled first.'))return;const response=await fetch('/api/music-history-reset',{method:'POST'});const result=await response.text();if(!response.ok)alert('Reset not completed: '+result);refresh()}
        async function deleteVideo(id){if(!confirm('Permanently delete this video and its MIDI, composition JSON, and manifest? This cannot be undone.'))return;const response=await fetch('/api/delete?id='+encodeURIComponent(id),{method:'POST'});const result=await response.text();if(!response.ok||result!=='DELETED')alert('Delete was not completed: '+result);refresh()}
        async function deleteFailedEntry(id){if(!confirm('Remove this failed entry from the queue? This cannot be undone.'))return;const response=await fetch('/api/delete-failed?id='+encodeURIComponent(id),{method:'POST'});const result=await response.text();if(!response.ok||result!=='DISMISSED')alert('Entry was not removed: '+result);refresh()}
        function htmlEscape(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
        async function refresh(){const data=await(await fetch('/api/jobs')).json();const player=document.getElementById('video'),shown=player.getAttribute('src');if(shown&&!data.jobs.some(j=>j.state==='READY'&&('/api/media/'+encodeURIComponent(j.output))===shown)){player.pause();player.removeAttribute('src');player.load();document.getElementById('preview').style.display='none'}document.getElementById('summary').textContent='  '+(data.paused?'PAUSED':'ACTIVE')+(data.draining?' / DRAINING':'')+' · '+data.jobs.length+' jobs';document.getElementById('jobs').innerHTML=data.jobs.map(j=>`<tr><td>${j.id.substring(0,8)}</td><td>${htmlEscape(j.seedText)}</td><td>${htmlEscape(j.mode)}</td><td>${htmlEscape(j.state)}</td><td>${j.attempts}</td><td>${j.output?`<a style="color:#93cdff" href="#preview" onclick="preview('${j.output}')">Preview MP4</a>`:htmlEscape(j.error)}</td><td>${j.state==='FAILED'?`<button onclick="call('/api/retry?id=${j.id}')">Retry</button><button onclick="deleteFailedEntry('${j.id}')">Delete entry</button>`:''}${['QUEUED','RUNNING'].includes(j.state)?`<button onclick="call('/api/cancel?id=${j.id}')">Cancel</button>`:''}${j.state==='READY'?`<button onclick=\"deleteVideo('${j.id}')\">Delete video</button>`:''}${['DELETING','DELETE_FAILED'].includes(j.state)?`<button onclick=\"deleteVideo('${j.id}')\">Retry delete</button>`:''}</td></tr>`).join('')}
        function preview(name){if(!/^chordflow-[a-zA-Z0-9-]+\\.mp4$/.test(name))return;document.getElementById('preview').style.display='block';const player=document.getElementById('video');player.src='/api/media/'+encodeURIComponent(name);player.load();player.play().catch(()=>{})}
        refresh();setInterval(refresh,2500);</script></body></html>
        """;
}