package szserver.persistence;

import com.google.gson.*;
import szserver.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Ordered world-event outbox. A source fsync reference commits a reserved row; one lease keeps
 * cross-store publication ordered. Only the event worker writes; cursor pages use a captured disk index. */
public final class WorldEventStore implements AutoCloseable {
    public record Ref(long sequence,long revision,String source,String sha256){
        public Ref{if(sequence<1||revision<1||!Set.of("map","environment").contains(source)||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("world_event_reference");}
        public JsonObject json(){return Json.tree(Map.of("sequence",sequence,"revision",revision,"source",source,"sha256",sha256));}
        public static Ref read(JsonObject value){if(!value.keySet().equals(Set.of("sequence","revision","source","sha256")))throw new IllegalArgumentException("world_event_reference");return new Ref(integer(value,"sequence",1),integer(value,"revision",1),Json.text(value,"source",32),Json.text(value,"sha256",64));}
    }
    private record Pending(Ref ref,JsonObject row){}
    private record Request(String source,String type,JsonObject payload,long tick,CompletableFuture<Ref> future){}
    private record Published(long revision,long head,long oldest,WorldEventRows.Snapshot rows){}
    private final ThreadPoolExecutor worker=Workers.pool("world-events",1,64);
    private final ArrayDeque<Request> waiting=new ArrayDeque<>();
    private final Journal journal;private final WorldEventRows rows;private final String identity;private final LongSupplier ticks;
    private Pending pending;private long allocation,revision,head,oldest=1,maximumRowRevision;private boolean seeded,seedHead;
    private volatile Published visible;private volatile boolean failed,closed;private volatile String failure="";
    private final long compactionGrowthBytes;private volatile long journalBytes;
    private long lastCompactionAttemptBytes,compactions,compactionFailures;
    private volatile Map<String,Object> maintenance=Map.of("status","idle");
    public WorldEventStore(Path data,String identity,LongSupplier ticks)throws Exception{
        this(data,identity,ticks,64L*1024*1024);
    }
    /** A smaller growth threshold is useful for isolated capacity/recovery scenarios. */
    public WorldEventStore(Path data,String identity,LongSupplier ticks,long compactionGrowthBytes)throws Exception{
        if(compactionGrowthBytes<4096||compactionGrowthBytes>1024L*1024*1024)throw new IllegalArgumentException("world_event_compaction_growth");this.compactionGrowthBytes=compactionGrowthBytes;
        this.identity=Objects.requireNonNull(identity);if(identity.isBlank()||identity.length()>4096)throw new IllegalArgumentException("world_identity_required");this.ticks=Objects.requireNonNull(ticks);
        String key=hash(identity.getBytes(StandardCharsets.UTF_8));rows=new WorldEventRows(data);Journal opened=null;
        try{opened=new Journal(data.resolve("world-events-"+key+".sjournal"),0,this::recover);journal=opened;journalBytes=Files.size(journal.path());publish();}
        catch(Exception|Error error){worker.shutdownNow();rows.close();if(opened!=null)opened.close();throw error;}
    }
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hash(JsonObject value){return hash(new GsonBuilder().disableHtmlEscaping().serializeNulls().create().toJson(value).getBytes(StandardCharsets.UTF_8));}
    private static long integer(JsonObject row,String key,long min){try{var value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=value.getAsBigDecimal().longValueExact();if(n<min)throw new ArithmeticException();return n;}catch(ArithmeticException error){throw new IllegalArgumentException("world_event_integer:"+key,error);}}
    public static JsonObject validateRow(JsonObject row){if(!row.keySet().equals(Set.of("sequence","revision","serverTick","type","payload")))throw new IllegalArgumentException("world_event_row");integer(row,"sequence",1);integer(row,"revision",0);integer(row,"serverTick",0);if(Json.text(row,"type",64).isEmpty()||!row.get("payload").isJsonObject()||WorldEventCodec.bytes(row).length>47000)throw new IllegalArgumentException("world_event_row");return row;}
    private JsonObject envelope(String operation){return Json.tree(Map.of("type",operation,"world",identity));}
    private void recover(JsonObject event){if(!Json.text(event,"world",4096).equals(identity))throw new IllegalArgumentException("world_event_namespace");String type=Json.text(event,"type",32);
        switch(type){
            case "world_event_seed"->{if(seedHead||pending!=null||allocation!=0)throw new IllegalArgumentException("world_event_seed_order");for(var value:WorldEventCodec.decode(event.get("rowsData"))){JsonObject row=validateRow(value.getAsJsonObject());long seq=integer(row,"sequence",1);if(seq<=head)throw new IllegalArgumentException("world_event_seed_order");if(rows.capture().count()==0)oldest=seq;rows.append(List.of(row),false);head=seq;maximumRowRevision=Math.max(maximumRowRevision,integer(row,"revision",0));}seeded=true;}
            case "world_event_head"->{if(pending!=null)throw new IllegalArgumentException("world_event_head_pending");long nextRevision=integer(event,"revision",0),nextHead=integer(event,"head",0),nextAllocation=integer(event,"allocation",0);if(nextRevision<revision||nextRevision<maximumRowRevision||nextHead<head||nextAllocation<nextHead||nextAllocation<allocation)throw new IllegalArgumentException("world_event_head_order");revision=nextRevision;head=nextHead;allocation=nextAllocation;seedHead=true;}
            case "world_event_prepare"->{Ref ref=Ref.read(event.getAsJsonObject("reference"));JsonObject row=validateRow(WorldEventCodec.decode(event.get("rowData")).get(0).getAsJsonObject());if(pending!=null||ref.sequence()!=Math.addExact(allocation,1)||ref.revision()!=Math.addExact(revision,1)||!hash(row).equals(ref.sha256())||integer(row,"sequence",1)!=ref.sequence()||integer(row,"revision",1)!=ref.revision())throw new IllegalArgumentException("world_event_prepare_order");pending=new Pending(ref,row);allocation=ref.sequence();seedHead=true;}
            case "world_event_commit","world_event_abort"->{Ref ref=Ref.read(event.getAsJsonObject("reference"));if(pending==null||!pending.ref().equals(ref))throw new IllegalArgumentException("world_event_commit_reference");if(type.equals("world_event_commit")){if(rows.capture().count()==0)oldest=ref.sequence();rows.append(List.of(pending.row()),false);head=ref.sequence();revision=ref.revision();}pending=null;}
            default->throw new IllegalArgumentException("world_event_journal_type");
        }
    }
    private void publish(){visible=new Published(revision,head,oldest,rows.capture());}
    private void append(JsonObject event)throws Exception{journal.append(event).get();journalBytes=Math.addExact(journalBytes,8L+Json.bytes(event).length);recover(event);publish();}
    private interface Operation<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> enqueue(Operation<T> operation){var result=new CompletableFuture<T>();if(closed||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("world_events_unavailable"));try{worker.execute(()->{try{if(!healthy())throw new IllegalStateException("world_events_unavailable");result.complete(operation.run());}catch(Throwable error){Throwable cause=error instanceof ExecutionException?error.getCause():error;if(!(cause instanceof IllegalArgumentException)&&!(cause instanceof RejectedExecutionException)){failed=true;failure=cause.getClass().getSimpleName();}result.completeExceptionally(cause);}});}catch(RejectedExecutionException full){result.completeExceptionally(full);}return result;}
    public CompletableFuture<Ref> prepare(String source,String type,JsonObject payload){if(!Set.of("map","environment").contains(source)||type.isBlank()||type.length()>64)return CompletableFuture.failedFuture(new IllegalArgumentException("world_event_request"));if(Json.bytes(payload).length>44000)return CompletableFuture.failedFuture(new IllegalArgumentException("world_event_row_limit"));var result=new CompletableFuture<Ref>();Request request=new Request(source,type,payload.deepCopy(),Math.max(0,ticks.getAsLong()),result);enqueue(()->{if(waiting.size()>=64)throw new RejectedExecutionException("world_event_queue_full");waiting.addLast(request);grant();return true;}).whenComplete((ignored,error)->{if(error!=null)result.completeExceptionally(error);});return result;}
    private void grant()throws Exception{if(pending!=null||waiting.isEmpty())return;Request request=waiting.removeFirst();try{long seq=Math.addExact(allocation,1),rev=Math.addExact(revision,1);JsonObject row=Json.tree(Map.of("sequence",seq,"revision",rev,"serverTick",request.tick(),"type",request.type()));row.add("payload",request.payload().deepCopy());if(request.type().equals("world_environment_changed")){row.getAsJsonObject("payload").addProperty("worldRevision",rev);row.getAsJsonObject("payload").addProperty("serverTick",request.tick());if(!row.getAsJsonObject("payload").has("weatherExpired"))row.getAsJsonObject("payload").addProperty("eventSequence",head);}validateRow(row);Ref ref=new Ref(seq,rev,request.source(),hash(row));JsonObject event=envelope("world_event_prepare");event.add("reference",ref.json());event.add("rowData",WorldEventCodec.single(row));append(event);request.future().complete(ref);}catch(Throwable error){request.future().completeExceptionally(error);throw error;}}
    private void resolved(Ref ref,boolean commit)throws Exception{if(pending==null||!pending.ref().equals(ref)){if(commit&&ref.sequence()<=head){var page=rows.after(visible.rows(),ref.sequence()-1,1);if(!page.isEmpty()&&integer(page.get(0),"sequence",1)==ref.sequence()&&hash(page.get(0)).equals(ref.sha256()))return;}throw new IllegalArgumentException("world_event_reference_not_pending");}JsonObject event=envelope(commit?"world_event_commit":"world_event_abort");event.add("reference",ref.json());append(event);compactAutomatic();grant();}
    public CompletableFuture<Void> commit(Ref ref){return enqueue(()->{resolved(ref,true);return null;});}
    public CompletableFuture<Void> abort(Ref ref){return enqueue(()->{resolved(ref,false);return null;});}
    /** Source journals are authoritative even when the process stopped before the outbox commit. */
    public void reconcile(JsonObject sourceRecord,String source)throws Exception{if(!sourceRecord.has("worldEvent"))return;Ref ref=Ref.read(sourceRecord.getAsJsonObject("worldEvent"));if(!ref.source().equals(source))throw new IllegalArgumentException("world_event_source_reference");commit(ref).get();}
    public CompletableFuture<Void> finishRecovery(){return enqueue(()->{if(pending!=null)resolved(pending.ref(),false);return null;});}
    /** Upgrade body-less Java baselines without inventing historical request flags. */
    public CompletableFuture<Void> adoptLegacyHead(long rev,long sequence){return enqueue(()->{if(rev<=revision&&sequence<=head)return null;if(pending!=null)throw new IllegalArgumentException("world_event_legacy_head_pending");JsonObject event=envelope("world_event_head");event.addProperty("revision",Math.max(revision,rev));event.addProperty("head",Math.max(head,sequence));event.addProperty("allocation",Math.max(allocation,sequence));append(event);return null;});}
    public CompletableFuture<Void> seedBatch(JsonObject batch){JsonObject value=batch.deepCopy();value.addProperty("world",identity);return enqueue(()->{if(!Json.text(value,"type",32).equals("world_event_seed")||seedHead||pending!=null||allocation!=0)throw new IllegalArgumentException("world_event_seed_order");long previous=head;for(var element:WorldEventCodec.decode(value.get("rowsData"))){JsonObject row=validateRow(element.getAsJsonObject());long next=integer(row,"sequence",1);if(next<=previous)throw new IllegalArgumentException("world_event_seed_order");previous=next;}append(value);return null;});}
    public CompletableFuture<Void> seedHead(long rev,long sequence,long allocated){return enqueue(()->{if(pending!=null||rev<revision||rev<maximumRowRevision||sequence<head||allocated<sequence||allocated<allocation)throw new IllegalArgumentException("world_event_head_order");JsonObject event=envelope("world_event_head");event.addProperty("revision",rev);event.addProperty("head",sequence);event.addProperty("allocation",allocated);append(event);return null;});}
    public Map<String,Object> page(long after,int limit){if(after<0||limit<1||limit>4096)throw new IllegalArgumentException("world_event_cursor");Published captured=visible;long first=captured.rows().count()==0&&captured.head()>0?captured.head()==Long.MAX_VALUE?Long.MAX_VALUE:captured.head()+1:captured.oldest();return Map.of("worldRevision",captured.revision(),"eventSequence",captured.head(),"oldestSequence",first,"cursorExpired",after!=0&&after<first-1,"events",rows.after(captured.rows(),after,limit));}
    public JsonPages.Spec pageSpec(long after,int limit){
        if(after<0||limit<1||limit>4096)throw new IllegalArgumentException("world_event_cursor");Published captured=visible;long first=captured.rows().count()==0&&captured.head()>0?captured.head()==Long.MAX_VALUE?Long.MAX_VALUE:captured.head()+1:captured.oldest();
        return new JsonPages.Spec(Map.of("worldRevision",captured.revision(),"eventSequence",captured.head(),"oldestSequence",first,"cursorExpired",after!=0&&after<first-1),"events",rows.afterRows(captured.rows(),after,limit),ignored->Map.of());
    }
    public long revision(){return visible.revision();}public long head(){return visible.head();}
    private void seedPart(Journal.CheckpointSink sink,JsonArray batch)throws Exception{JsonObject event=envelope("world_event_seed");event.add("rowsData",WorldEventCodec.encode(batch));sink.accept(event);}
    private static int nodes(JsonElement value){int count=1;if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet())count+=nodes(entry.getValue());else if(value.isJsonArray())for(var item:value.getAsJsonArray())count+=nodes(item);return count;}
    /** Keep every committed row, allocation gap and any reserved-but-unresolved source receipt. */
    private void checkpoint(Journal.CheckpointSink sink)throws Exception{
        JsonArray batch=new JsonArray();int bytes=11,nodeCount=2;
        int rawLimit=Math.min(48000,(65536-Json.bytes(envelope("world_event_seed")).length-128)/4*3);
        for(JsonObject row:rows.rows(visible.rows())){
            int size=WorldEventCodec.bytes(row).length,rowNodes=nodes(row);
            if(!batch.isEmpty()&&(batch.size()==128||bytes+size+1>rawLimit||nodeCount+rowNodes>4096)){seedPart(sink,batch);batch=new JsonArray();bytes=11;nodeCount=2;}
            bytes+=size+(batch.isEmpty()?0:1);nodeCount+=rowNodes;batch.add(row);
        }
        if(!batch.isEmpty())seedPart(sink,batch);
        JsonObject state=envelope("world_event_head");state.addProperty("revision",revision);state.addProperty("head",head);state.addProperty("allocation",pending==null?allocation:pending.ref().sequence()-1);sink.accept(state);
        if(pending!=null){JsonObject reserved=envelope("world_event_prepare");reserved.add("reference",pending.ref().json());reserved.add("rowData",WorldEventCodec.single(pending.row()));sink.accept(reserved);}
    }
    private Map<String,Object> compactNow(boolean automatic)throws Exception{
        long started=System.nanoTime(),before=journalBytes;lastCompactionAttemptBytes=before;maintenance=Map.of("status","compacting","automatic",automatic);
        try{journal.compactStreaming(this::checkpoint).get();journalBytes=Files.size(journal.path());lastCompactionAttemptBytes=journalBytes;compactions++;
            maintenance=Map.of("status","compacted","automatic",automatic,"beforeBytes",before,"afterBytes",journalBytes,"retainedRows",visible.rows().count(),"reservedReceipt",pending!=null,"durationMs",(System.nanoTime()-started)/1e6,"compactions",compactions,"failures",compactionFailures);
        }catch(Exception error){compactionFailures++;maintenance=Map.of("status","compact_failed","automatic",automatic,"error",error.getClass().getSimpleName(),"failures",compactionFailures);throw error;}
        return maintenance;
    }
    private void compactAutomatic()throws Exception{
        if(journalBytes-lastCompactionAttemptBytes<compactionGrowthBytes||journalBytes<=rows.capture().bytes()*2)return;
        try{compactNow(true);}catch(Exception error){if(!journal.healthy()||!rows.healthy())throw error;}
    }
    /** The event worker serializes capture and publication with prepare/commit; caller cancellation is isolated. */
    public CompletableFuture<Map<String,Object>> compact(){
        var result=new CompletableFuture<Map<String,Object>>();if(closed||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("world_events_unavailable"));
        try{worker.execute(()->{try{if(!healthy())throw new IllegalStateException("world_events_unavailable");result.complete(compactNow(false));}catch(Throwable error){if(!journal.healthy()||!rows.healthy()){failed=true;failure=error.getClass().getSimpleName();}result.completeExceptionally(error);}});}catch(RejectedExecutionException full){result.completeExceptionally(full);}
        return result.copy();
    }
    public Map<String,Object> maintenance(){return maintenance;}
    public boolean healthy(){return !closed&&!failed&&rows.healthy()&&(journal==null||journal.healthy());}
    public Map<String,Object> metrics(){Published state=visible;return Map.of("healthy",healthy(),"revision",state.revision(),"eventSequence",state.head(),"rows",state.rows().count(),"bytes",state.rows().bytes(),"historyIndex",rows.metrics(),"failure",failure,"journalBytes",journalBytes,"compactionGrowthBytes",compactionGrowthBytes,"maintenance",maintenance);}
    @Override public void close()throws Exception{if(closed)return;closed=true;try{Workers.stop(worker);for(Request request:waiting)request.future().completeExceptionally(new IllegalStateException("world_events_closed"));}finally{try{journal.close();}finally{rows.close();}}}
}
