package replay;

import java.io.*;
import java.util.*;

/** All native CPU frame handles are admitted together before viewing. */
final class SceneResidentFrames<T> implements AutoCloseable {
    interface Loader<T>{T load(long key)throws Exception;}
    interface Release<T>{void release(T value)throws Exception;}
    interface Size<T>{long bytes(T value)throws Exception;}
    final List<Long> keys;final Loader<T> loader;final Release<T> release;final Size<T> size;final long budget;
    volatile Map<Long,T> values=Map.of();volatile boolean ready,closed;volatile Exception failure,cleanupFailure;
    volatile int loaded;volatile long bytes;Thread worker;
    SceneResidentFrames(Collection<Long> keys,long budget,Loader<T> loader,Release<T> release,Size<T> size){
        var unique=new LinkedHashSet<>(keys);unique.add(-1L);this.keys=List.copyOf(unique);this.budget=budget;this.loader=loader;this.release=release;this.size=size;
    }
    boolean prepare()throws Exception {
        if(closed)throw new IllegalStateException("Closed resident frames");if(failure!=null)throw failure;if(ready)return true;
        if(worker==null){worker=new Thread(this::load,"Replay complete particle frames");worker.setDaemon(true);worker.start();}return false;
    }
    T get(long key)throws Exception {if(failure!=null)throw failure;if(closed||!ready)throw new IllegalStateException("Resident frames not admitted");T result=values.get(key);if(result==null)throw new IOException("Missing resident frame: "+key);return result;}
    private void load(){var candidate=new HashMap<Long,T>();try{
        for(long key:keys){if(closed)return;T value=loader.load(key);candidate.put(key,value);long amount=size.bytes(value);if(amount<0)throw new IOException("Invalid native frame allocation");bytes=Math.addExact(bytes,Math.addExact(amount,128));if(bytes>budget)throw new IOException("Native particle history exceeds resident budget: "+bytes+" > "+budget);loaded++;}
        if(closed)return;values=Collections.unmodifiableMap(candidate);ready=true;candidate=null;
    }catch(Exception error){if(!closed)failure=error;}finally{if(candidate!=null)for(T value:candidate.values())try{release.release(value);}catch(Exception error){if(cleanupFailure==null)cleanupFailure=error;else cleanupFailure.addSuppressed(error);}}}
    Map<String,Object> diagnostic(){return Map.of("ready",ready,"loaded",loaded,"total",keys.size(),"resident_bytes",bytes,"budget_bytes",budget);}
    public void close()throws Exception {
        if(closed)return;closed=true;if(worker!=null){worker.interrupt();worker.join();}Exception error=cleanupFailure;for(T value:values.values())try{release.release(value);}catch(Exception e){if(error==null)error=e;else error.addSuppressed(e);}values=Map.of();if(error!=null)throw error;
    }
}
