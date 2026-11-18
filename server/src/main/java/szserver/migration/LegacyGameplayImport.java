package szserver.migration;

import com.google.gson.*;
import szserver.client.GameItems.Medicine;
import szserver.net.CharacterEffects;
import szserver.persistence.CharacterLocations;
import szserver.util.Json;
import java.nio.file.*;
import java.util.*;

/** Actual GameplayRuntime tables. NativeAuthority HP remains an independent compatibility mirror. */
public final class LegacyGameplayImport {
    private LegacyGameplayImport(){}
    private static UUID owner(JsonObject row,Set<UUID> owners){String raw=Json.text(row,"character_id",36);UUID id=UUID.fromString(raw);if(!id.toString().equals(raw)||!owners.contains(id))throw new IllegalArgumentException("legacy_gameplay_owner");return id;}
    private static float number(JsonObject row,String key,float min,float max){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_gameplay_number");float number=value.getAsFloat();if(!Float.isFinite(number)||number<min||number>max)throw new IllegalArgumentException("legacy_gameplay_number");return number;}
    private static long wall(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_effect_wall");try{return value.getAsBigDecimal().multiply(java.math.BigDecimal.valueOf(1000)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_effect_wall",error);}}
    public static JsonObject effects(JsonObject row,Map<Integer,Medicine> catalog,long now){
        JsonObject old=Json.parse(Json.text(row,"data",48000).getBytes(java.nio.charset.StandardCharsets.UTF_8),48000);JsonArray source=old.has("effects")?old.getAsJsonArray("effects"):new JsonArray();JsonObject groups=old.has("cooldowns")?old.getAsJsonObject("cooldowns"):new JsonObject();
        if(source.size()>32||groups.size()>64)throw new IllegalArgumentException("legacy_effect_limit");JsonArray effects=new JsonArray();int next=1_000_000;Set<Integer> seen=new HashSet<>();
        for(JsonElement value:source){JsonObject effect=value.getAsJsonObject();long end=wall(effect.get("expires_wall"));if(end<=now)continue;int id=Json.integer(effect,"id",1_000_000,Integer.MAX_VALUE-1),item=Json.integer(effect,"item_id",0,65535);if(!seen.add(id)||!catalog.containsKey(item))throw new IllegalArgumentException("legacy_effect_identity");
            JsonObject converted=new JsonObject();converted.addProperty("id",id);converted.addProperty("item",item);converted.addProperty("until",end);JsonObject definition=new JsonObject();definition.addProperty("group",Json.text(effect,"group",128));definition.addProperty("priority",Json.integer(effect,"priority",Integer.MIN_VALUE,Integer.MAX_VALUE));definition.add("stats",effect.getAsJsonObject("stats").deepCopy());converted.add("definition",definition);effects.add(converted);next=Math.max(next,id+1);
        }
        JsonObject cooldowns=new JsonObject();for(var entry:groups.entrySet()){if(entry.getKey().isEmpty()||entry.getKey().length()>128)throw new IllegalArgumentException("legacy_effect_group");long end=wall(entry.getValue());if(end>now)cooldowns.addProperty(entry.getKey(),end);}
        JsonObject result=new JsonObject();result.addProperty("nextEffect",next);result.add("effects",effects);result.add("cooldowns",cooldowns);return CharacterEffects.read(result,catalog,now).json();
    }
    public record Plan(LegacyEventPlan events,int rows,int batches) implements AutoCloseable {
        public void install(CharacterLocations locations)throws Exception{events.visit(event->{for(var value:event.getAsJsonArray("rows")){JsonObject row=value.getAsJsonObject();locations.observeState(UUID.fromString(row.get("scope").getAsString()),CharacterLocations.PlayerState.read(row.getAsJsonObject("state")));}locations.flush().get();});}
        public void close()throws Exception{events.close();}
    }
    public static Plan readVitals(LegacySnapshot snapshot,Set<UUID> owners,Path scratch)throws Exception{
        try(var out=new LegacyEventPlan.Builder(scratch,"legacy-vitals-");var sorted=new LegacySortedRows(out.directory,10)){
            int[] count={0};snapshot.visit("character_vitals",row->{UUID id=owner(row,owners);var state=new CharacterLocations.PlayerState(0,0,number(row,"health",0,10000),6,false,0,number(row,"stamina",0,100));JsonObject converted=new JsonObject();converted.addProperty("object_id",id.toString());converted.add("state",state.json());sorted.add(converted);count[0]++;});
            JsonObject event=new JsonObject();JsonArray batch=new JsonArray();event.add("rows",batch);int[] batches={0};sorted.visit(value->{JsonObject row=new JsonObject();row.addProperty("scope",value.get("object_id").getAsString());row.add("state",value.get("state"));batch.add(row);if(batch.size()==CharacterLocations.BATCH){out.append(event);while(!batch.isEmpty())batch.remove(batch.size()-1);batches[0]++;}});if(!batch.isEmpty()){out.append(event);batches[0]++;}sorted.close();return new Plan(out.seal(),count[0],batches[0]);
        }
    }
}
