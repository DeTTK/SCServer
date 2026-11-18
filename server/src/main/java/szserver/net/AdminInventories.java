package szserver.net;

import com.google.gson.JsonObject;
import szserver.commands.PlayerInventories;
import szserver.inventory.*;
import szserver.util.Workers;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded administrative bridge. All transport-owned state is accessed via the owner's queue. */
public final class AdminInventories implements PlayerInventories,AutoCloseable {
    private record Committed(AdminGiveReceipt receipt,Inventory.Plan plan){}
    private final GameInventory inventories;private final Predicate<UUID> known;
    private final Consumer<Runnable> owner;private final BiConsumer<UUID,Inventory.Plan> sync;
    private final AdminGiveReceipts receipts;
    private final ThreadPoolExecutor worker=Workers.pool("admin-inventory",1,32);
    private volatile boolean closing,failed;
    public AdminInventories(Path data,GameInventory inventories,Predicate<UUID> known,Consumer<Runnable> owner,BiConsumer<UUID,Inventory.Plan> sync)throws Exception{
        this.inventories=inventories;this.known=known;this.owner=owner;this.sync=sync;
        receipts=new AdminGiveReceipts(data);
        try{inventories.repairAdminReceipts(receipts);}catch(Exception|Error error){receipts.close();throw error;}
    }
    @Override public JsonObject receipt(String id){AdminGiveReceipt r=receipts.find(id);return r==null?null:r.event();}
    @Override public CompletableFuture<szserver.world.ActorEquipment> actorEquipment(List<Integer> worn,Integer held,boolean encumbered){if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("admin_inventory_unavailable"));return inventories.actorEquipment(worn,held,encumbered);}
    @Override public List<JsonObject> inventory(UUID id){if(!known.test(id))throw new IllegalArgumentException("character_not_found");return inventories.durableInventory(id);}
    @Override public CompletableFuture<JsonObject> give(JsonObject body,String fingerprint){
        AdminGive request=AdminGive.read(body);CompletableFuture<JsonObject> result=new CompletableFuture<>();
        if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("admin_inventory_unavailable"));
        try{worker.execute(()->{try{
            if(!healthy())throw new IllegalStateException("admin_inventory_unavailable");
            AdminGiveReceipt existing=receipts.find(request.commandId());
            if(existing!=null){if(!existing.fingerprint().equals(fingerprint))throw new IllegalArgumentException("command_id_reused_with_different_request");result.complete(existing.event());return;}
            if(!known.test(request.character()))throw new IllegalArgumentException("character_not_found");
            try(var lease=inventories.retainDurable(request.character())){lease.join();
            inventories.ensureDefaults(request.character()).get();
            AdminGiveReceipt previous=inventories.adminPending(request.character());if(previous!=null)receipts.record(previous);
            CompletableFuture<Committed> committed=new CompletableFuture<>();
            owner.accept(()->{try{inventories.adminGive(request,fingerprint,previous,(r,p)->committed.complete(new Committed(r,p)),error->committed.completeExceptionally(inventories.healthy()?new IllegalArgumentException(error):new IllegalStateException("inventory_save_failed")));}catch(RuntimeException error){committed.completeExceptionally(error);}});
            Committed granted=committed.get();receipts.record(granted.receipt());
            CompletableFuture<Void> published=new CompletableFuture<>();owner.accept(()->{try{sync.accept(request.character(),granted.plan());published.complete(null);}catch(Throwable error){published.completeExceptionally(error);}});published.get();
            result.complete(granted.receipt().event());
            }
        }catch(Throwable error){Throwable cause=error;while((cause instanceof CompletionException||cause instanceof ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();if(cause instanceof IllegalStateException&&"character_deleting".equals(cause.getMessage()))cause=new IllegalArgumentException("character_deleting",cause);if(!(cause instanceof IllegalArgumentException)&&!(cause instanceof RejectedExecutionException))failed=true;result.completeExceptionally(cause);}});}
        catch(RejectedExecutionException full){result.completeExceptionally(new RejectedExecutionException("admin_inventory_queue_full"));}
        // A caller cancelling/completing its copy cannot cancel an accepted durable mutation.
        return result.copy();
    }
    @Override public boolean healthy(){return !failed&&receipts.healthy()&&inventories.healthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"closing",closing,"queued",worker.getQueue().size(),"receipts",receipts.metrics());}
    /** Drain accepted work while the game owner is still running. No new admission. */
    @Override public void close()throws Exception{closing=true;try{Workers.stop(worker);}finally{receipts.close();}}
}
