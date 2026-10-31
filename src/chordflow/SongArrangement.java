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
    private static int section(Identity id,int bar,int bars){
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
                               List<Composition.Drum> drums){
        Identity id=identity(planSeed,style);
        double beat=60.0/bpm,total=seconds/beat;
        int bars=(int)Math.ceil(total/4.0);
        boolean guitar=mode.equals("guitar");
        // Remove the old root/fifth-on-every-second-beat and constant .75-beat
        // arpeggiator. Keeping them would drown out the new musical identity.
        notes.removeIf(n->n.voice().startsWith("bass")||n.voice().startsWith("pad")||
            n.voice().startsWith("arp"));
        drums.clear();
        // Lead phrasing survives, while sections control presence and intensity.
        for(int i=notes.size()-1;i>=0;i--){
            Composition.Note n=notes.get(i);
            int sec=section(id,(int)Math.floor(n.start()/beat/4),bars);
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
                int sec=section(id,bar,bars);
                double e=energy(sec);
                double[] bp=bassPattern(id.bass(),style);
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
            int sec=section(id,(int)Math.floor(from/4),bars);
            double e=energy(sec);
            switch(id.backing()){
                case 0->{ // sustained warm bed and answering plucks
                    pad(notes,voiced,from,Math.min(to-from,4.5),.65*e,beat,seconds);
                    arp(notes,voiced,from+.5,to,2.0,0,e,beat,seconds);
                }
                case 1->{ // tight piano comping
                    for(double at=from;at<to-.05;at+=2.0)
                        pad(notes,voiced,at,Math.min(.53,to-at),.91*e,beat,seconds);
                    arp(notes,voiced,from+.75,to,1.5,1,e,beat,seconds);
                }
                case 2->{ // offbeat short stabs, syncopated bass carries pulse
                    for(double at=from+.5;at<to-.05;at+=1.0)
                        pad(notes,voiced,at,Math.min(.30,to-at),.81*e,beat,seconds);
                    arp(notes,voiced,from+.5,to,2.0,2,.65*e,beat,seconds);
                }
                case 3->{ // rolling broken chord
                    pad(notes,voiced,from,Math.min(to-from,3.7),.30*e,beat,seconds);
                    arp(notes,voiced,from+.25,to,.5,3,e,beat,seconds);
                }
                case 4->{ // slow orchestral / spacious harmony
                    pad(notes,voiced,from,Math.min(to-from,6.0),.78*e,beat,seconds);
                    arp(notes,voiced,from+.5,to,2.5,4,.55*e,beat,seconds);
                }
                default->{ // independent melodic accompaniment, short responses
                    pad(notes,voiced,from,Math.min(.7,to-from),.58*e,beat,seconds);
                    arp(notes,voiced,from+.5,to,1.25,5,e,beat,seconds);
                }
            }
        }
        // Relative groove accents are seed-specific but style-constrained. Two
        // seeds of one style need not share kick, hats or snare placement.
        for(int bar=0;bar<bars;bar++){
            int sec=section(id,bar,bars);
            double level=energy(sec),base=bar*4.0;
            Random r=new Random(mix(planSeed^((long)bar*0xA0761D6478BD642FL)));
            boolean sparse=style.equals("ambient")||style.equals("cinematic");
            double[] kicks=kickPattern(id.groove(),style);
            for(int j=0;j<kicks.length;j++){
                if(sparse&&sec!=2&&j>0&&r.nextBoolean())continue;
                if(sec==3&&j>0||sec==0&&j>1)continue;
                drum(drums,"kick",base+kicks[j],(int)((j==0?103:74)*level),beat,seconds);
            }
            // A seed-specific syncopation acts as a recognizable drum motif,
            // rather than choosing from the same handful of kick grids alone.
            double[] offbeats={.75,1.75,2.25,3.25,3.5};
            int sync=(int)Math.floorMod(mix(planSeed^0x7F4A7C15D42E19A1L),
                offbeats.length);
            if(!sparse&&sec!=0&&sec!=3&&(bar+id.groove())%3!=0)
                drum(drums,"kick",base+offbeats[sync],(int)(57*level),beat,seconds);
            double[] snares=sparse?
                (style.equals("cinematic")?new double[]{3}:new double[]{2.5}):
                id.drums()%3==0?new double[]{2}:
                id.drums()%3==1?new double[]{1,3}:new double[]{1.5,3.5};
            for(double snare:snares){
                if(sec==0&&snare!=snares[0]||sec==3&&r.nextBoolean())continue;
                drum(drums,"snare",base+snare,(int)(78*level),beat,seconds);
            }
            double hatStep=style.equals("ambient")||style.equals("cinematic")?
                (id.drums()%2==0?1.0:2.0):
                (id.drums()==0?.25:id.drums()==1?.75:id.drums()==2?1.0:.5);
            for(double at=hatStep/2;at<4;at+=hatStep){
                if(sec==3&&r.nextDouble()<.75||sec==0&&r.nextBoolean())continue;
                if(r.nextDouble()<(sparse?.35:.10))continue;
                double human=(id.drums()%2==0&&((int)(at/hatStep))%2==1)?.045:0;
                boolean open=at>=3.5&&bar%4==3&&r.nextBoolean();
                drum(drums,open?"open-hat":"hat",base+at+human,
                    (int)((42+r.nextInt(25))*level),beat,seconds);
            }
            if(sec==2&&bar%4==3){
                if(!sparse)drum(drums,"snare",base+3.75,(int)(63*level),beat,seconds);
                if(id.groove()%2==0)drum(drums,"kick",base+3.5,(int)(65*level),beat,seconds);
            }
        }
        // Keep drum onsets ordered for stable serialization/replay/fingerprint.
        drums.sort(Comparator.comparingDouble(Composition.Drum::start).thenComparing(Composition.Drum::kind));
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
    private static double[] bassPattern(int n,String style){
        if(style.equals("ambient")&&n%2==0)return new double[]{0,2.75};
        return switch(n){
            case 0->new double[]{0,2,3.5};
            case 1->new double[]{0,1,2,3};
            case 2->new double[]{0,.75,2.5,3.25};
            case 3->new double[]{0,1.5,2.75};
            case 4->new double[]{0,2.5};
            default->new double[]{0,.5,1.75,2.5,3.5};
        };
    }
    private static double[] kickPattern(int n,String style){
        if(style.equals("ambient"))return switch(n%3){
            case 0->new double[]{0};case 1->new double[]{0,2.75};default->new double[]{0,3.5};};
        if(style.equals("cinematic"))return switch(n%3){
            case 0->new double[]{0,2.5};case 1->new double[]{0};default->new double[]{0,1.5,3};};
        if(style.equals("electronic"))return switch(n%3){
            case 0->new double[]{0,1,2,3};case 1->new double[]{0,1,2,3.5};default->new double[]{0,1.5,2,3};};
        return switch(n){
            case 0->new double[]{0,2};case 1->new double[]{0,2.75};case 2->new double[]{0,1.5,2.5};
            case 3->new double[]{0,1.75,3.5};case 4->new double[]{0,2,3.25};
            case 5->new double[]{0,1,2,3};default->new double[]{0,2.5,3.5};
        };
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
    private static void drum(List<Composition.Drum> drums,String kind,double at,int velocity,
                             double beat,int seconds){
        double t=at*beat;
        if(t>=0&&t<seconds-.02)
            drums.add(new Composition.Drum(kind,t,Math.max(1,Math.min(127,velocity))));
    }
    public static boolean hasGeneratedIdentity(Composition c){
        return c.rights.equals("ORIGINAL_GENERATED")&&c.title.startsWith("Original ")&&
            c.title.contains(" arrangement ")&&c.title.contains(" variation ");
    }
    /** Import and historical composition playback retain their original mapping. */
    public static int gmProgram(Composition c,String role){
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
