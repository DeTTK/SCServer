package replay;

import java.util.*;
import java.util.concurrent.*;

/** Bounded CPU-only preparation. GPU publication and game state stay on the game thread. */
final class SceneAsyncFrames<T> implements AutoCloseable {
    interface Loader<T>{T load(long key)throws Exception;}
    interface Release<T>{void release(T value)throws Exception;}
    interface Size<T>{long bytes(T value)throws Exception;}
    final class Task {final long key;final Job job;T value;long bytes;Exception failure;boolean done,cancelled;Task(long key){this.key=key;job=new Job(this,null);}}
    final class Job {final Task task;final T retired;Job(Task task,T retired){this.task=task;this.retired=retired;}}
    final Loader<T> loader;final Release<T> release;final int capacity;
    final Size<T> size;final long budget;long residentBytes,peakBytes;
    final LinkedHashMap<Long,Task> tasks=new LinkedHashMap<>(32,.75f,true);
    final LinkedBlockingDeque<Job> queue=new LinkedBlockingDeque<>();final Thread worker;
    Set<Long> workingSet=Set.of();
    volatile Exception cleanupFailure;
    boolean closed;long loaded,misses,evictions;Task foreground;
    SceneAsyncFrames(int capacity,Loader<T> loader,Release<T> release){
        this("Replay effect preparation",capacity,loader,release);
    }
    SceneAsyncFrames(String name,int capacity,Loader<T> loader,Release<T> release){
        this(name,capacity,Long.MAX_VALUE,loader,release,value->0);
    }
    SceneAsyncFrames(String name,int capacity,long budget,Loader<T> loader,Release<T> release,Size<T> size){
        if(capacity<2||budget<1)throw new IllegalArgumentException();this.capacity=capacity;this.loader=loader;this.release=release;this.size=size;this.budget=budget;
        worker=new Thread(this::run,name);worker.setDaemon(true);worker.start();
    }
    synchronized T request(long key,boolean urgent)throws Exception {
        if(closed)throw new IllegalStateException("Closed frame preparation");if(cleanupFailure!=null)throw cleanupFailure;Task task=tasks.get(key);
        if(task==null){task=new Task(key);tasks.put(key,task);if(urgent)foreground=task;if(urgent)queue.addFirst(task.job);else queue.addLast(task.job);trim();}
        else if(urgent){foreground=task;if(!task.done&&queue.remove(task.job))queue.addFirst(task.job);}
        if(task.failure!=null)throw task.failure;if(!task.done){if(urgent)misses++;return null;}return task.value;
    }
    /** Consecutive resources required by one pending publication, not a lookahead.
     * Reserve before enqueueing so a full queue cannot discard the first member. */
    synchronized void reserve(Collection<Long> keys){
        Set<Long> next=Set.copyOf(keys);if(next.size()>=capacity)throw new IllegalArgumentException("Working set must leave a foreground slot");workingSet=next;
    }
    private void trim()throws Exception {
        while(tasks.size()>capacity||residentBytes>budget){var iterator=tasks.values().iterator();Task old=null;while(iterator.hasNext()){Task candidate=iterator.next();if(candidate!=foreground&&!workingSet.contains(candidate.key)){old=candidate;iterator.remove();break;}}
            if(old==null)throw new IllegalStateException("Async cache reservation exceeds capacity");
            evictions++;old.cancelled=true;queue.remove(old.job);if(old.value!=null){residentBytes-=old.bytes;queue.addFirst(new Job(null,old.value));old.value=null;}}
    }
    private void run(){
        while(true){synchronized(this){if(closed&&queue.isEmpty())return;}Job job;try{job=queue.takeFirst();}catch(InterruptedException stopped){continue;}
            if(job.task==null){try{release.release(job.retired);}catch(Exception failure){cleanupFailure=failure;}continue;}
            Task task=job.task;
            synchronized(this){if(task.cancelled||closed)continue;}
            T value=null;long bytes=0;Exception failure=null;try{value=loader.load(task.key);bytes=size.bytes(value);if(bytes<0||bytes>budget)throw new java.io.IOException("Native effect frame exceeds cache budget: "+bytes+" > "+budget);}catch(Exception e){failure=e;if(value!=null){try{release.release(value);}catch(Exception cleanup){e.addSuppressed(cleanup);}value=null;}}
            synchronized(this){if(task.cancelled||closed){if(value!=null)queue.addFirst(new Job(null,value));}else{task.value=value;task.bytes=bytes;residentBytes+=value==null?0:bytes;peakBytes=Math.max(peakBytes,residentBytes);task.failure=failure;task.done=true;loaded++;try{trim();}catch(Exception error){task.failure=error;}}}
        }
    }
    public void close()throws Exception {
        synchronized(this){if(closed)return;closed=true;queue.removeIf(job->job.task!=null);for(Task task:tasks.values())if(task.value!=null){queue.addFirst(new Job(null,task.value));task.value=null;}tasks.clear();residentBytes=0;foreground=null;workingSet=Set.of();worker.interrupt();}
        // Keep the native manager/resource owners alive until CPU decoding exits.
        worker.join();if(cleanupFailure!=null)throw cleanupFailure;
    }
    synchronized Map<String,Object> diagnostic(){return Map.of("cached_frames",tasks.size(),"loaded",loaded,"evictions",evictions,"misses",misses,"resident_bytes",residentBytes,"peak_bytes",peakBytes,"budget_bytes",budget);}
}
