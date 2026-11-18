package szserver.persistence;

/** Immutable native day clock; legacy command ticks remain a 24000-unit view of the same day. */
public record EnvironmentClock(long ticks,long wallMillis,double fraction,double speed) {
    public static final long NATIVE_DAY_TICKS=160000;
    public static final double NATIVE_DAY_OFFSET=46666.66666666667;
    public static final double SOLAR_SPEED=10.8; // 20 native ticks/s, 8000 wall seconds per native day.
    public EnvironmentClock {
        if(!Double.isFinite(fraction)||fraction<0||fraction>=1||!Double.isFinite(speed)||speed<0||speed>10000)
            throw new IllegalArgumentException("invalid_environment_clock");
    }
    public EnvironmentClock at(long now) {
        if(now<wallMillis)return this;
        double elapsed=((double)now-wallMillis)*speed*.003+fraction;
        if(elapsed>=Long.MAX_VALUE)return new EnvironmentClock(Long.MAX_VALUE,now,0,speed);
        long whole=(long)elapsed,time;
        try{time=Math.addExact(ticks,whole);}catch(ArithmeticException overflow){return new EnvironmentClock(Long.MAX_VALUE,now,0,speed);}
        return new EnvironmentClock(time,now,elapsed-whole,speed);
    }
    public long epochMillis(long now) {
        EnvironmentClock current=at(now);
        long midnight=now-Math.floorMod(now,86400000L);
        double phase=(Math.floorMod(current.ticks,24000L)+6000+current.fraction)%24000;
        return Math.addExact(midnight,(long)(phase*3600));
    }
    public long nativeTicks(long now) {
        EnvironmentClock current=at(now);
        double value=(current.ticks+current.fraction)*NATIVE_DAY_TICKS/24000d-NATIVE_DAY_TICKS/24d;
        if(value>=Long.MAX_VALUE)return Long.MAX_VALUE;if(value<=Long.MIN_VALUE)return Long.MIN_VALUE;
        return (long)Math.floor(value);
    }
    public EnvironmentClock changeSpeed(long now,double value) {
        EnvironmentClock current=at(now);return new EnvironmentClock(current.ticks,now,current.fraction,value);
    }
}
