package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Runs the installed game's resource graph, factories, predicates and cosmetic slot methods. */
final class NativeCosmetics {
    private final ClassLoader loader;private final Map<Integer,String> definitions,classes;
    private final Map<Integer,Object> descriptors=new HashMap<>(),factories=new LinkedHashMap<>();
    private final Class<?> resource,json,base,skin,style,target,stack,kind;private final Object[] registry;
    NativeCosmetics(ClassLoader loader,Map<Integer,String> definitions,Map<Integer,String> classes)throws Exception{
        this.loader=loader;this.definitions=Map.copyOf(definitions);this.classes=Map.copyOf(classes);
        resource=type("1422486348");json=type("com.google.gson.JsonObject");base=type("1366800141");skin=type("1078890796");style=type("871813794");target=type("714221946");stack=type("211097523");kind=type("1847365303");registry=(Object[])base.getField("import").get(null);
        Object global=type("498192453").getField("long").get(null),manager=global.getClass().getField("class").get(global);
        Map<Integer,String> aliases=new HashMap<>((Map<Integer,String>)type("985191831").getField("package").get(manager));
        List<Object> rows=new ArrayList<>();for(var e:new TreeMap<>(definitions).entrySet()){JsonObject row=JsonParser.parseString(e.getValue()).getAsJsonObject();Object descriptor=descriptor(e.getKey(),row,aliases.get(e.getKey()),row.has("extends_item")?row.get("extends_item").getAsInt():null);descriptors.put(e.getKey(),descriptor);rows.add(descriptor);}
        // This is the original resource-loader merge and descendant-index construction.
        type("985191831").getMethod("else",Collection.class).invoke(manager,rows);
        for(int id:new TreeSet<>(classes.keySet())){Class<?> c=type(classes.get(id));if(skin.isAssignableFrom(c)||style.isAssignableFrom(c))factory(id);}
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private Object json(JsonObject row)throws Exception{Object parsed=type("com.google.gson.JsonParser").getMethod("parseString",String.class).invoke(null,row.toString());return parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);}
    private Object descriptor(int id,JsonObject row,String alias,Integer parent)throws Exception{return resource.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("server_cosmetic_"+id,json(row),id,alias,parent,null);}
    /** Resolve names with the game's parser, then restore the headless environment before any factory/event work. */
    private <T> T clientNames(java.util.concurrent.Callable<T> action)throws Exception{Class<?> env=type("220710426");Field f=env.getField("if");Object before=f.get(null);f.set(null,type("1553900928").getField("CLIENT").get(null));try{return action.call();}finally{f.set(null,before);}}
    private Object prepared(int id,Class<?> factoryType)throws Exception{
        JsonObject row=JsonParser.parseString(definitions.get(id)).getAsJsonObject();for(String key:new ArrayList<>(row.keySet())){JsonElement v=row.get(key);if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()&&v.getAsString().equalsIgnoreCase("<default>"))row.remove(key);}
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
        Class<?> type=type(name);Object descriptor=prepared(id,type),prior=registry[id];registry[id]=null;
        try{Object value=type.getConstructor(resource).newInstance(descriptor);if(skin.isInstance(value))skin.getMethod("implements",resource).invoke(value,descriptors.get(id));factories.put(id,value);return value;}finally{registry[id]=prior;}
    }
    private <T> T bound(java.util.concurrent.Callable<T> action)throws Exception{Map<Integer,Object> prior=new HashMap<>();for(var e:factories.entrySet()){prior.put(e.getKey(),registry[e.getKey()]);registry[e.getKey()]=e.getValue();}try{return action.call();}finally{for(var e:prior.entrySet())registry[e.getKey()]=e.getValue();}}
    record Skin(Object state,int previous,boolean unchanged){}
    Skin skin(int item,Object state,int selected)throws Exception{
        Class<?> visual=type("865153858");if(!visual.isInstance(state))throw new IOException("item_has_no_visual_name");if((Boolean)visual.getMethod("abstract").invoke(state))throw new IOException("visual_name_locked");
        Object definition=registry[item],replacement=selected==0?null:factory(selected);if(definition==null)throw new IOException("skin_target_factory_missing");if(replacement!=null&&(!skin.isInstance(replacement)||!(Boolean)skin.getMethod("long",base).invoke(replacement,definition)))throw new IOException("skin_incompatible");
        String previous=(String)visual.getMethod("transient").invoke(state),name=replacement==null?null:(String)skin.getField("new").get(replacement);boolean unchanged=Objects.equals(previous,name);
        Object old=previous==null?null:skin.getMethod("transient",String.class,base).invoke(null,previous,definition);int oldId=old==null?0:(Integer)base.getMethod("static").invoke(old);
        visual.getMethod("double",String.class).invoke(state,name);return new Skin(state,oldId,unchanged);
    }
    record Paint(Object state,byte[] previous,boolean builtin){}
    Paint paint(int item,Object state,String stateClass,int slot,byte[] raw,byte[] attached,boolean virtual)throws Exception{
        if(slot<0||slot>1)throw new IOException("paint_slot");Object definition=factory(item);if(!target.isInstance(definition))throw new IOException("paint_target_factory");Object selected=attached==null?null:factory(new DataInputStream(new ByteArrayInputStream(attached)).readUnsignedShort());Object key=kind.getField(slot==0?"VISUAL_STYLE":"MOTIF").get(null);
        if(selected!=null&&(!style.isInstance(selected)||!Arrays.stream(style.getMethods()).filter(m->m.getName().equals("implements")&&m.getParameterCount()==0&&m.getReturnType()==kind).findFirst().orElseThrow().invoke(selected).equals(key)||!(Boolean)style.getMethod("break",target).invoke(selected,definition)))throw new IOException("paint_target_incompatible");
        Class<?> flags=type("1156376641");if(!flags.isInstance(state))throw new IOException("paint_flags_missing");boolean wasVirtual=(Boolean)flags.getMethod(slot==0?"throw":"finally").invoke(state);
        return bound(()->{Object itemStack=stack.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw)),replacement=attached==null?null:stack.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(attached));Object previous=target.getMethod("implements",stack,kind).invoke(definition,itemStack,key);
            boolean builtin=wasVirtual||previous!=null&&(Boolean)style.getMethod("if",stack).invoke(null,previous);byte[] old=null;if(previous!=null){var out=new ByteArrayOutputStream();stack.getMethod("switch",DataOutput.class).invoke(previous,new DataOutputStream(out));old=out.toByteArray();}
            target.getMethod("throws",stack,kind,stack).invoke(definition,itemStack,key,replacement);Object changed=stack.getMethod("for",Class.class).invoke(itemStack,type(stateClass));flags.getMethod(slot==0?"assert":"private",boolean.class).invoke(changed,virtual&&replacement!=null);return new Paint(changed,old,builtin);
        });
    }
}
