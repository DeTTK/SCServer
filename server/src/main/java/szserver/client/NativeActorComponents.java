package szserver.client;

import szserver.world.World;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Warmed native component factory for dispatcher hosts. Every actor gets separate native properties,
 * inventory, status, weapon, energy and event objects. This factory does not load chunks or run AI. */
final class NativeActorComponents {
    private final ClassLoader loader;
    private final Object unsafe;
    private final Method allocate,position,box;
    private final Class<?> actorType,entity,player,living,contextType,flagsType,boxType;
    private final NativeActorDispatcher dispatcher;
    final NativeActorEquipment equipment;
    private final String[] strings;
    private Thread owner;
    private final NativeActorLife life;
    private final szserver.net.HealthRules healthRules;

    NativeActorComponents(Path runtime,Path definitions,Path registry,ClassLoader loader,java.util.function.BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate)throws Exception {
        ActorDispatcherRelease.verify(runtime);ActorLifeRelease.verify(runtime);this.loader=loader;this.unsafe=unsafe;this.allocate=allocate;
        entity=type("1217489680");player=type("200241649");living=type("820497227");contextType=type("358035993");flagsType=type("769798186");boxType=type("242639986");
        actorType=define.apply("szserver.bridge.ActorLivingProfile",NativeActorBridge.livingActor());life=new NativeActorLife(loader);
        healthRules=szserver.net.HealthRules.read(com.google.gson.JsonParser.parseString(szserver.util.ItemResources.gameplaySettings(definitions.resolveSibling("artifact-settings.json"))).getAsJsonObject());
        position=entity.getMethod("throw",double.class,double.class,double.class);box=boxType.getMethod("synchronized",double.class,double.class,double.class,double.class,double.class,double.class);
        type("600030218").getField("volatile").set(null,type("1974661934").getField("RU").get(null));
        Class<?> metadata=type("1993742576");if(metadata.getField("it").get(null)==null)metadata.getMethod("interface").invoke(null);
        strings=(String[])type("1281655698").getField("if").get(null);
        // This class must precede 1924679062 to avoid their original cyclic static initialization.
        NativeMedicineRelease.verify(runtime);
        type("964863275");type("1464336358");prepareItems(definitions);equipment=new NativeActorEquipment(loader,define,definitions,registry,new NativeActorBuffs(loader,definitions,registry));dispatcher=new NativeActorDispatcher(runtime,loader,equipment);
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
    private Object allocate(Class<?> type)throws Exception{return allocate.invoke(unsafe,type);}
    private static void set(Class<?> type,String name,Object owner,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private void prepareItems(Path path)throws Exception {
        if(RuntimeFiles.size(path)>32L*1024*1024)throw new IllegalArgumentException("actor_component_definitions_size");
        var definitions=szserver.inventory.CatalogDefinitions.read(path);
        Class<?> parserType=type("com.google.gson.JsonParser"),json=type("com.google.gson.JsonObject"),descriptor=type("1422486348");
        Object parser=parserType.getConstructor().newInstance();Object[] factories=(Object[])type("1366800141").getField("import").get(null);
        for(int id:new int[]{14987,21059,21085}) {
            Class<?> factory=type(id==14987?"942610364":"124763289");
            if(factories[id]!=null){if(!factory.isInstance(factories[id]))throw new IllegalStateException("actor_component_item_conflict:"+id);continue;}
            if(!definitions.has(Integer.toString(id)))throw new IllegalArgumentException("actor_component_item_missing:"+id);
            Object parsed=parserType.getMethod("parse",String.class).invoke(parser,definitions.getAsJsonObject(Integer.toString(id)).toString());parsed=parsed.getClass().getMethod("getAsJsonObject").invoke(parsed);
            Object data=descriptor.getConstructor(String.class,json,int.class,String.class,Integer.class,String.class).newInstance("npc_native_"+id,parsed,id,null,null,null);
            factory.getConstructor(descriptor).newInstance(data);
        }
    }
    void warm(Object nativeWorld)throws Exception{
        if(owner!=null)throw new IllegalStateException("actor_component_warmup_order");
        create(new World.Vec(0,1,0),nativeWorld);dispatcher.finishFactoryWarmup();owner=null;
    }
    void finishBootstrap(){if(owner!=null&&owner!=Thread.currentThread())throw new IllegalStateException("actor_component_bootstrap_owner");dispatcher.finishBootstrap();owner=null;}
    NativeActorDispatcher.Session create(World.Vec spawn,Object nativeWorld)throws Exception {
        return create(spawn,nativeWorld,UUID.randomUUID());
    }
    NativeActorDispatcher.Session create(World.Vec spawn,Object nativeWorld,UUID identity)throws Exception {
        if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("actor_component_owner");
        if(spawn==null||!Double.isFinite(spawn.x())||!Double.isFinite(spawn.y())||!Double.isFinite(spawn.z())||!type("1500514786").isInstance(nativeWorld))throw new IllegalArgumentException("actor_component_host");
        Object actor=allocate(actorType);set(entity,"enum",actor,type("1115").getConstructor().newInstance());set(entity,"interface",actor,nativeWorld);
        try{((NativeActorRandom)nativeWorld.getClass().getField("contactRandom").get(nativeWorld)).setSeed(identity.getMostSignificantBits()^Long.rotateLeft(identity.getLeastSignificantBits(),17));}catch(NoSuchFieldException noMapHost){/* Standalone dispatcher tests have no block-world random. */}
        entity.getMethod("float",UUID.class).invoke(actor,Objects.requireNonNull(identity));
        Object bounds=type("284743").getConstructor().newInstance();set(living,"const",actor,bounds);
        for(Field f:bounds.getClass().getDeclaredFields())if(f.getType()==boxType){f.setAccessible(true);double radius=.6f/2d;f.set(bounds,box.invoke(null,spawn.x()-radius,spawn.y(),spawn.z()-radius,spawn.x()+radius,spawn.y()+1.8f,spawn.z()+radius));}
        living.getMethod("if",Set.class).invoke(actor,new HashSet<>());position.invoke(actor,spawn.x(),spawn.y(),spawn.z());
        Object playerStorage=type("299188").getConstructor().newInstance();set(player,"finally",actor,playerStorage);Object flags=flagsType.getConstructor(player).newInstance(actor);
        for(Field f:playerStorage.getClass().getDeclaredFields())if(f.getType()==flagsType){f.setAccessible(true);f.set(playerStorage,flags);}
        Object context=contextType.getConstructor(player).newInstance(actor);actorType.getField("components").set(actor,context);set(player,"protected",actor,context);
        contextType.getField("class").set(context,type("1451253642").getConstructor(String.class).newInstance(identity.toString()));
        Object metadata=type("1993742576").getField("it").get(null),attribute=metadata.getClass().getField("iq").get(metadata);
        Object builder=attribute.getClass().getMethod("byte",contextType,Object.class).invoke(attribute,context,false);builder=builder.getClass().getMethod("finally").invoke(builder);contextType.getField("import").set(context,builder.getClass().getMethod("public").invoke(builder));
        // The original living constructor registers this flag before callbacks may call new().
        Object livingAttribute=metadata.getClass().getField("instanceof").get(metadata);
        Object livingBuilder=livingAttribute.getClass().getMethod("catch",type("1667320743"),Object.class).invoke(livingAttribute,actor,false);
        for(String method:new String[]{"finally","super","transient"})livingBuilder=livingBuilder.getClass().getMethod(method).invoke(livingBuilder);
        Object livingFlag=livingBuilder.getClass().getMethod("public").invoke(livingBuilder);
        bounds.getClass().getMethod("native",int.class,int.class,type("1798539107")).invoke(bounds,1557452155,-943478924,livingFlag);
        Field table=contextType.getField("void");table.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> components=(Map<String,Object>)table.get(context);
        components.put(strings[30326],type("1409908300").getConstructor(contextType).newInstance(context));
        components.put(strings[9355],type("1268372086").getConstructor(contextType).newInstance(context));
        components.put(strings[18927],type("208724657").getConstructor(contextType).newInstance(context));
        components.put(strings[4995],type("1448531254").getConstructor(contextType).newInstance(context));
        for(String name:new String[]{"596586228","1810277368"}){Class<?> t=type(name);components.put((String)t.getField(name.equals("596586228")?"instanceof":"void").get(null),t.getConstructor(contextType).newInstance(context));}
        // Native defaults, separate local-audio timers for each actor.
        Class<?> eventType=type("1429083604");Object defaults=eventType.getConstructor().newInstance(),events=allocate(type("szserver.bridge.ActorVelocityEvents"));
        for(Field field:eventType.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(events,field.get(defaults));}
        var session=dispatcher.create(context,events);life.prepare(actor,healthRules);session.initializeBuffs();return session;
    }
}
