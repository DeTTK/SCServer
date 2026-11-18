package szserver.client;

import java.util.HashMap;

/** Defined separately in each isolated native loader; scopes never escape the codec operation. */
public final class SceneCodecClock {
    private static final ThreadLocal<Object[]> ACTIVE=new ThreadLocal<>();
    private static final int MAX_TIMESTAMPS=512;
    private SceneCodecClock(){}
    public static void enter(Object clock,long millis,double seconds){
        if(clock==null||!Double.isFinite(seconds))throw new IllegalArgumentException("scene_clock_snapshot");
        ACTIVE.set(new Object[]{clock,millis,seconds,new HashMap<Long,Long>(),ACTIVE.get()});
    }
    public static void exit(){Object[] frame=frame();Object[] previous=(Object[])frame[4];if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    private static Object[] frame(){Object[] frame=ACTIVE.get();if(frame==null)throw new IllegalStateException("scene_clock_scope_missing");return frame;}
    public static Object clock(){return frame()[0];}
    public static long millis(){return (long)frame()[1];}
    /** Keep the game's seconds conversion, retaining its exact original wire epoch for reserialization. */
    @SuppressWarnings("unchecked") public static double decode(long epoch){
        Object[] frame=frame();double seconds=(double)frame[2]-((long)frame[1]-epoch)/1000d;long key=Double.doubleToRawLongBits(seconds);
        var original=(HashMap<Long,Long>)frame[3];Long existing=original.get(key);
        if(existing!=null&&existing.longValue()!=epoch)throw new IllegalArgumentException("scene_clock_precision_collision");
        if(existing==null&&original.size()>=MAX_TIMESTAMPS)throw new IllegalArgumentException("scene_clock_timestamp_limit");
        original.put(key,epoch);return seconds;
    }
    /** New events use the reviewed native conversion; unchanged read events retain every millisecond. */
    @SuppressWarnings("unchecked") public static long encode(double seconds){
        Object[] frame=frame();if(!Double.isFinite(seconds))throw new IllegalArgumentException("scene_clock_nonfinite");
        Long original=((HashMap<Long,Long>)frame[3]).get(Double.doubleToRawLongBits(seconds));
        return original!=null?original:(long)frame[1]-(long)(1000*((double)frame[2]-seconds));
    }
}
