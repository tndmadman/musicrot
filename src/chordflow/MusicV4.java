package chordflow;

import java.util.*;

/**
 * Structure-first composition engine. Every part reads the same tonal, harmonic,
 * sectional and groove plan. Seeded variation changes notes, rhythms and form,
 * not only the metadata or the final instrument patch.
 *
 * V4 intentionally keeps the canonical V3 event format for old replay/visuals.
 */
public final class MusicV4 {
    private MusicV4() {}
    private static final String[] ROOTS={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};
    private static final int[] MAJOR={0,2,4,5,7,9,11}, MINOR={0,2,3,5,7,8,10};
    private static final int[][] MAJOR_PROGRESSIONS={
        {0,5,3,4,0,3,1,4},{5,3,0,4,5,1,4,0},{0,4,5,3,1,4,0,0},
        {1,4,0,5,3,4,0,0},{0,3,4,3,5,1,4,0},{5,1,4,0,3,4,5,0},
        {0,2,5,3,0,4,3,4},{3,0,4,5,1,4,0,0}
    };
    private static final int[][] MINOR_PROGRESSIONS={
        {0,5,2,6,0,3,4,0},{0,3,6,2,5,3,4,0},{0,6,5,6,3,6,4,0},
        {0,2,5,3,0,5,4,0},{5,6,0,3,5,6,4,0}
    };
    private static final int[][] GUITAR_PROGRESSIONS={
        {0,1,7,2,8,2,0,1},{0,7,2,0,1,7,0,2},
        {1,7,0,2,8,2,0,1},{16,17,18,19,0,1,7,2}
    };
    private static final int[][] GUITAR={
        {-1,3,2,0,1,0}, {-1,0,2,2,1,0}, {3,2,0,0,0,3}, {-1,-1,0,2,3,2},
        {-1,3,2,0,0,0},{-1,0,2,2,2,0},{3,2,0,0,0,1}, {1,3,3,2,1,1},
        {-1,-1,0,2,3,1},{-1,1,3,3,3,1},{-1,-1,0,2,1,1},{0,2,2,1,0,0},
        {0,2,2,0,0,0},{-1,0,2,0,1,0},{3,2,0,0,0,1},{-1,3,2,0,1,3}, {0,3,2,0,1,0}, {-1,0,3,2,1,1}, {-1,2,0,0,0,3}, {3,3,2,0,1,0}
    };
    private static final String[] GUITAR_NAMES={"C","Am","G","D","Cmaj7","A","G7","F","Dm","Bb","Dm7","E","Em","Am7","G7","Cadd9","C/E","F/A","G/B","C/G"};
    private static final int[] TUNING={40,45,50,55,59,64};

    /** Style is a collection of compositional decisions, not just a sound label. */
    public record Style(String id,int bpmMin,int bpmMax,double swing,double density,
                        int backingFamily,int phraseBars,int[] chordLengths) {}
    private static final Style[] STYLES={
        new Style("lofi",75,98,.075,.68,0,2,new int[]{8,4,4,8,4,4}),
        new Style("ambient",60,85,0,.38,1,4,new int[]{8,8,4,8,8,4}),
        new Style("pop",98,127,.012,.80,2,2,new int[]{4,4,8,4,4,8}),
        new Style("rock",103,142,.006,.78,3,2,new int[]{4,4,4,8,4,4}),
        new Style("funk",92,123,.047,.88,4,2,new int[]{4,4,4,4,8,4}),
        new Style("electronic",106,138,0,.74,5,2,new int[]{8,4,4,8,4,4}),
        new Style("jazz",90,138,.10,.76,6,2,new int[]{4,8,4,4,4,8}),
        new Style("cinematic",68,105,0,.59,7,4,new int[]{8,8,4,8,4,8})
    };

