package chordflow;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

/** Neutral editorial music typography, real event bars, and verified fret diagrams. */
public final class Visual {
    private final Composition c;private final int w,h,fps;
    private final BufferedImage img;private final Graphics2D g;
    private final Color bg,fg,muted,grid,accent,melody,bass,accomp;
    private final Map<String,List<Composition.Note>> groups=new LinkedHashMap<>();
    public Visual(Composition c,int w,int h,int fps){
        this.c=c;this.w=w;this.h=h;this.fps=fps;
        img=new BufferedImage(w,h,BufferedImage.TYPE_3BYTE_BGR);g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for(Composition.Note n:c.notes)groups.computeIfAbsent(n.voice(),k->new ArrayList<>()).add(n);
        groups.values().forEach(v->v.sort(Comparator.comparingDouble(Composition.Note::start)));
        String theme=c.theme;
        if(theme.equals("paper")){
            bg=new Color(241,238,229);fg=new Color(39,38,34);muted=new Color(111,106,96);grid=new Color(213,208,195);accent=new Color(135,113,73);
            melody=new Color(46,47,43);bass=new Color(144,119,78);accomp=new Color(130,128,120);
        }else if(theme.equals("olive")){
            bg=new Color(35,37,33);fg=new Color(232,228,215);muted=new Color(150,154,141);grid=new Color(65,68,58);accent=new Color(161,169,126);
            melody=new Color(232,228,215);bass=new Color(161,169,126);accomp=new Color(126,131,113);
        }else{
            // Legacy gold/blue/violet also map to a restrained monochrome system.
            bg=new Color(22,22,21);fg=new Color(235,231,219);muted=new Color(150,147,140);grid=new Color(54,54,51);accent=new Color(182,153,104);
            melody=new Color(236,231,218);bass=new Color(184,155,108);accomp=new Color(137,135,129);
        }
    }
    public byte[] frame(int frame){
        double t=frame/(double)fps,scale=w/720.0,cy=h*.465,playX=w*.49,pxps=w*.104;
        g.setColor(bg);g.fillRect(0,0,w,h);
        g.setFont(new Font("SansSerif",Font.BOLD,Math.max(11,(int)(14*scale))));g.setColor(muted);
        g.drawString("CHORDFLOW   /   ORIGINAL MUSIC",(int)(w*.085),(int)(h*.078));
        Composition.Harmony current=c.harmonyAt(t);
        g.setColor(fg);g.setFont(new Font("SansSerif",Font.BOLD,Math.max(27,(int)(55*scale))));
        String label=current.symbol();g.drawString(label,(int)(w*.085),(int)(h*.150));
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(12*scale))));
        g.drawString("KEY  "+c.key+"     •     "+Math.round(c.bpm)+" BPM     •     4/4",(int)(w*.085),(int)(h*.177));
        g.setColor(grid);g.setStroke(new BasicStroke(Math.max(0.7f,(float)(.6*scale))));
        double beatSeconds=60/c.bpm;
        int firstBeat=(int)Math.max(0,Math.floor((t-playX/pxps)/beatSeconds));
        int finalBeat=(int)Math.ceil((t+(w-playX)/pxps)/beatSeconds);
        for(int b=firstBeat;b<=finalBeat;b++){
            double x=playX+(b*beatSeconds-t)*pxps;
            if(x<0||x>w)continue;
            g.setColor(b%4==0?muted:grid);g.setStroke(new BasicStroke((float)((b%4==0?1.25:.55)*scale)));
            g.draw(new Line2D.Double(x,h*.26,x,h*.67));
        }
        for(int i=0;i<22;i++){
            double y=h*.28+i*h*.0168;g.setColor(grid);g.setStroke(new BasicStroke((float)(.60*scale)));
            g.draw(new Line2D.Double(w*.07,y,w*.93,y));
        }
        for(Composition.Harmony harmonic:c.arranged?c.harmony:List.<Composition.Harmony>of()){
            double x=playX+(harmonic.start()-t)*pxps;
            if(x<0||x>w)continue;
            g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(11*scale))));
            g.drawString(harmonic.symbol(),(int)x+3,(int)(h*.246));
        }
        for(var group:groups.entrySet()){
            String voice=group.getKey();boolean isLead=voice.startsWith("lead"),isBass=voice.startsWith("bass");
            Color ink=isLead?melody:isBass?bass:accomp;
            for(Composition.Note n:group.getValue()){
                double x0=playX+(n.start()-t)*pxps,x1=playX+((n.start()+n.duration())-t)*pxps;
                if(x1<0||x0>w)continue;
                double y=noteY(n.pitch(),cy);
                g.setColor(ink);g.setStroke(new BasicStroke((float)((isLead?3.2:isBass?2.8:1.6)*scale),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(Math.max(0,x0),y,Math.min(w,x1),y));
                if(x0>=0&&x0<w){g.setColor(ink);double r=(isLead?3.5:2.2)*scale;g.fill(new Ellipse2D.Double(x0-r,y-r,r*2,r*2));}
                if(n.start()<=t&&n.start()+n.duration()>t&&isLead){
                    g.setFont(new Font("SansSerif",Font.BOLD,Math.max(10,(int)(12*scale))));g.setColor(fg);
                    g.drawString(Composition.pitchName(n.pitch()),(int)(playX+9*scale),(int)(y-6*scale));
                }
            }
        }
        g.setColor(accent);g.setStroke(new BasicStroke((float)(1.6*scale)));
        g.draw(new Line2D.Double(playX,h*.251,playX,h*.68));
        if(c.arranged&&c.drums.size()>0)drawBeat(t,scale);
        if(c.mode.equals("guitar"))drawGuitar(current,scale);
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(9,(int)(12*scale))));
        g.drawString("MELODY  /  BASS  /  HARMONY",(int)(w*.085),(int)(h*.946));
        return ((DataBufferByte)img.getRaster().getDataBuffer()).getData();
    }
    private double noteY(int pitch,double cy){
        // Pitch range 36–88 is scaled into the notation pane to avoid clipped bass or lead.
        return cy-(pitch-61)*h*.00765;
    }
    private void drawBeat(double t,double scale){
        double beat=60/c.bpm;
        double x=w*.085,y=h*.70,step=(w*.83)/16;
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(9,(int)(11*scale))));
        g.drawString("RHYTHM",(int)x,(int)(y-14*scale));
        for(int i=0;i<16;i++){
            double beatTime=(Math.floor(t/(4*beat))*4+i*.25)*beat;
            boolean kick=false,snare=false,hat=false;
            for(Composition.Drum d:c.drums){if(Math.abs(d.start()-beatTime)<beat*.12){if(d.kind().equals("kick"))kick=true;else if(d.kind().equals("snare"))snare=true;else hat=true;}}
            int bar=(int)(x+i*step), yy=(int)y;
            g.setColor(kick?accent:snare?fg:hat?muted:grid);
            g.fillRoundRect(bar,yy,(int)(step*.54),Math.max(2,(int)((kick?16:snare?12:hat?7:3)*scale)),3,3);
        }
    }
    private void drawGuitar(Composition.Harmony harmonic,double scale){
        int[] frets=harmonic.frets();if(frets==null)return;
        int min=25,max=0;for(int fret:frets)if(fret>0){min=Math.min(min,fret);max=Math.max(max,fret);}int base=max<=4?1:min;
        double x=w*.28,y=h*.775,dx=60*scale,dy=29*scale;
        g.setStroke(new BasicStroke((float)(.95*scale)));g.setColor(muted);
        for(int s=0;s<6;s++)g.draw(new Line2D.Double(x+s*dx,y,x+s*dx,y+4*dy));
        for(int f=0;f<=4;f++)g.draw(new Line2D.Double(x,y+f*dy,x+5*dx,y+f*dy));
        if(base==1){g.setStroke(new BasicStroke((float)(2.9*scale)));g.draw(new Line2D.Double(x,y,x+5*dx,y));}
        for(int s=0;s<6;s++){
            int fret=frets[s];double sx=x+s*dx;
            if(fret==0){g.setColor(fg);g.setStroke(new BasicStroke((float)(1.4*scale)));g.draw(new Ellipse2D.Double(sx-5*scale,y-17*scale,10*scale,10*scale));}
            else if(fret<0){g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,(int)(12*scale)));g.drawString("x",(int)(sx-3*scale),(int)(y-9*scale));}
            else{double fy=y+(fret-base+.5)*dy;g.setColor(accent);g.fill(new Ellipse2D.Double(sx-7*scale,fy-7*scale,14*scale,14*scale));}
        }
        if(base>1){g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(12*scale))));g.setColor(fg);g.drawString(base+"fr",(int)(x-40*scale),(int)(y+dy));}
    }
}