package vr;

import java.util.Arrays;
import java.util.Locale;

/** Bounded application timings. XR submissions are not compositor/display FPS. */
final class VrFrameStats implements AutoCloseable {
    static final int TOTAL=0,OUTSIDE=1,WAIT=2,BEGIN=3,TRACKING=4,RIG=5,SCOPE=6,WORLD=7,COPY=8,OVERLAY=9,SWAP=10,SUBMIT=11,COUNT=12;
    private static final String[] NAMES={"cycle","nativeOutside","waitFrame","beginFrame","tracking","rig","scope","world","copy","overlay","swapchain","endFrame"};
    private static final int CAPACITY=256;
    private final long[][] samples=new long[COUNT][CAPACITY];
    private final long[] current=new long[COUNT],scratch=new long[CAPACITY];
    private final long[] stamps=new long[CAPACITY];
    final VrGpuTimers gpu;
    boolean enabled;
    private boolean measuring;
    private long started,previousEnd,period,nextReport;
    private int size,cursor;
    long submissions,idle,failures,budgetOver;
    String summary="VR: ожидание кадров",phases="",report="VR timings: no samples";
    VrFrameStats(){this(VrGpuTimers.OPENGL);}
    VrFrameStats(VrGpuTimers.Queries queries){gpu=new VrGpuTimers(this,queries);}
    void enabled(boolean value){if(enabled==value)return;gpu.close();enabled=value;reset();}
    private void reset(){measuring=false;started=previousEnd=period=nextReport=0;size=cursor=0;submissions=idle=failures=budgetOver=0;summary="VR: ожидание кадров";phases="";report="VR timings: no samples";}
    long mark(){return enabled?System.nanoTime():0;}
    void begin(){if(enabled)begin(System.nanoTime());}
    void begin(long now){if(!enabled)return;Arrays.fill(current,0);started=now;current[OUTSIDE]=previousEnd==0?0:Math.max(0,now-previousEnd);measuring=true;}
    void add(int phase,long nanos){if(enabled&&measuring)current[phase]+=Math.max(0,nanos);}
    void cpu(int phase,long start){if(start!=0)add(phase,System.nanoTime()-start);}
    void tracking(long start){if(start!=0)add(TRACKING,System.nanoTime()-start-current[RIG]);}
    int gpuStart(int phase){return enabled&&measuring?gpu.start(phase):0;}
    void end(int phase,long start,int token){cpu(phase,start);if(token!=0)gpu.end(token);}
    void finish(int kind,long predictedPeriod){if(enabled)finish(kind,predictedPeriod,System.nanoTime());}
    void finish(int kind,long predictedPeriod,long now){
        if(!enabled||!measuring)return;current[TOTAL]=Math.max(0,now-started);measuring=false;previousEnd=now;period=predictedPeriod;
        if(kind<0){failures++;return;}if(kind==0){idle++;return;}
        for(int i=0;i<COUNT;i++)samples[i][cursor]=current[i];stamps[cursor]=started;cursor=(cursor+1)%CAPACITY;size=Math.min(CAPACITY,size+1);
        submissions++;
        if(period>0&&current[TOTAL]-current[WAIT]>period)budgetOver++;
    }
    private double percentile(int phase,double fraction){for(int i=0;i<size;i++)scratch[i]=samples[phase][i];Arrays.sort(scratch,0,size);return size==0?0:scratch[Math.min(size-1,(int)(size*fraction))]*1e-6;}
    void refresh(long now){if(!enabled||now<nextReport)return;nextReport=now+1_000_000_000L;if(size==0){report="VR timings: no submitted frames; idle="+idle+" failed="+failures+"; not display FPS";return;}
        double rate=cadenceRate();
        summary=String.format(Locale.ROOT,"XR %.0f Гц · цикл p95 %.1f · GPU %s · бюджет %.1f мс",rate,percentile(TOTAL,.95),gpu.summary(),period*1e-6);
        phases=String.format(Locale.ROOT,"p95: мир %.1f · оптика %.1f · подг. рук %.1f · ожидание %.1f мс",percentile(WORLD,.95),percentile(SCOPE,.95),percentile(RIG,.95),percentile(WAIT,.95));
        StringBuilder out=new StringBuilder("VR timings: application submissions, not display FPS; last ").append(size).append(" submitted frames; submitted=").append(submissions).append(" idle=").append(idle).append(" failed=").append(failures).append(" workOverPeriod=").append(budgetOver).append("; ").append(summary);
        for(int i=0;i<COUNT;i++)out.append(String.format(Locale.ROOT,"; %sMs.p50=%.3f p95=%.3f",NAMES[i],percentile(i,.5),percentile(i,.95)));
        out.append(gpu.report());report=out.toString();
    }
    int sampleCount(){return size;}
    double cadenceRate(){if(size<2)return 0;long first=stamps[(cursor-size+CAPACITY)%CAPACITY],last=stamps[(cursor-1+CAPACITY)%CAPACITY];return last>first?(size-1)*1e9/(last-first):0;}
    double p95(int phase){return percentile(phase,.95);}
    public void close(){gpu.close();enabled=false;reset();}
}
