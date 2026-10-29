package chordflow;

import java.util.*;
import java.nio.file.*;
import java.io.*;

/** Canonical note events are the only timing/pitch authority for MIDI, audio and screen. */
public final class Composition {
    public static final int VERSION=1;
    public record Note(String voice,int pitch,double start,double duration,int velocity) {}
    public record Chord(double start,double duration,String symbol,int[] pitches,int[] frets) {}
    public final long seed;
    public final String title,mode,theme,rights,key,scaleMode;
    public final double bpm,duration;
    public final List<Chord> chords;
    public final List<Note> notes;
    public Composition(long seed,String title,String mode,String theme,String rights,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this(seed,title,mode,theme,rights,"unclassified","unclassified",bpm,duration,chords,notes);
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this.seed=seed;this.title=title;this.mode=mode;this.theme=theme;this.rights=rights;this.key=key;this.scaleMode=scaleMode;this.bpm=bpm;this.duration=duration;
        this.chords=List.copyOf(chords);this.notes=List.copyOf(notes);validate();
    }
    public void validate(){
        if(!Double.isFinite(duration)||duration<=0||!Double.isFinite(bpm)||bpm<35||bpm>220||!Set.of("flow","guitar").contains(mode)||rights==null||rights.isBlank())throw new IllegalArgumentException("COMPOSITION_INVALID_METADATA");
        if(chords.isEmpty()||notes.isEmpty())throw new IllegalArgumentException("COMPOSITION_EMPTY");
        double previous=-1;
        for(Chord c:chords){
            if(c.symbol()==null||c.symbol().isBlank()||!Double.isFinite(c.start())||!Double.isFinite(c.duration())||c.start()<0||c.start()<=previous||c.duration()<=0||c.start()+c.duration()>duration+0.04||c.pitches().length==0)throw new IllegalArgumentException("CHORD_INVALID");
            previous=c.start();
            for(int p:c.pitches())if(p<0||p>127)throw new IllegalArgumentException("CHORD_PITCH_INVALID");
            if(c.frets()!=null){
                if(c.frets().length!=6)throw new IllegalArgumentException("GUITAR_INVALID_STRINGS");
                int[] tune={40,45,50,55,59,64}; List<Integer> sounding=new ArrayList<>();
                for(int i=0;i<6;i++){int f=c.frets()[i];if(f < -1||f>24)throw new IllegalArgumentException("GUITAR_INVALID_FRET");if(f>=0)sounding.add(tune[i]+f);}
                int[] got=sounding.stream().mapToInt(Integer::intValue).sorted().toArray();int[] want=c.pitches().clone();Arrays.sort(want);
                if(!Arrays.equals(got,want))throw new IllegalArgumentException("GUITAR_MISMATCH");
            }
        }
        for(Note n:notes)if(n.pitch()<0||n.pitch()>127||n.velocity()<1||n.velocity()>127||n.start()<0||n.duration()<=0||n.start()+n.duration()>duration+0.04||!Double.isFinite(n.start())||!Double.isFinite(n.duration()))throw new IllegalArgumentException("NOTE_INVALID");
        for(Chord c:chords){
            List<Integer> active=new ArrayList<>();for(Note n:notes)if(n.start()<=c.start()+0.001&&n.start()+n.duration()>c.start()+0.002)active.add(n.pitch());
            int[] got=active.stream().mapToInt(Integer::intValue).sorted().toArray();int[] want=c.pitches().clone();Arrays.sort(want);
            if(!Arrays.equals(got,want))throw new IllegalArgumentException("NOTES_CHORD_MISMATCH: "+c.symbol());
        }
    }
    public Chord at(double seconds){Chord active=chords.get(0);for(Chord c:chords){if(c.start()<=seconds+1e-9)active=c;else break;}return active;}
    public static String pitchName(int midi){String[] names={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};return names[midi%12]+(midi/12-1);}
    public String json(){
        StringBuilder b=new StringBuilder();b.append("{\"schemaVersion\":1,\"seed\":").append(seed).append(",\"title\":").append(q(title)).append(",\"key\":").append(q(key)).append(",\"scaleMode\":").append(q(scaleMode)).append(",\"mode\":").append(q(mode)).append(",\"theme\":").append(q(theme)).append(",\"rights\":").append(q(rights)).append(",\"bpm\":").append(bpm).append(",\"durationSeconds\":").append(duration).append(",\"meter\":\"4/4\",\"chordEvents\":[");
        for(int i=0;i<chords.size();i++){Chord c=chords.get(i);if(i>0)b.append(',');b.append("{\"startSeconds\":").append(c.start()).append(",\"durationSeconds\":").append(c.duration()).append(",\"symbol\":").append(q(c.symbol())).append(",\"midiPitches\":").append(Arrays.toString(c.pitches())).append(",\"frets\":").append(c.frets()==null?"null":Arrays.toString(c.frets())).append('}');}
        b.append("],\"noteEvents\":[");for(int i=0;i<notes.size();i++){Note n=notes.get(i);if(i>0)b.append(',');b.append("{\"voiceId\":").append(q(n.voice())).append(",\"midiPitch\":").append(n.pitch()).append(",\"startSeconds\":").append(n.start()).append(",\"durationSeconds\":").append(n.duration()).append(",\"velocity\":").append(n.velocity()).append('}');}return b.append("]}\n").toString();
    }
    private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\"";}
    public void save(Path path)throws IOException{Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,json());}
}
