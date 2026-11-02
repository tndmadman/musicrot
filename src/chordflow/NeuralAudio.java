package chordflow;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional loopback-only ACE-Step 1.5 service. No automatic fallback to MIDI. */
public final class NeuralAudio {
    private NeuralAudio(){}
    public record Request(long seed,int seconds,String style,int bpm,int key,String scale) {
        public Request {
            if(seconds<10||seconds>180||bpm!=0&&(bpm<35||bpm>220)||
               key<-1||key>11||!Set.of("auto","major","minor").contains(scale))
                throw new IllegalArgumentException("NEURAL_MUSIC_CONFIGURATION_INVALID");
            if(!Set.of("auto","lofi","ambient","pop","rock","funk","electronic","jazz","cinematic").contains(style))
                throw new IllegalArgumentException("NEURAL_MUSIC_STYLE_INVALID");
        }
    }
    public static String styleFor(Request req){
        if(!req.style().equals("auto"))return req.style();
        String[] genres={"lofi","ambient","pop","rock","funk","electronic","jazz","cinematic"};
        return genres[Math.floorMod(req.seed(),genres.length)];
    }
    static String prompt(Request req){
        String style=styleFor(req);
        EnsembleCatalog.Ensemble band=EnsembleCatalog.select(req.seed(),style,"auto-ensemble");
        StringBuilder instruments=new StringBuilder(InstrumentRegistry.name(band.lead())+
            " lead, "+InstrumentRegistry.name(band.bass())+" bass");
        if(band.pad()>=0)instruments.append(", ").append(InstrumentRegistry.name(band.pad())).append(" harmony");
        if(band.arp()>=0)instruments.append(", ").append(InstrumentRegistry.name(band.arp())).append(" arpeggios");
        String feel=switch(style){
            case "lofi"->"warm laid-back pocket, textured chords and spare melodic phrases";
            case "ambient"->"unhurried evolving instrumental, spacious transitions and gentle dynamics";
            case "pop"->"memorable lead hook, strong musical phrasing, contrasting sections";
            case "rock"->"organic instrumental band interplay, contrasting riffs and dynamic accents";
            case "funk"->"tight syncopated bass groove, responsive comping and purposeful rests";
            case "electronic"->"distinctive evolving synth timbres and syncopated bass phrases";
            case "jazz"->"responsive instrumental ensemble, expressive phrasing and harmonic voice leading";
            default->"cinematic instrumental with expressive arcs, tension and release";
        };
        return style+" instrumental. "+feel+". "+instruments+
            ". No singing, no spoken voice, no lyrics. Clear beginning, evolving middle, intentional ending.";
    }
    static URI endpoint(){
        String raw=System.getenv().getOrDefault("CHORDFLOW_ACE_URL","http://127.0.0.1:8001");
        URI uri=URI.create(raw);
        String host=uri.getHost();
        if(!"http".equals(uri.getScheme())||host==null||
            !(host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")||host.equals("::1"))||
            uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
            uri.getPort()<1||uri.getPort()>65535||
            (uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("ACE_ENDPOINT_MUST_BE_LOOPBACK_HTTP");
        return URI.create("http://"+(host.contains(":")?"["+host+"]":host)+":"+uri.getPort()+"/");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> obj(Object input)throws IOException{
        if(!(input instanceof Map<?,?> map))throw new IOException("ACE_JSON_OBJECT_REQUIRED");
        Map<String,Object> result=new LinkedHashMap<>();
        for(var e:map.entrySet())if(e.getKey() instanceof String name)result.put(name,e.getValue());
        return result;
    }
    private static String string(Object v)throws IOException {
        if(!(v instanceof String s)||s.length()>10000)throw new IOException("ACE_STRING_INVALID");
        return s;
    }
    private static int number(Object v)throws IOException{
        if(!(v instanceof Number n))throw new IOException("ACE_STATUS_INVALID");
        return n.intValue();
    }
    static String escape(String s){
        StringBuilder b=new StringBuilder("\"");
        for(char ch:s.toCharArray())switch(ch){
            case '"'->b.append("\\\"");
            case '\\'->b.append("\\\\");
            case '\n'->b.append("\\n");
            case '\r'->b.append("\\r");
            case '\t'->b.append("\\t");
            default->{if(ch<32)b.append(String.format("\\u%04x",(int)ch));else b.append(ch);}
        }
        return b.append('"').toString();
    }
    static String payload(Request req){
        StringBuilder b=new StringBuilder("{\"prompt\":").append(escape(prompt(req)))
            .append(",\"lyrics\":\"[Instrumental]\",\"instrumental\":true")
            .append(",\"task_type\":\"text2music\",\"thinking\":true")
            .append(",\"audio_format\":\"wav\",\"batch_size\":1")
            .append(",\"audio_duration\":").append(req.seconds())
            .append(",\"time_signature\":\"4\",\"use_random_seed\":false")
            .append(",\"seed\":").append(Math.floorMod(req.seed(),2_147_483_647L));
        if(req.bpm()>0)b.append(",\"bpm\":").append(req.bpm());
        if(req.key()>=0){
            String[] keys={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};
            String scale=req.scale().equals("auto")?"Major":req.scale().equals("major")?"Major":"Minor";
            b.append(",\"key_scale\":").append(escape(keys[req.key()]+" "+scale));
        }
        String model=System.getenv("CHORDFLOW_ACE_MODEL");
        if(model!=null&&!model.isBlank()&&model.matches("[a-zA-Z0-9_.-]{1,64}"))
            b.append(",\"model\":").append(escape(model));
        return b.append('}').toString();
    }
    private static Map<String,Object> post(HttpClient client,URI base,String path,String payload)
        throws IOException,InterruptedException {
        HttpRequest r=HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(45)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build();
        HttpResponse<String> response=client.send(r,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200||response.body().length()>1_000_000)
            throw new IOException("ACE_RESPONSE_INVALID_HTTP_"+response.statusCode());
        return obj(CompositionIO.parseJson(response.body()));
    }
    static String submittedTask(Map<String,Object> response)throws IOException{
        if(number(response.get("code"))!=200)throw new IOException("ACE_SUBMIT_REJECTED");
        String id=string(obj(response.get("data")).get("task_id"));
        if(!id.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("ACE_TASK_ID_INVALID");
        return id;
    }
    static String completedAudioPath(Map<String,Object> response)throws IOException{
        if(number(response.get("code"))!=200)throw new IOException("ACE_RESULT_REJECTED");
        if(!(response.get("data") instanceof List<?> entries)||entries.size()!=1)
            throw new IOException("ACE_RESULT_COUNT_INVALID");
        Map<String,Object> task=obj(entries.get(0));
        int status=number(task.get("status"));
        if(status==0)return null;
        if(status!=1)throw new IOException("ACE_TASK_FAILED");
        Object raw=task.get("result");
        Object parsed=raw instanceof String s?CompositionIO.parseJson(s):raw;
        if(!(parsed instanceof List<?> files)||files.isEmpty())throw new IOException("ACE_AUDIO_RESULT_EMPTY");
        Map<String,Object> audio=obj(files.get(0));
        String location=string(audio.get("file"));
        if(!location.startsWith("/v1/audio?path=")||location.contains("#"))
            throw new IOException("ACE_AUDIO_URL_UNTRUSTED");
        return location;
    }
    private static void invoke(List<String> args,int timeout)throws IOException {
        try{
            Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
            byte[] output=process.getInputStream().readNBytes(8192);
            if(!process.waitFor(timeout,TimeUnit.SECONDS)){
                process.destroyForcibly();throw new IOException("ACE_AUDIO_FFMPEG_TIMEOUT");
            }
            if(process.exitValue()!=0)throw new IOException("ACE_AUDIO_FFMPEG_FAILED: "+
                new String(output,StandardCharsets.UTF_8));
        }catch(InterruptedException ex){
            Thread.currentThread().interrupt();
            throw new IOException("ACE_AUDIO_FFMPEG_INTERRUPTED",ex);
        }
    }
    public static Audio.Stats generate(Request req,Path wav)throws IOException{
        URI base=endpoint();
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        Path tmp=Files.createTempDirectory("chordflow-ace-");
        try{
            String task;
            try{task=submittedTask(post(client,base,"release_task",payload(req)));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("ACE_CANCELLED",e);}
            String audioPath=null;
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(12);
            while(System.nanoTime()<deadline){
                if(Thread.currentThread().isInterrupted())throw new IOException("ACE_CANCELLED");
                try{
                    Thread.sleep(2000);
                    Map<String,Object> response=post(client,base,"query_result",
                        "{\"task_id_list\":["+escape(task)+"]}");
                    audioPath=completedAudioPath(response);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();throw new IOException("ACE_CANCELLED",e);
                }
                if(audioPath!=null)break;
            }
            if(audioPath==null)throw new IOException("ACE_TASK_TIMEOUT");
            URI location=base.resolve(audioPath);
            if(!Objects.equals(location.getHost(),base.getHost())||
                !Objects.equals(location.getPort(),base.getPort())||
                !"/v1/audio".equals(location.getPath()))
                throw new IOException("ACE_AUDIO_DOWNLOAD_NOT_LOOPBACK");
            HttpRequest get=HttpRequest.newBuilder(location).GET().timeout(Duration.ofSeconds(90)).build();
            Path downloaded=tmp.resolve("generated.wav");
            try{
                HttpResponse<InputStream> result=client.send(get,HttpResponse.BodyHandlers.ofInputStream());
                if(result.statusCode()!=200)throw new IOException("ACE_AUDIO_DOWNLOAD_HTTP_"+result.statusCode());
                try(InputStream in=result.body();OutputStream out=Files.newOutputStream(downloaded)){
                    byte[] buffer=new byte[65536];long total=0;int n;
                    while((n=in.read(buffer))>=0){
                        total+=n;
                        if(total>160_000_000)throw new IOException("ACE_AUDIO_TOO_LARGE");
                        out.write(buffer,0,n);
                    }
                    if(total<4096)throw new IOException("ACE_AUDIO_EMPTY");
                }
            }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException("ACE_CANCELLED",ex);}
            Files.createDirectories(wav.toAbsolutePath().getParent());
            String seconds=String.valueOf(req.seconds());
            invoke(List.of("ffmpeg","-hide_banner","-loglevel","error","-y",
                "-i",downloaded.toString(),"-af","atrim=0:"+seconds+
                ",apad=whole_dur="+seconds,"-t",seconds,"-ar","48000",
                "-ac","2","-c:a","pcm_s16le",wav.toString()),120);
            return SampledAudio.stats(wav);
        }finally{
            try(var walk=Files.walk(tmp)){
                walk.sorted(Comparator.reverseOrder()).forEach(p->{
                    try{Files.deleteIfExists(p);}catch(IOException ignored){}
                });
            }
        }
    }
}
