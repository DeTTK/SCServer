package replay;

import java.io.*;
import java.time.Duration;

/** Source 1651490162 stamped property, bound by 1895495626.interface. */
final class SourceExtractorState {
    enum Phase {INITIAL,CONNECTED,LOADING,EXTRACTING,FINISHED}
    record State(Phase phase,long changedEpochMillis,double changedSeconds) {
        float progress(Duration duration,double now){
            double seconds=duration.getSeconds();
            return (float)Math.max(0.0,Math.min(1.0,1.0-(changedSeconds+seconds-now)/seconds));
        }
    }
    static State read(DataInput input,double elapsed,long wallMillis)throws IOException {
        // The stamp precedes even the null flag. It is the source world clock,
        // not packet arrival time and not a plain enum property's payload.
        long changed=input.readLong();double at=elapsed-(double)(wallMillis-changed)/1000.0;
        if(input.readBoolean())return new State(null,changed,at);
        int ordinal=input.readShort();
        if(ordinal<0||ordinal>=Phase.values().length)throw new IOException("Invalid source extractor state ordinal "+ordinal);
        return new State(Phase.values()[ordinal],changed,at);
    }
}
