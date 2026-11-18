package szserver.commands;

import com.google.gson.*;
import szserver.client.*;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.*;

/** One native batch becomes one durable edit. Decode/projection run on the command worker. */
final class NativeSceneEditor {
    private record PendingEdit(SceneEditPackets.Edit edit,String seedAsset){}
    /** Both native lists are ordered upserts. Fold to one before/after pair per
     * identity before publishing, while retaining the first native factory and
     * the last asset descriptor/complete Inspector snapshot (old Store semantics). */
    private static List<PendingEdit> ordered(List<SceneEditPackets.Edit> decoded){
        Map<UUID,PendingEdit> result=new LinkedHashMap<>();
        for(var incoming:decoded){
            UUID id=incoming.identity()==null?UUID.randomUUID():incoming.identity();
            PendingEdit previous=result.get(id);String asset=incoming.asset();
            String seed=previous==null?asset:previous.seedAsset();
            if(previous!=null){if(asset==null)asset=previous.edit().asset();if(seed==null)seed=incoming.asset();}
            var edit=new SceneEditPackets.Edit(id,asset,incoming.transform(),incoming.properties(),incoming.parent(),incoming.group(),incoming.deletion());
            result.put(id,new PendingEdit(edit,seed));
        }
        return List.copyOf(result.values());
    }
    private final WorldLoop world;private final SceneSeeds seeds;private final SceneRelationships relationships;private final SceneEditorStates states;private final SceneDeletedStates deleted;
    private final Map<UUID,ScenePlacementPlan.Projection> projections;
    NativeSceneEditor(WorldLoop world,SceneSeeds seeds,SceneRelationships relationships,SceneEditorStates states,SceneDeletedStates deleted,Map<UUID,ScenePlacementPlan.Projection> projections){this.world=world;this.seeds=seeds;this.relationships=relationships;this.states=states;this.deleted=deleted;this.projections=projections;}
    List<SceneEdits.Change> prepare(JsonObject request,JsonObject event)throws Exception{
        String kind=Json.text(request,"packetKind",32);
        List<SceneEditPackets.Edit> decoded=SceneEditPackets.read(kind,SceneEditPackets.parts(request));
        if(kind.equals("transform"))event.addProperty("nativeUpdate",true);
        JsonElement scopeValue=request.get("visibleChunks");
        if(scopeValue==null||!scopeValue.isJsonArray()||scopeValue.getAsJsonArray().isEmpty()||scopeValue.getAsJsonArray().size()>289)throw new IllegalArgumentException("scene_editor_visible_chunks");
        List<Long> scope=new ArrayList<>();for(JsonElement value:scopeValue.getAsJsonArray()){long key=value.getAsLong();int x=(int)(key>>32),z=(int)key;if(Math.abs((long)x)>szserver.world.MapBounds.CHUNK||Math.abs((long)z)>szserver.world.MapBounds.CHUNK)throw new IllegalArgumentException("scene_editor_chunk_bounds");scope.add(key);}scope=List.copyOf(new LinkedHashSet<>(scope));
        List<PendingEdit> pending=ordered(decoded);
        Set<UUID> ids=new HashSet<>();Set<String> assets=new HashSet<>();
        // Anonymous creations have no source to find; don't scan cold chunks
        // for the UUIDs assigned by this command.
        for(var edit:decoded)if(edit.identity()!=null)ids.add(edit.identity());
        for(var plan:pending)if(plan.seedAsset()!=null)assets.add(plan.seedAsset());
        World.SceneEditLookup lookup=lookup(ids,assets,scope);
        Set<UUID> metadataLive=world.submit(w->{Set<UUID> result=new HashSet<>();for(UUID id:ids)if(w.metadataSceneExists(id))result.add(id);return Set.copyOf(result);}).get(5,TimeUnit.SECONDS);
        var behaviors=ScenePlacementPlan.behaviours(world);
        List<SceneEditorStates.Change> statePlans=new ArrayList<>();List<SceneEdits.Change> changes=new ArrayList<>();JsonArray identities=new JsonArray();
        for(var plan:pending){
            var edit=plan.edit();
            UUID id=edit.identity()==null?UUID.randomUUID():edit.identity();World.SceneLocation location=lookup.objects().get(id);ScenePayload.Entry before=location==null?null:location.entry();JsonObject pendingState=states.current(id);
            if(edit.deletion()){
                if(pendingState!=null&&before==null){JsonObject removed=pendingState.deepCopy();removed.addProperty("deleted",true);states.plan(statePlans,id,removed);continue;}if(location==null)throw new IllegalArgumentException("scene_object_not_found");if(before!=null)changes.add(new SceneEdits.Change(location.x(),location.z(),id,before,null));continue;
            }
            // A decoded asset+matrix is creation intent even on Hideout update257.
            // Original update_scene_object(create_if_missing=True) accepts a new UUID.
            boolean creation=edit.asset()!=null;
            if(before==null&&!creation&&deleted.current(id)!=null)throw new IllegalArgumentException("scene_object_deleted");
            if(location!=null&&before==null){if(!creation&&!metadataLive.contains(id)&&pendingState==null)throw new IllegalArgumentException("scene_object_deleted");location=null;}
            ScenePayload.Entry template=before;
            if(before==null){
                if(!creation&&pendingState==null){
                    if(!kind.equals("batch")||edit.properties()==null)throw new IllegalArgumentException("scene_object_not_found");
                    states.plan(statePlans,id,SceneEditorStates.nativeState(null,"",edit,"scene_editor_asset_unresolved",false));
                    relationships.remember(event,kind,id,edit);identities.add(id.toString());continue;
                }
                String asset=edit.asset()!=null?edit.asset():pendingState.get("asset").getAsString();
                String reason=pendingState==null?"scene_editor_template_not_found":pendingState.get("reason").getAsString();
                String seedAsset=plan.seedAsset()==null?asset:plan.seedAsset();
                SceneDeletedStates.Removed retained=deleted.current(id);if(retained!=null){template=retained.entry();}else template=lookup.templates().get(seedAsset);
                // The second list has no asset creation intent; retain its complete Inspector state.
                if(template==null&&edit.asset()!=null)try{if(seeds==null)throw new IllegalArgumentException("scene_editor_template_not_found");template=seeds.create(seedAsset);}catch(IllegalArgumentException failure){if(!SceneEditorStates.constructionFailure(failure))throw failure;reason=failure.getMessage();}
                if(template==null){
                    if(reason.length()>512)reason=reason.substring(0,512);states.plan(statePlans,id,SceneEditorStates.nativeState(pendingState,asset,edit,reason,!kind.equals("batch")));
                    relationships.remember(event,kind,id,edit);identities.add(id.toString());continue;
                }
            }
            // Existing/retained native_record owns its factory. A packet asset changes Store
            // metadata, not the binary type of an existing frame (original handler/projectors).
            SceneObject base=before==null?(deleted.current(id)!=null?template.object():template.object().shifted(id,0,0,0)):before.object();if(before==null&&pendingState!=null){JsonObject retained=pendingState;if(edit.properties()!=null){retained=pendingState.deepCopy();retained.remove("properties");retained.remove("legacyState");}base=SceneEditorStates.materialize(base,retained,behaviors.apply(base.asset()));}SceneObject changed=SceneInspectorProjection.apply(base,edit,behaviors.apply(base.asset()));
            changed=SceneEditorStates.culling(changed,pendingState,behaviors.apply(base.asset()),edit.properties()!=null);
            projections.put(id,new ScenePlacementPlan.Projection(false,o->{SceneObject source=o.container()?o.object().shifted(id,0,0,0):o.object();return SceneEditorStates.culling(SceneInspectorProjection.apply(source,edit,behaviors.apply(source.asset())),pendingState,behaviors.apply(source.asset()),edit.properties()!=null);}));
            if(!szserver.world.MapBounds.scene(changed.x(),changed.y(),changed.z()))throw new IllegalArgumentException("scene_edit_position");
            if(pendingState!=null)states.plan(statePlans,id,null);relationships.remember(event,kind,id,edit);if(!kind.equals("batch")&&event.has("sceneRelationships"))SceneRelationships.object(event,id,changed.asset(),edit.transform());
            if(edit.asset()!=null){JsonArray requests=event.has("sceneNativeAssetRequests")?event.getAsJsonArray("sceneNativeAssetRequests"):new JsonArray();JsonObject row=new JsonObject();row.addProperty("objectId",id.toString());row.addProperty("asset",edit.asset());row.addProperty("hideout",!kind.equals("batch"));JsonArray matrix=new JsonArray();for(float value:edit.transform())matrix.add(value);row.add("transform",matrix);row.add("parent_id",edit.parent()==null?JsonNull.INSTANCE:new JsonPrimitive(edit.parent().toString()));requests.add(row);event.add("sceneNativeAssetRequests",requests);}
            int tx=(int)Math.floor(changed.x()/16),tz=(int)Math.floor(changed.z()/16);ScenePayload.Entry after=new ScenePayload.Entry(template.container(),changed);identities.add(id.toString());
            if(before!=null&&location.x()==tx&&location.z()==tz){if(!before.equals(after))changes.add(new SceneEdits.Change(tx,tz,id,before,after));}
            else{if(before!=null)changes.add(new SceneEdits.Change(location.x(),location.z(),id,before,null));changes.add(new SceneEdits.Change(tx,tz,id,null,after));}
        }
        states.finish(event,statePlans);event.add("objectIds",identities);relationships.validate(event);if(changes.isEmpty()&&!event.has("sceneRelationships")&&!event.has("editorStateChanges"))event.addProperty("status","unchanged");return List.copyOf(changes);
    }
    private World.SceneEditLookup lookup(Set<UUID> ids,Set<String> assets,List<Long> scope)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);Map<UUID,World.SceneLocation> objects=new HashMap<>();Map<String,ScenePayload.Entry> templates=new HashMap<>();Set<Long> examined=new HashSet<>();
        while(true){
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalArgumentException("scene_editor_load_timeout");List<Long> pending=scope.stream().filter(key->!examined.contains(key)).toList();
            Set<UUID> missingIds=new HashSet<>(ids);missingIds.removeAll(objects.keySet());Set<String> missingAssets=new HashSet<>(assets);missingAssets.removeAll(templates.keySet());
            // Retain immutable results on this worker. Do not requeue already found distant chunks in a small cache.
            World.SceneEditLookup found=world.submit(w->w.findSceneEdits(missingIds,missingAssets,pending)).get(remaining,TimeUnit.NANOSECONDS);objects.putAll(found.objects());found.templates().forEach(templates::putIfAbsent);examined.addAll(found.examined());
            if(!found.pending()||objects.keySet().containsAll(ids)&&templates.keySet().containsAll(assets))return new World.SceneEditLookup(Map.copyOf(objects),Map.copyOf(templates),false,Set.copyOf(examined));Thread.sleep(10);
        }
    }
}
