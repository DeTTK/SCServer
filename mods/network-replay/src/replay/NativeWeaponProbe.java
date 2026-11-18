package replay;

import com.google.gson.*;
import java.lang.reflect.Array;
import java.util.*;

/** Read-only QA snapshot of canonical held-item selection and TP compatibility state. */
final class NativeWeaponProbe {
    static Integer item(Object stack)throws Exception {
        return stack==null?null:((Number)Native.call(stack,"continue","int")).intValue();
    }
    static List<String> profiles(JsonObject compiled,Integer item){
        List<String> result=new ArrayList<>();if(compiled==null||item==null||!compiled.has("profiles"))return result;
        for(var entry:compiled.getAsJsonObject("profiles").entrySet()){
            JsonObject profile=entry.getValue().getAsJsonObject();if(!profile.has("item_ids"))continue;
            for(JsonElement id:profile.getAsJsonArray("item_ids"))if(id.getAsInt()==item){result.add(entry.getKey());break;}
        }
        return result;
    }
    static Map<String,Object> snapshot(NativePackets.World replay)throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        Class<?> layer=Native.type("szgmod.SourceTpWeaponLayer"),fp=Native.type("szgmod.SourceWeaponLayer");
        Map<?,?> states=(Map<?,?>)Native.field(layer,"states");
        JsonObject compiled=(JsonObject)Native.field(fp,"compiled");
        result.put("seconds",ReplayClock.seconds);result.put("compiled_profiles_loaded",compiled!=null);
        for(String counter:List.of("queued","poseDraws","draws"))result.put(counter,Native.field(layer,counter));
        result.put("counter_scope","Global native layer lifetime, including prior reconstruction epochs; snapshot is outside individual draw scopes");
        List<Map<String,Object>> actors=new ArrayList<>();result.put("actors",actors);
        Object world=Native.call(replay.connection,"instanceof","1054393445"),local=Native.field(replay.game,"e");
        for(Object actor:(List<?>)Native.call(world,"const","java.util.List")){
            if(!Native.type("200241649").isInstance(actor))continue;
            if(actors.size()>=128){result.put("truncated",true);break;}
            Map<String,Object> row=new LinkedHashMap<>();actors.add(row);
            row.put("entity",Native.call(actor,"for","int"));row.put("local",actor==local);
            Object inventory=Native.call(Native.type("276406255"),"implements","276406255",actor);
            Integer equipment=item(Native.call(inventory,"public","211097523"));
            Integer visible=item(Native.call(Native.type("764970879"),"implements","211097523",actor));
            row.put("equipment_item",equipment);row.put("visible_item",visible);
            row.put("equipment_profiles",profiles(compiled,equipment));row.put("visible_profiles",profiles(compiled,visible));
            row.put("selector_mode",Native.call(Native.type("353451775"),"volatile","int",actor));
            row.put("selector_blocked",Native.call(Native.type("764970879"),"do","boolean",actor));
            Object state;synchronized(states){state=states.get(actor);}row.put("tp_state_present",state!=null);
            if(state==null)continue;
            Map<String,Object> nativeState=new LinkedHashMap<>();row.put("tp_state",nativeState);
            for(String field:List.of("item","path","action","clip","started","seconds","duration","locomotion"))nativeState.put(field,Native.field(state,field));
            for(String field:List.of("profile","carrierP","bodyMatrix","mesh"))nativeState.put(field+"_present",Native.field(state,field)!=null);
            for(String field:List.of("p","q","nativeNames")){Object values=Native.field(state,field);nativeState.put(field+"_count",values==null?0:Array.getLength(values));}
            JsonObject route=(JsonObject)Native.field(state,"route");nativeState.put("route_present",route!=null);
            if(route!=null)for(String key:List.of("enabled","status","donor"))if(route.has(key))nativeState.put("route_"+key,route.get(key).deepCopy());
        }
        return result;
    }
}
