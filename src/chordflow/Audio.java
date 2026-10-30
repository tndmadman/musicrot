package chordflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic multitrack additive synthesis and original procedural drums. */
public final class Audio {
    public static final int RATE=48000;
    public record Stats(double peak,double rms,double silenceFraction) {}
    public static Stats synth(Composition c,Path wav)throws IOException{
        int samples=(int)Math.round(c.duration*RATE);
        if(samples<1)throw new IOException("AUDIO_DURATION_INVALID");
        float[] left=new float[samples],right=new float[samples];
        for(Composition.Note n:c.notes)renderNote(c,n,left,right);
        int hitIndex=0;
        for(Composition.Drum hit:c.drums)renderDrum(hit,c.seed+(hitIndex++*10007L),left,right);
        // Gentle dual delay rather than the old full-strength synthetic chorus.
        int d1=(int)(.172*RATE),d2=(int)(.297*RATE);
        for(int i=d1;i<samples;i++){
            left[i]+=left[i-d1]*.065f;right[i]+=right[i-d1]*.065f;
            if(i>=d2){left[i]+=right[i-d2]*.028f;right[i]+=left[i-d2]*.028f;}
        }
        double max=0,sum=0;int silent=0;
        for(int i=0;i<samples;i++){
            double a=left[i],b=right[i];max=Math.max(max,Math.max(Math.abs(a),Math.abs(b)));
            sum+=(a*a+b*b)/2;if(Math.abs(a)+Math.abs(b)<.00045)silent++;
        }
        if(max<1e-5)throw new IOException("AUDIO_SILENT");
        double scale=.65/max,peak=0;
        Files.createDirectories(wav.toAbsolutePath().getParent());
        try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(wav))){
            int bytes=samples*4;out.write(new byte[]{'R','I','F','F'});writeInt(out,36+bytes);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});writeInt(out,16);writeShort(out,1);writeShort(out,2);
            writeInt(out,RATE);writeInt(out,RATE*4);writeShort(out,4);writeShort(out,16);
            out.write(new byte[]{'d','a','t','a'});writeInt(out,bytes);
            for(int i=0;i<samples;i++){
                int l=(int)Math.round(Math.max(-1,Math.min(1,left[i]*scale))*32767);
                int r=(int)Math.round(Math.max(-1,Math.min(1,right[i]*scale))*32767);
                writeShort(out,l);writeShort(out,r);peak=Math.max(peak,Math.max(Math.abs(l),Math.abs(r))/32767.);
            }
        }
        Stats stats=new Stats(peak,Math.sqrt(sum/samples)*scale,(double)silent/samples);
        if(stats.rms()<.007||stats.peak()>.98||stats.silenceFraction()>.60)throw new IOException("AUDIO_QA_FAILED "+stats);
        return stats;
    }
    private static void renderNote(Composition c,Composition.Note n,float[] left,float[] right){
        double freq=440*Math.pow(2,(n.pitch()-69)/12.0);
        int start=Math.max(0,(int)Math.round(n.start()*RATE));
        int end=Math.min(left.length,(int)Math.round((n.start()+n.duration())*RATE));
        String voice=n.voice(),preset=c.instrumentPreset;
        boolean lead=voice.startsWith("lead"),bass=voice.startsWith("bass"),arp=voice.startsWith("arp"),guitar=voice.startsWith("guitar");
        double pan=bass?0:lead?.05:guitar?Math.max(-.5,Math.min(.5,(n.pitch()-57)/30.)):arp?-.22: .25;
        double lGain=Math.sqrt((1-pan)/2),rGain=Math.sqrt((1+pan)/2);
        double gain=(bass?.28:lead?.245:arp?.12:guitar?.14:.085)*n.velocity()/100.;
        double attack=bass?.005:lead?.018:arp?.004:guitar?.003:preset.equals("warm-pad")?.115:.040;
        double release=lead?.11:bass?.095:arp?.08:guitar?.075:.17;
        double phase=0,step=2*Math.PI*freq/RATE;
        for(int s=start;s<end;s++){
            double elapsed=(s-start)/(double)RATE,remaining=(end-s)/(double)RATE;
            double env=Math.min(1,elapsed/attack)*Math.min(1,remaining/release);
            double decay=guitar?Math.exp(-2.1*elapsed):arp?Math.exp(-1.5*elapsed):bass?Math.exp(-.33*elapsed):lead?.85+.15*Math.exp(-elapsed):1;
            double tone;
            if(bass)tone=Math.sin(phase)+.23*Math.sin(2*phase)+.065*Math.sin(3*phase);
            else if(lead)tone=Math.sin(phase)+.28*Math.sin(2*phase)+.13*Math.sin(3*phase);
            else if(guitar)tone=Math.sin(phase)+.36*Math.sin(2*phase)+.19*Math.sin(3*phase)+.085*Math.sin(4*phase);
            else if(arp)tone=Math.sin(phase)+.24*Math.sin(3*phase)+.09*Math.sin(5*phase);
            else tone=switch(preset){
                case "soft-keys"->Math.sin(phase)+.38*Math.sin(2*phase)+.14*Math.sin(3*phase);
                case "nylon-pluck"->Math.sin(phase)+.30*Math.sin(2*phase)+.10*Math.sin(3*phase);
                case "glass"->Math.sin(phase+.45*Math.sin(2.004*phase))+.09*Math.sin(4*phase);
                case "layered"->Math.sin(phase)+.24*Math.sin(2*phase)+.12*Math.sin(phase*1.002);
                default->Math.sin(phase)+.18*Math.sin(2*phase)+.10*Math.sin(3*phase);
            };
            double sample=tone*env*decay*gain;
            left[s]+=(float)(sample*lGain);right[s]+=(float)(sample*rGain);
            phase+=step;if(phase>=2*Math.PI)phase-=2*Math.PI;
        }
    }
    private static void renderDrum(Composition.Drum hit,long seed,float[] left,float[] right){
        int start=(int)Math.round(hit.start()*RATE);
        double level=hit.velocity()/127.0;
        boolean kick=hit.kind().equals("kick"),snare=hit.kind().equals("snare");
        int length=(int)(RATE*(kick?.34:snare?.22:hit.kind().equals("open-hat")?.33:.105));
        int rng=(int)(seed^(seed>>>32))|1;double phase=0;
        for(int i=0;i<length&&start+i<left.length;i++){
            double t=i/(double)RATE,env=Math.exp(-t*(kick?18:snare?24:43));
            rng^=rng<<13;rng^=rng>>>17;rng^=rng<<5;
            double noise=((rng&0xffff)/32767.5-1);
            double sample;
            if(kick){double freq=51+86*Math.exp(-t*34);phase+=2*Math.PI*freq/RATE;sample=Math.sin(phase)*env*.43;}
            else if(snare){phase+=2*Math.PI*175/RATE;sample=(noise*.23+Math.sin(phase)*.09)*env;}
            else{sample=noise*env*(hit.kind().equals("open-hat")?.145:.11);}
            float v=(float)(sample*level);left[start+i]+=v;right[start+i]+=v;
        }
    }
    private static void writeShort(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);}
    private static void writeInt(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
}