package szserver.persistence;

import com.google.gson.*;
import szserver.util.Workers;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Old Store.blocks: logical string IDs, deliberately independent of native voxel overrides.
 * One bounded command worker serializes validation and commits; no gameplay owner accesses disk.
 */
public final class DeveloperBlocks implements AutoCloseable {
    public record Position(int x,int y,int z) implements Comparable<Position> {
        public Position { if(Math.abs((long)x)>30_000_000||Math.abs((long)y)>30_000_000||Math.abs((long)z)>30_000_000)throw new IllegalArgumentException("position is outside world bounds"); }
        public int compareTo(Position p){int n=Integer.compare(x,p.x);if(n==0)n=Integer.compare(y,p.y);return n==0?Integer.compare(z,p.z):n;}
        public JsonArray json(){JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(z);return a;}
    }
    public static final int ROW_LIMIT=100_000;
    public static final int BATCH_LIMIT=128;
    public static final long BYTE_LIMIT=16L*1024*1024;
    private final NavigableMap<Position,String> blocks=new TreeMap<>();
    private final ThreadPoolExecutor owner=Workers.pool("developer-blocks",1,32);
    private final Journal journal;
    private record Published(List<NavigableMap<Position,String>> parts,int count,long bytes,long operations){}
    private volatile Published published=new Published(Collections.nCopies(256,Collections.emptyNavigableMap()),0,0,0);
    private boolean recovering=true;
    private long bytes,operations;
    private long sinceCheckpoint;
    private volatile boolean closed,failed;
    private final Object closeLock=new Object();private boolean released;
    public DeveloperBlocks(Path data)throws Exception{this(data,0);}
    /** Isolated writer latency injection only. */
    public DeveloperBlocks(Path data,long delay)throws Exception{
        try{journal=new Journal(data.resolve("developer-blocks.sjournal"),delay,this::recover);publishRecovered();recovering=false;}catch(Exception|Error e){owner.shutdownNow();throw e;}
    }
    public static String id(String value){
        if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>80)throw new IllegalArgumentException("invalid block id");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw new IllegalArgumentException("invalid block id");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("invalid block id");}
        // Unpaired surrogates cannot be persisted by the original SQLite UTF-8 writer either.
        // No native registry, NUL, or whitespace restrictions apply to valid Unicode strings.
        return value;
    }
    private static long size(Position p,String id){return szserver.util.Json.bytes(row(p,id)).length;}
    private static JsonObject row(Position p,String id){JsonObject row=new JsonObject();row.add("position",p.json());row.addProperty("block_id",id);return row;}
    private static JsonObject event(Position p,String id){JsonObject event=new JsonObject();event.addProperty("type","developer-block");event.add("position",p.json());event.add("block_id",id==null?JsonNull.INSTANCE:new JsonPrimitive(id));return event;}
    private static Position position(JsonArray value){if(value==null||value.size()!=3)throw new IllegalArgumentException("developer_block_position");int[] p=new int[3];for(int i=0;i<3;i++){JsonElement e=value.get(i);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("developer_block_integer");p[i]=e.getAsBigDecimal().intValueExact();}return new Position(p[0],p[1],p[2]);}
    private synchronized void validate(Position p,String id){String old=blocks.get(p);long projected=bytes-(old==null?0:size(p,old))+(id==null?0:size(p,id));if(id!=null&&old==null&&blocks.size()>=ROW_LIMIT||projected>BYTE_LIMIT)throw new IllegalArgumentException("developer_block_capacity");}
    private synchronized void apply(Position p,String id){String old=id==null?blocks.remove(p):blocks.put(p,id);bytes-=(old==null?0:size(p,old));bytes+=(id==null?0:size(p,id));operations++;if(!recovering){int part=part(p);var parts=new ArrayList<>(published.parts());var changed=new TreeMap<>(parts.get(part));if(id==null)changed.remove(p);else changed.put(p,id);parts.set(part,Collections.unmodifiableNavigableMap(changed));published=new Published(List.copyOf(parts),blocks.size(),bytes,operations);}}
    private synchronized void validateBatch(Map<Position,String> values){
        long projected=bytes;int count=blocks.size();for(var entry:values.entrySet()){String old=blocks.get(entry.getKey()),value=entry.getValue();projected= Math.addExact(projected,-(old==null?0:size(entry.getKey(),old))+(value==null?0:size(entry.getKey(),value)));if(old==null&&value!=null)count++;else if(old!=null&&value==null)count--;}
        if(count>ROW_LIMIT||projected>BYTE_LIMIT)throw new IllegalArgumentException("developer_block_capacity");
    }
    private synchronized void applyBatch(Map<Position,String> values){
        Map<Integer,NavigableMap<Position,String>> changed=new HashMap<>();
        for(var entry:values.entrySet()){Position p=entry.getKey();String value=entry.getValue(),old=value==null?blocks.remove(p):blocks.put(p,value);bytes-=(old==null?0:size(p,old));bytes+=(value==null?0:size(p,value));operations++;
            if(!recovering){var partition=changed.computeIfAbsent(part(p),key->new TreeMap<>(published.parts().get(key)));if(value==null)partition.remove(p);else partition.put(p,value);}
        }
        if(!recovering){var parts=new ArrayList<>(published.parts());for(var entry:changed.entrySet())parts.set(entry.getKey(),Collections.unmodifiableNavigableMap(entry.getValue()));published=new Published(List.copyOf(parts),blocks.size(),bytes,operations);}
    }
    private static int part(Position p){return (p.hashCode()^(p.hashCode()>>>16))&255;}
    private void publishRecovered(){var parts=new ArrayList<NavigableMap<Position,String>>(256);for(int i=0;i<256;i++)parts.add(new TreeMap<>());blocks.forEach((p,id)->parts.get(part(p)).put(p,id));for(int i=0;i<256;i++)parts.set(i,Collections.unmodifiableNavigableMap(parts.get(i)));published=new Published(List.copyOf(parts),blocks.size(),bytes,operations);}
    private static Map<Position,String> batch(JsonObject event){
        JsonArray rows=event.getAsJsonArray("rows");if(rows==null||rows.isEmpty()||rows.size()>BATCH_LIMIT)throw new IllegalArgumentException("developer_block_batch_limit");Map<Position,String> values=new LinkedHashMap<>();
        for(var element:rows){JsonObject row=element.getAsJsonObject();Position p=position(row.getAsJsonArray("position"));JsonElement value=row.get("block_id");if(value!=null&&!value.isJsonNull()&&(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())||values.containsKey(p))throw new IllegalArgumentException("developer_block_batch_row");values.put(p,value==null||value.isJsonNull()?null:id(value.getAsString()));}return values;
    }
    private void recover(JsonObject event){if(event.has("type")&&event.get("type").getAsString().equals("developer-block-batch")){var values=batch(event);validateBatch(values);applyBatch(values);return;}if(!event.has("type")||!event.get("type").getAsString().equals("developer-block"))throw new IllegalArgumentException("developer_block_event");Position p=position(event.getAsJsonArray("position"));JsonElement value=event.get("block_id");if(value!=null&&!value.isJsonNull()&&(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()))throw new IllegalArgumentException("invalid block id");String id=value==null||value.isJsonNull()?null:id(value.getAsString());validate(p,id);apply(p,id);}
    /** Completion means fsync succeeded and the durable read view was updated. Caller cancellation
     * does not cancel a write accepted by the private worker. Rejected writes never mutate the view. */
    public CompletableFuture<Void> set(Position p,String value){Objects.requireNonNull(p);if(value!=null)id(value);return submit(()->{validate(p,value);journal.append(event(p,value),()->apply(p,value)).join();if(++sinceCheckpoint>=Math.max(256,rows()*2L)){journal.compactStreaming(this::checkpoint).join();sinceCheckpoint=0;}});}
    /** One durable record and one publication for a bounded batch, preserving old single-row recovery. */
    public CompletableFuture<Void> setBatch(Map<Position,String> values){
        if(values==null||values.isEmpty()||values.size()>BATCH_LIMIT)throw new IllegalArgumentException("developer_block_batch_limit");Map<Position,String> saved=new LinkedHashMap<>();JsonArray rows=new JsonArray();
        for(var entry:values.entrySet()){Position p=Objects.requireNonNull(entry.getKey());String value=entry.getValue();if(value!=null)id(value);saved.put(p,value);JsonObject row=new JsonObject();row.add("position",p.json());row.add("block_id",value==null?JsonNull.INSTANCE:new JsonPrimitive(value));rows.add(row);}
        JsonObject event=new JsonObject();event.addProperty("type","developer-block-batch");event.add("rows",rows);byte[] payload=szserver.util.Json.bytes(event);if(payload.length>65536)throw new IllegalArgumentException("developer_block_batch_bytes");szserver.util.Json.parse(payload);
        return submit(()->{validateBatch(saved);journal.append(event,()->applyBatch(saved)).join();sinceCheckpoint+=saved.size();if(sinceCheckpoint>=Math.max(256,rows()*2L)){journal.compactStreaming(this::checkpoint).join();sinceCheckpoint=0;}});
    }
    private CompletableFuture<Void> submit(Runnable task){CompletableFuture<Void> result=new CompletableFuture<>();if(closed||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("developer_block_storage_unavailable"));try{owner.execute(()->{try{if(!storageHealthy())throw new IllegalStateException("developer_block_storage_unavailable");task.run();result.complete(null);}catch(Throwable e){if(!journal.healthy())failed=true;result.completeExceptionally(e instanceof CompletionException&&e.getCause()!=null?e.getCause():e);}});}catch(RejectedExecutionException e){result.completeExceptionally(new IllegalStateException("developer_block_queue_full",e));}return result;}
    private synchronized int rows(){return blocks.size();}
    /** Capture takes no getter lock; a bounded merge keeps the original global XYZ order. */
    public szserver.util.JsonDocument.Rows snapshotRows(){Published captured=published;return szserver.util.JsonDocument.rows(()->new Iterator<>(){
        final class Part {final Iterator<Map.Entry<Position,String>> rows;Map.Entry<Position,String> current;Part(NavigableMap<Position,String> values){rows=values.entrySet().iterator();current=rows.next();}}
        final PriorityQueue<Part> queue=new PriorityQueue<>(Comparator.comparing(part->part.current.getKey()));
        {for(var values:captured.parts())if(!values.isEmpty())queue.add(new Part(values));}
        public boolean hasNext(){return !queue.isEmpty();}
        public JsonObject next(){if(queue.isEmpty())throw new NoSuchElementException();Part part=queue.remove();var current=part.current;if(part.rows.hasNext()){part.current=part.rows.next();queue.add(part);}return row(current.getKey(),current.getValue());}
    });}
    public JsonArray snapshot(){return new szserver.util.JsonDocument(Map.of("blocks",snapshotRows())).materialize().getAsJsonArray("blocks");}
    public synchronized String get(Position p){return blocks.get(p);}
    /** Mutation callbacks run only on the journal owner. Do not hold the live getter lock while writing. */
    private void checkpoint(Journal.CheckpointSink sink)throws Exception {for(var entry:blocks.entrySet())sink.accept(event(entry.getKey(),entry.getValue()));}
    public CompletableFuture<Void> compact(){return submit(()->{journal.compactStreaming(this::checkpoint).join();sinceCheckpoint=0;});}
    private boolean storageHealthy(){return !failed&&journal.healthy();}
    public boolean healthy(){return !closed&&storageHealthy();}
    public Map<String,Object> metrics(){Published captured=published;return Map.of("healthy",healthy(),"rows",captured.count(),"bytes",captured.bytes(),"rowLimit",ROW_LIMIT,"byteLimit",BYTE_LIMIT,"queued",owner.getQueue().size(),"journal",journal.metrics(),"streamedSnapshot",true,"snapshotPartitions",256);}
    @Override public void close()throws Exception{synchronized(closeLock){if(released)return;closed=true;try{Workers.stop(owner);}finally{if(owner.isTerminated())try{journal.close();}finally{synchronized(this){blocks.clear();bytes=0;published=new Published(Collections.nCopies(256,Collections.emptyNavigableMap()),0,0,operations);}released=true;}}}}
}
