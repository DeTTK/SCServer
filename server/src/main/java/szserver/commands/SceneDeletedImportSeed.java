package szserver.commands;

import com.google.gson.JsonObject;
import szserver.util.Json;
import szserver.world.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline retained deleted body; never publishes geometry or occupies user undo history. */
public final class SceneDeletedImportSeed {
    public static JsonObject create(String world,String registry,String source,long revision,ScenePayload.Entry entry){
        if(revision<1)throw new IllegalArgumentException("legacy_scene_seed_bounds");SceneObject object=entry.object();
        JsonObject event=Json.tree(Map.of("type","scene_deleted_import","worldIdentity",world,"sceneRegistry",registry,"legacyWorld",source,"sourceRevision",revision,"objectId",object.identity().toString()));
        JsonObject encoded=new JsonObject();SceneEdits.save(encoded,List.of(new SceneEdits.Change((int)Math.floor(object.x()/16),(int)Math.floor(object.z()/16),object.identity(),null,entry)));event.add("sceneDeletedState",encoded.get("sceneChanges"));validate(event);
        String fingerprint=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",fingerprint);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_scene_seed_record_limit");return event;
    }
    static void validate(JsonObject event){
        if(!Json.text(event,"type",32).equals("scene_deleted_import")||event.has("sceneChanges")||event.has("editorStateChanges"))throw new IllegalArgumentException("legacy_scene_deleted_seed");
        JsonObject wrapper=new JsonObject();wrapper.add("sceneChanges",event.get("sceneDeletedState"));var rows=SceneEdits.read(wrapper);
        UUID id=UUID.fromString(Json.text(event,"objectId",36));if(rows.size()!=1||!rows.get(0).id().equals(id)||rows.get(0).before()!=null||rows.get(0).after()==null)throw new IllegalArgumentException("legacy_scene_deleted_seed");
    }
    public static final class Validator implements AutoCloseable {private final SceneDeletedStates states;public Validator(){this(java.nio.file.Path.of("var"));}public Validator(java.nio.file.Path base){states=new SceneDeletedStates(base);}public void close(){states.close();}public void event(JsonObject event){if(event.has("sceneDeletedState")){validate(event);states.apply(event);}}}
    public static void validateAll(List<JsonObject> events){try(Validator validator=new Validator()){for(var event:events)validator.event(event);}}
    private SceneDeletedImportSeed(){}
}
