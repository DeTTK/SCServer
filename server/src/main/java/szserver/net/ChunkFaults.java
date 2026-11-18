package szserver.net;

import java.util.*;

/** Exact compressed fault set; bounded rotating sweeps retry every point without a per-cell deadline map. */
public final class ChunkFaults {
    public static final long PAUSE_NANOS=1_000_000_000L;
    private final ChunkCoverage coverage=new ChunkCoverage();private long remaining,nextAt;
    public void failed(long key,long now){boolean empty=coverage.size()==0;coverage.add(key);if(empty){remaining=0;nextAt=now+PAUSE_NANOS;}}
    public void resolved(long key){long before=coverage.size();coverage.remove(key);remaining=Math.max(0,remaining-before+coverage.size());}
    public void prune(ChunkWindow window){long before=coverage.size();coverage.trim(window,ChunkWindow.WORK_BUDGET);remaining=Math.max(0,remaining-before+coverage.size());}
    public List<Long> ready(long now,int maximum){
        if(maximum<0||maximum>32)throw new IllegalArgumentException("chunk_fault_retry_budget");
        if(maximum==0||coverage.size()==0||now<nextAt)return List.of();if(remaining==0)remaining=coverage.size();
        List<Long> keys=new ArrayList<>();for(int n=0;n<maximum&&remaining>0;n++){keys.add(coverage.scan());remaining--;}
        if(remaining==0)nextAt=now+PAUSE_NANOS;return List.copyOf(keys);
    }
    public long size(){return coverage.size();}
    public int rows(){return coverage.rows();}
    public void clear(){coverage.clear();remaining=nextAt=0;}
}
