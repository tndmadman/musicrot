package chordflow;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.List;

/** Buffered one-frame-at-a-time Java2D renderer. Audio and video use the same canonical seconds. */
public final class Visual {
    private final Composition c;private final int w,h,fps;private final BufferedImage img;private final Graphics2D g;
    private final Color[] palette;
    public Visual(Composition c,int w,int h,int fps){this.c=c;this.w=w;this.h=h;this.fps=fps;this.img=new BufferedImage(w,h,BufferedImage.TYPE_3BYTE_BGR);this.g=img.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        palette=switch(c.theme){case "blue"->new Color[]{new Color(114,178,255),new Color(246,235,179),new Color(230,240,255),new Color(105,220,239),new Color(200,166,255),new Color(255,170,197)};case "violet"->new Color[]{new Color(190,142,255),new Color(136,249,231),new Color(255,195,223),new Color(255,238,172),new Color(191,226,255)};default->new Color[]{new Color(255,113,140),new Color(255,177,109),new Color(249,223,122),new Color(242,245,255),new Color(210,222,255)};};
    }
    public byte[] frame(int frame){
        double t=frame/(double)fps,scale=w/720.0,cy=h*(c.mode.equals("guitar")?.40:.42),playX=w*.50,pxps=w*.078;
        g.setColor(new Color(5,7,21));g.fillRect(0,0,w,h);
        g.setColor(new Color(11,15,35));g.fillOval((int)(w*.1),(int)(h*.13),(int)(w*.82),(int)(h*.52));
        // Grid moves in real time. Grid lines are aligned to chord event boundaries.
        g.setStroke(new BasicStroke(Math.max(.5f,(float)(.65*scale))));
        for(Composition.Chord chord:c.chords){double x=playX+(chord.start()-t)*pxps;if(x<0||x>w)continue;g.setColor(new Color(34,39,63));g.draw(new Line2D.Double(x,h*.20,x,h*.66));}
        for(int i=-8;i<=8;i++){double y=cy+i*21*scale;g.setColor(new Color(24,29,51));g.draw(new Line2D.Double(w*.07,y,w*.93,y));}
        for(Composition.Chord chord:c.chords){double x=playX+(chord.start()-t)*pxps;if(x<0||x>w)continue;g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(9,(int)(11*scale))));g.setColor(new Color(116,126,162));g.drawString(chord.symbol(),(int)x+2,(int)(h*.19));}
        // Trajectories: note pitch is static within a chord, with Bezier movement at an actual chord boundary.
        int voiceCount=c.notes.stream().mapToInt(n->Integer.parseInt(n.voice().replaceAll("[^0-9]",""))).max().orElse(0)+1;
        for(int v=0;v<voiceCount;v++){
            Color color=palette[v%palette.length];Path2D path=new Path2D.Double();boolean begun=false;
            for(int j=0;j<c.chords.size();j++){
                Composition.Chord chord=c.chords.get(j); if(v>=chord.pitches().length){begun=false;continue;}
                double x0=playX+(chord.start()-t)*pxps,x1=playX+(chord.start()+chord.duration()-t)*pxps;
                double yy=yPitch(chord.pitches()[v],cy,scale);
                double xCur=Math.max(-w*.05,x0),xEnd=Math.min(w*1.05,x1);
                if(xEnd<0||xCur>w)continue;
                if(!begun){path.moveTo(xCur,yy);begun=true;}
                double curveWidth=Math.min(25*scale,(x1-x0)*.30);
                double flatEnd=x1-curveWidth;
                if(flatEnd>xCur)path.lineTo(Math.min(w*1.05,flatEnd),yy);
                if(j+1<c.chords.size()&&v<c.chords.get(j+1).pitches().length){double next=yPitch(c.chords.get(j+1).pitches()[v],cy,scale);path.curveTo(x1-curveWidth*.5,yy,x1-curveWidth*.5,next,x1,next);}else path.lineTo(xEnd,yy);
            }
            stroke(path,color,scale);
            Composition.Chord now=c.at(t);if(v<now.pitches().length){double yy=yPitch(now.pitches()[v],cy,scale);g.setColor(new Color(255,255,255,35));g.fill(new Ellipse2D.Double(playX-8*scale,yy-8*scale,16*scale,16*scale));g.setColor(color);g.fill(new Ellipse2D.Double(playX-3.4*scale,yy-3.4*scale,6.8*scale,6.8*scale));g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(12*scale))));g.drawString(Composition.pitchName(now.pitches()[v]),(int)(playX+9*scale),(int)(yy+3*scale));}
        }
        g.setColor(new Color(226,233,251));g.setFont(new Font("SansSerif",Font.BOLD,Math.max(32,(int)(56*scale))));drawCentered(c.at(t).symbol(),(int)(h*.124));
        g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(13*scale))));g.setColor(new Color(131,148,186));drawCentered("CHORDFLOW  •  ORIGINAL COMPOSITION",(int)(h*.151));
        g.setColor(new Color(90,102,142,145));g.setStroke(new BasicStroke((float)(1.2*scale)));g.draw(new Line2D.Double(playX,h*.193,playX,h*.665));
        if(c.mode.equals("guitar"))drawGuitar(c.at(t),scale);
        return ((DataBufferByte)img.getRaster().getDataBuffer()).getData();
    }
    private double yPitch(int pitch,double cy,double scale){return cy-(pitch-58)*11*scale;}
    private void stroke(Shape s,Color c,double scale){for(int j=3;j>=0;j--){int alpha=new int[]{244,45,22,9}[j];float width=(float)((j==0?2.2:j==1?6:j==2?12:22)*scale);g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),alpha));g.setStroke(new BasicStroke(Math.max(.8f,width),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(s);}}
    private void drawCentered(String text,int y){FontMetrics fm=g.getFontMetrics();g.drawString(text,(w-fm.stringWidth(text))/2,y);}
    private void drawGuitar(Composition.Chord chord,double scale){
        if(chord.frets()==null)return;int[] frets=chord.frets();int min=25,max=0;for(int fret:frets)if(fret>0){min=Math.min(min,fret);max=Math.max(max,fret);}int base=max<=4?1:min;
        double x=w*.29,y=h*.73,dx=59*scale,dy=42*scale;
        g.setStroke(new BasicStroke((float)(1*scale)));g.setColor(new Color(100,122,171));
        for(int s=0;s<6;s++)g.draw(new Line2D.Double(x+s*dx,y,x+s*dx,y+4*dy));
        for(int f=0;f<5;f++)g.draw(new Line2D.Double(x,y+f*dy,x+5*dx,y+f*dy));
        if(base==1){g.setColor(new Color(181,194,226));g.setStroke(new BasicStroke((float)(4*scale)));g.draw(new Line2D.Double(x,y,x+5*dx,y));}
        // F and Bb shapes have actual first-fret barre across physically covered strings.
        if(chord.symbol().equals("F")||chord.symbol().equals("Bb")){
            int start=chord.symbol().equals("Bb")?1:0;
            g.setColor(new Color(221,228,244,220));g.setStroke(new BasicStroke((float)(17*scale),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x+start*dx,y+.5*dy,x+5*dx,y+.5*dy));
        }
        for(int s=0;s<6;s++){
            int fret=frets[s];double sx=x+s*dx;
            if(fret==0){g.setColor(new Color(225,236,255));g.setStroke(new BasicStroke((float)(2*scale)));g.draw(new Ellipse2D.Double(sx-5*scale,y-21*scale,10*scale,10*scale));}
            else if(fret<0){g.setColor(new Color(115,123,153));g.drawString("x",(int)(sx-3*scale),(int)(y-12*scale));}
            else{double fy=y+(fret-base+.5)*dy;g.setColor(new Color(231,238,253));g.fill(new Ellipse2D.Double(sx-9*scale,fy-9*scale,18*scale,18*scale));}
        }
        if(base>1){g.setColor(new Color(156,174,209));g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(17*scale))));g.drawString(base+"fr",(int)(x-42*scale),(int)(y+dy));}
        g.setColor(new Color(135,151,190));g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(15*scale))));drawCentered("GUITAR VOICING — SOUNDING NOTES",(int)(h*.895));
    }
}
