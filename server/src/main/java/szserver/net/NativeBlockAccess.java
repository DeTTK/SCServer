package szserver.net;

import szserver.client.*;
import szserver.commands.CommandService;
import szserver.util.*;
import szserver.world.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Ordered native intents/CAS -> existing durable command owner. Never decode NBT on transport. */
public final class NativeBlockAccess implements AutoCloseable {
    public record Outcome(int accepted,int unchanged,int rejected,List<World.Block> repairs){}
    public record Repair(TerrainProjection.Prepared chunk,List<byte[]> blocks,long revision){public Repair(TerrainProjection.Prepared chunk,List<byte[]> blocks){this(chunk,blocks,chunk==null?0:chunk.revision());}}
    private final WorldLoop world;private final RegionTerrain regions;private final TerrainProjection projection;private final CommandService commands;
    private final ThreadPoolExecutor worker=Workers.pool("native-block-access",8,64);
    private final szserver.commands.SceneWriteQueue<Object> mutations=new szserver.commands.SceneWriteQueue<>();
    private final Semaphore slots=new Semaphore(65);private final AtomicLong retained=new AtomicLong(),accepted=new AtomicLong(),unchanged=new AtomicLong(),rejected=new AtomicLong(),conflicts=new AtomicLong(),failures=new AtomicLong();
    private final Map<String,AtomicLong> rejectionReasons=new ConcurrentHashMap<>();
    private final AtomicLong residentSequence=new AtomicLong();
    private void rejection(Throwable error){while((error instanceof ExecutionException||error instanceof CompletionException)&&error.getCause()!=null)error=error.getCause();String reason=error.getMessage();if(reason==null||reason.length()>96)reason=error.getClass().getSimpleName();if(!rejectionReasons.containsKey(reason)&&rejectionReasons.size()>=16)reason="other";rejectionReasons.computeIfAbsent(reason,k->new AtomicLong()).incrementAndGet();}
    public NativeBlockAccess(WorldLoop world,RegionTerrain regions,TerrainProjection projection,CommandService commands){this.world=world;this.regions=regions;this.projection=projection;this.commands=commands;}
    private <T> CompletableFuture<T> queued(int bytes,Callable<T> task){return queued(null,bytes,task);}
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> queued(UUID actor,int bytes,Callable<T> task){
        if(!slots.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("native_block_queue_full"));
        while(true){long current=retained.get();if(current+bytes>32L*1024*1024){slots.release();return CompletableFuture.failedFuture(new RejectedExecutionException("native_block_byte_limit"));}if(retained.compareAndSet(current,current+bytes))break;}
        java.util.function.Supplier<CompletableFuture<Object>> execute=()->{CompletableFuture<Object> future=new CompletableFuture<>();try{worker.execute(()->{try{future.complete(task.call());}catch(Throwable error){failures.incrementAndGet();future.completeExceptionally(error);}});}catch(RejectedExecutionException error){future.completeExceptionally(error);}return future;};
        // Independent players wait concurrently; each player's edits remain FIFO.
        // Charge the original 65-slot/32MiB ceiling before retaining a writer task.
        CompletableFuture<Object> future=actor==null?execute.get():mutations.submit(actor,0,execute);
        future.whenComplete((value,error)->{retained.addAndGet(-bytes);slots.release();});return future.thenApply(value->(T)value);
    }
    private void warm(World.Block p)throws Exception{var resident=world.residentMap();if(resident!=null){world.submit(w->resident.load(new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)))).thenCompose(f->f).get(5,TimeUnit.SECONDS);return;}if(regions==null)return;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(regions.nativeTerrainRecord(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16))==null){if(System.nanoTime()>end)throw new IllegalArgumentException("native_block_terrain_unavailable");Thread.sleep(5);}}
    private JsonObject submit(UUID actor,JsonObject request,double[] feet)throws Exception{var map=world.residentMap();if(map==null)return commands.submit(request).get(20,TimeUnit.SECONDS);return commands.submitResidentBlocks(request,map,actor,residentSequence.incrementAndGet(),()->new World.Vec(feet[0],feet[1]+1.62,feet[2])).get(20,TimeUnit.SECONDS);}
    private JsonObject request(UUID actor,World.Block p,String kind){JsonObject request=Json.tree(Map.of("type",kind,"commandId",UUID.randomUUID().toString(),"actor",actor.toString(),"x",p.x(),"y",p.y(),"z",p.z()));return request;}
    public CompletableFuture<Outcome> mutate(UUID actor,NativeBlockPackets.Batch batch,double[] reachOrigin){
        double[] origin=reachOrigin.clone();return queued(actor,batch.retainedBytes(),()->{int applied=0,echoes=0,denied=0;Set<World.Block> repairs=new LinkedHashSet<>();
            for(var delta:batch.deltas()){World.Block p=delta.position();if(!NativeBlockPackets.reachable(origin,p)){rejection(new IllegalArgumentException("native_block_out_of_reach"));denied++;continue;}
                try{warm(p);JsonObject request=request(actor,p,"block_cas");request.addProperty("expectedBlockId",delta.beforeId());request.addProperty("expectedMetadata",delta.beforeMetadata());request.addProperty("blockId",delta.afterId());request.addProperty("metadata",delta.afterMetadata());if(delta.after()!=null)request.add("completeTile",CompleteTileState.parts(delta.after().bytes()));if(world.residentMap()!=null&&batch.version()>=2)request.add("expectedCompleteTile",delta.before()==null?JsonNull.INSTANCE:CompleteTileState.parts(delta.before().bytes()));
                    JsonObject result=submit(actor,request,origin);if(result.get("status").getAsString().equals("unchanged"))echoes++;else applied++;
                }catch(Exception error){rejection(error);denied++;repairs.add(p);Throwable cause=error instanceof ExecutionException?error.getCause():error;if(cause instanceof IllegalArgumentException&&"native_block_compare_failed".equals(cause.getMessage()))conflicts.incrementAndGet();}
            }accepted.addAndGet(applied);unchanged.addAndGet(echoes);rejected.addAndGet(denied);return new Outcome(applied,echoes,denied,List.copyOf(repairs));});
    }
    public CompletableFuture<Outcome> destroy(UUID actor,World.Block p,double[] reachOrigin){double[] origin=reachOrigin.clone();return queued(actor,0,()->{if(!NativeBlockPackets.reachable(origin,p)){rejection(new IllegalArgumentException("native_block_out_of_reach"));rejected.incrementAndGet();return new Outcome(0,0,1,List.of());}try{warm(p);JsonObject request=request(actor,p,"tile_set");request.addProperty("blockId",0);request.addProperty("metadata",0);submit(actor,request,origin);accepted.incrementAndGet();return new Outcome(1,0,0,List.of());}catch(Exception error){rejection(error);rejected.incrementAndGet();return new Outcome(0,0,1,List.of(p));}});}
    /** Share projection cache; direct voxel corrections precede compact/full tile restoration. */
    public CompletableFuture<Repair> repair(List<World.Block> positions){List<World.Block> points=List.copyOf(positions);if(points.isEmpty()||points.size()>32)return CompletableFuture.failedFuture(new IllegalArgumentException("native_block_repair_count"));return queued(0,()->{
        World.Block first=points.get(0);int x=Math.floorDiv(first.x(),16),z=Math.floorDiv(first.z(),16);for(var p:points)if(Math.floorDiv(p.x(),16)!=x||Math.floorDiv(p.z(),16)!=z)throw new IllegalArgumentException("native_block_repair_chunk");warm(first);var resident=world.residentMap();if(resident!=null){var view=world.submit(w->resident.reads().require(new ResidentChunks.Key(x,z))).get(5,TimeUnit.SECONDS);List<byte[]> frames=new ArrayList<>();long bytes=0;boolean snapshot=false;for(var p:points){int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16),packed=view.data().voxel(cell);byte[] voxel=GamePackets.block(p.x(),p.y(),p.z(),packed>>>4,packed&15);frames.add(voxel);bytes+=voxel.length;var tile=view.data().tile(cell);if(tile!=null){if(tile.completeStateSize()==0){snapshot=true;break;}byte[] state=CompleteTileState.frame(tile);frames.add(state);bytes+=state.length;}if(bytes>1500000){snapshot=true;break;}}if(!snapshot)return new Repair(null,List.copyOf(frames),view.revision());}long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){var prepared=projection.prepared(x,z);if(prepared!=null&&prepared.revision()==projection.version(x,z)){
                record Snapshot(long revision,List<Integer> voxels){}Snapshot state=world.submit(w->new Snapshot(resident==null?w.overlay(x,z).revision():resident.reads().require(new ResidentChunks.Key(x,z)).revision(),points.stream().map(w::voxel).toList())).get(5,TimeUnit.SECONDS);
                if(state.revision()==prepared.revision()){List<byte[]> frames=new ArrayList<>();for(int i=0;i<points.size();i++){var p=points.get(i);int voxel=state.voxels().get(i);frames.add(GamePackets.block(p.x(),p.y(),p.z(),voxel>>>4,voxel&15));}return new Repair(prepared,List.copyOf(frames));}}
            Thread.sleep(5);
        }throw new IllegalArgumentException("native_block_repair_timeout");});}
    public Map<String,Object> metrics(){Map<String,Long> reasons=new TreeMap<>();rejectionReasons.forEach((reason,count)->reasons.put(reason,count.get()));return Map.of("accepted",accepted.get(),"unchanged",unchanged.get(),"rejected",rejected.get(),"conflicts",conflicts.get(),"failures",failures.get(),"retainedBytes",retained.get(),"byteLimit",32L*1024*1024,"queued",worker.getQueue().size(),"rejectionReasons",reasons);}
    @Override public void close()throws InterruptedException{if(!mutations.drain(60000))throw new IllegalStateException("native_block_shutdown_timeout");Workers.stop(worker);}
}
