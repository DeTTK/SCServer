package szserver.persistence;

import com.google.gson.*;
import szserver.net.TeleportCommands;
import szserver.util.Json;
import szserver.util.Workers;
import szserver.world.World.Vec;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Old Store named points, without a resident map or a default 4096-point ceiling.
 * Canonical append, index publication, queries and streaming checkpoints are serialized off gameplay owners. */
public final class WarpStore implements AutoCloseable {
    /** Optional legacy deployment budget; production defaults to no count ceiling. */
    public static final int LIMIT=4096,BATCH=32;
    private final ThreadPoolExecutor worker=Workers.pool("warp-storage",1,32);
    private final Set<String> pending=new HashSet<>();
    private final Object admission=new Object();
    private final Journal journal;
    private final DiskWarpIndex index;
    private final int limit;
    private volatile boolean failed,closing;
    private volatile Map<String,Object> view=Map.of();
    private long writes,journalBytes,compactions,compactionFailures,queries;
    private static final class CheckpointFailure extends Exception{CheckpointFailure(Throwable error){super("warp_checkpoint_failed",error);}}
    public WarpStore(Path data)throws Exception{this(data,0,0);}
    public WarpStore(Path data,long testDelay)throws Exception{this(data,testDelay,0);}
    public WarpStore(Path data,long testDelay,int limit)throws Exception{
        offOwner();if(limit<0)throw new IllegalArgumentException("warp_capacity_limit");this.limit=limit;
        journal=new Journal(data.resolve("warps.sjournal"),testDelay,event->{});DiskWarpIndex opened=null;
        try{opened=new DiskWarpIndex(data);index=opened;journal.replayLocated((reference,event)->recover(event)).get();journalBytes=Files.size(journal.path());publish();}
        catch(Exception|Error error){worker.shutdownNow();if(opened!=null)opened.close();journal.close();throw error;}
    }
    private static void offOwner(){String thread=Thread.currentThread().getName();if(thread.equals("world-tick")||thread.equals("native-game-owner"))throw new IllegalStateException("warp_blocking_on_owner");}
    private static JsonObject event(String name,Vec point){JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("deleted",point==null);if(point!=null){row.addProperty("x",point.x());row.addProperty("y",point.y());row.addProperty("z",point.z());}return row;}
    private void recover(JsonObject event)throws IOException{
        if(event.has("snapshot")){JsonArray rows=event.getAsJsonArray("snapshot");if(rows.size()>BATCH)throw new IllegalArgumentException("warp_checkpoint_batch_limit");for(JsonElement row:rows)recover(row.getAsJsonObject());return;}
        String name=TeleportCommands.warpName(event.get("name").getAsString());Vec point=event.get("deleted").getAsBoolean()?null:TeleportCommands.warpPosition(new Vec(event.get("x").getAsDouble(),event.get("y").getAsDouble(),event.get("z").getAsDouble()));
        if(point!=null&&limit>0&&index.get(name)==null&&index.count()>=limit)throw new IllegalArgumentException("warp_limit");index.put(name,point);
    }
    private void ensure(){if(!healthy())throw new IllegalStateException("warp_storage_failed");}
    @FunctionalInterface private interface Task<T>{T run()throws Exception;}
    private <T>CompletableFuture<T> submit(Task<T> action){
        CompletableFuture<T> result=new CompletableFuture<>();synchronized(admission){
            if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("warp_storage_closed_or_failed"));
            try{worker.execute(()->{try{ensure();T value=action.run();publish();result.complete(value);}catch(Throwable error){terminal(error);publish();result.completeExceptionally(error);}});}
            catch(RejectedExecutionException error){result.completeExceptionally(new IllegalArgumentException("warp_queue_full"));}
        }return result;
    }
    private void terminal(Throwable error){Throwable cause=error;while(cause instanceof ExecutionException||cause instanceof CompletionException)cause=cause.getCause();if(cause instanceof IOException||cause instanceof UncheckedIOException||!journal.healthy())failed=true;}
    public CompletableFuture<Vec> query(String raw){String name;try{name=TeleportCommands.warpName(raw);}catch(IllegalArgumentException error){return CompletableFuture.failedFuture(error);}return submit(()->{queries++;return index.get(name);});}
    /** Compatibility for bootstrap/tests/HTTP callers. The native owner uses query(), never waits. */
    public Vec get(String name){offOwner();return query(name).join();}
    public static int compareNames(String a,String b){int i=0,j=0;while(i<a.length()&&j<b.length()){int x=a.codePointAt(i),y=b.codePointAt(j);if(x!=y)return Integer.compare(x,y);i+=Character.charCount(x);j+=Character.charCount(y);}return Integer.compare(a.length()-i,b.length()-j);}
    public Map<String,Vec> list(){offOwner();return submit(()->{Map<String,Vec> result=new TreeMap<>(WarpStore::compareNames);index.visit(result::put);return Collections.unmodifiableMap(result);}).join();}
    public CompletableFuture<Void> set(String raw,Vec point){try{return changeBatch(Collections.singletonMap(TeleportCommands.warpName(raw),TeleportCommands.warpPosition(point)));}catch(IllegalArgumentException error){return CompletableFuture.failedFuture(error);}}
    public CompletableFuture<Void> delete(String raw){try{return changeBatch(Collections.singletonMap(TeleportCommands.warpName(raw),null));}catch(IllegalArgumentException error){return CompletableFuture.failedFuture(error);}}
    public CompletableFuture<Void> setBatch(Map<String,Vec> values){
        if(values==null||values.isEmpty()||values.size()>BATCH)return CompletableFuture.failedFuture(new IllegalArgumentException("warp_batch_limit"));Map<String,Vec> batch=new LinkedHashMap<>();
        try{for(var entry:values.entrySet()){String name=TeleportCommands.warpName(entry.getKey());if(batch.putIfAbsent(name,TeleportCommands.warpPosition(entry.getValue()))!=null)throw new IllegalArgumentException("warp_duplicate_name");}}
        catch(IllegalArgumentException error){return CompletableFuture.failedFuture(error);}return changeBatch(batch);
    }
    private CompletableFuture<Void> changeBatch(Map<String,Vec> batch){
        CompletableFuture<Void> result=new CompletableFuture<>();synchronized(admission){
            if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("warp_storage_failed"));
            if(batch.keySet().stream().anyMatch(pending::contains))return CompletableFuture.failedFuture(new IllegalArgumentException("warp_busy"));
            if(pending.size()+batch.size()>32*BATCH)return CompletableFuture.failedFuture(new IllegalArgumentException("warp_queue_full"));
            pending.addAll(batch.keySet());
            try{worker.execute(()->{
                Throwable error=null;
                try{ensure();if(limit>0){long fresh=0;for(var entry:batch.entrySet())if(entry.getValue()!=null&&index.get(entry.getKey())==null)fresh++;if(index.count()+fresh>limit)throw new IllegalArgumentException("warp_limit");}
                    JsonObject record;if(batch.size()==1){var entry=batch.entrySet().iterator().next();record=event(entry.getKey(),entry.getValue());}else{record=new JsonObject();JsonArray rows=new JsonArray();batch.forEach((name,point)->rows.add(event(name,point)));record.add("snapshot",rows);}
                    journal.append(record).get();for(var entry:batch.entrySet())index.put(entry.getKey(),entry.getValue());writes+=batch.size();journalBytes+=8+Json.bytes(record).length;
                    // A growing catalogue is already compact. Rewrite only when updates/deletes amplify disk usage.
                    if(journalBytes>Math.max(1024L*1024,index.count()*384L))try{checkpoint();}catch(CheckpointFailure failure){if(failed)throw failure;}
                }catch(Throwable failure){error=failure;terminal(failure);}
                finally{synchronized(admission){pending.removeAll(batch.keySet());}publish();}
                if(error==null)result.complete(null);else result.completeExceptionally(error);
            });}catch(RejectedExecutionException error){pending.removeAll(batch.keySet());result.completeExceptionally(new IllegalArgumentException("warp_queue_full"));}
        }return result;
    }
    /** Worker holds operation order while the journal owner emits bounded batches from the disk index. */
    private void checkpoint()throws Exception{
        try{journal.compactStreaming(sink->{JsonArray[] rows={new JsonArray()};
            try{index.visit((name,point)->{rows[0].add(event(name,point));if(rows[0].size()==BATCH){JsonObject part=new JsonObject();part.add("snapshot",rows[0]);sink.accept(part);rows[0]=new JsonArray();}});}catch(IOException error){if(error.getMessage()!=null&&error.getMessage().startsWith("warp_index_"))failed=true;throw error;}
            if(!rows[0].isEmpty()){JsonObject part=new JsonObject();part.add("snapshot",rows[0]);sink.accept(part);}}).get();journalBytes=Files.size(journal.path());compactions++;}
        catch(Exception error){compactionFailures++;if(!journal.healthy())failed=true;throw new CheckpointFailure(error);}
    }
    public CompletableFuture<Void> compact(){return submit(()->{checkpoint();return null;});}
    public boolean healthy(){return !failed&&journal.healthy();}
    private void publish(){int reserved;synchronized(admission){reserved=pending.size();}view=Map.of("healthy",healthy(),"points",index.count(),"capacityLimit",limit,"pending",reserved,"writes",writes,"queries",queries,"compactions",compactions,"compactionFailures",compactionFailures,"journalBytes",journalBytes,"index",index.metrics());}
    public Map<String,Object> metrics(){Map<String,Object> result=new LinkedHashMap<>(view);result.put("healthy",healthy());result.put("queued",worker.getQueue().size());result.put("journal",journal.metrics());return Collections.unmodifiableMap(result);}
    @Override public void close()throws Exception{
        offOwner();synchronized(admission){closing=true;worker.shutdown();}
        // Accepted fsync/compaction work must finish even on a large catalogue. Do not interrupt it at ten seconds.
        while(!worker.awaitTermination(1,TimeUnit.SECONDS)){}
        try{index.close();}finally{journal.close();}
    }
}
