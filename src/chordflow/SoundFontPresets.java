package chordflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Checks that an installed SF2 SoundFont actually offers the requested
 * bank-zero General MIDI programs. Does not load or redistribute sample data.
 */
public final class SoundFontPresets {
    private SoundFontPresets() {}
    private static String id(RandomAccessFile f)throws IOException {
        byte[] b=new byte[4];f.readFully(b);
        return new String(b,StandardCharsets.US_ASCII);
    }
    private static long u32(RandomAccessFile f)throws IOException {
        return (f.readUnsignedByte()|((long)f.readUnsignedByte()<<8)|
            ((long)f.readUnsignedByte()<<16)|((long)f.readUnsignedByte()<<24));
    }
    private static int u16(byte[] b,int at) {
        return (b[at]&255)|((b[at+1]&255)<<8);
    }
    static Set<Integer> parsePresetHeaders(byte[] data)throws IOException {
        if(data.length<76||data.length%38!=0)
            throw new IOException("SOUNDFONT_PRESET_HEADER_INVALID");
        Set<Integer> programs=new TreeSet<>();
        // Last phdr entry is the mandatory end-of-presets sentinel.
        for(int at=0;at<data.length-38;at+=38) {
            int preset=u16(data,at+20),bank=u16(data,at+22);
            if(bank==0&&preset<=127)programs.add(preset);
        }
        return Collections.unmodifiableSet(programs);
    }
    public static Set<Integer> programs(Path sf2)throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(sf2.toFile(),"r")){
            long size=f.length();
            if(size<12||!"RIFF".equals(id(f)))
                throw new IOException("SOUNDFONT_RIFF_INVALID");
            long riffEnd=8+u32(f);
            if(!"sfbk".equals(id(f))||riffEnd>size||riffEnd<12)
                throw new IOException("SOUNDFONT_RIFF_INVALID");
            for(long outer=12;outer+8<=riffEnd;) {
                f.seek(outer);
                String chunk=id(f);long len=u32(f),end=outer+8+len;
                if(end>riffEnd||end<outer+8)
                    throw new IOException("SOUNDFONT_CHUNK_INVALID");
                if("LIST".equals(chunk)&&len>=4) {
                    String listType=id(f);
                    if("pdta".equals(listType)) {
                        for(long inner=outer+12;inner+8<=end;) {
                            f.seek(inner);
                            String sub=id(f);long subLen=u32(f),next=inner+8+subLen;
                            if(next>end||next<inner+8)
                                throw new IOException("SOUNDFONT_PRESET_CHUNK_INVALID");
                            if("phdr".equals(sub)) {
                                if(subLen>2_000_000||subLen>Integer.MAX_VALUE)
                                    throw new IOException("SOUNDFONT_PRESET_HEADER_TOO_LARGE");
                                byte[] presets=new byte[(int)subLen];
                                f.readFully(presets);
                                return parsePresetHeaders(presets);
                            }
                            inner=next+(subLen&1);
                        }
                    }
                }
                outer=end+(len&1);
            }
            throw new IOException("SOUNDFONT_PRESET_TABLE_MISSING");
        }
    }
    public static void requirePrograms(Path sf2,Collection<Integer> requested)throws IOException {
        if(requested.isEmpty())return;
        Set<Integer> available=programs(sf2);
        Set<Integer> missing=new TreeSet<>();
        for(int program:requested)if(program>=0&&!available.contains(program))
            missing.add(program);
        if(!missing.isEmpty())
            throw new IOException("SOUNDFONT_MISSING_GM_PROGRAMS: "+missing+
                " (GM program numbers are zero-based)");
    }
}
