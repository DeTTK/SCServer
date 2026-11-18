package szserver.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable resident state for geometry/editor/network workers. No read falls back to old terrain. */
public final class ResidentMapReads<C extends Terrain.EditChunk> {
    public record Chunk<C>(long revision,C data){public Chunk{if(revision<0)throw new IllegalArgumentException("resident_read_revision");Objects.requireNonNull(data);}}
    private final Thread owner=Thread.currentThread();
    private final ConcurrentHashMap<ResidentChunks.Key,Chunk<C>> published=new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<ResidentChunks.Key> requests;
    private final Semaphore requestSlots;private final int capacity;
    private final Set<ResidentChunks.Key> pending=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ResidentChunks.Key,Long> retryAt=new ConcurrentHashMap<>();
    private final AtomicLong misses=new AtomicLong(),backpressure=new AtomicLong(),failures=new AtomicLong();
    private volatile long epoch;private volatile boolean stopped;
    public ResidentMapReads(int capacity){if(capacity<1)throw new IllegalArgumentException("resident_read_request_capacity");this.capacity=capacity;requests=new ArrayBlockingQueue<>(capacity);requestSlots=new Semaphore(capacity);}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("resident_reads_wrong_owner");}
    /** Publish all chunks of a committed operation in one revision barrier. */
    public void publish(Map<ResidentChunks.Key,Chunk<C>> chunks){owner();if(stopped)throw new IllegalStateException("resident_reads_stopping");for(var row:chunks.entrySet()){Objects.requireNonNull(row.getKey());Objects.requireNonNull(row.getValue());var before=published.get(row.getKey());if(before!=null&&before.revision()>row.getValue().revision())throw new IllegalArgumentException("resident_read_revision_regressed");}epoch++;try{published.putAll(chunks);}finally{epoch++;}}
    public void unload(ResidentChunks.Key key){owner();epoch++;try{published.remove(key);retryAt.remove(key);}finally{epoch++;}}
    public Chunk<C> peek(ResidentChunks.Key key){return published.get(key);}
    /** Cold reads enqueue a bounded/coalesced owner request and return explicit unavailability. */
    public Chunk<C> require(ResidentChunks.Key key){
        if(Math.abs((long)key.x())>MapBounds.CHUNK||Math.abs((long)key.z())>MapBounds.CHUNK)throw new IllegalArgumentException("invalid_chunk");
        Chunk<C> found=published.get(key);if(found!=null)return found;
        misses.incrementAndGet();if(stopped)throw new IllegalArgumentException("resident_map_stopped");
        long now=System.nanoTime();if(now>=retryAt.getOrDefault(key,Long.MIN_VALUE)&&pending.add(key)){
            if(!requestSlots.tryAcquire()){pending.remove(key);backpressure.incrementAndGet();}
            else if(!requests.offer(key)){pending.remove(key);requestSlots.release();backpressure.incrementAndGet();}
            else if(stopped){requests.remove(key);if(pending.remove(key))requestSlots.release();}
        }
        throw new IllegalArgumentException("terrain_not_ready_retry");
    }
    public ResidentChunks.Key poll(){owner();return requests.poll();}
    public void complete(ResidentChunks.Key key,Throwable error){owner();if(pending.remove(key))requestSlots.release();if(error==null)retryAt.remove(key);else{boolean absent=ResidentChunks.missingBase(error);if(!absent)failures.incrementAndGet();if(!stopped&&(retryAt.size()<capacity||retryAt.containsKey(key)))retryAt.put(key,absent?Long.MAX_VALUE:System.nanoTime()+TimeUnit.SECONDS.toNanos(1));}}
    public Capture capture(){if(stopped)throw new IllegalArgumentException("resident_map_stopped");return new Capture();}
    /** Worker-local revisions: unrelated edits do not invalidate a long editor/collision scan. */
    public final class Capture {
        // One worker adds immutable chunk references; consumers only validate revisions.
        // Validation also runs after a prepared scene/path crosses to the world owner.
        private final Map<ResidentChunks.Key,Chunk<C>> touched=new ConcurrentHashMap<>();
        private final Thread reader=Thread.currentThread();
        public C chunk(int x,int z){reader();ResidentChunks.Key key=new ResidentChunks.Key(x,z);var found=touched.get(key);if(found==null){long before=epoch;if((before&1)!=0)throw stale();found=require(key);if(before!=epoch)throw stale();touched.put(key,found);}return found.data();}
        private void reader(){if(reader!=Thread.currentThread())throw new IllegalStateException("resident_capture_wrong_reader");}
        public void checkCurrent(){long before=epoch;if((before&1)!=0||stopped)throw stale();for(var row:touched.entrySet())if(published.get(row.getKey())!=row.getValue())throw stale();if(before!=epoch)throw stale();}
        public Map<ResidentChunks.Key,Long> revisions(){reader();checkCurrent();Map<ResidentChunks.Key,Long> result=new LinkedHashMap<>();touched.forEach((key,value)->result.put(key,value.revision()));return Map.copyOf(result);}
        private IllegalArgumentException stale(){return new IllegalArgumentException("resident_map_read_stale");}
    }
    public void stop(){owner();stopped=true;requests.clear();for(var key:pending)if(pending.remove(key))requestSlots.release();retryAt.clear();}
    public Map<String,Object> metrics(){return Map.of("published",published.size(),"requested",pending.size(),"queued",requests.size(),"retrying",retryAt.size(),"misses",misses.get(),"backpressure",backpressure.get(),"failures",failures.get(),"stopping",stopped);}
}
