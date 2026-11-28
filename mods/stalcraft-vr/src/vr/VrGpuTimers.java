package vr;

import java.util.Arrays;
import java.util.Locale;
import static org.lwjgl.opengl.GL33.*;

/** Timestamp pairs never overlap native elapsed queries; unavailable results are never read. */
final class VrGpuTimers implements AutoCloseable {
    interface Queries {int create();void timestamp(int id);boolean available(int id);long result(int id);void delete(int id);}
    static final Queries OPENGL=new Queries(){public int create(){return glGenQueries();}public void timestamp(int id){glQueryCounter(id,GL_TIMESTAMP);}public boolean available(int id){return glGetQueryObjecti(id,GL_QUERY_RESULT_AVAILABLE)!=0;}public long result(int id){return glGetQueryObjecti64(id,GL_QUERY_RESULT);}public void delete(int id){glDeleteQueries(id);}};
    private static final int SLOTS=8,SPANS=16,HISTORY=256;
    private static final int[] PHASES={VrFrameStats.RIG,VrFrameStats.SCOPE,VrFrameStats.WORLD,VrFrameStats.COPY,VrFrameStats.OVERLAY};
    private static final String[] NAMES={"rig","scope","world","copy","overlay"};
    private final VrFrameStats stats;
    private final Queries queries;
    private final Slot[] slots=new Slot[SLOTS];
    private final long[][] history=new long[PHASES.length+1][HISTORY];
    private final long[] scratch=new long[HISTORY];
    private Slot active;private int size,cursor;
    long skipped,resolved;
    private static final class Slot {
        final int[] queries=new int[SPANS*2],phases=new int[SPANS];
        final boolean[] ended=new boolean[SPANS];
        final long[] sum=new long[PHASES.length];
        int count;boolean pending,keep;
    }
    VrGpuTimers(VrFrameStats stats,Queries queries){this.stats=stats;this.queries=queries;for(int i=0;i<SLOTS;i++)slots[i]=new Slot();}
    void begin(){if(!stats.enabled)return;drain();active=null;for(Slot slot:slots)if(!slot.pending){active=slot;slot.count=0;Arrays.fill(slot.ended,false);break;}if(active==null)skipped++;}
    int start(int phase){if(active==null||active.count==SPANS)return 0;int kind=-1;for(int i=0;i<PHASES.length;i++)if(PHASES[i]==phase)kind=i;if(kind<0)return 0;
        int span=active.count++;active.phases[span]=kind;for(int i=span*2;i<span*2+2;i++)if(active.queries[i]==0)active.queries[i]=queries.create();
        queries.timestamp(active.queries[span*2]);return span+1;
    }
    void end(int token){if(active==null||token<=0||token>active.count)return;int index=token-1;queries.timestamp(active.queries[index*2+1]);active.ended[index]=true;}
    void finish(boolean keep){if(active==null)return;active.keep=keep;for(int i=0;i<active.count;i++)if(!active.ended[i]){queries.timestamp(active.queries[i*2+1]);active.keep=false;}active.pending=active.count>0;active=null;}
    void drain(){for(Slot slot:slots){if(!slot.pending||!queries.available(slot.queries[slot.count*2-1]))continue;
        slot.pending=false;if(!slot.keep)continue;Arrays.fill(slot.sum,0);
        for(int i=0;i<slot.count;i++){long start=queries.result(slot.queries[i*2]),end=queries.result(slot.queries[i*2+1]);slot.sum[slot.phases[i]]+=Math.max(0,end-start);}
        long total=0;for(int i=0;i<PHASES.length;i++){history[i][cursor]=slot.sum[i];total+=slot.sum[i];}history[PHASES.length][cursor]=total;cursor=(cursor+1)%HISTORY;size=Math.min(size+1,HISTORY);resolved++;
    }}
    private double p95(int phase){for(int i=0;i<size;i++)scratch[i]=history[phase][i];Arrays.sort(scratch,0,size);return size==0?0:scratch[Math.min(size-1,(int)(size*.95))]*1e-6;}
    String report(){StringBuilder text=new StringBuilder("; GPU timestamp spans (no compositor), samples=").append(size).append(" skipped=").append(skipped);for(int i=0;i<PHASES.length;i++)text.append(String.format(Locale.ROOT," %sMs.p95=%.3f",NAMES[i],p95(i)));return text.toString();}
    int pending(){int count=0;for(Slot slot:slots)if(slot.pending)count++;return count;}
    String summary(){return size==0?"—":String.format(Locale.ROOT,"%.1f",p95(PHASES.length));}
    int allocatedQueries(){int count=0;for(Slot slot:slots)for(int id:slot.queries)if(id!=0)count++;return count;}
    public void close(){active=null;for(Slot slot:slots){for(int i=0;i<slot.queries.length;i++){if(slot.queries[i]!=0)queries.delete(slot.queries[i]);slot.queries[i]=0;}slot.pending=false;slot.count=0;}size=cursor=0;skipped=resolved=0;}
}
