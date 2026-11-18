package replay;

import java.io.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Weather additions from the audited 08.10 protocol; native state remains native. */
final class SourceWeather {
    enum Kind { EJECTION, SNOWSTORM, ELECTRIC_STORM, WORLD_TIMELINE }
    interface LegacyReader { Object read(DataInputStream input)throws Exception; }
    record Electric(Integer phase,Instant started,Instant phaseStarted,Instant phaseEnd,Long seed,boolean test) {
        boolean active(){return phase!=null;}
        // 1612976154.try(): the clock is state.started, not phaseStarted or phaseEnd.
        float progress(Instant now,long phaseDurationMillis){
            if(!active())return 0;
            return Math.max(0f,Math.min(1f,(float)Duration.between(started,now).toMillis()/(float)phaseDurationMillis));
        }
        float weight(Instant now,long phaseDurationMillis){
            if(!active())return 0;
            if(phase==1)return 1;
            float progress=progress(now,phaseDurationMillis);return phase==0?progress:1-progress;
        }
    }
    record WorldTimeline(String resource,Instant started,long durationMillis,UUID instance,float speed,int revision) {
        boolean active(){return resource!=null&&started!=null;}
        // 1154742350.while(): long -> float -> multiply -> long is observable.
        long elapsedMillis(Instant now){return started==null?0:(long)((float)Duration.between(started,now).toMillis()*speed);}
        long positionMillis(Instant now){
            if(started==null)return 0;
            if(durationMillis<0)throw new IllegalArgumentException("Invalid world timeline duration range");
            return Math.max(0,Math.min(durationMillis,elapsedMillis(now)));
        }
        boolean finished(Instant now){return started==null||elapsedMillis(now)>=durationMillis;}
    }
    record Update(Kind kind,Object current,Object previous) {
        boolean unsupportedActive(){return active(current)||active(previous);}
        static boolean active(Object state){return state instanceof Electric e&&e.active()||state instanceof WorldTimeline t&&t.active();}
    }
    record Frame(List<Update> updates) { Frame { updates=List.copyOf(updates); } }
    static Instant instant(DataInput in)throws IOException {
        long seconds=in.readLong();int nanos=in.readInt();if(nanos<0||nanos>999_999_999)throw new IOException("Invalid source weather instant");
        try{return Instant.ofEpochSecond(seconds,nanos);}catch(java.time.DateTimeException e){throw new IOException("Invalid source weather instant",e);}
    }
    static Instant nullableInstant(DataInput in)throws IOException {return in.readBoolean()?null:instant(in);}
    static Object state(DataInputStream in,Kind kind,LegacyReader legacy)throws Exception {
        in.mark(2);int id=in.readUnsignedShort();
        if(kind==Kind.EJECTION||kind==Kind.SNOWSTORM){in.reset();return legacy.read(in);}
        if(kind==Kind.ELECTRIC_STORM){
            if(id!=101)throw new IOException("Unexpected electric storm state ID "+id);
            Integer phase=in.readBoolean()?null:in.readInt();if(phase!=null&&(phase<0||phase>2))throw new IOException("Invalid electric storm phase "+phase);
            return new Electric(phase,instant(in),nullableInstant(in),nullableInstant(in),in.readBoolean()?null:in.readLong(),in.readBoolean());
        }
        if(id!=69)throw new IOException("Unexpected world timeline state ID "+id);
        String resource=in.readBoolean()?null:in.readUTF();Instant started=nullableInstant(in);long duration=in.readLong();
        UUID instance=in.readBoolean()?null:new UUID(in.readLong(),in.readLong());float speed=in.readFloat();int revision=in.readInt();
        if(!Float.isFinite(speed))throw new IOException("Invalid world timeline speed");return new WorldTimeline(resource,started,duration,instance,speed,revision);
    }
    static Frame read(DataInputStream in,LegacyReader legacy)throws Exception {
        int count=in.readInt();if(count<0||count>65535)throw new IOException("Invalid source weather count");List<Update> updates=new ArrayList<>(count);
        for(int n=0;n<count;n++){
            int ordinal=in.readInt();if(ordinal<0||ordinal>=Kind.values().length)throw new IOException("Invalid source weather kind "+ordinal);
            Kind kind=Kind.values()[ordinal];Object current=state(in,kind,legacy),previous=in.readBoolean()?null:state(in,kind,legacy);updates.add(new Update(kind,current,previous));
        }return new Frame(updates);
    }
}
