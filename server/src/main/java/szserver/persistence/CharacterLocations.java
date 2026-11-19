package szserver.persistence;

import com.google.gson.*;
import szserver.world.World.Vec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Coalesced movement, durable spawn commands. Journal IO has a separate owner. */
public final class CharacterLocations implements AutoCloseable {
    /** Native BSCPCRAFT units; Minecraft compatibility health/slot fields are stored separately. */
    public record PlayerState(float yaw,float pitch,float health,int hand,boolean holstered,long damagedAt,float stamina,szserver.net.SicknessState sickness){
        public PlayerState(float yaw,float pitch,float health,int hand,boolean holstered,long damagedAt,float stamina){this(yaw,pitch,health,hand,holstered,damagedAt,stamina,szserver.net.SicknessState.EMPTY);}
        public PlayerState(float yaw,float pitch,float health,int hand,boolean holstered,long damagedAt){this(yaw,pitch,health,hand,holstered,damagedAt,1);}
        public PlayerState{Objects.requireNonNull(sickness);if(!Float.isFinite(yaw)||!Float.isFinite(pitch)||!Float.isFinite(health)||health<0||health>10000||!Float.isFinite(stamina)||stamina<0||stamina>100||!Set.of(4,5,6,7,10).contains(hand)||damagedAt<0)throw new IllegalArgumentException("invalid_native_player_state");}
        public JsonObject json(){JsonObject value=new JsonObject();value.addProperty("yaw",yaw);value.addProperty("pitch",pitch);value.addProperty("health",health);value.addProperty("hand",hand);value.addProperty("holstered",holstered);value.addProperty("damagedAt",damagedAt);value.addProperty("stamina",stamina);if(!sickness.states().isEmpty())value.add("sickness",sickness.json());return value;}
        public static PlayerState read(JsonObject value){return new PlayerState(value.get("yaw").getAsFloat(),value.get("pitch").getAsFloat(),value.get("health").getAsFloat(),szserver.util.Json.integer(value,"hand",0,10),value.get("holstered").getAsBoolean(),value.get("damagedAt").getAsBigDecimal().longValueExact(),value.has("stamina")?value.get("stamina").getAsFloat():1,value.has("sickness")?szserver.net.SicknessState.read(value.getAsJsonObject("sickness")):szserver.net.SicknessState.EMPTY);}
    }
    public static final int LIMIT=4096,BATCH=32,HOT_LIMIT=256;
    private final int countLimit;
    private final LinkedHashMap<String,Boolean> loaded=new LinkedHashMap<>(256,.75f,true);
    private final Map<String,Integer> heldLoads=new HashMap<>();private int activeOperations;
    private final Semaphore loadRequests=new Semaphore(128);
    private final DiskLocationIndex index;
    private final Map<String,Vec> positions=new LinkedHashMap<>(),spawns=new LinkedHashMap<>(),dirty=new LinkedHashMap<>();
    private final Map<String,Vec> durablePositions=new LinkedHashMap<>();
    private final Map<String,PlayerState> states=new LinkedHashMap<>(),durableStates=new LinkedHashMap<>();
    private final Map<String,Long> vitalsRevisions=new LinkedHashMap<>(),durableVitalsRevisions=new LinkedHashMap<>();
    private final Map<String,Integer> vitalsHeld=new HashMap<>(),vitalsPending=new HashMap<>();
    private static final int STATE_QUEUE=128;
    public static final class StateSave {
        private final CompletableFuture<Void> future;private StateSave(CompletableFuture<Void> future){this.future=future;}
        public boolean isDone(){return future.isDone();}public void join(){future.join();}public CompletableFuture<Void> copyFuture(){return future.copy();}
    }
    private static final class StateWrite {PlayerState state;final CompletableFuture<Void> future=new CompletableFuture<>();final StateSave save=new StateSave(future);StateWrite(PlayerState state){this.state=state;}}
    private final Map<String,StateWrite> stateWrites=new LinkedHashMap<>();
    private final Set<String> stateWritesInFlight=new HashSet<>();
    private long stateRequests,stateCoalesced,stateSaved,stateNoops,stateRejected;
    private final Map<String,LegacyPlayerArchive.Ref> legacyPlayers=new LinkedHashMap<>();
    private final Map<String,Integer> positionPending=new HashMap<>();
    private final Map<String,Integer> spawnPending=new HashMap<>();
    private final ThreadPoolExecutor loadWorker=szserver.util.Workers.pool("location-load",1,128);
    private final ThreadPoolExecutor legacyWorker=szserver.util.Workers.pool("location-legacy",1,32);
    private final Object closeLock=new Object();private volatile boolean closing,closed;
    private final LegacyPlayerArchive legacyArchive;
    private final Set<String> legacyPending=new HashSet<>(),retiring=new HashSet<>();
    private final Map<String,PlayerState> dirtyStates=new LinkedHashMap<>();
    private final Semaphore spawnRequests=new Semaphore(32);
    private final Journal journal;
    private CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    private volatile boolean failed;
    private volatile long journalBytes,nextCheckpointAt=1024L*1024;private long checkpointBytesWritten;
    private CompletableFuture<Void> checkpointPending=CompletableFuture.completedFuture(null);
    private final Set<String> movementInFlight=new HashSet<>();
    private int batches;
    public CharacterLocations(Path data,String worldIdentity)throws Exception{this(data,worldIdentity,0,0);}
    public CharacterLocations(Path data,String worldIdentity,long testDelay)throws Exception{this(data,worldIdentity,testDelay,0);}
    public CharacterLocations(Path data,String worldIdentity,long testDelay,int countLimit)throws Exception{
        if(worldIdentity==null||worldIdentity.isBlank()||countLimit<0)throw new IllegalArgumentException("world_identity_or_limit_required");this.countLimit=countLimit;
        String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(worldIdentity.getBytes(StandardCharsets.UTF_8)));
        Path path=data.resolve("locations-"+key+".sjournal");legacyArchive=new LegacyPlayerArchive(path);journal=new Journal(path,testDelay,event->{});DiskLocationIndex opened=null;
        try{opened=new DiskLocationIndex(path);index=opened;journal.replayLocated((reference,event)->recover(event)).get();journalBytes=Files.size(path);var global=index.get("*");if(global!=null&&global.spawn()!=null)spawns.put("*",global.spawn());}
        catch(Exception|Error error){loadWorker.shutdownNow();legacyWorker.shutdownNow();if(opened!=null)opened.close();journal.close();throw error;}
    }
    /** A native session retains its hot root from prepare until disconnect. Closing an unfinished lease is safe. */
    public final class Lease implements AutoCloseable{
        private final String key;private final boolean reserved;private boolean released;private final CompletableFuture<Void> ready=new CompletableFuture<>();
        private Lease(String key,boolean reserved){this.key=key;this.reserved=reserved;}
        public boolean isDone(){return ready.isDone();}public void join(){ready.join();}
        @Override public void close(){synchronized(CharacterLocations.this){if(released)return;released=true;if(reserved){Integer previous=heldLoads.get(key);if(previous!=null){int n=previous-1;if(n==0)heldLoads.remove(key);else heldLoads.put(key,n);}loadRequests.release();}ready.cancel(false);evictLoaded();}}
    }
    public Lease prepare(UUID id){String key=id.toString();Lease lease;synchronized(this){if(closing||!healthy()||retiring.contains(key)||!loadRequests.tryAcquire()){lease=new Lease(key,false);lease.ready.completeExceptionally(new IllegalStateException("location_prepare_unavailable"));return lease;}heldLoads.merge(key,1,Integer::sum);lease=new Lease(key,true);}
        try{loadWorker.execute(()->{try{ensureLoaded(id);lease.ready.complete(null);}catch(Throwable error){lease.ready.completeExceptionally(error);}});}catch(RejectedExecutionException error){lease.ready.completeExceptionally(error);lease.close();}return lease;
    }
    private void ensureLoaded(UUID id){String key=id.toString();synchronized(this){if(closed)throw new IllegalStateException("location_closed");if(loaded.get(key)!=null)return;if(retiring.contains(key))throw new IllegalStateException("character_deleting");}
        String thread=Thread.currentThread().getName();if(thread.equals("native-game-owner")||thread.equals("world-tick"))throw new IllegalStateException("location_not_prepared_on_owner");
        DiskLocationIndex.Root root;try{root=index.get(key);}catch(java.io.IOException error){failed=true;throw new java.io.UncheckedIOException(error);}
        synchronized(this){if(closed)throw new IllegalStateException("location_closed");if(loaded.get(key)!=null)return;if(retiring.contains(key))throw new IllegalStateException("character_deleting");loaded.put(key,true);
            if(root!=null){if(root.position()!=null){positions.put(key,root.position());durablePositions.put(key,root.position());}if(root.state()!=null){states.put(key,root.state());durableStates.put(key,root.state());}if(root.revision()>0){vitalsRevisions.put(key,root.revision());durableVitalsRevisions.put(key,root.revision());}if(root.spawn()!=null)spawns.put(key,root.spawn());if(root.legacy()!=null)legacyPlayers.put(key,root.legacy());}evictLoaded();
        }
    }
    private <T>T withLoaded(Collection<UUID> ids,java.util.function.Supplier<T> action){
        List<String> keys=new ArrayList<>(ids.size());synchronized(this){if(closing||closed)throw new IllegalStateException("location_closed");activeOperations++;for(UUID id:ids)if(id!=null){String key=id.toString();heldLoads.merge(key,1,Integer::sum);keys.add(key);}}
        try{for(UUID id:ids)if(id!=null)ensureLoaded(id);return action.get();}
        finally{synchronized(this){for(String key:keys){Integer n=heldLoads.get(key);if(n!=null){if(n==1)heldLoads.remove(key);else heldLoads.put(key,n-1);}}activeOperations--;notifyAll();evictLoaded();}}
    }
    private CompletableFuture<Void> loadedBatch(Map<UUID,?> values,String error,java.util.function.Supplier<CompletableFuture<Void>> action){
        if(values==null||values.isEmpty()||values.size()>BATCH)return CompletableFuture.failedFuture(new IllegalArgumentException(error));
        try{return withLoaded(values.keySet(),action);}catch(RuntimeException rejected){return CompletableFuture.failedFuture(rejected);}
    }
    private boolean retained(String key){return retiring.contains(key)||heldLoads.containsKey(key)||movementInFlight.contains(key)||positionPending.containsKey(key)||spawnPending.containsKey(key)||legacyPending.contains(key)||vitalsHeld.containsKey(key)||vitalsPending.containsKey(key)||stateWrites.containsKey(key)||stateWritesInFlight.contains(key)||dirty.containsKey(key)||dirtyStates.containsKey(key);}
    private void evictLoaded(){if(loaded.size()<=HOT_LIMIT)return;var cursor=loaded.keySet().iterator();while(cursor.hasNext()&&loaded.size()>HOT_LIMIT){String key=cursor.next();if(retained(key))continue;cursor.remove();forget(key);}}
    private void hotPoints(Map<String,Vec> target,Map<String,Vec> values){values.forEach((key,point)->{if(key.equals("*")||loaded.containsKey(key))target.put(key,point);});}
    private void applyIndex(JsonObject event){index.apply(event);}
    /** Store.set_spawn accepts finite authored coordinates in the full old world domain.
     * Live placement validates collisions and the native terrain's vertical range separately. */
    public static Vec valid(Vec p){if(p==null||!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z())||Math.abs(p.x())>30_000_000||Math.abs(p.y())>30_000_000||Math.abs(p.z())>30_000_000)throw new IllegalArgumentException("invalid_character_position");return p;}
    public static Vec validPosition(Vec p){if(p==null||!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z())||Math.abs(p.x())>30_000_001||Math.abs(p.y())>30_000_001||Math.abs(p.z())>30_000_001)throw new IllegalArgumentException("invalid_character_position");return p;}
    private static JsonObject entry(String scope,Vec p){JsonObject row=new JsonObject();row.addProperty("scope",scope);row.addProperty("x",p.x());row.addProperty("y",p.y());row.addProperty("z",p.z());return row;}
    private static JsonObject event(String type,Map<String,Vec> values){JsonObject event=new JsonObject();event.addProperty("type",type);JsonArray rows=new JsonArray();values.forEach((scope,p)->rows.add(entry(scope,p)));event.add("rows",rows);return event;}
    private static JsonObject validateLegacy(String key,JsonObject saved){
        if(!key.equals(saved.get("character_id").getAsString())||szserver.util.Json.bytes(saved).length>48000)throw new IllegalArgumentException("legacy_player_row_limit_or_owner");return saved;
    }
    private void recover(JsonObject event){String type=event.get("type").getAsString();batches++;
        if(type.equals("legacy-players")){JsonArray rows=event.getAsJsonArray("rows");if(rows.isEmpty()||rows.size()>BATCH)throw new IllegalArgumentException("legacy_player_batch_limit");try{for(JsonElement item:rows){JsonObject row=item.getAsJsonObject();String scope=UUID.fromString(row.get("scope").getAsString()).toString();LegacyPlayerArchive.Ref ref;if(row.has("playerArchive")){ref=LegacyPlayerArchive.Ref.read(row.getAsJsonObject("playerArchive"));validateLegacy(scope,legacyArchive.read(ref));}else{if(rows.size()!=1)throw new IllegalArgumentException("legacy_player_inline_batch_limit");ref=legacyArchive.store(validateLegacy(scope,row.getAsJsonObject("player")));row.remove("player");row.add("playerArchive",ref.json());}}}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
        applyIndex(event);if(countLimit>0&&(index.positionCount()>countLimit||index.legacyCount()>countLimit||index.spawnCount()>countLimit+1))throw new IllegalArgumentException("location_limit");
    }
    public synchronized void holdDeletion(UUID id){String key=id.toString();retiring.add(key);dirty.remove(key);dirtyStates.remove(key);}
    public synchronized void releaseDeletion(UUID id){retiring.remove(id.toString());}
    public synchronized boolean deletionReady(UUID id){String key=id.toString();return !positionPending.containsKey(key)&&!spawnPending.containsKey(key)&&!legacyPending.contains(key)&&!vitalsHeld.containsKey(key)&&!vitalsPending.containsKey(key)&&!stateWrites.containsKey(key)&&!stateWritesInFlight.contains(key);}
    private void forget(String key){loaded.remove(key);positions.remove(key);durablePositions.remove(key);states.remove(key);durableStates.remove(key);vitalsRevisions.remove(key);durableVitalsRevisions.remove(key);spawns.remove(key);legacyPlayers.remove(key);dirty.remove(key);dirtyStates.remove(key);}
    public synchronized CompletableFuture<Void> deleteCharacter(UUID id){
        String key=id.toString();if(!deletionReady(id))return CompletableFuture.failedFuture(new IllegalStateException("character_delete_not_quiescent"));
        if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));
        dirty.remove(key);dirtyStates.remove(key);JsonObject event=new JsonObject();event.addProperty("type","delete-character");event.addProperty("scope",key);
        return journal.append(event,()->{applyIndex(event);journalBytes+=8+szserver.util.Json.bytes(event).length;synchronized(this){forget(key);}autoCheckpoint();}).copy();
    }
    public void observeState(UUID id,PlayerState state){withLoaded(List.of(id),()->{observeStateLoaded(id,state);return null;});}
    private synchronized void observeStateLoaded(UUID id,PlayerState state){Objects.requireNonNull(state);if(closing||!healthy())throw new IllegalStateException("location_storage_failed");String key=id.toString();if(retiring.contains(key))throw new IllegalStateException("character_deleting");if(!positions.containsKey(key))throw new IllegalArgumentException("player_state_without_position");if(!vitalsHeld.containsKey(key)&&!state.equals(states.get(key))){states.put(key,state);dirtyStates.put(key,state);}}
    public Vec position(UUID id){return withLoaded(List.of(id),()->positionLoaded(id));}
    private synchronized Vec positionLoaded(UUID id){return positions.get(id.toString());}
    public PlayerState state(UUID id){return withLoaded(List.of(id),()->stateLoaded(id));}
    private synchronized PlayerState stateLoaded(UUID id){return states.get(id.toString());}
    public PlayerState durableState(UUID id){return withLoaded(List.of(id),()->durableStateLoaded(id));}
    private synchronized PlayerState durableStateLoaded(UUID id){return durableStates.get(id.toString());}
    public synchronized boolean stateSavePending(UUID id){String key=id.toString();return stateWrites.containsKey(key)||stateWritesInFlight.contains(key);}
    /** One pending snapshot per owner; acknowledgement covers the captured latest state, never a prior append. */
    public CompletableFuture<Void> saveState(UUID id,PlayerState state){return requestState(id,state).copyFuture();}
    /** The native owner reuses this read-only handle instead of retaining a future dependent on every tick. */
    public StateSave requestState(UUID id,PlayerState state){return withLoaded(List.of(id),()->requestStateLoaded(id,state));}
    private synchronized StateSave requestStateLoaded(UUID id,PlayerState state){
        String key=id.toString();Objects.requireNonNull(state);
        if(closing||!healthy()||retiring.contains(key)||!positions.containsKey(key))return new StateSave(CompletableFuture.failedFuture(new IllegalStateException("state_save_unavailable")));
        StateWrite request=stateWrites.get(key);
        if(request==null){
            if(stateWrites.size()>=STATE_QUEUE){stateRejected++;return new StateSave(CompletableFuture.failedFuture(new IllegalStateException("state_save_queue_full")));}
            if(!stateWritesInFlight.contains(key)&&!vitalsHeld.containsKey(key)&&!vitalsPending.containsKey(key)&&state.equals(durableStates.get(key))){observeStateLoaded(id,state);stateNoops++;return new StateSave(CompletableFuture.completedFuture(null));}
            request=new StateWrite(state);stateWrites.put(key,request);
        }else{request.state=state;stateCoalesced++;}
        stateRequests++;observeStateLoaded(id,state);return request.save;
    }
    /** Called by the gameplay owner after changes are collected; no journal IO on this thread. */
    public synchronized void advanceStateWrites(){
        if(!healthy()){var queued=List.copyOf(stateWrites.values());stateWrites.clear();queued.forEach(r->r.future.completeExceptionally(new IllegalStateException("location_storage_failed")));return;}
        List<StateWrite> settled=new ArrayList<>();var iterator=stateWrites.entrySet().iterator();while(iterator.hasNext()){
            var entry=iterator.next();String key=entry.getKey();if(vitalsHeld.containsKey(key)||vitalsPending.containsKey(key)||stateWritesInFlight.contains(key))continue;
            if(Objects.equals(states.get(key),durableStates.get(key))&&Objects.equals(vitalsRevisions.get(key),durableVitalsRevisions.get(key))){iterator.remove();dirtyStates.remove(key);stateNoops++;settled.add(entry.getValue());}
        }
        settled.forEach(request->request.future.complete(null));
        if(stateWrites.keySet().stream().anyMatch(key->!vitalsHeld.containsKey(key)&&!vitalsPending.containsKey(key)))flush();
    }
    public synchronized Map<String,Object> stateWriteMetrics(){return Map.of("queued",stateWrites.size(),"inFlight",stateWritesInFlight.size(),"limit",STATE_QUEUE,"requests",stateRequests,"coalesced",stateCoalesced,"saved",stateSaved,"noops",stateNoops,"rejected",stateRejected);}
    private void captureVitalsRevision(String key,JsonObject row,Map<String,Long> captured){Long revision=vitalsRevisions.get(key);if(revision!=null){row.addProperty("vitalsRevision",revision);captured.put(key,revision);}}
    /** Preserve position traffic, but later HP snapshots cannot overtake an uncommitted inventory transaction. */
    public void holdVitals(UUID id){withLoaded(List.of(id),()->{holdVitalsLoaded(id);return null;});}
    private synchronized void holdVitalsLoaded(UUID id){String key=id.toString();if(closing||!healthy()||retiring.contains(key)||!positions.containsKey(key))throw new IllegalStateException("vitals_admission_unavailable");vitalsHeld.merge(key,1,Integer::sum);}
    public synchronized void releaseVitals(UUID id){String key=id.toString();Integer count=vitalsHeld.get(key);if(count==null)throw new IllegalStateException("vitals_fence_missing");if(count==1){vitalsHeld.remove(key);StateWrite deferred=stateWrites.get(key);if(deferred!=null){states.put(key,deferred.state);dirtyStates.put(key,deferred.state);}}else vitalsHeld.put(key,count-1);}
    public long vitalsRevision(UUID id){return withLoaded(List.of(id),()->{synchronized(this){return vitalsRevisions.getOrDefault(id.toString(),0L);}});}
    /** Receipt and current state share one locations append; cancellation never cancels accepted work. */
    public CompletableFuture<Void> commitVitals(UUID id,szserver.inventory.VitalsReceipt receipt,PlayerState current){return withLoaded(List.of(id),()->commitVitalsLoaded(id,receipt,current));}
    private synchronized CompletableFuture<Void> commitVitalsLoaded(UUID id,szserver.inventory.VitalsReceipt receipt,PlayerState current){
        String key=id.toString();if(closing||!healthy()||!positions.containsKey(key))return CompletableFuture.failedFuture(new IllegalStateException("vitals_storage_unavailable"));
        if(receipt.revision()<vitalsRevisions.getOrDefault(key,0L))return CompletableFuture.failedFuture(new IllegalStateException("vitals_revision_regressed"));
        states.put(key,current);vitalsRevisions.put(key,receipt.revision());dirtyStates.remove(key);vitalsPending.merge(key,1,Integer::sum);
        StateWrite deferred=stateWrites.get(key);if(deferred!=null)deferred.state=current;
        CompletableFuture<Void> write=append("positions",Map.of(),Map.of(key,current),()->{});
        write.whenComplete((done,error)->{synchronized(this){int n=vitalsPending.get(key)-1;if(n==0)vitalsPending.remove(key);else vitalsPending.put(key,n);evictLoaded();}if(error!=null)failed=true;});return write.copy();
    }
    /** Startup-only bounded replay. A newer ordinary snapshot carries the receipt marker and wins. */
    public CompletableFuture<Void> recoverVitals(Map<UUID,szserver.inventory.VitalsReceipt> receipts){if(receipts!=null&&receipts.isEmpty())return CompletableFuture.completedFuture(null);return loadedBatch(receipts,"vitals_recovery_batch_limit",()->recoverVitalsLoaded(receipts));}
    private synchronized CompletableFuture<Void> recoverVitalsLoaded(Map<UUID,szserver.inventory.VitalsReceipt> receipts){
        if(closing||!healthy()||receipts.size()>BATCH)return CompletableFuture.failedFuture(new IllegalStateException("vitals_recovery_unavailable"));
        Map<String,PlayerState> batch=new LinkedHashMap<>();
        try{for(var entry:receipts.entrySet()){
            String key=entry.getKey().toString();var receipt=entry.getValue();long applied=vitalsRevisions.getOrDefault(key,0L);
            if(applied>receipt.revision())throw new IllegalStateException("vitals_receipt_behind_locations");if(applied==receipt.revision())continue;
            if(!positions.containsKey(key))throw new IllegalStateException("vitals_receipt_without_position");
            batch.put(key,receipt.recover(states.get(key)));
        }}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        if(batch.isEmpty())return CompletableFuture.completedFuture(null);
        batch.forEach((key,state)->{states.put(key,state);vitalsRevisions.put(key,receipts.get(UUID.fromString(key)).revision());vitalsPending.merge(key,1,Integer::sum);});
        CompletableFuture<Void> write=append("positions",Map.of(),batch,()->{});write.whenComplete((done,error)->{synchronized(this){batch.keySet().forEach(key->{int n=vitalsPending.get(key)-1;if(n==0)vitalsPending.remove(key);else vitalsPending.put(key,n);});evictLoaded();}if(error!=null)failed=true;});return write.copy();
    }
    public JsonObject legacyPlayer(UUID id){LegacyPlayerArchive.Ref value=withLoaded(List.of(id),()->{synchronized(this){return legacyPlayers.get(id.toString());}});try{return value==null?null:legacyArchive.read(value);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}
    public void observe(UUID id,Vec position,PlayerState state){withLoaded(List.of(id),()->{observePrepared(id,position,state);return null;});}
    private synchronized void observePrepared(UUID id,Vec position,PlayerState state){Objects.requireNonNull(state);observeLoaded(id,position);String key=id.toString();if(!vitalsHeld.containsKey(key)&&!state.equals(states.get(key))){states.put(key,state);dirty.put(key,position);}}
    /** Offline migration retains the complete compatibility row without replacing hot core coordinates. */
    public CompletableFuture<Void> importLegacyPlayer(UUID id,JsonObject value){return importLegacyPlayers(Map.of(id,value));}
    /** Bounded offline batch. Body publication/fsync precedes one journal reference append. */
    public CompletableFuture<Void> importLegacyPlayers(Map<UUID,JsonObject> values){return loadedBatch(values,"legacy_player_batch_limit",()->importLegacyPlayersLoaded(values));}
    private synchronized CompletableFuture<Void> importLegacyPlayersLoaded(Map<UUID,JsonObject> values){
        if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));
        Map<String,JsonObject> copy=new LinkedHashMap<>();long bytes=0;
        try{if(values.isEmpty()||values.size()>BATCH)throw new IllegalArgumentException("legacy_player_batch_limit");
            for(var entry:values.entrySet()){String key=entry.getKey().toString();if(retiring.contains(key))throw new IllegalStateException("character_deleting");JsonObject value=validateLegacy(key,entry.getValue()).deepCopy();bytes+=szserver.util.Json.bytes(value).length;if(bytes>65536)throw new IllegalArgumentException("legacy_player_batch_bytes");if(legacyPlayers.containsKey(key)||legacyPending.contains(key))throw new IllegalArgumentException("legacy_player_duplicate_or_limit");copy.put(key,value);}
            if(countLimit>0&&index.legacyCount()+legacyPending.size()+copy.size()>countLimit)throw new IllegalArgumentException("legacy_player_duplicate_or_limit");
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        if(!spawnRequests.tryAcquire())return CompletableFuture.failedFuture(new IllegalStateException("location_command_queue_full"));legacyPending.addAll(copy.keySet());CompletableFuture<Void> result=new CompletableFuture<>();
        try{legacyWorker.execute(()->{Throwable problem=null;try{
            Map<String,LegacyPlayerArchive.Ref> refs=new LinkedHashMap<>();for(var entry:copy.entrySet())refs.put(entry.getKey(),legacyArchive.store(entry.getValue()));
            JsonObject event=legacyEvent(refs);journal.append(event,()->{applyIndex(event);journalBytes+=8+szserver.util.Json.bytes(event).length;synchronized(this){refs.forEach((key,ref)->{if(loaded.containsKey(key))legacyPlayers.put(key,ref);});batches++;}autoCheckpoint();}).get();
        }catch(Throwable error){Throwable cause=error instanceof ExecutionException?error.getCause():error;failed=true;problem=cause;}
        finally{synchronized(this){legacyPending.removeAll(copy.keySet());evictLoaded();}spawnRequests.release();}if(problem==null)result.complete(null);else result.completeExceptionally(problem);});}
        catch(RejectedExecutionException error){legacyPending.removeAll(copy.keySet());spawnRequests.release();result.completeExceptionally(error);}
        return result.copy();
    }
    private static JsonObject legacyEvent(Map<String,LegacyPlayerArchive.Ref> refs){JsonObject event=new JsonObject();event.addProperty("type","legacy-players");JsonArray rows=new JsonArray();refs.forEach((scope,ref)->{JsonObject row=new JsonObject();row.addProperty("scope",scope);row.add("playerArchive",ref.json());rows.add(row);});event.add("rows",rows);return event;}
    public Vec spawn(UUID id,Vec fallback){return withLoaded(List.of(id),()->spawnLoaded(id,fallback));}
    private synchronized Vec spawnLoaded(UUID id,Vec fallback){return spawns.getOrDefault(id.toString(),spawns.getOrDefault("*",fallback));}
    public List<Vec> spawnCandidates(UUID id,Vec fallback){return withLoaded(List.of(id),()->spawnCandidatesLoaded(id,fallback));}
    private synchronized List<Vec> spawnCandidatesLoaded(UUID id,Vec fallback){LinkedHashSet<Vec> candidates=new LinkedHashSet<>();Vec personal=spawns.get(id.toString()),global=spawns.get("*");if(personal!=null)candidates.add(personal);if(global!=null)candidates.add(global);candidates.add(fallback);return List.copyOf(candidates);}
    public void observe(UUID id,Vec p){withLoaded(List.of(id),()->{observeLoaded(id,p);return null;});}
    private synchronized void observeLoaded(UUID id,Vec p){validPosition(p);if(closing||!healthy())throw new IllegalStateException("location_storage_failed");String key=id.toString();if(retiring.contains(key))throw new IllegalStateException("character_deleting");if(countLimit>0&&!positions.containsKey(key)&&index.positionCount()+positionPending.keySet().stream().filter(scope->!durablePositions.containsKey(scope)).count()>=countLimit)throw new IllegalStateException("location_limit");if(p.equals(positions.get(key)))return;positions.put(key,p);dirty.put(key,p);}
    /** Teleport caller serializes that player's input until this durable write completes. */
    public CompletableFuture<Void> teleport(UUID id,Vec p){return teleportBatch(Map.of(id,p));}
    /** Same durable teleport publication, one append for up to32 offline owners. */
    public CompletableFuture<Void> teleportBatch(Map<UUID,Vec> values){return loadedBatch(values,"location_batch_limit",()->teleportBatchLoaded(values));}
    private synchronized CompletableFuture<Void> teleportBatchLoaded(Map<UUID,Vec> values){
        Map<String,Vec> batch=new LinkedHashMap<>();try{if(values.isEmpty()||values.size()>BATCH)throw new IllegalArgumentException("location_batch_limit");values.forEach((id,p)->{if(retiring.contains(id.toString()))throw new IllegalStateException("character_deleting");batch.put(id.toString(),validPosition(p));});}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));if(!spawnRequests.tryAcquire())return CompletableFuture.failedFuture(new IllegalStateException("location_command_queue_full"));
        Set<String> count=new HashSet<>(positionPending.keySet());count.addAll(batch.keySet());count.removeAll(durablePositions.keySet());if(countLimit>0&&index.positionCount()+count.size()>countLimit){spawnRequests.release();return CompletableFuture.failedFuture(new IllegalStateException("location_limit"));}
        batch.keySet().forEach(key->{dirty.remove(key);positionPending.merge(key,1,Integer::sum);});CompletableFuture<Void> write=append("positions",batch,()->{synchronized(this){hotPoints(positions,batch);hotPoints(durablePositions,batch);}});
        write.whenComplete((done,error)->{synchronized(this){batch.keySet().forEach(key->{int n=positionPending.get(key)-1;if(n==0)positionPending.remove(key);else positionPending.put(key,n);});evictLoaded();}if(error!=null)failed=true;spawnRequests.release();});return write.copy();
    }
    public CompletableFuture<Void> setSpawn(UUID id,Vec p){valid(p);return setSpawnBatch(Collections.singletonMap(id,p));}
    /** One durable publication for up to32 authored points; a null UUID denotes global spawn. */
    public CompletableFuture<Void> setSpawnBatch(Map<UUID,Vec> values){return loadedBatch(values,"spawn_batch_limit",()->setSpawnBatchLoaded(values));}
    private synchronized CompletableFuture<Void> setSpawnBatchLoaded(Map<UUID,Vec> values){
        Map<String,Vec> batch=new LinkedHashMap<>();try{if(values.isEmpty()||values.size()>BATCH)throw new IllegalArgumentException("spawn_batch_limit");values.forEach((id,p)->{String key=id==null?"*":id.toString();if(retiring.contains(key))throw new IllegalStateException("character_deleting");batch.put(key,valid(p));});}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));if(!spawnRequests.tryAcquire())return CompletableFuture.failedFuture(new IllegalStateException("spawn_queue_full"));
        Set<String> newKeys=new HashSet<>(spawnPending.keySet());newKeys.addAll(batch.keySet());newKeys.remove("*");newKeys.removeAll(spawns.keySet());
        if(countLimit>0&&personalSpawns()+newKeys.size()>countLimit){spawnRequests.release();return CompletableFuture.failedFuture(new IllegalStateException("spawn_limit"));}
        batch.keySet().forEach(key->spawnPending.merge(key,1,Integer::sum));
        CompletableFuture<Void> future=append("spawns",batch,()->{synchronized(this){hotPoints(spawns,batch);}});
        future.whenComplete((done,error)->{synchronized(this){batch.keySet().forEach(key->{int n=spawnPending.get(key)-1;if(n==0)spawnPending.remove(key);else spawnPending.put(key,n);});evictLoaded();}if(error!=null)failed=true;spawnRequests.release();});return future.copy();
    }
    private long personalSpawns(){return index.personalSpawnCount();}
    /** At most one movement batch/checkpoint in flight; newer input stays dirty. */
    public synchronized CompletableFuture<Void> flush(){
        if(!pending.isDone())return pending.copy();if(!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));
        Map<String,StateWrite> requests=new LinkedHashMap<>();var requestIterator=stateWrites.entrySet().iterator();
        while(requestIterator.hasNext()&&requests.size()<BATCH){var entry=requestIterator.next();String key=entry.getKey();if(vitalsHeld.containsKey(key)||vitalsPending.containsKey(key))continue;
            requests.put(key,entry.getValue());stateWritesInFlight.add(key);requestIterator.remove();
        }
        if(dirty.isEmpty()&&dirtyStates.isEmpty()&&requests.isEmpty())return CompletableFuture.completedFuture(null);
        Map<String,Vec> batch=new LinkedHashMap<>();var iterator=dirty.entrySet().iterator();while(iterator.hasNext()&&batch.size()<BATCH){var e=iterator.next();batch.put(e.getKey(),e.getValue());iterator.remove();}
        Map<String,PlayerState> stateBatch=new LinkedHashMap<>();for(String key:requests.keySet())if(!batch.containsKey(key))stateBatch.put(key,states.get(key));
        var stateIterator=dirtyStates.entrySet().iterator();while(stateIterator.hasNext()&&stateBatch.size()<BATCH){var entry=stateIterator.next();if(vitalsHeld.containsKey(entry.getKey()))continue;if(!batch.containsKey(entry.getKey()))stateBatch.put(entry.getKey(),states.get(entry.getKey()));stateIterator.remove();}
        if(batch.isEmpty()&&stateBatch.isEmpty()&&requests.isEmpty())return CompletableFuture.completedFuture(null);
        movementInFlight.addAll(batch.keySet());movementInFlight.addAll(stateBatch.keySet());
        CompletableFuture<Void> write=append("positions",batch,stateBatch,()->{synchronized(this){hotPoints(durablePositions,batch);}});
        pending=write.whenComplete((done,error)->{
            synchronized(this){requests.keySet().forEach(stateWritesInFlight::remove);movementInFlight.removeAll(batch.keySet());movementInFlight.removeAll(stateBatch.keySet());if(error==null)stateSaved+=requests.size();else failed=true;evictLoaded();}
            for(StateWrite request:requests.values())if(error==null)request.future.complete(null);else request.future.completeExceptionally(error);
        });return pending.copy();
    }
    private synchronized CompletableFuture<Void> append(String type,Map<String,Vec> batch,Runnable durable){return append(type,batch,Map.of(),durable);}
    private synchronized CompletableFuture<Void> append(String type,Map<String,Vec> batch,Map<String,PlayerState> stateBatch,Runnable durable){
        JsonObject record=event(type,batch);Map<String,PlayerState> captured=new LinkedHashMap<>();Map<String,Long> revisions=new LinkedHashMap<>();if(type.equals("positions"))for(JsonElement value:record.getAsJsonArray("rows")){JsonObject row=value.getAsJsonObject();String key=row.get("scope").getAsString();PlayerState state=states.get(key);if(state!=null){row.add("state",state.json());captured.put(key,state);captureVitalsRevision(key,row,revisions);}}
        if(!stateBatch.isEmpty()){JsonArray extra=new JsonArray();stateBatch.forEach((key,state)->{JsonObject row=new JsonObject();row.addProperty("scope",key);row.add("state",state.json());captureVitalsRevision(key,row,revisions);extra.add(row);});record.add("stateRows",extra);captured.putAll(stateBatch);}
        CompletableFuture<Void> write=journal.append(record,()->{applyIndex(record);journalBytes+=8+szserver.util.Json.bytes(record).length;durable.run();synchronized(this){captured.forEach((key,state)->{if(loaded.containsKey(key))durableStates.put(key,state);});revisions.forEach((key,revision)->{if(loaded.containsKey(key))durableVitalsRevisions.put(key,revision);});}autoCheckpoint();});
        batches++;return write;
    }
    /** Only this journal owner mutates durable positions/states/spawns/legacy rows. Emit without the live-state lock. */
    private void checkpoint(Journal.CheckpointSink sink)throws Exception {
        checkpointBytesWritten=8;Journal.CheckpointSink counted=record->{sink.accept(record);checkpointBytesWritten+=8+szserver.util.Json.bytes(record).length;};
        JsonArray[] positionsPart={new JsonArray()},spawnsPart={new JsonArray()},legacyPart={new JsonArray()};
        try{index.visit((key,root)->{
            if(root.position()!=null){JsonObject row=entry(key,root.position());if(root.state()!=null)row.add("state",root.state().json());if(root.revision()>0)row.addProperty("vitalsRevision",root.revision());checkpointRow(counted,"positions",positionsPart,row);}
            if(root.spawn()!=null)checkpointRow(counted,"spawns",spawnsPart,entry(key,root.spawn()));
            if(root.legacy()!=null){JsonObject row=new JsonObject();row.addProperty("scope",key);row.add("playerArchive",root.legacy().json());checkpointRow(counted,"legacy-players",legacyPart,row);}
        });}catch(java.io.IOException error){if(error.getMessage()!=null&&error.getMessage().startsWith("location_index_"))failed=true;throw error;}
        for(var part:List.of(Map.entry("positions",positionsPart[0]),Map.entry("spawns",spawnsPart[0]),Map.entry("legacy-players",legacyPart[0])))if(!part.getValue().isEmpty()){JsonObject record=new JsonObject();record.addProperty("type",part.getKey());record.add("rows",part.getValue());counted.accept(record);}
    }
    private static void checkpointRow(Journal.CheckpointSink sink,String type,JsonArray[] part,JsonObject row)throws Exception{part[0].add(row);if(part[0].size()==BATCH){JsonObject record=new JsonObject();record.addProperty("type",type);record.add("rows",part[0]);sink.accept(record);part[0]=new JsonArray();}}
    public boolean healthy(){return !closed&&!failed&&journal.healthy();}
    public synchronized Map<String,Object> metrics(){return Map.of("healthy",healthy(),"positions",index.positionCount(),"spawns",index.spawnCount(),"playerStates",index.stateCount(),"legacyPlayers",index.legacyCount(),"dirty",dirty.size(),"dirtyStates",dirtyStates.size(),"inFlight",!pending.isDone(),"spawnRequests",32-spawnRequests.availablePermits(),"journal",journal.metrics());}
    public synchronized Map<String,Object> residencyMetrics(){return Map.of("cachedRoots",loaded.size(),"cacheLimit",HOT_LIMIT,"heldRoots",heldLoads.size(),"heldLeases",128-loadRequests.availablePermits(),"capacityLimit",countLimit,"index",index.metrics());}
    private synchronized void autoCheckpoint(){
        long budget=Math.max(1024L*1024,index.positionCount()*512L+index.spawnCount()*192L+index.legacyCount()*256L);
        if(!closing&&checkpointPending.isDone()&&journalBytes>Math.max(nextCheckpointAt,budget))startCheckpoint();
    }
    private CompletableFuture<Void> startCheckpoint(){
        checkpointPending=journal.compactStreaming(this::checkpoint).whenComplete((done,error)->{
            if(error==null){journalBytes=checkpointBytesWritten;nextCheckpointAt=journalBytes+1024L*1024;}else{nextCheckpointAt=journalBytes+1024L*1024;if(!journal.healthy())failed=true;}
        });return checkpointPending;
    }
    public synchronized CompletableFuture<Void> compact(){if(closing||!healthy())return CompletableFuture.failedFuture(new IllegalStateException("location_storage_failed"));if(!checkpointPending.isDone())return checkpointPending.copy();return startCheckpoint().copy();}
    private void drainJournal()throws Exception{
        while(true)try{journal.durablePrefix().get();return;}catch(ExecutionException error){if(error.getCause() instanceof RejectedExecutionException){Thread.sleep(10);continue;}throw error;}
    }
    @Override public void close()throws Exception{synchronized(closeLock){if(closed)return;synchronized(this){closing=true;}
        loadWorker.shutdown();legacyWorker.shutdown();while(!loadWorker.awaitTermination(1,TimeUnit.SECONDS)){}while(!legacyWorker.awaitTermination(1,TimeUnit.SECONDS)){}try{synchronized(this){while(activeOperations>0)wait();}while(true){
            CompletableFuture<Void> write;synchronized(this){
                if(!vitalsHeld.isEmpty())throw new IllegalStateException("location_vitals_fence_not_drained");
                if(pending.isDone()&&dirty.isEmpty()&&dirtyStates.isEmpty()&&stateWrites.isEmpty()&&stateWritesInFlight.isEmpty())break;write=flush();
            }
            write.get();
        }}finally{try{drainJournal();}finally{try{index.close();}finally{try{journal.close();}finally{synchronized(this){
            closed=true;var abandoned=List.copyOf(stateWrites.values());stateWrites.clear();abandoned.forEach(request->request.future.completeExceptionally(new IllegalStateException("location_closed")));
            loaded.clear();heldLoads.clear();positions.clear();durablePositions.clear();states.clear();durableStates.clear();vitalsRevisions.clear();durableVitalsRevisions.clear();vitalsHeld.clear();vitalsPending.clear();stateWritesInFlight.clear();spawns.clear();dirty.clear();dirtyStates.clear();legacyPlayers.clear();legacyPending.clear();positionPending.clear();spawnPending.clear();movementInFlight.clear();
        }}}}}
    }}

}
