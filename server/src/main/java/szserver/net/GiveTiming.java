package szserver.net;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Network-owner measurements through reply enqueue, without names or item data. */
final class GiveTiming {
    private final ArrayDeque<Map<String,Object>> recentSlow=new ArrayDeque<>();
    private long completed,failed,maxQueue,maxApply,maxReply;

    void record(String kind,long queued,long started,long applied,long replied,boolean ok) {
        long queue=started-queued,apply=applied-started,reply=replied-applied;
        if(ok)completed++;else failed++;
        maxQueue=Math.max(maxQueue,queue);maxApply=Math.max(maxApply,apply);maxReply=Math.max(maxReply,reply);
        if(replied-queued<50_000_000L)return;
        if(recentSlow.size()==8)recentSlow.removeFirst();
        recentSlow.addLast(Map.of("kind",kind,"queueMs",queue/1e6,"applyMs",apply/1e6,"replyMs",reply/1e6,
                "totalMs",(replied-queued)/1e6,"ok",ok,"timeMs",System.currentTimeMillis()));
    }

    Map<String,Object> metrics(){
        return Map.of("completed",completed,"failed",failed,"maxQueueMs",maxQueue/1e6,
                "maxApplyMs",maxApply/1e6,"maxReplyMs",maxReply/1e6,"recentSlow",List.copyOf(recentSlow));
    }
}
