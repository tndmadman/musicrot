package chordflow;

import java.util.*;

/** Curated, explicit General MIDI instrument choices for each musical role. */
public final class InstrumentRegistry {
    private InstrumentRegistry() {}
    private static final Map<Integer,String> NAMES=new TreeMap<>();
    private static final Map<String,Set<Integer>> ROLES=new LinkedHashMap<>();
    static {
        for(String entry:("0=Piano|4=Electric Piano|5=Electric Piano 2|7=Clavinet|8=Celesta|10=Music Box|11=Vibraphone|18=Organ|24=Nylon Guitar|25=Steel Guitar|26=Jazz Guitar|27=Clean Guitar|28=Muted Guitar|29=Overdrive Guitar|32=Acoustic Bass|33=Finger Bass|34=Pick Bass|35=Fretless Bass|36=Slap Bass|37=Slap Bass 2|38=Synth Bass|39=Synth Bass 2|40=Violin|42=Cello|43=Contrabass|45=Pizzicato Strings|46=Harp|48=Strings|49=Slow Strings|50=Synth Strings|56=Trumpet|60=French Horn|65=Alto Saxophone|73=Flute|80=Square Synth|81=Saw Synth|88=New Age Pad|89=Warm Pad|90=Polysynth Pad|91=Choir Pad|98=Crystal").split("\\|")){
            String[] pair=entry.split("=",2);NAMES.put(Integer.parseInt(pair[0]),pair[1]);
        }
        role("lead",0,4,5,7,8,10,11,18,24,25,26,27,28,29,40,42,45,46,48,49,56,60,65,73,80,81,88,89,90,98);
        role("bass",32,33,34,35,36,37,38,39,42,43);
        role("pad",0,4,5,7,18,24,25,26,27,29,40,42,46,48,49,50,88,89,90,91,98);
        role("arp",0,4,5,7,8,10,11,24,25,26,27,45,46,80,81,88,89,98);
        role("guitar",24,25,26,27,28,29);
    }
    private static void role(String name,int... patches){
        Set<Integer> choices=new TreeSet<>();for(int p:patches)choices.add(p);
        ROLES.put(name,Collections.unmodifiableSet(choices));
    }
    public static String name(int patch){
        return NAMES.getOrDefault(patch,"GM "+(patch+1));
    }
    public static void validate(String role,int patch){
        Set<Integer> choices=ROLES.get(role);
        if(choices==null||!(choices.contains(patch)||(patch==-1&&(role.equals("pad")||role.equals("arp")))))
            throw new IllegalArgumentException("INVALID_INSTRUMENT_ROLE_OR_PATCH: "+role+"="+patch);
    }
    public static Map<String,Integer> parse(String text){
        if(text==null||text.isBlank())return Map.of();
        if(text.length()>200)throw new IllegalArgumentException("INSTRUMENT_SELECTION_TOO_LONG");
        Map<String,Integer> result=new LinkedHashMap<>();
        for(String token:text.split(",",-1)){
            String[] pair=token.split(":",-1);
            if(pair.length!=2||!pair[1].matches("-?[0-9]{1,3}"))
                throw new IllegalArgumentException("INVALID_INSTRUMENT_SELECTION");
            int patch=Integer.parseInt(pair[1]);validate(pair[0],patch);
            if(result.putIfAbsent(pair[0],patch)!=null)throw new IllegalArgumentException("DUPLICATE_INSTRUMENT_ROLE");
        }
        return Map.copyOf(result);
    }
    public static EnsembleCatalog.Ensemble customize(EnsembleCatalog.Ensemble base,
            Map<String,Integer> overrides){
        return customize(base,overrides,"pop");
    }
    /** A manual lineup is an intentional arrangement, not a preset timbre swap. */
    public static EnsembleCatalog.Ensemble customize(EnsembleCatalog.Ensemble base,
            Map<String,Integer> overrides,String style){
        if(overrides.isEmpty())return base;
        for(var entry:overrides.entrySet())validate(entry.getKey(),entry.getValue());
        int pad=overrides.getOrDefault("pad",base.pad());
        int arp=overrides.getOrDefault("arp",base.arp());
        if(pad==-1&&arp==-1)throw new IllegalArgumentException("ACCOMPANIMENT_REQUIRED");
        // Both chosen parts must play. The arrangement engine handles density,
        // rather than silently dropping the arpeggio in the "comp" backing mode.
        String backing=pad>=0&&arp>=0?
            (style.equals("ambient")||style.equals("cinematic")?"space":"comp"):
            arp>=0?"pluck":
            (style.equals("ambient")||style.equals("cinematic")?"sustain":"comp");
        return new EnsembleCatalog.Ensemble("Custom "+style,base.family(),
            overrides.getOrDefault("lead",base.lead()),overrides.getOrDefault("bass",base.bass()),
            pad,arp,backing);
    }
    public static String catalogJson(){
        StringBuilder b=new StringBuilder("{");int r=0;
        for(var entry:ROLES.entrySet()){
            if(r++>0)b.append(',');
            b.append('"').append(entry.getKey()).append("\":[");
            if(entry.getKey().equals("pad")||entry.getKey().equals("arp"))
                b.append("{\"value\":-1,\"label\":\"None\"},");
            int i=0;
            for(int p:entry.getValue()){
                if(i++>0)b.append(',');
                b.append("{\"value\":").append(p).append(",\"label\":\"")
                    .append(NAMES.get(p)).append("\"}");
            }
            b.append(']');
        }
        return b.append('}').toString();
    }
    public static String suggestionJson(String style){
        if(style.equals("auto"))style="pop";
        EnsembleCatalog.Ensemble e=EnsembleCatalog.choices(style).get(0);
        return "{\"lead\":"+e.lead()+",\"bass\":"+e.bass()+
            ",\"pad\":"+e.pad()+",\"arp\":"+e.arp()+
            ",\"guitar\":"+EnsembleCatalog.guitarProgram(style,0)+"}";
    }
}
