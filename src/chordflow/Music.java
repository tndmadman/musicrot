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
        {0,2,2,0,0,0},{-1,0,2,0,1,0},{3,2,0,0,0,1},{-1,3,2,0,1,3}
    };
    private static final String[] GUITAR_NAMES={"C","Am","G","D","Cmaj7","A","G7","F","Dm","Bb","Dm7","E","Em","Am7","G7","Cadd9"};
    public static Composition generate(long seed,int seconds,String mode,String theme){
        if(seconds<2||seconds>180)throw new IllegalArgumentException("DURATION_OUT_OF_RANGE");
        Random r=new Random(seed);double bpm=72+r.nextInt(35), bar=240.0/bpm;
        List<Composition.Chord> chords=new ArrayList<>(); List<Composition.Note> notes=new ArrayList<>();
        int[] previous=null;int position=r.nextInt(12),step=0;int phrase=r.nextInt(6);int[] scale={0,2,4,5,7,9,11};int[][] degrees={{0,5,3,4,1,4,0,0},{0,3,4,0,5,1,4,0},{5,3,0,4,5,3,4,0},{0,1,4,5,3,4,0,0},{0,3,1,4,5,1,4,0},{0,5,1,4,3,1,4,0}};int[][] guitarProgressions={{0,1,7,2},{0,7,2,0},{1,7,0,2},{8,2,0,1},{0,4,7,6},{0,1,4,2},{0,15,7,2}};
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
    public static void writeMidi(Composition c,Path path)throws Exception{
        Sequence seq=new Sequence(Sequence.PPQ,960);Track t=seq.createTrack();int mpqn=(int)Math.round(60000000/c.bpm);MetaMessage tempo=new MetaMessage();tempo.setMessage(0x51,new byte[]{(byte)(mpqn>>16),(byte)(mpqn>>8),(byte)mpqn},3);t.add(new MidiEvent(tempo,0));
        List<MidiEvent> ev=new ArrayList<>();for(Composition.Note n:c.notes){int tick=(int)Math.round(n.start()*c.bpm/60*960),end=(int)Math.round((n.start()+n.duration())*c.bpm/60*960);ShortMessage on=new ShortMessage(ShortMessage.NOTE_ON,0,n.pitch(),n.velocity());ShortMessage off=new ShortMessage(ShortMessage.NOTE_OFF,0,n.pitch(),0);ev.add(new MidiEvent(on,tick));ev.add(new MidiEvent(off,end));}
        ev.sort(Comparator.comparingLong(MidiEvent::getTick).thenComparingInt(e->((ShortMessage)e.getMessage()).getCommand()==ShortMessage.NOTE_OFF?0:1));for(MidiEvent e:ev)t.add(e);
        Files.createDirectories(path.toAbsolutePath().getParent());MidiSystem.write(seq,1,path.toFile());
    }
    /** Import only monophonic change boundaries or block-chord MIDI; provenance is mandatory. */
    public static Composition importMidi(Path midi,String rights,long seed,int seconds,String theme)throws Exception{
        if(rights==null||rights.isBlank()||rights.equals("ORIGINAL_GENERATED"))throw new IllegalArgumentException("IMPORT_RIGHTS_REQUIRED");
        Sequence seq=MidiSystem.getSequence(midi.toFile()); if(seq.getDivisionType()!=Sequence.PPQ)throw new IllegalArgumentException("MIDI_PPQ_REQUIRED");
        double bpm=120;for(Track tr:seq.getTracks())for(int i=0;i<tr.size();i++){MidiMessage msg=tr.get(i).getMessage();if(msg instanceof MetaMessage m&&m.getType()==0x51){byte[] d=m.getData();if(d.length==3)bpm=60000000.0/(((d[0]&255)<<16)|((d[1]&255)<<8)|(d[2]&255));break;}}
        // MIDI import is explicit about supported format. Reject changing tempo rather than desynchronize graphics.
        for(Track tr:seq.getTracks())for(int i=0;i<tr.size();i++){MidiMessage msg=tr.get(i).getMessage();if(msg instanceof MetaMessage m&&m.getType()==0x51&&tr.get(i).getTick()>0)throw new IllegalArgumentException("MIDI_TEMPO_MAP_UNSUPPORTED");}
        Map<String,Long> active=new HashMap<>();List<Composition.Note> notes=new ArrayList<>();
        for(Track tr:seq.getTracks())for(int i=0;i<tr.size();i++){
            MidiEvent ev=tr.get(i);if(!(ev.getMessage() instanceof ShortMessage m))continue;int channel=m.getChannel(),pitch=m.getData1();String k=channel+":"+pitch;double time=ev.getTick()/((double)seq.getResolution())*60/bpm;
            if(m.getCommand()==ShortMessage.NOTE_ON&&m.getData2()>0){if(active.put(k,ev.getTick())!=null)throw new IllegalArgumentException("MIDI_OVERLAP_SAME_PITCH");}
            else if(m.getCommand()==ShortMessage.NOTE_OFF||(m.getCommand()==ShortMessage.NOTE_ON&&m.getData2()==0)){Long begin=active.remove(k);if(begin==null)throw new IllegalArgumentException("MIDI_UNMATCHED_OFF");double s=begin/((double)seq.getResolution())*60/bpm;if(time>s)notes.add(new Composition.Note("midi-"+channel+"-"+pitch,pitch,s,time-s,76));}
        }
        if(!active.isEmpty()||notes.isEmpty())throw new IllegalArgumentException("MIDI_UNMATCHED_NOTES");
        notes.sort(Comparator.comparingDouble(Composition.Note::start));TreeSet<Double> starts=new TreeSet<>();for(Composition.Note n:notes)starts.add(n.start());List<Double> ts=new ArrayList<>(starts);List<Composition.Chord> chords=new ArrayList<>();
        for(int i=0;i<ts.size();i++){double t=ts.get(i),len=(i+1<ts.size()?ts.get(i+1):seconds)-t;if(len<=0)continue;List<Integer> pitches=new ArrayList<>();for(Composition.Note n:notes)if(n.start()<=t+1e-7&&n.start()+n.duration()>t+1e-7)pitches.add(n.pitch());if(!pitches.isEmpty())chords.add(new Composition.Chord(t,len,"MIDI chord",pitches.stream().mapToInt(Integer::intValue).toArray(),null));}
        return new Composition(seed,"Licensed MIDI "+midi.getFileName(),"flow",theme,rights,bpm,seconds,chords,notes);
    }
}
