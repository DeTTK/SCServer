package szserver.inventory;

import com.google.gson.*;
import szserver.world.*;
import java.util.*;

/** Atomic inventory debit marker. No native objects or connected-session identity are persisted. */
public record GrenadeReceipt(UUID character,UUID projectile,String name,int item,Grenades.Flight flight,
                             World.Vec origin,World.Vec velocity,long beforeRevision,long afterRevision,boolean held,List<GrenadeAreaProfile> areas,szserver.net.AreaRelations.Actor ownerContext) {
    public static final String PROGRESS="grenadeLastThrowV1";
    public GrenadeReceipt(UUID character,UUID projectile,String name,int item,Grenades.Flight flight,World.Vec origin,World.Vec velocity,long beforeRevision,long afterRevision){this(character,projectile,name,item,flight,origin,velocity,beforeRevision,afterRevision,false,List.of());}
    public GrenadeReceipt(UUID character,UUID projectile,String name,int item,Grenades.Flight flight,World.Vec origin,World.Vec velocity,long beforeRevision,long afterRevision,boolean held){this(character,projectile,name,item,flight,origin,velocity,beforeRevision,afterRevision,held,List.of());}
    public GrenadeReceipt(UUID character,UUID projectile,String name,int item,Grenades.Flight flight,World.Vec origin,World.Vec velocity,long beforeRevision,long afterRevision,boolean held,List<GrenadeAreaProfile> areas){this(character,projectile,name,item,flight,origin,velocity,beforeRevision,afterRevision,held,areas,null);}
    public GrenadeReceipt {
        if(areas==null||areas.size()>16)throw new IllegalArgumentException("grenade_receipt_area_limit");areas=List.copyOf(areas);
        Objects.requireNonNull(character);Objects.requireNonNull(projectile);Objects.requireNonNull(flight);
        if(ownerContext!=null&&!ownerContext.character().equals(character))throw new IllegalArgumentException("grenade_receipt_owner_context");
        if(name==null||name.isBlank()||name.length()>64||item<1||item>65535||beforeRevision<0||beforeRevision==Long.MAX_VALUE||afterRevision!=beforeRevision+1)throw new IllegalArgumentException("grenade_receipt_bounds");
        vector(origin,MapBounds.HORIZONTAL);vector(velocity,Grenades.MAX_COMPONENT_SPEED);
        if(held&&(flight.fuseTicks()!=1||velocity.x()!=0||velocity.y()!=0||velocity.z()!=0))throw new IllegalArgumentException("grenade_held_receipt");
    }
    public static void vector(World.Vec value,double limit){if(value==null||!Double.isFinite(value.x())||!Double.isFinite(value.y())||!Double.isFinite(value.z())||Math.abs(value.x())>limit||Math.abs(value.y())>limit||Math.abs(value.z())>limit)throw new IllegalArgumentException("grenade_receipt_vector");}
    public static JsonArray vec(World.Vec v){JsonArray a=new JsonArray();a.add(v.x());a.add(v.y());a.add(v.z());return a;}
    public static World.Vec vec(JsonArray a){if(a.size()!=3)throw new IllegalArgumentException("grenade_receipt_vector_size");return new World.Vec(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    public static long integer(JsonObject r,String key){try{return r.get(key).getAsBigDecimal().longValueExact();}catch(RuntimeException error){throw new IllegalArgumentException("grenade_receipt_integer",error);}}
    public JsonObject json(){JsonObject r=new JsonObject();r.addProperty("character",character.toString());r.addProperty("projectile",projectile.toString());r.addProperty("name",name);r.addProperty("item",item);r.addProperty("before",beforeRevision);r.addProperty("after",afterRevision);if(held)r.addProperty("held",true);r.add("origin",vec(origin));r.add("velocity",vec(velocity));JsonObject f=new JsonObject();f.addProperty("ticks",flight.fuseTicks());f.addProperty("gravity",flight.gravity());f.addProperty("bounce",flight.restitution());f.addProperty("friction",flight.friction());f.addProperty("drag",flight.drag());r.add("flight",f);if(!areas.isEmpty()){JsonArray a=new JsonArray();areas.forEach(p->a.add(p.json()));r.add("areas",a);}if(ownerContext!=null)r.add("ownerContext",ownerContext.json());return r;}
    public JsonObject progress(){JsonObject r=new JsonObject();r.add(PROGRESS,json());return r;}
    public static GrenadeReceipt read(JsonObject r){
        Set<String> fields=new HashSet<>(Set.of("character","projectile","name","item","before","after","origin","velocity","flight"));if(r.has("held")){fields.add("held");if(!r.get("held").isJsonPrimitive()||!r.getAsJsonPrimitive("held").isBoolean())throw new IllegalArgumentException("grenade_receipt_held_type");}if(r.has("areas"))fields.add("areas");if(r.has("ownerContext"))fields.add("ownerContext");if(!r.keySet().equals(fields))throw new IllegalArgumentException("grenade_receipt_fields");
        List<GrenadeAreaProfile> areas=new ArrayList<>();if(r.has("areas")){JsonArray a=r.getAsJsonArray("areas");if(a.size()>16)throw new IllegalArgumentException("grenade_receipt_area_limit");a.forEach(p->areas.add(GrenadeAreaProfile.read(p.getAsJsonObject())));}
        JsonObject f=r.getAsJsonObject("flight");if(!f.keySet().equals(Set.of("ticks","gravity","bounce","friction","drag")))throw new IllegalArgumentException("grenade_receipt_flight_fields");
        return new GrenadeReceipt(UUID.fromString(r.get("character").getAsString()),UUID.fromString(r.get("projectile").getAsString()),r.get("name").getAsString(),Math.toIntExact(integer(r,"item")),new Grenades.Flight(Math.toIntExact(integer(f,"ticks")),f.get("gravity").getAsDouble(),f.get("bounce").getAsDouble(),f.get("friction").getAsDouble(),f.get("drag").getAsDouble()),vec(r.getAsJsonArray("origin")),vec(r.getAsJsonArray("velocity")),integer(r,"before"),integer(r,"after"),r.has("held")&&r.get("held").getAsBoolean(),areas,r.has("ownerContext")?szserver.net.AreaRelations.Actor.read(r.getAsJsonObject("ownerContext")):null);
    }
    public static GrenadeReceipt pending(UUID id,JsonObject progress,long revision){if(!progress.has(PROGRESS))return null;GrenadeReceipt r=read(progress.getAsJsonObject(PROGRESS));if(!r.character().equals(id)||r.afterRevision()>revision)throw new IllegalArgumentException("grenade_receipt_identity_revision");return r;}
}
