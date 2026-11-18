package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pending SQL baseline only when the original projection has no native frame. */
public final class SceneEditorStateImportSeed {
    public static JsonObject attach(JsonObject event,String asset,JsonArray transform,World.Vec position,JsonObject legacyState,byte[] framedSave,UUID parent,UUID group,boolean deleted){
        UUID id=UUID.fromString(Json.text(event,"objectId",36));JsonObject row=new JsonObject();row.addProperty("asset",asset==null?"":asset);row.add("transform",transform==null?new JsonArray():transform.deepCopy());row.add("position",position==null?new JsonArray():Json.GSON.toJsonTree(List.of(position.x(),position.y(),position.z())));row.addProperty("legacy",true);row.addProperty("hideout",false);row.addProperty("deleted",deleted);row.addProperty("reason","legacy_scene_without_native_frame");
        JsonObject state=legacyState.deepCopy();state.remove("parent_id");state.remove("group_id");row.add("legacyState",state);if(parent!=null)row.addProperty("parent",parent.toString());if(group!=null)row.addProperty("group",group.toString());
        if(framedSave!=null){ByteBuffer in=ByteBuffer.wrap(framedSave);int size=ScenePayload.varint(in);if(size!=in.remaining())throw new IllegalArgumentException("legacy_scene_full_save_frame");row.addProperty("legacyFullSave",Base64.getEncoder().encodeToString(framedSave));if(size>0)row.addProperty("fullSave",Base64.getEncoder().encodeToString(ScenePayload.take(in,size)));}
        row=Json.parse(Json.bytes(row),192*1024);SceneEditorStates.validateRow(row);SceneEditorStates.save(event,List.of(new SceneEditorStates.Change(id,null,row)));SceneEditorStates.read(event);reseal(event);return event;
    }
    static void validate(JsonObject event){var changes=SceneEditorStates.read(event);UUID id=UUID.fromString(Json.text(event,"objectId",36));if(changes.size()!=1||!changes.get(0).id().equals(id)||changes.get(0).before()!=null||changes.get(0).after()==null||!changes.get(0).after().get("legacy").getAsBoolean())throw new IllegalArgumentException("legacy_scene_editor_state_seed");if(event.has("sceneChanges"))for(var c:SceneEdits.read(event))if(c.after()!=null)throw new IllegalArgumentException("legacy_scene_editor_state_native_frame");}
    /** Validate the combined uncompressed state capacity before an offline realm is published. */
    public static final class Validator implements AutoCloseable {private final SceneEditorStates states;public Validator(){this(java.nio.file.Path.of("var"));}public Validator(java.nio.file.Path base){states=new SceneEditorStates(base);}public void close(){states.close();}public void event(JsonObject event){if(event.has("editorStateChanges")){validate(event);states.apply(event);}}}
    public static void validateAll(List<JsonObject> events){try(Validator validator=new Validator()){for(var event:events)validator.event(event);}}
    private static void reseal(JsonObject event){event.remove("commandId");event.remove("request");String fingerprint=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",fingerprint);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_scene_seed_record_limit");validate(event);}
    private SceneEditorStateImportSeed(){}
}
