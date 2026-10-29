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
    public static Composition generate(long seed,int seconds,String mode,String theme){
        if(seconds<2||seconds>180)throw new IllegalArgumentException("DURATION_OUT_OF_RANGE");
        Random r=new Random(seed);double bpm=72+r.nextInt(35), bar=240.0/bpm;
        List<Composition.Chord> chords=new ArrayList<>(); List<Composition.Note> notes=new ArrayList<>();
        int[] previous=null;int position=r.nextInt(12),step=0;int phrase=r.nextInt(mode.equals("guitar")?8:6);int[] scale={0,2,4,5,7,9,11};int[][] degrees={{0,5,3,4,1,4,0,0},{0,3,4,0,5,1,4,0},{5,3,0,4,5,3,4,0},{0,1,4,5,3,4,0,0},{0,3,1,4,5,1,4,0},{0,5,1,4,3,1,4,0}};int[][] guitarProgressions={{0,1,7,2},{0,7,2,0},{1,7,0,2},{8,2,0,1},{0,4,7,6},{0,1,4,2},{0,15,7,2},{16,17,18,19}};
        for(double start=0;start<seconds-0.01;start+=bar){double len=Math.min(bar,seconds-start);int[] pitches;int[] frets=null;String name;
            if(mode.equals("guitar")){
                // Guitar notes are *exactly* the six-string fingering, including octave duplicates.
                int index=guitarProgressions[Math.floorMod(phrase,guitarProgressions.length)][step%4];frets=GUITAR[index].clone();name=GUITAR_NAMES[index];
                int[] open={40,45,50,55,59,64};List<Integer> p=new ArrayList<>();for(int i=0;i<6;i++)if(frets[i]>=0)p.add(open[i]+frets[i]);pitches=p.stream().mapToInt(Integer::intValue).toArray();
            }else{
                // Diatonic palette in key center; octave assign each upper voice by minimal travel.
                int degree=degrees[phrase][step%8];int root=(position+scale[degree])%12;
                Quality q=switch(degree){case 0,3->QUALITIES[r.nextBoolean()?0:2];case 4->QUALITIES[r.nextBoolean()?0:4];default->QUALITIES[r.nextBoolean()?1:3];};name=ROOTS[root]+q.suffix();
                pitches=new int[q.tones.length+1];pitches[0]=36+root;
                for(int v=0;v<q.tones.length;v++){int pitch=60+root+q.tones[v];if(previous!=null&&v+1<previous.length){int target=previous[v+1],candidate=pitch;for(int shift=-2;shift<=2;shift++){int t=pitch+shift*12;if(t>=48&&t<=85&&Math.abs(t-target)<Math.abs(candidate-target))candidate=t;}pitch=candidate;}pitches[v+1]=pitch;}
            }
            chords.add(new Composition.Chord(start,len,name,pitches,frets));
            for(int v=0;v<pitches.length;v++)notes.add(new Composition.Note("voice-"+v,pitches[v],start,len,v==0?83:75));
            previous=pitches;step++;
        }
        return new Composition(seed,"Original Harmony "+seed,mode,theme,"ORIGINAL_GENERATED",mode.equals("guitar")?"C":ROOTS[position],"major",bpm,seconds,chords,notes);
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
        for(MidiMsg event:stream){ShortMessage m=event.message();int cmd=m.getCommand(),channel=m.getChannel(),pitch=m.getData1();String lane=event.track()+"-"+channel, key=lane+"-"+pitch;
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
        Composition c=new Composition(seed,"Licensed MIDI "+midi.getFileName(),"flow",theme,rights,"unknown","imported",timeline.get(0).bpm(),seconds,chords,notes,timeline);
        return c;
    }
}
