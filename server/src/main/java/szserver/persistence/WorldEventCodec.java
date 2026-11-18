package szserver.persistence;
import com.google.gson.*;
import szserver.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Bounded canonical JSON parts preserve opaque explicit NULL fields inside the ordinary journal. */
public final class WorldEventCodec {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private WorldEventCodec(){}
    public static byte[] bytes(JsonObject value){return JSON.toJson(value).getBytes(StandardCharsets.UTF_8);}
    public static JsonArray encode(JsonArray rows){JsonObject root=new JsonObject();root.add("rows",rows);byte[] raw=bytes(root);if(rows.isEmpty()||rows.size()>128||raw.length>48000)throw new IllegalArgumentException("world_event_parts_limit");String encoded=Base64.getEncoder().encodeToString(raw);JsonArray parts=new JsonArray();for(int at=0;at<encoded.length();at+=8192)parts.add(encoded.substring(at,Math.min(at+8192,encoded.length())));return parts;}
    public static JsonArray decode(JsonElement encoded){if(encoded==null||!encoded.isJsonArray()||encoded.getAsJsonArray().isEmpty()||encoded.getAsJsonArray().size()>8)throw new IllegalArgumentException("world_event_parts_limit");StringBuilder parts=new StringBuilder();for(var value:encoded.getAsJsonArray()){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().length()>8192)throw new IllegalArgumentException("world_event_parts_limit");parts.append(value.getAsString());}byte[] raw=Base64.getDecoder().decode(parts.toString());if(raw.length>48000)throw new IllegalArgumentException("world_event_parts_limit");JsonObject root=Json.parse(raw);if(!root.keySet().equals(Set.of("rows"))||!root.get("rows").isJsonArray()||root.getAsJsonArray("rows").isEmpty()||root.getAsJsonArray("rows").size()>128)throw new IllegalArgumentException("world_event_parts_limit");return root.getAsJsonArray("rows");}
    public static JsonArray single(JsonObject row){JsonArray rows=new JsonArray();rows.add(row);return encode(rows);}
}
