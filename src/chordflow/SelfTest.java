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
        // Harmonic QA: generated major/minor/diminished triads must contain
        // actual major/minor thirds and proper fifths, not fabricated intervals.
        for(long seed=0;seed<40;seed++){
            Composition piece=MusicV4.generate(seed,24,"flow","noir");
            for(Composition.Harmony h:piece.harmony){
                int[] p=h.pitches();
                int third=Math.floorMod(p[2]-p[1],12),fifth=Math.floorMod(p[3]-p[1],12);
                check(Set.of(3,4).contains(third)&&Set.of(6,7).contains(fifth),
                    "diatonic-triad-intervals-"+seed+"-"+Math.round(h.start()*10));
            }
        }
                Composition guitar=Music.generate(89,10,"guitar","blue");check(guitar.harmony.stream().allMatch(x->x.frets()!=null),"exact-guitar-frets");
        Composition inversion=Music.generate(2,10,"guitar","blue");check(inversion.harmony.stream().allMatch(h->h.frets()!=null&&h.pitches().length>0),"harmony-guitar-inversion-exact");
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
        check(a.json().contains("\"schemaVersion\":3")&&a.json().contains("\"tempoMap\""),"versioned-tempo-schema");
        check(a.arranged&&a.harmony.size()>0&&a.drums.isEmpty(),"arranged-instrumental-no-automatic-percussion");
        check(a.notes.stream().anyMatch(n->n.voice().startsWith("lead"))&&a.notes.stream().anyMatch(n->n.voice().startsWith("bass"))&&a.notes.stream().anyMatch(n->n.voice().startsWith("pad")||n.voice().startsWith("arp")),"multi-track-melody-bass-curated-accompaniment");
        MusicFingerprint.Signature fingerprint=MusicFingerprint.of(a);
        check(fingerprint.encode().equals(MusicFingerprint.Signature.parse(fingerprint.encode()).encode()),
            "fingerprint-durable-roundtrip");
        MusicFingerprint.Signature differentInstrumentOnly=new MusicFingerprint.Signature(
            "0".repeat(64),fingerprint.drums(),"1".repeat(64),"2".repeat(64),
            fingerprint.melodySketch()^0xAAAAAAAAAAAAAAAAL,fingerprint.drumsSketch());
        check(!MusicFingerprint.nearDuplicate(fingerprint,differentInstrumentOnly),
            "no-false-duplicate-from-absent-percussion");
        Composition archivedWithClap=new Composition(a.seed,a.title,a.mode,a.theme,a.rights,
            a.key,a.scaleMode,a.bpm,a.duration,a.chords,a.notes,a.tempoMap,a.instrumentPreset,
            a.harmony,List.of(new Composition.Drum("clap",.5,80)),true);
        MusicFingerprint.Signature withDrum=MusicFingerprint.of(archivedWithClap);
        MusicFingerprint.Signature copiedBeatWithDifferentLead=new MusicFingerprint.Signature(
            "0".repeat(64),withDrum.drums(),"1".repeat(64),"2".repeat(64),
            withDrum.melodySketch()^0xAAAAAAAAAAAAAAAAL,withDrum.drumsSketch());
        check(MusicFingerprint.nearDuplicate(withDrum,copiedBeatWithDifferentLead),
            "archived-drum-beat-dedupe-still-works");

        Composition sameMusicDifferentSeed=new Composition(98765,a.title,a.mode,a.theme,a.rights,
            a.key,a.scaleMode,a.bpm,a.duration,a.chords,a.notes,a.tempoMap,a.instrumentPreset,a.harmony,a.drums,a.arranged);
        check(MusicFingerprint.nearDuplicate(fingerprint,MusicFingerprint.of(sameMusicDifferentSeed)),
            "seed-metadata-does-not-evade-duplicate-detection");
        List<Composition.Note> shiftedNotes=a.notes.stream().map(n->new Composition.Note(n.voice(),n.pitch()+12,
            n.start(),n.duration(),n.velocity())).toList();
        List<Composition.Chord> shiftedChords=a.chords.stream().map(ch->new Composition.Chord(ch.start(),ch.duration(),
            ch.symbol(),Arrays.stream(ch.pitches()).map(p->p+12).toArray(),null)).toList();
        List<Composition.Harmony> shiftedHarmony=a.harmony.stream().map(h->new Composition.Harmony(h.start(),h.duration(),
            h.symbol(),Arrays.stream(h.pitches()).map(p->p+12).toArray(),null)).toList();
        Composition transposed=new Composition(a.seed+1,a.title,a.mode,a.theme,a.rights,a.key,a.scaleMode,a.bpm,
            a.duration,shiftedChords,shiftedNotes,a.tempoMap,a.instrumentPreset,shiftedHarmony,a.drums,true);
        check(fingerprint.melody().equals(MusicFingerprint.of(transposed).melody()),
            "octave-shifted-melody-recognized");
        Set<String> musicalSignatures=new HashSet<>();
        for(int i=0;i<20;i++)musicalSignatures.add(MusicFingerprint.of(
            MusicV4.generate(i+100,25,"flow","noir","pop")).melody());
        check(musicalSignatures.size()>=18,"same-style-twenty-song-melodic-diversity");
        // Curated ensembles are selected as one band, never independent
        // lead/bass/pad/arp random choices. Verify every preset and all styles.
        check(Composition.PRESETS.contains("auto-ensemble"),"style-matched-ensemble-default-allowed");
        for(String style:List.of("lofi","ambient","pop","rock","funk","electronic","jazz","cinematic")){
            List<EnsembleCatalog.Ensemble> bands=EnsembleCatalog.choices(style);
            check(bands.size()>=5,"curated-ensembles-available-"+style);
            for(String family:Composition.PRESETS){
                Set<String> used=new HashSet<>();
                for(int trial=0;trial<4;trial++){
                    long seed=1000L+trial;
                    EnsembleCatalog.Ensemble selected=EnsembleCatalog.select(seed,style,family);
                    Composition piece=MusicV4.generate(seed,35,"flow","noir",style,0,family);
                    check(piece.drums.isEmpty()&&
                        piece.title.contains(" ensemble-v5 ")&&
                        piece.instrumentPreset.equals(family),
                        "new-ensemble-format-"+style+"-"+family+"-"+trial);
                    check(EnsembleCatalog.choices(style).contains(selected),
                        "ensemble-from-matching-style-"+style+"-"+family+"-"+trial);
                    check(family.equals("auto-ensemble")||selected.family().equals(family),
                        "preference-in-style-"+style+"-"+family+"-"+trial);
                    for(String role:List.of("lead","bass","pad","arp")){
                        boolean present=piece.notes.stream().anyMatch(n->n.voice().startsWith(role));
                        check(present==selected.supports(role),
                            "no-incompatible-extra-parts-"+style+"-"+family+"-"+role+"-"+trial);
                        if(present)check(SongArrangement.gmProgram(piece,role)==selected.program(role),
                            "all-midi-patches-from-one-ensemble-"+style+"-"+family+"-"+role+"-"+trial);
                    }
                    used.add(selected.name());
                    // Every generated performance must have a stable MIDI patch map.
                    check(piece.json().equals(MusicV4.generate(seed,35,"flow","noir",style,0,family).json()),
                        "curated-ensemble-deterministic-"+style+"-"+family+"-"+trial);
                }
                if(family.equals("auto-ensemble")){
                    for(int trial=4;trial<40;trial++)
                        used.add(EnsembleCatalog.select(1000L+trial,style,family).name());
                    check(used.size()>=4,"varied-but-coherent-ensembles-"+style);
                }
            }
        }
        // Preserve archived V4 playback even when new catalog selections differ.
        Composition oldGenerated=new Composition(a.seed,a.title.replace(" ensemble-v5",""),
            a.mode,a.theme,a.rights,a.key,a.scaleMode,a.bpm,a.duration,a.chords,a.notes,
            a.tempoMap,"soft-keys",a.harmony,a.drums,true);
        check(!oldGenerated.title.contains(" ensemble-v5 ")&&
            SongArrangement.gmProgram(oldGenerated,"lead")>=0,
            "legacy-V4-instrument-patch-replay");

        // Seed variety is now grounded in actual audible instrument notes.
        // An empty drum track never counts as rhythmic diversity.
        for(String style:List.of("pop","jazz","ambient","electronic","cinematic")){
            Set<String> designs=new HashSet<>(),bassPatterns=new HashSet<>(),
                backingPatterns=new HashSet<>();
            Set<Integer> leadInstruments=new HashSet<>();
            for(int i=0;i<24;i++){
                long seed=10100+i;
                Composition song=MusicV4.generate(seed,40,"flow","noir",style);
                check(song.title.startsWith("Original "+style+" ")&&song.drums.isEmpty(),
                    "theme-preserved-no-percussion-"+style+"-"+i);
                designs.add(SongArrangement.identity(seed,style).signature());
                leadInstruments.add(SongArrangement.gmProgram(song,"lead"));
                StringBuilder bassRhythm=new StringBuilder(),backingRhythm=new StringBuilder();
                for(Composition.Note n:song.notes){
                    if(n.start()>=8*60/song.bpm)continue;
                    if(n.voice().startsWith("bass"))
                        bassRhythm.append(Math.round(n.start()*song.bpm/60*4)).append(',');
                    if(n.voice().startsWith("arp")||n.voice().startsWith("pad"))
                        backingRhythm.append(n.voice().startsWith("arp")?"A":"P")
                            .append(Math.round(n.start()*song.bpm/60*4)).append(',');
                }
                bassPatterns.add(bassRhythm.toString());
                backingPatterns.add(backingRhythm.toString());
            }
            check(designs.size()>=21,"unique-arrangement-identities-"+style);
            check(bassPatterns.size()>=4,"seed-specific-bass-rhythm-"+style);
            check(backingPatterns.size()>=4,"seed-specific-real-instrument-rhythm-"+style);
            check(leadInstruments.size()>=4,"seed-specific-lead-instruments-"+style);
        }
        // No generated instrument-only MIDI is allowed to contain any channel
        // 10 percussion. The new rhythm cue must use the same note-on events
        // already present on audible, non-percussion MIDI channels.
        for(String style:List.of("lofi","pop","rock","funk","electronic","jazz","cinematic")){
            for(int i=0;i<8;i++){
                Composition song=MusicV4.generate(9000L+i,48,"flow","noir",style);
                check(song.drums.isEmpty(),"no-automatic-drum-events-"+style+"-"+i);
            }
        }
        Composition instrumentsOnly=MusicV4.generate(722,24,"flow","noir","pop");
        Path instrumentMidi=Files.createTempFile("chordflow-instrument-rhythm",".mid");
        try{
            Music.writeMidi(instrumentsOnly,instrumentMidi);
            Sequence midi=MidiSystem.getSequence(instrumentMidi.toFile());
            Map<String,Integer> expectedHits=new HashMap<>(),actualHits=new HashMap<>();
            for(Composition.Note n:instrumentsOnly.notes){
                int lane=Visual.instrumentRhythmLane(n.voice());
                check(lane>=0&&lane<=2,"instrument-onset-cue-lane-"+n.voice());
                long tick=Math.round(instrumentsOnly.beatAtSeconds(n.start())*960);
                String event=tick+"@"+n.pitch();
                expectedHits.merge(event,1,Integer::sum);
            }
            long percussionEvents=0;
            for(Track track:midi.getTracks())for(int i=0;i<track.size();i++){
                MidiEvent me=track.get(i);
                if(me.getMessage() instanceof ShortMessage sm&&
                    sm.getCommand()==ShortMessage.NOTE_ON&&sm.getData2()>0){
                    if(sm.getChannel()==9)percussionEvents++;
                    else actualHits.merge(me.getTick()+"@"+sm.getData1(),1,Integer::sum);
                }
            }
            check(percussionEvents==0&&actualHits.equals(expectedHits),
                "rhythm-cue-matches-audible-notes-no-channel-10");
            // Historical saved compositions with real drum events still replay.
            Path archivedMidi=Files.createTempFile("chordflow-legacy-midi",".mid");
            try{
                Music.writeMidi(archivedWithClap,archivedMidi);
                Sequence archived=MidiSystem.getSequence(archivedMidi.toFile());
                int oldDrums=0;
                for(Track track:archived.getTracks())for(int i=0;i<track.size();i++)
                    if(track.get(i).getMessage() instanceof ShortMessage sm&&
                        sm.getChannel()==9&&sm.getCommand()==ShortMessage.NOTE_ON&&sm.getData2()>0)
                        oldDrums++;
                check(oldDrums==1,"archived-percussion-replay-preserved");
            }finally{Files.deleteIfExists(archivedMidi);}
        }finally{Files.deleteIfExists(instrumentMidi);}
        MusicQuality.Report quality=MusicQuality.analyze(Music.generate(317,60,"flow","noir"));
        check(quality.leadPhrases()>=4&&quality.distinctMelodyWindows()>=3,
            "multi-section-melodic-development");
        Set<Long> rhythmicOffsets=new HashSet<>();
        for(Composition.Note n:a.notes)if(n.voice().startsWith("lead"))rhythmicOffsets.add(Math.round((n.start()*a.bpm/60%4)*100));
        check(rhythmicOffsets.size()>=3,"uneven-melodic-rhythm");
        Set<Long> lengths=new HashSet<>();for(Composition.Note n:a.notes)if(n.voice().startsWith("lead"))lengths.add(Math.round(n.duration()*1000));
        check(lengths.size()>=3,"varied-note-durations");
        check(a.harmony.size()<a.chords.size()/2,"independent-harmonic-rhythm");
        check(a.drums.isEmpty()&&guitar.drums.isEmpty(),"flow-and-guitar-have-no-extra-drum-layer");
        check(a.harmonyAt(.1).symbol().length()>0,"harmony-visible-independently");
        Composition longer=Music.generate(314,60,"flow","noir");
        List<Composition.Note> longerLead=longer.notes.stream().filter(n->n.voice().startsWith("lead")).toList();
        Set<Integer> leadPitches=new HashSet<>();Set<Long> leadGaps=new HashSet<>();
        for(int i=0;i<longerLead.size();i++){
            leadPitches.add(longerLead.get(i).pitch());
            if(i>0)leadGaps.add(Math.round((longerLead.get(i).start()-longerLead.get(i-1).start())*100));
        }
        check(leadPitches.size()>=6,"multi-phrase-melodic-contour");
        check(leadGaps.size()>=4,"multi-phrase-rhythmic-variation");
        check(longer.harmony.size()*2<longer.chords.size(),"nonuniform-chord-versus-note-timing");
        check(longer.harmony.stream().map(h->Math.round(h.duration()*10)).distinct().count()>=2,"variable-chord-length");
        Composition oldTheme=Music.generate(42,6,"flow","blue");
        Visual newPalette=new Visual(oldTheme,240,426,30);
        check(newPalette.frame(0).length==240*426*3,"legacy-theme-fallback-renders");
        Path tmp=Files.createTempDirectory("chordflow-test-");try{
            Path midi=tmp.resolve("test.mid"),wav=tmp.resolve("test.wav");Music.writeMidi(a,midi);Sequence seq=MidiSystem.getSequence(midi.toFile());
            long midiOnsets=0;for(Track track:seq.getTracks())for(int mi=0;mi<track.size();mi++){
                MidiMessage msg=track.get(mi).getMessage();
                if(msg instanceof ShortMessage sm&&sm.getChannel()!=9&&sm.getCommand()==ShortMessage.NOTE_ON&&sm.getData2()>0)midiOnsets++;
            }
            check(midiOnsets==a.notes.size()&&seq.getTracks().length>=5,"multitrack-midi-export-accurate");
            Composition imported=Music.importMidi(midi,"Licensed by creator",192,10,"gold");check(imported.notes.size()==a.notes.size(),"authorized-midi-import-roundtrip");
            Path compositionPath=tmp.resolve("saved.composition.json");a.save(compositionPath);
            Composition loaded=CompositionIO.read(compositionPath);
            check(a.json().equals(loaded.json()),"saved-json-roundtrip-identical");
            boolean malformedRejected=false;
            Path malformed=tmp.resolve("malformed.json");Files.writeString(malformed,a.json().replace("\"schemaVersion\":3", "\"schemaVersion\":99"));
            try{CompositionIO.read(malformed);}catch(java.io.IOException expected){malformedRejected=true;}
            check(malformedRejected,"unknown-composition-schema-rejected");
            malformedRejected=false;Files.writeString(malformed,a.json()+" garbage");
            try{CompositionIO.read(malformed);}catch(java.io.IOException expected){malformedRejected=true;}
            check(malformedRejected,"composition-trailing-data-rejected");
            malformedRejected=false;Files.writeString(malformed,a.json().replace("\"frets\":null", "\"frets\":null,\"frets\":null"));
            try{CompositionIO.read(malformed);}catch(java.io.IOException expected){malformedRejected=true;}
            check(malformedRejected,"duplicate-null-key-rejected");
            Composition escaped=new Composition(a.seed,"Title \"quoted\"",a.mode,a.theme,"Creator \n rights",a.key,a.scaleMode,a.bpm,a.duration,a.chords,a.notes,a.tempoMap,a.instrumentPreset);
            escaped.save(tmp.resolve("escaped.json"));
            check(escaped.json().equals(CompositionIO.read(tmp.resolve("escaped.json")).json()),"json-escape-roundtrip");
            Audio.Stats stats=Audio.synth(a,wav);check(stats.rms()>.007&&stats.peak()<.98,"nonsilent-true-synthesis");check(Files.size(wav)>1_000_000,"wav-real-pcm");
            Set<String> instrumentHashes=new HashSet<>();
            for(String preset:List.of("warm-pad","soft-keys","nylon-pluck","glass","layered")){
                Composition pc=a.withInstrument(preset);
                check(pc.json().contains("\"instrumentPreset\":\""+preset+"\""),"preset-serialized-"+preset);
                Path pw=tmp.resolve(preset+".wav");Audio.synth(pc,pw);instrumentHashes.add(Production.hash(pw));
            }
            check(instrumentHashes.size()==5,"five-distinct-instrument-timbres");
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