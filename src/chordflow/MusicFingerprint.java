package chordflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content-based musical signatures; changing a seed or transposing is not originality. */
public final class MusicFingerprint {
    private MusicFingerprint() {}
    public static final int VERSION=1;
    public record Signature(String melody,String drums,String harmony,String structure,long melodySketch,long drumsSketch) {
        public String encode(){return melody+":"+drums+":"+harmony+":"+structure+":"+
            Long.toUnsignedString(melodySketch,16)+":"+Long.toUnsignedString(drumsSketch,16);}
        public static Signature parse(String encoded){
            String[] f=encoded.split(":",-1);
            if(f.length!=6)throw new IllegalArgumentException("INVALID_FINGERPRINT");
            for(int i=0;i<4;i++)if(!f[i].matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_FINGERPRINT_HASH");
            if(!f[4].matches("[0-9a-f]{1,16}")||!f[5].matches("[0-9a-f]{1,16}"))throw new IllegalArgumentException("INVALID_FINGERPRINT_SKETCH");
            return new Signature(f[0],f[1],f[2],f[3],
                Long.parseUnsignedLong(f[4],16),Long.parseUnsignedLong(f[5],16));
        }
    }
    public static Signature of(Composition c){
        List<Composition.Note> lead=c.notes.stream().filter(n->n.voice().startsWith("lead"))
            .sorted(Comparator.comparingDouble(Composition.Note::start)).toList();
        // Relative pitch-class intervals survive global transposition AND individual octave shifts.
        List<String> melody=new ArrayList<>();Composition.Note prior=null;
        for(Composition.Note n:lead){
            int d=prior==null?0:Math.floorMod(n.pitch()-prior.pitch()+6,12)-6;
            int spacing=prior==null?0:(int)Math.round((n.start()-prior.start())*c.bpm/60*4);
            int duration=(int)Math.round(n.duration()*c.bpm/60*4);
            melody.add(d+"/"+spacing+"/"+duration);
            prior=n;
        }
        List<String> percussion=new ArrayList<>();
        List<Composition.Drum> hits=new ArrayList<>(c.drums);
        hits.sort(Comparator.comparingDouble(Composition.Drum::start).thenComparing(Composition.Drum::kind));
        for(Composition.Drum d:hits){
            int beat=(int)Math.round(d.start()*c.bpm/60*4);
            // Each groove token is relative to the two-bar cycle; also retain bar-to-bar variation.
            percussion.add(d.kind()+"/"+Math.floorMod(beat,32)+"/"+(beat/32));
        }
        List<String> harmony=new ArrayList<>();int previousRoot=0;
        List<Composition.Harmony> hs=c.arranged?c.harmony:
            c.chords.stream().map(x->new Composition.Harmony(x.start(),x.duration(),x.symbol(),x.pitches(),x.frets())).toList();
        for(Composition.Harmony h:hs){
            int root=Arrays.stream(h.pitches()).min().orElse(0)%12;
            int interval=Math.floorMod(root-previousRoot,12);
            int beats=(int)Math.round(h.duration()*c.bpm/60*2);
            harmony.add(interval+"/"+beats+"/"+h.pitches().length);previousRoot=root;
        }
        List<String> structure=new ArrayList<>();
        int count=Math.max(1,(int)Math.ceil(c.duration*c.bpm/60/8));
        for(int phrase=0;phrase<count;phrase++){
            double from=phrase*8*60/c.bpm,to=(phrase+1)*8*60/c.bpm;
            final double a=from,b=to;
            long nn=c.notes.stream().filter(n->n.start()>=a&&n.start()<b).count();
            long dd=c.drums.stream().filter(n->n.start()>=a&&n.start()<b).count();
            structure.add(nn+"/"+dd);
        }
        return new Signature(hash(melody),hash(percussion),hash(harmony),hash(structure),
            simhash(ngrams(melody)),simhash(ngrams(percussion)));
    }
    public static boolean nearDuplicate(Signature a,Signature b){
        if(a.melody().equals(b.melody()))return true;
        if(a.drums().equals(b.drums())&&a.harmony().equals(b.harmony())&&a.structure().equals(b.structure()))return true;
        // A near duplicate must match multiple independent musical dimensions.
        int m=Long.bitCount(a.melodySketch()^b.melodySketch());
        int d=Long.bitCount(a.drumsSketch()^b.drumsSketch());
        return m<=7&&d<=10&&(a.harmony().equals(b.harmony())||a.structure().equals(b.structure()));
    }
    public static String key(Composition c){return of(c).encode();}
    private static List<String> ngrams(List<String> tokens){
        List<String> grams=new ArrayList<>();
        if(tokens.isEmpty()){grams.add("empty");return grams;}
        int size=Math.min(4,tokens.size());
        for(int i=0;i<=tokens.size()-size;i++)grams.add(String.join("|",tokens.subList(i,i+size)));
        return grams;
    }
    private static long simhash(List<String> grams){
        long[] counts=new long[64];
        for(String gram:grams){
            long h=0xCBF29CE484222325L;
            for(byte b:gram.getBytes(StandardCharsets.UTF_8)){h^=(b&255);h*=0x100000001B3L;}
            for(int i=0;i<64;i++)counts[i]+=((h>>>i)&1)==1?1:-1;
        }
        long value=0;
        for(int i=0;i<64;i++)if(counts[i]>0)value|=1L<<i;
        return value;
    }
    private static String hash(List<String> tokens){
        try{
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            for(String token:tokens){d.update(token.getBytes(StandardCharsets.UTF_8));d.update((byte)0);}
            return HexFormat.of().formatHex(d.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
