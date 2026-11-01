package chordflow;

import java.util.*;

/**
 * Curated General MIDI ensembles. Choosing four unrelated programs by random
 * index creates timbral clashes: a sound must fit its BAND and musical role.
 * All patch numbers are zero-based General MIDI programs.
 *
 * A family choice is a texture preference, not permission to place an alien
 * lead over unrelated accompaniment. Auto picks any curated ensemble for
 * the chosen style. Song selection is deterministic from planSeed.
 */
public final class EnsembleCatalog {
    private EnsembleCatalog() {}
    public record Ensemble(String name,String family,int lead,int bass,int pad,int arp,
                           String backing) {
        public Ensemble {
            if(name.isBlank()||!Composition.PRESETS.contains(family)||
               lead<0||lead>127||bass<0||bass>127||
               pad < -1||pad>127||arp < -1||arp>127||
               (pad==-1&&arp==-1)||
               !Set.of("sustain","comp","pluck","space").contains(backing))
                throw new IllegalArgumentException("INVALID_CURATED_ENSEMBLE");
        }
        public boolean supports(String role){
            return switch(role){
                case "lead","bass"->true;
                case "pad"->pad>=0;
                case "arp"->arp>=0;
                default->false;
            };
        }
        public int program(String role){
            return switch(role){
                case "lead"->lead;case "bass"->bass;
                case "pad"->pad;case "arp"->arp;
                default->throw new IllegalArgumentException("UNSUPPORTED_ENSEMBLE_ROLE");
            };
        }
    }
    private static Ensemble e(String name,String family,int lead,int bass,
                              int pad,int arp,String backing){
        return new Ensemble(name,family,lead,bass,pad,arp,backing);
    }

