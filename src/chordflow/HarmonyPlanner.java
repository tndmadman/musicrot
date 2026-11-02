package chordflow;

import java.util.*;

/**
 * Style-sensitive harmonic planner. Compose section changes and cadences
 * before melody or bass so those parts can respond to actual harmony.
 */
public final class HarmonyPlanner {
    private HarmonyPlanner(){}
    private static final int[] MAJOR={0,2,4,5,7,9,11};
    private static final int[] MINOR={0,2,3,5,7,8,10};
    private static final String[] NAMES={"C","Db","D","Eb","E","F","Gb","G","Ab","A","Bb","B"};
    private static final int[][] MAJOR_CYCLES={
        {0,5,3,4},{0,3,4,0},{5,1,4,0},{0,4,5,3},
        {0,2,5,3},{1,4,0,5},{0,3,1,4},{5,3,0,4}
    };
    private static final int[][] MINOR_CYCLES={
        {0,5,2,6},{0,3,6,2},{5,6,0,4},{0,6,5,4},{0,2,5,4}
    };
    public static List<Composition.Harmony> plan(long seed,String style,int key,boolean minor,
                                                   double bpm,int seconds){
        if(key<0||key>11||bpm<=0)throw new IllegalArgumentException("HARMONY_PARAMETERS_INVALID");
        double beat=60/bpm,total=seconds/beat;
        List<SongFormPlanner.Section> form=SongFormPlanner.plan(total,seed,style);
        Random random=new Random(seed^0x58D1E3BE71A2F4C3L);
        int[][] cycles=minor?MINOR_CYCLES:MAJOR_CYCLES;
        int[] home=cycles[random.nextInt(cycles.length)];
        int[] contrast=cycles[random.nextInt(cycles.length)];
        int[] scale=minor?MINOR:MAJOR;
        List<Composition.Harmony> result=new ArrayList<>();
        double at=0;int chord=0;
        while(at<total-1e-7){
            int bar=(int)(at/4);
            SongFormPlanner.Section section=SongFormPlanner.at(form,bar);
            int[] cycle=switch(section.kind()){
                case "answer","bridge","development"->contrast;
                default->home;
            };
            double remaining=total-at;
            double maxSection=Math.min(total,section.lastBar()*4)-at;
            double units;
            if(style.equals("ambient")||style.equals("cinematic")){
                units=switch((chord+Math.floorMod(seed,3))%4){case 0->8.0;case 1->6.0;default->4.0;};
            }else if(style.equals("jazz")){
                units=chord%3==2?6.0:4.0;
            }else{
                units=chord%5==3?6.0:4.0;
            }
            double duration=Math.min(remaining,Math.min(units,Math.max(.001,maxSection)));
            if(remaining>4.0&&remaining-duration<4.0)duration=remaining-4.0;
            int degree=cycle[(chord+section.firstBar()/4)%cycle.length];
            // An actual dominant approach precedes the tonic where duration allows.
            if(total>=10&&remaining<=8.001&&remaining>4.001){
                duration=remaining-4;
                degree=4;
            }else if(remaining<=4.001)degree=0;
            boolean seventh=style.equals("jazz")||style.equals("lofi")&&chord%3==0||
                style.equals("cinematic")&&section.kind().equals("bridge");
            result.add(create(key,minor,scale,degree,at*beat,duration*beat,seventh));
            at+=duration;chord++;
        }
        return List.copyOf(result);
    }
    private static Composition.Harmony create(int key,boolean minor,int[] scale,int degree,
                                               double start,double duration,boolean seventh){
        int rootPc=Math.floorMod(key+scale[degree],12);
        int third=scale[(degree+2)%7]+(degree+2>=7?12:0)-scale[degree];
        int fifth=scale[(degree+4)%7]+(degree+4>=7?12:0)-scale[degree];
        int seventhInterval=scale[(degree+6)%7]+(degree+6>=7?12:0)-scale[degree];
        // Harmonic minor: dominant major triad / dominant seventh.
        if(minor&&degree==4){third=4;fifth=7;seventhInterval=10;}
        // Jazz and lofi use seventh chords as a style choice, not for every beat.
        int[] pitches=seventh?
            new int[]{36+rootPc,60+rootPc,60+rootPc+third,60+rootPc+fifth,
                60+rootPc+seventhInterval}:
            new int[]{36+rootPc,60+rootPc,60+rootPc+third,60+rootPc+fifth};
        String quality=!seventh?
            (third==3&&fifth==6?"dim":third==3?"m":""):
            third==3&&fifth==6?"m7b5":
            third==3?(seventhInterval==11?"m(maj7)":"m7"):
            seventhInterval==11?"maj7":"7";
        return new Composition.Harmony(start,duration,NAMES[rootPc]+quality,pitches,null);
    }
}
