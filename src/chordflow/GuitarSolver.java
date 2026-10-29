package chordflow;

import java.util.*;

/** Exact-pitch guitar voicing search. Impossible/ambiguous requests omit the chart. */
public final class GuitarSolver {
    private static final int[] STANDARD={40,45,50,55,59,64}; // E2 A2 D3 G3 B3 E4
    private GuitarSolver(){}
    private static final class Best {int[] frets;int score=Integer.MAX_VALUE;}
    public static Optional<int[]> solve(int[] midiPitches){
        if(midiPitches==null||midiPitches.length==0||midiPitches.length>6)return Optional.empty();
        int[] sorted=midiPitches.clone();Arrays.sort(sorted);
        for(int n:sorted)if(n<40||n>76)return Optional.empty();
        int[] frets={-1,-1,-1,-1,-1,-1};Best best=new Best();
        search(sorted,0,0,frets,best);
        return best.frets==null?Optional.empty():Optional.of(best.frets.clone());
    }
    private static void search(int[] pitches,int noteIndex,int firstString,int[] frets,Best best){
        if(noteIndex==pitches.length){
            int min=100,max=0,fretted=0;Set<Integer> positions=new HashSet<>();
            for(int fret:frets)if(fret>0){fretted++;positions.add(fret);min=Math.min(min,fret);max=Math.max(max,fret);}
            if(fretted>0&&(max-min>4||positions.size()>4))return;
            int gaps=0,prev=-1;for(int i=0;i<6;i++)if(frets[i]>=0){if(prev>=0)gaps+=i-prev-1;prev=i;}
            int score=(max==0?0:(max-min)*100+max*10)+positions.size()*3+gaps*2;
            if(score<best.score){best.score=score;best.frets=frets.clone();}
            return;
        }
        int remaining=pitches.length-noteIndex;
        for(int string=firstString;string<=6-remaining;string++){
            int fret=pitches[noteIndex]-STANDARD[string];
            if(fret<0||fret>12)continue; // conservatively limit hand position
            frets[string]=fret;search(pitches,noteIndex+1,string+1,frets,best);frets[string]=-1;
        }
    }
    /** Apply fingerings only where each pitch is individually reproducible; never invent notes. */
    public static Composition withDiagrams(Composition src){
        List<Composition.Chord> charts=new ArrayList<>();
        for(Composition.Chord c:src.chords){
            int[] exact=c.frets()!=null?c.frets():solve(c.pitches()).orElse(null);
            charts.add(new Composition.Chord(c.start(),c.duration(),c.symbol(),c.pitches(),exact));
        }
        return new Composition(src.seed,src.title,"guitar",src.theme,src.rights,src.key,src.scaleMode,src.bpm,src.duration,charts,src.notes,src.tempoMap,"nylon-pluck");
    }
}
