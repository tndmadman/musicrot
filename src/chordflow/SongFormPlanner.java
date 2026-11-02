package chordflow;

import java.util.*;

/** Canonical song-form map. All composers receive the same section boundaries. */
public final class SongFormPlanner {
    private SongFormPlanner(){}
    public record Section(int firstBar,int lastBar,String kind,double energy){
        public boolean contains(int bar){return bar>=firstBar&&bar<lastBar;}
    }
    public static List<Section> plan(double totalBeats,long seed,String style){
        int bars=Math.max(1,(int)Math.ceil(totalBeats/4.0));
        if(bars<6)return List.of(new Section(0,bars,"theme",1.0));
        int intro=bars>=18?2:1, outro=bars>=18?2:1;
        int body=bars-intro-outro;
        int first=Math.max(2,body/3);
        int middle=Math.max(2,body/3);
        int last=body-first-middle;
        if(last<1){first=Math.max(1,first-1);last=body-first-middle;}
        int pattern=(int)Math.floorMod(seed^((long)style.hashCode()<<1),3);
        String contrast=pattern==0?"answer":pattern==1?"bridge":"development";
        List<Section> form=new ArrayList<>();
        if(intro>0)form.add(new Section(0,intro,"intro",.58));
        int at=intro;
        form.add(new Section(at,at+first,"theme",.90));at+=first;
        form.add(new Section(at,at+middle,contrast,pattern==1?.62:1.12));at+=middle;
        if(last>0)form.add(new Section(at,at+last,"return",1.02));
        if(outro>0)form.add(new Section(bars-outro,bars,"outro",.60));
        return List.copyOf(form);
    }
    public static Section at(List<Section> form,int bar){
        for(Section section:form)if(section.contains(bar))return section;
        return form.get(form.size()-1);
    }
}
