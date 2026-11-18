package szserver.migration;

import com.google.gson.*;
import szserver.persistence.CharacterLocations;
import szserver.util.Json;
import szserver.world.World.Vec;
import java.util.*;

/** Preserve NativeAuthority's compatibility snapshot. Its HP/creative slot are not Stalcraft HP/hand. */
public final class LegacyPlayerImport {
    private LegacyPlayerImport(){}
    private static void finite(JsonObject row,String key){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble()))throw new IllegalArgumentException("legacy_player_number");}
    private static long integer(JsonObject row,String key){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_player_integer");try{return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_player_integer",error);}}
    public static UUID validate(JsonObject row,Set<UUID> characters){String raw=Json.text(row,"character_id",36);UUID id=UUID.fromString(raw);if(!raw.equals(id.toString())||!characters.contains(id))throw new IllegalArgumentException("legacy_player_owner_or_duplicate");for(String key:List.of("x","y","z","yaw","pitch","health","max_health"))finite(row,key);CharacterLocations.validPosition(new Vec(row.get("x").getAsDouble(),row.get("y").getAsDouble(),row.get("z").getAsDouble()));integer(row,"selected_slot");integer(row,"last_client_tick");if(integer(row,"revision")<0||integer(row,"event_sequence")<0)throw new IllegalArgumentException("legacy_player_revision");Json.text(row,"pose",512);Json.text(row,"animation",512);Json.text(row,"updated_at",128);Json.parse(Json.text(row,"extensions_json",16384).getBytes(java.nio.charset.StandardCharsets.UTF_8));if(Json.bytes(row).length>48000)throw new IllegalArgumentException("legacy_player_row_limit");return id;
    }
}
