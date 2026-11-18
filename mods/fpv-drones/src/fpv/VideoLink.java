package fpv;

/** Monotonic timing independent of frame rate and server acknowledgement delay. */
final class VideoLink {
    static final long SECOND=1_000_000_000L;
    private long started;
    private boolean active, lost;
    void connect(long now){started=now;active=true;lost=false;}
    void lose(long now){if(!lost){started=now;active=true;lost=true;}}
    float noise(long now){
        if(!active)return 0;
        if(lost)return 1;
        double t=Math.max(0,Math.min(1,(now-started)/(double)SECOND));
        return (float)(1-t*t*(3-2*t));
    }
    boolean lost(){return lost;}
    /** Keep the native CCTV pass through acquisition, then fade it for one more second. */
    float cctv(long now){
        if(!active)return 0;
        if(lost)return 1;
        return (float)Math.max(0,Math.min(1,2-(now-started)/(double)SECOND));
    }
    boolean finished(long now){return lost&&now-started>=SECOND;}
    void clear(){active=lost=false;started=0;}
}
