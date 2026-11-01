package chordflow;

import java.util.*;

/**
 * Deterministic melodic identity and closure. Distinct seeds choose contour
 * families, while each song's phrases can transform and reprise one motif.
 */
public final class MotifComposer {
    private MotifComposer() {}
    // Small phrase contours in scale-degree space, not finished song melodies.
    // A song develops its contour with independent harmony and note rhythms.
    private static final int[][] CONTOURS={
        {0,1,2,1,-1,0},{0,2,1,0,-1,0},
        {0,-1,-2,0,1,0},{0,1,3,2,1,0},
        {0,2,3,1,0,-1},{0,-2,-1,1,2,0},
        {0,1,0,-2,-1,0},{0,3,1,-1,1,0},
        {0,-1,1,3,1,0},{0,2,0,-1,-2,0},
        {0,1,-1,2,-1,0},{0,-2,0,2,1,0}
    };
    public static int[] theme(long seed,String style){
        long value=seed^((long)style.hashCode()<<32)^0x6C8E9CF570932BD5L;
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        value^=value>>>31;
        int[] motif=CONTOURS[Math.floorMod(value,CONTOURS.length)].clone();
        // Controlled ornament: one interior scale degree changes, not a
        // completely new random sequence each time a phrase returns.
        int ornament=1+Math.floorMod(value>>>20,4);
        int delta=((value>>>34)&1)==0?1:-1;
        motif[ornament]=Math.max(-3,Math.min(3,motif[ornament]+delta));
        return motif;
    }
    /** Land the end of a longer piece on the tonic without a hidden track. */
    public static void resolve(List<Composition.Note> notes,int key,double bpm,int seconds){
        if(seconds<12)return;
        Composition.Note last=null;int position=-1;
        for(int i=0;i<notes.size();i++){
            Composition.Note n=notes.get(i);
            if(n.voice().startsWith("lead")&&(last==null||n.start()>last.start())){
                last=n;position=i;
            }
        }
        if(last==null)return;
        double beat=60/bpm;
        double harmonicEnd=seconds-1.25*beat;
        // An intentional tonic response should not overlap an existing lead.
        if(last.start()+last.duration()<harmonicEnd){
            double start=Math.max(last.start()+last.duration()+.07,seconds-beat);
            if(start<seconds-.12){
                int tonic=tonicNear(last.pitch(),key);
                notes.add(new Composition.Note("lead-0",tonic,start,
                    Math.min(beat*.75,seconds-start),Math.max(58,last.velocity()-4)));
            }
        }else if(last.start()>=seconds-4*beat){
            int tonic=tonicNear(last.pitch(),key);
            notes.set(position,new Composition.Note(last.voice(),tonic,
                last.start(),last.duration(),last.velocity()));
        }
    }
    private static int tonicNear(int pitch,int key){
        int best=60+key,dist=Integer.MAX_VALUE;
        for(int o=-1;o<=2;o++){
            int candidate=60+key+12*o;
            if(candidate<55||candidate>90)continue;
            int d=Math.abs(candidate-pitch);
            if(d<dist){best=candidate;dist=d;}
        }
        return best;
    }
}
