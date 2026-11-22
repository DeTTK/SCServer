package szserver.net;

import szserver.client.GameItems;
import szserver.inventory.*;
import szserver.util.Workers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Character mutations are serialized, while disk and native construction stay off the owner. */
public final class GameInventory implements AutoCloseable {
    private static final class Character {
        final InventoryJournal.Lease lease;Inventory inventory;boolean busy,failed,deathClear,clearingEffects;Map<Integer,byte[]> appearances=Map.of();Map<Integer,UUID> identities=Map.of();Map<String,Float> stats=Map.of(),artifactStats=Map.of();
        GrenadeApplications applications;GrenadePulseEffects pulseEffects;
        Character(Inventory inventory,InventoryJournal.Lease lease){this.inventory=inventory;this.lease=lease;}
    }
    private final ItemCatalog catalog;
    private final LootboxCatalog lootboxes;
    private final Path equipmentRoot;
    private final InventoryJournal journal;
    private final GameItems items;private Map<Integer,List<GrenadeAreaProfile>> pulseProfiles=Map.of();private boolean pulseProfilesPrepared;
    private final Map<Integer,PhysicalReload.Mechanism> physicalMechanisms;
    private final Map<Integer,byte[]> defaultAppearances=new HashMap<>();
    private final Map<UUID,Character> characters=new LinkedHashMap<>(256,.75f,true);
    private final Map<UUID,Integer> activeCharacters=new HashMap<>();
    private static final int IDLE_LIMIT=256;private static final long IDLE_BYTES=32L*1024*1024;
    private volatile long idleBytes,evicted;
    private record DeathClear(Character character,szserver.persistence.CharacterLocations.PlayerState state,Consumer<VitalsReceipt> success,Consumer<String> failure,Set<UUID> live,Set<UUID> eligible,UUID death){}
    private final Map<UUID,DeathClear> pendingDeathClears=new LinkedHashMap<>();
    private record Expiration(Character character,long before){}
    private final Map<UUID,Expiration> pendingExpirations=new LinkedHashMap<>();
    private final ThreadPoolExecutor factories=Workers.pool("inventory-native",1,32);
    private final ArrayBlockingQueue<Runnable> completed=new ArrayBlockingQueue<>(128);
    private static final String DEFAULT_EQUIPMENT="default_equipment_v1";
    private static final String STARTER_REVISION="java_starter_eligible_revision";
    private record Initialization(UUID id,CompletableFuture<Void> future,InventoryJournal.Lease lease) {}
    private final ArrayBlockingQueue<Initialization> initializations=new ArrayBlockingQueue<>(128);
    // Only this small admission map is shared with HTTP. Character state stays on its owner.
    private final Map<UUID,CompletableFuture<Void>> pendingInitializations=new HashMap<>();
    private final Set<UUID> retiring=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean completionFailure;
    private long accepted,denied,callbackFailures;
    public interface CreativeGrants {boolean active(UUID character);CreativeDelivery prepare(UUID character,List<ItemStack> stacks,Integer slot);}
    public record CreativeDelivery(int firstSlot,Runnable publish){}
    private CreativeGrants creativeGrants;
    /** Bound by the game owner; native creative contents belong to the client's separate model. */
    public void bindCreativeGrants(CreativeGrants bridge){if(creativeGrants!=null)throw new IllegalStateException("creative_grants_already_bound");creativeGrants=Objects.requireNonNull(bridge);}
    private boolean creative(UUID id){return creativeGrants!=null&&creativeGrants.active(id);}
    private List<ItemStack> creativeCopies(ItemStack template,int count)throws Exception{
        int limit=catalog.get(template.id()).stackLimit(),pieces=(int)((count+(long)limit-1)/limit);if(pieces>84)throw new IllegalArgumentException("creative_inventory_full");
        List<ItemStack> stacks=new ArrayList<>(pieces);for(int remaining=count;remaining>0;remaining-=Math.min(limit,remaining))stacks.add(items.copyForGrant(template.count(Math.min(limit,remaining))));return List.copyOf(stacks);
    }
    private void finishCreative(UUID id,Character c,Inventory before,List<ItemStack> stacks,int count,Integer slot,AdminGive request,String fingerprint,Consumer<Inventory.Plan> success,Consumer<String> failure){
        try{
            if(!creative(id))throw new IllegalArgumentException("creative_player_not_available");
            CreativeDelivery delivery=creativeGrants.prepare(id,stacks,slot);Inventory.Plan plan=before.replace(Map.of(),count);
            JsonObject progress=request==null?null:new AdminGiveReceipt(request.commandId(),fingerprint,id,request.item(),count,request.metadata(),delivery.firstSlot(),before.revision(),plan.after().revision()).progress();
            // Only the administrative receipt/revision is durable. Never copy DEV grants into GAME.
            persist(id,c,plan,progress,p->{try{delivery.publish().run();success.accept(p);}catch(RuntimeException error){failure.accept(Objects.toString(error.getMessage(),"creative_delivery_failed"));}},failure);
        }catch(RuntimeException error){c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"creative_give_failed"));}
    }
    public record Weapon(int factory,int ammo,int capacity,int rounds,UUID identity,byte[][] magazines,WeaponProfile profile,GameItems.Launcher launcher,byte[][] launcherMagazines,GameItems.Jamming jamming) {}
    public GameInventory(Path root,Path data,Path runtime,Path registry)throws Exception {
        this(root,data,runtime,registry,0);
    }
    /** Isolated test fault injection, never set from the live configuration. */
    public GameInventory(Path root,Path data,Path runtime,Path registry,long testDelayMillis)throws Exception {
        equipmentRoot=root;
        catalog=new ItemCatalog(root.resolve("fixtures/item-definitions.json"));lootboxes=new LootboxCatalog(catalog,root);journal=new InventoryJournal(data.resolve("characters.sjournal"),catalog,testDelayMillis);items=new GameItems(runtime,registry);
        physicalMechanisms=PhysicalReload.catalogue(root.resolve("fixtures/vr-weapon-catalog.json"));
        items.warmGameplay(root.resolve("fixtures/item-definitions.json"),root.resolve("fixtures/artifact-settings.json"));
        for(var entry:JsonParser.parseString(java.nio.file.Files.readString(root.resolve("fixtures/equipment-appearances.json"))).getAsJsonObject().entrySet())defaultAppearances.put(Integer.parseInt(entry.getKey()),HexFormat.of().parseHex(entry.getValue().getAsString()));
        for(int id=1;id<65536;id++){ItemCatalog.Definition definition;try{definition=catalog.get(id);}catch(IllegalArgumentException absent){continue;}if(definition.type().equals("bullet"))items.registerStateless(id);}
    }
    public Inventory inventory(UUID id){return character(id).inventory;}
    /** Share the already warmed native catalog; never construct another player catalog for NPCs. */
    public CompletableFuture<szserver.world.ActorEquipment> actorEquipment(List<Integer> worn,Integer held,boolean encumbered){
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("inventory_closed"));List<Integer> copy=List.copyOf(worn);var future=new CompletableFuture<szserver.world.ActorEquipment>();
        try{factories.execute(()->{try{future.complete(items.actorEquipment(equipmentRoot,copy,held,encumbered));}catch(Throwable failure){future.completeExceptionally(failure);}});}catch(RejectedExecutionException failure){future.completeExceptionally(failure);}return future.copy();
    }
    public ItemCatalog catalog(){return catalog;}
    public LootboxCatalog lootboxes(){return lootboxes;}
    public record LootResult(Inventory.Plan plan,UUID receipt,List<ItemStack> rewards){}
    public void openLootboxes(UUID id,int box,int count,Consumer<LootResult> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
        try{lootboxes.available(box);if(count<1||count>500||LootboxOpening.count(c.inventory,box)<count)throw new IllegalArgumentException("lootbox_missing");if(lootboxes.key(box)!=-1&&LootboxOpening.count(c.inventory,lootboxes.key(box))<count)throw new IllegalArgumentException("lootbox_key_missing");}catch(IllegalArgumentException error){failure.accept(error.getMessage());return;}
        Inventory before=c.inventory;JsonObject saved=journal.gameplay(id),previous=saved.has("lootboxes")?saved.getAsJsonObject("lootboxes"):new JsonObject();c.busy=true;
        try{factories.execute(()->{try{
            String rank="newbie";List<String> ranks=List.of("picklock","newbie","stalker","veteran","master","legend");int highest=1;for(var e:before.items().entrySet())if(e.getKey().aggregate()==0){JsonObject definition=catalog.get(e.getValue().id()).properties();if(definition.has("rank"))highest=Math.max(highest,ranks.indexOf(definition.get("rank").getAsString().toLowerCase(Locale.ROOT)));}rank=ranks.get(highest);
            var draw=lootboxes.draw(box,count,rank,previous,java.util.concurrent.ThreadLocalRandom.current());
            var opened=LootboxOpening.plan(before,catalog,box,lootboxes.key(box),count,draw.prizes(),(prize,amount)->items.createStack(prize.id(),amount,prize.metadata(),prize.tags()));
            UUID receipt=UUID.randomUUID();JsonObject state=draw.progress(),last=new JsonObject();last.addProperty("receipt",receipt.toString());last.addProperty("item",box);last.addProperty("count",count);state.add("last",last);JsonObject progress=new JsonObject();progress.add("lootboxes",state);
            completion(()->{if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("lootbox_owner_changed");return;}persist(id,c,opened.plan(),progress,plan->success.accept(new LootResult(plan,receipt,opened.rewards())),failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"lootbox_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public boolean hasAppearanceCodec(int id)throws Exception{return items.hasAppearanceCodec(id);}
    public byte[] appearance(UUID id,int container){return character(id).appearances.get(container);}
    public Map<String,Float> stats(UUID id){return character(id).stats;}
    public Map<String,Float> artifactStats(UUID id){return character(id).artifactStats;}
    public UUID identity(UUID id,int container){return character(id).identities.getOrDefault(container,new UUID(0,0));}
    public JsonObject gameplay(UUID id){return journal.gameplay(id);}
    public void repairVitalsReceipts(szserver.persistence.CharacterLocations locations)throws Exception{journal.repairVitalsReceipts(locations);}
    public FpvReceipt fpvPending(UUID id){return journal.fpvPending(id);}
    public GrenadeReceipt grenadePending(UUID id){return journal.grenadePending(id);}
    public void repairGrenadeReceipts(GrenadeOutbox outbox)throws Exception{journal.repairGrenadeReceipts(outbox);}
    public AdminGiveReceipt adminPending(UUID id){return journal.adminPending(id);}
    public void repairAdminReceipts(AdminGiveReceipts receipts)throws Exception{journal.repairAdminReceipts(receipts);}
    public List<JsonObject> durableInventory(UUID id){Inventory snapshot=journal.load(id);return snapshot.items().entrySet().stream().map(entry->{JsonObject r=entry.getValue().json();r.addProperty("itemId",entry.getValue().id());r.addProperty("aggregate",entry.getKey().aggregate());r.addProperty("container",entry.getKey().container());r.addProperty("slot",entry.getKey().slot());r.addProperty("revision",snapshot.revision());return r;}).toList();}
    public String faction(UUID id){JsonObject state=journal.gameplay(id);return state.has("faction")?Factions.normalize(state.get("faction").getAsString()):"stalker";}
    public playerscale.ScaleValue scale(UUID id){return CharacterScale.saved(journal.gameplay(id));}
    public void scale(UUID id,playerscale.ScaleValue size,Runnable success,Consumer<String> failure){mutate(id,before->before.replace(Map.of(),0),CharacterScale.progress(size),plan->success.run(),failure);}
    /** HTTP reads only the immutable durable journal view, never transport-owned Characters. */
    public Map<String,Object> lobbyPreview(UUID id){Inventory durable=journal.load(id);Map<String,Object> preview=new LinkedHashMap<>();String[] names={"armor","skin","backpack","melee","weapon","weaponSecondary"};int[] slots={1,2,3,5,6,7};for(int i=0;i<slots.length;i++){ItemStack stack=durable.at(new Inventory.Address(0,slots[i],0));preview.put(names[i],stack==null?null:Map.of("id",stack.id(),"stackSize",1,"tag",Map.of()));}return Collections.unmodifiableMap(preview);}
    /** Thread-safe admission. Native creation and fsync never run on HTTP or transport. */
    public CompletableFuture<Void> ensureDefaults(UUID id){
        Objects.requireNonNull(id);if(retiring.contains(id))return CompletableFuture.failedFuture(new IllegalStateException("character_deleting"));
        synchronized(pendingInitializations){
            if(closed)return CompletableFuture.failedFuture(new IllegalStateException("inventory_closed"));
            CompletableFuture<Void> existing=pendingInitializations.get(id);if(existing!=null)return existing.copy();
            if(pendingInitializations.size()>=128)return CompletableFuture.failedFuture(new IllegalArgumentException("inventory_initialization_queue_full"));
            CompletableFuture<Void> future=new CompletableFuture<>();pendingInitializations.put(id,future);
            future.whenComplete((ignored,error)->{synchronized(pendingInitializations){pendingInitializations.remove(id,future);}});
            InventoryJournal.Lease lease=journal.prepare(id);future.whenComplete((v,e)->lease.close());
            if(!initializations.offer(new Initialization(id,future,lease)))future.completeExceptionally(new IllegalArgumentException("inventory_initialization_queue_full"));
            // Cancellation/completion of a caller's copy cannot release admission
            // while the private initialization is still queued or committing.
            return future.copy();
        }
    }
    private void initialize(Initialization request){
        if(request.future().isDone())return;
        if(!request.lease().isDone()){if(!initializations.offer(request))request.future().completeExceptionally(new IllegalStateException("inventory_initialization_limit"));return;}
        try{request.lease().join();}catch(RuntimeException error){request.future().completeExceptionally(error);return;}
        if(retiring.contains(request.id())){request.future().completeExceptionally(new IllegalStateException("character_deleting"));return;}
        Character c=character(request.id());
        if(c.failed){request.future().completeExceptionally(new IllegalStateException("inventory_save_failed"));return;}
        if(c.busy||c.deathClear){if(!initializations.offer(request))throw new IllegalStateException("inventory_initialization_limit");return;}
        JsonObject progress=journal.gameplay(request.id());
        if(progress.has(DEFAULT_EQUIPMENT)&&progress.get(DEFAULT_EQUIPMENT).getAsBoolean()){request.future().complete(null);return;}
        Inventory before=c.inventory;c.busy=true;
        try{factories.execute(()->{try{
            Inventory.Address armor=new Inventory.Address(0,1,0);Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();
            if(before.at(armor)==null)changes.put(armor,items.createStack(25078,1,0,new JsonObject()));
            progress.addProperty(DEFAULT_EQUIPMENT,true);
            // Retain the existing Java development weapon seed only for a truly new,
            // otherwise untouched inventory. A later mutation invalidates this revision.
            progress.addProperty(STARTER_REVISION,before.revision()==0?before.revision()+1:-1);
            Inventory.Plan plan=before.replace(changes,0);
            completion(()->persist(request.id(),c,plan,progress,p->request.future().complete(null),error->request.future().completeExceptionally(new IllegalStateException(error))));
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;request.future().completeExceptionally(error);});}});}
        catch(RejectedExecutionException error){c.busy=false;request.future().completeExceptionally(new IllegalArgumentException("inventory_factory_queue_full"));}
    }
    public void faction(UUID id,String requested,Runnable success,Consumer<String> failure){String faction;try{faction=Factions.normalize(requested);}catch(IllegalArgumentException error){failure.accept(error.getMessage());return;}JsonObject state=new JsonObject();state.addProperty("faction",faction);mutate(id,before->before.replace(Map.of(),0),state,plan->success.run(),failure);}
    public Map<Integer,GameItems.Medicine> medicines(){return items.medicines();}
    public InventoryPackets.MedicineRequest medicineRequest(byte[] body,int page)throws Exception{var request=items.medicineRequest(body);return InventoryPackets.medicine(request.owner(),request.sequence(),request.address(),page);}
    public Map<String,Float> medicineStats(Collection<Map<String,Float>> banks){try{return items.medicineStats(banks);}catch(Exception error){throw new IllegalStateException("medicine_native_stats",error);}}
    public byte[] medicineEffects(int tick,List<CharacterEffects.Effect> removed,Collection<CharacterEffects.Effect> added,long now){try{return GamePackets.effectRecords(tick,items.medicineEffects(removed.stream().map(CharacterEffects.Effect::id).toList(),added.stream().map(e->new GameItems.MedicineEffect(e.id(),e.until(),e.definition(items.medicines()))).toList(),now));}catch(Exception error){throw new IllegalStateException("medicine_native_effects",error);}}
    public boolean medicineAllowed(int item,String mode,String location){try{return items.medicineAllowed(item,mode,location);}catch(Exception error){throw new IllegalStateException("medicine_native_admission",error);}}
    public Map<Integer,ExplosiveProfile> explosives(){return items.explosives();}
    public PhysicalReload.Mechanism physicalMechanism(int factory){return physicalMechanisms.get(factory);}
    public record PhysicalResult(Inventory.Plan plan,PhysicalReload.Result result){}
    /** One native worker preparation followed by a single inventory+reload-state journal commit. */
    public void physicalReload(UUID id,int container,UUID expected,PhysicalReload.Request request,PhysicalReload.Cycle current,Consumer<PhysicalResult> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
        Inventory before=c.inventory;Inventory.Address address=new Inventory.Address(0,container,0);ItemStack gun=before.at(address);
        PhysicalReload.Mechanism mechanism=gun==null?null:physicalMechanisms.get(gun.id());if(mechanism==null){failure.accept("vr_supported_weapon_not_equipped");return;}
        JsonObject gameplay=journal.gameplay(id);c.busy=true;
        try{factories.execute(()->{try{
            var info=items.inspectMagazine(gun);if(!info.identity().equals(expected))throw new IllegalArgumentException("vr_weapon_identity_changed");
            WeaponProfile profile=WeaponProfile.from(catalog.get(gun.id()).properties());if(info.magazineFactory()!=0)profile=profile.withMagazine(catalog.get(info.magazineFactory()).properties());
            PhysicalReload.Cycle previous=current;if(request.operation()==PhysicalReload.ENABLE&&(previous==null||!previous.identity().equals(expected)||request.sequence()==1)){previous=PhysicalReload.saved(gameplay,expected);if(previous!=null)previous=previous.reconnect();}
            int chosen=(request.operation()==PhysicalReload.TAKE||request.operation()==PhysicalReload.SHELL_TAKE)?request.ammunition():previous==null?0:previous.heldAmmo();
            long available=0;for(var e:before.items().entrySet())if(e.getKey().aggregate()==1&&e.getValue().id()==chosen)available+=e.getValue().count();
            var result=PhysicalReload.apply(mechanism,expected,profile.capacity(),new HashSet<>(profile.ammunition()),info.ammo(),info.rounds(),(int)Math.min(Integer.MAX_VALUE,available),request,previous);
            Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();int remaining=result.consume();
            for(var e:before.items().entrySet())if(e.getKey().aggregate()==1&&e.getValue().id()==result.ammunition()&&remaining>0){int take=Math.min(remaining,e.getValue().count());changes.put(e.getKey(),e.getValue().count(e.getValue().count()-take));remaining-=take;}
            if(remaining!=0)throw new IllegalArgumentException("vr_ammunition_changed");
            if(result.ammunition()!=info.ammo()||result.rounds()!=info.rounds())changes.put(address,gun.state(items.magazine(gun,result.ammunition(),result.rounds())));
            Inventory.Plan plan=before.replace(changes,result.consume());JsonObject progress=PhysicalReload.progress(gameplay,expected,result.disabled()?null:result.cycle());
            completion(()->persist(id,c,plan,progress,p->success.accept(new PhysicalResult(p,result)),failure));
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"vr_reload_failed"));});}});}
        catch(RejectedExecutionException full){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public Map<String,Integer> statOrdinals(){return items.statOrdinals();}
    public void weapon(UUID id,int container,Consumer<Weapon> ready,Consumer<String> failure){
        weapon(id,container,0,ready,failure);
    }
    public void weapon(UUID id,int container,int requestedAmmo,Consumer<Weapon> ready,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}ItemStack stack=c.inventory.at(new Inventory.Address(0,container,0));
        WeaponProfile profile=stack==null?null:WeaponProfile.from(catalog.get(stack.id()).properties());Inventory snapshot=c.inventory;c.busy=true;
        try{factories.execute(()->{try{
            Map<Inventory.Address,ItemStack> builtins=new LinkedHashMap<>();for(var entry:snapshot.items().entrySet())if(entry.getKey().aggregate()==0){ItemStack upgraded=items.initializeBuiltins(entry.getValue());if(upgraded!=entry.getValue())builtins.put(entry.getKey(),upgraded);}
            Inventory.Plan upgrade=builtins.isEmpty()?null:snapshot.replace(builtins,0);Inventory working=upgrade==null?snapshot:upgrade.after();ItemStack selected=working.at(new Inventory.Address(0,container,0));
            Map<Integer,byte[]> appearances=new HashMap<>();Map<Integer,UUID> identities=new HashMap<>();for(var entry:working.items().entrySet())if(entry.getKey().aggregate()==0){ItemStack equipped=entry.getValue();appearances.put(entry.getKey().container(),items.appearance(equipped,defaultAppearances.get(equipped.id())));identities.put(entry.getKey().container(),items.identity(equipped));}
            Map<Integer,byte[]> prepared=Map.copyOf(appearances);Map<Integer,UUID> preparedIdentities=Map.copyOf(identities);var summary=items.equipmentSummary(working.items().entrySet().stream().filter(entry->entry.getKey().aggregate()==0).map(Map.Entry::getValue).toList());Map<String,Float> stats=summary.total();
            if(profile==null){completion(()->publishBuiltinWeapon(id,c,upgrade,()->{c.appearances=prepared;c.identities=preparedIdentities;c.stats=stats;c.artifactStats=summary.artifacts();c.busy=false;ready.accept(null);},failure));return;}
            var info=items.inspectMagazine(selected);WeaponProfile configured=info.magazineFactory()==0?profile:profile.withMagazine(catalog.get(info.magazineFactory()).properties());int capacity=configured.capacity();int ammo=requestedAmmo!=0&&info.rounds()==0?requestedAmmo:info.ammo()==0?(requestedAmmo==0?profile.ammunition().get(0):requestedAmmo):info.ammo();if(!profile.ammunition().contains(ammo)||requestedAmmo!=0&&ammo!=requestedAmmo)throw new IllegalArgumentException("weapon_ammunition_type");
            if(info.rounds()<0||info.rounds()>capacity)throw new IllegalArgumentException("magazine_rounds");byte[][] magazines=new byte[capacity+1][];for(int n=0;n<=capacity;n++)magazines[n]=items.magazine(selected,ammo,n);
            GameItems.Launcher launcher=items.inspectLauncher(selected);byte[][] launcherMagazines=launcher==null?null:new byte[capacity+1][];if(launcher!=null)for(int n=0;n<=capacity;n++)launcherMagazines[n]=items.launcherMagazine(selected.state(magazines[n]),0,false);Weapon weapon=new Weapon(stack.id(),ammo,capacity,info.rounds(),info.identity(),magazines,configured,launcher,launcherMagazines,items.jamming(selected));completion(()->publishBuiltinWeapon(id,c,upgrade,()->{c.appearances=prepared;c.identities=preparedIdentities;c.stats=stats;c.artifactStats=summary.artifacts();c.busy=false;ready.accept(weapon);},failure));
        }catch(Exception | LinkageError e){e.printStackTrace();completion(()->{c.busy=false;failure.accept(Objects.toString(e.getMessage(),"native_weapon_failed"));});}});}catch(RejectedExecutionException e){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    private void publishBuiltinWeapon(UUID id,Character c,Inventory.Plan upgrade,Runnable ready,Consumer<String> failure){
        if(upgrade==null){ready.run();return;}if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("builtin_weapon_owner_changed");return;}
        persist(id,c,upgrade,plan->ready.run(),failure);
    }
    private Character character(UUID id){
        if(retiring.contains(id))throw new IllegalStateException("character_deleting");Character existing=characters.get(id);if(existing!=null)return existing;
        InventoryJournal.Lease lease=journal.prepare(id);
        try{lease.join();Character created=new Character(journal.load(id),lease);characters.put(id,created);return created;}
        catch(RuntimeException error){lease.close();throw error;}
    }
    /** Owner-only session references; asynchronous initialization obtains the actual durable pin. */
    public void activate(UUID id){activeCharacters.merge(id,1,Integer::sum);}
    public void deactivate(UUID id){activeCharacters.computeIfPresent(id,(key,count)->count==1?null:count-1);}
    public InventoryJournal.Lease retainDurable(UUID id){return journal.prepare(id);}
    private void trimCharacters(){
        Set<UUID> pending;synchronized(pendingInitializations){pending=Set.copyOf(pendingInitializations.keySet());}
        int count=0;long bytes=0;
        for(var entry:characters.entrySet()){Character c=entry.getValue();if(!protectedCharacter(entry.getKey(),c,pending)){count++;bytes+=characterBytes(c);}}
        for(var it=characters.entrySet().iterator();it.hasNext()&&(count>IDLE_LIMIT||bytes>IDLE_BYTES);){var entry=it.next();Character c=entry.getValue();if(protectedCharacter(entry.getKey(),c,pending))continue;count--;bytes-=characterBytes(c);c.lease.close();it.remove();evicted++;}
        idleBytes=bytes;
    }
    private boolean protectedCharacter(UUID id,Character c,Set<UUID> pending){return activeCharacters.containsKey(id)||pending.contains(id)||retiring.contains(id)||c.busy||c.deathClear||c.clearingEffects||pendingDeathClears.containsKey(id)||pendingExpirations.containsKey(id);}
    private static long characterBytes(Character c){return c.lease.residentBytes()+16384+c.appearances.values().stream().mapToLong(a->a.length).sum();}
    public boolean busy(UUID id){Character c=character(id);return c.busy||c.failed||c.deathClear;}
    private void completion(Runnable task){if(!completed.offer(task))completionFailure=true;}
    public void drain(){if(completionFailure)throw new IllegalStateException("inventory_completion_limit");for(int n=0;n<64;n++){Runnable task=completed.poll();if(task==null)break;try{task.run();}catch(RuntimeException error){callbackFailures++;System.err.println("inventory callback failed: "+error);error.printStackTrace();}}drainDeathClears();drainExpirations();int budget=Math.min(64,initializations.size());for(int n=0;n<budget;n++){Initialization request=initializations.poll();if(request==null)break;initialize(request);}trimCharacters();}
    /** Serialize a death clear behind any accepted medicine/gear write, including disconnects. */
    public void clearEffectsOnDeath(UUID id){clearEffectsOnDeath(id,null,r->{},e->{});}
    public void clearEffectsOnDeath(UUID id,szserver.persistence.CharacterLocations.PlayerState state,Consumer<VitalsReceipt> success,Consumer<String> failure){
        clearEffectsOnDeath(id,state,success,failure,Set.of(),Set.of());
    }
    public void clearEffectsOnDeath(UUID id,szserver.persistence.CharacterLocations.PlayerState state,Consumer<VitalsReceipt> success,Consumer<String> failure,Set<UUID> live,Set<UUID> eligible){
        clearEffectsOnDeath(id,state,success,failure,live,eligible,null);
    }
    public void clearEffectsOnDeath(UUID id,szserver.persistence.CharacterLocations.PlayerState state,Consumer<VitalsReceipt> success,Consumer<String> failure,Set<UUID> live,Set<UUID> eligible,UUID death){
        Character c=character(id);if(pendingDeathClears.containsKey(id))return;
        if(pendingDeathClears.size()>=128){completionFailure=true;failure.accept("death_effect_queue_limit");return;}
        if(state!=null&&state.health()!=0)throw new IllegalArgumentException("death_vitals_not_zero");
        pendingExpirations.remove(id);c.deathClear=true;pendingDeathClears.put(id,new DeathClear(c,state,success,failure,Set.copyOf(live),Set.copyOf(eligible),death));
    }
    /** Owner-only, coalesced cleanup behind accepted inventory writes. False requests retry. */
    public boolean expireEffects(UUID id,long before){
        if(closed||retiring.contains(id))return false;Character c=character(id);
        if(c.failed)return false;if(c.deathClear)return true;
        Expiration previous=pendingExpirations.get(id);
        if(previous==null&&pendingExpirations.size()>=128)return false;
        pendingExpirations.put(id,new Expiration(c,previous==null?before:Math.max(before,previous.before())));return true;
    }
    private void drainExpirations(){
        for(var iterator=pendingExpirations.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();UUID id=entry.getKey();Expiration expiry=entry.getValue();Character c=expiry.character();
            if(c.failed||c.deathClear){iterator.remove();continue;}if(c.busy)continue;
            // Filter the latest durable root, never a session snapshot captured before a medicine save.
            JsonObject previous=journal.gameplay(id),state=CharacterEffects.read(previous,items.medicines(),expiry.before()).json();iterator.remove();
            if(previous.has(GrenadePulseEffects.KEY))state.add(GrenadePulseEffects.KEY,GrenadePulseEffects.read(previous,pulseProfiles,expiry.before()).json());
            if(Objects.equals(previous.get("effects"),state.get("effects"))&&Objects.equals(previous.get("cooldowns"),state.get("cooldowns"))&&Objects.equals(previous.get(GrenadePulseEffects.KEY),state.get(GrenadePulseEffects.KEY)))continue;
            if(!previous.has("effects")&&!previous.has(GrenadePulseEffects.KEY))continue;
            c.busy=true;c.clearingEffects=true;
            persist(id,c,c.inventory.replace(Map.of(),0),state,p->{c.clearingEffects=false;},error->{c.clearingEffects=false;c.failed=true;});
        }
    }
    private void drainDeathClears(){
        for(var iterator=pendingDeathClears.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();UUID id=entry.getKey();DeathClear clear=entry.getValue();Character c=clear.character();if(c.failed){c.deathClear=false;iterator.remove();clear.failure().accept("inventory_save_failed");continue;}if(c.busy)continue;
            // Read the latest committed medicine cooldown/sequence, not the earlier session snapshot.
            JsonObject previous=journal.gameplay(id),state=CharacterEffects.read(previous,items.medicines(),System.currentTimeMillis()).onDeath().after().json();if(previous.has(GrenadePulseEffects.KEY)||!clear.eligible().isEmpty())state.add(GrenadePulseEffects.KEY,GrenadePulseEffects.read(previous,pulseProfiles,System.currentTimeMillis()).onDeath(clear.live(),clear.eligible()).json());c.busy=true;c.clearingEffects=true;c.deathClear=false;iterator.remove();
            Inventory.Plan plan=c.inventory.replace(Map.of(),0);VitalsReceipt receipt=clear.state()==null?null:new VitalsReceipt(plan.after().revision(),clear.state());if(receipt!=null)state.add(VitalsReceipt.KEY,receipt.json());if(clear.death()!=null)state.add(CharacterRevival.KEY,new CharacterRevival(clear.death(),true).json());
            persist(id,c,plan,state,p->{c.clearingEffects=false;clear.success().accept(receipt);},error->{c.clearingEffects=false;c.failed=true;clear.failure().accept(error);});
        }
    }
    private void persist(UUID id,Character c,Inventory.Plan plan,Consumer<Inventory.Plan> success,Consumer<String> failure){
        persist(id,c,plan,null,success,failure);
    }
    private void persist(UUID id,Character c,Inventory.Plan plan,JsonObject progress,Consumer<Inventory.Plan> success,Consumer<String> failure){
        persist(id,c,plan,progress,null,success,failure);
    }
    private void persist(UUID id,Character c,Inventory.Plan plan,JsonObject progress,WorldDrop drop,Consumer<Inventory.Plan> success,Consumer<String> failure){persist(id,c,plan,progress,drop,null,null,success,failure);}
    private void persist(UUID id,Character c,Inventory.Plan plan,JsonObject progress,WorldDrop drop,WorldItem spawn,UUID claim,Consumer<Inventory.Plan> success,Consumer<String> failure){
        JsonObject merged=null;if(progress!=null){merged=journal.gameplay(id);for(var entry:progress.entrySet())merged.add(entry.getKey(),entry.getValue().deepCopy());}
        try{(spawn!=null||claim!=null?journal.commitWorldItem(id,plan,spawn,claim):journal.commit(id,plan,merged,drop)).whenComplete((ignored,error)->completion(()->{
            c.busy=false;if(error!=null){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();denied++;if(cause instanceof IllegalArgumentException){failure.accept(cause.getMessage());return;}c.failed=true;failure.accept("inventory_save_failed");return;}
            c.inventory=plan.after();c.applications=null;c.pulseEffects=null;accepted++;success.accept(plan);
        }));}catch(IllegalArgumentException invalid){c.busy=false;denied++;failure.accept(invalid.getMessage());}
    }
    public void loadout(UUID id,String action,String name,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}String normalized;
        try{normalized=Loadouts.name(name);if(!Set.of("save","apply","delete").contains(action))throw new IllegalArgumentException("loadout_action");}catch(IllegalArgumentException invalid){failure.accept(invalid.getMessage());return;}
        Inventory before=c.inventory;JsonObject progress=journal.gameplay(id);JsonObject presets=progress.has("loadouts")?progress.getAsJsonObject("loadouts"):new JsonObject();c.busy=true;
        try{factories.execute(()->{try{
            Function<ItemStack,UUID> identity=item->{try{return items.identity(item);}catch(Exception error){throw new IllegalArgumentException("loadout_identity_invalid",error);}};
            Inventory.Plan plan=before.replace(Map.of(),0);
            if(action.equals("save")){if(presets.size()>=16&&!presets.has(normalized))throw new IllegalArgumentException("loadout_limit");presets.add(normalized,Loadouts.save(before,identity));}
            else if(action.equals("delete")){if(presets.remove(normalized)==null)throw new IllegalArgumentException("loadout_not_found");}
            else{if(!presets.has(normalized))throw new IllegalArgumentException("loadout_not_found");plan=Loadouts.apply(before,presets.getAsJsonObject(normalized),identity);}
            progress.add("loadouts",presets);Inventory.Plan prepared=plan;completion(()->persist(id,c,prepared,progress,success,failure));
        }catch(Exception | LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"loadout_failed"));});}});}catch(RejectedExecutionException full){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void prepare(UUID id,ItemStack primary,ItemStack ammunition,Runnable ready,Consumer<String> failure){
        ensureDefaults(id).whenComplete((ignored,error)->completion(()->{
            if(error!=null){failure.accept("inventory_initialization_failed");return;}
            Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
            JsonObject state=journal.gameplay(id);long eligible=state.has(STARTER_REVISION)?state.get(STARTER_REVISION).getAsLong():-1;
            if(c.inventory.revision()!=eligible){ready.run();return;}
            c.busy=true;try{persist(id,c,c.inventory.replace(Map.of(new Inventory.Address(0,6,0),primary,new Inventory.Address(1,0,0),ammunition),0),p->ready.run(),failure);}catch(RuntimeException e){c.busy=false;failure.accept(e.getMessage());}
        }));
    }
    public void mutate(UUID id,Function<Inventory,Inventory.Plan> operation,Consumer<Inventory.Plan> success,Consumer<String> failure){
        mutate(id,operation,null,success,failure);
    }
    /** Character remains busy until the off-owner plan is durably committed or rejected. */
    public void sort(UUID id,Inventory.Address root,Consumer<Inventory.Plan> success,Consumer<String> failure){mutateAsync(id,before->before.sort(root),success,failure);}
    public void mutateAsync(UUID id,Function<Inventory,Inventory.Plan> operation,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear||!admissionAllowed(id)){denied++;failure.accept("inventory_busy");return;}
        Inventory before=c.inventory;c.busy=true;
        try{factories.execute(()->{try{
            Inventory.Plan plan=operation.apply(before);
            completion(()->{
                if(c.inventory!=before||c.failed||c.deathClear||!admissionAllowed(id)){c.busy=false;denied++;failure.accept("inventory_plan_stale");return;}
                persist(id,c,plan,success,failure);
            });
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"inventory_plan_failed"));});}});}
        catch(RejectedExecutionException full){c.busy=false;denied++;failure.accept("inventory_factory_queue_full");}
    }
    public boolean areaApplied(UUID target,UUID projectile,int profile){Character c=character(target);if(c.applications==null)c.applications=GrenadeApplications.read(journal.gameplay(target));return c.applications.contains(projectile,profile);}
    public int areaApplicationCount(UUID target){Character c=character(target);if(c.applications==null)c.applications=GrenadeApplications.read(journal.gameplay(target));return c.applications.size();}
    public record AreaHealing(Inventory.Plan plan,VitalsReceipt receipt,boolean applied){}
    /** Startup-only native profile registration; never adds grenades to the medicine-use catalog. */
    public void pulseProfiles(Map<Integer,List<GrenadeAreaProfile>> profiles){if(pulseProfilesPrepared||Thread.currentThread().getName().equals("native-game-owner"))throw new IllegalStateException("grenade_pulse_profiles_startup");Map<Integer,List<GrenadeAreaProfile>> copy=new HashMap<>();profiles.forEach((id,rows)->copy.put(id,List.copyOf(rows)));pulseProfiles=Map.copyOf(copy);pulseProfilesPrepared=true;}
    public GrenadePulseEffects pulseEffects(UUID id,long now){Character c=character(id);if(c.pulseEffects==null)c.pulseEffects=GrenadePulseEffects.read(journal.gameplay(id),pulseProfiles,Long.MIN_VALUE);return c.pulseEffects.expire(now);}
    public record PulseApplied(Inventory.Plan plan,VitalsReceipt receipt,GrenadePulseEffects after,boolean applied){}
    /** Application marker, fixed-deadline bank and expected vitals share one character journal revision. */
    public void applyPulse(UUID target,GrenadeAreaStore.Activation source,int profile,Set<UUID> live,long now,szserver.persistence.CharacterLocations.PlayerState expected,Consumer<PulseApplied> success,Consumer<String> failure){
        Character c=character(target);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}Inventory before=c.inventory;JsonObject saved=journal.gameplay(target);Set<UUID> active=Set.copyOf(live);c.busy=true;
        try{factories.execute(()->{try{
            var nativeRows=pulseProfiles.get(source.item());if(source.pulseTargets()==null||!source.pulseTargets().contains(target)||expected==null||expected.health()<=0||nativeRows==null||!nativeRows.equals(source.profiles())||profile<0||profile>=nativeRows.size()||!szserver.client.GrenadeAreaScenes.pulse(List.of(nativeRows.get(profile))))throw new IllegalArgumentException("grenade_pulse_target_or_profile");
            var effects=GrenadePulseEffects.read(saved,pulseProfiles,now);if(effects.retired(source.projectile()))throw new IllegalArgumentException("grenade_pulse_retired");var claim=GrenadeApplications.read(saved).claim(source,profile,active);
            if(!claim.applied()){completion(()->{c.busy=false;success.accept(new PulseApplied(null,null,effects,false));});return;}
            var after=effects.apply(source,profile,active,now);Inventory.Plan plan=before.replace(Map.of(),0);VitalsReceipt receipt=new VitalsReceipt(plan.after().revision(),expected);JsonObject progress=new JsonObject();progress.add(GrenadeApplications.KEY,claim.after().json());progress.add(GrenadePulseEffects.KEY,after.json());progress.add(VitalsReceipt.KEY,receipt.json());
            completion(()->{if(c.deathClear||!admissionAllowed(target)){c.busy=false;failure.accept("grenade_pulse_owner_changed");return;}persist(target,c,plan,progress,p->success.accept(new PulseApplied(p,receipt,after,true)),failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"grenade_pulse_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }

    public record ReviveApplied(Inventory.Plan plan,VitalsReceipt receipt,boolean applied){}
    /** One target-local commit for exact-death retirement, once marker and resurrected HP. */
    public void reviveArea(UUID target,GrenadeAreaStore.Activation source,int profile,UUID death,Set<UUID> live,long now,szserver.persistence.CharacterLocations.PlayerState expected,Consumer<ReviveApplied> success,Consumer<String> failure){
        Character c=character(target);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}Inventory before=c.inventory;JsonObject saved=journal.gameplay(target);Set<UUID> active=Set.copyOf(live);c.busy=true;
        try{factories.execute(()->{try{
            var nativeRows=pulseProfiles.get(source.item());if(source.reviveTargets()==null||!Objects.equals(source.reviveTargets().get(target),death)||expected==null||expected.health()<=0||nativeRows==null||!nativeRows.equals(source.profiles())||profile<0||profile>=nativeRows.size()||!GrenadeAreaComponents.revive(List.of(nativeRows.get(profile))))throw new IllegalArgumentException("grenade_revive_target_or_profile");
            var ledger=GrenadeApplications.read(saved);if(ledger.contains(source.projectile(),profile)){var duplicate=ledger.claim(source,profile,active);completion(()->{c.busy=false;success.accept(new ReviveApplied(null,null,false));});return;}
            if(now<source.started()||now>=source.started()+nativeRows.get(profile).durationMillis())throw new IllegalArgumentException("grenade_revive_expired");
            var state=CharacterRevival.read(saved).orElseThrow(()->new IllegalArgumentException("grenade_revive_death_missing")).revive(death);var claim=ledger.claim(source,profile,active);Inventory.Plan plan=before.replace(Map.of(),0);VitalsReceipt receipt=new VitalsReceipt(plan.after().revision(),expected);JsonObject progress=new JsonObject();progress.add(GrenadeApplications.KEY,claim.after().json());progress.add(CharacterRevival.KEY,state.json());progress.add(VitalsReceipt.KEY,receipt.json());
            completion(()->{if(c.deathClear||!admissionAllowed(target)){c.busy=false;failure.accept("grenade_revive_owner_changed");return;}persist(target,c,plan,progress,p->success.accept(new ReviveApplied(p,receipt,true)),failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"grenade_revive_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }

    /** Marker and expected HP receipt commit in the same existing character journal revision. */
    public void healArea(UUID target,GrenadeAreaStore.Activation source,int profile,Set<UUID> live,szserver.persistence.CharacterLocations.PlayerState expected,Consumer<AreaHealing> success,Consumer<String> failure){
        Character c=character(target);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}Inventory snapshot=c.inventory;JsonObject saved=journal.gameplay(target);Set<UUID> active=Set.copyOf(live);c.busy=true;
        try{factories.execute(()->{try{
            if(expected==null||expected.health()<=0||profile<0||profile>=source.profiles().size()||!szserver.client.GrenadeAreaScenes.healing(List.of(source.profiles().get(profile))))throw new IllegalArgumentException("grenade_heal_profile");
            var claim=GrenadeApplications.read(saved).claim(source,profile,active);
            if(!claim.applied()){completion(()->{c.busy=false;success.accept(new AreaHealing(null,null,false));});return;}
            Inventory.Plan plan=snapshot.replace(Map.of(),0);VitalsReceipt receipt=new VitalsReceipt(plan.after().revision(),expected);JsonObject progress=new JsonObject();progress.add(GrenadeApplications.KEY,claim.after().json());progress.add(VitalsReceipt.KEY,receipt.json());
            completion(()->{if(c.deathClear||!admissionAllowed(target)){c.busy=false;failure.accept("grenade_heal_owner_changed");return;}persist(target,c,plan,progress,p->success.accept(new AreaHealing(p,receipt,true)),failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"grenade_heal_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    /** Unjamming edits only the reviewed native flag on the inventory worker. */
    public void unjam(UUID id,int container,ItemStack expected,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}Inventory snapshot=c.inventory;c.busy=true;
        try{factories.execute(()->{try{
            var address=new Inventory.Address(0,container,0);ItemStack current=snapshot.at(address);if(current==null||(!current.mergeable(expected)||current.count()!=expected.count()))throw new IllegalArgumentException("unjamming_weapon_changed");
            if(!items.jamming(current).jammed())throw new IllegalArgumentException("weapon_not_jammed");
            Inventory.Plan plan=snapshot.replace(Map.of(address,items.jamming(current,false)),0);
            completion(()->{if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("unjamming_owner_changed");return;}persist(id,c,plan,success,failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"unjamming_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    /** Native attachment state is built off-owner; bag debit and loaded launcher commit together. */
    public void reloadLauncher(UUID id,int container,ItemStack expected,UUID launcherIdentity,int ammunition,boolean infinite,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear||!admissionAllowed(id)){failure.accept("inventory_busy");return;}
        Inventory before=c.inventory;Inventory.Address address=new Inventory.Address(0,container,0);
        if(before.at(address)!=expected){failure.accept("launcher_weapon_changed");return;}c.busy=true;
        try{factories.execute(()->{try{
            var launcher=items.inspectLauncher(expected);if(launcher==null||!launcher.identity().equals(launcherIdentity)||launcher.rounds()!=0||!launcher.ammunition().contains(ammunition))throw new IllegalArgumentException("launcher_reload_changed");
            Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();boolean found=infinite;ItemStack charge=infinite?items.createStack(ammunition,1,0,new JsonObject()):null;
            if(!infinite)for(var entry:before.items().entrySet())if(entry.getKey().aggregate()==1&&entry.getValue().id()==ammunition){changes.put(entry.getKey(),entry.getValue().count(entry.getValue().count()-1));charge=entry.getValue().count(1);found=true;break;}
            if(!found)throw new IllegalArgumentException("launcher_ammunition_missing");
            changes.put(address,expected.state(items.launcherMagazine(expected,charge)));var plan=before.replace(changes,infinite?0:1);
            completion(()->{if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("launcher_owner_changed");return;}persist(id,c,plan,success,failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"launcher_reload_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    /** Moves the actual unspent native charge into the bag in one journal revision. */
    public void unloadLauncher(UUID id,Inventory.Address target,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear||!admissionAllowed(id)){failure.accept("inventory_busy");return;}
        Inventory before=c.inventory;ItemStack expected;try{expected=before.at(target);if(expected==null)throw new IllegalArgumentException("launcher_weapon_missing");}catch(RuntimeException error){failure.accept(error.getMessage());return;}c.busy=true;
        try{factories.execute(()->{try{
            var launcher=items.inspectLauncher(expected);if(launcher==null||launcher.rounds()!=1)throw new IllegalArgumentException("launcher_charge_empty");
            ItemStack charge=items.launcherCharge(expected);if(charge.count()!=1||charge.id()!=launcher.ammo())throw new IllegalArgumentException("launcher_charge_state");
            Inventory after=before.replace(Map.of(target,expected.state(launcher.emptyState())),0).after().give(charge).after();
            Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());
            for(Inventory.Address address:addresses)if(before.at(address)!=after.at(address))delta.put(address,after.at(address));Inventory.Plan plan=before.replace(delta,1);
            completion(()->{if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("launcher_owner_changed");return;}persist(id,c,plan,success,failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"launcher_unload_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void mutate(UUID id,Function<Inventory,Inventory.Plan> operation,JsonObject progress,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}c.busy=true;
        try{persist(id,c,operation.apply(c.inventory),progress,success,failure);}catch(RuntimeException e){c.busy=false;denied++;failure.accept(e.getMessage());}
    }
    /** Atomic one-item debit and last-placement marker; the receipt worker finalized its predecessor. */
    public void spendFpv(FpvReceipts.Admission admission,Consumer<FpvReceipt> success,Consumer<String> failure){
        UUID id=admission.character();Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}c.busy=true;
        try{
            if(!Objects.equals(journal.fpvPending(id),admission.previous()))throw new IllegalArgumentException("fpv_placement_receipt_stale");
            Inventory.Address address=new Inventory.Address(1,0,admission.slot());ItemStack item=c.inventory.at(address);if(item==null||item.id()!=12447)throw new IllegalArgumentException("fpv_item_missing");
            Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();delta.put(address,item.count(item.count()-1));Inventory.Plan plan=c.inventory.replace(delta,1);
            FpvReceipt receipt=new FpvReceipt(id,admission.drone(),admission.slot(),admission.position(),c.inventory.revision(),plan.after().revision());persist(id,c,plan,receipt.progress(),committed->success.accept(receipt),failure);
        }catch(RuntimeException invalid){c.busy=false;denied++;failure.accept(invalid.getMessage());}
    }
    public Map<UUID,WorldItem> worldItems(){return journal.physicalItems();}
    public CompletableFuture<Void> settleWorldItem(UUID id,double x,double y,double z){return journal.settleWorldItem(id,x,y,z);}
    public CompletableFuture<Void> expireWorldItem(UUID id,long now){return journal.expireWorldItem(id,now);}
    public boolean canCollect(UUID id,ItemStack item){Character c=character(id);if(c.busy||c.failed||c.deathClear)return false;try{c.inventory.give(item);return true;}catch(IllegalArgumentException full){return false;}}
    public void collectWorldItem(UUID id,UUID itemId,String world,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
        WorldItem item=journal.physicalItems().get(itemId);if(item==null||!item.world().equals(world)){failure.accept("world_item_already_claimed");return;}
        try{Inventory.Plan plan=c.inventory.give(item.stack());c.busy=true;persist(id,c,plan,null,null,null,itemId,success,failure);}catch(IllegalArgumentException invalid){failure.accept(invalid.getMessage());}
    }
    public void dropWorldItem(UUID id,Inventory.Address address,String world,double[] position,double[] velocity,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
        Inventory before=c.inventory;ItemStack item=before.at(address);if(item==null||address.aggregate()>1||address.aggregate()==1&&address.container()!=0){failure.accept("world_item_source");return;}
        if(journal.physicalItems().size()>=2048){failure.accept("world_item_capacity");return;}
        double[] origin=position.clone(),motion=velocity.clone();c.busy=true;
        try{factories.execute(()->{try{
            var policy=items.dropPolicy(item);if(!policy.allowed())throw new IllegalArgumentException("item_cannot_be_dropped");long now=System.currentTimeMillis();
            WorldItem drop=new WorldItem(UUID.randomUUID(),id,world,item,origin[0],origin[1],origin[2],motion[0],motion[1],motion[2],now,now+300_000,policy.pickupOwner());
            Inventory.Plan plan=before.replace(Collections.singletonMap(address,null),item.count());
            completion(()->{if(c.deathClear||!admissionAllowed(id)){c.busy=false;failure.accept("world_item_owner_changed");return;}persist(id,c,plan,null,null,drop,null,success,failure);});
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;failure.accept(Objects.toString(error.getMessage(),"world_item_drop_failed"));});}});}catch(RejectedExecutionException full){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public List<WorldDrop> drops(UUID id){return journal.drops(id);}
    public void remove(UUID id,InventoryCommand command,double[] position,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}c.busy=true;
        try{Inventory.Plan plan=c.inventory.remove(command.slot(),command.count());ItemStack source=c.inventory.at(new Inventory.Address(1,0,command.slot()));WorldDrop drop=command.operation().equals("drop")?WorldDrop.create(id,position,source.count(plan.moved())):null;persist(id,c,plan,null,drop,success,failure);}catch(RuntimeException error){c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"inventory_remove_failed"));}
    }
    public void give(UUID id,int factory,int count,Consumer<Inventory.Plan> success,Consumer<String> failure){
        give(id,factory,count,0,new JsonObject(),success,failure);
    }
    /** Administrative replacement still uses native construction and durable atomic inventory publication. */
    public void applyEquipment(UUID id,int container,int factory,Consumer<Inventory.Plan> success,Consumer<String> failure){
        if(container<0||container>=StorageLayout.EQUIPMENT||factory<0||factory>65535){failure.accept("apply_arguments");return;}
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}
        try{if(factory!=0)catalog.get(factory);}catch(IllegalArgumentException e){failure.accept(e.getMessage());return;}
        c.busy=true;Inventory before=c.inventory;
        try{factories.execute(()->{try{
            ItemStack item=factory==0?null:items.createStack(factory,1,0,new JsonObject());
            Map<Inventory.Address,ItemStack> change=new LinkedHashMap<>();change.put(new Inventory.Address(0,container,0),item);
            Inventory.Plan plan=before.replace(change,0);completion(()->persist(id,c,plan,success,failure));
        }catch(Exception|LinkageError e){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(e.getMessage(),"apply_failed"));});}});}catch(RejectedExecutionException e){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void edit(UUID id,Inventory.Address address,byte[] raw,Consumer<Inventory.Plan> success,Consumer<String> failure){
        edit(id,address,raw,false,success,failure);
    }
    public void module(UUID id,Inventory.Address target,Inventory.Address source,int selection,int slot,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}Inventory before=c.inventory;ItemStack weapon=before.at(target),attached=source==null?null:before.at(source);if(weapon==null||source!=null&&(source.equals(target)||attached==null)){failure.accept("module_inventory_address");return;}c.busy=true;
        try{factories.execute(()->{try{var edit=items.moduleBatch(weapon,slot,attached,selection);Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();changes.put(target,edit.weapon());if(source!=null)changes.put(source,attached.count(attached.count()-1));Inventory after=before.replace(changes,0).after();for(ItemStack removed:edit.removed())after=after.give(removed).after();var info=items.inspectMagazine(edit.weapon());WeaponProfile profile=WeaponProfile.from(catalog.get(weapon.id()).properties());if(profile!=null){if(info.magazineFactory()!=0)profile=profile.withMagazine(catalog.get(info.magazineFactory()).properties());if(info.rounds()>profile.capacity())throw new IllegalArgumentException("module_loaded_magazine_capacity");}Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());for(Inventory.Address address:addresses)if(before.at(address)!=after.at(address))delta.put(address,after.at(address));Inventory.Plan plan=before.replace(delta,0);completion(()->persist(id,c,plan,success,failure));}catch(Exception | LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"module_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void socket(UUID id,Inventory.Address source,int slot,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}Inventory before=c.inventory;Inventory.Address target=new Inventory.Address(0,3,0);ItemStack parent=before.at(target),artifact=source==null?null:before.at(source);if(parent==null||source!=null&&(source.aggregate()!=1||artifact==null)){failure.accept("artifact_socket_inventory");return;}JsonObject definition=catalog.get(parent.id()).properties();int slots=definition.has("artefakt_slots")?definition.get("artefakt_slots").getAsInt():0;if(slot<0||slot>=slots){failure.accept("artifact_socket_capacity");return;}c.busy=true;
        try{factories.execute(()->{try{var edit=items.socket(parent,slot,artifact);if(artifact==null&&edit.removed()==null)throw new IllegalArgumentException("artifact_slot_empty");Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();changes.put(target,edit.weapon());if(source!=null)changes.put(source,null);Inventory after=before.replace(changes,0).after();if(edit.removed()!=null)after=after.give(edit.removed()).after();Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());for(Inventory.Address address:addresses)if(before.at(address)!=after.at(address))delta.put(address,after.at(address));Inventory.Plan plan=before.replace(delta,0);completion(()->persist(id,c,plan,success,failure));}catch(Exception | LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"artifact_socket_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void edit(UUID id,Inventory.Address address,byte[] raw,boolean config,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}
        ItemStack original=c.inventory.at(address);if(original==null){failure.accept("editor_original_missing");return;}c.busy=true;Inventory before=c.inventory;
        try{factories.execute(()->{try{ItemStack edited=config?items.readConfig(raw):items.readStack(raw);if(edited.id()!=original.id()||!config&&edited.count()!=original.count())throw new IllegalArgumentException("editor_cannot_replace_item_or_count");edited=items.preserveIdentity(original,edited);Inventory.Plan plan=before.replace(Map.of(address,edited),0);completion(()->persist(id,c,plan,success,failure));}catch(Exception | LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"editor_state_invalid"));});}});}catch(RejectedExecutionException error){c.busy=false;denied++;failure.accept("inventory_factory_queue_full");}
    }
    public void cosmetic(UUID id,Inventory.Address target,Inventory.Address source,int selection,int slot,boolean skin,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){failure.accept("inventory_busy");return;}Inventory before=c.inventory;ItemStack item=before.at(target),consumed=source==null?null:before.at(source);
        if(item==null||item.count()!=1||source!=null&&(source.equals(target)||consumed==null)){failure.accept("cosmetic_inventory_address");return;}
        c.busy=true;
        try{factories.execute(()->{try{
            ItemStack changed,removed;boolean noChange=false;
            if(skin){var edit=items.nativeSkin(item,consumed);changed=edit.item();noChange=edit.unchanged();removed=edit.removed();}
            else{var edit=items.paint(item,slot,consumed,selection);changed=edit.weapon();removed=edit.removed();}
            Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();if(!noChange){changes.put(target,changed);if(source!=null)changes.put(source,consumed.count(consumed.count()-1));}
            Inventory after=before.replace(changes,0).after();if(!noChange&&removed!=null)after=after.give(removed).after();
            Map<Inventory.Address,ItemStack> delta=new LinkedHashMap<>();Set<Inventory.Address> addresses=new TreeSet<>(before.items().keySet());addresses.addAll(after.items().keySet());for(Inventory.Address address:addresses)if(before.at(address)!=after.at(address))delta.put(address,after.at(address));
            Inventory.Plan plan=before.replace(delta,0);completion(()->persist(id,c,plan,success,failure));
        }catch(Exception | LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"cosmetic_failed"));});}});}catch(RejectedExecutionException error){c.busy=false;failure.accept("inventory_factory_queue_full");}
    }
    public void give(UUID id,int factory,int count,int metadata,JsonObject tags,Consumer<Inventory.Plan> success,Consumer<String> failure){
        give(id,factory,count,metadata,tags,null,null,null,success,failure);
    }
    /** Native I-window configuration uses the same grid and durable owner as commands. */
    public void grantConfig(UUID id,byte[] raw,int destination,double[] position,Consumer<Inventory.Plan> success,Consumer<String> failure){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}
        if(destination<0||destination>3){failure.accept("admin_destination");return;}boolean toCreative=destination<2&&creative(id);byte[] config=raw.clone();double[] location=position==null?null:position.clone();Inventory before=c.inventory;c.busy=true;
        try{factories.execute(()->{try{
            ItemStack template=items.readConfig(config);int count=template.count(),limit=catalog.get(template.id()).stackLimit();if(count<1||count>10000)throw new IllegalArgumentException("give_count");
            WeaponProfile profile=WeaponProfile.from(catalog.get(template.id()).properties());if(profile!=null){var magazine=items.inspectMagazine(template);if(magazine.magazineFactory()!=0)profile=profile.withMagazine(catalog.get(magazine.magazineFactory()).properties());if(magazine.ammo()!=0&&!profile.ammunition().contains(magazine.ammo()))throw new IllegalArgumentException("weapon_ammunition_type");if(magazine.rounds()<0||magazine.rounds()>profile.capacity())throw new IllegalArgumentException("magazine_rounds");}
            if(toCreative){List<ItemStack> copies=creativeCopies(template,count);completion(()->finishCreative(id,c,before,copies,count,null,null,null,success,failure));return;}
            int pieces=template.stateful()||destination==3?(int)((count+(long)limit-1)/limit):1;if(pieces>160)throw new IllegalArgumentException("give_native_copy_limit");
            Inventory after=before;Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();List<WorldDrop> drops=new ArrayList<>();int remaining=count;
            for(int n=0;n<pieces;n++){
                int amount=pieces==1?remaining:Math.min(limit,remaining);ItemStack granted=items.copyForGrant(template.count(amount));remaining-=amount;
                if(destination==3)drops.add(WorldDrop.create(id,location,granted));
                else{Inventory.Plan part=destination==2?after.store(granted):after.give(granted);changes.putAll(part.changes());after=part.after();}
            }
            Inventory.Plan plan=before.replace(changes,count);
            completion(()->{if(destination!=3){persist(id,c,plan,success,failure);return;}
                try{journal.grantDrops(id,plan,drops).whenComplete((ignored,error)->completion(()->{
                    c.busy=false;if(error!=null){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();denied++;if(!(cause instanceof IllegalArgumentException))c.failed=true;failure.accept(cause instanceof IllegalArgumentException?cause.getMessage():"inventory_save_failed");return;}
                    c.inventory=plan.after();c.applications=null;c.pulseEffects=null;accepted++;success.accept(plan);
                }));}catch(IllegalArgumentException error){c.busy=false;denied++;failure.accept(error.getMessage());}
            });
        }catch(Exception|LinkageError error){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(error.getMessage(),"native_item_failed"));});}});}
        catch(RejectedExecutionException error){c.busy=false;denied++;failure.accept("inventory_factory_queue_full");}
    }
    /** Owner admission, worker native factory, one atomic inventory/receipt transaction. */
    public void adminGive(AdminGive request,String fingerprint,AdminGiveReceipt previous,BiConsumer<AdminGiveReceipt,Inventory.Plan> success,Consumer<String> failure){
        if(retiring.contains(request.character())){denied++;failure.accept("character_deleting");return;}
        give(request.character(),request.item(),request.count(),request.metadata(),request.tags(),request.slot(),request,fingerprint,plan->success.accept(journal.adminPending(request.character()),plan),failure,previous);
    }
    private void give(UUID id,int factory,int count,int metadata,JsonObject tags,Integer slot,AdminGive request,String fingerprint,Consumer<Inventory.Plan> success,Consumer<String> failure){give(id,factory,count,metadata,tags,slot,request,fingerprint,success,failure,null);}
    private void give(UUID id,int factory,int count,int metadata,JsonObject tags,Integer slot,AdminGive request,String fingerprint,Consumer<Inventory.Plan> success,Consumer<String> failure,AdminGiveReceipt previous){
        Character c=character(id);if(c.busy||c.failed||c.deathClear){denied++;failure.accept("inventory_busy");return;}
        if(count<1||count>10000){failure.accept("give_count");return;}
        try{catalog.get(factory);if(request!=null&&!Objects.equals(previous,journal.adminPending(id)))throw new IllegalArgumentException("give_receipt_stale");}catch(IllegalArgumentException invalid){failure.accept(invalid.getMessage());return;}
        boolean toCreative=creative(id);c.busy=true;Inventory before=c.inventory;JsonObject safeTags=tags.deepCopy();
        try{factories.execute(()->{try{
            ItemStack created=items.createStack(factory,1,metadata,safeTags);byte[] state=created.state();Inventory.Plan plan;
            if(toCreative){List<ItemStack> copies=creativeCopies(created,count);completion(()->finishCreative(id,c,before,copies,count,slot,request,fingerprint,success,failure));return;}
            if(slot!=null){Inventory.Address address=new Inventory.Address(1,0,slot);if(before.at(address)!=null)throw new IllegalArgumentException("inventory_slot_occupied_or_full");plan=before.replace(Map.of(address,created.count(count)),count);}
            else if(state.length==0||catalog.get(factory).stackLimit()>1)plan=before.give(created.count(count));
            else {if(count>160)throw new IllegalArgumentException("inventory_full");Inventory after=before;Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();
                for(int n=0;n<count;n++){Inventory.Plan part=after.give(n==0?created:items.createStack(factory,1,metadata,safeTags));changes.putAll(part.changes());after=part.after();}
                plan=before.replace(changes,count);
            }
            JsonObject progress=null;if(request!=null){int first=plan.changes().keySet().stream().filter(a->a.aggregate()==1).mapToInt(Inventory.Address::slot).min().orElseThrow();progress=new AdminGiveReceipt(request.commandId(),fingerprint,id,factory,count,metadata,first,before.revision(),plan.after().revision()).progress();}
            Inventory.Plan finalPlan=plan;JsonObject finalProgress=progress;completion(()->persist(id,c,finalPlan,finalProgress,success,failure));
        }catch(Exception | LinkageError e){completion(()->{c.busy=false;denied++;failure.accept(Objects.toString(e.getMessage(),"native_item_failed"));});}});}catch(RejectedExecutionException e){c.busy=false;denied++;failure.accept("inventory_factory_queue_full");}
    }
    /** Admission fence is shared with HTTP; cached characters remain exclusively owner-owned. */
    public boolean admissionAllowed(UUID id){return !retiring.contains(id);}
    public void holdDeletion(UUID id){retiring.add(id);}
    public void releaseDeletion(UUID id){retiring.remove(id);}
    public boolean deletionReady(UUID id){Character c=characters.get(id);synchronized(pendingInitializations){return !activeCharacters.containsKey(id)&&(c==null||!c.busy&&!c.clearingEffects&&!c.deathClear)&&!pendingInitializations.containsKey(id)&&!pendingDeathClears.containsKey(id)&&!pendingExpirations.containsKey(id);}}
    public boolean hasDrops(UUID id){return journal.hasDrops(id);}
    /** Live invocation is on the game owner, after accepted factory writes have completed. */
    public CompletableFuture<Void> deleteHeld(UUID id){
        if(!retiring.contains(id)||!deletionReady(id))return CompletableFuture.failedFuture(new IllegalStateException("character_delete_not_quiescent"));
        CompletableFuture<Void> result=new CompletableFuture<>();journal.deleteCharacter(id).whenComplete((ignored,error)->completion(()->{if(error==null){Character removed=characters.remove(id);if(removed!=null)removed.lease.close();result.complete(null);}else result.completeExceptionally(error);}));return result.copy();
    }
    /** Preparation only: no native owner or cached gameplay requests exist yet. */
    public CompletableFuture<Void> deleteOffline(UUID id){if(!characters.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("character_delete_requires_owner"));return journal.deleteCharacter(id);}
    public Map<String,Object> metrics(){int pending;synchronized(pendingInitializations){pending=pendingInitializations.size();}var metrics=new LinkedHashMap<String,Object>(Map.of("accepted",accepted,"denied",denied,"characters",characters.size(),"busy",characters.values().stream().filter(c->c.busy).count(),"journal",journal.metrics(),"factoryQueue",factories.getQueue().size(),"callbackFailures",callbackFailures,"initializations",pending,"pendingDeathClears",pendingDeathClears.size(),"pendingExpirations",pendingExpirations.size()));metrics.put("lootboxes",lootboxes.metrics());metrics.put("characterCache",Map.of("idleLimit",IDLE_LIMIT,"idleByteLimit",IDLE_BYTES,"idleBytes",idleBytes,"active",activeCharacters.size(),"evicted",evicted));return Collections.unmodifiableMap(metrics);}
    public boolean healthy(){return !closed&&!completionFailure&&Boolean.TRUE.equals(((Map<?,?>)journal.metrics().get("writer")).get("healthy"));}
    @Override public void close()throws Exception{List<CompletableFuture<Void>> pending;synchronized(pendingInitializations){closed=true;pending=List.copyOf(pendingInitializations.values());initializations.clear();}for(var future:pending)future.completeExceptionally(new IllegalStateException("inventory_closed"));Workers.stop(factories);try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!pendingDeathClears.isEmpty()||!pendingExpirations.isEmpty()||!completed.isEmpty()||characters.values().stream().anyMatch(c->c.busy||c.clearingEffects)){drain();if(System.nanoTime()>=deadline)throw new IllegalStateException("inventory_shutdown_timeout");Thread.sleep(2);}}finally{for(Character c:characters.values())c.lease.close();characters.clear();activeCharacters.clear();journal.close();items.close();}}
}
