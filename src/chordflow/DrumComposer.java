package chordflow;

import java.util.*;

/**
 * Drum arrangements are song-level rhythm compositions, not a fixed snare
 * backbeat with randomized extra hits. Style selects a vocabulary, song identity
 * chooses the rhythmic grammar, and 2/4/8-bar variation creates development.
 *
 * Every hit is a canonical Composition.Drum event shared by MIDI, audio,
 * fingerprints and the rhythm visualizer. No hidden audio-only percussion.
 */
public final class DrumComposer {
    private DrumComposer(){}
    private static final String[] KINDS={"kick","snare","hat","open-hat",
        "clap","rim","tom","ride","crash"};
    public static boolean known(String s){return Arrays.asList(KINDS).contains(s);}

    // These are style-appropriate *starting feels*, not fixed 1-bar drum loops.
    // Each note is a location in a 4/4 bar; the phrase generator below may omit,
    // shift, add or answer it. Different seed identities select different feels.
    private static final double[][] KICKS={
        {0,1,2,3},            // club four-on-the-floor
        {0,2.5},              // half-time / laid-back
        {0,1.5,2.75},         // broken beat
        {0,2.25,3.5},         // two-step
        {0,.75,2.5,3.25},     // syncopated funk
        {0},                  // sparse pulse
        {0,1.75,3.25}        // rolling back-and-forth
    };
    private static final double[][] SNARES={
        {1,3}, {2}, {1.5,3.5}, {3}, {1,2.75}, {2.5}, {.75,2.75}
    };
    private static long mix(long z){
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
        z=(z^(z>>>27))*0x94d049bb133111ebL;
        return z^(z>>>31);
    }
    private static int[] permittedFeels(String style){
        return switch(style){
            case "ambient"->new int[]{5,1,3,6};
            case "cinematic"->new int[]{5,1,3,6,2};
            case "lofi"->new int[]{1,2,3,4,6};
            case "pop"->new int[]{0,1,2,3,6};
            case "rock"->new int[]{1,2,3,6};
            case "funk"->new int[]{2,3,4,6};
            case "electronic"->new int[]{0,2,3,4,6};
            case "jazz"->new int[]{1,2,3,4,5,6};
            default->new int[]{0,1,2,3,4,5,6};
        };
    }
    private static double kitEnergy(int section){
        return switch(section){
            case 0->.54;case 2->1.05;case 3->.35;case 4->.62;
            default->.87;
        };
    }
    public static void compose(long planSeed,String style,int bpm,int seconds,
                               SongArrangement.Identity identity,List<Composition.Drum> output){
        output.clear();
        double beat=60.0/bpm,beats=seconds/beat;
        int bars=(int)Math.ceil(beats/4);
        int[] feels=permittedFeels(style);
        int feel=feels[Math.floorMod(identity.groove()+identity.drums()*3,feels.length)];
        int backbeat=Math.floorMod(identity.drums()+identity.groove(),SNARES.length);
        boolean sparse=style.equals("ambient")||style.equals("cinematic");
        boolean shuffle=style.equals("jazz")||style.equals("lofi");
        // Stable, independently seeded per-song rhythmic identity.
        Random phraseRandom=new Random(mix(planSeed^0x237A9D51428B6F0CL));
        double kickPush= new double[]{.0,.25,.5,.75,1.25}[phraseRandom.nextInt(5)];
        int hatDensity=phraseRandom.nextInt(5);
        int barCycle=phraseRandom.nextBoolean()?4:8;
        for(int bar=0;bar<bars;bar++){
            int section=SongArrangement.section(identity,bar,bars);
            double energy=kitEnergy(section),base=4.0*bar;
            Random r=new Random(mix(planSeed^((long)(bar/barCycle)*0x9E3779B97F4A7C15L)
                ^((long)(bar%barCycle)*0xD6E8FEB86659FD93L)));
            boolean fill=bar%barCycle==barCycle-1&&section!=0&&section!=3;
            boolean answer=bar%2==1;
            // Downbeat kick is deliberate in some grooves, but not every bar.
            for(int k=0;k<KICKS[feel].length;k++){
                double at=KICKS[feel][k];
                if(section==3&&k>0)continue;
                if(section==0&&k>0&&r.nextDouble()<.70)continue;
                if(sparse&&k>0&&r.nextDouble()<.60)continue;
                if(k==0&&answer&&((identity.groove()+bar)%4==0))continue;
                if(k>0&&r.nextDouble()<(fill?.12:.09))continue;
                double shift=(k>0&&bar%4==2&&r.nextDouble()<.38)? .25:0;
                hit(output,"kick",base+at+shift,Math.round((k==0?103:83)*energy),
                    beat,seconds);
            }
            if(section!=3&&!sparse&&(bar+identity.groove())%3==1)
                hit(output,"kick",base+kickPush,Math.round(64*energy),beat,seconds);
            // The backbeat is deliberately not hardcoded to beats 2 and 4.
            double[] snare=SNARES[backbeat];
            for(int s=0;s<snare.length;s++){
                if(section==0&&s>0)continue;
                if(section==3&&r.nextDouble()<.60)continue;
                if(sparse&&answer&&s>0)continue;
                if(bar%4==2&&s==0&&r.nextDouble()<.27)continue;
                String kind=(identity.drums()%4==0&&s==0)?"clap":
                    (style.equals("jazz")||style.equals("ambient"))&&s==0?"rim":"snare";
                hit(output,kind,base+snare[s],Math.round((kind.equals("rim")?61:90)*energy),
                    beat,seconds);
            }
            // There is intentional negative space and an independent cymbal
            // rhythm per song. Not every track receives eighth-note closed hats.
            double subdivision=switch(hatDensity){
                case 0->.25;case 1->.5;case 2->.75;case 3->1.;default->1.5;
            };
            if(sparse)subdivision=Math.max(.75,subdivision);
            int pulse=0;
            for(double at=0;at<4.0;at+=subdivision,pulse++){
                if(at==0&&bar%2==1&&r.nextBoolean())continue;
                if(section==3&&r.nextDouble()<.75)continue;
                if(section==0&&r.nextDouble()<.60)continue;
                if(r.nextDouble()<(sparse?.42:.20))continue;
                double swing=shuffle&&pulse%2==1?.10:0;
                String kind=(fill&&at>3.0&&r.nextDouble()<.55)?"open-hat":
                    (identity.drums()==2&&pulse%4==1)?"ride":"hat";
                hit(output,kind,base+at+swing,Math.round((39+(pulse%4==0?20:0)+
                    r.nextInt(12))*energy),beat,seconds);
            }
            // Two-bar calls and answers, kit fills, and section-specific accents.
            if(fill){
                if(!sparse){
                    hit(output,"tom",base+3.00,Math.round(76*energy),beat,seconds);
                    hit(output,identity.drums()%2==0?"snare":"clap",base+3.50,
                        Math.round(71*energy),beat,seconds);
                    if(r.nextBoolean())hit(output,"tom",base+3.75,
                        Math.round(68*energy),beat,seconds);
                }else if(r.nextBoolean()){
                    hit(output,"crash",base+3.5,Math.round(65*energy),beat,seconds);
                }
            }
            if(section==2&&bar%4==0&&bar>0)
                hit(output,"crash",base,Math.round(74*energy),beat,seconds);
        }
        output.sort(Comparator.comparingDouble(Composition.Drum::start)
            .thenComparing(Composition.Drum::kind));
    }
    private static void hit(List<Composition.Drum> out,String kind,double at,long velocity,
                            double beat,int seconds){
        double time=at*beat;
        if(time>=0&&time<seconds-.02)
            out.add(new Composition.Drum(kind,time,
                (int)Math.max(1,Math.min(127,velocity))));
    }
    /** Preserve the original drum pitches for imported and older arrangements. */
    public static int gmPitch(Composition c,Composition.Drum hit){
        int kit=SongArrangement.hasGeneratedIdentity(c)?
            Math.floorMod(SongArrangement.identity(c).drums(),3):0;
        return switch(hit.kind()){
            case "kick"->kit==1?35:36;
            case "snare"->kit==2?40:38;
            case "hat"->kit==1?44:42;
            case "open-hat"->46;
            case "clap"->39;
            case "rim"->37;
            case "tom"->kit==1?47:45;
            case "ride"->kit==2?59:51;
            case "crash"->kit==2?57:49;
            default->throw new IllegalArgumentException("UNKNOWN_PERCUSSION "+hit.kind());
        };
    }
}
