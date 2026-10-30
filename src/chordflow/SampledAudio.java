package chordflow;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Optional real sampled-instrument renderer using a locally installed FluidSynth
 * executable and a user-provided legitimately licensed SF2 sound bank. Never
 * downloads or redistributes samples. Explicit sampled mode fails closed.
 */
public final class SampledAudio {
    private SampledAudio() {}
    public static boolean configured(){
        String bank=System.getenv("CHORDFLOW_SOUNDFONT");
        return bank!=null&&!bank.isBlank();
    }
    public static Audio.Stats synth(Composition c,Path wav)throws IOException{
        String bank=System.getenv("CHORDFLOW_SOUNDFONT");
        if(bank==null||bank.isBlank())throw new IOException(
            "SOUNDFONT_NOT_CONFIGURED: set CHORDFLOW_SOUNDFONT to a licensed .sf2 file");
        Path sf=Path.of(bank).toAbsolutePath();
        if(!Files.isRegularFile(sf)||!sf.toString().toLowerCase(Locale.ROOT).endsWith(".sf2"))
            throw new IOException("SOUNDFONT_NOT_FOUND: "+sf);
        String executable=System.getenv().getOrDefault("CHORDFLOW_FLUIDSYNTH","fluidsynth");
        Path work=Files.createTempDirectory("chordflow-sf2-");
        try{
            Path midi=work.resolve("composition.mid"),raw=work.resolve("samples.wav");
            Music.writeMidi(c,midi);
            invoke(List.of(executable,"-ni","-F",raw.toString(),"-r","48000",
                sf.toString(),midi.toString()),work.resolve("fluidsynth.log"),180);
            if(!Files.isRegularFile(raw)||Files.size(raw)<1000)
                throw new IOException("SAMPLED_RENDER_EMPTY");
            String duration=String.format(Locale.ROOT,"%.6f",c.duration);
            invoke(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-i",raw.toString(),
                "-af","atrim=0:"+duration+",apad=whole_dur="+duration,
                "-t",duration,"-c:a","pcm_s16le","-ar","48000","-ac","2",wav.toString()),
                work.resolve("ffmpeg.log"),120);
            return stats(wav);
        }finally{
            try(var walk=Files.walk(work)){
                walk.sorted(Comparator.reverseOrder()).forEach(p->{
                    try{Files.deleteIfExists(p);}catch(IOException ignored){}
                });
            }
        }
    }
    private static void invoke(List<String> args,Path log,int timeout)throws IOException{
        Process p;
        try{
            p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!p.waitFor(timeout,TimeUnit.SECONDS)){
                p.destroyForcibly();throw new IOException("SAMPLED_AUDIO_TIMEOUT: "+args.get(0));
            }
            if(p.exitValue()!=0){
                String details=Files.exists(log)?Files.readString(log):"";
                throw new IOException("SAMPLED_AUDIO_TOOL_FAILED: "+args.get(0)+" exit="+p.exitValue()+
                    " "+details.substring(0,Math.min(800,details.length())));
            }
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();throw new IOException("SAMPLED_AUDIO_INTERRUPTED",e);
        }catch(IOException e){
            throw new IOException("SAMPLED_AUDIO_DEPENDENCY_OR_RENDER_FAILED: "+e.getMessage(),e);
        }
    }
    public static Audio.Stats stats(Path wav)throws IOException{
        try(AudioInputStream in=AudioSystem.getAudioInputStream(wav.toFile())){
            AudioFormat f=in.getFormat();
            if(f.getEncoding()!=AudioFormat.Encoding.PCM_SIGNED||f.getChannels()!=2||
               f.getSampleSizeInBits()!=16||f.isBigEndian()||f.getFrameSize()!=4)
                throw new IOException("SAMPLED_AUDIO_PCM_FORMAT_INVALID: "+f);
            byte[] b=new byte[32768];
            long frames=0,silent=0;double sum=0,peak=0;int n;
            while((n=in.read(b))>0){
                if(n%4!=0)throw new IOException("SAMPLED_PCM_TRUNCATED");
                for(int i=0;i<n;i+=4){
                    short l=(short)((b[i]&255)|(b[i+1]<<8));
                    short r=(short)((b[i+2]&255)|(b[i+3]<<8));
                    double a=l/32768.0,d=r/32768.0;
                    peak=Math.max(peak,Math.max(Math.abs(a),Math.abs(d)));
                    sum+=(a*a+d*d)/2;frames++;
                    if(Math.abs(a)+Math.abs(d)<.00045)silent++;
                }
            }
            if(frames==0)throw new IOException("SAMPLED_AUDIO_EMPTY");
            Audio.Stats stats=new Audio.Stats(peak,Math.sqrt(sum/frames),(double)silent/frames);
            if(stats.rms()<.007||stats.peak()>.98||stats.silenceFraction()>.60)
                throw new IOException("SAMPLED_AUDIO_QA_FAILED: "+stats);
            return stats;
        }catch(UnsupportedAudioFileException e){
            throw new IOException("SAMPLED_AUDIO_FORMAT_UNSUPPORTED",e);
        }
    }
}
