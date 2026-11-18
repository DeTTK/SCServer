package replay;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;

/** Read-only identities of the actual initialized native attribute registry. */
final class RegistrySnapshot {
    static JsonObject read()throws Exception {
        Object registry=Native.field(Native.type("1993742576"),"it");
        TreeMap<Integer,JsonObject> entries=new TreeMap<>();
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        visit(registry,"",entries,seen);
        JsonObject result=new JsonObject();JsonArray values=new JsonArray();entries.values().forEach(values::add);result.add("entries",values);result.addProperty("ready",registry!=null);
        JsonArray properties=new JsonArray();Set<Integer> ids=new HashSet<>();Object items=Native.call(Native.type("241793746"),"while","241793746");
        for(Object codec:(List<?>)Native.call(items,"switch","java.util.List")){
            JsonObject property=new JsonObject();int id=Byte.toUnsignedInt(((Number)Native.field(codec,"native")).byteValue());if(!ids.add(id))throw new IllegalStateException("Duplicate item property "+id);
            property.addProperty("id",id);property.addProperty("codec_class",codec.getClass().getName());if(codec.getClass().getName().equals("500887208"))property.addProperty("name",(String)Native.field(codec,"while"));properties.add(property);
        }result.add("item_properties",properties);
        TreeMap<Integer,JsonObject> types=new TreeMap<>();
        for(Object definition:((Map<?,?>)Native.field(Native.type("423259911"),"implements")).values()){
            int id=Byte.toUnsignedInt(((Number)Native.field(definition,"super")).byteValue());JsonObject entry=new JsonObject();
            entry.addProperty("id",id);entry.addProperty("name",(String)Native.field(definition,"if"));entry.addProperty("class",((Class<?>)Native.field(definition,"continue")).getName());
            if(types.putIfAbsent(id,entry)!=null)throw new IllegalStateException("Duplicate entity type "+id);
        }
        JsonArray entityTypes=new JsonArray();types.values().forEach(entityTypes::add);result.add("entity_types",entityTypes);return result;
    }
    static void visit(Object value,String path,Map<Integer,JsonObject> entries,Set<Object> seen)throws Exception {
        if(value==null||!seen.add(value))return;
        String type=value.getClass().getName();
        if(type.equals("1961828072")){
            int id=Short.toUnsignedInt(((Number)Native.field(value,"while")).shortValue());
            JsonObject entry=new JsonObject();entry.addProperty("id",id);entry.addProperty("name",(String)Native.field(value,"break"));entry.addProperty("field",path);
            if(entries.putIfAbsent(id,entry)!=null)throw new IllegalStateException("Duplicate attribute identity "+id);return;
        }
        if(value instanceof Map<?,?> map){for(var e:map.entrySet())visit(e.getValue(),path+"["+e.getKey()+"]",entries,seen);return;}
        if(value instanceof Object[] array){for(int i=0;i<array.length;i++)visit(array[i],path+"["+i+"]",entries,seen);return;}
        if(!type.equals("1993742576")&&!type.equals("1949430380"))return;
        for(Field f:value.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);visit(f.get(value),path.isEmpty()?f.getName():path+"."+f.getName(),entries,seen);}
    }
}
