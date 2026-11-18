package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Durable death identity. A grenade can revive only the exact death captured by its source. */
public record CharacterRevival(UUID death,boolean active) {
 public static final String KEY="grenadeRevival";
 public CharacterRevival {Objects.requireNonNull(death);}
 public CharacterRevival revive(UUID expected){if(!active||!death.equals(expected))throw new IllegalArgumentException("grenade_revive_death_changed");return new CharacterRevival(death,false);}
 public JsonObject json(){JsonObject row=new JsonObject();row.addProperty("version",1);row.addProperty("death",death.toString());row.addProperty("active",active);return row;}
 public static Optional<CharacterRevival> read(JsonObject progress){if(!progress.has(KEY))return Optional.empty();JsonObject row=progress.getAsJsonObject(KEY);if(!row.keySet().equals(Set.of("version","death","active"))||GrenadeReceipt.integer(row,"version")!=1||!row.get("active").isJsonPrimitive()||!row.getAsJsonPrimitive("active").isBoolean())throw new IllegalArgumentException("grenade_revive_state");return Optional.of(new CharacterRevival(UUID.fromString(row.get("death").getAsString()),row.get("active").getAsBoolean()));}
}
