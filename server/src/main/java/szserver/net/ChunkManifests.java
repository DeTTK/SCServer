package szserver.net;

import szserver.world.RegionTerrain;
import szserver.util.Workers;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** CRC-checked SZR catalogue IO on a dedicated worker; never decompresses geometry or creates game objects. */
public final class ChunkManifests implements AutoCloseable {
    public record Row(long key,UUID revision,boolean present,String error){public Row(long key,UUID revision,boolean present){this(key,revision,present,null);}}
    private final RegionTerrain regions;private final GamePackets packets;
    private final szserver.world.WorldLoop world;
    private final ThreadPoolExecutor worker=Workers.pool("chunk-manifests",1,32);
    private final AtomicLong reads=new AtomicLong(),errors=new AtomicLong(),rejected=new AtomicLong();
    public ChunkManifests(RegionTerrain regions,GamePackets packets){this(null,regions,packets);}
    public ChunkManifests(szserver.world.WorldLoop world,RegionTerrain regions,GamePackets packets){this.world=world;this.regions=regions;this.packets=packets;}
    /** Compatibility strict API; production inspect reports each bad coordinate separately. */
    public CompletableFuture<List<Row>> request(List<Long> coordinates){
        var result=new CompletableFuture<List<Row>>();inspect(coordinates).whenComplete((rows,error)->{if(error!=null){result.completeExceptionally(error);return;}for(var row:rows)if(row.error()!=null){result.completeExceptionally(new java.io.IOException(row.error()));return;}result.complete(rows);});return result;
    }
    public CompletableFuture<List<Row>> inspect(List<Long> coordinates){
        if(coordinates.size()>128)throw new IllegalArgumentException("chunk_manifest_batch_limit");List<Long> immutable=List.copyOf(coordinates);var result=new CompletableFuture<List<Row>>();
        try{worker.execute(()->{try{List<Row> rows=new ArrayList<>();for(long key:immutable){try{int x=(int)(key>>32),z=(int)key;UUID revision;
            var resident=world==null?null:world.residentMap();if(resident!=null){var snapshot=world.submit(w->resident.load(new szserver.world.ResidentChunks.Key(x,z))).thenCompose(future->future).get(10,TimeUnit.SECONDS);revision=snapshot.networkRevision();}
            else if(regions==null){ByteBuffer record=ByteBuffer.wrap(packets.chunkRecord(x,z));revision=new UUID(record.getLong(17),record.getLong(25));}
            else revision=regions.manifestRevision(x,z);
            reads.incrementAndGet();rows.add(new Row(key,revision,revision!=null));
            }catch(Exception failure){if(szserver.world.ResidentChunks.missingBase(failure)){reads.incrementAndGet();rows.add(new Row(key,null,false));continue;}errors.incrementAndGet();String message=failure.getMessage();if(message==null)message=failure.getClass().getSimpleName();rows.add(new Row(key,null,false,message.substring(0,Math.min(message.length(),160))));}
        }result.complete(List.copyOf(rows));}catch(Exception failure){errors.incrementAndGet();result.completeExceptionally(failure);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();result.completeExceptionally(full);}return result;
    }
    public Map<String,Object> metrics(){return Map.of("reads",reads.get(),"errors",errors.get(),"rejected",rejected.get(),"queued",worker.getQueue().size());}
    @Override public void close()throws InterruptedException{Workers.stop(worker);}
}
