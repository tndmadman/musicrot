package chordflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Strict bounded schema-v2 loader: never regenerate notes when replaying a saved composition. */
public final class CompositionIO {
    private CompositionIO() {}
    private static final int MAX_BYTES=4_000_000;
    public static Composition read(Path path) throws IOException {
        if(Files.size(path)>MAX_BYTES)throw new IOException("COMPOSITION_JSON_TOO_LARGE");
        try {
            Object parsed=new Parser(Files.readString(path,StandardCharsets.UTF_8)).value();
            Map<String,Object> root=obj(parsed);
            int schema=integer(root,"schemaVersion");
            if(schema!=2&&schema!=3)throw new IllegalArgumentException("COMPOSITION_SCHEMA_UNSUPPORTED");
            List<Composition.Chord> chords=new ArrayList<>();
            for(Object element:array(root,"chordEvents",50_000)){
                Map<String,Object> c=obj(element);
                List<Object> rawP=array(c,"midiPitches",128);
                int[] pitches=new int[rawP.size()];for(int i=0;i<pitches.length;i++)pitches[i]=intValue(rawP.get(i));
                int[] frets=null;
                if(c.containsKey("frets")&&c.get("frets")!=null){
                    List<Object> raw=array(c,"frets",6);frets=new int[raw.size()];for(int i=0;i<frets.length;i++)frets[i]=intValue(raw.get(i));
                }
                chords.add(new Composition.Chord(number(c,"startSeconds"),number(c,"durationSeconds"),string(c,"symbol"),pitches,frets));
            }
            List<Composition.Note> notes=new ArrayList<>();
            for(Object element:array(root,"noteEvents",100_000)){
                Map<String,Object> n=obj(element);
                notes.add(new Composition.Note(string(n,"voiceId"),integer(n,"midiPitch"),number(n,"startSeconds"),number(n,"durationSeconds"),integer(n,"velocity")));
            }
            List<Composition.Tempo> tempos=new ArrayList<>();
            for(Object element:array(root,"tempoMap",2_000)){
                Map<String,Object> t=obj(element);tempos.add(new Composition.Tempo(number(t,"beat"),number(t,"bpm")));
            }
            List<Composition.Harmony> harmony=new ArrayList<>();List<Composition.Drum> drums=new ArrayList<>();
            if(schema==3){
                for(Object element:array(root,"harmonyEvents",50_000)){
                    Map<String,Object> h=obj(element);List<Object> raw=array(h,"midiPitches",128);int[] pitches=new int[raw.size()];
                    for(int i=0;i<pitches.length;i++)pitches[i]=intValue(raw.get(i));int[] frets=null;
                    if(h.containsKey("frets")&&h.get("frets")!=null){List<Object> f=array(h,"frets",6);frets=new int[f.size()];for(int i=0;i<frets.length;i++)frets[i]=intValue(f.get(i));}
                    harmony.add(new Composition.Harmony(number(h,"startSeconds"),number(h,"durationSeconds"),string(h,"symbol"),pitches,frets));
                }
                for(Object element:array(root,"drumEvents",100_000)){Map<String,Object> d=obj(element);
                    drums.add(new Composition.Drum(string(d,"kind"),number(d,"startSeconds"),integer(d,"velocity")));
                }
            }
            Map<String,Integer> programs=new LinkedHashMap<>();
            if(root.containsKey("instrumentPrograms")){
                for(var entry:obj(root.get("instrumentPrograms")).entrySet()){
                    int patch=intValue(entry.getValue());
                    InstrumentRegistry.validate(entry.getKey(),patch);
                    programs.put(entry.getKey(),patch);
                }
            }
            return new Composition(longValue(root,"seed"),string(root,"title"),string(root,"mode"),string(root,"theme"),
                    string(root,"rights"),string(root,"key"),string(root,"scaleMode"),number(root,"bpm"),
                    number(root,"durationSeconds"),chords,notes,tempos,string(root,"instrumentPreset"),harmony,drums,schema==3,programs);
        } catch(IllegalArgumentException e){throw new IOException("INVALID_COMPOSITION_JSON: "+e.getMessage(),e);}
    }
    private static Map<String,Object> obj(Object v){if(!(v instanceof Map<?,?> m))throw new IllegalArgumentException("EXPECTED_OBJECT");
        Map<String,Object> result=new LinkedHashMap<>();for(var e:m.entrySet()){
            if(!(e.getKey() instanceof String k))throw new IllegalArgumentException("INVALID_KEY");result.put(k,e.getValue());
        }return result;
    }
    private static List<Object> array(Map<String,Object> o,String k,int max){Object v=o.get(k);if(!(v instanceof List<?> a))throw new IllegalArgumentException("EXPECTED_ARRAY_"+k);
        if(a.size()>max)throw new IllegalArgumentException("ARRAY_TOO_LONG_"+k);return new ArrayList<>(a);
    }
    private static String string(Map<String,Object> o,String k){Object v=o.get(k);if(!(v instanceof String s)||s.length()>4096)throw new IllegalArgumentException("EXPECTED_STRING_"+k);return s;}
    private static int intValue(Object v){long n=longValueRaw(v);if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException("INTEGER_OUT_OF_RANGE");return (int)n;}
    private static long longValueRaw(Object v){if(!(v instanceof Number n))throw new IllegalArgumentException("EXPECTED_INTEGER");double d=n.doubleValue();long result=n.longValue();if(!Double.isFinite(d)||d!=result)throw new IllegalArgumentException("NONINTEGER_VALUE");return result;}
    private static long longValue(Map<String,Object> o,String k){if(!o.containsKey(k))throw new IllegalArgumentException("MISSING_"+k);return longValueRaw(o.get(k));}
    private static int integer(Map<String,Object> o,String k){return intValue(o.get(k));}
    private static double number(Map<String,Object> o,String k){Object v=o.get(k);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("EXPECTED_NUMBER_"+k);return n.doubleValue();}
    /** Share the bounded parser with the optional loopback music service adapter. */
    static Object parseJson(String source){
        if(source==null||source.length()>1_000_000)throw new IllegalArgumentException("JSON_TOO_LARGE");
        return new Parser(source).value();
    }
    /** Limited recursive JSON parser with strict duplicate-key and trailing-data rejection. */
    private static final class Parser {
        private final String s;private int i;
        Parser(String s){this.s=s;}
        Object value(){Object value=read(0);ws();if(i!=s.length())throw bad("TRAILING_DATA");return value;}
        private IllegalArgumentException bad(String why){return new IllegalArgumentException(why+" at character "+i);}
        private void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        private char peek(){ws();if(i>=s.length())throw bad("UNEXPECTED_EOF");return s.charAt(i);}
        private void expect(char c){if(peek()!=c)throw bad("EXPECTED_"+c);i++;}
        private Object read(int depth){if(depth>32)throw bad("TOO_DEEP");char c=peek();
            if(c=='{'){i++;Map<String,Object> o=new LinkedHashMap<>();if(peek()=='}'){i++;return o;}
                while(true){if(peek()!='"')throw bad("OBJECT_KEY");String key=quoted();expect(':');Object v=read(depth+1);if(o.containsKey(key))throw bad("DUPLICATE_KEY");o.put(key,v);
                    char next=peek();if(next=='}'){i++;return o;}expect(',');}
            }
            if(c=='['){i++;List<Object> a=new ArrayList<>();if(peek()==']'){i++;return a;}
                while(true){if(a.size()>100_000)throw bad("TOO_MANY_ELEMENTS");a.add(read(depth+1));if(peek()==']'){i++;return a;}expect(',');}
            }
            if(c=='"')return quoted();
            if(s.startsWith("null",i)){i+=4;return null;}
            if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;}
            if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}
            if(c=='-'||Character.isDigit(c)){
                int start=i;while(i<s.length()&&"-+0123456789.eE".indexOf(s.charAt(i))>=0)i++;
                String token=s.substring(start,i);
                try {double d=Double.parseDouble(token);if(!Double.isFinite(d))throw bad("NONFINITE_NUMBER");return d;}
                catch(NumberFormatException e){throw bad("BAD_NUMBER");}
            }
            throw bad("INVALID_TOKEN");
        }
        private String quoted(){expect('"');StringBuilder b=new StringBuilder();boolean ended=false;
            while(i<s.length()){char c=s.charAt(i++);if(c=='"'){ended=true;break;}
                if(c<32)throw bad("CONTROL_IN_STRING");
                if(c=='\\'){
                    if(i>=s.length())throw bad("BAD_ESCAPE");char e=s.charAt(i++);
                    switch(e){case '"','\\','/'->b.append(e);case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'b'->b.append('\b');case 'f'->b.append('\f');
                        case 'u'->{if(i+4>s.length())throw bad("BAD_UNICODE");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){throw bad("BAD_UNICODE");}i+=4;}
                        default->throw bad("BAD_ESCAPE");}
                }else b.append(c);
                if(b.length()>8192)throw bad("STRING_TOO_LONG");
            }
            if(!ended)throw bad("UNCLOSED_STRING");return b.toString();
        }
    }
}