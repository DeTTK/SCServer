package szserver.client;

import com.google.gson.*;
import szserver.net.Wire;
import szserver.util.Json;
import szserver.world.*;
import java.nio.*;
import java.util.*;

/** Old SQLite Inspector fields and length-framed FULL_SAVE, translated only on an offline worker. */
public final class LegacySceneState {
    private static final Set<String> REQUIRED=Set.of("mode","render_flags","vector_1","vector_2","float_1","float_2","flag_1","vector_3","flag_2","flag_3","state_name","flag_4","tag");
    private static final Set<String> ALLOWED;
    static{Set<String> keys=new HashSet<>(REQUIRED);keys.addAll(Set.of("instance_name","_native_channel","editor_flags","editor_mode"));ALLOWED=Set.copyOf(keys);}
    private static World.Vec vector(JsonObject state,String key){JsonArray a=state.getAsJsonArray(key);if(a==null||a.size()!=3)throw new IllegalArgumentException("legacy_scene_vector");double[] v=new double[3];for(int i=0;i<3;i++){if(!a.get(i).isJsonPrimitive()||!a.get(i).getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_scene_vector");v[i]=a.get(i).getAsDouble();if(!Double.isFinite(v[i])||Math.abs(v[i])>30000000)throw new IllegalArgumentException("legacy_scene_vector");}return new World.Vec(v[0],v[1],v[2]);}
    private static boolean bool(JsonObject o,String key){var v=o.get(key);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("legacy_scene_boolean");return v.getAsBoolean();}
    private static String text(JsonObject o,String key,boolean nullable){var v=o.get(key);if(v==null||v.isJsonNull()){if(nullable)return null;throw new IllegalArgumentException("legacy_scene_string");}if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().length()>1024||v.getAsString().indexOf('\0')>=0)throw new IllegalArgumentException("legacy_scene_string");return v.getAsString();}
    public static SceneObject apply(SceneObject object,float[] matrix,JsonObject state,byte[] framedSave,String behavior){
        if(state!=null&&!ALLOWED.containsAll(state.keySet()))throw new IllegalArgumentException("legacy_scene_state_unsupported");
        SceneObject projected=SceneInspectorProjection.transform(object,matrix);
        if(framedSave!=null&&!(framedSave.length==1&&framedSave[0]==0)){
            ByteBuffer in=ByteBuffer.wrap(framedSave);int length=ScenePayload.varint(in);if(length!=in.remaining())throw new IllegalArgumentException("legacy_scene_full_save_frame");byte[] body=ScenePayload.take(in,length),raw=projected.record();var prefix=SceneObject.prefix(raw);var layout=SceneAttributes.layout(raw,behavior);
            boolean current=true;try{SceneAttributes.validateFullSave(body,behavior);}catch(IllegalArgumentException legacy){current=false;}
            if(current)projected=SceneAttributes.configured(projected,body,behavior);
            else{
            // The old overlay replaces its whole behavior block, including attributes, rather than merging a partial save.
            projected=new SceneObject(object.asset(),Wire.concat(Arrays.copyOf(raw,prefix.behaviourAt()),body,Arrays.copyOfRange(raw,layout.tailAt(),raw.length)));
            }
        }
        if(state!=null&&state.keySet().containsAll(REQUIRED)){
            var p=new SceneEditPackets.Properties(Json.integer(state,"mode",0,4),Json.integer(state,"render_flags",-128,255),vector(state,"vector_1"),vector(state,"vector_2"),(float)Json.number(state,"float_1",-30000000,30000000),(float)Json.number(state,"float_2",-30000000,30000000),bool(state,"flag_1"),vector(state,"vector_3"),bool(state,"flag_2"),bool(state,"flag_3"),state.has("editor_flags")&&!state.get("editor_flags").isJsonNull()?Json.integer(state,"editor_flags",Integer.MIN_VALUE,Integer.MAX_VALUE):null,state.has("editor_mode")&&!state.get("editor_mode").isJsonNull()?Json.integer(state,"editor_mode",0,2):null,text(state,"state_name",true),bool(state,"flag_4"),text(state,"tag",false),text(state,"instance_name",true));
            projected=SceneInspectorProjection.apply(projected,new SceneEditPackets.Edit(projected.identity(),projected.asset(),matrix,p,null,null,false),behavior);
        }
        SceneAttributes.validateCurrent(projected.record(),behavior);return projected;
    }
    private LegacySceneState(){}
}
