package chordflow;

import java.util.*;
import java.nio.file.*;
import java.io.*;

/** Canonical note events are the only timing/pitch authority for MIDI, audio and screen. */
public final class Composition {
    public static final int VERSION=2;
    public record Tempo(double beat,double bpm) {}
    public record Note(String voice,int pitch,double start,double duration,int velocity) {}
    /** Defensive copies prevent later changes to array-backed notes/fingerings. */
    public record Chord(double start,double duration,String symbol,int[] pitches,int[] frets) {
        public Chord {pitches=Objects.requireNonNull(pitches).clone();frets=frets==null?null:frets.clone();}
        @Override public int[] pitches(){return pitches.clone();}
        @Override public int[] frets(){return frets==null?null:frets.clone();}
    }
    public final long seed;
    public final String title,mode,theme,rights,key,scaleMode;
    public final double bpm,duration;
    /** Beat-to-second map is canonical for imported changing-tempo MIDI. */
    public final List<Tempo> tempoMap;
    public final List<Chord> chords;
    public final List<Note> notes;
    public Composition(long seed,String title,String mode,String theme,String rights,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this(seed,title,mode,theme,rights,"unclassified","unclassified",bpm,duration,chords,notes);
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this(seed,title,mode,theme,rights,key,scaleMode,bpm,duration,chords,notes,List.of(new Tempo(0,bpm)));
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes,List<Tempo> tempos){
        this.seed=seed;this.title=title;this.mode=mode;this.theme=theme;this.rights=rights;this.key=key;this.scaleMode=scaleMode;this.bpm=bpm;this.duration=duration;
        this.chords=List.copyOf(chords);this.notes=List.copyOf(notes);this.tempoMap=List.copyOf(tempos);validate();
    }
    public double secondsAtBeat(double beat){
        if(beat<0||!Double.isFinite(beat))throw new IllegalArgumentException("INVALID_BEAT");
        double seconds=0;
        for(int i=0;i<tempoMap.size();i++){
            Tempo t=tempoMap.get(i);double until=i+1<tempoMap.size()?tempoMap.get(i+1).beat():beat;
            double delta=Math.max(0,Math.min(beat,until)-t.beat());seconds+=delta*60/t.bpm();
            if(beat<=until)break;
        }
        return seconds;
    }
    public double beatAtSeconds(double seconds){
        if(seconds<0||!Double.isFinite(seconds))throw new IllegalArgumentException("INVALID_SECONDS");
        double remaining=seconds;
        for(int i=0;i<tempoMap.size();i++){
            Tempo t=tempoMap.get(i);double length=i+1<tempoMap.size()?(tempoMap.get(i+1).beat()-t.beat())*60/t.bpm():Double.POSITIVE_INFINITY;
            if(remaining<=length)return t.beat()+remaining*t.bpm()/60;
            remaining-=length;
        }
        throw new IllegalStateException("TEMPO_MAP_INCOMPLETE");
    }
    public void validate(){
        if(tempoMap.isEmpty()||tempoMap.get(0).beat()!=0)throw new IllegalArgumentException("TEMPO_MAP_MISSING_START");
        double priorBeat=-1;
        for(Tempo t:tempoMap){if(!Double.isFinite(t.beat())||t.beat()<=priorBeat||!Double.isFinite(t.bpm())||t.bpm()<20||t.bpm()>300)throw new IllegalArgumentException("TEMPO_MAP_INVALID");priorBeat=t.beat();}
        if(!Double.isFinite(duration)||duration<=0||!Double.isFinite(bpm)||bpm<35||bpm>220||!Set.of("flow","guitar").contains(mode)||rights==null||rights.isBlank())throw new IllegalArgumentException("COMPOSITION_INVALID_METADATA");
        if(chords.isEmpty()||notes.isEmpty())throw new IllegalArgumentException("COMPOSITION_EMPTY");
        double previous=-1;
        for(Chord c:chords){
            if(c.symbol()==null||c.symbol().isBlank()||!Double.isFinite(c.start())||!Double.isFinite(c.duration())||c.start()<0||c.start()<=previous||c.duration()<=0||c.start()+c.duration()>duration+0.04||(c.pitches().length==0&&!c.symbol().equals("Rest")))throw new IllegalArgumentException("CHORD_INVALID");
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
        // Validate every interval where the sounding multiset can change, not just chord starts.
        TreeSet<Double> boundaries=new TreeSet<>();boundaries.add(0.0);boundaries.add(duration);
        for(Chord c:chords){boundaries.add(c.start());boundaries.add(c.start()+c.duration());}
        for(Note n:notes){boundaries.add(n.start());boundaries.add(n.start()+n.duration());}
        List<Double> cuts=new ArrayList<>(boundaries);
        for(int i=0;i+1<cuts.size();i++){
            double mid=(cuts.get(i)+cuts.get(i+1))/2;
            if(cuts.get(i+1)-cuts.get(i)<1e-8)continue;
            Chord chord=at(mid);
            if(mid>=chord.start()+chord.duration()+1e-7)throw new IllegalArgumentException("CHORD_TIMELINE_GAP");
            int[] expected=chord.pitches();Arrays.sort(expected);
            int[] actual=notes.stream().filter(n->n.start()<=mid&&n.start()+n.duration()>mid).mapToInt(Note::pitch).sorted().toArray();
            if(!Arrays.equals(actual,expected))throw new IllegalArgumentException("NOTES_CHORD_MISMATCH: "+chord.symbol()+" @"+mid);
        }
        if(chords.get(0).start()>1e-7||chords.get(chords.size()-1).start()+chords.get(chords.size()-1).duration()<duration-1e-7)
            throw new IllegalArgumentException("CHORD_TIMELINE_INCOMPLETE");
    }
    public Chord at(double seconds){Chord active=chords.get(0);for(Chord c:chords){if(c.start()<=seconds+1e-9)active=c;else break;}return active;}
    public static String pitchName(int midi){String[] names={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};return names[midi%12]+(midi/12-1);}
    public String json(){
        StringBuilder b=new StringBuilder();b.append("{\"schemaVersion\":2,\"seed\":").append(seed).append(",\"title\":").append(q(title)).append(",\"key\":").append(q(key)).append(",\"scaleMode\":").append(q(scaleMode)).append(",\"mode\":").append(q(mode)).append(",\"theme\":").append(q(theme)).append(",\"rights\":").append(q(rights)).append(",\"bpm\":").append(bpm).append(",\"durationSeconds\":").append(duration).append(",\"meter\":\"4/4\",\"chordEvents\":[");
        for(int i=0;i<chords.size();i++){Chord c=chords.get(i);if(i>0)b.append(',');b.append("{\"startSeconds\":").append(c.start()).append(",\"durationSeconds\":").append(c.duration()).append(",\"symbol\":").append(q(c.symbol())).append(",\"midiPitches\":").append(Arrays.toString(c.pitches())).append(",\"frets\":").append(c.frets()==null?"null":Arrays.toString(c.frets())).append('}');}
        b.append("],\"noteEvents\":[");for(int i=0;i<notes.size();i++){Note n=notes.get(i);if(i>0)b.append(',');b.append("{\"voiceId\":").append(q(n.voice())).append(",\"midiPitch\":").append(n.pitch()).append(",\"startSeconds\":").append(n.start()).append(",\"durationSeconds\":").append(n.duration()).append(",\"velocity\":").append(n.velocity()).append('}');}b.append("],\"tempoMap\":[");for(int i=0;i<tempoMap.size();i++){Tempo t=tempoMap.get(i);if(i>0)b.append(',');b.append("{\"beat\":").append(t.beat()).append(",\"bpm\":").append(t.bpm()).append('}');}return b.append("]}\n").toString();
    }
    private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\"";}
    public void save(Path path)throws IOException{Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,json());}
}
