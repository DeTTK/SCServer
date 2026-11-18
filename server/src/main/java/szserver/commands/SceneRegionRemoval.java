package szserver.commands;

import com.google.gson.*;
import szserver.world.*;
import java.util.*;

/** Clear the non-geometric rows the old Store cleared alongside a native worldedit region. */
final class SceneRegionRemoval {
    static boolean inside(World.Vec p,World.Block min,World.Block max){return p!=null&&p.x()>=min.x()&&p.x()<max.x()+1d&&p.y()>=min.y()&&p.y()<max.y()+1d&&p.z()>=min.z()&&p.z()<max.z()+1d;}
    static void prepare(JsonObject event,SceneEditorStates states,SceneDomain domain) {
        List<World.Block> cells=BlockEdit.cells(event);World.Block min=cells.get(0),max=cells.get(cells.size()-1);
        Set<UUID> selected=new TreeSet<>();if(event.has("sceneChanges"))for(var c:SceneEdits.read(event))selected.add(c.id());
        List<SceneEditorStates.Change> changes=new ArrayList<>();
        for(var entry:states.currentRows().entrySet()){
            JsonObject row=entry.getValue();World.Vec position=row.has("position")&&row.getAsJsonArray("position").isEmpty()?null:SceneEditorStates.position(row);
            if(!selected.contains(entry.getKey())&&!inside(position,min,max))continue;
            selected.add(entry.getKey());if(selected.size()>SceneLimits.IDENTITIES)throw new IllegalArgumentException("worldedit_scene_selection_byte_or_identity_limit");if(SceneEditorStates.deleted(row))continue;
            JsonObject after=row.deepCopy();after.addProperty("deleted",true);states.plan(changes,entry.getKey(),after);
        }
        changes.sort(Comparator.comparing(SceneEditorStates.Change::id));states.finish(event,changes);int pending=changes.size();int backend=domain.removeRegion(event,min,max,selected);
        if(pending>0||backend>0){event.addProperty("sceneRegionCleanup",true);event.addProperty("scenePendingRemoved",pending);event.addProperty("sceneBackendRemoved",backend);event.remove("status");}
    }
    private SceneRegionRemoval(){}
}
