package chordflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.*;

/** Optional per-MIDI-lane sample rendering and balanced stem mixing. */
public final class StemMixer {
    private StemMixer(){}
    private static void command(List<String> args,Path log,int seconds)throws IOException{
        try{
            Process process=new ProcessBuilder(args).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
            if(!process.waitFor(seconds,TimeUnit.SECONDS)){
                process.destroyForcibly();throw new IOException("STEM_RENDER_TIMEOUT");
            }
            if(process.exitValue()!=0){
                String error=Files.isRegularFile(log)?Files.readString(log):"";
                throw new IOException("STEM_TOOL_FAILED: "+error.substring(0,Math.min(error.length(),2000)));
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("STEM_RENDER_INTERRUPTED",e);}
    }
    private static boolean sounds(Track track){
        for(int i=0;i<track.size();i++)
            if(track.get(i).getMessage() instanceof ShortMessage m&&
                m.getCommand()==ShortMessage.NOTE_ON&&m.getData2()>0)return true;
        return false;
    }
    private static void copy(Track source,Track dest){
        for(int i=0;i<source.size();i++){
            MidiEvent event=source.get(i);
            dest.add(new MidiEvent(event.getMessage(),event.getTick()));
        }
    }
    /** Input MIDI remains canonical; exactly the existing notes are in the stems. */
    public static void render(Path midi,Path soundbank,String synth,Path out,
                              int seconds,Path temp)throws Exception{
        Sequence sequence=MidiSystem.getSequence(midi.toFile());
        Track[] tracks=sequence.getTracks();
        if(tracks.length<2)throw new IOException("STEMS_NEED_INSTRUMENT_TRACKS");
        List<Path> stems=new ArrayList<>();
        int lane=0;
        for(int i=1;i<tracks.length;i++){
            if(!sounds(tracks[i]))continue;
            Sequence single=new Sequence(Sequence.PPQ,sequence.getResolution());
            copy(tracks[0],single.createTrack());
            copy(tracks[i],single.createTrack());
            Path part=temp.resolve("part-"+lane+".mid");
            Path sample=temp.resolve("part-"+lane+".wav");
            MidiSystem.write(single,1,part.toFile());
            command(List.of(synth,"-ni","-F",sample.toString(),"-r","48000",
                soundbank.toString(),part.toString()),temp.resolve("synth-"+lane+".log"),180);
            if(!Files.isRegularFile(sample)||Files.size(sample)<512)
                throw new IOException("STEM_EMPTY: "+lane);
            stems.add(sample);lane++;
        }
        if(stems.isEmpty()||stems.size()>15)throw new IOException("STEM_COUNT_INVALID");
        List<String> cmd=new ArrayList<>(List.of("ffmpeg","-hide_banner","-loglevel",
            "error","-y"));
        for(Path stem:stems){cmd.add("-i");cmd.add(stem.toString());}
        StringBuilder graph=new StringBuilder();
        for(int i=0;i<stems.size();i++){
            // Headroom remains even for layered arrangements; avoid hard panning.
            graph.append("[").append(i).append(":a]volume=0.82,highpass=f=30[s")
                .append(i).append("];");
        }
        for(int i=0;i<stems.size();i++)graph.append("[s").append(i).append("]");
        graph.append("amix=inputs=").append(stems.size())
            .append(":duration=longest:normalize=0,alimiter=limit=0.94:level=0");
        graph.append(",atrim=0:").append(seconds)
            .append(",apad=whole_dur=").append(seconds).append("[mix]");
        cmd.addAll(List.of("-filter_complex",graph.toString(),"-map","[mix]",
            "-t",Integer.toString(seconds),"-ar","48000","-ac","2",
            "-c:a","pcm_s16le",out.toString()));
        command(cmd,temp.resolve("mix.log"),180);
    }
}
