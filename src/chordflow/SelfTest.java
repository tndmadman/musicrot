package chordflow;

import java.util.*;
import java.nio.file.*;
import javax.sound.midi.*;

public final class SelfTest {
    private static int passed=0;
    private static void check(boolean test,String name){if(!test)throw new AssertionError(name);System.out.println("PASS "+name);passed++;}
    private static void fails(Runnable r,String name){boolean failed=false;try{r.run();}catch(IllegalArgumentException e){failed=true;}check(failed,name);}
    public static void run()throws Exception{
        Composition a=Music.generate(1234,10,"flow","gold"),b=Music.generate(1234,10,"flow","gold"),c=Music.generate(321,10,"flow","gold");
        check(a.json().equals(b.json()),"deterministic-seed");check(!a.json().equals(c.json()),"seed-variation");check(a.notes.size()>10,"multiple-notes");
        check(a.at(0).symbol().equals(a.chords.get(0).symbol()),"playhead-first-chord");
        check(a.at(9.999).symbol().equals(a.chords.get(a.chords.size()-1).symbol()),"playhead-last-chord");
        for(long seed=0;seed<100;seed++)for(String mode:List.of("flow","guitar"))Music.generate(seed,60,mode,"blue").validate();check(true,"200-generated-compositions-valid");
        Composition guitar=Music.generate(89,10,"guitar","blue");check(guitar.chords.stream().allMatch(x->x.frets()!=null),"exact-guitar-frets");
        fails(()->new Composition(1,"bad","guitar","blue","ORIGINAL_GENERATED",80,10,List.of(new Composition.Chord(0,10,"C",new int[]{60},new int[]{-1,3,2,0,1,0})),List.of(new Composition.Note("voice-0",60,0,10,75))),"wrong-guitar-fingering-rejected");
        fails(()->new Composition(1,"bad","flow","blue","ORIGINAL_GENERATED",80,10,List.of(new Composition.Chord(0,10,"C",new int[]{60},null)),List.of(new Composition.Note("voice-0",128,0,10,75))),"out-of-range-midi-rejected");
        Path tmp=Files.createTempDirectory("chordflow-test-");try{
            Path midi=tmp.resolve("test.mid"),wav=tmp.resolve("test.wav");Music.writeMidi(a,midi);Sequence seq=MidiSystem.getSequence(midi.toFile());check(seq.getTracks()[0].size()>a.notes.size(),"valid-midi-export");
            Audio.Stats stats=Audio.synth(a,wav);check(stats.rms()>.007&&stats.peak()<.98,"nonsilent-true-synthesis");check(Files.size(wav)>1_000_000,"wav-real-pcm");
            byte[] frame=new Visual(a,240,426,30).frame(0);check(frame.length==240*426*3,"full-raster-frame");
            check(Production.hash(wav).length()==64,"sha256-provenance");
        }finally{try(var walk=Files.walk(tmp)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
        System.out.println("SELFTEST_SUCCESS total="+passed);
    }
}
