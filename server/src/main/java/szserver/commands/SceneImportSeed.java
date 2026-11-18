package szserver.commands;

import com.google.gson.JsonObject;
import szserver.util.Json;
import szserver.world.*;
import java.util.*;

/** Offline baseline in the existing world journal; subsequent ordinary edits undo to this baseline. */
public final class SceneImportSeed {
    public static JsonObject create(String world,String registry,String legacyWorld,long revision,UUID id,Map<Terrain.SceneHome,ScenePayload.Entry> placements){
        if(placements.isEmpty()||placements.size()>256||revision<1)throw new IllegalArgumentException("legacy_scene_seed_bounds");
        JsonObject event=Json.tree(Map.of("type","scene_import","worldIdentity",world,"sceneRegistry",registry,"legacyWorld",legacyWorld,"sourceRevision",revision,"objectId",id.toString()));
        List<SceneEdits.Change> changes=new ArrayList<>();placements.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Terrain.SceneHome,ScenePayload.Entry> e)->e.getKey().x()).thenComparingInt(e->e.getKey().z())).forEach(e->changes.add(new SceneEdits.Change(e.getKey().x(),e.getKey().z(),id,null,e.getValue())));
        SceneEdits.save(event,changes);SceneEdits.read(event);String fingerprint=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());event.addProperty("request",fingerprint);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_scene_seed_record_limit");return event;
    }
    private SceneImportSeed(){}
}
