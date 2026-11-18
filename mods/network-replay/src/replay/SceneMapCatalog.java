package replay;
import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.function.LongSupplier;import java.nio.charset.StandardCharsets;

/** Decode every map recipe once before admission; never instantiate a world snapshot. */
final class SceneMapCatalog implements AutoCloseable {
    record Prepared(String type,SceneTracks.Bytes key,SceneTracks.Bytes value,Object coordinate,SceneChunkCodec.Chunk chunk,byte[] payload){}
    interface Install {void accept(Prepared value)throws Exception;}
    final ArrayBlockingQueue<Prepared> queue=new ArrayBlockingQueue<>(8);final Thread worker;
    volatile boolean closed,decoded;volatile Exception failure;volatile int prepared;
    int installed;boolean ready;
    SceneMapCatalog(SceneTracks tracks){
        worker=new Thread(()->{try{
            Set<SceneTracks.Bytes> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var object:tracks.objects)if(object.type().equals("world-map-chunk")||object.type().equals("world-map-prefab")){
                var values=object.channels().get("value");var coordinates=object.channels().get("map-key");
                if(values==null||coordinates==null)throw new IOException("Missing map resource track");
                for(int i=0;i<values.keys.size();i++){
                    if(closed)return;if(!(values.keys.get(i).value() instanceof SceneTracks.Bytes bytes))throw new IOException("Invalid map resource value");
                    if(!seen.add(bytes))continue;
                    if(!(coordinates.sample(values.timeAt(i)) instanceof SceneTracks.Bytes key))throw new IOException("Missing map resource identity");
                    String name=new String(key.data(),StandardCharsets.UTF_8);SceneChunkCodec.Chunk chunk=null;Object coordinate;byte[] payload=null;
                    if(object.type().equals("world-map-chunk")){
                        String[] parts=name.split(",",-1);if(parts.length!=2)throw new IOException("Invalid chunk resource coordinate");
                        var c=new SceneMapIndex.Coordinate(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));coordinate=c;chunk=SceneChunkCodec.decode(bytes);
                        if(chunk.x()!=c.x()||chunk.z()!=c.z())throw new IOException("Chunk resource coordinate changed");
                    }else{UUID id=UUID.fromString(name);if(!id.toString().equals(name))throw new IOException("Invalid prefab resource UUID");coordinate=id;payload=bytes.data();}
                    queue.put(new Prepared(object.type(),key,bytes,coordinate,chunk,payload));prepared++;
                }
            }decoded=true;
        }catch(InterruptedException stopped){if(!closed)failure=stopped;}catch(Exception error){if(!closed)failure=error;}
        },"Replay map resource catalog");worker.setDaemon(true);worker.start();
    }
    boolean step(Install install,long deadline,LongSupplier clock)throws Exception {
        if(closed)throw new IllegalStateException("Closed map catalog");if(failure!=null)throw failure;if(ready)return true;
        while(clock.getAsLong()<deadline){var value=queue.poll();if(value==null)break;install.accept(value);installed++;}
        return ready=decoded&&queue.isEmpty();
    }
    Map<String,Object> diagnostic(){return Map.of("decoded",prepared,"installed",installed,"ready",ready,"packet_dispatch",false,"simulation_ticks",false);}
    public void close()throws InterruptedException {if(closed)return;closed=true;worker.interrupt();worker.join();queue.clear();}
}
