package chordflow;

import javax.sound.midi.*;

/**
 * Instrument-aware MIDI performance controls. Does not fabricate notes or
 * alter canonical note timing; CC events are real synthesizer instructions.
 */
public final class InstrumentExpression {
    private InstrumentExpression(){}
    private static void cc(Track track,int channel,int controller,int value,long tick)
        throws InvalidMidiDataException{
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,
            channel,controller,Math.max(0,Math.min(127,value))),tick));
    }
    public static void initialize(Track track,int channel,String role,int patch)
        throws InvalidMidiDataException {
        int pan=switch(role){
            case "bass","lead"->64;
            case "pad"->78;
            case "arp"->47;
            case "guitar"->72;
            default->64;
        };
        cc(track,channel,10,pan,0);
        cc(track,channel,11,110,0); // expression, not a volume normalization trick
        cc(track,channel,91,role.equals("pad")?32:role.equals("lead")?17:12,0);
        cc(track,channel,1,0,0);
    }
    public static void phrase(Track track,int channel,String role,int patch,
                              Composition.Note note,long start,long end)
        throws InvalidMidiDataException{
        if(!role.equals("lead")&&!role.equals("bass"))return;
        int expression=Math.max(78,Math.min(118,99+(note.velocity()-85)/3));
        cc(track,channel,11,expression,start);
        // Modulation provides optional sustained expressive vibrato where the
        // SoundFont maps CC1 to vibrato. Keep per-note pitch bends disabled.
        boolean expressive=(patch>=40&&patch<=43)||(patch>=56&&patch<=79);
        if(expressive&&role.equals("lead")&&end-start>380){
            cc(track,channel,1,0,start);
            cc(track,channel,1,17,start+Math.min(280,(end-start)/3));
            cc(track,channel,1,0,end);
        }
    }
}
