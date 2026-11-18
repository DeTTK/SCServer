package szserver.net;

import szserver.inventory.*;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Inventory-owner bridge. Admission precedes the durable decrement; activation follows it.
 * drain() is called by the same owner as GameInventory.drain(), never by a world/disk thread. */
public final class GameGrenades {
    private static final class Pending {
        final long started=System.nanoTime();long admitted,debit,persisted,committed;UUID reservation;boolean cancel,inFlight,held;volatile boolean worldReleased;long retryAt;final Consumer<UUID> success;final Consumer<String> failure;
        CompletableFuture<UUID> admission;boolean admissionChecking;long admissionUntil;
        Pending(Consumer<UUID> success,Consumer<String> failure){this.success=success;this.failure=failure;}
    }
    private final GameInventory inventory;private final WorldLoop world;private final GrenadeOutbox outbox;private final GrenadeAreaStore areas;private final Map<Integer,List<GrenadeAreaProfile>> areaProfiles;
    private final Set<UUID> busy=new HashSet<>();private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    private final ArrayBlockingQueue<Runnable> completions=new ArrayBlockingQueue<>(256);
    private volatile boolean failed;private long activated,rejected,retries;
    private final ArrayDeque<Map<String,Object>> launchTimings=new ArrayDeque<>();
    private void timing(Pending p){long end=System.nanoTime();if(launchTimings.size()==32)launchTimings.removeFirst();launchTimings.addLast(Map.of("totalMs",(end-p.started)/1e6,"geometryMs",(p.admitted-p.started)/1e6,"prepareMs",(p.debit-p.admitted)/1e6,"inventoryMs",(p.persisted-p.debit)/1e6,"outboxMs",(p.committed-p.persisted)/1e6,"activateMs",(end-p.committed)/1e6));}
    public GameGrenades(GameInventory inventory,WorldLoop world){this(inventory,world,null);}
    public GameGrenades(GameInventory inventory,WorldLoop world,GrenadeOutbox outbox){this(inventory,world,outbox,null,Map.of());}
    public GameGrenades(GameInventory inventory,WorldLoop world,GrenadeOutbox outbox,GrenadeAreaStore areas,Map<Integer,List<GrenadeAreaProfile>> profiles){this.inventory=inventory;this.world=world;this.outbox=outbox;this.areas=areas;Map<Integer,List<GrenadeAreaProfile>> copy=new HashMap<>();profiles.forEach((item,p)->copy.put(item,List.copyOf(p)));this.areaProfiles=Map.copyOf(copy);if(!copy.isEmpty()&&(areas==null||outbox==null))throw new IllegalArgumentException("grenade_areas_need_durable_outbox");}
    private void completed(Runnable action){if(!completions.offer(action))failed=true;}
    public void throwItem(UUID character,Inventory.Address address,Grenades.Flight flight,World.Vec origin,World.Vec velocity,Consumer<UUID> success,Consumer<String> failure){
        throwItem(character,character.toString(),address,flight,origin,velocity,success,failure);
    }
    public void throwItem(UUID character,String name,Inventory.Address address,Grenades.Flight flight,World.Vec origin,World.Vec velocity,Consumer<UUID> success,Consumer<String> failure){
        throwItem(character,name,address,flight,origin,velocity,false,success,failure);
    }
    public void throwItem(UUID character,String name,Inventory.Address address,Grenades.Flight flight,World.Vec origin,World.Vec velocity,boolean held,Consumer<UUID> success,Consumer<String> failure){
        throwItem(character,name,address,flight,origin,velocity,held,null,success,failure);
    }
    public void throwItem(UUID character,String name,Inventory.Address address,Grenades.Flight flight,World.Vec origin,World.Vec velocity,boolean held,AreaRelations.Actor ownerContext,Consumer<UUID> success,Consumer<String> failure){
        if(!inventory.admissionAllowed(character)||inventory.busy(character)){failure.accept("grenade_busy");return;}
        ItemStack item=inventory.inventory(character).at(address);
        if(address.aggregate()!=0||address.container()!=4||item==null||!inventory.explosives().containsKey(item.id())){failure.accept("grenade_not_equipped");return;}
        debit(character,name,address,item,item.count(item.count()-1),item.id(),flight,origin,velocity,held,ownerContext,success,failure);
    }
    /** The weapon's prepared native state and its projectile share one durable inventory receipt. */
    public void launchMagazine(UUID character,String name,Inventory.Address address,ItemStack expected,byte[] nextState,int ammunition,Grenades.Flight flight,World.Vec origin,World.Vec velocity,AreaRelations.Actor ownerContext,Consumer<UUID> success,Consumer<String> failure){
        if(address.aggregate()!=0||(address.container()!=6&&address.container()!=7)||expected==null||!inventory.explosives().containsKey(ammunition)){failure.accept("launcher_not_equipped");return;}
        debit(character,name,address,expected,expected.state(nextState),ammunition,flight,origin,velocity,false,ownerContext,success,failure);
    }
    private void debit(UUID character,String name,Inventory.Address address,ItemStack item,ItemStack replacement,int ammunition,Grenades.Flight flight,World.Vec origin,World.Vec velocity,boolean held,AreaRelations.Actor ownerContext,Consumer<UUID> success,Consumer<String> failure){
        if(ownerContext!=null&&!ownerContext.character().equals(character)){failure.accept("grenade_owner_context");return;}
        if(failed||!inventory.admissionAllowed(character)||busy.contains(character)||inventory.busy(character)||pending.size()>=Grenades.LIMIT){failure.accept("grenade_busy");return;}
        ExplosiveProfile profile=inventory.explosives().get(ammunition);
        Pending operation=new Pending(success,failure);operation.held=held;busy.add(character);pending.put(character,operation);inventory.activate(character);
        world.submit(w->{UUID id=w.grenades().reserve(character.toString(),profile,flight,origin,velocity);w.grenades().pin(id);return id;}).thenCompose(id->geometryAdmission(operation,id)).whenComplete((id,error)->completed(()->{
            if(error!=null){if(operation.reservation==null){pending.remove(character);busy.remove(character);inventory.deactivate(character);}else release(character,operation,true);rejected++;failure.accept(!inventory.admissionAllowed(character)?"character_deleting":"grenade_admission_failed");return;}
            operation.reservation=id;operation.admitted=System.nanoTime();
            if(!inventory.admissionAllowed(character)){release(character,operation,true);failure.accept("character_deleting");return;}
            GrenadeReceipt previous=outbox==null?null:inventory.grenadePending(character);
            List<GrenadeAreaProfile> selectedAreas=areaProfiles.getOrDefault(ammunition,List.of());
            CompletableFuture<Void> prepared=(selectedAreas.isEmpty()?CompletableFuture.completedFuture(true):areas.reserve(id,selectedAreas.size())).thenCompose(admitted->{if(!admitted)return CompletableFuture.failedFuture(new IllegalStateException("grenade_area_capacity"));return outbox==null?CompletableFuture.completedFuture(null):outbox.prepare(previous);});
            prepared.whenComplete((ignored,prepareError)->completed(()->{
                if(prepareError!=null){release(character,operation,true);failure.accept("grenade_outbox_prepare_failed");return;}
                if(!inventory.admissionAllowed(character)){release(character,operation,true);failure.accept("character_deleting");return;}
                if(outbox!=null&&!Objects.equals(previous,inventory.grenadePending(character))){release(character,operation,true);failure.accept("grenade_receipt_stale");return;}
                long revision=inventory.inventory(character).revision();GrenadeReceipt receipt=new GrenadeReceipt(character,id,name,ammunition,flight,origin,velocity,revision,revision+1,held,selectedAreas,ownerContext);
                operation.debit=System.nanoTime();inventory.mutate(character,before->{
                    if(!item.equals(before.at(address))||before.revision()!=revision)throw new IllegalArgumentException("grenade_inventory_changed");
                    Map<Inventory.Address,ItemStack> changes=new HashMap<>();changes.put(address,replacement);return before.replace(changes,1);
                },outbox==null?null:receipt.progress(),plan->{
                    operation.persisted=System.nanoTime();if(outbox==null){operation.committed=operation.persisted;release(character,operation,false);return;}
                    outbox.committed(receipt).whenComplete((value,commitError)->completed(()->{if(commitError!=null){failed=true;failure.accept("grenade_outbox_commit_failed");return;}operation.committed=System.nanoTime();release(character,operation,false);}));
                },message->{release(character,operation,true);failure.accept(message);});
            }));
        }));
    }
    private CompletableFuture<UUID> geometryAdmission(Pending operation,UUID id){
        CompletableFuture<UUID> result=new CompletableFuture<>();completed(()->{operation.reservation=id;operation.admission=result;operation.admissionUntil=System.nanoTime()+5_000_000_000L;});return result;
    }
    private void pollAdmission(UUID character,Pending p,long now){
        if(p.admission==null||p.admission.isDone())return;
        if(!inventory.admissionAllowed(character)||now>=p.admissionUntil){p.retryAt=0;p.admission.completeExceptionally(new IllegalStateException("grenade_geometry_admission_cancelled"));return;}
        if(p.admissionChecking||now<p.retryAt)return;
        p.admissionChecking=true;
        record Probe(boolean ready,CompletableFuture<?> preparation){}
        world.submit(w->{boolean ready=w.grenades().admitted(p.reservation);return new Probe(ready,ready?null:w.grenades().preparation(p.reservation));}).whenComplete((probe,error)->completed(()->{
            p.admissionChecking=false;if(p.admission.isDone())return;
            if(error!=null){p.retryAt=0;p.admission.completeExceptionally(error);}
            else if(probe.ready()){p.retryAt=0;p.admission.complete(p.reservation);}
            else{p.retryAt=System.nanoTime()+50_000_000L;
                // Completion only schedules owner work. Collision validation stays on the world owner.
                if(probe.preparation()!=null){p.admissionChecking=true;probe.preparation().whenComplete((value,failure)->completed(()->{p.admissionChecking=false;p.retryAt=0;pollAdmission(character,p,System.nanoTime());}));}
            }
        }));
    }
    private void release(UUID character,Pending operation,boolean cancel){
        operation.cancel=cancel;operation.inFlight=true;
        (operation.worldReleased?CompletableFuture.completedFuture(true):world.submit(w->cancel?w.grenades().cancel(operation.reservation):operation.held?w.grenades().detonateHeld(operation.reservation):w.grenades().activate(operation.reservation))).thenCompose(ok->{if(!ok)return CompletableFuture.failedFuture(new IllegalStateException("grenade_reservation_lost"));operation.worldReleased=true;return cancel&&areas!=null?areas.cancel(operation.reservation).thenApply(ignored->true):CompletableFuture.completedFuture(true);}).whenComplete((ok,error)->completed(()->{
            operation.inFlight=false;
            if(error!=null){operation.retryAt=System.nanoTime()+50_000_000;retries++;return;}
            if(!ok)throw new IllegalStateException("grenade_reservation_lost");
            pending.remove(character);busy.remove(character);inventory.deactivate(character);if(!cancel){activated++;timing(operation);operation.success.accept(operation.reservation);}
        }));
    }
    public void drain(){
        if(failed)throw new IllegalStateException("grenade_completion_limit");
        for(int n=0;n<64;n++){Runnable action=completions.poll();if(action==null)break;action.run();}
        long now=System.nanoTime();for(var entry:new ArrayList<>(pending.entrySet())){Pending p=entry.getValue();pollAdmission(entry.getKey(),p,now);if((p.admission==null||p.admission.isDone())&&p.reservation!=null&&!p.inFlight&&p.retryAt!=0&&now>=p.retryAt)release(entry.getKey(),p,p.cancel);}
    }
    public int pending(){return pending.size();}
    public boolean busy(UUID character){return busy.contains(character);}
    public boolean healthy(){return !failed&&(outbox==null||outbox.healthy())&&(areas==null||areas.healthy());}
    public Map<String,Object> metrics(){return Map.of("pending",pending.size(),"activated",activated,"rejected",rejected,"activationRetries",retries,"failed",failed,"launchTimings",List.copyOf(launchTimings));}
}
