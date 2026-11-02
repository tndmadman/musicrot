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
        long begin=System.nanoTime();Files.createDirectories(out);String job="chordflow-"+c.seed+"-"+c.mode+"-"+width+"-"+UUID.randomUUID().toString().substring(0,8);
        Path finalVideo=out.resolve(job+".mp4"),finalManifest=out.resolve(job+".manifest.json");
        if(Files.exists(finalVideo))throw new IOException("OUTPUT_EXISTS: "+finalVideo);
        Path temp=Files.createTempDirectory(out,".cf-partial-");
        try{
            Path midi=temp.resolve("composition.mid"),json=temp.resolve("composition.json"),wav=temp.resolve("audio.wav"),candidate=temp.resolve("candidate.mp4");
            c.save(json);Music.writeMidi(c,midi);Audio.Stats audio=Audio.synthProduction(c,wav);
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
            // Promote the manifest LAST: any interrupted promotion has no READY evidence.
            // The controller independently validates its hash, codec and complete decode.
            try{
                Files.copy(json,out.resolve(job+".composition.json"));
                Files.copy(midi,out.resolve(job+".mid"));
                Files.move(candidate,finalVideo,StandardCopyOption.ATOMIC_MOVE);
                Files.move(manifestTemp,finalManifest,StandardCopyOption.ATOMIC_MOVE);
            }catch(Exception promotionFailure){
                Files.deleteIfExists(finalManifest);Files.deleteIfExists(finalVideo);
                Files.deleteIfExists(out.resolve(job+".composition.json"));Files.deleteIfExists(out.resolve(job+".mid"));
                throw promotionFailure;
            }
            return new Result(finalVideo,finalManifest,sha,audio,chosen,(System.nanoTime()-begin)/1_000_000);
        }finally{try(var files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
    }
    /** Neural audio is rendered with verified amplitude visualization only.
     * It does not invent a MIDI composition or chord sheet. */
    public static Result renderNeural(NeuralAudio.Request req,Path out,String theme,
                                      int width,int height,int fps,String encoder)throws Exception{
        Path temp=Files.createTempDirectory("chordflow-neural-source-");
        try{
            Path wav=temp.resolve("neural.wav");
            NeuralAudio.generate(req,wav);
            return renderNeural(req,wav,out,theme,width,height,fps,encoder);
        }finally{
            try(var files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.deleteIfExists(p);}catch(IOException ignored){}
            });}
        }
    }
    /** Worker supplies a previously verified WAV after fingerprint reservation. */
    public static Result renderNeural(NeuralAudio.Request req,Path verifiedWav,Path out,String theme,
                                      int width,int height,int fps,String encoder)throws Exception{
        if(width<160||height<160||fps<10||fps>60||width%2!=0||height%2!=0)
            throw new IllegalArgumentException("INVALID_RENDER_SPEC");
        if(!has("ffmpeg")||!has("ffprobe"))throw new IOException("DEPENDENCY_UNAVAILABLE: ffmpeg/ffprobe");
        long began=System.nanoTime();Files.createDirectories(out);
        String job="chordflow-"+req.seed()+"-flow-"+width+"-"+UUID.randomUUID().toString().substring(0,8);
        Path video=out.resolve(job+".mp4"),manifest=out.resolve(job+".manifest.json");
        Path temp=Files.createTempDirectory(out,".cf-neural-");
        try{
            Path wav=verifiedWav,candidate=temp.resolve("candidate.mp4");
            Audio.Stats stats=SampledAudio.stats(wav);
            AudioQualityReview.requireAcceptable(wav,req.seconds());
            AudioVisual visual=new AudioVisual(wav,width,height,fps,req.seconds(),theme);
            String chosen=null;List<String> errors=new ArrayList<>();
            for(String enc:encoder.equals("auto")?List.of("h264_nvenc","libx264"):
                    List.of(encoder.equals("nvenc")?"h264_nvenc":"libx264")){
                try{
                    encodeFrames(req.seconds(),wav,candidate,temp.resolve("ffmpeg-"+enc+".log"),
                        width,height,fps,enc,visual::frame);
                    audit(candidate,req.seconds(),width,height,fps);
                    chosen=enc;break;
                }catch(Exception failure){
                    errors.add(enc+": "+failure.getMessage());Files.deleteIfExists(candidate);
                    if(enc.equals("libx264")||!encoder.equals("auto"))throw new IOException("NEURAL_VIDEO_ENCODE_FAILED "+errors,failure);
                }
            }
            if(chosen==null)throw new IOException("NEURAL_VIDEO_ENCODER_UNAVAILABLE "+errors);
            String sha=hash(candidate);
            String json="{\"schemaVersion\":2,\"engine\":\"ace-step\",\"seed\":"+req.seed()+
                ",\"style\":\""+NeuralAudio.styleFor(req)+"\",\"durationSeconds\":"+req.seconds()+
                ",\"sha256\":\""+sha+"\",\"encoder\":\""+chosen+
                "\",\"audioRms\":"+stats.rms()+",\"verified\":true}\n";
            Path tempManifest=temp.resolve("manifest.json");Files.writeString(tempManifest,json);
            try{
                Files.move(candidate,video,StandardCopyOption.ATOMIC_MOVE);
                Files.move(tempManifest,manifest,StandardCopyOption.ATOMIC_MOVE);
            }catch(Exception ex){
                Files.deleteIfExists(video);Files.deleteIfExists(manifest);throw ex;
            }
            return new Result(video,manifest,sha,stats,chosen,(System.nanoTime()-began)/1_000_000);
        }finally{
            try(var files=Files.walk(temp)){
                files.sorted(Comparator.reverseOrder()).forEach(p->{
                    try{Files.deleteIfExists(p);}catch(IOException ignored){}
                });
            }
        }
    }
    private static boolean has(String command){try{Process p=new ProcessBuilder(command,"-version").redirectErrorStream(true).start();p.getInputStream().transferTo(OutputStream.nullOutputStream());return p.waitFor()==0;}catch(Exception e){return false;}}
    private static void encode(Composition c,Path wav,Path candidate,Path log,int w,int h,int fps,String encoder)throws Exception{
        Visual visual=new Visual(c,w,h,fps);
        encodeFrames(c.duration,wav,candidate,log,w,h,fps,encoder,visual::frame);
    }
    private static void encodeFrames(double seconds,Path wav,Path candidate,Path log,
                                     int w,int h,int fps,String encoder,
                                     java.util.function.IntFunction<byte[]> framesSource)throws Exception{
        List<String> cmd=new ArrayList<>(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-f","rawvideo","-pix_fmt","bgr24","-video_size",w+"x"+h,"-framerate",String.valueOf(fps),"-i","pipe:0","-i",wav.toString(),"-map","0:v:0","-map","1:a:0","-c:v",encoder));
        if(encoder.equals("h264_nvenc"))cmd.addAll(List.of("-preset","p5","-cq","23","-b:v","0"));else cmd.addAll(List.of("-preset","veryfast","-crf","20"));
        cmd.addAll(List.of("-pix_fmt","yuv420p","-r",String.valueOf(fps),"-frames:v",String.valueOf((int)Math.round(seconds*fps)),"-af","loudnorm=I=-15:TP=-2.5:LRA=11","-c:a","aac","-ar","48000","-ac","2","-b:a","192k","-movflags","+faststart",candidate.toString()));
        Process p=new ProcessBuilder(cmd).redirectError(log.toFile()).start();Exception error=null;
        try(OutputStream sink=new BufferedOutputStream(p.getOutputStream(),1<<20)){int frames=(int)Math.round(seconds*fps);for(int i=0;i<frames;i++){if(Thread.currentThread().isInterrupted())throw new InterruptedException("RENDER_CANCELLED");sink.write(framesSource.apply(i));}}
        catch(Exception e){error=e;p.destroyForcibly();}
        long timeout=Math.max(45,Math.min(900,(long)Math.ceil(seconds*(w>=1080?6.0:3.0))));if(!p.waitFor(timeout,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("FFMPEG_TIMEOUT");}
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
        // Independently decode and inspect the delivered AAC, not merely the input WAV.
        Process audioProbe=new ProcessBuilder("ffmpeg","-hide_banner","-nostats","-i",video.toString(),"-map","0:a:0","-af","ebur128=peak=true","-f","null","-").redirectErrorStream(true).start();
        // Drain merged output while the child runs; separate stderr/stdout reading can deadlock on long videos.
        String loudness=new String(audioProbe.getInputStream().readAllBytes());
        if(audioProbe.waitFor()!=0)throw new IOException("AUDIO_LOUDNESS_PROBE_FAILED");
        int summary=loudness.lastIndexOf("Summary:");if(summary<0)throw new IOException("AAC_LOUDNESS_MISSING");
        String measurements=loudness.substring(summary);
        Matcher lufs=Pattern.compile("\\bI:\\s*(-?[0-9.]+) LUFS").matcher(measurements);
        Matcher peak=Pattern.compile("\\bPeak:\\s*(-?[0-9.]+) dBFS").matcher(measurements);
        if(!lufs.find()||!peak.find())throw new IOException("AAC_LOUDNESS_PARSE_ERROR");
        double integrated=Double.parseDouble(lufs.group(1)),truePeak=Double.parseDouble(peak.group(1));
        if(!Double.isFinite(integrated)||integrated < -24||integrated > -10||!Double.isFinite(truePeak)||truePeak > -1.0)
            throw new IOException("AAC_AUDIO_QA_FAILED lufs="+integrated+" truePeak="+truePeak);
        // Full decode catches corrupt frame sequences, not just broken headers.
        Process decode=new ProcessBuilder("ffmpeg","-v","error","-xerror","-i",video.toString(),"-f","null","-").start();decode.getInputStream().transferTo(OutputStream.nullOutputStream());String decodeErr=new String(decode.getErrorStream().readAllBytes());if(decode.waitFor()!=0)throw new IOException("VIDEO_DECODE_FAILED "+decodeErr);
    }
    public static String hash(Path file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>0)md.update(buffer,0,n);}return HexFormat.of().formatHex(md.digest());}
}
