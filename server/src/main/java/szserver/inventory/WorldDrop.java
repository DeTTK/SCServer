package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Immutable archival drop, matching the old server's world_drops semantics (no pickup entity). */
public record WorldDrop(UUID id,UUID character,double x,double y,double z,ItemStack stack,
                        int legacyMetadata,int legacyDamage,byte[] legacyState,String createdAt) {
    public WorldDrop {
        Objects.requireNonNull(id);Objects.requireNonNull(character);Objects.requireNonNull(stack);Objects.requireNonNull(createdAt);
        for(double value:new double[]{x,y,z})if(!Double.isFinite(value)||Math.abs(value)>30_000_000)throw new IllegalArgumentException("world_drop_position");
        if(legacyMetadata<0||legacyMetadata>65535||legacyDamage<0||legacyDamage>65535||legacyState!=null&&legacyState.length>8201||createdAt.length()>128)throw new IllegalArgumentException("world_drop_bounds");
        legacyState=legacyState==null?null:legacyState.clone();
    }
    @Override public byte[] legacyState(){return legacyState==null?null:legacyState.clone();}
    public static WorldDrop create(UUID character,double[] position,ItemStack stack){if(position==null||position.length!=3)throw new IllegalArgumentException("world_drop_position");return new WorldDrop(UUID.randomUUID(),character,position[0],position[1],position[2],stack,stack.metadata(),0,null,java.time.Instant.now().toString());}
    public JsonObject json(){JsonObject value=new JsonObject();value.addProperty("id",id.toString());value.addProperty("character",character.toString());value.addProperty("x",x);value.addProperty("y",y);value.addProperty("z",z);value.add("stack",stack.json());value.addProperty("legacyMetadata",legacyMetadata);value.addProperty("legacyDamage",legacyDamage);if(legacyState!=null)value.addProperty("legacyState",Base64.getEncoder().encodeToString(legacyState));value.addProperty("createdAt",createdAt);return value;}
    public static WorldDrop read(JsonObject v){return new WorldDrop(UUID.fromString(v.get("id").getAsString()),UUID.fromString(v.get("character").getAsString()),v.get("x").getAsDouble(),v.get("y").getAsDouble(),v.get("z").getAsDouble(),ItemStack.read(v.getAsJsonObject("stack")),v.get("legacyMetadata").getAsInt(),v.get("legacyDamage").getAsInt(),v.has("legacyState")?Base64.getDecoder().decode(v.get("legacyState").getAsString()):null,v.get("createdAt").getAsString());}
}
