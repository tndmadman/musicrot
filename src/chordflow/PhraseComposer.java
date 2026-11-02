package chordflow;

import java.util.*;

/** Melodic phrases written from a recurring rhythmic hook and harmonic targets. */
public final class PhraseComposer {
    private PhraseComposer(){}
    private static final double[][] RHYTHMS={
        {0,1,2.5,4,5.5,7},{0,.75,2,3.5,4.75,6.5},
        {0,1.5,3,4,6.5},{0,.5,1.5,3,4.5,5.5,7},
        {.5,1.5,2.5,4.5,6},{0,2,3.5,5,6.5},
        {0,1,1.75,3.75,5,6.75},{0,.75,2.75,4,5.75,7},
        {0,1.25,3.25,4,5.5},{.5,2,3,4.5,6.75},
        {0,.5,2,3,4,6.5},{0,1.5,2.25,4.75,6}
    };
    private static final int[] MAJOR={0,2,4,5,7,9,11};
    private static final int[] MINOR={0,2,3,5,7,8,10};
    private static Composition.Harmony at(List<Composition.Harmony> harmony,double seconds){
        Composition.Harmony current=harmony.get(0);
        for(Composition.Harmony h:harmony){if(h.start()>seconds+1e-8)break;current=h;}
        return current;
    }
    private static int closeChordTone(int pitch,Composition.Harmony chord){
        int best=pitch,distance=Integer.MAX_VALUE;
        for(int original:chord.pitches())for(int offset=-24;offset<=36;offset+=12){
            int candidate=original+offset;
            if(candidate<60||candidate>89)continue;
            int diff=Math.abs(candidate-pitch);
            if(diff<distance){best=candidate;distance=diff;}
        }
        return best;
    }
    private static int noteFor(long seed,int key,boolean minor,int degree,
                               int previous,Composition.Harmony chord,boolean strong){
        int[] scale=minor?MINOR:MAJOR;
        int note=60+key+scale[Math.floorMod(degree,7)];
        if(note<64)note+=12;
        int close=note,distance=Math.abs(note-previous);
        for(int offset:new int[]{-12,12}){
            int candidate=note+offset;
            if(candidate>=60&&candidate<=89&&Math.abs(candidate-previous)<distance){
                close=candidate;distance=Math.abs(candidate-previous);
            }
        }
        if(strong)return closeChordTone(close,chord);
        // Passing and neighbor notes keep their diatonic function.
        return close;
    }
    public static void compose(long seed,String style,int key,boolean minor,double bpm,int seconds,
                               List<Composition.Harmony> harmony,List<Composition.Note> notes){
        double beat=60.0/bpm,total=seconds/beat;
        int[] intervalMotif=MotifComposer.theme(seed,style);
        Random identity=new Random(seed^0x2AF1C54D61E98572L);
        int rhythmicIdentity=identity.nextInt(RHYTHMS.length);
        int anchor=identity.nextInt(7);
        List<SongFormPlanner.Section> form=SongFormPlanner.plan(total,seed,style);
        int last=69+key,phrase=0;
        for(double start=0;start<total-1e-8;start+=8,phrase++){
            int bar=(int)(start/4);
            SongFormPlanner.Section section=SongFormPlanner.at(form,bar);
            boolean returnTheme=section.kind().equals("return")||phrase%4==2;
            boolean answer=section.kind().equals("answer")||phrase%4==1;
            boolean bridge=section.kind().equals("bridge")||section.kind().equals("development");
            Random patternRandom=new Random(seed^((long)phrase*0x9E3779B97F4A7C15L));
            int pattern=returnTheme?rhythmicIdentity:
                (rhythmicIdentity+1+Math.floorMod(patternRandom.nextInt(),RHYTHMS.length-1))%
                    RHYTHMS.length;
            if(phrase==0)pattern=rhythmicIdentity;
            double[] onsets=RHYTHMS[pattern];
            double remaining=Math.min(8,total-start);
            for(int i=0;i<onsets.length;i++){
                double pos=onsets[i];
                if(pos>=remaining-.06)break;
                // Contrasting sections leave more room rather than filling every gap.
                if((style.equals("ambient")||section.kind().equals("bridge"))&&i%3==2)continue;
                double next=i+1<onsets.length?onsets[i+1]:8;
                double duration=Math.min(remaining-pos,
                    Math.max(.23,(next-pos)*(style.equals("ambient")?.88:.75)));
                int movement=intervalMotif[i%intervalMotif.length];
                if(answer)movement=-movement;
                if(bridge)movement+=(i%3)-1;
                int degree=anchor+movement+(bridge?2:0)+(phrase>=3&&!returnTheme?1:0);
                double position=start+pos;
                boolean strong=Math.abs(pos-Math.rint(pos/2)*2)<.15||i==onsets.length-1;
                Composition.Harmony current=at(harmony,position*beat);
                int target=noteFor(seed,key,minor,degree,last,current,strong);
                long pulse=seed^((long)phrase*0xC2B2AE3D27D4EB4FL)^i;
                int accent=(pos%4<.11?8:pos%2<.12?3:-2);
                int velocity=(int)Math.round(79+section.energy()*7+accent+
                    Math.floorMod(pulse,11)-5);
                double onset=position;
                // Small local timing fluctuations retain the actual authored groove.
                if(pos>0&&i%3==1)onset+=((pulse&1)==0?.018:-.012);
                double startSec=onset*beat;
                double lenSec=Math.min(duration*beat,seconds-startSec);
                if(startSec>=0&&startSec<seconds-.04&&lenSec>.04){
                    notes.add(new Composition.Note("lead-0",target,startSec,lenSec,
                        Math.max(42,Math.min(120,velocity))));
                    last=target;
                }
            }
        }
        if(notes.stream().noneMatch(n->n.voice().startsWith("lead"))){
            int pitch=closeChordTone(72,at(harmony,0));
            notes.add(new Composition.Note("lead-0",pitch,0,Math.min(seconds,.5*beat),83));
        }
    }
}
