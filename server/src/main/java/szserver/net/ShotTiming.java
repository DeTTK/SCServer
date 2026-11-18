package szserver.net;

/** Legacy fire pacing: advancing client ticks can repay at most 250ms of receiver delay. */
public final class ShotTiming {
    private ShotTiming(){}
    public static boolean allowed(long now,long next,Integer previousTick,int tick,double rpm){
        double elapsed=previousTick==null?0:((long)tick-previousTick)/20.;
        long tolerance=elapsed>0&&elapsed+.05>=60/rpm?250_000_000L:15_000_000L;
        return now+tolerance>=next;
    }
    public static long next(long now,long previous,Integer previousTick,double rpm){return Math.max(previousTick==null?now:now-250_000_000L,previous)+(long)(60_000_000_000L/rpm);}
}
