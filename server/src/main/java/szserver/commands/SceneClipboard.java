package szserver.commands;

import com.google.gson.*;
import szserver.world.ScenePayload;
import java.util.*;

/** Durable native object snapshots carried by the same copy/cut command as blocks and tiles. */
final class SceneClipboard {
    static void save(JsonObject event,List<SceneEdits.Change> objects){
        if(objects.isEmpty())return;
        // A snapshot uses the established bounded native edit codec, with the original entry
        // in the before field. It is data only, never an edit to apply when replaying copy.
        JsonObject encoded=new JsonObject();SceneEdits.save(encoded,objects);event.add("sceneClipboard",encoded.get("sceneChanges"));
    }
    static List<SceneEdits.Change> read(JsonObject event){
        if(!event.has("sceneClipboard"))return List.of();JsonObject encoded=new JsonObject();encoded.add("sceneChanges",event.get("sceneClipboard"));List<SceneEdits.Change> rows=SceneEdits.read(encoded);
        for(var row:rows)if(row.before()==null||row.after()!=null)throw new IllegalArgumentException("scene_clipboard_layout");return rows;
    }
}
