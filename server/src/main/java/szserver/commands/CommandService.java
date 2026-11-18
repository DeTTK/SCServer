package szserver.commands;

import com.google.gson.*;
import szserver.client.GameItems;
import szserver.persistence.Journal;
import szserver.persistence.WorldCheckpointStore;
import szserver.util.*;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** One command owner for the first server. No global lock, disk or codec on the tick.
 * Accepted durable commands are replayable and idempotent across restarts.
 */
public final class CommandService implements AutoCloseable {
    private final ThreadPoolExecutor worker=Workers.pool("commands",1,128);
    private final ThreadPoolExecutor nativeCommitWorker=Workers.pool("native-map-commit",1,1);
    private final ThreadPoolExecutor checkpointWorker=Workers.pool("world-checkpoint",1,1);
    private volatile String checkpointNativePin="";
    private szserver.world.NativeChunkCommits nativeCommits;private ScheduledExecutorService nativeCommitPolicy;
    private final AtomicInteger pendingNativeCommit=new AtomicInteger();private volatile long nativeCheckedEpoch=-1,nextNativeAttempt;
    private final AtomicLong nativeCommitAttempts=new AtomicLong(),nativeCommitFailures=new AtomicLong(),nativeCommitDeferrals=new AtomicLong();
    private volatile Map<String,Object> nativeCommitView=Map.of("status","unavailable");
    private volatile Map<String,Object> nativeMaintenanceView=Map.of("status","idle");private volatile boolean nativeRetentionDirty;
    private volatile long nextNativeSweep,nativeWritesAtMaintenance;
    private final WorldLoop world;
    private final Journal journal;
    private szserver.persistence.WorldEventStore worldEvents;
    private szserver.persistence.WorldEnvironment environment;
    private szserver.persistence.DerivedCaches.Context cacheContext;private volatile Map<String,Object> cacheCleanupView=Map.of("status","idle");
    private final GameItems items;
    private java.nio.file.Path actorEquipmentRoot;
    public void configureActorEquipment(java.nio.file.Path root){if(actorEquipmentRoot!=null)throw new IllegalStateException("actor_equipment_already_configured");actorEquipmentRoot=root.toAbsolutePath().normalize();}
    /** Native codecs and fsync execute on the command worker; only prepared data enters the world owner. */
    public CompletableFuture<Map<String,Object>> actorEquipment(JsonObject request){
        JsonObject copy=request.deepCopy();if(!Set.of("entityId","wornItemIds","heldItemId","encumbered","aiming").containsAll(copy.keySet()))return CompletableFuture.failedFuture(new IllegalArgumentException("actor_equipment_fields"));
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));var future=new CompletableFuture<Map<String,Object>>();
        try{worker.execute(()->{boolean applied=false;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");if(actorEquipmentRoot==null)throw new IllegalArgumentException("actor_equipment_not_configured");
            String id=UUID.fromString(Json.text(copy,"entityId",36)).toString();List<Integer> worn=new ArrayList<>();if(copy.has("wornItemIds")){var values=copy.getAsJsonArray("wornItemIds");if(values.size()>2)throw new IllegalArgumentException("actor_equipment_slots");for(var value:values){int item=value.getAsBigDecimal().intValueExact();if(item<1||item>65535)throw new IllegalArgumentException("actor_equipment_item");worn.add(item);}}
            Integer held=copy.has("heldItemId")?Json.integer(copy,"heldItemId",0,65535):0;boolean aiming=booleanValue(copy,"aiming"),encumbered=booleanValue(copy,"encumbered");
            long incarnation=world.submit(w->{if(!Boolean.TRUE.equals(w.actorMetrics().get("completeNativeDispatcher")))throw new IllegalArgumentException("actor_equipment_native_required");return w.snapshot().entities().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("entity_not_found")).incarnation();}).get(5,TimeUnit.SECONDS);
            PlayerInventories gateway=players;ActorEquipment settings=gateway==null?items.actorEquipment(actorEquipmentRoot,worn,held==0?null:held,encumbered):gateway.actorEquipment(worn,held==0?null:held,encumbered).get(30,TimeUnit.SECONDS);var prepared=world.prepareEquipment(settings);
            StateCapture capture=world.submit(w->{w.equipment(id,incarnation,prepared);w.aiming(id,aiming);return new StateCapture(null,w.actorRuntime(),w.identity(),w.sceneRegistryDigest());}).get(5,TimeUnit.SECONDS);applied=true;persistActors(capture.actors(),capture.identity());
            future.complete(Map.of("status","applied","durable",true,"entityId",id,"incarnation",incarnation,"aiming",aiming,"movement",settings.movement(),"energyCapacity",settings.energyCapacity()));
        }catch(Throwable error){if(applied){healthy=false;failure="actor_equipment_persistence_failed";}Throwable cause=error instanceof ExecutionException?error.getCause():error;if(!(cause instanceof IllegalArgumentException)&&!(cause instanceof RejectedExecutionException)){System.err.println("NPC equipment request failed:");cause.printStackTrace(System.err);}future.completeExceptionally(cause);}finally{admission.release();}});}catch(RejectedExecutionException error){admission.release();future.completeExceptionally(error);}return future.copy();
    }
    /** Developer-authorized effect application; medicine preparation never enters the world owner. */
    public CompletableFuture<Map<String,Object>> actorEffect(JsonObject request){
        JsonObject copy=request.deepCopy();String operation;
        try{operation=Json.text(copy,"operation",16);if(!Set.of("apply","remove").contains(operation)||!copy.keySet().equals(Set.of("entityId","operation",operation.equals("apply")?"itemId":"effectId")))throw new IllegalArgumentException("actor_effect_fields");}
        catch(IllegalArgumentException invalid){return CompletableFuture.failedFuture(invalid);}
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));var future=new CompletableFuture<Map<String,Object>>();
        try{worker.execute(()->{boolean applied=false;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");String id=UUID.fromString(Json.text(copy,"entityId",36)).toString();
            long incarnation=world.submit(w->{if(!Boolean.TRUE.equals(w.actorMetrics().get("completeNativeDispatcher")))throw new IllegalArgumentException("actor_effect_native_required");return w.snapshot().entities().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("entity_not_found")).incarnation();}).get(5,TimeUnit.SECONDS);
            ActorControllerEngine.PreparedMedicine prepared=operation.equals("apply")?world.prepareMedicine(Json.integer(copy,"itemId",1,65535)):null;int effect=operation.equals("remove")?Json.integer(copy,"effectId",0,Integer.MAX_VALUE):0;
            StateCapture capture=world.submit(w->{if(prepared!=null)w.medicine(id,incarnation,prepared);else w.removeBuff(id,incarnation,effect);return new StateCapture(null,w.actorRuntime(),w.identity(),w.sceneRegistryDigest());}).get(5,TimeUnit.SECONDS);applied=true;persistActors(capture.actors(),capture.identity());
            future.complete(Map.of("status","applied","durable",true,"entityId",id,"incarnation",incarnation,"operation",operation));
        }catch(Throwable error){if(applied){healthy=false;failure="actor_effect_persistence_failed";}Throwable cause=error instanceof ExecutionException?error.getCause():error;future.completeExceptionally(cause);}finally{admission.release();}});}catch(RejectedExecutionException error){admission.release();future.completeExceptionally(error);}return future.copy();
    }
    private static boolean booleanValue(JsonObject value,String key){if(!value.has(key))return false;var field=value.get(key);if(!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("actor_equipment_boolean");return field.getAsBoolean();}
    /** Queue one native condition on the owner and durably save its pending input on this worker. */
    public CompletableFuture<Map<String,Object>> actorCondition(JsonObject request){
        JsonObject copy=request.deepCopy();if(!copy.keySet().equals(Set.of("entityId","kind","enabled")))return CompletableFuture.failedFuture(new IllegalArgumentException("actor_condition_fields"));
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));var future=new CompletableFuture<Map<String,Object>>();
        try{worker.execute(()->{boolean queued=false;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");String id=UUID.fromString(Json.text(copy,"entityId",36)).toString();var kind=ActorControllerEngine.ConditionKind.valueOf(Json.text(copy,"kind",16));var enabled=copy.get("enabled");if(!enabled.isJsonPrimitive()||!enabled.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("actor_condition_boolean");var condition=new ActorControllerEngine.Condition(kind,enabled.getAsBoolean());
            StateCapture capture=world.submit(w->new StateCapture(null,w.conditionRuntime(id,condition),w.identity(),w.sceneRegistryDigest())).get(5,TimeUnit.SECONDS);queued=true;persistActors(capture.actors(),capture.identity());
            future.complete(Map.of("status","queued","durable",true,"entityId",id,"condition",condition));
        }catch(Throwable error){if(queued){healthy=false;failure="actor_condition_persistence_failed";}Throwable cause=error instanceof ExecutionException?error.getCause():error;future.completeExceptionally(cause);}finally{admission.release();}});}catch(RejectedExecutionException error){admission.release();future.completeExceptionally(error);}return future.copy();
    }
    /** Native seat intent is persisted before acknowledgement; geometry/admission runs on the world owner. */
    public CompletableFuture<Map<String,Object>> actorSeat(JsonObject request){
        JsonObject copy=request.deepCopy();if(!copy.keySet().equals(Set.of("entityId","objectId","index","leave")))return CompletableFuture.failedFuture(new IllegalArgumentException("actor_seat_fields"));
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));var future=new CompletableFuture<Map<String,Object>>();
        try{worker.execute(()->{boolean queued=false;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");String id=UUID.fromString(Json.text(copy,"entityId",36)).toString();UUID object=UUID.fromString(Json.text(copy,"objectId",36));int index=Json.integer(copy,"index",0,31);var leave=copy.get("leave");if(!leave.isJsonPrimitive()||!leave.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("actor_seat_boolean");var seat=new ActorControllerEngine.Seat(object,index,leave.getAsBoolean());
            StateCapture capture=world.submit(w->new StateCapture(null,w.seatRuntime(id,seat),w.identity(),w.sceneRegistryDigest())).get(5,TimeUnit.SECONDS);queued=true;persistActors(capture.actors(),capture.identity());future.complete(Map.of("status","queued","durable",true,"entityId",id,"seat",seat));
        }catch(Throwable error){if(queued){healthy=false;failure="actor_seat_persistence_failed";}future.completeExceptionally(error instanceof ExecutionException?error.getCause():error);}finally{admission.release();}});}catch(RejectedExecutionException error){admission.release();future.completeExceptionally(error);}return future.copy();
    }
    private Map<String,TreeMap<Integer,JsonObject>> inventories;
    private final CommandHistory history;
    private final WorldCheckpointStore checkpoints;
    private DurableEntityProgram entityProgram;
    private Terrain checkpointTerrain;private boolean restoreEnabled;
    private volatile Map<String,Object> bootstrapView=Map.of("method","journal");
    private volatile Map<String,Object> checkpointView=Map.of("status","idle","creationEnabled",true,"restoreEnabled",false),lastCheckpoint=Map.of();
    private final AtomicInteger pendingSceneMaintenance=new AtomicInteger();private volatile Map<String,Object> sceneMaintenanceView=Map.of("status","idle");private volatile Map<String,Object> worldJournalMaintenanceView=Map.of("status","idle");private volatile Map<String,Object> nativeMapArchiveView=Map.of("status","idle");
    private long checkpointSaves;private volatile long checkpointFailures;
    private volatile long savedCheckpointRecords,savedActorTick;private volatile String savedCheckpointNativeBase="";private final AtomicInteger pendingCheckpoints=new AtomicInteger();
    private final AtomicLong retiredNativeVoxels=new AtomicLong(),retiredNativeTiles=new AtomicLong(),retiredNativeBytes=new AtomicLong(),retiredNativeScenes=new AtomicLong();private volatile Map<String,Object> nativeBaseView=Map.of("status","original","generation","","sceneOverlaysRetired",false);
    private volatile Map<String,List<JsonObject>> inventoryView=Map.of();
    private record Noop(String fingerprint,JsonObject response){}
    private final ConcurrentHashMap<String,Noop> noops=new ConcurrentHashMap<>();private final ArrayDeque<String> noopOrder=new ArrayDeque<>();
    private volatile boolean healthy=true;
    private volatile PlayerInventories players;
    private volatile String failure="";
    private record Flight(String fingerprint,CompletableFuture<JsonObject> future){}
    private final ConcurrentHashMap<String,Flight> inFlight=new ConcurrentHashMap<>();
    private final Semaphore admission=new Semaphore(129);
    private final java.util.concurrent.atomic.AtomicBoolean residentExportPending=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long residentPublication;
    private volatile boolean residentHistoryEnabled;
    public boolean residentHistoryEnabled(){return residentHistoryEnabled;}
    /** Cold operation IO completes on WorldLoop; decode canonical JSON on the calling history worker. */
    public CompletableFuture<Void> enableResidentRuntimeHistory(ResidentCommandIndex.Loaded loaded,java.util.function.Supplier<ResidentMapRuntime> runtime){
        Objects.requireNonNull(runtime);return enableResidentHistory(loaded,event->{var stored=world.submit(w->Objects.requireNonNull(runtime.get(),"resident_runtime_not_ready").history(event)).thenCompose(future->future).get(10,TimeUnit.SECONDS);return ResidentCommandEnvelope.command(stored);});
    }
    public CompletableFuture<Void> enableResidentHistory(ResidentCommandIndex.Loaded loaded,ResidentCommandIndex.Lookup lookup){
        if(!world.beforeStart())return CompletableFuture.failedFuture(new IllegalStateException("resident_history_late_binding"));var result=new CompletableFuture<Void>();
        try{worker.execute(()->{try{if(!world.beforeStart())throw new IllegalStateException("resident_history_late_binding");history.resident(new ResidentCommandIndex(loaded,lookup));residentHistoryEnabled=true;publish();result.complete(null);}catch(Throwable failure){result.completeExceptionally(failure);}});}catch(RejectedExecutionException failure){result.completeExceptionally(failure);}return result.copy();
    }
    @FunctionalInterface public interface ResidentApply {CompletableFuture<?> apply(JsonObject canonical,ResidentCommandEnvelope.Prepared command)throws Exception;}
    /** Physical Scene Editor and logical portal changes share the native map revision boundary. */
    public CompletableFuture<JsonObject> submitResidentScenes(JsonObject request,ResidentMapRuntime runtime,UUID actor){
        try{String type=Json.text(request,"type",32);if(!SceneEditor.handles(type)&&!SceneDomain.handles(type))throw new IllegalArgumentException("resident_scene_command_required");}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        return submitResident(request,(event,command)->{
            if(event.has("status")&&event.get("status").getAsString().equals("unchanged"))return world.submit(w->runtime.recordCommand(actor,command));
            SceneEditor.WorldMutation mutation=SceneDomain.handles(Json.text(event,"type",32))?SceneEditor.WorldMutation.metadata(world,world.submit(World::sceneRegistryDigest).get(5,TimeUnit.SECONDS),scenePortals.compile(event,false)):sceneEditor.prepareApply(event).withPortals(scenePortals.compile(event,false));
            var nativePlan=mutation.resident(runtime);return world.submit(w->{if(nativePlan.chunks().isEmpty())return CompletableFuture.completedFuture(nativePlan.metadata().history()==null?runtime.recordCommand(actor,command):runtime.editMetadata(actor,command.operation(),w,nativePlan.metadata(),()->{},command));return runtime.editScenes(actor,command.operation(),nativePlan.chunks(),w,nativePlan.metadata(),()->{},revisions->{},command);}).thenCompose(future->future);
        });
    }
    /** Interactive voxel/tile ingress. Eye supplier must read an authenticated session on WorldLoop. */
    public CompletableFuture<JsonObject> submitResidentBlocks(JsonObject request,ResidentMapRuntime runtime,UUID actor,long sequence,java.util.function.Supplier<World.Vec> eye){
        Objects.requireNonNull(runtime);Objects.requireNonNull(actor);Objects.requireNonNull(eye);
        try{if(!Set.of("block","block_cas","tile_set","undo").contains(Json.text(request,"type",32)))throw new IllegalArgumentException("resident_interactive_command_required");}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        return submitResident(request,(event,command)->{
            if(event.has("status")&&event.get("status").getAsString().equals("unchanged"))return world.submit(w->runtime.recordCommand(actor,command));
            if(event.has("sceneChanges")||event.has("editorStateChanges")||event.has("portalMetadata"))throw new IllegalArgumentException("resident_mixed_edit_requires_bulk_ingress");
            String type=Json.text(event,"type",32);CompletableFuture<List<ResidentBlockOperations.Delta>> changes;
            if(type.equals("block")){JsonObject p=Json.object(event,"position");World.Block position=new World.Block(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt());int target=(event.get("blockId").getAsInt()<<4)|(event.has("metadata")?event.get("metadata").getAsInt():0);var key=new ResidentChunks.Key(Math.floorDiv(position.x(),16),Math.floorDiv(position.z(),16));int cell=(position.y()<<8)|(Math.floorMod(position.z(),16)<<4)|Math.floorMod(position.x(),16);
                changes=world.submit(w->runtime.load(key)).thenCompose(f->f).thenApply(snapshot->List.of(new ResidentBlockOperations.Delta(position,snapshot.voxel(cell),target,snapshot.tile(cell),null)));
            }else if(BlockEditor.handles(type)){var rows=editor.residentDeltas(event);for(var row:rows)if(row.tile()!=null&&row.tile().completeStateSize()>0)szserver.client.CompleteTileState.frame(row.tile());changes=CompletableFuture.completedFuture(rows);}else throw new IllegalArgumentException("resident_block_command_required");
            var rows=changes.get(10,TimeUnit.SECONDS);if(rows.isEmpty())return world.submit(w->runtime.recordCommand(actor,command));validateResidentPointFrames(runtime,rows);return world.submit(w->runtime.edit(new ResidentBlockOperations.Request(actor,sequence,eye.get(),rows,command))).thenCompose(f->f).thenApply(receipt->{if(receipt.status().equals("rejected"))throw new IllegalArgumentException(receipt.reason());return command.event();});
        });
    }
    private void validateResidentPointFrames(ResidentMapRuntime runtime,List<ResidentBlockOperations.Delta> rows)throws Exception{
        Map<ResidentChunks.Key,List<ResidentBlockOperations.Delta>> groups=new LinkedHashMap<>();for(var row:rows){var p=row.position();groups.computeIfAbsent(new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)),ignored->new ArrayList<>()).add(row);}for(var group:groups.entrySet()){var source=world.submit(w->runtime.load(group.getKey())).thenCompose(f->f).get(10,TimeUnit.SECONDS);Map<Integer,BlockOverlays.Voxel> cells=new HashMap<>();Map<Integer,TilePayload.Tile> tiles=new HashMap<>();for(var row:group.getValue()){var p=row.position();int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);cells.put(cell,new BlockOverlays.Voxel(row.packed()>>>4,row.packed()&15,1));tiles.put(cell,row.tile());}byte[] record=szserver.net.TerrainProjection.project(source.networkRecord(),new BlockOverlays.Snapshot(1,cells,Collections.unmodifiableMap(tiles),Map.of()));if(szserver.net.GamePackets.terrainChunk(record).length>240000)throw new IllegalArgumentException("terrain_frame_limit");}
    }
    public long residentPublication(){return residentPublication;}
    /** Trusted editor/admin lane. Worker prepares whole native chunks; owner publishes them together. */
    public CompletableFuture<JsonObject> submitResidentBulk(JsonObject request,ResidentMapRuntime runtime,UUID actor){
        return submitResident(request,(event,command)->{
            if(event.has("status")&&event.get("status").getAsString().equals("unchanged"))return world.submit(w->runtime.recordCommand(actor,command));
            String type=Json.text(event,"type",32);List<ResidentBlockOperations.Delta> rows;
            if(type.equals("block")){var position=event.getAsJsonObject("position");var p=new World.Block(position.get("x").getAsInt(),position.get("y").getAsInt(),position.get("z").getAsInt());var key=new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));var source=world.submit(w->runtime.load(key)).thenCompose(f->f).get(10,TimeUnit.SECONDS);int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16),packed=event.get("blockId").getAsInt()<<4|(event.has("metadata")?event.get("metadata").getAsInt():0);rows=List.of(new ResidentBlockOperations.Delta(p,source.voxel(cell),packed,source.tile(cell),null));}
            else rows=editor.residentBulkDeltas(event);
            var mutation=sceneSelection.prepareApply(event);var scenes=mutation==null?null:mutation.resident(runtime);Map<ResidentChunks.Key,List<ResidentBlockOperations.Delta>> groups=new LinkedHashMap<>();for(var row:rows){var p=row.position();groups.computeIfAbsent(new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)),ignored->new ArrayList<>()).add(row);}if(scenes!=null)for(var key:scenes.chunks().keySet())groups.computeIfAbsent(key,ignored->new ArrayList<>());
            Map<ResidentChunks.Key,szserver.client.NativeResidentChunkCodec.PreparedTerrain> prepared=new LinkedHashMap<>();for(var group:groups.entrySet()){var source=world.submit(w->runtime.load(group.getKey())).thenCompose(f->f).get(10,TimeUnit.SECONDS);prepared.put(group.getKey(),runtime.prepareTerrain(source,group.getValue(),scenes==null?null:scenes.chunks().get(group.getKey())));}
            if(prepared.isEmpty()&&(scenes==null||scenes.metadata().history()==null))return world.submit(w->runtime.recordCommand(actor,command));
            return world.submit(w->runtime.editPrepared(actor,prepared,w,scenes==null?null:scenes.metadata(),command)).thenCompose(future->future);
        });
    }
    public record ResidentCut(ResidentCommandCheckpoint.Snapshot snapshot,String identity,String registry,Journal.Prefix source,long publication,ResidentCommandIndex.Snapshot index){}
    @FunctionalInterface public interface ResidentCapture {void capture(World world,ResidentCut cut);}
    private volatile ResidentCut sealedResidentCut;
    private volatile java.util.function.Supplier<CompletableFuture<Void>> residentCloseHook;
    public synchronized void residentCloseHook(java.util.function.Supplier<CompletableFuture<Void>> hook){if(residentCloseHook!=null)throw new IllegalStateException("resident_close_hook_already_set");residentCloseHook=Objects.requireNonNull(hook);}
    private final AtomicLong rejected=new AtomicLong();
    private volatile double maxCommandMs;
    private final Semaphore mapReadAdmission=new Semaphore(2);
    private final AtomicLong mapReads=new AtomicLong(),mapReadRejected=new AtomicLong(),mapReadFailures=new AtomicLong();
    private volatile double maxMapReadMs;
    private final JsonPages backendPages=new JsonPages();
    private SceneTransactionArchive sceneArchive;
    private BlockEditor editor;
    private SceneDomain sceneDomain;
    private SceneEditorStates editorStates;
    private SceneDeletedStates deletedScenes;
    private SceneRelationships relationships;
    private SceneEditor sceneEditor;private final SceneSeeds sceneSeeds;
    private SceneSelectionEditor sceneSelection;
    private ScenePortalProjection scenePortals;
    private volatile long recoveredRecords,importedRecords;
    private static final Set<String> IMPORT_TYPES=Set.of("scene_import","scene_store_import","scene_portal_import","scene_deleted_import","native_block_import","scene_relationship_import","actor_runtime");
    public CommandService(WorldLoop world,Journal journal,GameItems items)throws Exception{this(world,journal,items,32L*1024*1024,null);}
    public CommandService(WorldLoop world,Journal journal,GameItems items,SceneSeeds sceneSeeds)throws Exception{this(world,journal,items,32L*1024*1024,sceneSeeds);}
    /** Smaller history-cache budgets for isolated eviction tests. */
    public CommandService(WorldLoop world,Journal journal,GameItems items,long historyByteLimit)throws Exception{this(world,journal,items,historyByteLimit,null);}
    public CommandService(WorldLoop world,Journal journal,GameItems items,long historyByteLimit,SceneSeeds sceneSeeds)throws Exception{
        this(world,journal,items,historyByteLimit,sceneSeeds,null);
    }
    /** A validated resident bundle replaces legacy map replay. Bootstrap failure never falls back to an older map. */
    public CommandService(WorldLoop world,Journal journal,GameItems items,long historyByteLimit,SceneSeeds sceneSeeds,ResidentMapBootstrap.Bundle resident)throws Exception{
        if(historyByteLimit<256||historyByteLimit>32L*1024*1024)throw new IllegalArgumentException("invalid_history_byte_limit");
        this.history=new CommandHistory(journal,historyByteLimit);
        try{this.checkpoints=new WorldCheckpointStore(journal);}catch(Throwable failure){worker.shutdownNow();checkpointWorker.shutdownNow();history.close();if(failure instanceof Exception error)throw error;if(failure instanceof Error error)throw error;throw new IllegalStateException(failure);}
        this.world=world;this.journal=journal;this.items=items;this.sceneSeeds=sceneSeeds;
        try{cacheContext=szserver.persistence.DerivedCaches.context(journal.path().toAbsolutePath().getParent());String identity=world.bootstrap(World::identity);worldEvents=new szserver.persistence.WorldEventStore(journal.path().toAbsolutePath().getParent(),identity,()->world.snapshot().tick());checkpointTerrain=world.bootstrap(World::checkpointTerrain);
            if(resident==null&&checkpointTerrain instanceof RegionTerrain regions&&regions.nativeCommitBinding()!=null)try{nativeCommits=new szserver.world.NativeChunkCommits(journal.path().toAbsolutePath().getParent(),regions.nativeCommitBinding(),identity,journal);regions.archiveCommits(nativeCommits.readView());nativeCommitView=Map.of("status","idle");}catch(Exception degraded){nativeCommitFailures.incrementAndGet();nativeCommitView=Map.of("status","unavailable","error",degraded.getClass().getSimpleName(),"reason",Objects.toString(degraded.getMessage(),""));}
            if(resident==null)bootstrap();else bootstrapResident(resident);if(nativeCommits!=null)advanceNativeBase(true);environment=new szserver.persistence.WorldEnvironment(journal.path().toAbsolutePath().getParent(),identity,System::currentTimeMillis,0,worldEvents);world.bootstrap(w->{w.overlayVersions().prepareBootstrapSnapshots();return true;});publish();checkpointView=Map.of("status","idle","creationEnabled",resident==null,"restoreEnabled",restoreEnabled);
        }
        catch(Throwable failure){worker.shutdownNow();nativeCommitWorker.shutdownNow();checkpointWorker.shutdownNow();for(AutoCloseable resource:Arrays.asList(nativeCommits,checkpoints,history,sceneDomain,editorStates,deletedScenes,environment,worldEvents))if(resource!=null)try{resource.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;if(cause instanceof Exception error)throw error;if(cause instanceof Error error)throw error;throw new IllegalStateException(cause);}
    }
    private void initializeState(CommandStateCheckpoint.State state)throws java.io.IOException{
        if(sceneDomain!=null&&sceneDomain!=state.domain()){sceneDomain.close();editorStates.close();deletedScenes.close();}sceneArchive=new SceneTransactionArchive(journal.path());inventories=state.inventories();entityProgram=state.actors();sceneDomain=state.domain();editorStates=state.editor();deletedScenes=state.deleted();relationships=state.links();
        editor=new BlockEditor(world,history,new BlockStateArchive(journal.path()));sceneEditor=new SceneEditor(world,history,sceneSeeds,relationships,editorStates,deletedScenes);scenePortals=new ScenePortalProjection(world);sceneSelection=new SceneSelectionEditor(world,history,sceneEditor,editorStates,sceneDomain,scenePortals);
    }
    private void bootstrapResident(ResidentMapBootstrap.Bundle bundle)throws Exception{
        if(!world.beforeStart())throw new IllegalStateException("resident_bootstrap_after_start");var binding=bundle.binding();
        if(binding.source()==null||!journal.matches(binding.source()))throw new IllegalArgumentException("resident_command_source_changed");
        world.bootstrap(w->{bundle.metadata().requireBinding(w.identity(),w.sceneRegistryDigest());return true;});
        initializeState(bundle.takeCommands());world.bootstrap(w->{bundle.metadata().restoreInto(w);entityProgram.restoreWorld(w);return true;});
        long cut=binding.source().end();long[] covered={0},suffix={0};
        journal.replayLocated((reference,event)->{
            long end=Math.addExact(reference.offset(),8L+reference.length());String type=Json.text(event,"type",32);
            if(end<=cut){
                boolean imported=IMPORT_TYPES.contains(type);history.register(reference,event,imported);if(type.equals("undo")||type.equals("scene_undo"))history.markUndone(Json.text(event,"target",64));worldEvents.reconcile(event,"map");covered[0]++;recoveredRecords++;if(imported)importedRecords++;
            }else{
                // Resident map actions have no journal suffix. Replaying one would mix two authorities.
                if(!Set.of("give","spawn","goal","patrol","follow","remove","actor_runtime").contains(type))throw new IllegalArgumentException("resident_legacy_map_suffix:"+type);
                recover(reference,event);suffix[0]++;
            }
        }).get();
        if(covered[0]!=binding.source().records())throw new IllegalArgumentException("resident_command_source_count");
        restoreEnabled=false;savedCheckpointRecords=covered[0];savedActorTick=world.snapshot().tick();bootstrapView=Map.of("method","resident","revision",bundle.history().revision(),"coveredRecords",covered[0],"suffixApplied",suffix[0],"fallback",false);nativeCommitView=Map.of("status","resident_authority");
    }
    private void bootstrap()throws Exception{
        World original=world.bootstrap(w->w);checkpointTerrain=original.checkpointTerrain();String binding;String fallback="";try{binding=checkpointTerrain.checkpointBinding();}catch(java.io.IOException unavailable){binding=null;fallback="base_unavailable";}restoreEnabled=binding!=null;WorldCheckpointStore.Validated checked=null;WorldStateCheckpoint.Bundle bundle=null;
        if(restoreEnabled){try{var restored=WorldStateCheckpoint.restore(checkpoints,original,binding,nativeCommits);if(restored.isPresent()){checked=restored.get().checkpoint();bundle=restored.get().bundle();binding=checkpointTerrain.checkpointBinding();}}catch(Exception invalid){if(!journal.healthy())throw invalid;checked=null;fallback=invalid.getClass().getSimpleName();}}
        if(bundle!=null){
            world.replaceBootstrap(bundle.world());initializeState(bundle.commands());long cut=checked.metadata().prefix().end();long[] covered={0},suffix={0};
            try{
                journal.replayLocated((reference,event)->{
                    if(Math.addExact(reference.offset(),8L+reference.length())<=cut){editor.validateExternalState(sceneArchive.hydrate(event));worldEvents.reconcile(event,"map");boolean imported=IMPORT_TYPES.contains(Json.text(event,"type",32));history.register(reference,event,imported);String type=Json.text(event,"type",32);if(type.equals("undo")||type.equals("scene_undo"))history.markUndone(Json.text(event,"target",64));covered[0]++;recoveredRecords++;if(imported)importedRecords++;}
                    else {recover(reference,event);suffix[0]++;}
                }).get();
                if(covered[0]!=checked.metadata().prefix().records()||!binding.equals(checkpointTerrain.checkpointBinding()))throw new IllegalArgumentException("checkpoint_bootstrap_binding_changed");
                savedCheckpointRecords=covered[0];savedActorTick=world.snapshot().tick();savedCheckpointNativeBase=bundle.nativeSceneBase()?liveNativeGeneration():"";bootstrapView=Map.of("method","checkpoint","generation",checked.metadata().generation().toString(),"coveredRecords",covered[0],"suffixApplied",suffix[0],"fallback",false);return;
            }catch(Exception invalid){if(!journal.healthy()||!history.healthy())throw invalid;fallback=invalid.getClass().getSimpleName();history.resetForBootstrap();recoveredRecords=importedRecords=0;world.replaceBootstrap(original);}
        }
        if(checkpointTerrain instanceof RegionTerrain regions&&!regions.liveCommits().generation().isEmpty())regions.prepareLive(NativeCommitIndex.empty()).get().publish();
        initializeState(CommandStateCheckpoint.State.empty(journal.path().toAbsolutePath().getParent()));journal.replayLocated(this::recover).get();bootstrapView=Map.of("method","journal","coveredRecords",0L,"suffixApplied",recoveredRecords,"fallback",!fallback.isEmpty(),"fallbackReason",fallback);
    }
    private void recover(Journal.Reference reference,JsonObject event)throws Exception{
        boolean imported=IMPORT_TYPES.contains(Json.text(event,"type",32));history.register(reference,event,imported);
        apply(sceneArchive.hydrate(event),true);worldEvents.reconcile(event,"map");recoveredRecords++;if(imported)importedRecords++;
    }
    /** Ephemeral mini-game actors use normal collision preparation without filling the durable command journal. */
    public CompletableFuture<Void> spawnTransientFollower(String id,World.Vec position,UUID target,java.util.function.Consumer<World> authorization){
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(!admission.tryAcquire()){result.completeExceptionally(new RejectedExecutionException("command_queue_full"));return result;}
        result.whenComplete((value,error)->admission.release());
        try{worker.execute(()->{
            try{
                if(!healthy())throw new IllegalStateException("server_read_only_after_failure");
                prepareSpawn(id,"BOT",position);
                world.submit(w->{authorization.accept(w);w.spawnTransient(id,"BOT",position);w.follow(id,new World.Follow(target,1.6));return true;})
                    .whenComplete((value,error)->{if(error==null)result.complete(null);else result.completeExceptionally(error);});
            }catch(Throwable error){result.completeExceptionally(error);}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}
        return result;
    }
    public CompletableFuture<JsonObject> submit(JsonObject request){
        if(residentHistoryEnabled){var runtime=world.residentMap();if(runtime!=null){String type;try{type=Json.text(request,"type",32);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}if(SceneEditor.handles(type)||SceneDomain.handles(type))return submitResidentScenes(request,runtime,new UUID(0,0));if(type.equals("block")||BlockEditor.handles(type))return submitResidentBulk(request,runtime,new UUID(0,0));}}
        JsonObject copy=request.deepCopy();CompletableFuture<JsonObject> future=new CompletableFuture<>();
        String submittedId,submittedFingerprint;
        try{submittedId=Json.text(copy,"commandId",64);if(players!=null&&Json.text(copy,"type",32).equals("give"))szserver.inventory.AdminGive.read(copy);submittedFingerprint=fingerprint(copy);}
        catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
        Flight existing=inFlight.get(submittedId);
        if(existing!=null)return existing.fingerprint.equals(submittedFingerprint)?existing.future:CompletableFuture.failedFuture(new IllegalArgumentException("command_id_reused_with_different_request"));
        if(!admission.tryAcquire()){rejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        Flight race=inFlight.putIfAbsent(submittedId,new Flight(submittedFingerprint,future));
        if(race!=null){admission.release();return race.fingerprint.equals(submittedFingerprint)?race.future:CompletableFuture.failedFuture(new IllegalArgumentException("command_id_reused_with_different_request"));}
        try{worker.execute(()->{
            long start=System.nanoTime();boolean durable=false;szserver.persistence.WorldEventStore.Ref eventReference=null;
            try{
                if(!healthy())throw new IllegalStateException("server_read_only_after_failure");
                String id=Json.text(copy,"commandId",64);String fingerprint=fingerprint(copy);
                JsonObject previous=history.raw(id);
                if(previous!=null){
                    if(IMPORT_TYPES.contains(Json.text(previous,"type",32)))throw new IllegalArgumentException(IMPORT_TYPES.contains(Json.text(copy,"type",32))?"unknown_command":"command_id_reused_with_different_request");
                    if(!fingerprint.equals(Json.text(previous,"request",65536)))throw new IllegalArgumentException("command_id_reused_with_different_request");
                    future.complete(response(previous));return;
                }
                Noop noop=noops.get(id);if(noop!=null){if(!fingerprint.equals(noop.fingerprint()))throw new IllegalArgumentException("command_id_reused_with_different_request");future.complete(noop.response().deepCopy());return;}
                // A character fsync may precede the world audit record. Reserve its identity
                // globally, including attempts to reuse it for a map mutation after a crash.
                PlayerInventories gateway=players;JsonObject nativeReceipt=gateway==null?null:gateway.receipt(id);
                if(nativeReceipt!=null&&!fingerprint.equals(Json.text(nativeReceipt,"request",64)))throw new IllegalArgumentException("command_id_reused_with_different_request");
                if(gateway!=null&&!gateway.healthy())throw new IllegalStateException("admin_inventory_unavailable");
                String submittedType=Json.text(copy,"type",32);if(residentHistoryEnabled&&(submittedType.equals("block")||BlockEditor.handles(submittedType)||SceneEditor.handles(submittedType)||SceneDomain.handles(submittedType)))throw new IllegalStateException("resident_map_requires_native_ingress");
                JsonObject event=prepare(copy);event.addProperty("commandId",id);event.addProperty("request",fingerprint);
                // Raw-container loading can echo identical FULL_SAVE state. No journal/refresh feedback loop.
                if(event.has("status")&&event.get("status").getAsString().equals("unchanged")){event.remove("request");while(noopOrder.size()>=256)noops.remove(noopOrder.removeFirst());noopOrder.addLast(id);noops.put(id,new Noop(fingerprint,event.deepCopy()));future.complete(event);return;}
                if(BlockEditor.handles(Json.text(event,"type",32)))editor.prepareApply(event,sceneSelection.prepareApply(event));
                if(SceneEditor.handles(Json.text(event,"type",32)))sceneEditor.prepareApplication(event);
                if(SceneDomain.handles(Json.text(event,"type",32)))scenePortals.prepareApplication(event);
                String eventType=Json.text(event,"type",32);if(eventType.equals("block")||eventType.equals("block_cas")||eventType.equals("tile_set")){JsonObject position=event.getAsJsonObject(eventType.equals("block")?"position":"min");int idValue=event.get("blockId").getAsInt();JsonObject payload=Json.tree(Map.of("dimension",0,"x",position.get("x").getAsInt(),"y",position.get("y").getAsInt(),"z",position.get("z").getAsInt(),"blockNumericId",idValue,"metadata",event.has("metadata")?event.get("metadata").getAsInt():0));eventReference=worldEvents.prepare("map",idValue==0?"block_broken":"block_placed",payload).get();event.add("worldEvent",eventReference.json());}
                JsonObject durableEvent=sceneArchive.store(event);Journal.Reference reference=journal.appendLocated(durableEvent).get();durable=true;
                // Apply completion back on the world owner; disk never holds it.
                apply(event,false);if(eventReference!=null)worldEvents.commit(eventReference).get();history.register(reference,durableEvent,false);publish();future.complete(response(durableEvent));
            }catch(Throwable e){
                Throwable cause=e instanceof ExecutionException?e.getCause():e;
                if(eventReference!=null&&!durable&&worldEvents.healthy())try{worldEvents.abort(eventReference).get();}catch(Exception cleanup){cause.addSuppressed(cleanup);}
                if(durable||!journal.healthy()){healthy=false;failure="durable_apply_or_storage_failure:"+cause.getClass().getSimpleName();}
                future.completeExceptionally(cause);
            }finally{editor.discardPrepared();sceneEditor.discardPrepared();scenePortals.discardPrepared();maxCommandMs=Math.max(maxCommandMs,(System.nanoTime()-start)/1e6);inFlight.remove(submittedId);admission.release();}
        });}catch(RejectedExecutionException e){rejected.incrementAndGet();inFlight.remove(submittedId);admission.release();future.completeExceptionally(e);}
        return future;
    }
    /** Native map ingress. Preparation and logical publication use the existing command queue;
     * the applier commits canonical bytes with native state, with no journal append here. */
    public CompletableFuture<JsonObject> submitResident(JsonObject request,ResidentApply applier){
        Objects.requireNonNull(applier);if(!residentHistoryEnabled)return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_bound"));JsonObject copy=request.deepCopy();String id,fingerprint;
        try{id=Json.text(copy,"commandId",64);fingerprint=fingerprint(copy);String type=Json.text(copy,"type",32);if(!type.equals("block")&&!BlockEditor.handles(type)&&!SceneEditor.handles(type)&&!SceneDomain.handles(type))throw new IllegalArgumentException("resident_map_command_required");}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        var future=new CompletableFuture<JsonObject>();Flight flight=new Flight(fingerprint,future);Flight prior=inFlight.putIfAbsent(id,flight);if(prior!=null)return prior.fingerprint().equals(fingerprint)?prior.future().copy():CompletableFuture.failedFuture(new IllegalArgumentException("command_id_reused_with_different_request"));
        if(!admission.tryAcquire()){inFlight.remove(id,flight);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        try{worker.execute(()->{ResidentCommandEnvelope.Prepared prepared=null;CompletableFuture<?> mutation=null;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");var previous=history.raw(id);if(previous!=null){if(!fingerprint.equals(Json.text(previous,"request",65536)))throw new IllegalArgumentException("command_id_reused_with_different_request");future.complete(response(previous));return;}
            PlayerInventories gateway=players;if(gateway!=null&&gateway.receipt(id)!=null)throw new IllegalArgumentException("command_id_reused_with_different_request");
            JsonObject event=prepare(copy);event.addProperty("commandId",id);event.addProperty("request",fingerprint);prepared=ResidentCommandEnvelope.prepare(event);history.admitResident(id,event.has("target")?Json.text(event,"target",64):null);
            mutation=applier.apply(event.deepCopy(),prepared);mutation.get();if(prepared.event()<1)throw new IllegalStateException("resident_command_not_committed");
            if(event.has("sceneDomain"))sceneDomain.apply(event,false);deletedScenes.apply(event);if(event.has("editorStateChanges"))editorStates.apply(event);relationships.apply(event);
            String type=Json.text(event,"type",32);if(type.equals("undo")||type.equals("scene_undo"))history.markUndone(Json.text(event,"target",64));history.registerResident(prepared);publish();future.complete(response(event));
        }catch(Throwable failure){Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;if(prepared!=null&&(prepared.event()>0||mutation!=null&&!mutation.isDone())){healthy=false;this.failure="resident_command_post_commit_failure:"+cause.getClass().getSimpleName();}future.completeExceptionally(cause);}
        finally{editor.discardPrepared();sceneEditor.discardPrepared();scenePortals.discardPrepared();inFlight.remove(id,flight);admission.release();}});}catch(RejectedExecutionException failure){inFlight.remove(id,flight);admission.release();future.completeExceptionally(failure);}return future.copy();
    }
    /** Reads share the map mutation barrier; only two retained HTTP payloads may exist. */
    public CompletableFuture<MapReads.Reply> readMap(MapReads.Request request){
        if(!mapReadAdmission.tryAcquire()){mapReadRejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("map_reads_full"));}
        if(!admission.tryAcquire()){mapReadAdmission.release();mapReadRejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        CompletableFuture<MapReads.Reply> future=new CompletableFuture<>();
        try{worker.execute(()->{
            long start=System.nanoTime();boolean transferred=false;
            try{
                if(future.isCancelled())return;
                if(!healthy())throw new IllegalStateException("server_read_only_after_failure");
                BlockReadView view=world.submit(World::blockReadView).get(2,TimeUnit.SECONDS);
                MapReads.Reply reply=MapReads.execute(request,checkpointTerrain,view,future::isCancelled,mapReadAdmission::release);
                transferred=true;if(!future.complete(reply))reply.close();mapReads.incrementAndGet();
            }catch(Throwable error){Throwable cause=error instanceof ExecutionException?error.getCause():error;mapReadFailures.incrementAndGet();future.completeExceptionally(cause);}
            finally{if(!transferred)mapReadAdmission.release();admission.release();maxMapReadMs=Math.max(maxMapReadMs,(System.nanoTime()-start)/1e6);}
        });}catch(RejectedExecutionException error){admission.release();mapReadAdmission.release();mapReadRejected.incrementAndGet();future.completeExceptionally(error);}
        return future;
    }
    public Map<String,Object> mapReadMetrics(){return Map.of("completed",mapReads.get(),"rejected",mapReadRejected.get(),"failures",mapReadFailures.get(),"retainedOrQueued",2-mapReadAdmission.availablePermits(),"capacity",2,"responseByteLimit",MapReads.MAX_BYTES,"maxReadMs",maxMapReadMs);}
    private static String fingerprint(JsonObject request){
        JsonElement type=request.get("type");boolean culling=type!=null&&type.isJsonPrimitive()&&type.getAsJsonPrimitive().isString()&&type.getAsString().equals("scene_culling");return Json.fingerprint(request,culling);
    }
    private JsonObject response(JsonObject event){JsonObject r=CommandHistory.response(event);if(players!=null&&Json.text(event,"type",32).equals("give")&&!event.has("nativeInventory")){r.addProperty("nativeInventory",false);r.addProperty("inventoryDomain","legacy_virtual");r.addProperty("legacyGrantNotMigrated",true);}return r;}
    private void prepareDeleted(String type,JsonObject event){
        if((type.equals("scene_undo")||type.equals("undo"))&&event.has("target")){JsonObject original=history.get(Json.text(event,"target",64));if(original!=null)deletedScenes.reverse(original,event);}deletedScenes.prepare(event);
    }
    /** Cold catalog/chunk reads retry on the command worker, never on the world owner.
     * A late geometry result remains owned by the completion chain even after timeout. */
    private void prepareSpawn(String id,String kind,World.Vec position)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new TimeoutException("spawn_terrain_load_timeout");
            CompletableFuture<Boolean> validation=world.submit(w->w.actorSceneRequest(position))
                .thenCompose(pending->pending)
                .thenCompose(scene->{
                    try{return world.submit(w->{w.validateSpawn(id,kind,position,scene);return true;})
                        .whenComplete((ok,error)->{if(scene!=null)scene.close();});}
                    catch(Throwable error){if(scene!=null)scene.close();throw error;}
                });
            try{validation.get(remaining,TimeUnit.NANOSECONDS);return;}
            catch(TimeoutException error){throw new TimeoutException("spawn_terrain_load_timeout");}
            catch(ExecutionException error){
                Throwable cause=error.getCause();
                while(cause instanceof ExecutionException||cause instanceof CompletionException)cause=cause.getCause();
                if(!(cause instanceof IllegalArgumentException)||!"terrain_not_ready_retry".equals(cause.getMessage())){
                    if(cause instanceof Exception exception)throw exception;
                    if(cause instanceof Error fatal)throw fatal;
                    throw new IllegalStateException(cause);
                }
                remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new TimeoutException("spawn_terrain_load_timeout");
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(10)));
            }
        }
    }
    private JsonObject prepare(JsonObject request)throws Exception{
        String type=Json.text(request,"type",32);JsonObject event=new JsonObject();event.addProperty("type",type);
        if(SceneDomain.handles(type)){sceneDomain.prepare(type,request,event,world.submit(World::identity).get(5,TimeUnit.SECONDS));scenePortals.compile(event,false);return event;}
        if(SceneEditor.handles(type)){sceneEditor.prepare(type,request,event);prepareDeleted(type,event);if(event.has("editorStateChanges"))scenePortals.compile(event,false);if(type.equals("scene_undo"))sceneDomain.reverseNativeMetadata(history.get(Json.text(event,"target",64)),event);if(event.has("sceneChanges")||event.has("editorStateChanges")||SceneRelationships.hasSceneParents(event)||event.has("sceneNativeAssetRequests")||event.has("sceneNativeMetadata"))sceneDomain.nativeEdit(event,world.submit(World::identity).get(5,TimeUnit.SECONDS));return event;}
        if(BlockEditor.handles(type)){editor.prepare(type,request,event);sceneSelection.prepare(type,request,event);prepareDeleted(type,event);sceneSelection.validate(event,editor);if(type.equals("undo"))sceneDomain.reverseNativeMetadata(history.get(Json.text(event,"target",64)),event);if(event.has("sceneChanges")||event.has("editorStateChanges")||event.has("sceneNativeMetadata"))sceneDomain.nativeEdit(event,world.submit(World::identity).get(5,TimeUnit.SECONDS));return event;}
        switch(type){
            case "give"->{
                PlayerInventories gateway=players;if(gateway!=null)return gateway.give(request,fingerprint(request)).get();
                String owner=UUID.fromString(Json.text(request,"owner",36)).toString();
                if(!inventories.containsKey(owner)&&inventories.size()>=256)throw new IllegalArgumentException("inventory_owner_limit");
                int itemId=Json.integer(request,"itemId",0,65535),count=Json.integer(request,"count",1,64);
                if(count>items.stackLimit(itemId))throw new IllegalArgumentException("item_count_exceeds_conservative_stack_limit");
                TreeMap<Integer,JsonObject> inventory=inventories.getOrDefault(owner,new TreeMap<>());
                int slot=request.has("slot")?Json.integer(request,"slot",0,159):0;
                if(!request.has("slot")){while(slot<160&&inventory.containsKey(slot))slot++;}
                if(slot>=160||inventory.containsKey(slot))throw new IllegalArgumentException("inventory_slot_occupied_or_full");
                JsonObject tags=request.has("stateTags")?Json.object(request,"stateTags"):new JsonObject();
                byte[] raw=items.create(itemId,tags);
                event.addProperty("owner",owner);event.addProperty("slot",slot);event.addProperty("itemId",itemId);
                event.addProperty("count",count);event.addProperty("nativeState",Base64.getEncoder().encodeToString(raw));
            }
            case "spawn"->{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString(),kind=Json.text(request,"kind",16);World.Vec p=position(request);
                prepareSpawn(id,kind,p);
                event.addProperty("entityId",id);event.addProperty("kind",kind);event.add("position",Json.tree(p));
            }
            case "goal"->{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();World.Vec p=position(request);
                if(!world.submit(w->w.contains(id)).get(5,TimeUnit.SECONDS))throw new IllegalArgumentException("entity_not_found");
                event.addProperty("entityId",id);event.add("position",Json.tree(p));
            }
            case "patrol"->{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();List<World.Vec> points=patrolRoute(request);
                if(!world.submit(w->w.contains(id)).get(5,TimeUnit.SECONDS))throw new IllegalArgumentException("entity_not_found");
                event.addProperty("entityId",id);event.add("points",Json.GSON.toJsonTree(points));
            }
            case "follow"->{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();World.Follow follow=followTarget(request);
                if(!world.submit(w->w.contains(id)).get(5,TimeUnit.SECONDS))throw new IllegalArgumentException("entity_not_found");
                event.addProperty("entityId",id);event.addProperty("target",follow.character().toString());event.addProperty("distance",follow.distance());
            }
            case "remove"->{
                String id=UUID.fromString(Json.text(request,"entityId",36)).toString();
                if(!world.submit(w->w.contains(id)).get(5,TimeUnit.SECONDS))throw new IllegalArgumentException("entity_not_found");
                event.addProperty("entityId",id);
            }
            case "block"->{
                World.Block p=new World.Block(Json.integer(request,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.integer(request,"y",0,255),Json.integer(request,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));
                int block=Json.integer(request,"blockId",0,4095);
                int metadata=request.has("metadata")?Json.integer(request,"metadata",0,15):0;
                world.submit(w->{w.validateBlock(p,block,metadata);return true;}).get(5,TimeUnit.SECONDS);
                event.add("position",Json.tree(p));event.addProperty("blockId",block);event.addProperty("metadata",metadata);
            }
            default->throw new IllegalArgumentException("unknown_command");
        }
        return event;
    }
    private static World.Vec position(JsonObject request){JsonObject p=Json.object(request,"position");return new World.Vec(Json.number(p,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.number(p,"y",0,255),Json.number(p,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));}
    static List<World.Vec> patrolRoute(JsonObject request){
        if(!request.has("points")||!request.get("points").isJsonArray())throw new IllegalArgumentException("patrol_points_required");
        JsonArray input=request.getAsJsonArray("points");if(input.size()<2||input.size()>16)throw new IllegalArgumentException("patrol_point_limit");
        List<World.Vec> points=new ArrayList<>();for(JsonElement row:input){JsonObject wrapper=new JsonObject();wrapper.add("position",row);points.add(position(wrapper));}World.validatePatrol(points);return List.copyOf(points);
    }
    static World.Follow followTarget(JsonObject request){return new World.Follow(UUID.fromString(Json.text(request,"target",36)),request.has("distance")?Json.number(request,"distance",1,6):2.5);}
    private void apply(JsonObject e,boolean bootstrap)throws Exception{
        String type=Json.text(e,"type",32);
        if(type.equals("actor_runtime")){
            if(!bootstrap)throw new IllegalArgumentException("actor_runtime_internal_only");var snapshot=ActorRuntimeCodec.snapshot(Json.object(e,"actorRuntime"));entityProgram.runtime(snapshot);
            world.bootstrap(w->{if(!w.identity().equals(Json.text(e,"worldIdentity",4096)))throw new IllegalArgumentException("actor_runtime_world_mismatch");w.restoreActorRuntime(snapshot);return true;});return;
        }
        if(type.equals("scene_deleted_import")){if(!bootstrap)throw new IllegalArgumentException("scene_deleted_import_offline_only");world.bootstrap(w->{if(!Json.text(e,"worldIdentity",4096).equals(w.identity()))throw new IllegalArgumentException("scene_deleted_import_world_identity_mismatch");if(!Json.text(e,"sceneRegistry",64).equals(w.sceneRegistryDigest()))throw new IllegalArgumentException("scene_edit_registry_changed");return true;});SceneDeletedImportSeed.validate(e);deletedScenes.apply(e);return;}
        if(type.equals("scene_relationship_import")){if(!bootstrap)throw new IllegalArgumentException("scene_relationship_import_offline_only");world.bootstrap(w->{if(!Json.text(e,"worldIdentity",4096).equals(w.identity()))throw new IllegalArgumentException("scene_relationship_import_world_identity_mismatch");if(!Json.text(e,"sceneRegistry",64).equals(w.sceneRegistryDigest()))throw new IllegalArgumentException("scene_edit_registry_changed");return true;});relationships.validate(e);relationships.apply(e);return;}
        if(type.equals("native_block_import")){if(!bootstrap)throw new IllegalArgumentException("native_block_import_offline_only");var cells=NativeBlockSeed.read(e);world.bootstrap(w->{if(!Json.text(e,"worldIdentity",4096).equals(w.identity()))throw new IllegalArgumentException("native_block_import_world_identity_mismatch");NativeBlockSeed.apply(w,cells);return true;});return;}
        if(type.equals("scene_store_import")||SceneDomain.handles(type)||e.has("sceneDomain")){
            if(type.equals("scene_store_import")&&!bootstrap)throw new IllegalArgumentException("scene_domain_import_offline_only");
            java.util.function.Function<World,Boolean> identityCheck=w->{if(!Json.text(e,"sceneDomainNamespace",4096).equals(w.identity()))throw new IllegalArgumentException("scene_domain_namespace_mismatch");return true;};
            if(bootstrap)world.bootstrap(identityCheck);else world.submit(identityCheck).get(5,TimeUnit.SECONDS);
            if(type.equals("scene_store_import")||SceneDomain.handles(type))scenePortals.apply(e,bootstrap);
            sceneDomain.apply(e,type.equals("scene_store_import"));if(type.equals("scene_store_import")||SceneDomain.handles(type))return;
        }
        if(type.equals("scene_import")||type.equals("scene_portal_import")){
            if(!bootstrap)throw new IllegalArgumentException("scene_import_offline_only");
            world.bootstrap(w->{if(!Json.text(e,"worldIdentity",4096).equals(w.identity()))throw new IllegalArgumentException("scene_import_world_identity_mismatch");return true;});
            if(type.equals("scene_import")){sceneEditor.apply(e,true);deletedScenes.apply(e);}else world.bootstrap(w->{if(!Json.text(e,"sceneRegistry",64).equals(w.sceneRegistryDigest()))throw new IllegalArgumentException("scene_edit_registry_changed");return true;});
            if(e.has("editorStateChanges")){SceneEditorStateImportSeed.validate(e);editorStates.apply(e);}if(e.has("portalMetadata"))scenePortals.apply(e,true);return;
        }
        if(SceneEditor.handles(type)){sceneEditor.apply(e,bootstrap);deletedScenes.apply(e);if(e.has("editorStateChanges"))editorStates.apply(e);relationships.apply(e);return;}
        if(type.equals("give")){
            if(e.has("nativeInventory")){szserver.inventory.AdminGiveReceipt.read(e);return;}
            String owner=Json.text(e,"owner",36);int slot=Json.integer(e,"slot",0,159);
            JsonObject stack=new JsonObject();for(String key:List.of("slot","itemId","count","nativeState"))stack.add(key,e.get(key).deepCopy());
            inventories.computeIfAbsent(owner,k->new TreeMap<>()).put(slot,stack);return;
        }
        if(BlockEditor.handles(type)){editor.apply(e,bootstrap,bootstrap?sceneSelection.prepareApply(e,true):null);deletedScenes.apply(e);if(e.has("editorStateChanges"))editorStates.apply(e);return;}
        java.util.function.Function<World,Boolean> action=w->{
            String id=e.has("entityId")?e.get("entityId").getAsString():"";
            switch(type){
                case "spawn"->w.spawn(id,Json.text(e,"kind",16),position(e));
                case "goal"->w.goal(id,position(e));
                case "patrol"->w.patrol(id,patrolRoute(e));
                case "follow"->w.follow(id,followTarget(e));
                case "remove"->w.remove(id);
                case "block"->{JsonObject p=Json.object(e,"position");w.block(new World.Block(Json.integer(p,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.integer(p,"y",0,255),Json.integer(p,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL)),Json.integer(e,"blockId",0,4095),e.has("metadata")?Json.integer(e,"metadata",0,15):0);}
                default->throw new IllegalArgumentException("unsupported_journal_event");
            }
            return true;
        };
        if(bootstrap)world.bootstrap(action);else world.submit(action).get(5,TimeUnit.SECONDS);
        entityProgram.apply(e);
    }
    private void publish(){
        sceneDomain.publish();relationships.publish();editorStates.publish();
        HashMap<String,List<JsonObject>> next=new HashMap<>();inventories.forEach((owner,slots)->next.put(owner,slots.values().stream().map(JsonObject::deepCopy).toList()));inventoryView=Map.copyOf(next);
        residentPublication=Math.incrementExact(residentPublication);
    }
    public synchronized void bindPlayerInventories(PlayerInventories gateway){Objects.requireNonNull(gateway);if(players!=null&&players!=gateway)throw new IllegalStateException("player_inventory_already_bound");players=gateway;}
    /** A failed/stopped native service must not keep a closed gateway attached to this realm. */
    public synchronized void unbindPlayerInventories(PlayerInventories gateway){if(players==gateway)players=null;}
    public List<JsonObject> inventory(String owner){PlayerInventories gateway=players;return gateway==null?inventoryView.getOrDefault(owner,List.of()).stream().map(JsonObject::deepCopy).toList():gateway.inventory(UUID.fromString(owner));}
    public JsonObject sceneObject(String id){return sceneDomain.object(id);}
    public JsonObject nativeEditorState(UUID id){return editorStates.snapshot(id);}
    public JsonObject nativeEditorRelationships(UUID id){return relationships.snapshot(id);}
    public JsonObject developmentSceneSnapshot(){return sceneDomain.snapshot();}
    public szserver.util.JsonDocument developmentSceneDocument(){return sceneDomain.snapshotDocument();}
    public List<JsonObject> sceneObjects(Integer x,Integer z,int limit){return sceneDomain.objects(x,z,null,limit);}
    public List<JsonObject> sceneObjects(Integer x,Integer z,String after,int limit){return sceneDomain.objects(x,z,after,limit);}
    public List<JsonObject> sceneTombstones(int limit){return sceneDomain.tombstones(null,limit);}
    public List<JsonObject> sceneTombstones(String after,int limit){return sceneDomain.tombstones(after,limit);}
    public List<JsonObject> sceneChanges(long after,int limit){return sceneDomain.changes(after,limit);}
    public JsonPages.Page worldEventPage(long after,int limit){return backendPages.admit(worldEvents.pageSpec(after,limit));}
    public JsonPages.Page scenePage(String field,Integer x,Integer z,String after,int limit){return backendPages.admit(sceneDomain.pageSpec(field,x,z,after,limit));}
    public Map<String,Object> sceneDomainMetrics(){return sceneDomain.metrics();}
    public JsonObject status(String id){JsonObject e=history.raw(id);if(e!=null&&!Json.text(e,"type",32).endsWith("_import"))return response(e);PlayerInventories gateway=players;if(gateway!=null){e=gateway.receipt(id);if(e!=null)return response(e);}Noop noop=noops.get(id);return noop!=null?noop.response().deepCopy():inFlight.containsKey(id)?Json.tree(Map.of("commandId",id,"status","pending")):null;}
    public boolean healthy(){PlayerInventories gateway=players;return healthy&&(worldEvents==null||worldEvents.healthy())&&(environment==null||environment.healthy())&&journal.healthy()&&history.healthy()&&(sceneDomain==null||sceneDomain.healthy())&&(editorStates==null||editorStates.healthy())&&(deletedScenes==null||deletedScenes.healthy())&&(gateway==null||gateway.healthy());}
    public Map<String,Object> historyPackingStatus(){return journal.packingStatus();}
    public CompletableFuture<Map<String,Object>> packHistory(){
        if(!healthy())return CompletableFuture.failedFuture(new IllegalStateException("server_read_only_after_failure"));
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));
        var result=new CompletableFuture<Map<String,Object>>();journal.packHistory().whenComplete((value,error)->{admission.release();if(error==null)result.complete(value);else result.completeExceptionally(error);});return result.copy();
    }
    /** Serialized barrier:all map/command mutation shares this worker. World/native simulation and
     * native inventory workers remain independent. Cancellation does not abort an accepted save. */
    private Map<String,Object> compactSceneState(boolean force){var result=new LinkedHashMap<>(sceneDomain.compactRows(force));result.put("editor",editorStates.compactRows(force));result.put("deleted",deletedScenes.compactRows(force));return Collections.unmodifiableMap(result);}
    public Map<String,Object> nativeMapArchiveStatus(){return nativeMapArchiveView;}
    /** Captured native base reader for loader/checkpoint integration. Disk reads remain worker-only. */
    public szserver.world.NativeCommitIndex nativeMapReadView(){if(nativeCommits==null)throw new IllegalStateException("native_commit_unavailable");return nativeCommits.readView();}
    public Map<String,Object> nativeMapCommitStatus(){Map<String,Object> view=new LinkedHashMap<>(nativeCommitView);view.put("pending",pendingNativeCommit.get()>0);view.put("automatic",nativeCommitPolicy!=null);view.put("attempts",nativeCommitAttempts.get());view.put("failures",nativeCommitFailures.get());view.put("deferrals",nativeCommitDeferrals.get());view.put("liveBase",nativeBaseView);if(nativeCommits!=null)view.put("storage",nativeCommits.metrics());return Collections.unmodifiableMap(view);}
    public Map<String,Object> nativeMapMaintenanceStatus(){var view=new LinkedHashMap<>(nativeMaintenanceView);view.put("pending",pendingNativeCommit.get()>0);view.put("automatic",nativeCommitPolicy!=null);view.put("maintenanceGenerationInterval",64);view.put("automaticRecordConsolidation",true);view.put("selectiveRecordConsolidation",nativeCommits==null?Map.of():nativeCommits.selectiveStatus());view.put("canonicalHistoryRetained",true);if(nativeCommits!=null)view.put("storage",nativeCommits.maintenance());return Collections.unmodifiableMap(view);}
    public CompletableFuture<Map<String,Object>> maintainNativeMap(){return maintainNativeMap(2);}
    public CompletableFuture<Map<String,Object>> consolidateNativeMap(){return maintainNativeMap(3);}
    /** 0=sweep, 1=checkpoint retention, 2=full manual consolidation, 3=bounded selective consolidation. */
    private CompletableFuture<Map<String,Object>> maintainNativeMap(int mode){
        if(nativeCommits==null)return CompletableFuture.failedFuture(new IllegalStateException("native_commit_unavailable"));if(!pendingNativeCommit.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("native_commit_already_pending"));
        if(!admission.tryAcquire()){pendingNativeCommit.set(0);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}var result=new CompletableFuture<Map<String,Object>>();nativeMaintenanceView=Map.of("status","queued");
        try{worker.execute(()->{try{if(!healthy())throw new IllegalStateException("server_read_only_after_failure");var capture=mode>0?world.submit(szserver.world.NativeMapArchive::capture).get(5,TimeUnit.SECONDS):null;if(mode>0)nativeRetentionDirty=false;
                nativeCommitWorker.execute(()->{try{Set<String> bases=new HashSet<>(mode>0?checkpoints.nativeBases():Set.of());String pin=checkpointNativePin;if(!pin.isEmpty())bases.add(pin);var saved=mode>0?nativeCommits.maintain(capture,bases,mode,view->nativeMaintenanceView=view):nativeCommits.sweep();if(mode>0){nativeWritesAtMaintenance=((Number)nativeCommits.metrics().get("generations")).longValue();}if(checkpointTerrain instanceof RegionTerrain regions)regions.archiveCommits(nativeCommits.readView());
                    worker.execute(()->{try{advanceNativeBase(false);if(mode==3&&Objects.equals(nativeCommits.selectiveStatus().get("status"),"selected"))nativeRetentionDirty=true;nativeMaintenanceView=Map.of("status","complete","result",saved);pendingNativeCommit.set(0);result.complete(nativeMapMaintenanceStatus());}catch(Throwable failure){nativeMaintenanceFailed(failure,result);}});
                }catch(Throwable failure){nativeMaintenanceFailed(failure,result);}});
            }catch(Throwable failure){nativeMaintenanceFailed(failure,result);}finally{admission.release();}});
        }catch(RejectedExecutionException failure){admission.release();nativeMaintenanceFailed(failure,result);}return result.copy();
    }
    private void nativeMaintenanceFailed(Throwable failure,CompletableFuture<Map<String,Object>> result){if(nativeCommits!=null&&checkpointTerrain instanceof RegionTerrain regions)regions.archiveCommits(nativeCommits.readView());nativeRetentionDirty=true;nativeCheckedEpoch=-1;pendingNativeCommit.set(0);nativeMaintenanceView=Map.of("status","maintenance_failed","error",failure.getClass().getSimpleName(),"reason",Objects.toString(failure.getMessage(),""));nextNativeSweep=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);result.completeExceptionally(failure);}
    private String liveNativeGeneration(){return checkpointTerrain instanceof RegionTerrain regions?regions.liveCommits().generation():"";}
    private boolean nativeBasePending(){return nativeCommits!=null&&!liveNativeGeneration().equals(nativeCommits.readView().generation());}
    /** Serialized command worker prepares cache/root replacements; the world owner only swaps references. */
    private void advanceNativeBase(boolean bootstrap)throws Exception{
        if(!(checkpointTerrain instanceof RegionTerrain regions))return;NativeCommitIndex index=nativeCommits.readView();boolean moving=nativeBasePending();
        var capture=bootstrap?world.bootstrap(w->w.overlayVersions().capture()):world.submit(w->w.overlayVersions().capture()).get(5,TimeUnit.SECONDS);
        if(!moving&&capture.sceneCount()==0){if(!index.generation().isEmpty())publishNativeBaseView(index);return;}
        var retired=BlockOverlays.prepareNativeRetirement(capture,index);if(!moving&&retired.retiredScenes().isEmpty()){if(!index.generation().isEmpty())publishNativeBaseView(index);return;}var terrain=moving?regions.prepareLive(index).get():null;
        var metadataCapture=bootstrap?world.bootstrap(World::sceneMetadataView):world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);var metadata=World.prepareNativeSceneRetirement(metadataCapture,retired,terrain==null?regions::basePortal:terrain::portal);
        java.util.function.Function<World,Boolean> publish=w->{capture.checkCurrent();if(terrain!=null)terrain.checkCurrent();metadata.validate(w);w.overlayVersions().publish(retired);if(terrain!=null)terrain.publish();w.publishSceneMetadata(metadata);return true;};
        if(bootstrap)world.bootstrap(publish);else world.submit(publish).get(5,TimeUnit.SECONDS);
        retiredNativeVoxels.addAndGet(capture.voxelCount()-retired.voxelCount());retiredNativeTiles.addAndGet(capture.tileCount()-retired.tileCount());retiredNativeBytes.addAndGet(capture.completeTileBytes()-retired.completeTileBytes());retiredNativeScenes.addAndGet(capture.sceneCount()-retired.sceneCount());publishNativeBaseView(index);
    }
    private void publishNativeBaseView(NativeCommitIndex index){nativeBaseView=Map.ofEntries(Map.entry("status","active"),Map.entry("generation",index.generation()),Map.entry("indexedChunks",index.size()),Map.entry("retiredVoxels",retiredNativeVoxels.get()),Map.entry("retiredTiles",retiredNativeTiles.get()),Map.entry("retiredCompleteBytes",retiredNativeBytes.get()),Map.entry("retiredScenePlacements",retiredNativeScenes.get()),Map.entry("voxelTileOverlaysRetired",true),Map.entry("sceneOverlaysRetired",true),Map.entry("globalSceneCatalogHandoff",true),Map.entry("metadataTombstonesRetained",true));}
    private void finishNativeCommit(CompletableFuture<Map<String,Object>> result){try{advanceNativeBase(false);pendingNativeCommit.set(0);result.complete(nativeMapCommitStatus());}catch(Throwable failure){pendingNativeCommit.set(0);nativeCommitFailed(failure,result);}}
    private void enqueueNativeHandoff(CompletableFuture<Map<String,Object>> result){
        if(worker.isShutdown()){pendingNativeCommit.set(0);result.complete(nativeMapCommitStatus());return;}
        try{worker.execute(()->finishNativeCommit(result));}catch(RejectedExecutionException failure){pendingNativeCommit.set(0);nativeCheckedEpoch=-1;nextNativeAttempt=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);nativeCommitDeferrals.incrementAndGet();result.complete(nativeMapCommitStatus());}
    }
    /** Listener startup enables automatic coalescing; constructor/import tests stay offline. */
    public synchronized void startNativeMapCommits(){
        if(nativeCommitPolicy!=null)throw new IllegalStateException("native_commit_policy_already_started");if(nativeCommits==null)return;
        nativeCommitPolicy=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"native-map-policy");t.setDaemon(true);return t;});
        nextNativeSweep=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        nativeCommitPolicy.scheduleWithFixedDelay(()->{if(!worker.isShutdown()&&pendingNativeCommit.get()==0){if(System.nanoTime()>=nextNativeAttempt&&(world.overlayVersions().epoch()!=nativeCheckedEpoch||nativeBasePending()))commitNativeMap();else if(System.nanoTime()>=nextNativeSweep){nextNativeSweep=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long generations=((Number)nativeCommits.metrics().get("generations")).longValue();maintainNativeMap(generations-nativeWritesAtMaintenance>=64||nativeRetentionDirty||nativeCommits.selectivePending()?3:0);}}},250,250,TimeUnit.MILLISECONDS);
    }
    /** Captures one complete latest write set, then releases the command barrier before codec/fsync IO. */
    public CompletableFuture<Map<String,Object>> commitNativeMap(){
        if(world.residentMap()!=null)return commitResidentMap();
        if(nativeCommits==null)return CompletableFuture.failedFuture(new IllegalStateException("native_commit_unavailable"));
        if(!pendingNativeCommit.compareAndSet(0,1)){nativeCommitDeferrals.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("native_commit_already_pending"));}
        if(!admission.tryAcquire()){pendingNativeCommit.set(0);nativeCommitDeferrals.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var result=new CompletableFuture<Map<String,Object>>();nativeCommitView=Map.of("status","capturing");nativeCommitAttempts.incrementAndGet();
        try{worker.execute(()->{try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");var capture=world.submit(szserver.world.NativeMapArchive::capture).get(5,TimeUnit.SECONDS);long epoch=world.overlayVersions().epoch();var detached=capture.changedSince(nativeCommits.acknowledged());
            Journal.Prefix prefix=journal.durablePrefix().get();if(prefix.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("native_commit_source_not_fully_applied");nativeCheckedEpoch=epoch;
            if(detached.empty()){nativeCommitView=Map.of("status","unchanged","chunks",0);finishNativeCommit(result);return;}
            nativeCommitView=Map.of("status","queued","chunks",detached.revisions().size());
            nativeCommitWorker.execute(()->{try{nativeCommitView=nativeCommits.commit(detached,prefix,view->nativeCommitView=view);if(checkpointTerrain instanceof RegionTerrain regions)regions.archiveCommits(nativeCommits.readView());enqueueNativeHandoff(result);}catch(Throwable failure){pendingNativeCommit.set(0);nativeCommitFailed(failure,result);}});
        }catch(Throwable failure){pendingNativeCommit.set(0);nativeCommitFailed(failure,result);}finally{admission.release();}});}catch(RejectedExecutionException failure){admission.release();pendingNativeCommit.set(0);nativeCommitFailed(failure,result);}
        return result.copy();
    }
    private void nativeCommitFailed(Throwable failure,CompletableFuture<Map<String,Object>> result){nativeCommitFailures.incrementAndGet();nativeCheckedEpoch=-1;nextNativeAttempt=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);nativeCommitView=Map.of("status","commit_failed","error",failure.getClass().getSimpleName(),"reason",Objects.toString(failure.getMessage(),""));result.completeExceptionally(failure);}
    public CompletableFuture<Map<String,Object>> saveNativeMapArchive(){
        if(!pendingSceneMaintenance.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("scene_maintenance_already_pending"));
        if(!admission.tryAcquire()){pendingSceneMaintenance.set(0);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var result=new CompletableFuture<Map<String,Object>>();nativeMapArchiveView=Map.of("status","queued");
        try{worker.execute(()->{try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");nativeMapArchiveView=Map.of("status","capturing");String binding=checkpointTerrain.checkpointBinding();Journal.Prefix prefix=journal.prefix().get();
            if(prefix.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("native_archive_source_not_fully_applied");
            var capture=world.submit(szserver.world.NativeMapArchive::capture).get(5,TimeUnit.SECONDS);nativeMapArchiveView=capture.write(journal.path().toAbsolutePath().getParent(),binding,prefix,view->nativeMapArchiveView=view);result.complete(nativeMapArchiveView);
        }catch(Throwable error){nativeMapArchiveView=Map.of("status","save_failed","error",error.getClass().getSimpleName(),"reason",Objects.toString(error.getMessage(),""));result.completeExceptionally(error);}finally{pendingSceneMaintenance.set(0);admission.release();}});}catch(RejectedExecutionException error){pendingSceneMaintenance.set(0);admission.release();nativeMapArchiveView=Map.of("status","save_failed","error",error.getClass().getSimpleName());result.completeExceptionally(error);}
        return result.copy();
    }
    public Map<String,Object> worldJournalMaintenanceStatus(){var view=new LinkedHashMap<>(worldJournalMaintenanceView);view.put("events",worldEvents.maintenance());view.put("environment",environment.maintenance());return Collections.unmodifiableMap(view);}
    public CompletableFuture<Map<String,Object>> compactWorldJournals(){
        if(!pendingSceneMaintenance.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("scene_maintenance_already_pending"));
        if(!admission.tryAcquire()){pendingSceneMaintenance.set(0);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var future=new CompletableFuture<Map<String,Object>>();worldJournalMaintenanceView=Map.of("status","queued");
        try{worker.execute(()->{long started=System.nanoTime();try{if(!healthy())throw new IllegalStateException("server_read_only_after_failure");worldJournalMaintenanceView=Map.of("status","compacting");var environmentResult=environment.compact().get();var eventResult=worldEvents.compact().get();worldJournalMaintenanceView=Map.of("status","compacted","durationMs",(System.nanoTime()-started)/1e6,"result",Map.of("environment",environmentResult,"events",eventResult));future.complete(worldJournalMaintenanceStatus());}catch(Throwable failure){worldJournalMaintenanceView=Map.of("status","compact_failed","error",failure.getClass().getSimpleName());future.completeExceptionally(failure);}finally{pendingSceneMaintenance.set(0);admission.release();}});}catch(RejectedExecutionException error){pendingSceneMaintenance.set(0);admission.release();worldJournalMaintenanceView=Map.of("status","compact_failed","error",error.getClass().getSimpleName());future.completeExceptionally(error);}
        return future.copy();
    }
    public Map<String,Object> cacheCleanupStatus(){var view=new LinkedHashMap<>(cacheCleanupView);view.put("cache",cacheContext.metrics());return Collections.unmodifiableMap(view);}
    public CompletableFuture<Map<String,Object>> cleanDerivedCaches(){
        if(!pendingSceneMaintenance.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("scene_maintenance_already_pending"));
        if(!admission.tryAcquire()){pendingSceneMaintenance.set(0);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var future=new CompletableFuture<Map<String,Object>>();cacheCleanupView=Map.of("status","queued");
        try{worker.execute(()->{long start=System.nanoTime();try{if(!healthy())throw new IllegalStateException("server_read_only_after_failure");cacheCleanupView=Map.of("status","cleaning");var result=cacheContext.sweep();cacheCleanupView=Map.of("status",result.get("status").equals("failed")?"cleanup_failed":"cleaned","durationMs",(System.nanoTime()-start)/1e6,"result",result);future.complete(cacheCleanupStatus());}catch(Throwable error){cacheCleanupView=Map.of("status","cleanup_failed","error",error.getClass().getSimpleName());future.completeExceptionally(error);}finally{pendingSceneMaintenance.set(0);admission.release();}});}catch(RejectedExecutionException error){pendingSceneMaintenance.set(0);admission.release();cacheCleanupView=Map.of("status","cleanup_failed","error",error.getClass().getSimpleName());future.completeExceptionally(error);}
        return future.copy();
    }
    public Map<String,Object> sceneMaintenanceStatus(){return sceneMaintenanceView;}
    public CompletableFuture<Map<String,Object>> compactSceneRows(){
        if(!pendingSceneMaintenance.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("scene_maintenance_already_pending"));
        if(!admission.tryAcquire()){pendingSceneMaintenance.set(0);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var future=new CompletableFuture<Map<String,Object>>();sceneMaintenanceView=Map.of("status","queued");
        try{worker.execute(()->{long start=System.nanoTime();try{if(!healthy())throw new IllegalStateException("server_read_only_after_failure");sceneMaintenanceView=Map.of("status","compacting");var result=compactSceneState(true);sceneMaintenanceView=Map.of("status","compacted","durationMs",(System.nanoTime()-start)/1e6,"result",result);future.complete(sceneMaintenanceView);}catch(Throwable error){sceneMaintenanceView=Map.of("status","compact_failed","error",error.getClass().getSimpleName());future.completeExceptionally(error);}finally{pendingSceneMaintenance.set(0);admission.release();}});}catch(RejectedExecutionException error){pendingSceneMaintenance.set(0);admission.release();sceneMaintenanceView=Map.of("status","compact_failed","error",error.getClass().getSimpleName());future.completeExceptionally(error);}
        return future.copy();
    }
    public CompletableFuture<Map<String,Object>> checkpoint(){
        if(world.residentMap()!=null)return commitResidentMap().thenApply(saved->{checkpointView=saved;return saved;});
        return checkpoint(false);
    }
    /** Manual saves use the same coherent chunk/history generation as periodic saves. */
    private CompletableFuture<Map<String,Object>> commitResidentMap(){
        if(!healthy())return CompletableFuture.failedFuture(new IllegalStateException("server_read_only_after_failure"));
        if(!pendingNativeCommit.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("native_commit_already_pending"));
        nativeCommitAttempts.incrementAndGet();nativeCommitView=Map.of("status","capturing","authority","resident");
        var result=new CompletableFuture<Map<String,Object>>();
        world.submit(w->world.residentMap().save()).thenCompose(save->save).whenComplete((ignored,error)->{
            if(error==null){nativeCommitView=Map.of("status","saved","authority","resident");pendingNativeCommit.set(0);result.complete(nativeMapCommitStatus());}
            else{nativeCommitFailures.incrementAndGet();nativeCommitView=Map.of("status","save_failed","authority","resident","error",error.getClass().getSimpleName());pendingNativeCommit.set(0);result.completeExceptionally(error);}
        });return result.copy();
    }
    private boolean actorsDirty(){var snapshot=world.snapshot();return (!snapshot.entities().isEmpty()||snapshot.mineCooldowns()>0||entityProgram.runtime()!=null&&!entityProgram.runtime().mines().isEmpty())&&snapshot.tick()!=savedActorTick;}
    private record StateCapture(WorldMapCheckpoint.Export map,ActorRuntime.Snapshot actors,String identity,String registry){}
    private void persistActors(ActorRuntime.Snapshot snapshot,String identity)throws Exception{
        boolean changedMines=!snapshot.mines().equals(entityProgram.runtime()==null?List.of():entityProgram.runtime().mines());entityProgram.runtime(snapshot);if(snapshot.counter()==0&&snapshot.mines().isEmpty()&&!changedMines)return;
        JsonObject event=new JsonObject();event.addProperty("type","actor_runtime");event.addProperty("commandId",UUID.randomUUID().toString());event.addProperty("worldIdentity",identity);event.add("actorRuntime",ActorRuntimeCodec.encode(snapshot));
        JsonObject stored=sceneArchive.store(event);boolean durable=false;
        try{var reference=journal.appendLocated(stored).get();durable=true;history.register(reference,stored,true);publish();}
        catch(Exception error){if(durable||!journal.healthy()){healthy=false;failure="actor_runtime_durable_failure";}throw error;}
    }
    public szserver.persistence.AutomaticCheckpoint.Progress checkpointProgress(){return new szserver.persistence.AutomaticCheckpoint.Progress(((Number)history.metrics().get("records")).longValue(),savedCheckpointRecords,restoreEnabled,healthy(),pendingCheckpoints.get(),!savedCheckpointNativeBase.equals(liveNativeGeneration()),actorsDirty());}
    public CompletableFuture<Map<String,Object>> checkpointAutomatic(){return checkpoint(true);}
    private void checkpointFailed(Throwable error,CompletableFuture<Map<String,Object>> future){
        checkpointFailures++;checkpointView=Map.of("status","save_failed","creationEnabled",true,"restoreEnabled",restoreEnabled,"saves",checkpointSaves,"failures",checkpointFailures,"error",error.getClass().getSimpleName(),"lastSaved",lastCheckpoint);future.completeExceptionally(error instanceof ExecutionException?error.getCause():error);
    }
    private CompletableFuture<Map<String,Object>> checkpoint(boolean automatic){
        if(residentHistoryEnabled)return CompletableFuture.failedFuture(new IllegalStateException("resident_checkpoint_requires_coordinated_map_save"));
        if(!pendingCheckpoints.compareAndSet(0,1))return CompletableFuture.failedFuture(new RejectedExecutionException("checkpoint_already_pending"));
        var future=new CompletableFuture<Map<String,Object>>();if(!admission.tryAcquire()){pendingCheckpoints.decrementAndGet();rejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        try{worker.execute(()->{long start=System.nanoTime();boolean dispatched=false;try{
            if(automatic&&((Number)history.metrics().get("records")).longValue()<=savedCheckpointRecords&&savedCheckpointNativeBase.equals(liveNativeGeneration())&&!actorsDirty()){future.complete(Map.of("status","unchanged","sourceRecords",savedCheckpointRecords));return;}
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");
            checkpointView=Map.of("status","capturing","creationEnabled",true,"restoreEnabled",restoreEnabled,"saves",checkpointSaves,"failures",checkpointFailures,"lastSaved",lastCheckpoint);
            NativeCommitIndex nativeBase=checkpointTerrain instanceof RegionTerrain regions?regions.liveCommits():NativeCommitIndex.empty();
            String sourceBinding=checkpointTerrain instanceof RegionTerrain regions?regions.capturedCheckpointBinding(nativeBase):checkpointTerrain.checkpointBinding();
            Journal.Prefix first=journal.durablePrefix().get();if(first.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("checkpoint_source_not_fully_applied");
            StateCapture capture=world.submit(w->new StateCapture(WorldMapCheckpoint.capture(w),w.actorRuntime(),w.identity(),w.sceneRegistryDigest())).get(5,TimeUnit.SECONDS);persistActors(capture.actors(),capture.identity());Journal.Prefix prefix=journal.durablePrefix().get();
            var map=capture.map().detached();var state=CommandStateCheckpoint.capture(new CommandStateCheckpoint.State(entityProgram,inventories,sceneDomain,editorStates,deletedScenes,relationships));
            var metadata=new WorldCheckpointStore.Metadata(UUID.randomUUID(),System.currentTimeMillis(),capture.identity(),capture.registry(),prefix);long captureNanos=System.nanoTime()-start;checkpointNativePin=nativeBase.generation();
            checkpointWorker.execute(()->{try{
                checkpointView=Map.of("status","saving","creationEnabled",true,"restoreEnabled",restoreEnabled,"saves",checkpointSaves,"failures",checkpointFailures,"captureMs",captureNanos/1e6,"lastSaved",lastCheckpoint);
                long verifyStart=System.nanoTime();String checked=checkpointTerrain instanceof RegionTerrain regions?regions.checkpointBindingFor(nativeBase):checkpointTerrain.checkpointBinding();if(!Objects.equals(sourceBinding,checked))throw new IllegalStateException("checkpoint_base_changed_before_save");long[] verifyNanos={System.nanoTime()-verifyStart};
                long saveStart=System.nanoTime();var saved=checkpoints.save(metadata,sink->{map.writeBound(sink,sourceBinding);CommandStateCheckpoint.write(sink,state);long before=System.nanoTime();String after=checkpointTerrain instanceof RegionTerrain regions?regions.checkpointBindingFor(nativeBase):checkpointTerrain.checkpointBinding();if(!Objects.equals(sourceBinding,after))throw new IllegalStateException("checkpoint_base_changed_during_save");verifyNanos[0]+=System.nanoTime()-before;});
                checkpointSaves++;savedActorTick=capture.actors().tick();savedCheckpointRecords=prefix.records();savedCheckpointNativeBase=nativeBase.generation();nativeRetentionDirty=true;
                checkpointView=Map.ofEntries(Map.entry("status","saved"),Map.entry("creationEnabled",true),Map.entry("restoreEnabled",restoreEnabled),Map.entry("generation",saved.metadata().generation().toString()),Map.entry("createdMillis",saved.metadata().createdMillis()),Map.entry("sourceEnd",prefix.end()),Map.entry("sourceRecords",prefix.records()),Map.entry("bytes",saved.bytes()),Map.entry("rows",saved.rows()),Map.entry("durationMs",(System.nanoTime()-start)/1e6),Map.entry("captureMs",captureNanos/1e6),Map.entry("baseCheckMs",verifyNanos[0]/1e6),Map.entry("snapshotWriteMs",(System.nanoTime()-saveStart)/1e6),Map.entry("saves",checkpointSaves),Map.entry("failures",checkpointFailures));lastCheckpoint=checkpointView;future.complete(checkpointView);
            }catch(Throwable error){checkpointFailed(error,future);}finally{checkpointNativePin="";pendingCheckpoints.decrementAndGet();admission.release();}});dispatched=true;
        }catch(Throwable error){checkpointFailed(error,future);}finally{if(!dispatched){checkpointNativePin="";pendingCheckpoints.decrementAndGet();admission.release();}}});}catch(RejectedExecutionException error){pendingCheckpoints.decrementAndGet();admission.release();rejected.incrementAndGet();future.completeExceptionally(error);}return future.copy();
    }
    private ResidentCut residentCutSnapshot()throws Exception{
        Journal.Prefix prefix=journal.durablePrefix().get();if(prefix.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("checkpoint_source_not_fully_applied");
        var snapshot=ResidentCommandCheckpoint.capture(new CommandStateCheckpoint.State(entityProgram,inventories,sceneDomain,editorStates,deletedScenes,relationships));
        return new ResidentCut(snapshot,checkpointTerrain.identity(),checkpointTerrain.sceneRegistryDigest(),prefix,residentPublication,history.residentSnapshot());
    }
    /** Offline migration holds the command barrier through publication. It never installs a live map. */
    public CompletableFuture<Map<String,Object>> migrateResidentMap(ResidentLegacyMap legacy,RegionSnapshotStore target,int batchBytes,java.util.function.Consumer<Map<String,Object>> progress){
        Objects.requireNonNull(legacy);Objects.requireNonNull(target);Objects.requireNonNull(progress);
        if(!world.beforeStart()||residentHistoryEnabled)return CompletableFuture.failedFuture(new IllegalStateException("resident_migration_requires_offline_legacy_service"));
        if(!admission.tryAcquire())return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));var result=new CompletableFuture<Map<String,Object>>();
        try{worker.execute(()->{try{
            if(!healthy()||!world.beforeStart()||residentHistoryEnabled)throw new IllegalStateException("resident_migration_requires_offline_healthy_service");legacy.verifyOffline();
            if(!legacy.binding().equals(target.worldBinding())||target.publishedStateRevision()!=0)throw new IllegalArgumentException("resident_migration_target_already_published_or_mismatched");
            var prefix=journal.durablePrefix().get();if(prefix.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("checkpoint_source_not_fully_applied");
            var metadata=world.bootstrap(w->{w.prepareResidentSceneBaseline();return ResidentWorldMetadata.capture(w);});
            var migratedHistory=new ResidentOperationLog(ResidentOperationLog.Loaded.empty(),64L*1024*1024);migratedHistory.requireCommands();migratedHistory.world(metadata.payload());var nativeState=migratedHistory.freeze().encoder().encode();long revision=nativeState.revision();
            List<ResidentChunks.StateFile> files=new ArrayList<>(nativeState.files());long[] retained={files.stream().mapToLong(ResidentChunks.StateFile::size).sum()};
            ResidentCommandCheckpoint.Pages pages=file->{retained[0]=Math.addExact(retained[0],file.size());if(retained[0]>128L*1024*1024)throw new IllegalArgumentException("resident_migration_state_requires_staged_pages");files.add(file);};
            var commandState=ResidentCommandCheckpoint.capture(new CommandStateCheckpoint.State(entityProgram,inventories,sceneDomain,editorStates,deletedScenes,relationships));
            var encoded=commandState.encode(new ResidentCommandCheckpoint.Binding(checkpointTerrain.identity(),checkpointTerrain.sceneRegistryDigest(),revision,prefix),Set.of(),pages);pages.write(encoded.root());
            var emptyIndex=new ResidentCommandIndex(ResidentCommandIndex.Loaded.empty(),event->{throw new IllegalStateException("resident_migration_has_no_native_events");});emptyIndex.capture().encode(revision,Set.of(),pages);
            var copied=legacy.copyChanges(target,batchBytes,progress);legacy.verifyOffline();if(!journal.matches(prefix))throw new IllegalStateException("resident_migration_source_changed");
            target.save(List.of(),new ResidentChunks.StateDelta(revision,files,Set.of()));
            result.complete(Map.of("status","MIGRATED_NOT_CUTOVER","binding",legacy.binding(),"revision",revision,"sourceRecords",prefix.records(),"chunks",copied.get("chunks"),"payloadSha256",copied.get("payloadSha256"),"stateBytes",retained[0]));
        }catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException?failure.getCause():failure);}finally{admission.release();}});}catch(RejectedExecutionException failure){admission.release();result.completeExceptionally(failure);}return result.copy();
    }
    /** Command worker waits only for the owner freeze, never for serialization or disk. */
    public CompletableFuture<Void> captureResidentCut(ResidentCapture action){
        Objects.requireNonNull(action);ResidentCut sealed=sealedResidentCut;if(sealed!=null)return world.submit(w->{action.capture(w,sealed);return (Void)null;});
        if(!residentExportPending.compareAndSet(false,true))return CompletableFuture.failedFuture(new RejectedExecutionException("resident_command_snapshot_pending"));
        if(!admission.tryAcquire()){residentExportPending.set(false);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var result=new CompletableFuture<Void>();try{worker.execute(()->{try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");var cut=residentCutSnapshot();world.submit(w->{action.capture(w,cut);return true;}).get(5,TimeUnit.SECONDS);result.complete(null);
        }catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException?failure.getCause():failure);}finally{residentExportPending.set(false);admission.release();}});}catch(RejectedExecutionException failure){residentExportPending.set(false);admission.release();result.completeExceptionally(failure);}return result.copy();
    }
    /** Capture through the actual command barrier, encode on the save worker. The map coordinator
     * must bind this cut to its native cohort; this does not independently install a map snapshot. */
    public CompletableFuture<ResidentCommandCheckpoint.Encoded> exportResidentCommands(long revision,Set<String> existing,ResidentCommandCheckpoint.Pages pages){
        if(revision<0)return CompletableFuture.failedFuture(new IllegalArgumentException("resident_command_revision"));Objects.requireNonNull(pages);Set<String> known=Set.copyOf(existing);
        if(!residentExportPending.compareAndSet(false,true))return CompletableFuture.failedFuture(new RejectedExecutionException("resident_command_snapshot_pending"));
        if(!admission.tryAcquire()){residentExportPending.set(false);return CompletableFuture.failedFuture(new RejectedExecutionException("command_queue_full"));}
        var result=new CompletableFuture<ResidentCommandCheckpoint.Encoded>();
        try{worker.execute(()->{boolean dispatched=false;try{
            if(!healthy())throw new IllegalStateException("server_read_only_after_failure");
            Journal.Prefix prefix=journal.durablePrefix().get();if(prefix.records()!=((Number)history.metrics().get("records")).longValue())throw new IllegalStateException("checkpoint_source_not_fully_applied");
            var identity=world.beforeStart()?world.bootstrap(w->List.of(w.identity(),w.sceneRegistryDigest())):world.submit(w->List.of(w.identity(),w.sceneRegistryDigest())).get(5,TimeUnit.SECONDS);
            var snapshot=ResidentCommandCheckpoint.capture(new CommandStateCheckpoint.State(entityProgram,inventories,sceneDomain,editorStates,deletedScenes,relationships));var binding=new ResidentCommandCheckpoint.Binding(identity.get(0),identity.get(1),revision,prefix);
            checkpointWorker.execute(()->{try{result.complete(snapshot.encode(binding,known,pages));}catch(Throwable failure){result.completeExceptionally(failure);}finally{residentExportPending.set(false);admission.release();}});dispatched=true;
        }catch(Throwable failure){result.completeExceptionally(failure instanceof ExecutionException?failure.getCause():failure);}finally{if(!dispatched){residentExportPending.set(false);admission.release();}}});
        }catch(RejectedExecutionException failure){residentExportPending.set(false);admission.release();result.completeExceptionally(failure);}return result.copy();
    }
    public szserver.persistence.WorldEventStore worldEvents(){return worldEvents;}
    public szserver.persistence.WorldEnvironment environment(){return environment;}
    public Map<String,Object> environmentView(){var state=environment.state();return Map.of("worldTime",state.worldTime(),"dayTime",state.dayTime(),"nativeWorldTime",environment.clockAnchor().nativeTicks(System.currentTimeMillis()),"nativeDayTicks",szserver.persistence.EnvironmentClock.NATIVE_DAY_TICKS,"timeSpeed",environment.clockSpeed(),"weather",state.weather().wireName(),"worldRevision",state.revision(),"eventSequence",state.eventSequence(),"serverTick",world.snapshot().tick());}
    public Map<String,Object> checkpointStatus(){return checkpointView;}
    public Map<String,Object> bootstrapStatus(){return bootstrapView;}
    public Map<String,Object> metrics(){Map<String,Object> view=new LinkedHashMap<>(Map.of("queue",worker.getQueue().size(),"rejected",rejected.get(),"committed",history.metrics().get("userRecords"),"maxCommandMs",maxCommandMs,"healthy",healthy(),"failure",failure,"itemDefinitions",items.size(),"historyBytes",history.metrics().get("cacheBytes"),"historyByteLimit",history.metrics().get("cacheByteLimit")));view.put("residentCapture",Map.of("publication",residentPublication,"pending",residentExportPending.get(),"sealed",sealedResidentCut!=null,"coordinated",residentCloseHook!=null));view.put("backendPages",backendPages.metrics());view.put("mapReads",mapReadMetrics());view.put("worldEvents",worldEvents.metrics());view.put("worldEditCellLimit",BlockEdit.LIMIT);view.put("historyArchive",history.metrics());view.put("recoveredRecords",recoveredRecords);view.put("importedRecords",importedRecords);view.put("sceneDefaults",sceneSeeds==null?Map.of("enabled",false):sceneSeeds.metrics());view.put("sceneDomain",sceneDomain.metrics());view.put("sceneRelationships",relationships.metrics());view.put("editorState",editorStates.metrics());view.put("deletedNativeState",deletedScenes.metrics());view.put("checkpoint",checkpointView);view.put("bootstrap",bootstrapView);view.put("checkpointIo",checkpoints.metrics());view.put("sceneMaintenance",sceneMaintenanceView);view.put("worldJournalMaintenance",worldJournalMaintenanceStatus());view.put("nativeMapArchive",nativeMapArchiveView);view.put("nativeMapCommit",nativeMapCommitStatus());view.put("nativeMapMaintenance",nativeMapMaintenanceStatus());view.put("derivedCaches",cacheContext.metrics());return Collections.unmodifiableMap(view);}
    @Override public void close()throws Exception{
        if(nativeCommitPolicy!=null){nativeCommitPolicy.shutdownNow();if(!nativeCommitPolicy.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("native_commit_policy_shutdown_timeout");}
        Exception failed=null;long checkpointDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(pendingCheckpoints.get()>0){if(System.nanoTime()>=checkpointDeadline)throw new IllegalStateException("checkpoint_shutdown_timeout");Thread.sleep(10);}
        if(!residentHistoryEnabled&&healthy()&&Boolean.TRUE.equals(world.metrics().get("running"))&&!world.snapshot().entities().isEmpty())try{checkpoint().get(60,TimeUnit.SECONDS);}catch(Exception error){failed=error;}
        try{Workers.stop(worker);}catch(Exception error){if(failed==null)failed=error;else failed.addSuppressed(error);}if(!worker.isTerminated()){if(failed!=null)throw failed;throw new IllegalStateException("command_worker_still_running");}inFlight.values().forEach(f->f.future.completeExceptionally(new IllegalStateException("server_stopped")));
        if(residentCloseHook!=null)try{if(healthy())sealedResidentCut=residentCutSnapshot();residentCloseHook.get().get(30,TimeUnit.SECONDS);}catch(Exception error){if(failed==null)failed=error;else failed.addSuppressed(error);}
        checkpointWorker.shutdown();if(!checkpointWorker.awaitTermination(60,TimeUnit.SECONDS))throw new IllegalStateException("checkpoint_worker_shutdown_timeout");
        try{Workers.stop(nativeCommitWorker);}catch(Exception error){if(failed==null)failed=error;else failed.addSuppressed(error);}if(!nativeCommitWorker.isTerminated())throw new IllegalStateException("native_commit_worker_still_running");
        for(AutoCloseable resource:Arrays.asList(nativeCommits,checkpoints,history,sceneDomain,editorStates,deletedScenes,environment,worldEvents))if(resource!=null)try{resource.close();}catch(Exception error){if(failed==null)failed=error;else failed.addSuppressed(error);}
        if(worker.isTerminated()){inFlight.clear();noops.clear();noopOrder.clear();inventories.clear();inventoryView=Map.of();editorStates.release();deletedScenes.release();relationships.release();}
        if(failed!=null)throw failed;
    }
}
