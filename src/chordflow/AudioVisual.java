package chordflow;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.nio.file.*;
import javax.sound.sampled.*;
import java.io.*;
import java.util.*;

/**
 * Neural audio visualization. These contours come directly from PCM amplitude;
 * no MIDI notes, guessed chords or fabricated beat markers are displayed.
 */
public final class AudioVisual {
    private final int width,height,fps,seconds;
    private final double[] amplitude;
    private final BufferedImage image;
    private final Graphics2D g;
    private final Color background,text,muted,accent;
    public AudioVisual(Path wav,int width,int height,int fps,int seconds,String theme)throws IOException {
        this.width=width;this.height=height;this.fps=fps;this.seconds=seconds;
        this.image=new BufferedImage(width,height,BufferedImage.TYPE_3BYTE_BGR);
        this.g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if(theme.equals("paper")){
            background=new Color(241,238,229);text=new Color(39,38,34);
            muted=new Color(120,114,99);accent=new Color(137,109,68);
        }else if(theme.equals("olive")){
            background=new Color(35,37,33);text=new Color(232,228,215);
            muted=new Color(134,139,123);accent=new Color(161,169,126);
        }else{
            background=new Color(22,22,21);text=new Color(235,231,219);
            muted=new Color(134,131,122);accent=new Color(184,153,108);
        }
        amplitude=readEnvelope(wav,seconds);
    }
    private static double[] readEnvelope(Path wav,int seconds)throws IOException{
        int slots=seconds*40;
        double[] bins=new double[slots];int[] counts=new int[slots];
        try(AudioInputStream in=AudioSystem.getAudioInputStream(wav.toFile())){
            AudioFormat f=in.getFormat();
            if(!f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)||
                f.getSampleSizeInBits()!=16||f.getChannels()!=2||f.isBigEndian()||f.getFrameSize()!=4)
                throw new IOException("NEURAL_VISUAL_PCM_FORMAT_UNSUPPORTED");
            byte[] buffer=new byte[16384];long frame=0;
            int n;
            while((n=in.read(buffer))>0){
                for(int i=0;i+3<n;i+=4,frame++){
                    int at=(int)(frame*40.0/f.getSampleRate());
                    if(at<0||at>=slots)continue;
                    short l=(short)((buffer[i]&255)|(buffer[i+1]<<8));
                    short r=(short)((buffer[i+2]&255)|(buffer[i+3]<<8));
                    double level=(Math.abs(l)/32768.0+Math.abs(r)/32768.0)/2.0;
                    bins[at]+=level*level;counts[at]++;
                }
            }
        }catch(UnsupportedAudioFileException e){throw new IOException("NEURAL_VISUAL_INVALID_WAV",e);}
        for(int i=0;i<slots;i++)if(counts[i]>0)bins[i]=Math.sqrt(bins[i]/counts[i]);
        return bins;
    }
    public byte[] frame(int index){
        double t=index/(double)fps,scale=width/720.0;
        g.setColor(background);g.fillRect(0,0,width,height);
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.BOLD,Math.max(12,(int)(16*scale))));
        g.drawString("CHORDFLOW  /  INSTRUMENTAL AUDIO",(int)(width*.085),(int)(height*.085));
        g.setColor(text);g.setFont(new Font("SansSerif",Font.BOLD,Math.max(24,(int)(50*scale))));
        g.drawString("ORIGINAL SOUND",(int)(width*.085),(int)(height*.16));
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,(int)(13*scale))));
        g.drawString("LIVE AUDIO WAVEFORM  /  NOT MIDI TRANSCRIPTION",(int)(width*.085),(int)(height*.2));
        double center=height*.48,left=width*.085,right=width*.915,span=right-left;
        double window=8;
        g.setStroke(new BasicStroke(Math.max(1,(float)(1.25*scale))));
        g.setColor(muted);g.draw(new Line2D.Double(left,center,right,center));
        for(int pixel=(int)left;pixel<(int)right;pixel+=3){
            double moment=t+((pixel-left)/span-.50)*window;
            int slot=(int)(moment*40);
            if(slot<0||slot>=amplitude.length)continue;
            double peak=Math.min(1,Math.sqrt(amplitude[slot])*1.15);
            double extent=Math.max(1,peak*height*.22);
            g.setColor(moment<=t?accent:muted);
            g.draw(new Line2D.Double(pixel,center-extent,pixel,center+extent));
        }
        g.setColor(text);g.setStroke(new BasicStroke((float)(2.5*scale)));
        g.draw(new Line2D.Double(width*.50,height*.255,width*.50,height*.71));
        g.setColor(muted);g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(12,(int)(16*scale))));
        g.drawString(String.format(java.util.Locale.ROOT,"%02d:%02d / %02d:%02d",
            (int)t/60,(int)t%60,seconds/60,seconds%60),
            (int)(width*.085),(int)(height*.86));
        g.setColor(accent);
        g.fillRect((int)(width*.085),(int)(height*.89),
            (int)(width*.83*Math.min(1,t/seconds)),Math.max(2,(int)(3*scale)));
        return ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
    }
}
