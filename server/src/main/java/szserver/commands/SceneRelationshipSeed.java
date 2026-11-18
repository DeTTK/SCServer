package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline reference baseline: historical and deleted sidecars never replay geometry. */
public final class SceneRelationshipSeed {
    public record Links(UUID identity,UUID parent,UUID group){}
    public static JsonObject create(String world,String registry,String sourceWorld,List<Links> rows){
        if(rows.isEmpty()||rows.size()>128)throw new IllegalArgumentException("legacy_scene_relationship_seed_bounds");
        JsonObject event=Json.tree(Map.of("type","scene_relationship_import","worldIdentity",world,"sceneRegistry",registry,"legacyWorld",sourceWorld));List<SceneRelationships.Change> changes=new ArrayList<>();
        for(var row:rows){if(row.identity()==null||row.parent()==null&&row.group()==null)throw new IllegalArgumentException("legacy_scene_relationship_seed_empty");changes.add(new SceneRelationships.Change(row.identity(),false,SceneRelationships.EMPTY,new SceneRelationships.State(row.parent(),row.group())));}
        SceneRelationships.save(event,changes);String fingerprint=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",fingerprint);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_scene_relationship_seed_record_limit");return event;
    }
    private SceneRelationshipSeed(){}
}
