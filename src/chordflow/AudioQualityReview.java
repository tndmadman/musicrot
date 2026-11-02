package chordflow;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Audio-domain QA. Does not claim to detect vocal leakage or musical taste. */
public final class AudioQualityReview {
    private AudioQualityReview(){}
    public record Report(double rms,double peak,double clippedFraction,
                         double silenceSeconds,double longestSilentSpan,String envelopeFingerprint){}
    public static Report analyze(Path wav)throws IOException{
        double[] sums=new double[3600];int[] counts=new int[3600];
        long frames=0,clipped=0,silentFrames=0;double sum=0,peak=0;
        double rate=48000;
        try(AudioInputStream stream=AudioSystem.getAudioInputStream(wav.toFile())){
            AudioFormat format=stream.getFormat();
            if(format.getEncoding()!=AudioFormat.Encoding.PCM_SIGNED||
                format.getChannels()!=2||format.getSampleSizeInBits()!=16||
                format.isBigEndian()||format.getFrameSize()!=4||
                Math.abs(format.getSampleRate()-48000)>1)
                throw new IOException("AUDIO_QUALITY_REQUIRES_PCM_48K_STEREO");
            byte[] bytes=new byte[32768];int read;
            while((read=stream.read(bytes))>0){
                if(read%4!=0)throw new IOException("AUDIO_QUALITY_PCM_TRUNCATED");
                for(int at=0;at<read;at+=4){
                    int l=(short)((bytes[at]&255)|(bytes[at+1]<<8));
                    int r=(short)((bytes[at+2]&255)|(bytes[at+3]<<8));
                    double x=l/32768.0,y=r/32768.0;
                    double magnitude=(x*x+y*y)*.5;
                    sum+=magnitude;
                    peak=Math.max(peak,Math.max(Math.abs(x),Math.abs(y)));
                    if(Math.abs(l)>32400||Math.abs(r)>32400)clipped++;
                    if(Math.abs(x)+Math.abs(y)<.00045)silentFrames++;
                    int slot=(int)(frames/(rate*.25));
                    if(slot<sums.length){sums[slot]+=magnitude;counts[slot]++;}
                    frames++;
                }
            }
        }catch(UnsupportedAudioFileException e){throw new IOException("AUDIO_QA_INVALID_FILE",e);}
        if(frames==0)throw new IOException("AUDIO_QA_EMPTY");
        int used=0;double longest=0,current=0;
        StringBuilder envelope=new StringBuilder();
        for(int i=0;i<counts.length&&counts[i]>0;i++){
            double rms=Math.sqrt(sums[i]/counts[i]);
            boolean empty=rms<.001;
            current=empty?current+.25:0;
            longest=Math.max(longest,current);
            int level=Math.max(0,Math.min(15,(int)Math.round(rms*60)));
            envelope.append(Integer.toHexString(level));
            used++;
        }
        String fingerprint;
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            fingerprint=HexFormat.of().formatHex(hash.digest(
                envelope.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new Report(Math.sqrt(sum/frames),peak,(double)clipped/frames,
            silentFrames/rate,longest,fingerprint);
    }
    public static Report requireAcceptable(Path wav,int expectedSeconds)throws IOException{
        Report r=analyze(wav);
        if(r.rms()<.007||r.peak()>.995||r.clippedFraction()>.01||
            r.longestSilentSpan()>Math.max(5,expectedSeconds*.60))
            throw new IOException("AUDIO_QUALITY_FAILED: rms="+r.rms()+
                " peak="+r.peak()+" clipped="+r.clippedFraction()+
                " longSilence="+r.longestSilentSpan());
        return r;
    }
}
