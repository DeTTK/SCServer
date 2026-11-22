package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** A physical item remains independent of its former owner's character lifetime. */
public record WorldItem(UUID id,UUID creator,String world,ItemStack stack,double x,double y,double z,
                        double vx,double vy,double vz,long created,long expires,String pickupOwner) {
    public WorldItem {
        Objects.requireNonNull(id);Objects.requireNonNull(creator);Objects.requireNonNull(stack);Objects.requireNonNull(world);
        if(world.isBlank()||world.length()>256||created<0||expires<=created||expires-created>86_400_000L||pickupOwner!=null&&pickupOwner.length()>128)throw new IllegalArgumentException("world_item_bounds");
        for(double value:new double[]{x,y,z})if(!Double.isFinite(value)||Math.abs(value)>30_000_000)throw new IllegalArgumentException("world_item_position");
        for(double value:new double[]{vx,vy,vz})if(!Double.isFinite(value)||Math.abs(value)>100)throw new IllegalArgumentException("world_item_velocity");
    }
    public WorldItem resting(double x,double y,double z){return new WorldItem(id,creator,world,stack,x,y,z,0,0,0,created,expires,pickupOwner);}
    public JsonObject json(){JsonObject o=new JsonObject();o.addProperty("id",id.toString());o.addProperty("creator",creator.toString());o.addProperty("world",world);o.add("stack",stack.json());o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);o.addProperty("vx",vx);o.addProperty("vy",vy);o.addProperty("vz",vz);o.addProperty("created",created);o.addProperty("expires",expires);if(pickupOwner!=null)o.addProperty("pickupOwner",pickupOwner);return o;}
    public static WorldItem read(JsonObject o){return new WorldItem(UUID.fromString(o.get("id").getAsString()),UUID.fromString(o.get("creator").getAsString()),o.get("world").getAsString(),ItemStack.read(o.getAsJsonObject("stack")),o.get("x").getAsDouble(),o.get("y").getAsDouble(),o.get("z").getAsDouble(),o.get("vx").getAsDouble(),o.get("vy").getAsDouble(),o.get("vz").getAsDouble(),o.get("created").getAsLong(),o.get("expires").getAsLong(),o.has("pickupOwner")?o.get("pickupOwner").getAsString():null);}
}
