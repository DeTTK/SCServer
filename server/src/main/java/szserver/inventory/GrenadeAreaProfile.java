package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Plain values returned by the canonical throwable-effect factory, never a native world object. */
public record GrenadeAreaProfile(String target,double duration,boolean oneShot,String object,double damageMultiplier,
                                float radiusScale,float shield,float heal,boolean revive,String group,byte priority,
                                String visuals,String targetFx,Map<String,Float> stats) {
    public GrenadeAreaProfile {
        if(!Set.of("SELF","ALLIES","ENEMIES","ALL").contains(target)||!Double.isFinite(duration)||duration<0||duration>600||
            !Double.isFinite(damageMultiplier)||damageMultiplier<0||damageMultiplier>100||!Float.isFinite(radiusScale)||radiusScale<0||radiusScale>32||
            !Float.isFinite(shield)||shield<0||shield>100000||!Float.isFinite(heal)||Math.abs(heal)>100000||stats==null||stats.size()>128)throw new IllegalArgumentException("grenade_area_profile_budget:target="+target+",duration="+duration+",multiplier="+damageMultiplier+",scale="+radiusScale+",shield="+shield+",heal="+heal);
        for(String text:new String[]{object,group,visuals,targetFx})if(text!=null&&text.length()>1024)throw new IllegalArgumentException("grenade_area_text_budget");
        for(var e:stats.entrySet())if(e.getKey().isBlank()||e.getKey().length()>128||e.getValue()==null||!Float.isFinite(e.getValue()))throw new IllegalArgumentException("grenade_area_stat_budget");
        stats=Map.copyOf(stats);
    }
    public long durationMillis(){return (long)Math.ceil(duration*1000);}
    public JsonObject json(){JsonObject r=new JsonObject();r.addProperty("target",target);r.addProperty("duration",duration);r.addProperty("oneShot",oneShot);r.addProperty("object",object);r.addProperty("damageMultiplier",damageMultiplier);r.addProperty("radiusScale",radiusScale);r.addProperty("shield",shield);r.addProperty("heal",heal);r.addProperty("revive",revive);r.addProperty("group",group);r.addProperty("priority",priority);r.addProperty("visuals",visuals);r.addProperty("targetFx",targetFx);JsonObject b=new JsonObject();new TreeMap<>(stats).forEach(b::addProperty);r.add("stats",b);return r;}
    private static String optional(JsonObject r,String key){return !r.has(key)||r.get(key).isJsonNull()?null:r.get(key).getAsString();}
    private static boolean bool(JsonObject r,String key){if(!r.get(key).isJsonPrimitive()||!r.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException("grenade_area_boolean");return r.get(key).getAsBoolean();}
    public static GrenadeAreaProfile read(JsonObject r){
        Set<String> fields=new HashSet<>(Set.of("target","duration","oneShot","damageMultiplier","radiusScale","shield","heal","revive","priority","stats"));for(String optional:List.of("object","group","visuals","targetFx"))if(r.has(optional))fields.add(optional);if(!r.keySet().equals(fields))throw new IllegalArgumentException("grenade_area_profile_fields");
        JsonObject b=r.getAsJsonObject("stats");if(b.size()>128)throw new IllegalArgumentException("grenade_area_stat_limit");Map<String,Float> stats=new TreeMap<>();b.entrySet().forEach(e->stats.put(e.getKey(),e.getValue().getAsFloat()));int priority=Math.toIntExact(GrenadeReceipt.integer(r,"priority"));if(priority<Byte.MIN_VALUE||priority>Byte.MAX_VALUE)throw new IllegalArgumentException("grenade_area_priority");
        return new GrenadeAreaProfile(r.get("target").getAsString(),r.get("duration").getAsDouble(),bool(r,"oneShot"),optional(r,"object"),r.get("damageMultiplier").getAsDouble(),r.get("radiusScale").getAsFloat(),r.get("shield").getAsFloat(),r.get("heal").getAsFloat(),bool(r,"revive"),optional(r,"group"),(byte)priority,optional(r,"visuals"),optional(r,"targetFx"),stats);
    }
}
