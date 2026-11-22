package szserver.inventory;

import com.google.gson.*;
import szserver.persistence.Journal;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Recovery is before listening; only the journal worker performs runtime disk IO. */
public final class InventoryJournal implements AutoCloseable {
    private final Journal journal;
    private final WorldItemLedger physicalItems=new WorldItemLedger();
    private final DiskWorldDrops drops;
    private final long dropCountLimit,dropByteLimit;
    private volatile long growthBytes,checkpointBaseBytes;
    private final DiskInventoryRoots roots;
    private final InventoryTransactionArchive transactions;
    private final java.util.concurrent.atomic.AtomicBoolean compacting=new java.util.concurrent.atomic.AtomicBoolean();
    private CompletableFuture<Void> checkpointFuture=CompletableFuture.completedFuture(null);
    private long appends;private volatile long compactions,compactionFailures;private volatile String compactionError="";
    private final ItemCatalog catalog;
    public InventoryJournal(Path file,ItemCatalog catalog)throws Exception{this(file,catalog,0);}
    /** Slow durable writer injection for isolated tests; production uses zero. */
    public InventoryJournal(Path file,ItemCatalog catalog,long testDelayMillis)throws Exception{this(file,catalog,testDelayMillis,0,0);}
    /** Optional deployment budgets; zero preserves the old Store's uncapped archive. */
    public InventoryJournal(Path file,ItemCatalog catalog,long testDelayMillis,long countLimit,long byteLimit)throws Exception{
        if(countLimit<0||byteLimit<0)throw new IllegalArgumentException("world_drop_budget");
        this.catalog=catalog;dropCountLimit=countLimit;dropByteLimit=byteLimit;
        transactions=new InventoryTransactionArchive(file);
        journal=new Journal(file,testDelayMillis,event->{});DiskWorldDrops prepared=null;DiskInventoryRoots preparedRoots=null;
        try{prepared=new DiskWorldDrops(file);drops=prepared;preparedRoots=new DiskInventoryRoots(file,catalog);roots=preparedRoots;journal.replay(this::recover).get();
            if(roots.incomplete()||drops.ownerIds().stream().anyMatch(id->!roots.contains(id)))throw new IllegalStateException("incomplete_inventory_checkpoint");
            checkpointBaseBytes=Files.size(file);
        }catch(Throwable error){if(preparedRoots!=null)try{preparedRoots.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}if(prepared!=null)try{prepared.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}try{journal.close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    private void recover(JsonObject event){
        try{event=transactions.hydrate(event);}catch(IOException failure){throw new UncheckedIOException(failure);}
        if(physicalItems.standalone(event))return;
        if(event.has("deleteCharacter")){UUID id=UUID.fromString(event.get("deleteCharacter").getAsString());if(hasDrops(id))throw new IllegalStateException("character_has_world_drops");roots.delete(id);return;}
        if(event.has("dropSnapshot")){WorldDrop drop=WorldDrop.read(event.getAsJsonObject("dropSnapshot"));validateDrop(drop);publishDrop(drop);return;}
        UUID id=UUID.fromString(event.get("character").getAsString());long revision=event.get("revision").getAsLong();
        if(event.has("snapshot")){roots.snapshot(event);return;}
        var before=roots.get(id);Inventory previous=before.inventory();if(revision!=previous.revision()+1)throw new IllegalStateException("inventory_revision_gap");
        Inventory.Plan plan=previous.replace(readChanges(event),0);WorldDrop drop=event.has("drop")?WorldDrop.read(event.getAsJsonObject("drop")):null;
        if(drop!=null){validateDropMutation(id,previous,plan,drop);validateDrop(drop);}
        List<WorldDrop> granted=new ArrayList<>();if(event.has("grantedDrops")){if(drop!=null)throw new IllegalArgumentException("mixed_drop_transaction");for(var row:event.getAsJsonArray("grantedDrops"))granted.add(WorldDrop.read(row.getAsJsonObject()));validateGrantedDrops(id,plan,granted);}
        physicalItems.validate(event,id,previous,plan);
        roots.record(event,plan.after(),event.has("gameplay")?event.getAsJsonObject("gameplay"):before.gameplay());
        if(drop!=null)publishDrop(drop);for(WorldDrop grant:granted)publishDrop(grant);physicalItems.apply(event);
    }
    /** A world drop has the old non-cascading owner FK: do not silently destroy it. */
    public boolean hasDrops(UUID id){return drops.hasOwner(id);}
    public CompletableFuture<Void> deleteCharacter(UUID id){
        JsonObject event=new JsonObject();event.addProperty("deleteCharacter",id.toString());
        return journal.appendChecked(event,()->{if(hasDrops(id))throw new IllegalArgumentException("character_has_world_drops");},()->{roots.delete(id);appended(szserver.util.Json.bytes(event).length);});
    }
    public Inventory load(UUID id){return roots.get(id).inventory();}
    public JsonObject gameplay(UUID id){return roots.get(id).gameplay().deepCopy();}
    public final class Lease implements AutoCloseable {
        private final UUID id;private final DiskInventoryRoots.Pin pin;private final CompletableFuture<Void> ready;private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        private Lease(UUID id){this.id=id;pin=roots.reserve(id);ready=pin.snapshot!=null?CompletableFuture.completedFuture(null):journal.readDerived(()->{roots.prepare(id,pin);return null;});ready.whenComplete((v,e)->{if(e!=null)close();});}
        public boolean isDone(){return ready.isDone();}
        public long residentBytes(){var value=pin.snapshot;return value==null?0:value.bytes();}
        public void join(){if(!ready.isDone()&&(Thread.currentThread().getName().equals("native-game-owner")||Thread.currentThread().getName().equals("world-tick")))throw new IllegalStateException("inventory_lease_not_ready");ready.join();if(released.get())throw new IllegalStateException("inventory_lease_closed");}
        @Override public void close(){if(released.compareAndSet(false,true))roots.release(id,pin);}
    }
    public Lease prepare(UUID id){return new Lease(Objects.requireNonNull(id));}
    public void repairVitalsReceipts(szserver.persistence.CharacterLocations locations)throws Exception{
        Map<UUID,VitalsReceipt> batch=new LinkedHashMap<>();
        roots.visitMarkers(1,(id,revision,state)->{VitalsReceipt receipt=VitalsReceipt.read(state,revision);if(receipt==null)return;
            batch.put(id,receipt);if(batch.size()==szserver.persistence.CharacterLocations.BATCH){locations.recoverVitals(batch).get(30,TimeUnit.SECONDS);batch.clear();}
        });
        if(!batch.isEmpty())locations.recoverVitals(batch).get(30,TimeUnit.SECONDS);
    }
    public AdminGiveReceipt adminPending(UUID id){var value=roots.get(id);return AdminGiveReceipt.pending(id,value.gameplay(),value.inventory().revision());}
    public void repairAdminReceipts(AdminGiveReceipts receipts)throws Exception{roots.visitMarkers(2,(id,revision,state)->{AdminGiveReceipt receipt=AdminGiveReceipt.pending(id,state,revision);if(receipt!=null)receipts.record(receipt);});}
    public FpvReceipt fpvPending(UUID id){var value=roots.get(id);FpvReceipt receipt=FpvReceipt.pending(id,value.gameplay());if(receipt!=null&&receipt.afterRevision()>value.inventory().revision())throw new IllegalArgumentException("fpv_receipt_revision_ahead");return receipt;}
    public GrenadeReceipt grenadePending(UUID id){var value=roots.get(id);return GrenadeReceipt.pending(id,value.gameplay(),value.inventory().revision());}
    public void repairGrenadeReceipts(GrenadeOutbox outbox)throws Exception{roots.visitMarkers(8,(id,revision,state)->{GrenadeReceipt receipt=GrenadeReceipt.pending(id,state,revision);if(receipt!=null)outbox.repair(receipt);});}
    @FunctionalInterface public interface RootVisitor{void accept(UUID id,Inventory inventory,JsonObject gameplay)throws Exception;}
    public void visitRoots(RootVisitor visitor)throws Exception{roots.visit((id,value)->visitor.accept(id,value.inventory(),value.gameplay().deepCopy()));}
    public CompletableFuture<Void> commit(UUID id,Inventory.Plan plan){return commit(id,plan,null);}
    public CompletableFuture<Void> commit(UUID id,Inventory.Plan plan,JsonObject progress){return commit(id,plan,progress,null);}
    public Map<UUID,WorldItem> physicalItems(){return physicalItems.view();}
    public CompletableFuture<Void> commitWorldItem(UUID id,Inventory.Plan plan,WorldItem spawn,UUID claim){return commit(id,plan,null,null,spawn,claim);}
    public CompletableFuture<Void> settleWorldItem(UUID id,double x,double y,double z){JsonObject pose=new JsonObject();pose.addProperty("id",id.toString());pose.addProperty("x",x);pose.addProperty("y",y);pose.addProperty("z",z);JsonObject event=new JsonObject();event.add("worldItemPose",pose);return journal.appendChecked(event,()->{WorldItem before=physicalItems.view().get(id);if(before!=null)before.resting(x,y,z);},()->{physicalItems.standalone(event);appended(szserver.util.Json.bytes(event).length);});}
    public CompletableFuture<Void> expireWorldItem(UUID id,long now){JsonObject event=new JsonObject();event.addProperty("worldItemExpire",id.toString());event.addProperty("at",now);return journal.appendChecked(event,()->{WorldItem item=physicalItems.view().get(id);if(item!=null&&now<item.expires())throw new IllegalArgumentException("world_item_early_expiry");},()->{physicalItems.standalone(event);appended(szserver.util.Json.bytes(event).length);});}
    public CompletableFuture<Void> commit(UUID id,Inventory.Plan plan,JsonObject progress,WorldDrop drop){return commit(id,plan,progress,drop,null,null);}
    private CompletableFuture<Void> commit(UUID id,Inventory.Plan plan,JsonObject progress,WorldDrop drop,WorldItem spawn,UUID claim){JsonObject event=new JsonObject();event.addProperty("character",id.toString());event.addProperty("revision",plan.after().revision());JsonArray changes=new JsonArray();
        if(drop!=null)event.add("drop",drop.json());if(spawn!=null)event.add("worldItemSpawn",spawn.json());if(claim!=null)event.addProperty("worldItemClaim",claim.toString());
        JsonObject saved=progress==null?null:progress.deepCopy();if(saved!=null)event.add("gameplay",saved);
        for(var e:plan.changes().entrySet())changes.add(row(e));event.add("changes",changes);
        long[] bytes={0};return journal.appendPreparedChecked(()->{JsonObject stored=transactions.store(event);bytes[0]=szserver.util.Json.bytes(stored).length;return stored;},()->{Inventory previous=load(id);if(plan.after().revision()!=previous.revision()+1)throw new IllegalArgumentException("inventory_revision_conflict");physicalItems.validate(event,id,previous,plan);if(drop!=null){validateDropMutation(id,previous,plan,drop);validateDrop(drop);}},()->{roots.record(event,plan.after(),saved==null?roots.get(id).gameplay():saved);if(drop!=null)publishDrop(drop);physicalItems.apply(event);appended(bytes[0]);});
    }
    private static void validateDropMutation(UUID id,Inventory previous,Inventory.Plan plan,WorldDrop drop){
        if(!id.equals(drop.character())||plan.changes().size()!=1)throw new IllegalArgumentException("world_drop_inventory_mismatch");
        var entry=plan.changes().entrySet().iterator().next();Inventory.Address address=entry.getKey();ItemStack before=previous.at(address),after=entry.getValue();
        if(address.aggregate()!=1||address.container()!=0||before==null||!before.mergeable(drop.stack())||after!=null&&!before.mergeable(after)||before.count()-(after==null?0:after.count())!=drop.stack().count())throw new IllegalArgumentException("world_drop_inventory_mismatch");
    }
    private void validateDrop(WorldDrop drop){
        try{if(drops.contains(drop.id()))throw new IllegalArgumentException("world_drop_duplicate");}
        catch(IOException error){throw new UncheckedIOException(error);}
        validateCapacity(1,szserver.util.Json.bytes(drop.json()).length);
    }
    private void validateCapacity(long count,long bytes){if(dropCountLimit>0&&drops.count()+count>dropCountLimit||dropByteLimit>0&&drops.bytes()+bytes>dropByteLimit)throw new IllegalArgumentException("world_drop_capacity");}
    private void publishDrop(WorldDrop drop){try{drops.add(drop);}catch(IOException error){throw new UncheckedIOException(error);}}
    /** Offline seed of already removed old items, preserving their original UUID and state. */
    public CompletableFuture<Void> importDrop(WorldDrop drop){JsonObject event=new JsonObject();event.add("dropSnapshot",drop.json());szserver.util.Json.parse(szserver.util.Json.bytes(event));return journal.appendChecked(event,()->{if(!roots.contains(drop.character()))throw new IllegalArgumentException("world_drop_character_missing");validateDrop(drop);},()->{publishDrop(drop);appended(szserver.util.Json.bytes(event).length);});}
    public List<WorldDrop> drops(UUID character){try{return drops.list(character);}catch(IOException error){throw new UncheckedIOException(error);}}
    /** Worker-only archive iteration; keeps large exports/checkpoints independent of archive body size. */
    public void visitDrops(java.util.function.Consumer<WorldDrop> visitor)throws Exception{drops.visit(visitor::accept);}
    /** Authorized administrator creation, separate from the ordinary debit-backed drop format. */
    public CompletableFuture<Void> grantDrops(UUID id,Inventory.Plan plan,List<WorldDrop> requested){
        List<WorldDrop> granted=List.copyOf(requested);JsonObject event=new JsonObject();event.addProperty("character",id.toString());event.addProperty("revision",plan.after().revision());event.add("changes",new JsonArray());JsonArray rows=new JsonArray();for(WorldDrop drop:granted)rows.add(drop.json());event.add("grantedDrops",rows);
        long[] bytes={0};return journal.appendPreparedChecked(()->{JsonObject stored=transactions.store(event);bytes[0]=szserver.util.Json.bytes(stored).length;return stored;},()->{if(plan.after().revision()!=load(id).revision()+1)throw new IllegalArgumentException("inventory_revision_conflict");validateGrantedDrops(id,plan,granted);},()->{roots.record(event,plan.after(),roots.get(id).gameplay());for(WorldDrop drop:granted)publishDrop(drop);appended(bytes[0]);});
    }
    private void validateGrantedDrops(UUID id,Inventory.Plan plan,List<WorldDrop> granted){
        if(!plan.changes().isEmpty()||granted.isEmpty()||granted.size()>160)throw new IllegalArgumentException("admin_drop_transaction");
        Set<UUID> seen=new HashSet<>();long bytes=0;for(WorldDrop drop:granted){if(!id.equals(drop.character())||!seen.add(drop.id())||drop.stack().count()>catalog.get(drop.stack().id()).stackLimit())throw new IllegalArgumentException("admin_drop_stack");validateDrop(drop);bytes+=szserver.util.Json.bytes(drop.json()).length;}
        validateCapacity(granted.size(),bytes);
    }
    private static JsonObject row(Map.Entry<Inventory.Address,ItemStack> e){JsonObject row=new JsonObject();row.addProperty("aggregate",e.getKey().aggregate());row.addProperty("container",e.getKey().container());row.addProperty("slot",e.getKey().slot());row.addProperty("deleted",e.getValue()==null);if(e.getValue()!=null)row.add("stack",e.getValue().json());return row;}
    /** Coalesced callers await the real publication; cancelling a caller's copy cannot cancel storage work. */
    public synchronized CompletableFuture<Void> compact(){
        if(compacting.get())return checkpointFuture.copy();compacting.set(true);CompletableFuture<Void> owned=new CompletableFuture<>();checkpointFuture=owned;
        journal.compactStreaming(this::checkpoint).whenComplete((ignored,error)->{if(error==null){compactions++;compactionError="";growthBytes=0;checkpointBaseBytes=8+((Number)journal.metrics().get("checkpointBytes")).longValue();}else{compactionFailures++;compactionError=error.getClass().getSimpleName();}compacting.set(false);if(error==null)owned.complete(null);else owned.completeExceptionally(error);});
        return owned.copy();
    }
    /** Durable maps change only on the same journal owner. No owner/gameplay lock or whole-store JSON list. */
    private void checkpoint(Journal.CheckpointSink sink)throws Exception {
        roots.visit((id,value)->writeRoot(id,value.inventory(),value.gameplay(),sink));
        for(WorldItem item:physicalItems.view().values()){JsonObject event=new JsonObject();event.add("worldItemSnapshot",item.json());sink.accept(event);}
        drops.visit(drop->{JsonObject event=new JsonObject();event.add("dropSnapshot",drop.json());sink.accept(event);});
    }
    static void writeRoot(UUID id,Inventory inventory,JsonObject gameplay,Journal.CheckpointSink sink)throws Exception{
        long revision=inventory.revision();boolean first=true;
        if(!gameplay.isEmpty()){JsonObject event=checkpointPart(id,revision,new JsonArray(),true,false);event.add("gameplay",gameplay.deepCopy());sink.accept(event);first=false;}
        JsonArray rows=new JsonArray();int bytes=0;
        for(var entry:inventory.items().entrySet()){JsonObject row=row(entry);int size=szserver.util.Json.bytes(row).length;
            if((bytes+size>48000||rows.size()>=200)&&!rows.isEmpty()){sink.accept(checkpointPart(id,revision,rows,first,false));first=false;rows=new JsonArray();bytes=0;}
            rows.add(row);bytes+=size+1;
        }
        sink.accept(checkpointPart(id,revision,rows,first,true));
    }
    private static JsonObject checkpointPart(UUID id,long revision,JsonArray rows,boolean first,boolean last){JsonObject event=new JsonObject();event.addProperty("character",id.toString());event.addProperty("revision",revision);event.addProperty("snapshot",true);event.addProperty("first",first);event.addProperty("last",last);event.add("changes",rows);return event;}
    static Map<Inventory.Address,ItemStack> readChanges(JsonObject event){Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();for(JsonElement element:event.getAsJsonArray("changes")){JsonObject row=element.getAsJsonObject();changes.put(new Inventory.Address(row.get("aggregate").getAsInt(),row.get("container").getAsInt(),row.get("slot").getAsInt()),!row.has("stack")||row.get("stack").isJsonNull()?null:ItemStack.read(row.getAsJsonObject("stack")));}return changes;}
    private long checkpointGrowthThreshold(){return Math.max(4L*1024*1024,checkpointBaseBytes/2);}
    /** Runs only after fsync on the inventory journal owner, never from a game tick. */
    private void appended(long bytes){appends++;growthBytes+=8+bytes;if(growthBytes>=checkpointGrowthThreshold())compact();}
    public Map<String,Object> metrics(){var m=new LinkedHashMap<String,Object>();m.put("writer",journal.metrics());m.put("rootDirectory",roots.metrics());m.put("compactions",compactions);m.put("compacting",compacting.get());m.put("compactionFailures",compactionFailures);m.put("compactionError",compactionError);m.put("drops",drops.metrics().get("rows"));m.put("dropBytes",drops.metrics().get("jsonBytes"));m.put("dropArchive",drops.metrics());m.put("dropCountLimit",dropCountLimit);m.put("dropByteLimit",dropByteLimit);m.put("checkpointGrowthBytes",growthBytes);m.put("checkpointGrowthThreshold",checkpointGrowthThreshold());return Collections.unmodifiableMap(m);}
    @Override public void close()throws Exception{journal.closeWith(()->{try{roots.close();}finally{drops.close();}});}
}
