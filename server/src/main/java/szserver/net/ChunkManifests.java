package szserver.net;

import szserver.client.NativeResidentChunkCodec.ReadSnapshot;
import szserver.world.RegionTerrain;
import szserver.world.ResidentChunks;
import szserver.world.ResidentMapRuntime;
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
        try{
            worker.execute(()->{
                try{
                    var resident=world==null?null:world.residentMap();
                    var rows=resident==null?inspectBase(immutable):inspectResident(resident,immutable);
                    result.complete(rows);
                }catch(Exception failure){errors.incrementAndGet();result.completeExceptionally(failure);}
            });
        }catch(RejectedExecutionException full){rejected.incrementAndGet();result.completeExceptionally(full);}
        return result;
    }

    private List<Row> inspectBase(List<Long> keys){
        List<Row> rows=new ArrayList<>(keys.size());
        for(long key:keys){
            try{
                int x=(int)(key>>32),z=(int)key;
                UUID revision;
                if(regions==null){
                    ByteBuffer record=ByteBuffer.wrap(packets.chunkRecord(x,z));
                    revision=new UUID(record.getLong(17),record.getLong(25));
                }else revision=regions.manifestRevision(x,z);
                rows.add(row(key,revision,null));
            }catch(Exception failure){rows.add(row(key,null,failure));}
        }
        return List.copyOf(rows);
    }

    private record ResidentRead(ReadSnapshot snapshot,Throwable failure){}

    private List<Row> inspectResident(ResidentMapRuntime resident,List<Long> keys)throws Exception{
        List<CompletableFuture<ResidentRead>> reads=new ArrayList<>(keys.size());
        Map<ResidentChunks.Key,CompletableFuture<ResidentRead>> cold=new LinkedHashMap<>();
        for(long key:keys){
            var region=new ResidentChunks.Key((int)(key>>32),(int)key);
            var current=resident.reads().peek(region);
            reads.add(current==null?cold.computeIfAbsent(region,ignored->new CompletableFuture<>())
                :CompletableFuture.completedFuture(new ResidentRead(current.data(),null)));
        }
        // One submission for the whole batch. Geometry/revision work stays on
        // this worker even when the map owner completes a cold load.
        if(!cold.isEmpty())world.submit(w->{
            if(world.residentMap()!=resident)throw new IllegalStateException("chunk_manifest_map_changed");
            cold.forEach((key,waiting)->resident.load(key).whenComplete((snapshot,failure)->
                waiting.complete(new ResidentRead(snapshot,failure))));
            return null;
        }).exceptionally(failure->{
            cold.values().forEach(waiting->waiting.completeExceptionally(failure));
            return null;
        });
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
        List<Row> rows=new ArrayList<>(keys.size());
        for(int i=0;i<keys.size();i++){
            ResidentRead read=reads.get(i).join();
            try{rows.add(row(keys.get(i),read.snapshot()==null?null:read.snapshot().networkRevision(),read.failure()));}
            catch(Exception failure){rows.add(row(keys.get(i),null,failure));}
        }
        return List.copyOf(rows);
    }

    private Row row(long key,UUID revision,Throwable failure){
        if(failure==null||ResidentChunks.missingBase(failure)){
            reads.incrementAndGet();
            return new Row(key,revision,revision!=null);
        }
        errors.incrementAndGet();
        Throwable cause=failure;
        while(cause.getCause()!=null)cause=cause.getCause();
        String message=Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());
        return new Row(key,null,false,message.substring(0,Math.min(message.length(),160)));
    }

    public Map<String,Object> metrics(){return Map.of("reads",reads.get(),"errors",errors.get(),"rejected",rejected.get(),"queued",worker.getQueue().size());}
    @Override public void close()throws InterruptedException{Workers.stop(worker);}
}
