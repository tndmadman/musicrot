package chordflow;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;

/** Fail-closed FFmpeg streaming pipeline: no READY path before final media audit. */
public final class Production {
    public record Result(Path video,Path manifest,String sha256,Audio.Stats audio,String encoder,long elapsedMillis) {}
    public static Result render(Composition c,Path out,int width,int height,int fps,String encoder)throws Exception{
        if(width<160||height<160||fps<10||fps>60||width%2!=0||height%2!=0)throw new IllegalArgumentException("INVALID_RENDER_SPEC");
        if(!has("ffmpeg")||!has("ffprobe"))throw new IOException("DEPENDENCY_UNAVAILABLE: ffmpeg/ffprobe");
        long begin=System.nanoTime();Files.createDirectories(out);String job="chordflow-"+c.seed+"-"+c.mode+"-"+width;
        Path finalVideo=out.resolve(job+".mp4"),finalManifest=out.resolve(job+".manifest.json");
        if(Files.exists(finalVideo))throw new IOException("OUTPUT_EXISTS: "+finalVideo);
        Path temp=Files.createTempDirectory(out,".cf-partial-");
        try{
            Path midi=temp.resolve("composition.mid"),json=temp.resolve("composition.json"),wav=temp.resolve("audio.wav"),candidate=temp.resolve("candidate.mp4");
            c.save(json);Music.writeMidi(c,midi);Audio.Stats audio=Audio.synth(c,wav);
            List<String> encoders=encoder.equals("auto")?List.of("h264_nvenc","libx264"):List.of(encoder.equals("nvenc")?"h264_nvenc":"libx264");
            String chosen=null;List<String> failures=new ArrayList<>();
            for(String enc:encoders){
                try{encode(c,wav,candidate,temp.resolve("ffmpeg-"+enc+".log"),width,height,fps,enc);audit(candidate,c.duration,width,height,fps);chosen=enc;break;}
                catch(Exception e){failures.add(enc+": "+e.getMessage());Files.deleteIfExists(candidate);if(enc.equals("libx264")||!encoder.equals("auto"))throw new IOException("ENCODE_FAILED: "+failures,e);}
            }
            if(chosen==null)throw new IOException("NO_VALID_ENCODER: "+failures);
            String sha=hash(candidate);String manifest="{\"schemaVersion\":1,\"sha256\":\""+sha+"\",\"seed\":"+c.seed+",\"mode\":\""+c.mode+"\",\"encoder\":\""+chosen+"\",\"audioRms\":"+audio.rms()+",\"durationSeconds\":"+c.duration+",\"verified\":true,\"license\":\""+c.rights+"\"}\n";
            Path manifestTemp=temp.resolve("manifest.json");Files.writeString(manifestTemp,manifest);
            // No partial video or false READY status: both QA and checksum precede promotion.
            Files.move(manifestTemp,finalManifest,StandardCopyOption.ATOMIC_MOVE);Files.move(candidate,finalVideo,StandardCopyOption.ATOMIC_MOVE);
            // Preserve canonical sources for reproducibility. No copy of reference audio or art.
            Files.copy(json,out.resolve(job+".composition.json"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(midi,out.resolve(job+".mid"),StandardCopyOption.REPLACE_EXISTING);
            return new Result(finalVideo,finalManifest,sha,audio,chosen,(System.nanoTime()-begin)/1_000_000);
        }finally{try(var files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
    }
    private static boolean has(String command){try{Process p=new ProcessBuilder(command,"-version").redirectErrorStream(true).start();p.getInputStream().transferTo(OutputStream.nullOutputStream());return p.waitFor()==0;}catch(Exception e){return false;}}
    private static void encode(Composition c,Path wav,Path candidate,Path log,int w,int h,int fps,String encoder)throws Exception{
        List<String> cmd=new ArrayList<>(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-f","rawvideo","-pix_fmt","bgr24","-video_size",w+"x"+h,"-framerate",String.valueOf(fps),"-i","pipe:0","-i",wav.toString(),"-map","0:v:0","-map","1:a:0","-c:v",encoder));
        if(encoder.equals("h264_nvenc"))cmd.addAll(List.of("-preset","p5","-cq","23","-b:v","0"));else cmd.addAll(List.of("-preset","veryfast","-crf","20"));
        cmd.addAll(List.of("-pix_fmt","yuv420p","-r",String.valueOf(fps),"-frames:v",String.valueOf((int)Math.round(c.duration*fps)),"-c:a","aac","-ar","48000","-ac","2","-b:a","192k","-movflags","+faststart",candidate.toString()));
        Process p=new ProcessBuilder(cmd).redirectError(log.toFile()).start();Visual visual=new Visual(c,w,h,fps);Exception error=null;
        try(OutputStream sink=new BufferedOutputStream(p.getOutputStream(),1<<20)){int frames=(int)Math.round(c.duration*fps);for(int i=0;i<frames;i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("RENDER_CANCELLED");sink.write(visual.frame(i));}}
        catch(Exception e){error=e;p.destroyForcibly();}
        if(!p.waitFor(45,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("FFMPEG_TIMEOUT");}
        if(error!=null)throw error;
        if(p.exitValue()!=0)throw new IOException("FFMPEG_EXIT_"+p.exitValue()+" "+Files.readString(log));
    }
    public static void audit(Path video,double expected,int width,int height,int fps)throws Exception{
        if(!Files.isRegularFile(video)||Files.size(video)<10_000)throw new IOException("VIDEO_EMPTY_OR_TRUNCATED");
        Process p=new ProcessBuilder("ffprobe","-v","error","-show_entries","stream=codec_name,codec_type,width,height,pix_fmt,avg_frame_rate,nb_frames:format=duration","-of","json",video.toString()).start();String info=new String(p.getInputStream().readAllBytes());String err=new String(p.getErrorStream().readAllBytes());if(p.waitFor()!=0)throw new IOException("FFPROBE_FAILED "+err);
        if(!info.contains("\"codec_name\": \"h264\"")||!info.contains("\"codec_name\": \"aac\"")||!info.contains("\"pix_fmt\": \"yuv420p\""))throw new IOException("VIDEO_CODEC_QA_FAILED: "+info);
        if(!info.contains("\"width\": "+width)||!info.contains("\"height\": "+height))throw new IOException("VIDEO_DIMENSION_QA_FAILED");
        Matcher m=Pattern.compile("\\\"duration\\\"\\s*:\\s*\\\"([0-9.]+)\\\"").matcher(info);if(!m.find()||Math.abs(Double.parseDouble(m.group(1))-expected)>.20)throw new IOException("VIDEO_DURATION_QA_FAILED: "+info);
        Matcher f=Pattern.compile("\\\"nb_frames\\\"\\s*:\\s*\\\"(\\d+)\\\"").matcher(info);if(f.find()&&Math.abs(Integer.parseInt(f.group(1))-Math.round(expected*fps))>1)throw new IOException("VIDEO_FRAME_COUNT_QA_FAILED");
        // Full decode catches corrupt frame sequences, not just broken headers.
        Process decode=new ProcessBuilder("ffmpeg","-v","error","-xerror","-i",video.toString(),"-f","null","-").start();decode.getInputStream().transferTo(OutputStream.nullOutputStream());String decodeErr=new String(decode.getErrorStream().readAllBytes());if(decode.waitFor()!=0)throw new IOException("VIDEO_DECODE_FAILED "+decodeErr);
    }
    public static String hash(Path file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>0)md.update(buffer,0,n);}return HexFormat.of().formatHex(md.digest());}
}
