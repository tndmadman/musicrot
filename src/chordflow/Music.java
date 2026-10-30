package chordflow;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import javax.sound.midi.*;

/** Deterministic harmony generator, accurate guitar templates and MIDI interchange. */
public final class Music {
    private static final String[] ROOTS={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};
    private record Quality(String suffix,int[] tones) {}
    private static final Quality[] QUALITIES={new Quality("",new int[]{0,4,7}),new Quality("m",new int[]{0,3,7}),new Quality("maj7",new int[]{0,4,7,11}),new Quality("m7",new int[]{0,3,7,10}),new Quality("7",new int[]{0,4,7,10}),new Quality("sus4",new int[]{0,5,7}),new Quality("add9",new int[]{0,4,7,14})};
    // True 6-string physical fret coordinates, low E through high E. -1 means mute, 0 is open.
    private static final int[][] GUITAR={
        {-1,3,2,0,1,0}, {-1,0,2,2,1,0}, {3,2,0,0,0,3}, {-1,-1,0,2,3,2},
        {-1,3,2,0,0,0},{-1,0,2,2,2,0},{3,2,0,0,0,1}, {1,3,3,2,1,1},
        {-1,-1,0,2,3,1},{-1,1,3,3,3,1},{-1,-1,0,2,1,1},{0,2,2,1,0,0},
        {0,2,2,0,0,0},{-1,0,2,0,1,0},{3,2,0,0,0,1},{-1,3,2,0,1,3}, {0,3,2,0,1,0}, {-1,0,3,2,1,1}, {-1,2,0,0,0,3}, {3,3,2,0,1,0}
    };
    private static final String[] GUITAR_NAMES={"C","Am","G","D","Cmaj7","A","G7","F","Dm","Bb","Dm7","E","Em","Am7","G7","Cadd9","C/E","F/A","G/B","C/G"};
    private static final int[] MAJOR={0,2,4,5,7,9,11};
    private static final int[][] FLOW_PROGRESSIONS={
        {0,5,3,4,1,4,0,0}, {0,3,5,4,1,5,4,0},
        {5,3,0,4,5,1,4,0}, {0,1,4,5,3,4,0,0}, {0,5,1,4,3,1,4,0}
    };
    private static final int[][] GUITAR_PROGRESSIONS={
        {0,1,7,2,8,2,0,1}, {0,7,2,0,1,7,0,2},
        {1,7,0,2,8,2,0,1}, {16,17,18,19,0,1,7,2}
    };
    private static final double[][] MOTIF_RHYTHMS={
        {0,0.75,1.50,2.5,3.5}, {0,0.5,1.25,2.0,3.0,3.5},
        {0.25,1,1.5,2.75,3.25}, {0,1,1.75,2.25,3.0}
    };
    private static final int[][] MOTIF_STEPS={
        {0,2,4,2,1,0}, {0,1,3,4,2,1}, {2,4,3,1,0,2}, {0,2,1,4,3,1}
    };
    /** Multi-track 4/4 arrangement: independent melody, bass, accompaniment and percussion.
     * Chord snapshots are derived from exact sounding notes, so the legacy pitch QA
     * still applies while separate harmony events preserve long chords and guitar shapes. */
    public static Composition generate(long seed,int seconds,String mode,String theme){
        if(seconds<2||seconds>180)throw new IllegalArgumentException("DURATION_OUT_OF_RANGE");
        if(!Set.of("flow","guitar").contains(mode))throw new IllegalArgumentException("INVALID_MODE");
        Random r=new Random(seed);
        double bpm=78+r.nextInt(38),beat=60.0/bpm,totalBeats=seconds/beat;
        int key=r.nextInt(12),progression=r.nextInt(mode.equals("guitar")?GUITAR_PROGRESSIONS.length:FLOW_PROGRESSIONS.length);
        List<Composition.Harmony> harmony=new ArrayList<>();
        // Phrase-level duration patterns, rather than uniformly one chord per bar.
        int[][] rhythms={{4,2,2,4,4,2,2,4,6,2},{4,4,2,2,6,2,4,4}, {2,2,4,4,2,2,6,2},{6,2,4,2,2,4,4,4}};
        int[] rhythm=rhythms[r.nextInt(rhythms.length)];
        int event=0;
        for(double at=0;at<totalBeats-1e-8;){
            double len=Math.min(rhythm[event%rhythm.length],totalBeats-at);
            int[] pitches,frets=null;String name;
            if(mode.equals("guitar")){
                int ix=GUITAR_PROGRESSIONS[progression][event%8];frets=GUITAR[ix].clone();name=GUITAR_NAMES[ix];
                int[] tune={40,45,50,55,59,64};List<Integer> p=new ArrayList<>();
                for(int i=0;i<6;i++)if(frets[i]>=0)p.add(tune[i]+frets[i]);
                pitches=p.stream().mapToInt(Integer::intValue).toArray();
            }else{
                int degree=FLOW_PROGRESSIONS[progression][event%8];int root=Math.floorMod(key+MAJOR[degree],12);
                Quality q=switch(degree){case 0,3->QUALITIES[event%3==2?2:0];case 4->QUALITIES[event%2==0?4:0];default->QUALITIES[event%3==1?3:1];};
                name=ROOTS[root]+q.suffix();pitches=new int[q.tones.length+1];pitches[0]=36+root;
                for(int v=0;v<q.tones.length;v++)pitches[v+1]=60+root+q.tones[v];
            }
            harmony.add(new Composition.Harmony(at*beat,len*beat,name,pitches,frets));
            at+=len;event++;
        }
        List<Composition.Note> notes=new ArrayList<>();List<Composition.Drum> drums=new ArrayList<>();
        int motif=r.nextInt(MOTIF_RHYTHMS.length);int[] phraseOffsets=MOTIF_STEPS[motif];
        int phrase=0;
        for(double phraseStart=0;phraseStart<totalBeats;phraseStart+=4,phrase++){
            Composition.Harmony base=harmonyAt(harmony,phraseStart*beat);
            double[] offsets=MOTIF_RHYTHMS[motif];
            for(int m=0;m<offsets.length;m++){
                double offset=offsets[m]+(phrase%4==2&&m==1?.25:0)+(phrase%4==3&&m==3?-.25:0);
                double at=phraseStart+offset;
                if(at>=totalBeats-0.05||(phrase%8==7&&m==0))continue;
                // Related four-bar phrases recur with section-level register/contour variation.
                int sectionShift=switch((phrase/4)%4){case 1->2;case 2->4;case 3->1;default->0;};
                int index=Math.floorMod(phraseOffsets[m%phraseOffsets.length]+sectionShift+(phrase%4==3&&m==offsets.length-1?-1:0),7);
                int pitch=60+key+MAJOR[index];
                while(pitch<66)pitch+=12;while(pitch>83)pitch-=12;
                if(m==0||m==offsets.length-1){int[] chord=base.pitches();int best=pitch,dist=100;
                    for(int tone:chord){int t=tone;while(t<66)t+=12;while(t>83)t-=12;int d=Math.abs(t-pitch);if(d<dist){best=t;dist=d;}}pitch=best;}
                double next=m+1<offsets.length?offsets[m+1]:4;
                double dur=Math.min(next-offset,totalBeats-at)*(m%3==1?.72:.88);
                addNote(notes,"lead-0",pitch,at*beat,dur*beat,77+r.nextInt(25),seconds);
            }
        }
        for(Composition.Harmony h:harmony){
            double start=h.start()/beat,end=Math.min(totalBeats,(h.start()+h.duration())/beat);
            int[] chord=h.pitches();
            int low=Arrays.stream(chord).min().orElse(40);
            // Independent bass groove: two strong pulses and syncopated approach notes.
            for(double off=0;start+off<end-0.15;off+=4){
                double at=start+off;addNote(notes,"bass-0",Math.min(52,low),at*beat,Math.min(1.30,end-at)*beat,88+r.nextInt(16),seconds);
                if(at+2<end){addNote(notes,"bass-0",Math.min(52,low)+(off%8==0?7:0), (at+2)*beat,Math.min(.84,end-at-2)*beat,75+r.nextInt(16),seconds);}
                if(at+3.5<end-.2){int walk=Math.min(54,low)+(r.nextBoolean()?2:-2);addNote(notes,"bass-0",walk,(at+3.5)*beat,Math.min(.40,end-at-3.5)*beat,64+r.nextInt(14),seconds);}
            }
            if(mode.equals("guitar")){
                // Spread strokes across strings over ~90ms, with rhythmic gaps and accents.
                int beatIndex=0;
                for(double off=0;start+off<end-.15;off+=(beatIndex++%3==1?1.5:1.0)){
                    double at=start+off;int[] tune={40,45,50,55,59,64};
                    int[] frets=h.frets();boolean down=beatIndex%2==1;
                    for(int string=0;string<6;string++){
                        int i=down?string:5-string;if(frets[i]<0)continue;
                        double offset=(string*.018)/beat;
                        if(at+offset>=end)continue;
                        addNote(notes,"guitar-"+i,tune[i]+frets[i],(at+offset)*beat,Math.min(.56,end-at-offset)*beat,65+r.nextInt(24),seconds);
                    }
                }
            }else{
                for(int i=1;i<chord.length;i++){
                    addNote(notes,"pad-"+i,chord[i],start*beat,Math.max(.07,(end-start)*beat*.90),55+r.nextInt(16),seconds);
                }
                int j=0;
                for(double at=start+.5;at<end-.15;at+=.75){
                    int pitch=chord[1+(j++%(chord.length-1))];
                    addNote(notes,"arp-"+(j%3),pitch,at*beat,Math.min(.47,end-at)*beat,45+r.nextInt(24),seconds);
                }
            }
        }
        // Deliberately arranged drum groove with holes, backbeat and light swing.
        // Strictly separate from pitched notes so guitar charts never claim drum pitches.
        for(int bar=0;bar*4<totalBeats;bar++){
            double at=bar*4;
            for(double offset:new double[]{0,2.5})addDrum(drums,"kick",(at+offset)*beat,offset==0?98:78,seconds);
            for(double offset:new double[]{1,3})addDrum(drums,"snare",(at+offset)*beat,79+(bar%3)*5,seconds);
            for(int sub=0;sub<8;sub++){
                if((sub==5&&bar%2==0)||(sub==7&&bar%3==1))continue;
                double swing=sub%2==1?.09:0;
                addDrum(drums,sub==7&&bar%4==3?"open-hat":"hat",(at+sub*.5+swing)*beat,sub%2==0?60:40,seconds);
            }
        }
        notes.sort(Comparator.comparingDouble(Composition.Note::start).thenComparing(Composition.Note::voice));
        // Complete change-point snapshots, including rests and overlapping voices.
        TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add((double)seconds);
        for(Composition.Note n:notes){cuts.add(n.start());cuts.add(n.start()+n.duration());}
        List<Double> times=new ArrayList<>(cuts);List<Composition.Chord> snapshots=new ArrayList<>();
        for(int i=0;i+1<times.size();i++){
            double a=times.get(i),b=times.get(i+1);if(b-a<1e-9)continue;double mid=(a+b)/2;
            int[] sounding=notes.stream().filter(n->n.start()<=mid&&n.start()+n.duration()>mid).mapToInt(Composition.Note::pitch).toArray();
            Composition.Harmony h=harmonyAt(harmony,mid);
            snapshots.add(new Composition.Chord(a,b-a,sounding.length==0?"Rest":h.symbol(),sounding,null));
        }
        return new Composition(seed,"Original Arrangement "+seed,mode,theme,"ORIGINAL_GENERATED",mode.equals("guitar")?"C":ROOTS[key],"major",bpm,seconds,
            snapshots,notes,List.of(new Composition.Tempo(0,bpm)),mode.equals("guitar")?"nylon-pluck":"soft-keys",harmony,drums,true);
    }
    private static Composition.Harmony harmonyAt(List<Composition.Harmony> harmony,double seconds){
        Composition.Harmony active=harmony.get(0);for(Composition.Harmony h:harmony){if(h.start()>seconds+1e-9)break;active=h;}return active;
    }
    private static void addNote(List<Composition.Note> notes,String voice,int pitch,double start,double duration,int velocity,double seconds){
        double dur=Math.min(duration,seconds-start);if(dur>1e-7&&start<seconds)notes.add(new Composition.Note(voice,Math.max(0,Math.min(127,pitch)),start,dur,velocity));
    }
    private static void addDrum(List<Composition.Drum> drums,String kind,double time,int velocity,int seconds){
        if(time<seconds-1e-7)drums.add(new Composition.Drum(kind,time,velocity));
    }
    /** MIDI export uses the exact composition tempo map, retaining imported tempo events. */
    public static void writeMidi(Composition c,Path path)throws Exception{
        Sequence seq=new Sequence(Sequence.PPQ,960);Track t=seq.createTrack();
        for(Composition.Tempo tempo:c.tempoMap){int mpqn=(int)Math.round(60000000/tempo.bpm());MetaMessage m=new MetaMessage();m.setMessage(0x51,new byte[]{(byte)(mpqn>>16),(byte)(mpqn>>8),(byte)mpqn},3);t.add(new MidiEvent(m,(long)Math.round(tempo.beat()*960)));}
        List<MidiEvent> ev=new ArrayList<>();for(Composition.Note n:c.notes){
            long tick=Math.round(c.beatAtSeconds(n.start())*960),end=Math.round(c.beatAtSeconds(n.start()+n.duration())*960);
            if(end<=tick)throw new IllegalArgumentException("MIDI_NOTE_TOO_SHORT");
            ShortMessage on=new ShortMessage(ShortMessage.NOTE_ON,0,n.pitch(),n.velocity());ShortMessage off=new ShortMessage(ShortMessage.NOTE_OFF,0,n.pitch(),0);
            ev.add(new MidiEvent(on,tick));ev.add(new MidiEvent(off,end));
        }
        for(Composition.Drum hit:c.drums){
            int pitch=switch(hit.kind()){case "kick"->36;case "snare"->38;case "hat"->42;default->46;};
            long tick=Math.round(c.beatAtSeconds(hit.start())*960);long end=tick+Math.max(1,Math.round(.08*c.bpm/60*960));
            ev.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,9,pitch,hit.velocity()),tick));
            ev.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,9,pitch,0),end));
        }
        ev.sort(Comparator.comparingLong(MidiEvent::getTick).thenComparingInt(e->((ShortMessage)e.getMessage()).getCommand()==ShortMessage.NOTE_OFF?0:1));for(MidiEvent e:ev)t.add(e);
        Files.createDirectories(path.toAbsolutePath().getParent());MidiSystem.write(seq,1,path.toFile());
    }
    private record MidiMsg(long tick,int track,ShortMessage message) {}
    private record NoteOn(long tick,int velocity) {}
    private record Extracted(long start,long end,int pitch,int velocity,String voice) {}
    /** Import multi-track, variable-tempo PPQ MIDI. Note-off sustain-pedal semantics are preserved. */
    public static Composition importMidi(Path midi,String rights,long seed,int seconds,String theme)throws Exception{
        if(rights==null||rights.isBlank()||rights.equals("ORIGINAL_GENERATED"))throw new IllegalArgumentException("IMPORT_RIGHTS_REQUIRED");
        if(seconds<2||seconds>180)throw new IllegalArgumentException("DURATION_OUT_OF_RANGE");
        Sequence seq=MidiSystem.getSequence(midi.toFile());
        if(seq.getDivisionType()!=Sequence.PPQ||seq.getResolution()<=0)throw new IllegalArgumentException("MIDI_PPQ_REQUIRED");
        // MIDI type 2 contains asynchronous sequences; it cannot be combined into one timeline.
        if(seq.getTracks().length>64)throw new IllegalArgumentException("MIDI_TOO_MANY_TRACKS");
        NavigableMap<Long,Double> tempos=new TreeMap<>();tempos.put(0L,120.0);
        List<MidiMsg> stream=new ArrayList<>();
        for(int ti=0;ti<seq.getTracks().length;ti++){
            Track track=seq.getTracks()[ti];
            for(int i=0;i<track.size();i++){
                MidiEvent ev=track.get(i);MidiMessage msg=ev.getMessage();
                if(msg instanceof MetaMessage meta && meta.getType()==0x51){
                    byte[] d=meta.getData();if(d.length!=3)throw new IllegalArgumentException("MIDI_BAD_TEMPO");
                    int micros=((d[0]&255)<<16)|((d[1]&255)<<8)|(d[2]&255);
                    if(micros<200000||micros>3000000)throw new IllegalArgumentException("MIDI_BAD_TEMPO");
                    tempos.put(ev.getTick(),60000000.0/micros);
                }
                if(msg instanceof ShortMessage shortMessage)stream.add(new MidiMsg(ev.getTick(),ti,shortMessage));
            }
        }
        List<Composition.Tempo> timeline=new ArrayList<>();for(var e:tempos.entrySet())timeline.add(new Composition.Tempo(e.getKey()/(double)seq.getResolution(),e.getValue()));
        // Provide an exact standalone seconds conversion, including rests and tempo changes.
        java.util.function.LongToDoubleFunction toSeconds=(tick)->{
            double out=0;long previous=0;double bpm=120;
            for(var entry:tempos.entrySet()){
                if(entry.getKey()>tick)break;
                out+=(entry.getKey()-previous)*60/(bpm*seq.getResolution());previous=entry.getKey();bpm=entry.getValue();
            }
            return out+(tick-previous)*60/(bpm*seq.getResolution());
        };
        stream.sort(Comparator.comparingLong(MidiMsg::tick).thenComparingInt(m->{
            int cmd=m.message().getCommand();return cmd==ShortMessage.NOTE_OFF||(cmd==ShortMessage.NOTE_ON&&m.message().getData2()==0)?0:1;
        }));
        Map<String,Deque<NoteOn>> active=new HashMap<>();Map<String,List<Extracted>> held=new HashMap<>();Map<String,Boolean> pedal=new HashMap<>();List<Extracted> extracted=new ArrayList<>();
        List<Composition.Drum> drums=new ArrayList<>();
        for(MidiMsg event:stream){ShortMessage m=event.message();int cmd=m.getCommand(),channel=m.getChannel(),pitch=m.getData1();String lane=event.track()+"-"+channel, key=lane+"-"+pitch;
            if(channel==9){
                if(cmd==ShortMessage.NOTE_ON&&m.getData2()>0){
                    String kind=switch(pitch){case 36,35->"kick";case 38,40->"snare";case 42,44->"hat";case 46->"open-hat";default->null;};
                    double at=toSeconds.applyAsDouble(event.tick());
                    if(kind!=null&&at<seconds)drums.add(new Composition.Drum(kind,at,m.getData2()));
                }
                continue;
            }
            if(cmd==ShortMessage.CONTROL_CHANGE&&pitch==64){boolean down=m.getData2()>=64;
                if(!down&&pedal.getOrDefault(lane,false)){
                    for(var it=held.entrySet().iterator();it.hasNext();){var e=it.next();if(e.getKey().startsWith(lane+"-")){for(Extracted n:e.getValue())extracted.add(new Extracted(n.start(),event.tick(),n.pitch(),n.velocity(),n.voice()));it.remove();}}
                }
                pedal.put(lane,down);
            }else if(cmd==ShortMessage.NOTE_ON&&m.getData2()>0){active.computeIfAbsent(key,k->new ArrayDeque<>()).addLast(new NoteOn(event.tick(),m.getData2()));}
            else if(cmd==ShortMessage.NOTE_OFF||(cmd==ShortMessage.NOTE_ON&&m.getData2()==0)){
                Deque<NoteOn> starts=active.get(key);if(starts==null||starts.isEmpty())throw new IllegalArgumentException("MIDI_UNMATCHED_OFF");
                NoteOn start=starts.removeFirst();if(event.tick()<=start.tick())throw new IllegalArgumentException("MIDI_ZERO_LENGTH_NOTE");
                Extracted note=new Extracted(start.tick(),event.tick(),pitch,start.velocity(),"midi-"+lane+"-"+pitch);
                if(pedal.getOrDefault(lane,false))held.computeIfAbsent(key,k->new ArrayList<>()).add(note);else extracted.add(note);
            }
        }
        if(active.values().stream().anyMatch(q->!q.isEmpty())||!held.isEmpty()||extracted.isEmpty())throw new IllegalArgumentException("MIDI_UNMATCHED_NOTES");
        List<Composition.Note> notes=new ArrayList<>();for(Extracted n:extracted){double start=toSeconds.applyAsDouble(n.start()),end=toSeconds.applyAsDouble(n.end());if(end>seconds+1e-6)throw new IllegalArgumentException("MIDI_EXCEEDS_REQUESTED_DURATION");notes.add(new Composition.Note(n.voice(),n.pitch(),start,end-start,n.velocity()));}
        notes.sort(Comparator.comparingDouble(Composition.Note::start).thenComparingInt(Composition.Note::pitch));
        TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add((double)seconds);for(Composition.Note n:notes){cuts.add(n.start());cuts.add(n.start()+n.duration());}
        List<Double> times=new ArrayList<>(cuts);List<Composition.Chord> chords=new ArrayList<>();
        for(int i=0;i+1<times.size();i++){double start=times.get(i),len=times.get(i+1)-start;if(len<1e-8)continue;double mid=start+len/2;
            int[] pitches=notes.stream().filter(n->n.start()<=mid&&n.start()+n.duration()>mid).mapToInt(Composition.Note::pitch).toArray();
            chords.add(new Composition.Chord(start,len,pitches.length==0?"Rest":"Voicing",pitches,null));
        }
        if(!drums.isEmpty()){
            List<Composition.Harmony> harmony=new ArrayList<>();
            for(Composition.Chord chord:chords)harmony.add(new Composition.Harmony(chord.start(),chord.duration(),chord.symbol(),chord.pitches(),null));
            return new Composition(seed,"Licensed MIDI "+midi.getFileName(),"flow",theme,rights,"unknown","imported",timeline.get(0).bpm(),seconds,chords,notes,timeline,"soft-keys",harmony,drums,true);
        }
        return new Composition(seed,"Licensed MIDI "+midi.getFileName(),"flow",theme,rights,"unknown","imported",timeline.get(0).bpm(),seconds,chords,notes,timeline);
    }
}