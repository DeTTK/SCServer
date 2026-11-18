package replay;

import java.io.*;
import java.util.*;

/** Numeric, pose and actor metadata history is independent of the map/page LRU.
 * Decode on a CPU worker and publish the complete, checked set before viewing. */
final class SceneResidentValues implements AutoCloseable {
    final SceneTracks tracks;
    final long budget;
    Thread worker;
    volatile boolean closed,ready;
    volatile Exception failure;
    volatile int loaded,total;
    volatile long bytes;
    record Value(int type,SceneTracks.Blend blend){}
    SceneResidentValues(SceneTracks tracks){this(tracks,Math.max(128L*1024*1024,Math.min(1024L*1024*1024,Runtime.getRuntime().maxMemory()/3)));}
    SceneResidentValues(SceneTracks tracks,long budget){this.tracks=tracks;this.budget=budget;}
    boolean prepare()throws Exception {
        if(closed)throw new IllegalStateException("Closed scene value residency");if(failure!=null)throw failure;if(ready)return true;
        if(worker==null){worker=new Thread(this::load,"Replay complete scene values");worker.setDaemon(true);worker.start();}return false;
    }
    private void load(){try{
        if(!(tracks.storage instanceof ScenePagedArchive.Store store)){ready=true;return;}
        var plan=new LinkedHashMap<ScenePagedArchive.Reference,Value>();long estimated=0;
        for(var object:tracks.sceneIndexRows())for(var track:object.channels().values())if(track.pagedKeys!=null){var keys=track.pagedKeys;for(int n=0;n<keys.size();n++){
            if(closed)return;var ref=keys.reference(n);if(!plan.containsKey(ref)){estimated=Math.addExact(estimated,ref.length()+128L);if(estimated>budget)throw new IOException("Scene track history exceeds resident budget: "+estimated+" > "+budget);plan.put(ref,new Value(keys.type[n],track.blend));}
        }}
        total=plan.size();var result=new HashMap<ScenePagedArchive.Reference,SceneTracks.Value>();long allocated=0;
        for(var entry:plan.entrySet()){
            if(closed)return;var value=entry.getValue();var decoded=store.value(entry.getKey(),value.type(),value.blend());
            allocated=Math.addExact(allocated,SceneTrackArchive.valueBytes(decoded)+128);if(allocated>budget)throw new IOException("Decoded scene history exceeds resident budget");result.put(entry.getKey(),decoded);loaded++;
        }
        if(closed)return;
        synchronized(store){if(store.closed)throw new IOException("Scene archive closed during residency preparation");store.resident=Collections.unmodifiableMap(result);
            for(var iterator=store.decoded.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(result.containsKey(entry.getKey())){store.decodedBytes-=SceneTrackArchive.valueBytes(entry.getValue())+64;iterator.remove();}}
        }
        bytes=allocated;ready=true;
    }catch(Exception error){if(!closed)failure=error;}}
    Map<String,Object> diagnostic(){return Map.of("ready",ready,"loaded",loaded,"total",total,"resident_bytes",bytes,"budget_bytes",budget);}
    public void close()throws Exception {closed=true;if(worker!=null){worker.interrupt();worker.join();}if(tracks.storage instanceof ScenePagedArchive.Store store)store.resident=Map.of();}
}
