package chordflow;

import java.util.*;
import java.nio.file.*;
import java.io.*;

/** Canonical note events are the only timing/pitch authority for MIDI, audio and screen. */
public final class Composition {
    public static final int VERSION=2;
    public record Tempo(double beat,double bpm) {}
    public record Note(String voice,int pitch,double start,double duration,int velocity) {}
    /** Harmonic annotations are independent of sounding notes, including strums and melody. */
    public record Harmony(double start,double duration,String symbol,int[] pitches,int[] frets) {
        public Harmony { pitches=Objects.requireNonNull(pitches).clone();frets=frets==null?null:frets.clone(); }
        @Override public int[] pitches(){return pitches.clone();}
        @Override public int[] frets(){return frets==null?null:frets.clone();}
    }
    public record Drum(String kind,double start,int velocity) {}

    /** Defensive copies prevent later changes to array-backed notes/fingerings. */
    public record Chord(double start,double duration,String symbol,int[] pitches,int[] frets) {
        public Chord {pitches=Objects.requireNonNull(pitches).clone();frets=frets==null?null:frets.clone();}
        @Override public int[] pitches(){return pitches.clone();}
        @Override public int[] frets(){return frets==null?null:frets.clone();}
    }
    public final long seed;
    public final String title,mode,theme,rights,key,scaleMode,instrumentPreset;
    public final double bpm,duration;
    /** Beat-to-second map is canonical for imported changing-tempo MIDI. */
    public final List<Tempo> tempoMap;
    public final List<Chord> chords;
    public final List<Note> notes;
    public final List<Harmony> harmony;
    public final List<Drum> drums;
    public final boolean arranged;
    public Composition(long seed,String title,String mode,String theme,String rights,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this(seed,title,mode,theme,rights,"unclassified","unclassified",bpm,duration,chords,notes);
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes){
        this(seed,title,mode,theme,rights,key,scaleMode,bpm,duration,chords,notes,List.of(new Tempo(0,bpm)));
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes,List<Tempo> tempos){
        this(seed,title,mode,theme,rights,key,scaleMode,bpm,duration,chords,notes,tempos,mode.equals("guitar")?"nylon-pluck":"warm-pad");
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,List<Chord> chords,List<Note> notes,List<Tempo> tempos,String preset){
        this(seed,title,mode,theme,rights,key,scaleMode,bpm,duration,chords,notes,tempos,preset,List.of(),List.of(),false);
    }
    public Composition(long seed,String title,String mode,String theme,String rights,String key,String scaleMode,double bpm,double duration,
                       List<Chord> chords,List<Note> notes,List<Tempo> tempos,String preset,
                       List<Harmony> harmony,List<Drum> drums,boolean arranged){
        this.seed=seed;this.title=title;this.mode=mode;this.theme=theme;this.rights=rights;this.key=key;this.scaleMode=scaleMode;this.bpm=bpm;this.duration=duration;
        this.chords=List.copyOf(chords);this.notes=List.copyOf(notes);this.tempoMap=List.copyOf(tempos);this.instrumentPreset=preset;
        this.harmony=List.copyOf(harmony);this.drums=List.copyOf(drums);this.arranged=arranged;validate();
    }
    public Composition withInstrument(String preset){return new Composition(seed,title,mode,theme,rights,key,scaleMode,bpm,duration,chords,notes,tempoMap,preset,harmony,drums,arranged);}
    public static final Set<String> PRESETS=Set.of("warm-pad","soft-keys","nylon-pluck","glass","layered");
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
        if(!PRESETS.contains(instrumentPreset))throw new IllegalArgumentException("INSTRUMENT_PRESET_INVALID");
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
        if(arranged){
            if(harmony.isEmpty())throw new IllegalArgumentException("HARMONY_REQUIRED");
            double lastEnd=0;
            for(Harmony h:harmony){
                if(!Double.isFinite(h.start())||!Double.isFinite(h.duration())||h.duration()<=0||
                   Math.abs(h.start()-lastEnd)>1e-6||h.start()+h.duration()>duration+1e-6||
                   h.symbol()==null||h.symbol().isBlank()||(h.pitches().length==0&&!h.symbol().equals("Rest")))
                   throw new IllegalArgumentException("HARMONY_TIMELINE_INVALID");
                lastEnd=h.start()+h.duration();
                if(h.frets()!=null){
                    int[] tuning={40,45,50,55,59,64};int[] want=h.pitches().clone();Arrays.sort(want);
                    if(h.frets().length!=6)throw new IllegalArgumentException("HARMONY_FRETS_INVALID");
                    List<Integer> sounding=new ArrayList<>();for(int i=0;i<6;i++){int fret=h.frets()[i];if(fret<-1||fret>24)throw new IllegalArgumentException("HARMONY_FRETS_INVALID");if(fret>=0)sounding.add(tuning[i]+fret);}
                    int[] actual=sounding.stream().mapToInt(Integer::intValue).sorted().toArray();
                    if(!Arrays.equals(want,actual))throw new IllegalArgumentException("HARMONY_FRETS_PITCH_MISMATCH");
                }
            }
            if(Math.abs(lastEnd-duration)>1e-6)throw new IllegalArgumentException("HARMONY_TIMELINE_INCOMPLETE");
        }
        for(Drum d:drums)if(!Set.of("kick","snare","hat","open-hat","clap","rim","tom","ride","crash").contains(d.kind())||!Double.isFinite(d.start())||d.start()<0||d.start()>=duration||d.velocity()<1||d.velocity()>127)
            throw new IllegalArgumentException("DRUM_INVALID");
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
    public Harmony harmonyAt(double seconds){
        if(!arranged){Chord c=at(seconds);return new Harmony(c.start(),c.duration(),c.symbol(),c.pitches(),c.frets());}
        Harmony active=harmony.get(0);for(Harmony h:harmony){if(h.start()<=seconds+1e-9)active=h;else break;}return active;
    }
    public static String pitchName(int midi){String[] names={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};return names[midi%12]+(midi/12-1);}
    public String json(){
        StringBuilder b=new StringBuilder();b.append("{\"schemaVersion\":").append(arranged?3:2).append(",\"seed\":").append(seed).append(",\"title\":").append(q(title)).append(",\"key\":").append(q(key)).append(",\"scaleMode\":").append(q(scaleMode)).append(",\"mode\":").append(q(mode)).append(",\"theme\":").append(q(theme)).append(",\"rights\":").append(q(rights)).append(",\"instrumentPreset\":").append(q(instrumentPreset)).append(",\"bpm\":").append(bpm).append(",\"durationSeconds\":").append(duration).append(",\"meter\":\"4/4\",\"chordEvents\":[");
        for(int i=0;i<chords.size();i++){Chord c=chords.get(i);if(i>0)b.append(',');b.append("{\"startSeconds\":").append(c.start()).append(",\"durationSeconds\":").append(c.duration()).append(",\"symbol\":").append(q(c.symbol())).append(",\"midiPitches\":").append(Arrays.toString(c.pitches())).append(",\"frets\":").append(c.frets()==null?"null":Arrays.toString(c.frets())).append('}');}
        b.append("],\"noteEvents\":[");for(int i=0;i<notes.size();i++){Note n=notes.get(i);if(i>0)b.append(',');b.append("{\"voiceId\":").append(q(n.voice())).append(",\"midiPitch\":").append(n.pitch()).append(",\"startSeconds\":").append(n.start()).append(",\"durationSeconds\":").append(n.duration()).append(",\"velocity\":").append(n.velocity()).append('}');}b.append("],\"tempoMap\":[");for(int i=0;i<tempoMap.size();i++){Tempo t=tempoMap.get(i);if(i>0)b.append(',');b.append("{\"beat\":").append(t.beat()).append(",\"bpm\":").append(t.bpm()).append('}');}b.append(']');
        if(arranged){
            b.append(",\"harmonyEvents\":[");
            for(int i=0;i<harmony.size();i++){Harmony h=harmony.get(i);if(i>0)b.append(',');b.append("{\"startSeconds\":").append(h.start()).append(",\"durationSeconds\":").append(h.duration()).append(",\"symbol\":").append(q(h.symbol())).append(",\"midiPitches\":").append(Arrays.toString(h.pitches())).append(",\"frets\":").append(h.frets()==null?"null":Arrays.toString(h.frets())).append('}');}
            b.append("],\"drumEvents\":[");
            for(int i=0;i<drums.size();i++){Drum d=drums.get(i);if(i>0)b.append(',');b.append("{\"kind\":").append(q(d.kind())).append(",\"startSeconds\":").append(d.start()).append(",\"velocity\":").append(d.velocity()).append('}');}
            b.append(']');
        }
        return b.append("}\n").toString();
    }
    private static String q(String s){
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++){
            char ch=s.charAt(i);
            switch(ch){
                case '\"'->b.append("\\\"");
                case '\\'->b.append("\\\\");
                case '\n'->b.append("\\n");
                case '\r'->b.append("\\r");
                case '\t'->b.append("\\t");
                default->{if(ch<32)b.append(String.format("\\u%04x",(int)ch));else b.append(ch);}
            }
        }
        return b.append('\"').toString();
    }
    public void save(Path path)throws IOException{Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,json());}
}