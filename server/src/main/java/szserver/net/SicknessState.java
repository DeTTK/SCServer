package szserver.net;

import com.google.gson.*;
import java.util.*;

/** Optional vitals state, saved atomically with HP on the existing location worker. */
public record SicknessState(long savedAt,Map<String,SicknessRules.State> states){
    private static final Set<String> KINDS=Set.of("RADIATION","THERMAL","BIOLOGICAL","PSYCHO","ABNORMAL","BLEEDING","TOXIC","FROST","STOPPING","COMBUSTION","GONER");
    public static final SicknessState EMPTY=new SicknessState(0,Map.of());
    public SicknessState{states=Map.copyOf(states);if(savedAt<0||states.size()>11||!KINDS.containsAll(states.keySet())||!states.isEmpty()&&savedAt==0)throw new IllegalArgumentException("sickness_snapshot");}
    public JsonObject json(){JsonObject r=new JsonObject();r.addProperty("savedAt",savedAt);JsonObject values=new JsonObject();new TreeMap<>(states).forEach((kind,s)->{JsonObject v=new JsonObject();v.addProperty("power",s.power());v.addProperty("damageLeft",s.damageLeft());v.addProperty("resetLeft",s.resetLeft());values.add(kind,v);});r.add("states",values);return r;}
    public static SicknessState read(JsonObject r){if(!r.keySet().equals(Set.of("savedAt","states")))throw new IllegalArgumentException("sickness_snapshot_fields");JsonObject raw=r.getAsJsonObject("states");if(raw.size()>11)throw new IllegalArgumentException("sickness_snapshot_limit");Map<String,SicknessRules.State> values=new TreeMap<>();raw.entrySet().forEach(e->{JsonObject v=e.getValue().getAsJsonObject();if(!v.keySet().equals(Set.of("power","damageLeft","resetLeft")))throw new IllegalArgumentException("sickness_snapshot_state_fields");values.put(e.getKey(),new SicknessRules.State(v.get("power").getAsFloat(),szserver.util.Json.integer(v,"damageLeft",0,12000),szserver.util.Json.integer(v,"resetLeft",0,12000)));});return new SicknessState(r.get("savedAt").getAsBigDecimal().longValueExact(),values);}
    public SicknessState restored(SicknessRules rules,long now){if(now<0)throw new IllegalArgumentException("sickness_restore_clock");Map<String,SicknessRules.State> after=new TreeMap<>();long ticks=Math.max(0,now-savedAt)/50;states.forEach((kind,s)->{var value=rules.offline(kind,s,ticks);if(value.power()>0)after.put(kind,value);});return after.isEmpty()?EMPTY:new SicknessState(now,after);}
}
