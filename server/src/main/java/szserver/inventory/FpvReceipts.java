package szserver.inventory;

import com.google.gson.JsonObject;
import szserver.commands.CommandHistory;
import szserver.persistence.Journal;
import szserver.util.Workers;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Canonical CRC/fsynced spent-request log with a rebuilt bounded cold index. No owner IO. */
public final class FpvReceipts implements AutoCloseable {
    public record Admission(UUID character,UUID drone,int slot,szserver.client.FpvPackets.Vector position,FpvReceipt previous){}
    private final Journal journal;private final CommandHistory index;private final ThreadPoolExecutor worker=Workers.pool("fpv-receipts",1,32);
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();private volatile boolean closed;private volatile Throwable failure;
    public FpvReceipts(Path data)throws Exception{
        worker();
        journal=Journal.streaming(data.resolve("fpv-spent.sjournal"));CommandHistory opened=null;
        try{opened=new CommandHistory(journal,1024*1024);index=opened;journal.replayLocated((reference,event)->{receipt(event);index.register(reference,event,false);}).get();}
        catch(Exception|Error error){if(opened!=null)opened.close();journal.close();throw error;}
    }
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("fpv_receipt_io_on_owner");}
    private static FpvReceipt receipt(JsonObject event){if(!event.get("type").getAsString().equals("fpv_placement_spent"))throw new IllegalArgumentException("fpv_receipt_event_type");FpvReceipt r=FpvReceipt.read(event.getAsJsonObject("receipt"));if(!r.key().equals(event.get("commandId").getAsString()))throw new IllegalArgumentException("fpv_receipt_key_mismatch");return r;}
    private FpvReceipt find(UUID character,UUID drone){worker();JsonObject event=index.raw(FpvReceipt.key(character,drone));if(event==null)return null;FpvReceipt found=receipt(event);if(!found.character().equals(character)||!found.drone().equals(drone))throw new IllegalArgumentException("fpv_receipt_identity_mismatch");return found;}
    private void record(FpvReceipt request)throws Exception{
        worker();FpvReceipt existing=find(request.character(),request.drone());if(existing!=null){if(!existing.equals(request))throw new IllegalArgumentException("fpv_receipt_conflict");return;}
        JsonObject event=new JsonObject();event.addProperty("commandId",request.key());event.addProperty("type","fpv_placement_spent");event.add("receipt",request.json());
        journal.appendLocated(event).thenAccept(reference->index.register(reference,event,false)).get();
    }
    @FunctionalInterface private interface Operation<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> submit(Operation<T> operation){
        CompletableFuture<T> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        if(closed||failure!=null){result.completeExceptionally(new IllegalStateException("fpv_receipts_unavailable",failure));return result;}
        try{worker.execute(()->{try{if(closed)throw new IllegalStateException("fpv_receipts_closed");result.complete(operation.run());}catch(Throwable error){Throwable cause=error;while(cause instanceof ExecutionException||cause instanceof CompletionException){if(cause.getCause()==null)break;cause=cause.getCause();}if(!(cause instanceof IllegalArgumentException)&&!(cause instanceof RejectedExecutionException))failure=cause;result.completeExceptionally(cause);}});}
        catch(RejectedExecutionException full){result.completeExceptionally(new IllegalArgumentException("fpv_receipts_queue_full"));}return result;
    }
    /** Finalize the last debit before any later placement may overwrite its inventory marker. */
    public CompletableFuture<Admission> prepare(UUID character,UUID drone,int slot,szserver.client.FpvPackets.Vector position,FpvReceipt previous){
        return submit(()->{if(previous!=null){if(!previous.character().equals(character))throw new IllegalArgumentException("fpv_receipt_character_mismatch");record(previous);}if(find(character,drone)!=null)throw new IllegalArgumentException("fpv_drone_identity_already_used");return new Admission(character,drone,slot,position,previous);});
    }
    public CompletableFuture<Void> committed(FpvReceipt request){return submit(()->{record(request);return null;});}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"queued",worker.getQueue().size(),"pending",pending.size(),"index",index.metrics(),"journal",journal.metrics());}
    public boolean healthy(){return !closed&&failure==null&&journal.healthy()&&index.healthy();}
    @Override public void close()throws Exception{try{Workers.stop(worker);}finally{closed=true;for(var result:pending)result.completeExceptionally(new IllegalStateException("fpv_receipts_closed"));try{journal.close();}finally{index.close();}}}
}
