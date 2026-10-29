package chordflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Original self-contained additive synthesizer: no stock music and no soundfont license burden. */
public final class Audio {
    public static final int RATE=48000;
    public record Stats(double peak,double rms,double silenceFraction) {}
    public static Stats synth(Composition c,Path wav)throws IOException{
        int count=(int)Math.round(c.duration*RATE);if(count<1)throw new IOException("AUDIO_DURATION_INVALID");
        float[] left=new float[count],right=new float[count];
        for(Composition.Note n:c.notes){
            double freq=440*Math.pow(2,(n.pitch()-69)/12.0);int start=(int)Math.round(n.start()*RATE);int end=Math.min(count,(int)Math.round((n.start()+n.duration())*RATE));
            double pan=n.voice().equals("voice-0")?-0.24:Math.max(-0.7,Math.min(0.7,(n.pitch()-63)/33.));
            double lGain=Math.sqrt((1-pan)/2),rGain=Math.sqrt((1+pan)/2),gain=0.18*n.velocity()/127.0;
            // Gentle additive pad/keys: fundamental, fifth harmonic fractions, slow ensemble vibrato and envelope.
            double phase=0,step=2*Math.PI*freq/RATE;double attack=c.mode.equals("guitar")?0.022:0.075;
            for(int s=start;s<end;s++){
                double elapsed=(s-start)/(double)RATE,remaining=(end-s)/(double)RATE;
                double env=Math.min(1,elapsed/attack)*Math.min(1,remaining/0.17);
                double swell=0.75+0.25*Math.sin(Math.PI*Math.min(1,elapsed/Math.max(.01,n.duration())));
                double signal=(Math.sin(phase)+0.24*Math.sin(2*phase+0.04)+0.13*Math.sin(3*phase)+0.045*Math.sin(5*phase))*env*swell*gain;
                left[s]+=(float)(signal*lGain);right[s]+=(float)(signal*rGain);
                phase+=step*(1+0.0007*Math.sin(2*Math.PI*5.1*elapsed));
                if(phase>2*Math.PI)phase-=2*Math.PI;
            }
        }
        // Deterministic soft echo/reverb and transparent peak limiter.
        int delay=(int)(.18*RATE);for(int i=delay;i<count;i++){left[i]+=left[i-delay]*.095f;right[i]+=right[i-delay]*.095f;}
        int delay2=(int)(.32*RATE);for(int i=delay2;i<count;i++){left[i]+=right[i-delay2]*.045f;right[i]+=left[i-delay2]*.045f;}
        double max=0,sum=0;int silent=0;
        for(int i=0;i<count;i++){max=Math.max(max,Math.max(Math.abs(left[i]),Math.abs(right[i])));sum+=(left[i]*left[i]+right[i]*right[i])*0.5;if(Math.abs(left[i])+Math.abs(right[i])<.0004)silent++;}
        if(max<.00001)throw new IOException("AUDIO_SILENT");
        double scale=.50/max,peak=0; // target typical short-form integrated loudness near -15 LUFS
        Files.createDirectories(wav.toAbsolutePath().getParent());
        try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(wav))){
            int bytes=count*4;out.write(new byte[]{'R','I','F','F'});writeInt(out,36+bytes);out.write(new byte[]{'W','A','V','E','f','m','t',' '});writeInt(out,16);writeShort(out,1);writeShort(out,2);writeInt(out,RATE);writeInt(out,RATE*4);writeShort(out,4);writeShort(out,16);out.write(new byte[]{'d','a','t','a'});writeInt(out,bytes);
            for(int i=0;i<count;i++){int l=(int)Math.round(Math.max(-1,Math.min(1,left[i]*scale))*32767),r=(int)Math.round(Math.max(-1,Math.min(1,right[i]*scale))*32767);writeShort(out,l);writeShort(out,r);peak=Math.max(peak,Math.max(Math.abs(l),Math.abs(r))/32767.);}
        }
        Stats result=new Stats(peak,Math.sqrt(sum/count)*scale,(double)silent/count);
        if(result.rms()<.007||result.peak()>.98||result.silenceFraction()>.60)throw new IOException("AUDIO_QA_FAILED "+result);
        return result;
    }
    private static void writeShort(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>8)&255);}
    private static void writeInt(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>8)&255);o.write((v>>16)&255);o.write((v>>24)&255);}
}
