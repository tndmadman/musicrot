package chordflow;

import java.util.*;

/**
 * Deterministic, instrument-aware musical articulation. No extra notes, drums or
 * hidden audio are created: all changes happen on the canonical note events.
 */
public final class InstrumentPerformance {
    private InstrumentPerformance() {}
    private static int rolePatch(Map<String,Integer> programs,String voice){
        String role=voice.startsWith("lead")?"lead":voice.startsWith("bass")?"bass":
            voice.startsWith("guitar")?"guitar":voice.startsWith("pad")?"pad":
            voice.startsWith("arp")?"arp":null;
        return role==null?-1:programs.getOrDefault(role,-1);
    }
    private static double articulation(int patch,String role,String style){
        if(patch>=24&&patch<=31)return role.equals("guitar")?.84:.77;
        if(patch>=56&&patch<=79)return .96; // winds and brass: sustained lines
        if(patch>=40&&patch<=51)return .98; // bowed strings and ensembles
        if(patch>=0&&patch<=15)return style.equals("jazz")?.82:.87;
        if(patch>=80&&patch<=95)return .93;
        if(role.equals("bass"))return style.equals("funk")?.78:.90;
        return .90;
    }
    public static void apply(List<Composition.Note> notes,Map<String,Integer> programs,
                             String style,long seed,double bpm,int seconds){
        double beat=60.0/bpm;
        for(int i=0;i<notes.size();i++){
            Composition.Note n=notes.get(i);
            String role=n.voice().startsWith("lead")?"lead":
                n.voice().startsWith("bass")?"bass":
                n.voice().startsWith("guitar")?"guitar":
                n.voice().startsWith("pad")?"pad":
                n.voice().startsWith("arp")?"arp":"unknown";
            int patch=rolePatch(programs,n.voice());
            if(patch<0)continue;
            // Keep individual guitar-string strum offsets and other voices
            // aligned to the authored rhythm; do not independently swing notes.
            double duration=Math.min(seconds-n.start(),
                Math.max(.045,n.duration()*articulation(patch,role,style)));
            double beatPosition=n.start()/beat;
            boolean strong=Math.abs(beatPosition-Math.rint(beatPosition))<.10;
            long value=seed^((long)n.pitch()*0x9E3779B97F4A7C15L)^
                Double.doubleToLongBits(n.start())^role.hashCode();
            value^=value>>>33;value*=0xff51afd7ed558ccdL;
            value^=value>>>33;value*=0xc4ceb9fe1a85ec53L;
            int variation=(int)Math.floorMod(value,9)-4;
            int velocity=Math.max(28,Math.min(122,n.velocity()+(strong?3:0)+variation));
            notes.set(i,new Composition.Note(n.voice(),n.pitch(),n.start(),duration,velocity));
        }
    }
}
