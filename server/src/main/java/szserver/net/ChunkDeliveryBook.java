package szserver.net;

import java.util.*;

/** Reliable transport owns retries; suppress duplicate application rebuilds for the legacy30 seconds. */
public final class ChunkDeliveryBook {
    public static final long RETRY_NANOS=30_000_000_000L;
    private record Delivery(UUID revision,long overlay,long at){}
    private final Map<Long,Delivery> deliveries=new HashMap<>();
    public boolean current(long key,long overlay,long now){Delivery previous=deliveries.get(key);return previous!=null&&previous.overlay==overlay&&now-previous.at<RETRY_NANOS;}
    public boolean claim(long key,UUID revision,long overlay,long now){Delivery previous=deliveries.get(key);if(previous!=null&&previous.revision.equals(revision)&&now-previous.at<RETRY_NANOS)return false;deliveries.put(key,new Delivery(revision,overlay,now));return true;}
    public void abandon(long key,UUID revision){Delivery previous=deliveries.get(key);if(previous!=null&&previous.revision.equals(revision))deliveries.remove(key);}
    public void record(long key,UUID revision,long overlay,long now){deliveries.put(key,new Delivery(revision,overlay,now));}
    public void remove(long key){deliveries.remove(key);}
    public void clear(){deliveries.clear();}
    public int size(){return deliveries.size();}
}
