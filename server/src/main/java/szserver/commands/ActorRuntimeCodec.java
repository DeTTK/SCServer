package szserver.commands;

import com.google.gson.*;
import szserver.world.*;
import szserver.util.Json;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict versioned internal codec. Encoding and JSON validation stay on command/storage workers. */
final class ActorRuntimeCodec {
    private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static JsonObject encode(ActorRuntime.Snapshot snapshot){var value=GSON.toJsonTree(snapshot).getAsJsonObject();if(snapshot.version()==1)value.remove("controllers");if(snapshot.mines().isEmpty())value.remove("mines");return value;}
    static byte[] encode(ActorRuntime actor){return GSON.toJson(actor).getBytes(StandardCharsets.UTF_8);}
    static byte[] encode(ActorRuntime.Controller controller){return GSON.toJson(controller).getBytes(StandardCharsets.UTF_8);}
    static ActorRuntime.Snapshot snapshot(JsonObject value){
        if(value.has("version")&&value.get("version").getAsInt()==1){if(value.has("controllers"))throw new IllegalArgumentException("actor_runtime_v1_fields");value=value.deepCopy();value.add("controllers",new JsonArray());}
        return read(value,ActorRuntime.Snapshot.class);
    }
    static ActorRuntime actor(byte[] bytes){if(bytes.length>2*1024*1024)throw new IllegalArgumentException("actor_runtime_size");return read(Json.parse(bytes,2*1024*1024,262144),ActorRuntime.class);}
    static ActorRuntime.Controller controller(byte[] bytes){if(bytes.length>2*1024*1024)throw new IllegalArgumentException("actor_controller_size");return read(Json.parse(bytes,2*1024*1024,262144),ActorRuntime.Controller.class);}
    private static <T> T read(JsonObject value,Class<T> type){try{shape(type,value);return GSON.fromJson(value,type);}catch(RuntimeException invalid){throw new IllegalArgumentException("actor_runtime_invalid",invalid);}}
    private static void shape(Type type,JsonElement value){
        if(value==null)throw new IllegalArgumentException("actor_runtime_missing_field");if(value.isJsonNull()){if(type instanceof Class<?> c&&c.isPrimitive())throw new IllegalArgumentException("actor_runtime_null_primitive");return;}
        if(type instanceof ParameterizedType generic&&generic.getRawType()==List.class){if(!value.isJsonArray()||value.getAsJsonArray().size()>ActorScene.MAX_CELLS)throw new IllegalArgumentException("actor_runtime_array");for(var child:value.getAsJsonArray())shape(generic.getActualTypeArguments()[0],child);return;}
        if(!(type instanceof Class<?> c))throw new IllegalArgumentException("actor_runtime_type");
        if(c==ActorPresentation.class){
            if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("roots","attributes")))throw new IllegalArgumentException("actor_presentation_fields");
            for(String name:List.of("roots","attributes")){
                var entries=value.getAsJsonObject().get(name);if(!entries.isJsonObject()||entries.getAsJsonObject().size()>(name.equals("roots")?3:18))throw new IllegalArgumentException("actor_presentation_map");
                for(var entry:entries.getAsJsonObject().entrySet()){
                    if(!entry.getKey().matches("[0-9]{1,3}")||!Integer.toString(Integer.parseInt(entry.getKey())).equals(entry.getKey()))throw new IllegalArgumentException("actor_presentation_key");
                    shape(String.class,entry.getValue());
                }
            }return;
        }
        if(c.isRecord()){
            if(c==ActorRuntime.Snapshot.class&&value.isJsonObject()&&!value.getAsJsonObject().has("mines"))value.getAsJsonObject().add("mines",new JsonArray());
            if(c==ActorContacts.Obstacle.class&&value.isJsonObject()&&!value.getAsJsonObject().has("living"))value.getAsJsonObject().add("living",JsonNull.INSTANCE);
            if(!value.isJsonObject())throw new IllegalArgumentException("actor_runtime_object");var object=value.getAsJsonObject();if(c==ActorControllerEngine.Input.class)for(String optional:List.of("condition","seat"))if(!object.has(optional))object.add(optional,JsonNull.INSTANCE);if(c==ActorControllerState.class){if(!object.has("presentation"))object.add("presentation",JsonNull.INSTANCE);if(!object.has("carousels"))object.add("carousels",new JsonArray());}var components=c.getRecordComponents();Set<String> keys=new HashSet<>();for(var field:components){keys.add(field.getName());shape(field.getGenericType(),object.get(field.getName()));}if(!object.keySet().equals(keys))throw new IllegalArgumentException("actor_runtime_fields");return;
        }
        if(c==String.class||c==UUID.class){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("actor_runtime_string");return;}
        if(c.isEnum()){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||Arrays.stream(c.getEnumConstants()).noneMatch(e->((Enum<?>)e).name().equals(value.getAsString())))throw new IllegalArgumentException("actor_runtime_enum");return;}
        if(c==boolean.class){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("actor_runtime_boolean");return;}
        if(c.isPrimitive()){
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("actor_runtime_number");
            try{if(c==int.class)value.getAsBigDecimal().intValueExact();else if(c==long.class)value.getAsBigDecimal().longValueExact();else if(!Double.isFinite(value.getAsDouble())||c==float.class&&!Float.isFinite(value.getAsFloat()))throw new ArithmeticException();}catch(ArithmeticException e){throw new IllegalArgumentException("actor_runtime_number",e);}return;
        }
        throw new IllegalArgumentException("actor_runtime_type");
    }
    private ActorRuntimeCodec(){}
}
