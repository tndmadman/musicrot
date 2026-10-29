package chordflow;

import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args)throws Exception{
        if(args.length==0){help();return;}
        switch(args[0]){
            case "render"->{long seed=Long.parseLong(option(args,"--seed","101"));int seconds=Integer.parseInt(option(args,"--seconds","60")),width=Integer.parseInt(option(args,"--width","1080")),height=Integer.parseInt(option(args,"--height","1920")),fps=Integer.parseInt(option(args,"--fps","30"));String mode=option(args,"--mode","flow"),theme=option(args,"--theme","gold"),encoder=option(args,"--encoder","auto");Path out=Path.of(option(args,"--out","output"));Composition c=Music.generate(seed,seconds,mode,theme);Production.Result r=Production.render(c,out,width,height,fps,encoder);System.out.println("READY "+r.video()+" sha256="+r.sha256()+" encoder="+r.encoder()+" elapsed_ms="+r.elapsedMillis()+" rms="+r.audio().rms());}
            case "compose"->{Composition c=Music.generate(Long.parseLong(option(args,"--seed","101")),Integer.parseInt(option(args,"--seconds","60")),option(args,"--mode","flow"),option(args,"--theme","gold"));Path path=Path.of(option(args,"--out","composition.json"));c.save(path);Music.writeMidi(c,path.resolveSibling(path.getFileName()+".mid"));System.out.println("COMPOSITION_READY "+path);}
            case "import-midi"->{Path source=Path.of(option(args,"--input",""));if(!Files.isRegularFile(source))throw new IllegalArgumentException("--input MIDI file required");Composition c=Music.importMidi(source,option(args,"--rights",""),Long.parseLong(option(args,"--seed","1")),Integer.parseInt(option(args,"--seconds","60")),option(args,"--theme","gold"));c.save(Path.of(option(args,"--out","imported.composition.json")));System.out.println("MIDI_IMPORTED "+c.notes.size());}
            case "import-midi-render"->{Path source=Path.of(option(args,"--input",""));if(!Files.isRegularFile(source))throw new IllegalArgumentException("--input MIDI file required");Composition c=Music.importMidi(source,option(args,"--rights",""),Long.parseLong(option(args,"--seed","1")),Integer.parseInt(option(args,"--seconds","60")),option(args,"--theme","gold"));Production.Result r=Production.render(c,Path.of(option(args,"--out","output")),Integer.parseInt(option(args,"--width","1080")),Integer.parseInt(option(args,"--height","1920")),Integer.parseInt(option(args,"--fps","30")),option(args,"--encoder","auto"));System.out.println("READY_LICENSED_MIDI "+r.video()+" sha256="+r.sha256());}
            case "serve"->QueueServer.serve(Integer.parseInt(option(args,"--port","8791")),Path.of(option(args,"--root","chordflow-data")));
            case "worker"->QueueServer.worker(option(args,"--url","http://localhost:8791"),option(args,"--id",UUID.randomUUID().toString()),Path.of(option(args,"--out","chordflow-data/output")));
            case "selftest"->SelfTest.run();
            default->help();
        }
    }
    private static String option(String[] args,String name,String fallback){for(int i=1;i<args.length-1;i++)if(args[i].equals(name))return args[i+1];return fallback;}
    private static void help(){System.out.println("ChordFlow Java 21 CLI: render [--seed N --seconds 60 --mode flow|guitar --theme gold|blue|violet --width 1080 --height 1920 --fps 30 --encoder auto|nvenc|x264 --out output], compose, import-midi --input PATH --rights LICENSE, import-midi-render --input PATH --rights LICENSE --seconds 60, serve [--port 8791], worker [--url URL], selftest");}
}
