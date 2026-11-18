package szserver.net;

import szserver.persistence.CharacterLocations;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Admission fences/leases belong to the native owner; account outbox owns crash recovery. */
public final class CharacterDeletions implements AutoCloseable {
    private record Waiting(UUID id,long deadline,CompletableFuture<LocalAccounts.DeleteLease> result){}
    private final GameInventory inventory;
    private final CharacterLocations locations;
    private final Consumer<Runnable> owner;
    private final Consumer<UUID> retireSessions;
    private final Map<UUID,Waiting> waiting=new LinkedHashMap<>();
    private final Map<UUID,Lease> leased=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile long completed;private long denied;
    public CharacterDeletions(GameInventory inventory,CharacterLocations locations,Consumer<Runnable> owner,Consumer<UUID> retireSessions){this.inventory=inventory;this.locations=locations;this.owner=owner;this.retireSessions=retireSessions;}
    public CompletableFuture<LocalAccounts.DeleteLease> prepare(UUID id){
        CompletableFuture<LocalAccounts.DeleteLease> result=new CompletableFuture<>();
        try{owner.accept(()->{
            if(closed||waiting.size()+leased.size()>=64||waiting.containsKey(id)||leased.containsKey(id)){result.completeExceptionally(new IllegalStateException("character_delete_unavailable"));return;}
            try{retireSessions.accept(id);inventory.holdDeletion(id);locations.holdDeletion(id);waiting.put(id,new Waiting(id,System.nanoTime()+TimeUnit.SECONDS.toNanos(10),result));}
            catch(RuntimeException error){release(id);result.completeExceptionally(error);}
        });}catch(RuntimeException error){result.completeExceptionally(error);}return result.copy();
    }
    public void advance(){
        for(var it=waiting.values().iterator();it.hasNext();){Waiting job=it.next();
            if(!inventory.healthy()||!locations.healthy()||System.nanoTime()>job.deadline){it.remove();release(job.id);denied++;job.result.completeExceptionally(new IllegalStateException("character_delete_drain_failed"));continue;}
            if(!inventory.deletionReady(job.id)||!locations.deletionReady(job.id))continue;
            it.remove();if(inventory.hasDrops(job.id)){release(job.id);denied++;job.result.completeExceptionally(new IllegalArgumentException("character_has_world_drops"));continue;}
            Lease lease=new Lease(job.id);leased.put(job.id,lease);job.result.complete(lease);
        }
    }
    private void release(UUID id){inventory.releaseDeletion(id);locations.releaseDeletion(id);}
    private final class Lease implements LocalAccounts.DeleteLease {
        final UUID id;final AtomicBoolean committing=new AtomicBoolean();final CompletableFuture<Void> result=new CompletableFuture<>();
        Lease(UUID id){this.id=id;}
        public CompletableFuture<Void> commit(){
            if(committing.compareAndSet(false,true))try{owner.accept(()->{
                if(closed){result.completeExceptionally(new IllegalStateException("character_delete_closed"));return;}
                try{inventory.deleteHeld(id).thenCompose(ignored->locations.deleteCharacter(id)).whenComplete((ignored,error)->{if(error==null){completed++;result.complete(null);}else result.completeExceptionally(error);});}
                catch(RuntimeException error){result.completeExceptionally(error);}
            });}catch(RuntimeException error){result.completeExceptionally(error);}return result.copy();
        }
        public void release(){leased.remove(id,this);CharacterDeletions.this.release(id);}
    }
    public Map<String,Object> metrics(){return Map.of("waiting",waiting.size(),"leased",leased.size(),"completed",completed,"denied",denied,"limit",64);}
    @Override public void close(){closed=true;for(Waiting job:waiting.values()){release(job.id);job.result.completeExceptionally(new IllegalStateException("character_delete_closed"));}waiting.clear();for(Lease lease:List.copyOf(leased.values())){lease.result.completeExceptionally(new IllegalStateException("character_delete_closed"));lease.release();}}
}
