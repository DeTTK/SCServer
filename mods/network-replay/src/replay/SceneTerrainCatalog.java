package replay;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Prepare immutable recorded mesh versions directly, never by playing a world. */
final class SceneTerrainCatalog implements AutoCloseable {
    record Prepared(SceneTracks.Bytes value,SceneTerrainCodec.Mesh mesh){}
    interface Upload {void accept(Prepared value)throws Exception;}
    final ArrayBlockingQueue<Prepared> queue=new ArrayBlockingQueue<>(8);
    final Thread worker;
    volatile boolean closed,decoded;
    volatile Exception failure;
    volatile int prepared;
    int uploaded;
    boolean ready;
    SceneTerrainCatalog(SceneTracks tracks){
        worker=new Thread(()->{
            try{
                Set<SceneTracks.Bytes> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                for(var object:tracks.objects)if(object.type().equals("world-map-terrain")){
                    var values=object.channels().get("value");var coordinates=object.channels().get("map-key");
                    if(values==null||coordinates==null)throw new IOException("Terrain resource track missing");
                    for(int i=0;i<values.keys.size();i++){
                        if(closed)return;
                        if(!(values.keys.get(i).value() instanceof SceneTracks.Bytes bytes))throw new IOException("Invalid terrain resource value");
                        if(!seen.add(bytes))continue;
                        var mesh=SceneTerrainCodec.decode(bytes);
                        if(!(coordinates.sample(values.timeAt(i)) instanceof SceneTracks.Bytes key)||!mesh.coordinate().equals(SceneTerrainCodec.Coordinate.parse(new String(key.data(),java.nio.charset.StandardCharsets.UTF_8))))throw new IOException("Terrain resource coordinate changed");
                        queue.put(new Prepared(bytes,mesh));prepared++;
                    }
                }
                decoded=true;
            }catch(InterruptedException stopped){if(!closed)failure=stopped;}
            catch(Exception error){if(!closed)failure=error;}
        },"Replay terrain resource catalog");worker.setDaemon(true);worker.start();
    }
    boolean step(Upload upload,long deadline,LongSupplier clock)throws Exception {
        if(closed)throw new IllegalStateException("Closed terrain catalog");if(failure!=null)throw failure;if(ready)return true;
        while(clock.getAsLong()<deadline){var value=queue.poll();if(value==null)break;upload.accept(value);uploaded++;}
        return ready=decoded&&queue.isEmpty();
    }
    Map<String,Object> diagnostic(){return Map.of("decoded",prepared,"uploaded",uploaded,"ready",ready,"simulation_ticks",false,"packet_dispatch",false);}
    public void close()throws InterruptedException {if(closed)return;closed=true;worker.interrupt();worker.join();queue.clear();}
}