    private static final Map<String,List<Ensemble>> BANDS=Map.of(
        "lofi",List.of(
            e("Rhodes and finger bass","soft-keys",4,33,4,-1,"comp"),
            e("Felt piano trio","soft-keys",0,32,0,-1,"space"),
            e("Mellow nylon guitar","nylon-pluck",24,33,-1,24,"pluck"),
            e("Jazz guitar and Rhodes","nylon-pluck",26,33,4,-1,"comp"),
            e("Warm electric piano","warm-pad",5,33,4,-1,"sustain"),
            e("Vibes and acoustic bass","glass",11,32,0,-1,"space"),
            e("Music box and Rhodes","glass",10,33,4,-1,"space"),
            e("Rhodes and soft strings","layered",4,33,48,-1,"sustain")
        ),
        "ambient",List.of(
            e("Piano and soft strings","soft-keys",0,42,49,-1,"sustain"),
            e("Minimal felt piano","soft-keys",0,32,-1,0,"space"),
            e("Nylon and warm strings","nylon-pluck",24,32,49,-1,"space"),
            e("Vibes and strings","glass",11,42,49,-1,"sustain"),
            e("Music box and pad","glass",10,38,89,-1,"space"),
            e("Warm synth air","warm-pad",89,38,90,-1,"sustain"),
            e("New-age keys","warm-pad",88,39,89,-1,"sustain"),
            e("Flute and strings","layered",73,42,48,46,"space")
        ),
        "pop",List.of(
            e("Piano pop band","soft-keys",0,33,4,-1,"comp"),
            e("Electric piano pop","soft-keys",5,33,4,-1,"comp"),
            e("Acoustic guitar pop","nylon-pluck",25,33,-1,25,"pluck"),
            e("Clean guitar pop","nylon-pluck",27,34,4,-1,"comp"),
            e("Synth-pop warmth","warm-pad",80,38,89,-1,"sustain"),
            e("Modern pop keys","warm-pad",4,38,89,-1,"comp"),
            e("Bright bells and piano","glass",11,33,0,-1,"space"),
            e("Soft layered pop","layered",0,33,48,11,"space")
        ),
        "rock",List.of(
            e("Clean electric band","nylon-pluck",27,33,27,-1,"comp"),
            e("Crunch guitar band","nylon-pluck",29,34,29,-1,"comp"),
            e("Steel acoustic rock","nylon-pluck",25,34,-1,25,"pluck"),
            e("Rock piano and guitar","soft-keys",0,34,29,-1,"comp"),
            e("Rock organ and guitar","soft-keys",18,33,27,-1,"sustain"),
            e("Guitar and organ","warm-pad",27,33,18,-1,"comp"),
            e("Guitar and electric piano","layered",26,33,4,-1,"comp"),
            e("Piano rock ballad","glass",0,33,48,-1,"sustain")
        ),
        "funk",List.of(
            e("Clavinet funk","soft-keys",7,36,7,-1,"comp"),
            e("Rhodes pocket","soft-keys",4,33,7,-1,"comp"),
            e("Clean guitar funk","nylon-pluck",27,36,-1,27,"pluck"),
            e("Jazz guitar funk","nylon-pluck",26,37,7,-1,"comp"),
            e("Warm funk keys","warm-pad",4,36,7,-1,"comp"),
            e("Funk organ groove","warm-pad",18,36,27,-1,"comp"),
            e("Bright clav and bass","glass",7,37,4,-1,"comp"),
            e("Funk keys and guitar","layered",27,36,4,-1,"comp")
        ),
        "electronic",List.of(
            e("Analog square and bass","warm-pad",80,38,89,-1,"sustain"),
            e("Analog saw and bass","warm-pad",81,39,90,-1,"sustain"),
            e("Electronic electric keys","soft-keys",5,38,89,-1,"comp"),
            e("Synth Rhodes hybrid","soft-keys",4,39,90,-1,"comp"),
            e("Crystal synth ambient","glass",98,39,89,-1,"space"),
            e("Music box electronica","glass",10,38,88,-1,"space"),
            e("Plucked synth palette","nylon-pluck",45,38,-1,45,"pluck"),
            e("Soft synth duo","layered",81,38,89,80,"space")
        ),
        "jazz",List.of(
            e("Acoustic piano trio","soft-keys",0,32,0,-1,"comp"),
            e("Electric piano trio","soft-keys",4,33,4,-1,"comp"),
            e("Jazz guitar trio","nylon-pluck",26,32,0,-1,"comp"),
            e("Nylon guitar trio","nylon-pluck",24,32,0,-1,"comp"),
            e("Vibraphone trio","glass",11,32,0,-1,"space"),
            e("Trumpet and piano","warm-pad",56,32,0,-1,"space"),
            e("Electric jazz trio","warm-pad",5,33,4,-1,"comp"),
            e("Soft piano and strings","layered",0,32,48,-1,"sustain")
        ),
        "cinematic",List.of(
            e("Piano and orchestra","soft-keys",0,42,49,-1,"sustain"),
            e("Piano and solo cello","soft-keys",0,42,48,-1,"space"),
            e("Nylon and strings","nylon-pluck",24,42,49,-1,"space"),
            e("Orchestral harp and strings","nylon-pluck",46,43,48,-1,"pluck"),
            e("Flute and orchestra","warm-pad",73,43,48,-1,"sustain"),
            e("French horn and strings","warm-pad",60,43,49,-1,"sustain"),
            e("Celesta and strings","glass",8,42,49,-1,"space"),
            e("Vibes with orchestra","glass",11,43,49,-1,"space"),
            e("Cello-led orchestra","layered",42,43,48,46,"space")
        )
    );

    public static List<Ensemble> choices(String style){
        List<Ensemble> result=BANDS.get(style);
        if(result==null)throw new IllegalArgumentException("UNSUPPORTED_MUSIC_STYLE");
        return result;
    }
    /** A fixed style and family limit the palette; only the seed picks within it. */
    public static Ensemble select(long planSeed,String style,String preference){
        List<Ensemble> available=choices(style);
        if(!Composition.PRESETS.contains(preference))
            throw new IllegalArgumentException("INSTRUMENT_PRESET_INVALID");
        if(!preference.equals("auto-ensemble")){
            List<Ensemble> preferred=available.stream()
                .filter(x->x.family().equals(preference)).toList();
            // Every genre has at least one curated option for all five families.
            if(preferred.isEmpty())throw new IllegalArgumentException("NO_COHERENT_ENSEMBLE_FOR_PRESET");
            available=preferred;
        }
        long z=planSeed^((long)style.hashCode()<<32)^0x5E2B5B1A86CD27A9L;
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;
        z=(z^(z>>>27))*0x94d049bb133111ebL;
        z^=z>>>31;
        return available.get(Math.floorMod(z,available.size()));
    }
    /** No arbitrary cross-family layering in guitar mode. */
    public static int guitarProgram(String style,long planSeed){
        int[] choices=switch(style){
            case "rock","funk"->new int[]{26,27,29};
            case "jazz","lofi"->new int[]{24,26,25};
            case "electronic"->new int[]{27,26};
            default->new int[]{24,25,26};
        };
        return choices[Math.floorMod(planSeed,choices.length)];
    }
}
