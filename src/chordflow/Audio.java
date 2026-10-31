package chordflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic multitrack additive synthesis and original procedural drums. */
public final class Audio {
    public static final int RATE=48000;
    public record Stats(double peak,double rms,double silenceFraction) {}
    /**
     * Published videos use actual sampled instrument recordings by default.
     * Only an explicit operator choice of "preview" permits procedural audio.
     * Self-tests and lightweight previews continue to call synth() directly.
     */
    public static Stats synthProduction(Composition c,Path wav)throws IOException{
        String quality=System.getenv().getOrDefault("CHORDFLOW_AUDIO_QUALITY","sampled");
        if(!Set.of("auto","sampled","preview").contains(quality))
            throw new IOException("CHORDFLOW_AUDIO_QUALITY_INVALID");
        if(quality.equals("preview")){
            System.err.println("WARNING SYNTHETIC_PREVIEW_AUDIO: not natural instrument sound");
            return synthPreview(c,wav);
        }
        return SampledAudio.synth(c,wav);
    }
    public static Stats synth(Composition c,Path wav)throws IOException{
        String quality=System.getenv().getOrDefault("CHORDFLOW_AUDIO_QUALITY","preview");
        if(!Set.of("auto","sampled","preview").contains(quality))
            throw new IOException("CHORDFLOW_AUDIO_QUALITY_INVALID");
        if(quality.equals("sampled")||(quality.equals("auto")&&SampledAudio.configured()))
            return SampledAudio.synth(c,wav);
        return synthPreview(c,wav);
    }
    private static Stats synthPreview(Composition c,Path wav)throws IOException{
        // Portable synthetic tones are explicitly a preview-only fallback.
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
            if(bass)tone=switch(preset){
                case "glass"->Math.sin(phase)+.12*Math.sin(3*phase);
                case "layered"->Math.sin(phase)+.32*Math.sin(2*phase)+.08*Math.sin(phase*.998);
                case "nylon-pluck"->Math.sin(phase)+.14*Math.sin(2*phase);
                default->Math.sin(phase)+.23*Math.sin(2*phase)+.065*Math.sin(3*phase);
            };
            else if(lead)tone=switch(preset){
                case "glass"->Math.sin(phase+.26*Math.sin(2.01*phase))+.21*Math.sin(4*phase);
                case "nylon-pluck"->Math.sin(phase)+.45*Math.sin(2*phase)+.23*Math.sin(3*phase);
                case "layered"->Math.sin(phase)+.27*Math.sin(2*phase)+.09*Math.sin(phase*1.004);
                case "warm-pad"->Math.sin(phase)+.15*Math.sin(2*phase);
                default->Math.sin(phase)+.28*Math.sin(2*phase)+.13*Math.sin(3*phase);
            };
            else if(guitar)tone=switch(preset){
                case "glass"->Math.sin(phase)+.31*Math.sin(5*phase);
                case "layered"->Math.sin(phase)+.48*Math.sin(2*phase)+.20*Math.sin(3*phase)+.12*Math.sin(5*phase);
                case "warm-pad"->Math.sin(phase)+.12*Math.sin(2*phase);
                case "soft-keys"->Math.sin(phase)+.24*Math.sin(2*phase)+.12*Math.sin(3*phase);
                default->Math.sin(phase)+.36*Math.sin(2*phase)+.19*Math.sin(3*phase)+.085*Math.sin(4*phase);
            };
            else if(arp)tone=switch(preset){
                case "glass"->Math.sin(phase+.23*Math.sin(2*phase))+.12*Math.sin(5*phase);
                case "layered"->Math.sin(phase)+.21*Math.sin(2*phase)+.18*Math.sin(phase*1.003);
                case "nylon-pluck"->Math.sin(phase)+.37*Math.sin(2*phase)+.17*Math.sin(4*phase);
                default->Math.sin(phase)+.24*Math.sin(3*phase)+.09*Math.sin(5*phase);
            };
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
        String kind=hit.kind();
        boolean kick=kind.equals("kick"),snare=kind.equals("snare"),
            clap=kind.equals("clap"),rim=kind.equals("rim"),tom=kind.equals("tom"),
            metallic=kind.equals("ride")||kind.equals("crash");
        double duration=switch(kind){
            case "kick"->.34;case "snare"->.22;case "clap"->.28;
            case "rim"->.09;case "tom"->.29;case "open-hat"->.32;
            case "ride"->.55;case "crash"->.9;default->.11;
        };
        int length=(int)(RATE*duration);
        int rng=(int)(seed^(seed>>>32))|1;double phase=0;
        for(int i=0;i<length&&start+i<left.length;i++){
            double t=i/(double)RATE;
            rng^=rng<<13;rng^=rng>>>17;rng^=rng<<5;
            double noise=((rng&0xffff)/32767.5-1);
            double env=Math.exp(-t*(kick?18:tom?13:clap?19:rim?60:metallic?5:snare?24:43));
            double sample;
            if(kick){
                phase+=2*Math.PI*(51+86*Math.exp(-t*34))/RATE;
                sample=Math.sin(phase)*env*.43;
            }else if(tom){
                phase+=2*Math.PI*(108+58*Math.exp(-t*27))/RATE;
                sample=(Math.sin(phase)*.29+noise*.025)*env;
            }else if(snare||clap||rim){
                phase+=2*Math.PI*(rim?480:175)/RATE;
                sample=(noise*(rim?.13:.23)+Math.sin(phase)*(rim?.07:.09))*env;
                if(clap)sample*=1+.4*Math.sin(2*Math.PI*32*t);
            }else{
                sample=noise*env*(metallic?.095:kind.equals("open-hat")?.145:.11);
                if(metallic)sample+=Math.sin(2*Math.PI*5200*t)*env*.018;
            }
            float v=(float)(sample*level);
            left[start+i]+=v;right[start+i]+=v;
        }
    }
    private static void writeShort(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);}
    private static void writeInt(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
}