    private record Plan(long seed,Style style,int key,boolean minor,double bpm,double beat,
                        double totalBeats,int[] progression,int[] scale,boolean guitar,int motifStart) {}
    public static Composition generate(long seed,int seconds,String mode,String theme){
        return generate(seed,seconds,mode,theme,"auto");
    }
    public static Composition generate(long seed,int seconds,String mode,String theme,String requestedStyle){
        return generate(seed,seconds,mode,theme,requestedStyle,0);
    }
    public static Composition generate(long seed,int seconds,String mode,String theme,String requestedStyle,int variation){
        return generate(seed,seconds,mode,theme,requestedStyle,variation,"auto-ensemble");
    }
    /** Select the musical ensemble BEFORE creating the accompaniment notes. */
    public static Composition generate(long seed,int seconds,String mode,String theme,String requestedStyle,
                                       int variation,String instrumentPreference){
        return generate(seed,seconds,mode,theme,requestedStyle,variation,instrumentPreference,Map.of());
    }
    public static Composition generate(long seed,int seconds,String mode,String theme,String requestedStyle,
                                       int variation,String instrumentPreference,Map<String,Integer> overrides){
        return generate(seed,seconds,mode,theme,requestedStyle,variation,instrumentPreference,overrides,0,-1);
    }
    /** bpm=0 and key=-1 choose genre-appropriate automatic defaults. */
    public static Composition generate(long seed,int seconds,String mode,String theme,String requestedStyle,
                                       int variation,String instrumentPreference,Map<String,Integer> overrides,
                                       int requestedBpm,int requestedKey){
        if(requestedBpm!=0&&(requestedBpm<35||requestedBpm>220))
            throw new IllegalArgumentException("MANUAL_BPM_OUT_OF_RANGE");
        if(requestedKey<-1||requestedKey>11||(mode.equals("guitar")&&requestedKey>0))
            throw new IllegalArgumentException("MANUAL_KEY_INCOMPATIBLE_WITH_GUITAR_SHAPES");
        if(overrides==null)throw new IllegalArgumentException("INSTRUMENT_SELECTION_MISSING");
        if(variation<0||variation>12)throw new IllegalArgumentException("INVALID_COMPOSITION_VARIATION");
        if(!Composition.PRESETS.contains(instrumentPreference))
            throw new IllegalArgumentException("INSTRUMENT_PRESET_INVALID");
        if(seconds<2||seconds>180)throw new IllegalArgumentException("DURATION_OUT_OF_RANGE");
        if(!Set.of("flow","guitar").contains(mode))throw new IllegalArgumentException("INVALID_MODE");
        long planSeed=seed^(0xD1B54A32D192ED03L*variation);
        Random r=new Random(planSeed^0x6A09E667F3BCC909L);
        Style style=null;
        if(requestedStyle.equals("auto"))style=STYLES[Math.floorMod(seed,STYLES.length)];
        else for(Style s:STYLES)if(s.id().equals(requestedStyle))style=s;
        if(style==null)throw new IllegalArgumentException("INVALID_MUSIC_STYLE");
        boolean guitar=mode.equals("guitar"),minor=!guitar&&r.nextBoolean();
        // The guitar library is deliberately C-major/Am-compatible. Never apply an unrelated key.
        int key=requestedKey>=0?requestedKey:guitar?0:r.nextInt(12);
        int tempo=requestedBpm>0?requestedBpm:style.bpmMin()+r.nextInt(style.bpmMax()-style.bpmMin()+1);
        int[] progressions=guitar?GUITAR_PROGRESSIONS[r.nextInt(GUITAR_PROGRESSIONS.length)]:
            (minor?MINOR_PROGRESSIONS:MAJOR_PROGRESSIONS)[r.nextInt(minor?MINOR_PROGRESSIONS.length:MAJOR_PROGRESSIONS.length)];
        double beat=60.0/tempo,totalBeats=seconds/beat;
        Plan plan=new Plan(planSeed,style,key,minor,tempo,beat,totalBeats,progressions,minor?MINOR:MAJOR,guitar,r.nextInt(7));
        // The band is chosen as a unit, not as independent random MIDI patches.
        EnsembleCatalog.Ensemble ensemble=InstrumentRegistry.customize(
            EnsembleCatalog.select(planSeed,style.id(),instrumentPreference),overrides);
        if(guitar&&overrides.containsKey("lead"))
            InstrumentRegistry.validate("guitar",overrides.get("lead"));
        List<Composition.Harmony> harmony=harmonize(plan,r,seconds);
        List<Composition.Note> notes=new ArrayList<>();
        // Newly generated songs are entirely instrument-led. No automatic drum events.
        List<Composition.Drum> drums=List.of();
        melody(plan,harmony,notes,seconds);
        // Preserve exact physical guitar fretting while varying the rhythm section.
        if(guitar)accompaniment(plan,harmony,notes,seconds,r);
        SongArrangement.arrange(planSeed,style.id(),mode,tempo,seconds,harmony,notes,ensemble);
        // Freeze programs in the composition; a later catalog change must not
        // silently alter the sounds of a previously produced song.
        Map<String,Integer> programs=new LinkedHashMap<>();
        int guitarPatch=overrides.getOrDefault("guitar",
            overrides.getOrDefault("lead",EnsembleCatalog.guitarProgram(style.id(),planSeed)));
        if(guitar)InstrumentRegistry.validate("guitar",guitarPatch);
        for(Composition.Note note:notes){
            String voice=note.voice();
            String role=voice.startsWith("lead")?"lead":voice.startsWith("bass")?"bass":
                voice.startsWith("guitar")?"guitar":voice.startsWith("pad")?"pad":
                voice.startsWith("arp")?"arp":null;
            if(role==null||programs.containsKey(role))continue;
            int patch=guitar&&(role.equals("lead")||role.equals("guitar"))?guitarPatch:ensemble.program(role);
            programs.put(role,patch);
        }
        InstrumentPerformance.apply(notes,programs,style.id(),planSeed,tempo,seconds);
        notes.sort(Comparator.comparingDouble(Composition.Note::start).thenComparing(Composition.Note::voice));
        List<Composition.Chord> snapshots=snapshots(notes,harmony,seconds);
        // ensemble-v5 title marker preserves the archived V5 format.
        Composition output=new Composition(seed,"Original "+style.id()+" arrangement "+seed+" ensemble-v5 variation "+variation,mode,theme,
            "ORIGINAL_GENERATED",ROOTS[key],minor?"minor":"major",tempo,seconds,
            snapshots,notes,List.of(new Composition.Tempo(0,tempo)),
            instrumentPreference,harmony,drums,true,programs);
        MusicQuality.ensureBasicIntegrity(output);
        return output;
    }
    private static List<Composition.Harmony> harmonize(Plan p,Random r,int seconds){
        List<Composition.Harmony> hs=new ArrayList<>();
        int[] pattern=p.style().chordLengths();
        double at=0;int index=0;
        // Lengths and progression shifts change at structural boundaries.
        while(at<p.totalBeats()-1e-8){
            int len=pattern[(index+p.motifStart())%pattern.length];
            double remaining=p.totalBeats()-at;
            double duration=Math.min(len,remaining);
            // Reserve a complete final tonic where possible. Avoid abruptly
            // cutting an unrelated progression off halfway through its cycle.
            if(remaining>5.0&&remaining-duration<4.0)duration=remaining-4.0;
            boolean closingChord=remaining-duration<1e-6;
            int degree=closingChord?0:p.progression()[(index+(index/6)%3)%p.progression().length];
            int[] pitches,frets=null;String symbol;
            if(p.guitar()){
                int chord=closingChord?0:p.progression()[(index+(index/6)%2)%p.progression().length];
                frets=GUITAR[chord].clone();symbol=GUITAR_NAMES[chord];
                int count=0;for(int f:frets)if(f>=0)count++;
                pitches=new int[count];for(int i=0,j=0;i<6;i++)if(frets[i]>=0)pitches[j++]=TUNING[i]+frets[i];
            }else{
                // Construct actual scale-degree triads and seventh chords. In
                // particular, the minor ii chord is diminished, not major,
                // and minor sevenths differ from major sevenths.
                int root=Math.floorMod(p.key()+p.scale()[degree],12);
                int third=p.scale()[(degree+2)%7]+(degree+2>=7?12:0)-p.scale()[degree];
                int fifth=p.scale()[(degree+4)%7]+(degree+4>=7?12:0)-p.scale()[degree];
                int seventhInterval=p.scale()[(degree+6)%7]+(degree+6>=7?12:0)-p.scale()[degree];
                if(p.minor()&&degree==4){third=4;fifth=7;seventhInterval=10;} // harmonic minor V7
                boolean seventh=p.style().id().equals("jazz")||p.style().id().equals("lofi")&&index%3==0;
                pitches=seventh?new int[]{36+root,60+root,60+root+third,60+root+fifth,60+root+seventhInterval}:
                    new int[]{36+root,60+root,60+root+third,60+root+fifth};
                String triad=(third==3&&fifth==6)?"dim":third==3?"m":"";
                String qualitySuffix=!seventh?triad:
                    (third==3&&fifth==6)?"m7b5":
                    third==3?(seventhInterval==11?"m(maj7)":"m7"):
                    seventhInterval==11?"maj7":"7";
                symbol=ROOTS[root]+qualitySuffix;
            }
            hs.add(new Composition.Harmony(at*p.beat(),duration*p.beat(),symbol,pitches,frets));
            at+=duration;index++;
        }
        return hs;
    }
    private static int[] chordPcs(Composition.Harmony h){
        return Arrays.stream(h.pitches()).map(x->Math.floorMod(x,12)).distinct().toArray();
    }
    private static Composition.Harmony harmonyAt(List<Composition.Harmony> hs,double t){
        Composition.Harmony a=hs.get(0);for(Composition.Harmony h:hs){if(h.start()>t+1e-8)break;a=h;}return a;
    }
    private static int chordToneNear(int target,Composition.Harmony chord){
        int best=target,dist=999;
        for(int pc:chordPcs(chord))for(int oct=4;oct<=7;oct++){
            int candidate=oct*12+pc;
            if(candidate<60||candidate>84)continue;
            int d=Math.abs(candidate-target);if(d<dist){dist=d;best=candidate;}
        }
        return best;
    }
    private static int scaleNote(Plan p,int degree,int register){
        return 60+p.key()+p.scale()[Math.floorMod(degree,7)]+register*12;
    }
    private static void melody(Plan p,List<Composition.Harmony> hs,List<Composition.Note> notes,int seconds){
        SongArrangement.Identity identity=SongArrangement.identity(p.seed(),p.style().id());
        int last=65+p.key();
        // A song owns a small interval theme. Each phrase develops the same
        // melodic vocabulary, instead of rolling completely unrelated notes.
        Random motifRng=new Random(p.seed()^0x165667B19E3779F9L);
        int[] motif=new int[6];
        for(int m=1;m<motif.length;m++)
            motif[m]=Math.max(-3,Math.min(3,motif[m-1]+motifRng.nextInt(5)-2));
        int phrase=0;
        // Each two-bar statement gets a distinct rhythm and motif treatment. Return
        // themes reappear as a developed hook, never as an indefinite copied loop.
        for(double start=0;start<p.totalBeats()-1e-7;start+=8,phrase++){
            int phraseKind=phrase%4,cycle=phrase/4;
            Random r=new Random(p.seed()^0x2545F4914F6CDD1DL^(phrase*0x9E3779B97F4A7C15L));
            // The third phrase intentionally reprises the first phrase's
            // rhythmic motif, even though it must adapt to new harmony.
            Random phraseRhythm=new Random(p.seed()^0x735A2D97BC3A27E9L^
                ((long)cycle*0xD1342543DE82EF95L)^
                ((long)(phraseKind==2?0:phraseKind)*0x9E3779B97F4A7C15L));
            boolean response=phraseKind==1,returnHook=phraseKind==2;
            int contour=(p.motifStart()+cycle+(response?2:phraseKind==3?3:0))%5;
            int anchor=Math.floorMod(p.motifStart()+cycle*3+
                new Random(p.seed()^((long)cycle*0x94D049BB133111EBL)).nextInt(3),7);
            double position=0;int event=0;
            while(position<8-1e-6&&start+position<p.totalBeats()-.05){
                // Stable rhythm language belongs to the song identity: lyrical,
                // offbeat, rolling, deliberate or call-and-response.
                double length=switch(identity.melody()){
                    case 0->new double[]{.5,.75,1,1.25,1.5,2}[phraseRhythm.nextInt(6)];
                    case 1->new double[]{1,1.5,2,2,2.5,3}[phraseRhythm.nextInt(6)];
                    case 2->new double[]{.5,.5,.75,1,1.25,1.5}[phraseRhythm.nextInt(6)];
                    case 3->new double[]{.75,1,1,1.5,2,2.25}[phraseRhythm.nextInt(6)];
                    default->new double[]{.5,.75,1.5,2,2.5,1}[event%6];
                };
                if(p.style().id().equals("ambient"))length=Math.max(.9,length*1.2);
                length=Math.min(length,8-position);
                boolean rest=(phraseRhythm.nextDouble()>(p.style().density()+.10))||
                    event>0&&phraseRhythm.nextDouble()<(identity.melody()==1?.18:.07);
                double swing=Math.min(.10,p.style().swing())*((event%2==1)?1:0);
                double onset=start+position+swing;
                if(!rest&&onset<p.totalBeats()-.05){
                    double x=position/8.0;
                    int shape=switch(contour){
                        case 0->(int)Math.round(x*4); // climbing answer
                        case 1->(int)Math.round((1-x)*4); // descent
                        case 2->(int)Math.round(4*(1-Math.abs(2*x-1))); // arch
                        case 3->(int)Math.round(2*Math.sin(x*2*Math.PI));
                        default->(int)Math.round(x*2);
                    };
                    int motifShift=motif[(event+(response?2:0))%motif.length];
                    if(response)motifShift=-motifShift;
                    // Reprise the motif every fourth phrase; answer phrases
                    // move in a contrasting direction over the new harmony.
                    int degree=Math.floorMod(anchor+shape+(response?-1:0)
                        +motifShift+(phraseKind==3?-1:0),7);
                    int target=scaleNote(p,degree,0);
                    if(target<64)target+=12;if(target>83)target-=12;
                    // Keep steps singable; let big leaps land on chord tones.
                    if(Math.abs(target-last)>8)target+=(target<last?12:-12);
                    if(target<60)target+=12;if(target>84)target-=12;
                    Composition.Harmony h=harmonyAt(hs,onset*p.beat());
                    boolean strong=Math.abs(position-Math.rint(position/2)*2)<.12||
                        position+length>=8-.001;
                    if(strong)target=chordToneNear(target,h);
                    double next=Math.min(8,position+length);
                    double duration=Math.max(.13,Math.min(length*(r.nextBoolean()?.82:.91),next-position-.04));
                    addNote(notes,"lead-0",target,onset*p.beat(),duration*p.beat(),72+r.nextInt(34),seconds);
                    last=target;
                }
                position+=length;event++;
            }
        }
        if(notes.stream().noneMatch(n->n.voice().equals("lead-0"))){
            Composition.Harmony h=hs.get(0);addNote(notes,"lead-0",chordToneNear(72,h),0,.5*p.beat(),90,seconds);
        }
    }
    private static void accompaniment(Plan p,List<Composition.Harmony> hs,List<Composition.Note> notes,int seconds,Random r){
        int[] priorVoicing=null;
        for(int i=0;i<hs.size();i++){
            Composition.Harmony h=hs.get(i);
            double from=h.start()/p.beat(),end=(h.start()+h.duration())/p.beat();
            int root=Arrays.stream(h.pitches()).min().orElse(36);
            while(root>47)root-=12;while(root<33)root+=12;
            int fifth=root+7;
            for(double at=from;at<end-.12;at+=2){
                int beatIndex=(int)Math.round((at-from)/2);
                int pitch=beatIndex%3==1?fifth:root;
                double duration=Math.min(p.style().backingFamily()==1?1.75:1.18,end-at);
                addNote(notes,"bass-0",pitch,at*p.beat(),duration*p.beat(),75+r.nextInt(29),seconds);
                if(p.style().backingFamily()==4&&at+1.5<end){
                    addNote(notes,"bass-0",root+12,(at+1.5)*p.beat(),.32*p.beat(),58+r.nextInt(21),seconds);
                }
            }
            if(p.guitar()){
                int[] frets=h.frets(),pattern={0,1,2,1,0,2,1,2};
                int stroke=0;
                for(double at=from;at<end-.14;at+=stroke++%3==2?1.5:1){
                    if((stroke+i)%7==5)continue;
                    boolean down=pattern[(stroke+i)%pattern.length]%2==0;
                    for(int s=0;s<6;s++){
                        int ix=down?s:5-s;if(frets[ix]<0)continue;
                        double start=at+.018*s/p.beat();
                        if(start>=end)continue;
                        addNote(notes,"guitar-"+ix,TUNING[ix]+frets[ix],start*p.beat(),
                            Math.min(.68,end-start)*p.beat(),60+r.nextInt(31),seconds);
                    }
                }
            }else{
                int[] chord=h.pitches(),voiced=chord.clone();
                for(int v=1;v<chord.length;v++){
                    int target=priorVoicing!=null&&v<priorVoicing.length?priorVoicing[v]:chord[v];
                    int best=chord[v],distance=Integer.MAX_VALUE;
                    for(int octave=-2;octave<=1;octave++){
                        int candidate=chord[v]+12*octave;
                        if(candidate<53||candidate>82)continue;
                        int d=Math.abs(candidate-target);
                        if(d<distance){best=candidate;distance=d;}
                    }
                    voiced[v]=best;
                    addNote(notes,"pad-"+v,best,h.start(),
                        Math.min(h.duration()*.92,seconds-h.start()),48+r.nextInt(18),seconds);
                }
                priorVoicing=voiced;
                int j=0;
                for(double at=from+.5;at<end-.17;at+=(p.style().id().equals("ambient")?1.5:.75)){
                    if((j+i)%5==4){j++;continue;}
                    int pitch=voiced[1+(j++%(voiced.length-1))];
                    addNote(notes,"arp-"+j%3,pitch,at*p.beat(),Math.min(.42,end-at)*p.beat(),
                        40+r.nextInt(29),seconds);
                }
            }
        }
    }
    private static List<Composition.Chord> snapshots(List<Composition.Note> notes,List<Composition.Harmony> harmony,int seconds){
        TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add((double)seconds);
        for(Composition.Note n:notes){cuts.add(n.start());cuts.add(n.start()+n.duration());}
        List<Double> times=new ArrayList<>(cuts);List<Composition.Chord> chords=new ArrayList<>();
        for(int i=0;i+1<times.size();i++){
            double a=times.get(i),b=times.get(i+1);if(b-a<1e-8)continue;
            double mid=(a+b)/2;
            int[] sounding=notes.stream().filter(n->n.start()<=mid&&n.start()+n.duration()>mid)
                .mapToInt(Composition.Note::pitch).toArray();
            Composition.Harmony h=harmonyAt(harmony,mid);
            chords.add(new Composition.Chord(a,b-a,sounding.length==0?"Rest":h.symbol(),sounding,null));
        }
        return chords;
    }
    private static void addNote(List<Composition.Note> notes,String voice,int pitch,double start,double duration,int velocity,int seconds){
        double length=Math.min(duration,seconds-start);
        if(length>1e-7&&start>=0&&start<seconds)notes.add(new Composition.Note(voice,Math.max(0,Math.min(127,pitch)),start,length,velocity));
    }

}
