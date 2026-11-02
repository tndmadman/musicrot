package chordflow;

import java.util.*;

/** Seed-unique instrument groove language, shared across bass and chord comping. */
public final class GroovePlanner {
    private GroovePlanner(){}
    private static final Map<String,double[][]> BASS=Map.of(
        "lofi",new double[][]{{0,1.75,3.25},{0,2,3.5},{0,1.5,2.75}},
        "ambient",new double[][]{{0,2.75},{0,3.25},{0,2}},
        "pop",new double[][]{{0,1.5,2.5,3.5},{0,1,2,3.5},{0,2,2.75}},
        "rock",new double[][]{{0,1,2,3},{0,1.5,2.5,3.5},{0,.75,2,3}},
        "funk",new double[][]{{0,.75,1.75,2.5,3.5},{0,1.25,2.25,3.25},{0,.5,1.5,2.75,3.5}},
        "electronic",new double[][]{{0,.75,2,3.5},{0,1.25,2.25,3.25},{0,.5,1.75,2.5}},
        "jazz",new double[][]{{0,1,2,3},{0,1,2.5,3},{0,1.5,2.25,3.5}},
        "cinematic",new double[][]{{0,3.0},{0,2.5},{0,2.0,3.5}}
    );
    public static double[] bassOnsets(long seed,String style,int bar,int groove){
        double[][] families=BASS.get(style);
        if(families==null)throw new IllegalArgumentException("UNSUPPORTED_GROOVE_STYLE");
        long identity=seed^((long)style.hashCode()<<32)^((long)groove*0x9E3779B97F4A7C15L);
        Random r=new Random(identity);
        int index=Math.floorMod(r.nextInt(),families.length);
        double[] pattern=families[index].clone();
        int section=bar/4,answer=section%4;
        if(pattern.length>2){
            if(answer==1&&(bar%4==3))pattern[pattern.length-1]=
                Math.min(3.75,pattern[pattern.length-1]+.25);
            if(answer==2&&bar%4==1)pattern[1]=Math.max(.5,pattern[1]-.25);
            if(answer==3&&bar%4==3)pattern[pattern.length-1]=
                Math.max(.75,pattern[pattern.length-1]-.25);
        }
        return pattern;
    }
    public static double[] compOnsets(long seed,String style,int bar){
        double[][] patterns=switch(style){
            case "funk"->new double[][]{{.5,1.5,2.75,3.5},{.75,1.75,3.25}};
            case "jazz"->new double[][]{{.5,2.5},{1.5,3.25}};
            case "rock"->new double[][]{{0,2},{.5,2.5}};
            case "ambient","cinematic"->new double[][]{{0},{1}};
            default->new double[][]{{.5,2.5},{1,3}};
        };
        long p=seed^((long)(bar/4)*0xD6E8FEB86659FD93L);
        return patterns[Math.floorMod(p,patterns.length)].clone();
    }
}
