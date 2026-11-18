package szserver.commands;

import com.google.gson.*;
import szserver.util.Json;
import szserver.world.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline ImEditor portal state without a saved native frame; ordinary scene-domain rows stay distinct. */
public final class ScenePortalImportSeed {
    public static JsonObject create(String world,String registry,String source,long revision,PortalMetadata metadata){
        if(revision<1)throw new IllegalArgumentException("legacy_scene_seed_bounds");
        JsonObject event=Json.tree(Map.of("type","scene_portal_import","worldIdentity",world,"sceneRegistry",registry,"legacyWorld",source,"sourceRevision",revision,"objectId",metadata.id().toString()));return attach(event,metadata);
    }
    public static JsonObject attach(JsonObject event,PortalMetadata metadata){
        JsonObject row=Json.tree(Map.of("id",metadata.id().toString(),"asset",metadata.asset(),"scale",metadata.scale(),"removed",metadata.removed()));
        row.add("position",metadata.position()==null?new JsonArray():Json.GSON.toJsonTree(List.of(metadata.position().x(),metadata.position().y(),metadata.position().z())));event.add("portalMetadata",row);read(event);
        event.remove("commandId");event.remove("request");String fingerprint=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",fingerprint);if(Json.bytes(event).length>65536)throw new IllegalArgumentException("legacy_scene_seed_record_limit");return event;
    }
    public static PortalMetadata read(JsonObject event){
        JsonObject row=Json.object(event,"portalMetadata");if(!row.keySet().equals(Set.of("id","asset","position","scale","removed")))throw new IllegalArgumentException("legacy_scene_portal_fields");String id=Json.text(row,"id",36);UUID identity=UUID.fromString(id);if(!identity.toString().equals(id)||!id.equals(Json.text(event,"objectId",36)))throw new IllegalArgumentException("legacy_scene_portal_identity");
        if(!row.get("asset").isJsonPrimitive()||!row.get("asset").getAsJsonPrimitive().isString())throw new IllegalArgumentException("legacy_scene_portal_asset");String asset=row.get("asset").getAsString();if(asset.indexOf('\0')>=0||asset.getBytes(StandardCharsets.UTF_8).length>1024)throw new IllegalArgumentException("legacy_scene_portal_asset");World.Vec position=null;JsonElement value=row.get("position");if(!value.isJsonArray())throw new IllegalArgumentException("legacy_scene_portal_position");if(!value.getAsJsonArray().isEmpty()){if(value.getAsJsonArray().size()!=3)throw new IllegalArgumentException("legacy_scene_portal_position");double[] v=new double[3];for(int i=0;i<3;i++){var n=value.getAsJsonArray().get(i);if(!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_scene_portal_position");v[i]=n.getAsDouble();if(!Double.isFinite(v[i])||Math.abs(v[i])>30000000)throw new IllegalArgumentException("legacy_scene_portal_position");}position=new World.Vec(v[0],v[1],v[2]);}
        if(!row.get("removed").isJsonPrimitive()||!row.get("removed").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("legacy_scene_portal_removed");if(event.has("sceneChanges"))for(var change:SceneEdits.read(event))if(!change.id().equals(identity))throw new IllegalArgumentException("legacy_scene_portal_identity");return new PortalMetadata(identity,asset,position,Json.number(row,"scale",0,Double.MAX_VALUE),row.get("removed").getAsBoolean());
    }
    private ScenePortalImportSeed(){}
}
