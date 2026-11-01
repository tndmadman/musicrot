package chordflow;

import java.util.*;

/** Pre-render diagnostics based on canonical musical events, never the seed/JSON hash. */
public final class MusicQuality {
    private MusicQuality() {}
    public record Report(int leadPhrases,int repeatedMelodyWindows,int repeatedGrooveWindows,
                         int distinctMelodyWindows,int distinctGrooveWindows,List<String> warnings) {}
    public static Report analyze(Composition c){
        double beat=60/c.bpm;
        int windows=Math.max(1,(int)Math.ceil(c.duration/(8*beat)));
        List<String> melodies=new ArrayList<>(),grooves=new ArrayList<>();
        for(int bar=0;bar<windows;bar++){
            double from=bar*8*beat,to=(bar+1)*8*beat;
            List<Composition.Note> ns=c.notes.stream().filter(n->n.voice().startsWith("lead")
                &&n.start()>=from&&n.start()<to).sorted(Comparator.comparingDouble(Composition.Note::start)).toList();
            StringBuilder melody=new StringBuilder();int prev=-1;
            for(Composition.Note n:ns){
                if(prev>=0)melody.append(Math.floorMod(n.pitch()-prev+6,12)-6).append(',');
                melody.append(Math.round((n.start()-from)/beat*4)).append('/')
                    .append(Math.round(n.duration()/beat*4)).append('|');
                prev=n.pitch();
            }
            // Generated songs intentionally have NO automatic drum track.
            // Analyze audible bass/accompaniment onsets, not an empty drum list.
            SortedSet<String> events=new TreeSet<>();
            for(Composition.Note n:c.notes){
                if(n.start()<from||n.start()>=to)continue;
                String role=n.voice().startsWith("bass")?"B":
                    n.voice().startsWith("pad")?"P":
                    n.voice().startsWith("arp")?"A":
                    n.voice().startsWith("guitar")?"G":null;
                if(role==null)continue;
                long tick=Math.round((n.start()-from)/beat*4);
                events.add(String.format(Locale.ROOT,"%03d%s",tick,role));
            }
            for(Composition.Drum hit:c.drums){
                if(hit.start()>=from&&hit.start()<to)
                    events.add(String.format(Locale.ROOT,"%03dD%s",
                        Math.round((hit.start()-from)/beat*4),hit.kind()));
            }
            melodies.add(melody.toString());grooves.add(String.join("|",events));
        }
        int sameLead=0,sameGrooves=0;
        for(int i=1;i<windows;i++){
            if(!melodies.get(i).isEmpty()&&melodies.get(i).equals(melodies.get(i-1)))sameLead++;
            if(!grooves.get(i).isEmpty()&&grooves.get(i).equals(grooves.get(i-1)))sameGrooves++;
        }
        List<String> warnings=new ArrayList<>();
        if(windows>=4&&sameLead>=windows/2)warnings.add("LEAD_PHRASES_TOO_REPETITIVE");
        if(windows>=4&&sameGrooves>=windows/2)warnings.add("INSTRUMENT_GROOVE_TOO_REPETITIVE");
        if(c.notes.stream().noneMatch(n->n.voice().startsWith("bass")))warnings.add("BASS_PART_MISSING");
        if(c.notes.stream().noneMatch(n->n.voice().startsWith("lead")))warnings.add("MELODY_MISSING");
        return new Report(windows,sameLead,sameGrooves,new HashSet<>(melodies).size(),
            new HashSet<>(grooves).size(),List.copyOf(warnings));
    }
    public static void ensureBasicIntegrity(Composition c){
        Report r=analyze(c);
        if(r.warnings().contains("MELODY_MISSING")||r.warnings().contains("BASS_PART_MISSING"))
            throw new IllegalArgumentException("V4_MISSING_ESSENTIAL_MUSICAL_PART");
    }
}
