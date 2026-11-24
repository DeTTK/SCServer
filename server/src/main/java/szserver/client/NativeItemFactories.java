package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Original resource graph and scoped genuine item factories; shared by cosmetic and medicine adapters. */
class NativeItemFactories {
    protected final ClassLoader loader;protected final Map<Integer,String> definitions,classes;
    protected final Map<Integer,Object> descriptors=new HashMap<>(),factories=new LinkedHashMap<>();
    protected final Class<?> resource,json,base,skin,style,stack;protected final Object[] registry;
    NativeItemFactories(ClassLoader loader,Map<Integer,String> definitions,Map<Integer,String> classes)throws Exception{
        this.loader=loader;this.definitions=Map.copyOf(definitions);this.classes=Map.copyOf(classes);
        resource=type("1422486348");json=type("com.google.gson.JsonObject");base=type("1366800141");skin=type("1078890796");style=type("871813794");stack=type("211097523");registry=(Object[])base.getField("import").get(null);
        Object global=type("498192453").getField("long").get(null),manager=global.getClass().getField("class").get(global);
        Map<Integer,String> aliases=new HashMap<>((Map<Integer,String>)type("985191831").getField("package").get(manager));
        List<Object> rows=new ArrayList<>();for(var e:new TreeMap<>(definitions).entrySet()){JsonObject row=JsonParser.parseString(e.getValue()).getAsJsonObject();Object descriptor=descriptor(e.getKey(),row,aliases.get(e.getKey()),row.has("extends_item")?row.get("extends_item").getAsInt():null);descriptors.put(e.getKey(),descriptor);rows.add(descriptor);}
        // This is the original resource-loader merge and descendant-index construction.
        type("985191831").getMethod("else",Collection.class).invoke(manager,rows);
    }
    protected Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private Object json(JsonObject row)throws Exception{Object parsed=type("com.google.gson.JsonParser").getMethod("parseString",String.class).invoke(null,row.toString());return parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);}
    private Object descriptor(int id,JsonObject row,String alias,Integer parent)throws Exception{return resource.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("server_cosmetic_"+id,json(row),id,alias,parent,null);}
    /** Resolve names with the game's parser, then restore the headless environment before any factory/event work. */
    private <T> T clientNames(java.util.concurrent.Callable<T> action)throws Exception{Class<?> env=type("220710426");Field f=env.getField("if");Object before=f.get(null);f.set(null,type("1553900928").getField("CLIENT").get(null));try{return action.call();}finally{f.set(null,before);}}
    private Object prepared(int id,Class<?> factoryType)throws Exception{
        JsonObject row=JsonParser.parseString(definitions.get(id)).getAsJsonObject();
        if (szserver.util.ItemResources.manages(row)) {
            // The native manager has resolved extends_item and resource defaults into this descriptor.
            Object resolved = resource.getField("strictfp").get(descriptors.get(id));
            row = JsonParser.parseString(resolved.toString()).getAsJsonObject();
        }
        for(String key:new ArrayList<>(row.keySet())){JsonElement v=row.get(key);if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()&&v.getAsString().equalsIgnoreCase("<default>"))row.remove(key);}
        if(style.isAssignableFrom(factoryType)&&row.has("applicable_items")){
            JsonObject normalized=new JsonObject();for(var e:row.getAsJsonObject("applicable_items").entrySet()){
                Collection<?> ids=(Collection<?>)clientNames(()->style.getMethod("default",String.class).invoke(null,e.getKey()));List<String> keys=new ArrayList<>();for(Object value:ids)keys.add(value.toString());normalized.add(String.join(",",keys),e.getValue());
            }row.add("applicable_items",normalized);
        }
        if(row.has("default_visuals_id")){Object original=descriptor(id,row,null,null);int[] ids=(int[])clientNames(()->resource.getMethod("else",String.class).invoke(original,"default_visuals_id"));JsonArray resolved=new JsonArray();for(int value:ids)resolved.add(value);row.add("default_visuals_id",resolved);}
        // Same headless collision-loader omission as the existing armor-stat adapter.
        if(type("1598274006").isAssignableFrom(factoryType))row.addProperty("trace_model","");
        if(row.has("slot_size")){String[] size=row.get("slot_size").getAsString().split("x");Class<?> geometry=type("1824362651");geometry.getMethod("catch",int.class,geometry).invoke(null,id,geometry.getConstructor(float.class,float.class).newInstance(Float.parseFloat(size[0]),Float.parseFloat(size[1])));}
        return descriptor(id,row,null,null);
    }
    Object factory(int id)throws Exception{
        Object cached=factories.get(id);if(cached!=null)return cached;String name=classes.get(id);if(name==null||!descriptors.containsKey(id))throw new IOException("cosmetic_factory_missing:"+id);
        if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IOException("cosmetic_factory_on_owner");
        Class<?> type=type(name);Object existing=registry[id];if(existing!=null&&existing.getClass()==type){factories.put(id,existing);return existing;}Object descriptor=prepared(id,type),prior=registry[id];registry[id]=null;
        try{Object value=type.getConstructor(resource).newInstance(descriptor);if(skin.isInstance(value))skin.getMethod("implements",resource).invoke(value,descriptors.get(id));factories.put(id,value);return value;}finally{registry[id]=prior;}
    }
    <T> T bound(java.util.concurrent.Callable<T> action)throws Exception{return bound(factories.keySet(),action);}
    <T> T bound(Collection<Integer> ids,java.util.concurrent.Callable<T> action)throws Exception{Map<Integer,Object> prior=new HashMap<>();for(int id:ids){Object factory=factories.get(id);if(factory==null)continue;prior.put(id,registry[id]);registry[id]=factory;}try{return action.call();}finally{for(var e:prior.entrySet())registry[e.getKey()]=e.getValue();}}
}
