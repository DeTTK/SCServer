package szserver.net;

import java.util.*;

/** Owner-thread FIFO with bounded retained memory, duplicate suppression and one durable batch per session. */
public final class InventoryTransferQueue<O,T> {
    public enum Admission { ACCEPTED, DUPLICATE, FULL }
    public record Entry<T>(T value,Object key,int bytes,long at){}
    private static final class Lane<T>{final ArrayDeque<Entry<T>> waiting=new ArrayDeque<>();final Set<Object> keys=new HashSet<>();List<Entry<T>> active=List.of();int count,bytes;}
    private final Map<O,Lane<T>> lanes=new LinkedHashMap<>();
    private final int ownerLimit,ownerBytes,globalLimit,globalBytes;
    private int count,bytes;private long admitted,duplicates,rejected,batches;
    public InventoryTransferQueue(int ownerLimit,int ownerBytes,int globalLimit,int globalBytes){if(ownerLimit<1||ownerBytes<1||globalLimit<ownerLimit||globalBytes<ownerBytes)throw new IllegalArgumentException("inventory_transfer_limits");this.ownerLimit=ownerLimit;this.ownerBytes=ownerBytes;this.globalLimit=globalLimit;this.globalBytes=globalBytes;}
    public Admission offer(O owner,T value,Object key,int cost,long at){
        Objects.requireNonNull(owner);Objects.requireNonNull(value);if(cost<1)throw new IllegalArgumentException("inventory_transfer_bytes");Lane<T> lane=lanes.get(owner);
        if(key!=null&&lane!=null&&lane.keys.contains(key)){duplicates++;return Admission.DUPLICATE;}
        if(count>=globalLimit||cost>globalBytes-bytes||lane!=null&&(lane.count>=ownerLimit||cost>ownerBytes-lane.bytes)||cost>ownerBytes){rejected++;return Admission.FULL;}
        if(lane==null){lane=new Lane<>();lanes.put(owner,lane);}Entry<T> entry=new Entry<>(value,key,cost,at);lane.waiting.addLast(entry);if(key!=null)lane.keys.add(key);lane.count++;lane.bytes+=cost;count++;bytes+=cost;admitted++;return Admission.ACCEPTED;
    }
    public List<Entry<T>> start(O owner,int limit){
        if(limit<1)throw new IllegalArgumentException("inventory_transfer_batch");Lane<T> lane=lanes.get(owner);if(lane==null||!lane.active.isEmpty()||lane.waiting.isEmpty())return List.of();
        List<Entry<T>> batch=new ArrayList<>();while(batch.size()<limit&&!lane.waiting.isEmpty())batch.add(lane.waiting.removeFirst());lane.active=List.copyOf(batch);batches++;return lane.active;
    }
    /** A callback for an abandoned/disconnected lane cannot release another batch's reservation. */
    public boolean complete(O owner,List<Entry<T>> batch){Lane<T> lane=lanes.get(owner);if(lane==null||lane.active!=batch)return false;release(lane,batch);lane.active=List.of();if(lane.count==0)lanes.remove(owner);return true;}
    public List<Entry<T>> cancelWaiting(O owner){Lane<T> lane=lanes.get(owner);if(lane==null)return List.of();List<Entry<T>> removed=List.copyOf(lane.waiting);lane.waiting.clear();release(lane,removed);if(lane.count==0)lanes.remove(owner);return removed;}
    public void discard(O owner){Lane<T> lane=lanes.remove(owner);if(lane!=null){count-=lane.count;bytes-=lane.bytes;}}
    private void release(Lane<T> lane,List<Entry<T>> entries){for(Entry<T> entry:entries){lane.count--;lane.bytes-=entry.bytes;count--;bytes-=entry.bytes;if(entry.key!=null)lane.keys.remove(entry.key);}}
    public Map<String,Object> metrics(){return Map.of("retained",count,"retainedBytes",bytes,"owners",lanes.size(),"admitted",admitted,"duplicates",duplicates,"rejected",rejected,"batches",batches,"ownerLimit",ownerLimit,"globalLimit",globalLimit);}
}
