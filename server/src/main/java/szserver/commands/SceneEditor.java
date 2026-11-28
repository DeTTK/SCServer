package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import szserver.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Administrative scene edits with bounded source-copy collapse and exact placement undo. */
final class SceneEditor {
    private final WorldLoop world;private final CommandHistory history;private final SceneSeeds seeds;private final SceneRelationships relationships;private final SceneEditorStates states;private final SceneDeletedStates deleted;
    SceneEditor(WorldLoop world,CommandHistory history,SceneSeeds seeds,SceneRelationships relationships,SceneEditorStates states,SceneDeletedStates deleted){this.world=world;this.history=history;this.seeds=seeds;this.relationships=relationships;this.states=states;this.deleted=deleted;}
    static boolean handles(String type){return Set.of("scene_move","scene_clone","scene_remove","scene_config","scene_culling","scene_culling_cleanup","scene_native","scene_undo").contains(type);}
    private static ScenePayload.Entry current(World.SceneChunk chunk,UUID id){return chunk.overlay().scenes().containsKey(id)?chunk.overlay().scenes().get(id):chunk.base().get(id);}
    private World.SceneChunk chunk(int x,int z)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_terrain_load_timeout");try{return world.submit(w->w.sceneChunk(x,z)).get(remaining,TimeUnit.NANOSECONDS);}catch(java.util.concurrent.ExecutionException failed){if(!(failed.getCause() instanceof IllegalArgumentException)||!"terrain_not_ready_retry".equals(failed.getCause().getMessage()))throw failed;Thread.sleep(10);}}}
    private String registry()throws Exception{return world.submit(World::sceneRegistryDigest).get(5,TimeUnit.SECONDS);}
    private World.SceneLocation configurationLocation(UUID id,int x,int z)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_configuration_load_timeout");var lookup=world.submit(w->w.findSceneEdits(Set.of(id),Set.of(),List.of(BlockOverlays.key(x,z)))).get(remaining,TimeUnit.NANOSECONDS);var location=lookup.objects().get(id);if(location!=null)return location;if(!lookup.pending())return null;Thread.sleep(10);}
    }
    JsonObject prepare(String type,JsonObject request,JsonObject event)throws Exception{
        String digest=registry();if(digest.isEmpty())throw new IllegalArgumentException("scene_edit_disabled");List<SceneEdits.Change> changes;Map<UUID,ScenePlacementPlan.Projection> projections=new HashMap<>();
        if(type.equals("scene_culling_cleanup")){
            var plan=SceneCullingCleanup.prepare(request,event,world,this);event.addProperty("sceneRegistry",digest);
            if(!plan.changes().isEmpty())save(event,plan.changes(),plan.projections());
            SceneCullingRetained.prepare(plan,event,world,states,deleted);
            if(world.submit(World::portalEpoch).get(5,TimeUnit.SECONDS)!=plan.epoch())throw new IllegalArgumentException("scene_culling_cleanup_stale");
            return event;
        }
        if(type.equals("scene_culling")){
            boolean replace=request.has("volumeId");UUID volume=null;
            if(replace&&!request.get("volumeId").isJsonNull()){String value=Json.text(request,"volumeId",36);volume=UUID.fromString(value);if(!volume.toString().equals(value))throw new IllegalArgumentException("scene_culling_volume_identity");}
            Boolean ignored=null;if(request.has("ignore")){var value=request.get("ignore");if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("scene_culling_ignore_boolean");ignored=value.getAsBoolean();}
            if(!replace&&ignored==null)throw new IllegalArgumentException("scene_culling_empty_change");
            UUID id=UUID.fromString(Json.text(request,"objectId",36));if(states.current(id)!=null)throw new IllegalArgumentException("scene_culling_pending_native");
            var location=configurationLocation(id,0,0);if(location==null||location.entry()==null)throw new IllegalArgumentException("scene_object_not_found");
            ScenePayload.Entry before=location.entry();SceneObject object=before.object();
            String behavior=world.submit(w->w.sceneBehaviour(object.asset())).get(5,TimeUnit.SECONDS);
            SceneObject changed=szserver.client.SceneCulling.apply(object,behavior,replace,volume,ignored);event.addProperty("objectId",id.toString());event.addProperty("sceneRegistry",digest);
            if(changed.equals(object)&&before.occurrences().size()==1){event.addProperty("status","unchanged");return event;}
            var behaviors=ScenePlacementPlan.behaviours(world);UUID targetVolume=volume;Boolean targetIgnore=ignored;var projection=new ScenePlacementPlan.Projection(true,o->szserver.client.SceneCulling.apply(o.object(),behaviors.apply(o.object().asset()),replace,targetVolume,targetIgnore));
            var plan=List.of(new SceneEdits.Change(location.x(),location.z(),id,before,new ScenePayload.Entry(before.container(),changed)));save(event,plan,Map.of(id,projection));return event;
        }
        if(type.equals("scene_config")){
            UUID id=UUID.fromString(Json.text(request,"objectId",36));boolean full=request.has("fullSave")&&request.get("fullSave").getAsBoolean();JsonObject pending=states.current(id);byte[] body=full?szserver.client.SceneConfigurationPackets.retainedBody(request):szserver.client.SceneConfigurationPackets.parts(request);event.addProperty("objectId",id.toString());event.addProperty("sceneRegistry",digest);
            if(pending!=null){if(full&&body.length>0&&!SceneEditorStates.deferFullSave(pending)){String behavior=world.submit(w->w.sceneBehaviour(pending.get("asset").getAsString())).get(5,TimeUnit.SECONDS);szserver.client.SceneAttributes.validateFullSave(body,behavior);}states.configuration(event,id,body,full);if(!event.has("editorStateChanges"))event.addProperty("status","unchanged");return event;}
            JsonObject scope=Json.object(request,"chunk");var location=configurationLocation(id,Json.integer(scope,"x",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK),Json.integer(scope,"z",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK));
            if(location==null){if(!full||deleted.current(id)!=null)throw new IllegalArgumentException("scene_object_not_found");states.orphanConfiguration(event,id,body);return event;}
            ScenePayload.Entry before=location.entry();if(before==null)throw new IllegalArgumentException("scene_object_not_found");SceneObject object=before.object();
            if(full&&body.length==0){event.addProperty("status","unchanged");return event;}
            var behaviors=ScenePlacementPlan.behaviours(world);String behavior=behaviors.apply(object.asset());SceneObject changed=full?szserver.client.SceneAttributes.configured(object,body,behavior):szserver.client.SceneSpecialConfiguration.configured(object,body,behavior);if(changed.equals(object)&&before.occurrences().size()==1){event.addProperty("status","unchanged");return event;}var projection=new ScenePlacementPlan.Projection(false,o->{SceneObject source=o.container()?o.object().shifted(id,0,0,0):o.object();String memberBehavior=behaviors.apply(source.asset());SceneObject configured=full?szserver.client.SceneAttributes.configured(source,body,memberBehavior):szserver.client.SceneSpecialConfiguration.configured(source,body,memberBehavior);return o.container()?configured:szserver.client.SceneInspectorProjection.position(configured,object.x(),object.y(),object.z());});var plan=List.of(new SceneEdits.Change(location.x(),location.z(),id,before,new ScenePayload.Entry(before.container(),changed)));save(event,plan,Map.of(id,projection));return event;
        }
        if(type.equals("scene_native")){
            changes=new NativeSceneEditor(world,seeds,relationships,states,deleted,projections).prepare(request,event);if(changes.isEmpty()){if(event.has("sceneRelationships")||event.has("editorStateChanges")||event.has("sceneNativeAssetRequests"))event.addProperty("sceneRegistry",digest);return event;}
        }else if(type.equals("scene_undo")){
            String target=Json.text(request,"target",64);JsonObject original=history.get(target);if(original==null||!Set.of("scene_move","scene_clone","scene_remove","scene_config","scene_culling","scene_culling_cleanup","scene_native").contains(Json.text(original,"type",32)))throw new IllegalArgumentException("scene_undo_not_found");if(history.undone(target))throw new IllegalArgumentException("edit_already_undone");if(!digest.equals(Json.text(original,"sceneRegistry",64)))throw new IllegalArgumentException("scene_edit_registry_changed");changes=original.has("sceneChanges")?SceneEdits.read(original).stream().map(SceneEdits.Change::reverse).toList():List.of();relationships.reverse(original,event);states.reverse(original,event);event.addProperty("target",target);
        }else{
            JsonObject source=Json.object(request,"chunk");int x=Json.integer(source,"x",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK),z=Json.integer(source,"z",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK);UUID id=UUID.fromString(Json.text(request,"objectId",36));ScenePayload.Entry before=current(chunk(x,z),id);if(before==null)throw new IllegalArgumentException("scene_object_not_found");SceneObject object=before.object();
            if(type.equals("scene_remove"))changes=List.of(new SceneEdits.Change(x,z,id,before,null));
            else{
                JsonObject offset=Json.object(request,"offset");double dx=Json.number(offset,"x",-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET),dy=Json.number(offset,"y",-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET),dz=Json.number(offset,"z",-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET);UUID identity=type.equals("scene_clone")?UUID.randomUUID():id;SceneObject moved=object.shifted(identity,dx,dy,dz);if(!szserver.world.MapBounds.scene(moved.x(),moved.y(),moved.z()))throw new IllegalArgumentException("scene_edit_position");int tx=(int)Math.floor(moved.x()/16),tz=(int)Math.floor(moved.z()/16);ScenePayload.Entry after=new ScenePayload.Entry(before.container(),moved);event.addProperty("objectId",identity.toString());
                if(type.equals("scene_clone")){if(current(chunk(tx,tz),identity)!=null)throw new IllegalArgumentException("scene_object_exists");changes=List.of(new SceneEdits.Change(tx,tz,identity,null,after));}
                else if(tx==x&&tz==z){if(after.equals(before))throw new IllegalArgumentException("scene_edit_no_change");changes=List.of(new SceneEdits.Change(x,z,id,before,after));}
                else{changes=List.of(new SceneEdits.Change(x,z,id,before,null),new SceneEdits.Change(tx,tz,id,null,after));}
            }
        }
        event.addProperty("sceneRegistry",digest);if(!changes.isEmpty()){if(type.equals("scene_undo")){SceneEdits.save(event,changes);SceneEdits.read(event);validate(changes);}else save(event,changes,projections);}return event;
    }
    List<SceneEdits.Change> expand(JsonObject event,List<SceneEdits.Change> changes)throws Exception{return ScenePlacementPlan.expand(changes,event,world,this);}
    private void save(JsonObject event,List<SceneEdits.Change> requested,Map<UUID,ScenePlacementPlan.Projection> projections)throws Exception{var changes=ScenePlacementPlan.expand(requested,event,world,this,projections);if(changes.isEmpty()){event.addProperty("status","unchanged");return;}SceneEdits.save(event,changes);SceneEdits.read(event);validate(changes);}
    void validate(List<SceneEdits.Change> changes)throws Exception{validate(changes,java.util.function.UnaryOperator.identity());}
    /** Mixed worldedit supplies its projected block/tile overlay for combined frame validation. */
    void validate(List<SceneEdits.Change> changes,java.util.function.UnaryOperator<World.SceneChunk> projection)throws Exception{
        int additions=0;Map<Long,List<SceneEdits.Change>> groups=new LinkedHashMap<>();for(SceneEdits.Change change:changes)groups.computeIfAbsent(BlockOverlays.key(change.x(),change.z()),key->new ArrayList<>()).add(change);
        var coordinates=new ArrayList<>(groups.keySet());
        for(int start=0;start<coordinates.size();start+=16){var part=coordinates.subList(start,Math.min(start+16,coordinates.size()));Map<Long,World.SceneChunk> chunks=chunks(new LinkedHashSet<>(part));
        for(long key:part){var group=groups.get(key);
            SceneEdits.Change first=group.get(0);World.SceneChunk chunk=chunks.get(BlockOverlays.key(first.x(),first.z()));Map<UUID,ScenePayload.Entry> overlay=new HashMap<>(chunk.overlay().scenes());for(SceneEdits.Change change:group){if(!Objects.equals(current(chunk,change.id()),change.before()))throw new IllegalArgumentException("scene_edit_conflicts_with_later_edit");if(!overlay.containsKey(change.id()))additions++;overlay.put(change.id(),change.after());if(change.after()!=null&&change.after().hasContainer()&&!chunk.rawContainer())throw new IllegalArgumentException("scene_nbt_container_edit_unsupported");}
            // Byte copies, compression, full-frame and expansion validation happen here on the command worker.
            World.SceneChunk blockProjection=projection.apply(chunk);BlockOverlays.Snapshot projected=new BlockOverlays.Snapshot(chunk.overlay().revision(),blockProjection.overlay().cells(),blockProjection.overlay().tiles(),Collections.unmodifiableMap(overlay));byte[] record=TerrainProjection.project(chunk.source().record(),projected);ScenePayload.parse(record);if(GamePackets.terrainChunk(record).length>240000)throw new IllegalArgumentException("terrain_frame_limit");
        }
        }final int count=additions;world.submit(w->{w.overlayVersions().validateSceneCapacity(count);return true;}).get(5,TimeUnit.SECONDS);
    }
    Map<Long,World.SceneChunk> chunks(Set<Long> keys)throws Exception{
        Map<Long,World.SceneChunk> found=new HashMap<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(found.size()<keys.size()){List<Long> pending=keys.stream().filter(key->!found.containsKey(key)).limit(16).toList();long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_terrain_load_timeout");Map<Long,World.SceneChunk> ready=world.submit(w->{Map<Long,World.SceneChunk> result=new HashMap<>();for(long key:pending)try{result.put(key,w.sceneChunk((int)(key>>32),(int)key));}catch(IllegalArgumentException error){if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;}return result;}).get(remaining,TimeUnit.NANOSECONDS);found.putAll(ready);if(ready.size()<pending.size())Thread.sleep(10);}return found;
    }
    /** Native decoding and final-placement selection stay on the command worker. */
    static final class WorldMutation {
        record Resident(Map<ResidentChunks.Key,szserver.client.NativeResidentChunkCodec.PreparedScenes> chunks,World.SceneMetadataPrepared metadata){}
        private final WorldLoop world;private World.SceneMetadataPrepared prepared;private BlockOverlays.Prepared root;
        private final List<SceneEdits.Change> changes;private final String digest;
        private final Map<UUID,World.SceneLocation> locations;private final Map<UUID,Boolean> existed;private final boolean finish;
        private final List<World.MetadataPortalChange> portals;
        private WorldMutation(WorldLoop world,List<SceneEdits.Change> changes,String digest,Map<UUID,World.SceneLocation> locations,Map<UUID,Boolean> existed,boolean finish){this(world,changes,digest,locations,existed,finish,List.of());}
        private WorldMutation(WorldLoop world,List<SceneEdits.Change> changes,String digest,Map<UUID,World.SceneLocation> locations,Map<UUID,Boolean> existed,boolean finish,List<World.MetadataPortalChange> portals){this.world=world;this.changes=changes;this.digest=digest;this.locations=locations;this.existed=existed;this.finish=finish;this.portals=List.copyOf(portals);}
        WorldMutation withPortals(List<World.MetadataPortalChange> metadata){return new WorldMutation(world,changes,digest,locations,existed,true,metadata);}
        static WorldMutation metadata(WorldLoop world,String digest,List<World.MetadataPortalChange> metadata){return new WorldMutation(world,List.of(),digest,Map.of(),Map.of(),false).withPortals(metadata);}
        List<BlockOverlays.SceneChange> overlays(){return changes.stream().map(c->new BlockOverlays.SceneChange(c.x(),c.z(),c.id(),c.after())).toList();}
        Resident resident(ResidentMapRuntime runtime)throws Exception{
            Map<ResidentChunks.Key,List<SceneEdits.Change>> groups=new LinkedHashMap<>();for(var change:changes)groups.computeIfAbsent(new ResidentChunks.Key(change.x(),change.z()),ignored->new ArrayList<>()).add(change);
            Map<ResidentChunks.Key,szserver.client.NativeResidentChunkCodec.PreparedScenes> nativeChunks=new LinkedHashMap<>();
            var keys=new ArrayList<>(groups.keySet());
            for(int offset=0;offset<keys.size();offset+=16){
                var batch=keys.subList(offset,Math.min(offset+16,keys.size()));
                var loads=world.submit(w->batch.stream().map(runtime::load).toList()).get(5,TimeUnit.SECONDS);
                CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
                for(int index=0;index<batch.size();index++){
                    var key=batch.get(index);
                    var snapshot=loads.get(index).join();
                    Map<UUID,ScenePayload.Entry> next=new LinkedHashMap<>();
                    for(var change:groups.get(key)){
                        if(!Objects.equals(snapshot.scenes().get(change.id()),change.before()))throw new IllegalArgumentException("scene_edit_conflicts_with_later_edit");
                        next.put(change.id(),change.after());
                    }
                    nativeChunks.put(key,szserver.client.NativeResidentChunkCodec.prepareScenes(snapshot,next,runtime.sceneRegistry()));
                }
            }
            var view=world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);if(!digest.equals(view.owner().sceneRegistryDigest()))throw new IllegalArgumentException("scene_edit_registry_changed");return new Resident(Map.copyOf(nativeChunks),World.prepareSceneMetadata(view,overlays(),locations,existed,finish,portals));
        }
        void prepare()throws Exception{if(prepared!=null)return;World.SceneMetadataView view=world.beforeStart()?world.bootstrap(World::sceneMetadataView):world.submit(World::sceneMetadataView).get(5,TimeUnit.SECONDS);var overlays=overlays();prepared=World.prepareSceneMetadata(view,overlays,locations,existed,finish,portals);root=BlockOverlays.prepare(view.blocks(),List.of(),overlays);}
        void validate(World w){if(!digest.equals(w.sceneRegistryDigest()))throw new IllegalArgumentException("scene_edit_registry_changed");if(prepared==null)throw new IllegalStateException("scene_mutation_not_prepared");prepared.validate(w);}
        void apply(World w,boolean metadataOnly){if(!metadataOnly)w.overlayVersions().publish(root);w.publishSceneMetadata(prepared);}
    }
    WorldMutation prepareApply(JsonObject event){
        List<SceneEdits.Change> changes=event.has("sceneChanges")?SceneEdits.read(event):List.of();boolean metadataFinal=!SceneEditorStates.portals(event).isEmpty();String digest=Json.text(event,"sceneRegistry",64);
        Map<UUID,World.SceneLocation> finalLocations=new HashMap<>();Map<UUID,Boolean> existed=new LinkedHashMap<>();
        for(var change:changes){existed.merge(change.id(),change.before()!=null,(a,b)->a||b);World.SceneLocation before=finalLocations.get(change.id());var after=change.after();boolean anchored=after!=null&&Math.floor(after.object().x()/16)==change.x()&&Math.floor(after.object().z()/16)==change.z();boolean previousAnchored=before!=null&&before.entry()!=null&&Math.floor(before.entry().object().x()/16)==before.x()&&Math.floor(before.entry().object().z()/16)==before.z();if(before==null||after!=null&&(anchored||!previousAnchored))finalLocations.put(change.id(),new World.SceneLocation(change.x(),change.z(),after));}
        return new WorldMutation(world,changes,digest,Map.copyOf(finalLocations),Collections.unmodifiableMap(new LinkedHashMap<>(existed)),!event.has("portalMetadata")&&!metadataFinal);
    }
    private WorldMutation preparedApplication;private String preparedCommand;
    void prepareApplication(JsonObject event)throws Exception{discardPrepared();preparedApplication=prepareApply(event).withPortals(new ScenePortalProjection(world).compile(event,false));preparedApplication.prepare();preparedCommand=Json.text(event,"commandId",64);}
    void discardPrepared(){preparedApplication=null;preparedCommand=null;}
    void apply(JsonObject event,boolean bootstrap)throws Exception{
        WorldMutation mutation=!bootstrap&&preparedApplication!=null&&Objects.equals(preparedCommand,Json.text(event,"commandId",64))?preparedApplication:prepareApply(event);
        if(mutation!=preparedApplication&&handles(Json.text(event,"type",32)))mutation=mutation.withPortals(new ScenePortalProjection(world).compile(event,bootstrap));mutation.prepare();final WorldMutation ready=mutation;
        java.util.function.Function<World,Boolean> action=w->{ready.validate(w);ready.apply(w,false);return true;};
        if(bootstrap)world.bootstrap(action);else world.submit(action).get(5,TimeUnit.SECONDS);if(Json.text(event,"type",32).equals("scene_undo"))history.markUndone(Json.text(event,"target",64));
    }
}
