package szserver.commands;

import com.google.gson.*;
import szserver.client.SceneCulling;
import szserver.world.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Pending Inspector rows and retained tombstones participate in the same cleanup/undo transaction. */
final class SceneCullingRetained {
    private static boolean inside(List<SceneCullingVolume> volumes,World.Vec p){
        if(p==null)return false;for(var v:volumes)if(Math.abs(p.x()-v.position().x())<=v.halfExtent().x()+1e-4&&Math.abs(p.y()-v.position().y())<=v.halfExtent().y()+1e-4&&Math.abs(p.z()-v.position().z())<=v.halfExtent().z()+1e-4)return true;return false;
    }
    private static World.Vec position(JsonObject row){
        // Explicit SQL NULL is not replaced by translation from a surviving matrix.
        if(row.has("legacy")&&row.has("position")&&row.getAsJsonArray("position").isEmpty())return null;
        return SceneEditorStates.position(row);
    }
    private static void visible(JsonObject row,Set<UUID> volumes){
        if(row.has("legacyState")&&row.getAsJsonObject("legacyState").has("flag_2"))row.getAsJsonObject("legacyState").addProperty("flag_2",true);
        if(row.has("properties"))row.getAsJsonObject("properties").addProperty("flag2",true);
        JsonObject c=row.has("cullingCleanup")?row.getAsJsonObject("cullingCleanup"):new JsonObject();c.addProperty("ignore",true);
        Set<String> ids=new TreeSet<>();if(c.has("clearedVolumes"))for(var id:c.getAsJsonArray("clearedVolumes"))ids.add(id.getAsString());for(UUID id:volumes)ids.add(id.toString());
        if(ids.size()>128)throw new IllegalArgumentException("scene_editor_state_culling_limit");JsonArray a=new JsonArray();ids.forEach(a::add);c.add("clearedVolumes",a);row.add("cullingCleanup",c);
    }
    static void prepare(SceneCullingCleanup.Plan plan,JsonObject event,WorldLoop world,SceneEditorStates states,SceneDeletedStates deleted)throws Exception{
        if(plan.volumes().isEmpty())return;Set<UUID> volumes=new HashSet<>();for(var v:plan.volumes())volumes.add(v.id());
        Set<String> assets=new HashSet<>();for(var r:deleted.currentRows().values())assets.add(r.entry().object().asset());
        Map<String,String> behaviors=assets.isEmpty()?Map.of():world.submit(w->{Map<String,String> result=new HashMap<>();for(String asset:assets)result.put(asset,w.sceneBehaviour(asset));return result;}).get(5,TimeUnit.SECONDS);
        Set<UUID> affected=new HashSet<>();for(var c:plan.changes())affected.add(c.id());Set<UUID> linkedRows=new HashSet<>();List<SceneEdits.Change> nativeChanges=new ArrayList<>();int references=0;
        for(var row:deleted.currentRows().entrySet()){
            UUID id=row.getKey();SceneDeletedStates.Removed retained=row.getValue();SceneObject before=retained.entry().object();
            // Live volume deletions retain their original body solely for the existing exact undo/revival contract.
            if(volumes.contains(id))continue;JsonObject pending=states.current(id);World.Vec p=pending==null?new World.Vec(before.x(),before.y(),before.z()):position(pending);boolean inside=inside(plan.volumes(),p);
            String behavior=behaviors.get(before.asset());SceneCulling.State c;
            try{c=SceneCulling.read(before,behavior);}catch(IllegalArgumentException invalid){if(inside||before.hasCullingReference())throw new IllegalArgumentException("scene_culling_cleanup_unsupported_retained:"+id+":"+before.asset(),invalid);continue;}
            boolean linked=volumes.contains(c.volume());if(linked)linkedRows.add(id);if(!inside&&!linked)continue;
            SceneObject after=SceneCulling.apply(before,behavior,linked,null,true);if(after.equals(before))continue;
            nativeChanges.add(new SceneEdits.Change(retained.x(),retained.z(),id,retained.entry(),new ScenePayload.Entry(retained.entry().container(),after)));if(linked)references++;
            affected.add(id);if(affected.size()>SceneCullingCleanup.MAX_OBJECTS)throw new IllegalArgumentException("scene_culling_cleanup_object_limit");
        }
        List<SceneEditorStates.Change> stateChanges=new ArrayList<>();
        for(var row:states.currentRows().entrySet()){
            UUID id=row.getKey();if(volumes.contains(id))continue;boolean inside=inside(plan.volumes(),position(row.getValue()));if(!inside&&!linkedRows.contains(id))continue;
            JsonObject after=row.getValue().deepCopy();visible(after,volumes);states.plan(stateChanges,id,after);
            affected.add(id);if(affected.size()>SceneCullingCleanup.MAX_OBJECTS)throw new IllegalArgumentException("scene_culling_cleanup_object_limit");
        }
        states.finish(event,stateChanges);deleted.withAdditional(event,nativeChanges);
        event.addProperty("retainedBodiesChanged",nativeChanges.size());event.addProperty("retainedReferencesCleared",references);event.addProperty("pendingRowsChanged",stateChanges.size());
        if(!nativeChanges.isEmpty()||!stateChanges.isEmpty())event.remove("status");
    }
    private SceneCullingRetained(){}
}
