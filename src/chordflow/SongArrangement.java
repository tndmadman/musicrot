package chordflow;

import java.util.*;

/**
 * Per-song arrangement identity: consistent musical style, seed-distinct rhythms,
 * accompaniment, form and GM instrument palettes. No external model or randomness
 * after the seed is chosen. All timing is derived from canonical beat positions.
 */
public final class SongArrangement {
    private SongArrangement() {}
    private static final long VARIATION_SALT=0xD1B54A32D192ED03L;
    public record Identity(int groove,int bass,int backing,int melody,int form,int palette,
                           int drums) {
        public String signature(){return groove+"-"+bass+"-"+backing+"-"+melody+"-"+form+"-"+palette+"-"+drums;}
    }
    public static Identity identity(long planSeed,String style){
        Random r=new Random(mix(planSeed^((long)style.hashCode()<<32)^0x75BCE3D4F0E9312BL));
        return new Identity(r.nextInt(7),r.nextInt(6),r.nextInt(6),r.nextInt(5),
            r.nextInt(4),r.nextInt(6),r.nextInt(5));
    }
    private static long mix(long z){
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
        z=(z^(z>>>27))*0x94d049bb133111ebL;
        return z^(z>>>31);
    }
    // Sections use bars rather than seconds; duration-2s smoke tests still
    // get full musical parts, while long videos develop dynamic contrast.
    static int section(Identity id,int bar,int bars){
        if(bars<12)return 1; // short compositions: no artificial intros
        int intro=id.form()%2==0?2:1;
        int outro=bars>=18?2:1;
        if(bar<intro)return 0;             // restrained introduction
        if(bar>=bars-outro)return 4;       // release
        int middle=bars/2+(id.form()%3-1);
        if(bars>=17&&bar>=middle&&bar<middle+2)return 3; // breakdown
        int hook=bars/3+(id.form()%2==0?0:1);
        return bar>=hook&&bar<hook+Math.max(3,bars/4)?2:1; // hook / verse
    }
    private static double energy(int section){
        return switch(section){case 0->.57;case 2->1.12;case 3->.43;case 4->.61;default->.91;};
    }
    public static void arrange(long planSeed,String style,String mode,int bpm,int seconds,
                               List<Composition.Harmony> harmony,List<Composition.Note> notes,
                               EnsembleCatalog.Ensemble ensemble){
        Identity id=identity(planSeed,style);
        double beat=60.0/bpm,total=seconds/beat;
        int bars=(int)Math.ceil(total/4.0);
        List<SongFormPlanner.Section> form=SongFormPlanner.plan(total,planSeed,style);
        boolean guitar=mode.equals("guitar");
        // Remove the old root/fifth-on-every-second-beat and constant .75-beat
        // arpeggiator. Keeping them would drown out the new musical identity.
        notes.removeIf(n->n.voice().startsWith("bass")||n.voice().startsWith("pad")||
            n.voice().startsWith("arp"));
        // Rhythmic movement comes from the bass, melody, and accompaniment.
        // No standalone automatic percussion is composed or appended.
        // Lead phrasing survives, while sections control presence and intensity.
        for(int i=notes.size()-1;i>=0;i--){
            Composition.Note n=notes.get(i);
            int sec=guitar?section(id,(int)Math.floor(n.start()/beat/4),bars):sectionForForm(form,(int)Math.floor(n.start()/beat/4));
            if(n.voice().startsWith("lead")&&bars>=12){
                if(sec==3&&((i+id.melody())%3!=0)&&n.start()>beat*4){
                    notes.remove(i); // actual breathing room in the breakdown
                    continue;
                }
                int velocity=(int)Math.round(n.velocity()*Math.min(1.10,energy(sec)));
                notes.set(i,new Composition.Note(n.voice(),n.pitch(),n.start(),n.duration(),
                    Math.max(37,Math.min(120,velocity))));
            }
        }
        for(int hIndex=0;hIndex<harmony.size();hIndex++){
            Composition.Harmony h=harmony.get(hIndex);
            double from=h.start()/beat,to=(h.start()+h.duration())/beat;
            if(to-from<.05)continue;
            int[] chord=h.pitches();
            int root=chord[0];
            while(root>47)root-=12;
            while(root<33)root+=12;
            int third=chord.length>2?root+Math.floorMod(chord[2]-chord[1],12):root+4;
            int fifth=chord.length>3?root+Math.floorMod(chord[3]-chord[1],12):root+7;
            int[] tones={root,third,fifth,root+12};
            // Chord change is always an anchor, even in sparse arrangements.
            addNote(notes,"bass-0",root,from,Math.min(to-from,id.bass()==4?2.5:.95),
                76,beat,seconds);
            int firstBar=(int)Math.floor(from/4.0),lastBar=(int)Math.ceil(to/4.0);
            for(int bar=firstBar;bar<lastBar;bar++){
                int sec=guitar?section(id,bar,bars):sectionForForm(form,bar);
                double e=energy(sec);
                double[] bp=GroovePlanner.bassOnsets(planSeed,style,bar,id.groove());
                for(int j=0;j<bp.length;j++){
                    double at=bar*4.0+bp[j];
                    if(at<from+.12||at>=to-.04||at>=total-.04)continue;
                    if(sec==3&&j>0||sec==0&&j>1||sec==4&&j>1)continue;
                    int pitch=switch(id.bass()){
                        case 0->j%3==1?fifth:root;
                        case 1->tones[j%4];
                        case 2->j%2==1?root+12:root;
                        case 3->j%3==1?third:j%3==2?fifth:root;
                        case 4->j%2==0?root:fifth;
                        default->tones[(j*3+1)%4];
                    };
                    double length=Math.min(to-at,(id.bass()==4?1.80:j%2==0?.86:.42));
                    addNote(notes,"bass-0",pitch,at,length,Math.max(43,(int)(86*e)),
                        beat,seconds);
                }
            }
            if(guitar)continue; // Keep physically valid, exact fret-derived guitar notes.
            int[] voiced=voiceChord(chord,hIndex==0?null:harmony.get(hIndex-1).pitches());
            int sec=guitar?section(id,(int)Math.floor(from/4),bars):sectionForForm(form,(int)Math.floor(from/4));
            double e=energy(sec);
            // Ensemble decides orchestration. The old code forced a pad and an
            // arpeggio into every song, even when they were incompatible.
            // New optional roles stay silent rather than importing alien timbres.
            boolean hasPad=ensemble.supports("pad");
            boolean hasArp=ensemble.supports("arp");
            double gap=(id.backing()%2==0?.5:.75);
            if(ensemble.name().startsWith("Custom ")){
                // Honor every explicitly selected role, even when the backing
                // preset would ordinarily skip it or the chord is short.
                if(hasPad){
                    if(ensemble.backing().equals("sustain")||ensemble.backing().equals("space"))
                        pad(notes,voiced,from,Math.min(to-from,4.0),.50*e,beat,seconds);
                    else
                        for(double at=from;at<to-.04;at+=2.0)
                            pad(notes,voiced,at,Math.min(.55,to-at),.47*e,beat,seconds);
                }
                if(hasArp){
                    double start=from+Math.min(.25,(to-from)*.2);
                    double pace=style.equals("ambient")||style.equals("cinematic")?2.0:
                        style.equals("funk")?.75:1.25;
                    arp(notes,voiced,start,to,pace,id.backing(),hasPad?.36*e:.59*e,beat,seconds);
                    if(notes.stream().noneMatch(n->n.voice().startsWith("arp")&&
                        n.start()>=h.start()&&n.start()<h.start()+h.duration())){
                        // A short chord must not silently erase a selected instrument.
                        int tone=voiced.length>1?voiced[1]:voiced[0];
                        addNote(notes,"arp-0",tone,from,Math.min(.20,to-from),
                            Math.max(31,(int)(45*e)),beat,seconds);
                    }
                }
                continue;
            }
            switch(ensemble.backing()){
                case "sustain"->{
                    if(hasPad)pad(notes,voiced,from,Math.min(to-from,5.5),.66*e,beat,seconds);
                    if(hasArp&&to-from>4.0)
                        arp(notes,voiced,from+2.0,to,2.5,id.backing(),.33*e,beat,seconds);
                }
                case "comp"->{
                    // Short, rhythmic chord stabs: do not saturate the melody.
                    if(hasPad)for(int block=(int)Math.floor(from/4);block<(int)Math.ceil(to/4);block++)
                        for(double offset:GroovePlanner.compOnsets(planSeed,style,block)){
                            double at=block*4+offset;
                            if(at>=from&&at<to-.08)
                                pad(notes,voiced,at,Math.min(.46,to-at),.57*e,beat,seconds);
                        }
                    if(hasArp&&!hasPad)
                        arp(notes,voiced,from+.75,to,1.5,id.backing(),.69*e,beat,seconds);
                }
                case "pluck"->{
                    if(hasArp)arp(notes,voiced,from+.25,to,
                        id.backing()%2==0?.75:1.25,id.backing(),.68*e,beat,seconds);
                    if(hasPad&&to-from>3)
                        pad(notes,voiced,from,Math.min(.60,to-from),.38*e,beat,seconds);
                }
                case "space"->{
                    if(hasPad)pad(notes,voiced,from,Math.min(to-from,4.8),.47*e,beat,seconds);
                    if(hasArp&&to-from>2.5)
                        arp(notes,voiced,from+1.5,to,2.5,id.backing(),.44*e,beat,seconds);
                }
                default->throw new IllegalArgumentException("INVALID_ENSEMBLE_BACKING");
            }
        }
        // Do not generate percussion. Imported/archived MIDI drums are replay-only.
    }
    private static int sectionForForm(List<SongFormPlanner.Section> form,int bar){
        return switch(SongFormPlanner.at(form,bar).kind()){
            case "intro"->0;
            case "outro"->4;
            case "bridge"->3;
            case "answer","return","development"->2;
            default->1;
        };
    }
    private static int[] voiceChord(int[] chord,int[] previous){
        int[] out=chord.clone();
        for(int i=1;i<out.length;i++){
            int ref=previous!=null&&i<previous.length?previous[i]:out[i];
            int best=out[i],distance=Integer.MAX_VALUE;
            for(int o=-2;o<=1;o++){
                int candidate=out[i]+12*o;
                if(candidate<52||candidate>84)continue;
                int d=Math.abs(candidate-ref);
                if(d<distance){distance=d;best=candidate;}
            }
            out[i]=best;
        }
        return out;
    }
    private static double[] bassPattern(int n,String style,int bar,int groove){
        if(style.equals("ambient")&&n%2==0)return new double[]{0,bar%4==3?3.0:2.75};
        double[] pattern=switch(n){
            case 0->new double[]{0,2,3.5};
            case 1->new double[]{0,1,2,3};
            case 2->new double[]{0,.75,2.5,3.25};
            case 3->new double[]{0,1.5,2.75};
            case 4->new double[]{0,2.5};
            default->new double[]{0,.5,1.75,2.5,3.5};
        };
        // Fourth-bar pickups and answer-phrase offsets add rhythmic development
        // without injecting a second percussion or metronome pipeline.
        if(pattern.length>2&&bar%4==3){
            pattern[pattern.length-1]=Math.min(3.75,pattern[pattern.length-1]+.25);
        }else if(pattern.length>2&&(bar/4+groove)%3==1){
            pattern[1]=Math.min(3.75,pattern[1]+.25);
        }
        return pattern;
    }
    private static void pad(List<Composition.Note> notes,int[] voiced,double from,double length,
                            double energy,double beat,int seconds){
        for(int i=1;i<voiced.length;i++)addNote(notes,"pad-"+i,voiced[i],from,Math.max(.07,length),
            Math.max(30,(int)(61*energy)),beat,seconds);
    }
    private static void arp(List<Composition.Note> notes,int[] voiced,double from,double end,
                            double spacing,int variant,double energy,double beat,int seconds){
        int j=0;
        for(double at=from;at<end-.12;at+=spacing){
            int index=1+Math.floorMod(variant==3?j*2:variant==5?j+2:j,voiced.length-1);
            addNote(notes,"arp-"+(j%3),voiced[index],at,Math.min(spacing*.52,end-at),
                Math.max(29,(int)(60*energy)),beat,seconds);
            j++;
        }
    }
    private static void addNote(List<Composition.Note> notes,String voice,int pitch,double start,
                                double duration,int velocity,double beat,int seconds){
        double at=start*beat;
        if(at<0||at>=seconds-.035)return;
        double len=Math.min(duration*beat,seconds-at);
        if(len<.04)return;
        notes.add(new Composition.Note(voice,Math.max(0,Math.min(127,pitch)),at,len,
            Math.max(1,Math.min(127,velocity))));
    }
    public static boolean hasGeneratedIdentity(Composition c){
        return c.rights.equals("ORIGINAL_GENERATED")&&c.title.startsWith("Original ")&&
            c.title.contains(" arrangement ")&&c.title.contains(" variation ");
    }
    /** Reconstruct the saved seed/variation identity for exact MIDI replay. */
    public static Identity identity(Composition c){
        String[] words=c.title.split(" ");
        String style=words.length>1?words[1]:"pop";
        int variation=0,position=c.title.lastIndexOf(" variation ");
        if(position>=0)try{variation=Integer.parseInt(c.title.substring(position+11).trim());}
            catch(NumberFormatException ignored){}
        return identity(c.seed^(VARIATION_SALT*variation),style);
    }
    /**
     * Choose all MIDI programs from the same curated band. Older V4
     * compositions without the V5 marker retain the original mapping.
     */
    public static int gmProgram(Composition c,String role){
        Integer frozen=c.instrumentPrograms.get(role);
        if(frozen!=null)return frozen;
        if(c.title.contains(" ensemble-v5 ")){
            String[] words=c.title.split(" ");
            String style=words.length>1?words[1]:"pop";
            int variant=0,at=c.title.lastIndexOf(" variation ");
            if(at>=0)try{variant=Integer.parseInt(c.title.substring(at+11).trim());}
                catch(NumberFormatException ex){throw new IllegalArgumentException("INVALID_SAVED_ENSEMBLE_VARIATION",ex);}
            long planSeed=c.seed^(VARIATION_SALT*variant);
            if(c.mode.equals("guitar")&&(role.equals("guitar")||role.equals("lead")))
                return EnsembleCatalog.guitarProgram(style,planSeed);
            EnsembleCatalog.Ensemble band=EnsembleCatalog.select(planSeed,style,c.instrumentPreset);
            if(!band.supports(role))
                throw new IllegalArgumentException("UNSUPPORTED_ENSEMBLE_PART: "+role);
            return band.program(role);
        }
        // Legacy replay: older generated composition JSON carries no ensemble
        // marker, and must continue to sound as originally rendered.
        String[] words=c.title.split(" ");
        String style=words.length>1?words[1]:"pop";
        int variation=0;
        int p=c.title.lastIndexOf(" variation ");
        if(p>=0)try{variation=Integer.parseInt(c.title.substring(p+11).trim());}
            catch(NumberFormatException ignored){}
        long seed=c.seed^(VARIATION_SALT*variation);
        Identity id=identity(seed,style);
        int[] lead=switch(style){
            case "lofi"->new int[]{4,5,11,24,10,0};
            case "ambient"->new int[]{88,89,90,98,48,11};
            case "rock"->new int[]{26,27,29,30,81,24};
            case "funk"->new int[]{26,4,81,11,56,24};
            case "electronic"->new int[]{80,81,82,98,11,88};
            case "jazz"->new int[]{0,4,11,56,24,5};
            case "cinematic"->new int[]{48,60,68,73,89,0};
            default->new int[]{0,4,5,11,24,80};
        };
        int[] bass=switch(style){
            case "jazz"->new int[]{32,33,35,36,43,38};
            case "electronic"->new int[]{38,39,33,34,37,32};
            default->new int[]{32,33,35,38,39,36};
        };
        int[] pad=switch(style){
            case "ambient","cinematic"->new int[]{48,49,50,89,90,91};
            case "rock","funk"->new int[]{26,27,4,48,24,5};
            case "jazz"->new int[]{0,4,5,48,11,24};
            default->new int[]{4,5,48,89,11,24};
        };
        int[] arp=switch(style){
            case "ambient","cinematic"->new int[]{10,11,48,89,24,98};
            case "rock","funk"->new int[]{24,25,26,4,11,81};
            case "electronic"->new int[]{80,81,10,11,98,4};
            default->new int[]{24,11,4,5,10,0};
        };
        // A deliberately selected lead preset narrows the timbral family,
        // but still varies the actual instrument with the seed.
        if(role.equals("lead")){
            return switch(c.instrumentPreset){
                case "soft-keys"->new int[]{0,1,2,3,4,5}[id.palette()];
                case "nylon-pluck"->new int[]{24,25,26,27,28,29}[id.palette()];
                case "glass"->new int[]{10,11,12,14,15,98}[id.palette()];
                default->lead[id.palette()];
            };
        }
        return switch(role){
            case "bass"->bass[(id.palette()+id.bass())%bass.length];
            case "pad"->pad[(id.palette()+id.backing())%pad.length];
            case "arp"->arp[(id.palette()+id.melody())%arp.length];
            case "guitar"->new int[]{24,25,26,27,28,29}[id.palette()];
            default->0;
        };
    }
}
