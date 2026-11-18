package szserver.commands;

import szserver.client.SceneConfigurationPackets;
import szserver.util.*;
import szserver.world.*;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded scene lookup with global UUID fallback. Disk, NBT and RPC construction stay off gameplay owners. */
public final class SceneAccess implements AutoCloseable {
    private final WorldLoop world;private final CommandService commands;
    private final ThreadPoolExecutor worker=Workers.pool("scene-configuration",2,32);
    private final AtomicLong reads=new AtomicLong(),writes=new AtomicLong(),rejected=new AtomicLong();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final SceneWriteQueue<JsonObject> writeQueue=new SceneWriteQueue<>();
    public SceneAccess(WorldLoop world,CommandService commands){this.world=world;this.commands=commands;}
    private CompletableFuture<JsonObject> submit(JsonObject request){var runtime=world.residentMap();return runtime==null?commands.submit(request):commands.submitResidentScenes(request,runtime,new UUID(0,0));}
    private World.SceneLocation locate(UUID id,List<Long> chunks)throws Exception{
        if(chunks.isEmpty()||chunks.size()>289)throw new IllegalArgumentException("scene_configuration_visible_chunks");List<Long> scope=List.copyOf(new LinkedHashSet<>(chunks));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_configuration_load_timeout");World.SceneLookup result=world.submit(w->w.findScene(id,scope)).get(remaining,TimeUnit.NANOSECONDS);if(result.location()!=null)return result.location();if(!result.pending())throw new IllegalArgumentException("scene_object_not_found");Thread.sleep(10);}
    }
    private World.SceneLocation locateEdit(UUID id,List<Long> chunks)throws Exception{
        if(chunks.isEmpty()||chunks.size()>289)throw new IllegalArgumentException("scene_configuration_visible_chunks");List<Long> scope=List.copyOf(new LinkedHashSet<>(chunks));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_configuration_load_timeout");World.SceneEditLookup found=world.submit(w->w.findSceneEdits(Set.of(id),Set.of(),scope)).get(remaining,TimeUnit.NANOSECONDS);World.SceneLocation object=found.objects().get(id);if(object!=null){if(object.entry()==null)throw new IllegalArgumentException("scene_object_not_found");return object;}if(!found.pending())return null;Thread.sleep(10);}
    }
    public CompletableFuture<byte[]> configuration(SceneConfigurationPackets.Request request,List<Long> chunks){
        CompletableFuture<byte[]> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));List<Long> scope=List.copyOf(chunks);try{worker.execute(()->{try{World.SceneLocation object=locate(request.identity(),scope);String behavior=world.submit(w->w.sceneBehaviour(object.entry().object().asset())).get(5,TimeUnit.SECONDS);byte[] payload=szserver.client.SceneSpecialConfiguration.configuration(object.entry().object(),behavior);result.complete(SceneConfigurationPackets.response(request.sequence(),payload));reads.incrementAndGet();}catch(Exception error){result.completeExceptionally(error);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();result.completeExceptionally(full);}return result;
    }
    public CompletableFuture<JsonObject> save(SceneConfigurationPackets.Save request,List<Long> chunks){
        CompletableFuture<JsonObject> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));List<Long> scope=List.copyOf(chunks);try{worker.execute(()->{try{byte[] payload=request.compressed()?SceneConfigurationPackets.compressedSaveNbt(request.payload()):request.payload();JsonObject retained=commands.nativeEditorState(request.identity());World.SceneLocation object=retained==null?locateEdit(request.identity(),scope):null;if(retained!=null&&SceneEditorStates.deleted(retained))throw new IllegalArgumentException("scene_object_not_found");if(retained==null&&object==null&&request.compressed())throw new IllegalArgumentException("scene_object_not_found");String asset=retained!=null?retained.get("asset").getAsString():object==null?"":object.entry().object().asset();String behavior=world.submit(w->w.sceneBehaviour(asset)).get(5,TimeUnit.SECONDS);var parts=!request.compressed()&&(payload.length==0||retained==null&&object==null||SceneEditorStates.deferFullSave(retained))?SceneConfigurationPackets.retainedParts(payload):SceneConfigurationPackets.parts(payload,!request.compressed(),behavior);JsonObject command=Json.tree(Map.of("commandId",UUID.randomUUID().toString(),"type","scene_config","objectId",request.identity().toString(),"chunk",Map.of("x",object==null?0:object.x(),"z",object==null?0:object.z())));command.addProperty("fullSave",!request.compressed());command.addProperty("configurationBehavior",behavior);command.add("configuration",parts);submit(command).whenComplete((value,error)->{if(error!=null)result.completeExceptionally(error);else{writes.incrementAndGet();result.complete(value);}});}catch(Exception error){result.completeExceptionally(error);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();result.completeExceptionally(full);}return result;
    }
    public CompletableFuture<JsonObject> queuedSave(UUID writer,SceneConfigurationPackets.Save request,List<Long> chunks){List<Long> scope=List.copyOf(chunks);return writeQueue.submit(writer,request.payload().length,()->save(request,scope));}
    public CompletableFuture<JsonObject> queuedEdit(UUID writer,String kind,byte[] body,List<Long> chunks){
        if(body.length>szserver.client.SceneEditPackets.MAX_BODY)return CompletableFuture.failedFuture(new IllegalArgumentException("scene_editor_packet_size"));byte[] copy=body.clone();List<Long> scope=List.copyOf(chunks);
        if(kind.equals("delete_request"))try{
            UUID selection=szserver.client.SceneEditPackets.deletionSelection(copy);
            if(selection!=null)return writeQueue.submitBatch(writer,copy.length,List.of(selection,scope),copy,messages->{
                try{return edit("delete_batch",szserver.client.SceneEditPackets.deletionBatch(messages),scope);}catch(Exception error){return CompletableFuture.failedFuture(error);}
            });
        }catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
        if(kind.equals("batch"))try{
            Set<UUID> selection=szserver.client.SceneEditPackets.updateSelection(copy);
            if(!selection.isEmpty())return writeQueue.submitBatch(writer,copy.length,List.of("inspector",selection,scope),copy,messages->edit("batch",messages.get(messages.size()-1),scope));
        }catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
        return writeQueue.submit(writer,copy.length,()->edit(kind,copy,scope));
    }
    private CompletableFuture<JsonObject> edit(String kind,byte[] body,List<Long> scope){
        CompletableFuture<JsonObject> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));try{worker.execute(()->{try{JsonObject request=Json.tree(Map.of("commandId",UUID.randomUUID().toString(),"type","scene_native","packetKind",kind,"visibleChunks",scope));request.add("nativePacket",szserver.client.SceneEditPackets.parts(body));submit(request).whenComplete((value,error)->{if(error!=null)result.completeExceptionally(error);else{writes.incrementAndGet();result.complete(value);}});}catch(Exception error){result.completeExceptionally(error);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();result.completeExceptionally(full);}return result;
    }
    public Map<String,Object> metrics(){return Map.of("reads",reads.get(),"writes",writes.get(),"rejected",rejected.get(),"queued",worker.getQueue().size(),"writeQueue",writeQueue.metrics());}
    @Override public void close()throws InterruptedException{
        // Full concurrent Delete selections may take longer than ten seconds.
        // Do not stop the executor underneath an admitted queue that is still draining.
        if(!writeQueue.drain(60000))throw new IllegalStateException("scene_write_shutdown_timeout");
        try{Workers.stop(worker);}finally{pending.forEach(future->future.completeExceptionally(new IllegalStateException("scene_configuration_stopped")));}
    }
}
