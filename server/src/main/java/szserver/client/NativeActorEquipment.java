package szserver.client;

import com.google.gson.*;
import szserver.world.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;

/** Real factories/states are prepared on a worker; applying equipment swaps already prepared data. */
final class NativeActorEquipment {
    private final ClassLoader loader;private final JsonObject definitions,registry;private final NativeActorLife life;final NativeActorBuffs buffs;
    private final Object[] factories;private final Class<?> gun,module,stackType,property,modularState;
    private final Constructor<?> descriptor,stackReader;private final Method parseJson,setProperty,aimSpeed,propertyValue;
    private final Field held;private final Map<Integer,Object> realFactories=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Integer> passiveFactories=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record Prepared(NativeActorEquipment owner,ActorEquipment settings,Object stack,java.util.concurrent.atomic.AtomicReference<Object> actor) implements ActorControllerEngine.PreparedEquipment {public ActorPresentation presentation(){return settings.presentation();}}
    NativeActorEquipment(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Path definitions,Path registry,NativeActorBuffs buffs)throws Exception{
        this.loader=loader;this.buffs=buffs;this.life=new NativeActorLife(loader);this.registry=metadata(registry);
        Path overrides=registry.resolveSibling("client-current-item-state-overrides.json");if(Files.isRegularFile(overrides))for(var e:metadata(overrides).entrySet())this.registry.add(e.getKey(),e.getValue());
        factories=(Object[])type("1366800141").getField("import").get(null);gun=type("1745367406");module=type("62393481");modularState=type("822754569");stackType=type("211097523");property=type("1798539107");held=type("szserver.bridge.ActorSpeedProfile").getField("heldItem");
        descriptor=type("1422486348").getConstructor(String.class,type("com.google.gson.JsonObject"),int.class,String.class,Integer.class,String.class);
        parseJson=type("com.google.gson.JsonParser").getMethod("parse",String.class);stackReader=stackType.getConstructor(DataInput.class);setProperty=property.getMethod("assert",Object.class);
        aimSpeed=Arrays.stream(gun.getMethods()).filter(m->m.getName().equals("catch")&&m.getReturnType()==float.class&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{stackType})).findFirst().orElseThrow();
        propertyValue=Arrays.stream(property.getMethods()).filter(m->m.getName().equals("continue")&&m.getReturnType()==Object.class&&m.getParameterCount()==0).findFirst().orElseThrow();
        this.definitions=movementDefinitions(definitions);
        Map<String,Constructor<?>> constructors=new HashMap<>();
        for(var e:this.registry.entrySet())if(e.getValue().getAsJsonObject().has("state")){
            int id=Integer.parseInt(e.getKey());if(id<4096||factories[id]!=null)continue;String kind=e.getValue().getAsJsonObject().get("state").getAsString();
            Constructor<?> constructor=constructors.get(kind);if(constructor==null){String name="szserver.bridge.ActorItemFactory_"+kind;constructor=define.apply(name,ClientStateAdapter.factoryBytecode(name.replace('.','/'),kind)).getConstructor(int.class);constructors.put(kind,constructor);}constructor.newInstance(id);
        }
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    /** Discard default-state payloads: a controller needs factory/state identities, not another item database. */
    private static JsonObject metadata(Path path)throws Exception{
        var result=new JsonObject();Map<String,JsonPrimitive> names=new HashMap<>();
        try(var text=Files.newBufferedReader(path);var in=new com.google.gson.stream.JsonReader(text)){in.setLenient(true);in.beginObject();while(in.hasNext()){String id=in.nextName();var row=new JsonObject();in.beginObject();while(in.hasNext()){String key=in.nextName();if(key.equals("definition")||key.equals("state")){String name=in.nextString();row.add(key,names.computeIfAbsent(name,JsonPrimitive::new));}else in.skipValue();}in.endObject();result.add(id,row);}in.endObject();}return result;
    }
    /** Only the factories used by native held-item movement need to stay resident here. */
    private JsonObject movementDefinitions(Path path)throws Exception{
        var result=new JsonObject();
        Map<String,Boolean> classes=new HashMap<>();
        for(var entry:szserver.inventory.CatalogDefinitions.read(path).entrySet()){
            var row=registry.getAsJsonObject(entry.getKey());
            if(row==null||!row.has("definition"))continue;
            String name=row.get("definition").getAsString();
            Boolean active=classes.get(name);
            if(active==null){
                Class<?> type=Class.forName(name,false,loader);
                active=gun.isAssignableFrom(type)||module.isAssignableFrom(type);
                classes.put(name,active);
            }
            if(active)result.add(entry.getKey(),entry.getValue());
        }
        return result;
    }
    private static void worker(){if(Set.of("world-tick","native-game-owner").contains(Thread.currentThread().getName()))throw new IllegalStateException("actor_equipment_prepare_on_owner");}
    private void factory(int id,boolean required)throws Exception{
        if(realFactories.containsKey(id)||!required&&passiveFactories.contains(id))return;worker();synchronized(realFactories){if(realFactories.containsKey(id)||!required&&passiveFactories.contains(id))return;var row=registry.getAsJsonObject(Integer.toString(id));
        if(row==null||!row.has("definition"))throw new IllegalArgumentException("actor_held_definition_missing:"+id);
        Class<?> definition=type(row.get("definition").getAsString());if(required&&!gun.isAssignableFrom(definition))throw new IllegalArgumentException("actor_held_weapon_required");if(!gun.isAssignableFrom(definition)&&!module.isAssignableFrom(definition)){passiveFactories.add(id);return;}
        var data=definitions.getAsJsonObject(Integer.toString(id));if(data==null)throw new IllegalArgumentException("actor_held_definition_missing:"+id);
        Object parser=type("com.google.gson.JsonParser").getConstructor().newInstance();Object json=parseJson.invoke(parser,data.toString());json=json.getClass().getMethod("getAsJsonObject").invoke(json);
        Object previous=factories[id];try{factories[id]=null;Object factory=definition.getConstructor(descriptor.getDeclaringClass()).newInstance(descriptor.newInstance("npc_held_"+id,json,id,null,null,null));factories[id]=factory;realFactories.put(id,factory);}catch(Exception|LinkageError failure){factories[id]=previous;throw failure;}}
    }
    Object decode(byte[] raw)throws Exception{
        if(raw.length==0)return null;if(raw.length<9||raw.length>8192)throw new IOException("actor_held_size");factory(new DataInputStream(new ByteArrayInputStream(raw)).readUnsignedShort(),true);
        var in=new BoundedNativeInput(raw);Object result=stackReader.newInstance(in);if(in.available()!=0)throw new IOException("actor_held_trailing");modules(result,0,new int[1]);return result;
    }
    private void modules(Object stack,int depth,int[] count)throws Exception{
        if(depth>8||++count[0]>64)throw new IOException("actor_held_modules_budget");
        Object state=stackType.getMethod("for",Class.class).invoke(stack,modularState);if(state==null)return;
        Method getter=Arrays.stream(state.getClass().getMethods()).filter(m->m.getName().equals("for")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        Method id=Arrays.stream(stackType.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==int.class).findFirst().orElseThrow();
        for(Object child:((Map<?,?>)getter.invoke(state)).values()){factory((Integer)id.invoke(child),false);modules(child,depth+1,count);}
    }
    ActorControllerEngine.PreparedEquipment prepare(ActorEquipment settings)throws Exception{
        worker();byte[] raw=settings.held()==null?new byte[0]:szserver.net.InventoryPackets.stack(settings.held());
        Object stack=decode(raw.length==0?raw:Arrays.copyOfRange(raw,1,raw.length));
        // Traverse the original movement accessor now: never discover an unprepared graph on an owner.
        if(stack!=null)aimSpeed.invoke(factories[settings.held().id()],stack);
        return new Prepared(this,settings,stack,new java.util.concurrent.atomic.AtomicReference<>());
    }
    private Object component(Object actor,String name,String key)throws Exception{
        Object context=type("200241649").getMethod("byte").invoke(actor);Field table=type("358035993").getField("void");table.setAccessible(true);return ((Map<?,?>)table.get(context)).get(type(name).getField(key).get(null));
    }
    void apply(Object controller,Object actor,ActorControllerEngine.PreparedEquipment input)throws Exception{
        if(!(input instanceof Prepared prepared)||prepared.owner()!=this)throw new IllegalArgumentException("actor_equipment_loader");
        if(prepared.actor().get()!=actor&&!prepared.actor().compareAndSet(null,actor))throw new IllegalArgumentException("actor_equipment_already_bound");
        var p=prepared.settings().movement();Object config=type("1704556748").getField("abstract").get(controller);
        for(var e:Map.of("super",p.baseSpeed(),"native",p.sprintBonus(),"while",p.speedMultiplier(),"static",prepared.settings().energyCapacity()).entrySet())type("632909433").getField(e.getKey()).setFloat(config,e.getValue());
        type("632909433").getField("case").setBoolean(config,p.encumbered());
        Object stats=component(actor,"596586228","instanceof");setProperty.invoke(type("596586228").getField("while").get(stats),p.statusMultiplier());setProperty.invoke(type("596586228").getField("for").get(stats),p.conditionFactor());held.set(actor,prepared.stack());
        buffs.equipment(actor,controller,prepared.settings());
    }
    void aiming(Object actor,boolean aiming)throws Exception{Object weapon=component(actor,"1810277368","void");setProperty.invoke(type("1810277368").getField("assert").get(weapon),aiming);}
    boolean aiming(Object actor)throws Exception{Object weapon=component(actor,"1810277368","void");return (Boolean)propertyValue.invoke(type("1810277368").getField("assert").get(weapon));}
    static byte[] held(Object actor)throws Exception{Object stack=actor.getClass().getField("heldItem").get(actor);if(stack==null)return new byte[0];var out=new ByteArrayOutputStream();stack.getClass().getMethod("switch",DataOutput.class).invoke(stack,new DataOutputStream(out));if(out.size()>8192)throw new IOException("actor_held_size");return out.toByteArray();}
}
