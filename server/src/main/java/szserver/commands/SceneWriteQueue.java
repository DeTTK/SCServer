package szserver.commands;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Bounded asynchronous FIFO per connection. Accepted writes survive peer disconnect. No IO under the guard. */
public final class SceneWriteQueue<T> {
    // Eight concurrent native selections may each contain 128 Delete-key messages.
    // Keep the per-writer and byte ceilings; one editor must not exhaust the realm's count budget.
    public static final int MAX_PENDING=1024,MAX_WRITER_PENDING=128;
    private record Write<T>(int bytes,Object group,Object payload,Function<List<Object>,CompletableFuture<T>> action,CompletableFuture<T> result){}
    private static final class Lane<T>{final ArrayDeque<Write<T>> writes=new ArrayDeque<>();int bytes;}
    private final Map<UUID,Lane<T>> lanes=new HashMap<>();private int count,bytes;private boolean closed;
    public CompletableFuture<T> submit(UUID writer,int size,Supplier<CompletableFuture<T>> action){
        return enqueue(writer,size,null,null,ignored->action.get());
    }
    /** Wait for preceding writes from this connection without holding an owner thread. */
    public CompletableFuture<Void> barrier(UUID writer){return submit(writer,0,()->CompletableFuture.completedFuture(null)).thenApply(ignored->null);}
    /** Only adjacent writes in the same logical selection may share a transaction.
     * The active transaction is never modified; config saves and other selections are FIFO barriers. */
    @SuppressWarnings("unchecked")
    public <P> CompletableFuture<T> submitBatch(UUID writer,int size,Object group,P payload,Function<List<P>,CompletableFuture<T>> action){
        Objects.requireNonNull(group);return enqueue(writer,size,group,payload,items->action.apply((List<P>)(List<?>)items));
    }
    private CompletableFuture<T> enqueue(UUID writer,int size,Object group,Object payload,Function<List<Object>,CompletableFuture<T>> action){
        CompletableFuture<T> result=new CompletableFuture<>();Write<T> write=new Write<>(size,group,payload,action,result);boolean start;
        synchronized(this){Lane<T> lane=lanes.get(writer);if(closed||size<0||size>65500||count>=MAX_PENDING||bytes+size>8*1024*1024||lane!=null&&(lane.writes.size()>=MAX_WRITER_PENDING||lane.bytes+size>1024*1024))return CompletableFuture.failedFuture(new RejectedExecutionException("scene_write_queue_full"));if(lane==null){lane=new Lane<>();lanes.put(writer,lane);}start=lane.writes.isEmpty();lane.writes.add(write);lane.bytes+=size;count++;bytes+=size;}
        if(start)start(writer);return result;
    }
    private void start(UUID writer){
        List<Write<T>> batch=new ArrayList<>();List<Object> payloads=new ArrayList<>();Write<T> write;
        synchronized(this){Lane<T> lane=lanes.get(writer);write=lane.writes.getFirst();for(var item:lane.writes){if(!batch.isEmpty()&&(write.group()==null||!Objects.equals(write.group(),item.group())))break;batch.add(item);payloads.add(item.payload());if(batch.size()==MAX_WRITER_PENDING)break;}}
        CompletableFuture<T> execution;try{execution=Objects.requireNonNull(write.action().apply(Collections.unmodifiableList(payloads)));}catch(Throwable error){execution=CompletableFuture.failedFuture(error);}
        execution.whenComplete((value,error)->{
            boolean more;synchronized(this){Lane<T> lane=lanes.get(writer);for(var item:batch){if(lane.writes.removeFirst()!=item)throw new IllegalStateException("scene_write_order");lane.bytes-=item.bytes();bytes-=item.bytes();count--;}more=!lane.writes.isEmpty();if(!more)lanes.remove(writer);notifyAll();}
            for(var item:batch)if(error==null)item.result().complete(value);else item.result().completeExceptionally(error);if(more)start(writer);
        });
    }
    public synchronized Map<String,Object> metrics(){return Map.of("pending",count,"bytes",bytes,"writers",lanes.size(),"countLimit",MAX_PENDING,"writerCountLimit",MAX_WRITER_PENDING,"byteLimit",8*1024*1024);}
    /** Stop admission, then wait for queued writes before their worker is shut down. */
    public synchronized boolean drain(long timeoutMs)throws InterruptedException{closed=true;long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);while(count>0){long remaining=deadline-System.nanoTime();if(remaining<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,remaining);}return true;}
}
