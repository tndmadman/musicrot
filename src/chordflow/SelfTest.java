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
        Composition inversion=Music.generate(2,10,"guitar","blue");check(inversion.chords.get(0).symbol().equals("C/E")&&inversion.chords.get(0).pitches()[0]==40,"slash-bass-inversion-exact");
        fails(()->new Composition(1,"bad","guitar","blue","ORIGINAL_GENERATED",80,10,List.of(new Composition.Chord(0,10,"C",new int[]{60},new int[]{-1,3,2,0,1,0})),List.of(new Composition.Note("voice-0",60,0,10,75))),"wrong-guitar-fingering-rejected");
        fails(()->new Composition(1,"bad","flow","blue","ORIGINAL_GENERATED",80,10,List.of(new Composition.Chord(0,10,"C",new int[]{60},null)),List.of(new Composition.Note("voice-0",128,0,10,75))),"out-of-range-midi-rejected");
        // Regression: an incorrect note that enters halfway through a nominal chord is rejected.
        fails(()->new Composition(2,"bad","flow","gold","ORIGINAL_GENERATED",90,2,
            List.of(new Composition.Chord(0,2,"C",new int[]{60},null)),
            List.of(new Composition.Note("a",60,0,2,80),new Composition.Note("b",64,1,1,80))),"mid-chord-notes-rejected");
        int[] mutablePitches={60};Composition.Chord immutable=new Composition.Chord(0,1,"C",mutablePitches,null);
        mutablePitches[0]=70;immutable.pitches()[0]=80;check(immutable.pitches()[0]==60,"chord-arrays-defensive");
        int[] solvedF=GuitarSolver.solve(new int[]{41,48,53,57,60,65}).orElseThrow();
        check(solvedF.length==6&&Arrays.equals(solvedF,new int[]{1,3,3,2,1,1}),"exact-solved-barre-F-chord");
        check(GuitarSolver.solve(new int[]{60,64,67}).isPresent(),"arbitrary-exact-triad-solved");
        check(GuitarSolver.solve(new int[]{28,40}).isEmpty(),"impossible-bass-fails-closed");
        check(GuitarSolver.solve(new int[]{60,61,62,63,64,65,66}).isEmpty(),"too-many-guitar-voices-rejected");
        Composition guitarized=GuitarSolver.withDiagrams(a);
        check(guitarized.mode.equals("guitar")&&guitarized.notes.equals(a.notes),"import-guitar-note-truth-retained");
        check(a.json().contains("\"schemaVersion\":2")&&a.json().contains("\"tempoMap\""),"versioned-tempo-schema");
        Path tmp=Files.createTempDirectory("chordflow-test-");try{
            Path midi=tmp.resolve("test.mid"),wav=tmp.resolve("test.wav");Music.writeMidi(a,midi);Sequence seq=MidiSystem.getSequence(midi.toFile());check(seq.getTracks()[0].size()>a.notes.size(),"valid-midi-export");
            Composition imported=Music.importMidi(midi,"Licensed by creator",192,10,"gold");check(imported.notes.size()==a.notes.size(),"authorized-midi-import-roundtrip");
            Path compositionPath=tmp.resolve("saved.composition.json");a.save(compositionPath);
            Composition loaded=CompositionIO.read(compositionPath);
            check(a.json().equals(loaded.json()),"saved-json-roundtrip-identical");
            boolean malformedRejected=false;
            Path malformed=tmp.resolve("malformed.json");Files.writeString(malformed,a.json().replace("\"schemaVersion\":2", "\"schemaVersion\":99"));
            try{CompositionIO.read(malformed);}catch(java.io.IOException expected){malformedRejected=true;}
            check(malformedRejected,"unknown-composition-schema-rejected");
            malformedRejected=false;Files.writeString(malformed,a.json()+" garbage");
            try{CompositionIO.read(malformed);}catch(java.io.IOException expected){malformedRejected=true;}
            check(malformedRejected,"composition-trailing-data-rejected");
            Audio.Stats stats=Audio.synth(a,wav);check(stats.rms()>.007&&stats.peak()<.98,"nonsilent-true-synthesis");check(Files.size(wav)>1_000_000,"wav-real-pcm");
            Set<String> instrumentHashes=new HashSet<>();
            for(String preset:Composition.PRESETS){
                Composition pc=a.withInstrument(preset);
                check(pc.json().contains("\"instrumentPreset\":\""+preset+"\""),"preset-serialized-"+preset);
                Path pw=tmp.resolve(preset+".wav");Audio.synth(pc,pw);instrumentHashes.add(Production.hash(pw));
            }
            check(instrumentHashes.size()==Composition.PRESETS.size(),"five-distinct-instrument-timbres");
            Visual v=new Visual(a,240,426,30);byte[] frame=v.frame(0).clone();check(frame.length==240*426*3,"full-raster-frame");check(!java.util.Arrays.equals(frame,v.frame(15)),"motion-is-animated");
            check(Production.hash(wav).length()==64,"sha256-provenance");
            // Independent multi-track, multi-tempo, sustain-pedal MIDI fixture.
            Sequence changing=new Sequence(Sequence.PPQ,960);Track t0=changing.createTrack(),t1=changing.createTrack(),t2=changing.createTrack();
            byte[] fast={0x07,(byte)0xA1,0x20},slow={0x0F,0x42,0x40};
            MetaMessage m0=new MetaMessage(0x51,fast,3),m1=new MetaMessage(0x51,slow,3);t0.add(new MidiEvent(m0,0));t0.add(new MidiEvent(m1,1920));
            t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,0,60,94),0));
            t1.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,0,60,0),3840));
            t2.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,1,64,127),0));
            t2.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,1,67,88),960));
            t2.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,1,67,0),1440));
            t2.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,1,64,0),2880));
            Path tempoMidi=tmp.resolve("changing.mid");MidiSystem.write(changing,1,tempoMidi.toFile());
            Composition importedTempo=Music.importMidi(tempoMidi,"Owned by test author",4,6,"blue");
            check(importedTempo.tempoMap.size()==2,"multi-tempo-import");
            check(Math.abs(importedTempo.secondsAtBeat(4)-3)<.001&&Math.abs(importedTempo.beatAtSeconds(3)-4)<.001,"tempo-beat-second-roundtrip");
            check(importedTempo.notes.stream().anyMatch(n->n.pitch()==67&&Math.abs(n.duration()-1.5)<.001),"pedal-held-note-duration");
            check(importedTempo.chords.stream().anyMatch(ch->ch.symbol().equals("Rest")),"imported-rests-truthful");
            check(importedTempo.notes.stream().anyMatch(n->n.voice().startsWith("midi-2-")),"multitrack-voice-identity");
            Path reexport=tmp.resolve("tempo-roundtrip.mid");Music.writeMidi(importedTempo,reexport);
            Composition roundtrip=Music.importMidi(reexport,"Owned by test author",5,6,"blue");
            check(roundtrip.tempoMap.size()==2,"tempo-map-exported");
            check(roundtrip.notes.size()==importedTempo.notes.size(),"tempo-map-midi-roundtrip");
        }finally{try(var walk=Files.walk(tmp)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
        System.out.println("SELFTEST_SUCCESS total="+passed);
    }
}
