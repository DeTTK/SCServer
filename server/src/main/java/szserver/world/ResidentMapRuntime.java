package szserver.world;

import szserver.client.NativeResidentChunkCodec;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One native map owner shared by player residency and interactive mutations. */
public final class ResidentMapRuntime implements WorldLoop.OwnerService {
    private final Thread owner=Thread.currentThread();
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("resident_runtime_wrong_owner");}
    public record Settings(int chunks,long bytes,int playerRadius,int loadingPerTick,int concurrentLoads,long saveNanos,long idleNanos){
        public Settings {if(chunks<1||bytes<1||playerRadius<0||playerRadius>12||loadingPerTick<1||concurrentLoads<1||saveNanos<1||idleNanos<0)throw new IllegalArgumentException("resident_runtime_settings");}
    }
    private final ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> chunks;
    private final ResidentChunkSubscriptions<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> subscriptions;
    private final ResidentBlockOperations blocks;
    private final ResidentMapReads<NativeResidentChunkCodec.ReadSnapshot> reads;
    private final ResidentReplication replication=new ResidentReplication();
    private final SceneRegistry.Manifest sceneRegistry;
    private final NativeResidentChunkCodec codec;private int bulkFlights;
    public NativeResidentChunkCodec.PreparedTerrain prepareTerrain(NativeResidentChunkCodec.ReadSnapshot source,List<ResidentBlockOperations.Delta> edits,NativeResidentChunkCodec.PreparedScenes scene)throws Exception{return codec.prepareTerrain(source,edits,scene,sceneRegistry);}
    public SceneRegistry.Manifest sceneRegistry(){return Objects.requireNonNull(sceneRegistry,"resident_scene_registry_required");}
    public ResidentReplication replication(){return replication;}
    private final int readAdmissions;
    private final ResidentOperationLog history;
    private boolean stopping;
    private CompletableFuture<Void> shutdown;
    private record SceneId(UUID actor,UUID operation){}
    private record SceneFlight(CompletableFuture<Map<ResidentChunks.Key,Long>> result,long bytes,Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> plan,World.SceneMetadataPrepared metadata,ResidentCommandEnvelope.Prepared command){}
    private final Map<SceneId,SceneFlight> sceneFlights=new HashMap<>();private long sceneBytes;
    private String fatal="";
    private boolean canonicalCommands;
    public void canonicalCommands(){owner();if(history==null)throw new IllegalStateException("resident_history_not_configured");canonicalCommands=true;}
    /** Storage must be opened/validated on the bootstrap worker; codec registry must already be warm. */
    public ResidentMapRuntime(ResidentChunks.Storage storage,NativeResidentChunkCodec codec,Settings settings,Consumer<ResidentBlockOperations.Delta> validate,Consumer<ResidentBlockOperations.Change> publish){
        this(storage,codec,settings,validate,publish,null,0);
    }
    public ResidentMapRuntime(ResidentChunks.Storage storage,NativeResidentChunkCodec codec,Settings settings,Consumer<ResidentBlockOperations.Delta> validate,Consumer<ResidentBlockOperations.Change> publish,ResidentOperationLog history,long savedStateRevision){
        this(storage,codec,settings,validate,publish,history,savedStateRevision,null);
    }
    public ResidentMapRuntime(ResidentChunks.Storage storage,NativeResidentChunkCodec codec,Settings settings,Consumer<ResidentBlockOperations.Delta> validate,Consumer<ResidentBlockOperations.Change> publish,ResidentOperationLog history,long savedStateRevision,SceneRegistry.Manifest registry){
        this(storage,codec,settings,validate,publish,history,savedStateRevision,registry,history==null?ResidentChunks.State.NONE:history);
    }
    public ResidentMapRuntime(ResidentChunks.Storage storage,NativeResidentChunkCodec codec,Settings settings,Consumer<ResidentBlockOperations.Delta> validate,Consumer<ResidentBlockOperations.Change> publish,ResidentOperationLog history,long savedStateRevision,SceneRegistry.Manifest registry,ResidentChunks.State saveState){
        Objects.requireNonNull(storage);Objects.requireNonNull(codec);Objects.requireNonNull(settings);Objects.requireNonNull(validate);Objects.requireNonNull(publish);
        this.history=history;
        this.sceneRegistry=registry;
        this.codec=codec;
        if(registry!=null)codec.persistenceRegistry(registry);
        reads=new ResidentMapReads<>(128);readAdmissions=settings.loadingPerTick();
        var publishedCodec=new ResidentChunks.Codec<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>(){
            public NativeResidentChunkCodec.Chunk decode(byte[] bytes)throws Exception{var chunk=codec.decode(bytes);if(registry!=null)chunk.prepareSceneReads(registry);return chunk;}
            public long residentBytes(NativeResidentChunkCodec.Chunk chunk){return codec.residentBytes(chunk);}
            public NativeResidentChunkCodec.Snapshot freeze(NativeResidentChunkCodec.Chunk chunk)throws Exception{return codec.freeze(chunk);}
            public byte[] encode(NativeResidentChunkCodec.Snapshot snapshot)throws Exception{return codec.encode(snapshot);}
            public void activate(ResidentChunks.Key key,NativeResidentChunkCodec.Chunk chunk,long revision)throws Exception{codec.activate(key,chunk,revision);reads.publish(Map.of(key,new ResidentMapReads.Chunk<>(revision,chunk.readSnapshot())));replication.loaded(key,revision);}
            public void replace(ResidentChunks.Key key,NativeResidentChunkCodec.Chunk before,NativeResidentChunkCodec.Chunk after,long revision){codec.replace(key,before,after,revision);}
            public void release(NativeResidentChunkCodec.Chunk chunk){if(codec.active(chunk)){reads.unload(chunk.key());replication.unloaded(chunk.key());}codec.release(chunk);}
        };
        chunks=new ResidentChunks<>(storage,publishedCodec,settings.chunks(),settings.bytes(),settings.saveNanos(),settings.idleNanos(),System::nanoTime,Objects.requireNonNull(saveState),savedStateRevision);
        subscriptions=new ResidentChunkSubscriptions<>(chunks,settings.playerRadius(),settings.chunks(),settings.concurrentLoads(),settings.loadingPerTick(),TimeUnit.SECONDS.toNanos(1));
        blocks=new ResidentBlockOperations(chunks,codec,validate,change->{Map<ResidentChunks.Key,ResidentMapReads.Chunk<NativeResidentChunkCodec.ReadSnapshot>> snapshots=new LinkedHashMap<>();change.revisions().forEach((key,revision)->snapshots.put(key,new ResidentMapReads.Chunk<>(revision,codec.readSnapshot(key))));reads.publish(snapshots);replication.blocks(change);publish.accept(change);},change->{
            if(history==null)return ResidentBlockOperations.Commit.NONE;var command=change.command();String kind=change.changes().isEmpty()?"command":"block";var body=change.changes().isEmpty()?null:ResidentMapEvents.blocks(change);var reserved=history.prepare(change.actor(),command==null?UUID.randomUUID():command.operation(),kind,ResidentCommandEnvelope.wrap(body,command));return new ResidentBlockOperations.Commit(){public void committed(){reserved.commit();if(command!=null)command.committed(reserved.id());}public void close(){reserved.close();}};
        });
    }
    @Override public void tick(Map<UUID,World.Vec> players,long now){
        owner();chunks.tick();if(!stopping){subscriptions.players(players);subscriptions.tick(now);
            for(int i=0;i<readAdmissions;i++){var key=reads.poll();if(key==null)break;chunks.acquire(key).whenComplete((ticket,error)->{try{reads.complete(key,error);}finally{if(ticket!=null)ticket.close();}});}
            if(history!=null&&history.pendingBytes()>=32L*1024*1024&&!Boolean.TRUE.equals(chunks.metrics().get("savePending")))chunks.save();
        }
        // Seal the map only after admitted block operations have released their tickets.
        if(stopping&&shutdown!=null&&!shutdown.isDone()&&bulkFlights==0&&sceneFlights.isEmpty()&&((Number)blocks.metrics().get("pending")).intValue()==0){
            if(mapShutdown==null){reads.stop();mapShutdown=chunks.shutdown();}
            if(mapShutdown.isDone())mapShutdown.whenComplete((ignored,error)->{if(error==null)shutdown.complete(null);else shutdown.completeExceptionally(error);});
        }
    }
    private CompletableFuture<Void> mapShutdown;
    public CompletableFuture<ResidentBlockOperations.Receipt> edit(ResidentBlockOperations.Request request){owner();if(stopping||!fatal.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("resident_map_stopping_or_failed:"+fatal));if(request.command()!=null&&(history==null||!canonicalCommands))return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_configured"));return blocks.submit(request);}
    /** A worker prepares complete object frames; owner validates every source before replacing any. */
    public CompletableFuture<Map<ResidentChunks.Key,Long>> editScenes(Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> prepared,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish){
        return editScenes(new UUID(0,0),UUID.randomUUID(),prepared,validate,publish);
    }
    public CompletableFuture<Map<ResidentChunks.Key,Long>> editScenes(UUID actor,UUID operation,Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> prepared,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish){
        return editScenes(actor,operation,prepared,null,null,validate,publish);
    }
    /** Physical frames, global scene indexes and operation history enter one owner revision boundary. */
    public CompletableFuture<Map<ResidentChunks.Key,Long>> editScenes(UUID actor,UUID operation,Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> prepared,World world,World.SceneMetadataPrepared metadata,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish){
        return editScenes(actor,operation,prepared,world,metadata,validate,publish,null);
    }
    public CompletableFuture<Map<ResidentChunks.Key,Long>> editScenes(UUID actor,UUID operation,Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> prepared,World world,World.SceneMetadataPrepared metadata,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish,ResidentCommandEnvelope.Prepared command){
        owner();Objects.requireNonNull(actor);Objects.requireNonNull(operation);Objects.requireNonNull(validate);Objects.requireNonNull(publish);var plan=Map.copyOf(prepared);var id=new SceneId(actor,operation);SceneFlight existing=sceneFlights.get(id);
        if(existing!=null)return existing.plan().equals(plan)&&existing.metadata()==metadata&&existing.command()==command?existing.result().copy():CompletableFuture.failedFuture(new IllegalArgumentException("resident_scene_operation_reused"));
        if(command!=null&&(!canonicalCommands||history==null||command.event()!=0||!operation.equals(command.operation())))return CompletableFuture.failedFuture(new IllegalArgumentException("resident_command_context"));
        if(stopping||!fatal.isEmpty()||sceneFlights.size()>=16)return CompletableFuture.failedFuture(new RejectedExecutionException("resident_scene_busy_or_stopping"));
        if((metadata==null)!=(world==null)||metadata!=null&&(history==null||plan.isEmpty()||metadata.history()==null))return CompletableFuture.failedFuture(new IllegalArgumentException("resident_scene_metadata_context"));
        ResidentOperationLog.Payload worldPayload;
        try{if(metadata!=null)metadata.validate(world);worldPayload=metadata==null?null:ResidentWorldMetadata.capture(metadata.detached()).payload();}catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
        if(plan.isEmpty()){if(command!=null)recordCommand(actor,command);return CompletableFuture.completedFuture(Map.of());}
        long retained=(worldPayload==null?0:worldPayload.bytes())+(command==null?0:command.bytes());for(var entry:plan.entrySet()){if(!entry.getKey().equals(entry.getValue().key()))return CompletableFuture.failedFuture(new IllegalArgumentException("resident_scene_chunk_mismatch"));retained+=entry.getValue().retainedBytes();}
        if(retained>4L*SceneLimits.RAW_BYTES||sceneBytes+retained>4L*SceneLimits.RAW_BYTES)return CompletableFuture.failedFuture(new RejectedExecutionException("resident_scene_plan_byte_limit"));
        try{validate.run();}catch(Exception denied){return CompletableFuture.failedFuture(denied);}
        var result=new CompletableFuture<Map<ResidentChunks.Key,Long>>();var flight=new SceneFlight(result,retained,plan,metadata,command);sceneFlights.put(id,flight);sceneBytes+=retained;
        Map<ResidentChunks.Key,CompletableFuture<ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>.Ticket>> loading=new LinkedHashMap<>();
        for(var key:plan.keySet())loading.put(key,chunks.acquire(key));
        CompletableFuture.allOf(loading.values().toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
            boolean committed=false,coherent=false;ResidentOperationLog.Reservation reserved=null;
            try{
                if(error!=null)throw new CompletionException(error);validate.run();if(metadata!=null)metadata.validate(world);long growth=0;
                for(var entry:plan.entrySet()){var ticket=loading.get(entry.getKey()).join();ticket.chunk().validateScenes(entry.getValue());if(ticket.revision()==Long.MAX_VALUE)throw new IllegalStateException("resident_scene_revision_exhausted");growth=Math.addExact(growth,entry.getValue().growth());}
                chunks.reserveBytes(growth);
                Map<ResidentChunks.Key,Long> revisions=new LinkedHashMap<>();for(var entry:loading.entrySet())revisions.put(entry.getKey(),entry.getValue().join().revision()+1);
                if(history!=null)reserved=history.prepare(actor,operation,"scene",ResidentCommandEnvelope.wrap(ResidentMapEvents.scenes(plan,revisions,metadata==null?null:metadata.history()),command),worldPayload);
                // All fallible preparation/admission is above this owner boundary.
                committed=true;for(var entry:plan.entrySet())loading.get(entry.getKey()).join().chunk().applyScenes(entry.getValue());
                for(var entry:loading.entrySet())if(entry.getValue().join().changed()!=revisions.get(entry.getKey()))throw new IllegalStateException("resident_scene_revision_changed");if(metadata!=null)world.publishSceneMetadata(metadata);if(reserved!=null){reserved.commit();if(command!=null)command.committed(reserved.id());}coherent=true;
                Map<ResidentChunks.Key,ResidentMapReads.Chunk<NativeResidentChunkCodec.ReadSnapshot>> snapshots=new LinkedHashMap<>();
                for(var entry:loading.entrySet()){var ticket=entry.getValue().join();var snapshot=ticket.chunk().readSnapshot();chunks.recount(ticket);snapshots.put(entry.getKey(),new ResidentMapReads.Chunk<>(revisions.get(entry.getKey()),snapshot));}
                reads.publish(snapshots);for(var changed:revisions.entrySet())replication.refresh(changed.getKey(),changed.getValue());Map<ResidentChunks.Key,Long> receipt=Map.copyOf(revisions);publish.accept(receipt);sceneFinished(id,flight);result.complete(receipt);
            }catch(Exception failure){if(committed){fatal="scene_post_commit_failed";if(!coherent)chunks.poison(fatal);}sceneFinished(id,flight);result.completeExceptionally(failure);}
            finally{if(reserved!=null)reserved.close();sceneFinished(id,flight);for(var future:loading.values())if(!future.isCompletedExceptionally()&&!future.isCancelled())future.join().close();}
        });return result.copy();
    }
    private void sceneFinished(SceneId id,SceneFlight flight){if(sceneFlights.remove(id,flight))sceneBytes-=flight.bytes();}
    /** Read callers never receive writable native tickets and cannot bypass revision publication. */
    public CompletableFuture<NativeResidentChunkCodec.ReadSnapshot> load(ResidentChunks.Key key){owner();if(stopping)return CompletableFuture.failedFuture(new IllegalStateException("resident_map_stopping"));return chunks.acquire(key).thenApply(ticket->{try{return ticket.chunk().readSnapshot();}finally{ticket.close();}});}
    public CompletableFuture<Void> save(){owner();return chunks.save();}
    /** Worker-built chunks swap at one owner boundary; no voxel loops or compression on the tick. */
    public CompletableFuture<Void> editPrepared(UUID actor,Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedTerrain> prepared,World world,World.SceneMetadataPrepared metadata,ResidentCommandEnvelope.Prepared command){
        owner();if(stopping||!fatal.isEmpty()||history==null||!canonicalCommands||command.event()!=0||bulkFlights!=0)return CompletableFuture.failedFuture(new IllegalStateException("resident_bulk_unavailable"));
        var plan=Map.copyOf(prepared);var result=new CompletableFuture<Void>();var loading=new LinkedHashMap<ResidentChunks.Key,CompletableFuture<ResidentChunks<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot>.Ticket>>();bulkFlights++;
        for(var key:plan.keySet())loading.put(key,chunks.acquire(key));
        CompletableFuture.allOf(loading.values().toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
            ResidentOperationLog.Reservation reservation=null;boolean committed=false,coherent=false;
            try{
                if(error!=null)throw new CompletionException(error);if(metadata!=null)metadata.validate(world);long growth=0;
                for(var row:plan.entrySet()){var ticket=loading.get(row.getKey()).join();row.getValue().validate(ticket.chunk());if(ticket.revision()==Long.MAX_VALUE)throw new IllegalStateException("resident_chunk_revision_exhausted");growth+=Math.max(0,row.getValue().bytes()-codec.residentBytes(ticket.chunk()));}
                chunks.reserveBytes(growth);var worldPayload=metadata==null?null:ResidentWorldMetadata.capture(metadata.detached()).payload();reservation=history.prepare(actor,command.operation(),"bulk",ResidentCommandEnvelope.wrap(null,command),worldPayload);
                Map<ResidentChunks.Key,ResidentMapReads.Chunk<NativeResidentChunkCodec.ReadSnapshot>> published=new LinkedHashMap<>();committed=true;
                for(var row:plan.entrySet()){var ticket=loading.get(row.getKey()).join();long revision=ticket.replace(row.getValue().claim(ticket.chunk()));published.put(row.getKey(),new ResidentMapReads.Chunk<>(revision,row.getValue().snapshot()));}
                if(metadata!=null)world.publishSceneMetadata(metadata);reservation.commit();command.committed(reservation.id());coherent=true;reads.publish(published);for(var row:published.entrySet())replication.refresh(row.getKey(),row.getValue().revision());result.complete(null);
            }catch(Throwable failure){if(committed){fatal="bulk_post_commit_failed";if(!coherent)chunks.poison(fatal);}result.completeExceptionally(failure);}finally{if(reservation!=null)reservation.close();for(var ticket:loading.values())if(!ticket.isCompletedExceptionally()&&!ticket.isCancelled())ticket.join().close();bulkFlights--;}
        });return result.copy();
    }
    public void captureGate(ResidentChunks.CaptureGate gate){owner();chunks.captureGate(gate);}
    public void poison(String reason){owner();fatal=Objects.requireNonNull(reason);chunks.poison(reason);}
    /** Logical editor changes do not manufacture a dirty native chunk or wait for a disk journal. */
    public long editMetadata(UUID actor,UUID operation,World world,World.SceneMetadataPrepared metadata,Runnable validate){
        return editMetadata(actor,operation,world,metadata,validate,null);
    }
    public long editMetadata(UUID actor,UUID operation,World world,World.SceneMetadataPrepared metadata,Runnable validate,ResidentCommandEnvelope.Prepared command){
        owner();Objects.requireNonNull(world);Objects.requireNonNull(metadata);Objects.requireNonNull(validate);
        if(stopping||!fatal.isEmpty()||history==null||metadata.history()==null)throw new IllegalStateException("resident_metadata_unavailable");
        if(command!=null&&(!canonicalCommands||command.event()!=0||!operation.equals(command.operation())))throw new IllegalArgumentException("resident_command_context");
        validate.run();metadata.validate(world);var payload=ResidentWorldMetadata.capture(metadata.detached()).payload();var delta=metadata.history();
        boolean published=false,coherent=false;
        try(var reservation=history.prepare(actor,operation,"scene_meta",ResidentCommandEnvelope.wrap(new ResidentOperationLog.Payload(delta.bytes(),delta::encode),command),payload)){
            world.publishSceneMetadata(metadata);published=true;reservation.commit();if(command!=null)command.committed(reservation.id());coherent=true;return reservation.id();
        }catch(RuntimeException failure){if(published&&!coherent){fatal="metadata_post_commit_failed";chunks.poison(fatal);}throw failure;}
    }
    public long recordCommand(UUID actor,ResidentCommandEnvelope.Prepared command){
        owner();Objects.requireNonNull(command);if(stopping||!fatal.isEmpty()||history==null||!canonicalCommands||command.event()!=0)throw new IllegalStateException("resident_command_unavailable");
        try(var reservation=history.prepare(actor,command.operation(),"command",ResidentCommandEnvelope.wrap(null,command))){reservation.commit();command.committed(reservation.id());return reservation.id();}
    }
    public CompletableFuture<Long> undoMetadata(long event,UUID actor,UUID operation,World world,Runnable validate){
        owner();Objects.requireNonNull(world);Objects.requireNonNull(validate);if(history==null)return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_configured"));
        try{validate.run();var read=history.read(event);return chunks.readState(files->{var original=read.read(files);if(!original.kind().equals("scene_meta"))throw new IllegalArgumentException("resident_event_kind");return ResidentMetadataChanges.decode(ResidentCommandEnvelope.nativeEvent(original).payload());}).thenCompose(delta->{
            validate.run();var basis=world.sceneMetadataView();return chunks.readState(files->delta.inverse(basis)).thenApply(inverse->editMetadata(actor,operation,world,inverse,validate));
        });}catch(Exception denied){return CompletableFuture.failedFuture(denied);}
    }
    public long latestEdit(UUID actor){owner();if(history==null)throw new IllegalStateException("resident_history_not_configured");return history.latest(actor);}
    public CompletableFuture<ResidentOperationLog.Stored> history(long event){owner();if(history==null)return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_configured"));return chunks.readState(history.read(event));}
    /** Bounded read-only persistence inspection, serialized with the map storage worker. */
    public <T> CompletableFuture<T> inspectState(ResidentChunks.StateRead<T> read){owner();return chunks.readState(read);}
    /** Prepare the inverse off owner; current reach/permissions and native CAS are checked again at apply. */
    public CompletableFuture<ResidentBlockOperations.Receipt> undoBlock(long event,UUID actor,long sequence,Supplier<World.Vec> eye,Runnable validate){
        owner();Objects.requireNonNull(eye);Objects.requireNonNull(validate);if(history==null)return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_configured"));
        try{validate.run();var read=history.read(event);return chunks.readState(files->{var original=ResidentMapEvents.blocks(read.read(files));return original.changes().stream().map(delta->new ResidentBlockOperations.Delta(delta.position(),delta.packed(),delta.expectedPacked(),delta.tile(),delta.expectedTile())).toList();}).thenCompose(inverse->{validate.run();return edit(new ResidentBlockOperations.Request(actor,sequence,eye.get(),inverse));});}
        catch(Exception denied){return CompletableFuture.failedFuture(denied);}
    }
    /** Only affected UUIDs are compared/restored; unrelated objects in those chunks remain untouched. */
    public CompletableFuture<Map<ResidentChunks.Key,Long>> undoScenes(long event,UUID actor,UUID operation,SceneRegistry.Manifest registry,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish){
        return undoScenes(event,actor,operation,registry,null,validate,publish);
    }
    public CompletableFuture<Map<ResidentChunks.Key,Long>> undoScenes(long event,UUID actor,UUID operation,SceneRegistry.Manifest registry,World world,Runnable validate,Consumer<Map<ResidentChunks.Key,Long>> publish){
        owner();Objects.requireNonNull(registry);Objects.requireNonNull(validate);Objects.requireNonNull(publish);if(history==null)return CompletableFuture.failedFuture(new IllegalStateException("resident_history_not_configured"));
        record Original(Map<ResidentChunks.Key,ResidentMapEvents.ObjectChanges> changes,ResidentMetadataChanges metadata){}
        record Inverse(Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> plan,World.SceneMetadataPrepared metadata){}
        try{validate.run();var read=history.read(event);return chunks.readState(files->{var scene=ResidentMapEvents.scenes(read.read(files));return new Original(ResidentMapEvents.objects(scene,registry),scene.metadata().length==0?null:ResidentMetadataChanges.decode(scene.metadata()));}).thenCompose(original->{
            if(original.metadata()!=null&&world==null)return CompletableFuture.failedFuture(new IllegalArgumentException("resident_scene_metadata_world_required"));
            if(original.metadata()==null&&world!=null)return CompletableFuture.failedFuture(new IllegalArgumentException("resident_scene_metadata_history_missing"));var changes=original.changes();
            validate.run();Map<ResidentChunks.Key,CompletableFuture<NativeResidentChunkCodec.ReadSnapshot>> loaded=new LinkedHashMap<>();for(var key:changes.keySet())loaded.put(key,load(key));
            return CompletableFuture.allOf(loaded.values().toArray(CompletableFuture[]::new)).thenCompose(ignored->{
                Map<ResidentChunks.Key,NativeResidentChunkCodec.ReadSnapshot> snapshots=new LinkedHashMap<>();loaded.forEach((key,future)->snapshots.put(key,future.join()));
                var metadataBasis=world==null?null:world.sceneMetadataView();
                return chunks.readState(files->{Map<ResidentChunks.Key,NativeResidentChunkCodec.PreparedScenes> plan=new LinkedHashMap<>();for(var entry:changes.entrySet()){
                    var snapshot=snapshots.get(entry.getKey());var current=ScenePayload.index(ScenePayload.parse(snapshot.sceneRecord()).channels(),registry);for(var expected:entry.getValue().after().entrySet())if(!Objects.equals(current.get(expected.getKey()),expected.getValue()))throw new IllegalArgumentException("resident_scene_undo_compare_failed");
                    plan.put(entry.getKey(),NativeResidentChunkCodec.prepareScenes(snapshot,entry.getValue().before(),registry));
                }return new Inverse(Map.copyOf(plan),original.metadata()==null?null:original.metadata().inverse(metadataBasis));}).thenCompose(inverse->editScenes(actor,operation,inverse.plan(),world,inverse.metadata(),validate,publish));
            });
        });}catch(Exception denied){return CompletableFuture.failedFuture(denied);}
    }
    public ResidentMapReads<NativeResidentChunkCodec.ReadSnapshot> reads(){return reads;}
    public boolean ready(UUID player){owner();return subscriptions.ready(player);}
    @Override public CompletableFuture<Void> shutdown(){owner();if(shutdown!=null)return shutdown.copy();stopping=true;subscriptions.stop();shutdown=new CompletableFuture<>();tick(Map.of(),System.nanoTime());return shutdown.copy();}
    @Override public Map<String,Object> metrics(){owner();return Map.of("chunks",chunks.metrics(),"subscriptions",subscriptions.metrics(),"blocks",blocks.metrics(),"reads",reads.metrics(),"history",history==null?Map.of():history.metrics(),"scenePending",!sceneFlights.isEmpty(),"sceneFlights",sceneFlights.size(),"sceneBytes",sceneBytes,"failure",fatal,"stopping",stopping);}
    @Override public boolean healthy(){owner();return fatal.isEmpty()&&chunks.healthy()&&Boolean.TRUE.equals(blocks.metrics().get("healthy"));}
}
