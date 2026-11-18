package replay;

import java.util.*;

/** Thread-scoped source ID translation; native registries are never mutated. */
public final class ReplayDecode {
    public interface Hooks {
        boolean replace(Object object,java.io.DataInput input)throws Exception;
        void after(Object object,java.io.DataInput input)throws Exception;
        default boolean apply(Object packet)throws Exception {return false;}
        default boolean itemDetails(Object definition,Object item,java.io.DataInput input)throws Exception {return false;}
        default Object gameObject(java.io.DataInput input)throws Exception {return null;}
        default boolean componentDetails(Object component,java.io.DataInput input)throws Exception {return false;}
        default void tail(Object entity,java.io.DataInput input,int flags)throws Exception {throw new java.io.IOException("Source motion tail handler unavailable");}
        default void deferredFailure(Throwable failure) {}
        default void discardedTail(int bytes) {}
        default void tailApplied(Object entity,long order,double received,double consumed,int flags,int bytes,String trigger)throws Exception {}
    }
    private static final ThreadLocal<Hooks> hooks=new ThreadLocal<>();
    private static final ThreadLocal<Map<String,Map<Integer,Integer>>> context=new ThreadLocal<>();
    public static Object nativeCopy(Object item)throws Exception {
        Map<String,Map<Integer,Integer>> maps=context.get();Hooks handler=hooks.get();
        // enum() serializes and reads an already native stack. Its property IDs
        // and display data must not pass through source translation again.
        context.remove();hooks.remove();
        try{return Native.call(item,"szreplay$original$enum","211097523");}
        finally{if(maps!=null)context.set(maps);if(handler!=null)hooks.set(handler);}
    }
    private static long tailOrder;
    private record Deferred(Map<String,Map<Integer,Integer>> maps,Hooks hooks,int flags,int length,int entity,long order,double received,java.lang.ref.WeakReference<Object> component,java.lang.ref.WeakReference<Object> snapshot) {}
    private record Task(Map<String,Map<Integer,Integer>> maps,Hooks hooks) {}
    private static final Map<Object,Task> tasks=Collections.synchronizedMap(new WeakHashMap<>());
    public static void captureTask(Object task){if(context.get()!=null&&hooks.get()!=null)tasks.put(task,new Task(context.get(),hooks.get()));}
    public static Object task(Object task,Object argument,String method,String returns)throws Exception {
        Task value=tasks.get(task);boolean scoped=value!=null&&context.get()==null;
        if(scoped){begin(value.maps);use(value.hooks);ReplayClock.beginDecode();}
        try{return Native.call(task,method,returns,argument);}
        catch(Exception|LinkageError failure){if(value!=null)value.hooks.deferredFailure(failure);throw failure;}
        finally{if(scoped){ReplayClock.endDecode();end();}}
    }
    private static final Map<Object,Deferred> deferred=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object,Hooks> localPlayers=Collections.synchronizedMap(new WeakHashMap<>());
    public static void bindLocal(Object player,Hooks owner){localPlayers.put(player,owner);}
    public static void clearLocal(Hooks owner){synchronized(localPlayers){localPlayers.values().removeIf(value->value==owner);}}
    public static boolean localController(Object player){return localPlayers.containsKey(player);}
    public static void defer(Object buffer,int flags,int length){defer(buffer,flags,length,-1,null,null);}
    public static void defer(Object buffer,int flags,int length,int entity,Object component,Object snapshot){Map<String,Map<Integer,Integer>> maps=context.get();Hooks handler=hooks.get();if(maps==null||handler==null)throw new IllegalStateException("Unscoped replay motion tail");synchronized(deferred){Deferred previous=deferred.get(buffer);if(previous!=null&&previous.length>0)throw new IllegalStateException("Native snapshot reused before consuming its recorded state");deferred.put(buffer,new Deferred(maps,handler,flags,length,entity,++tailOrder,ReplayClock.seconds,new java.lang.ref.WeakReference<>(component),new java.lang.ref.WeakReference<>(snapshot)));}}
    public static int settleTails(Hooks owner,Object world)throws Exception {
        return settleTails(owner,world,null);
    }
    /** Finish one actor's captured state before its pose is copied on removal. */
    public static int settleEntityTails(Hooks owner,Object world,int entity)throws Exception {
        return settleTails(owner,world,entity);
    }
    private static int settleTails(Hooks owner,Object world,Integer entityId)throws Exception {
        List<Map.Entry<Object,Deferred>> pending=new ArrayList<>();
        synchronized(deferred){for(var entry:deferred.entrySet())if(entry.getValue().hooks==owner&&entry.getValue().length>0&&(entityId==null||entry.getValue().entity==entityId))pending.add(Map.entry(entry.getKey(),entry.getValue()));}
        pending.sort(Comparator.comparingLong(entry->entry.getValue().order));int applied=0;
        for(var entry:pending){Deferred value=entry.getValue();Object buffer=entry.getKey(),component=value.component.get(),snapshot=value.snapshot.get();
            Object entity=Native.call(world,"continue","1217489680",value.entity);
            if(component==null||snapshot==null||entity==null||Native.call(component,"extends","820497227")!=entity){
                deferred.remove(buffer);owner.discardedTail(value.length);continue;
            }
            // Keep interpolation coordinates, phase and ring ownership intact.
            // Its later native consumption sees only pose flags, so it cannot
            // deserialize this already-applied source payload a second time.
            if(!tail(entity,buffer,entityId==null?"pause":"owner"))throw new IllegalStateException("Pending source state disappeared during pause completion");
            int flags=((Number)Native.field(snapshot,"int")).intValue();ReplayClock.field(snapshot,"int").setByte(snapshot,(byte)(flags&~(4|16)));applied++;
        }
        return applied;
    }
    public static List<Map<String,Object>> pendingTailDetails(Hooks owner)throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();synchronized(deferred){for(Deferred value:deferred.values())if(value.hooks==owner&&value.length>0){
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("entity",value.entity);entry.put("bytes",value.length);entry.put("source_flags",value.flags);entry.put("received_seconds",value.received);entry.put("order",value.order);
            Object component=value.component.get(),snapshot=value.snapshot.get();entry.put("component_present",component!=null);entry.put("snapshot_present",snapshot!=null);
            if(component!=null){entry.put("current",Native.call(component,"int","float"));entry.put("latest",Native.call(component,"volatile","float"));}
            if(snapshot!=null)entry.put("native_consumed",Native.field(snapshot,"this"));result.add(entry);
        }}return result;
    }
    public static long pendingTailBytes(Hooks owner){synchronized(deferred){long bytes=0;for(Deferred value:deferred.values())if(value.hooks==owner)bytes+=value.length;return bytes;}}
    public static void clearDeferred(Hooks owner){synchronized(deferred){deferred.values().removeIf(value->{if(value.hooks!=owner)return false;if(value.length>0)owner.discardedTail(value.length);return true;});}synchronized(tasks){tasks.values().removeIf(value->value.hooks==owner);}}
    public static boolean tail(Object entity,Object buffer)throws Exception {
        return tail(entity,buffer,"native");
    }
    private static boolean tail(Object entity,Object buffer,String trigger)throws Exception {
        Deferred value=deferred.remove(buffer);if(value==null)return false;boolean scoped=context.get()==null;
        if(scoped){begin(value.maps);use(value.hooks);ReplayClock.beginDecode();}
        try{
            byte[] bytes=(byte[])Native.field(buffer,"catch");if(value.length<0||value.length>bytes.length)throw new java.io.IOException("Invalid deferred motion buffer");
            Object bounded=Native.type("1754157392").getConstructor(byte[].class).newInstance((Object)Arrays.copyOf(bytes,value.length));
            value.hooks.tail(entity,(java.io.DataInput)bounded,value.flags);
            if(((Number)Native.field(bounded,"private")).intValue()!=value.length)throw new java.io.IOException("Unread source motion component bytes");
            ReplayClock.field(buffer,"private").setInt(buffer,value.length);
            value.hooks.tailApplied(entity,value.order,value.received,ReplayClock.seconds,value.flags,value.length,trigger);return true;
        }catch(Exception|LinkageError e){value.hooks.deferredFailure(e);throw e;}
        finally{if(scoped){ReplayClock.endDecode();end();}}
    }
    public static void begin(Map<String,Map<Integer,Integer>> maps){
        if(context.get()!=null)throw new IllegalStateException("Nested replay decode scope");
        context.set(maps);
    }
    public static void use(Hooks value){hooks.set(value);}
    /** Full visual component layouts, including internal native stack copies. */
    public static void visualProgressRead(Object component,java.io.DataInput input)throws Exception {
        if(!ReplayClock.active&&hooks.get()==null)return;
        Native.type("390888342").getField("szreplay$overrideProgress").setBoolean(component,input.readBoolean());
        Native.type("390888342").getField("szreplay$userProgress").setShort(component,input.readShort());
        Native.type("390888342").getField("szreplay$progress").setShort(component,input.readShort());
    }
    public static void visualProgressWrite(Object component,java.io.DataOutput output)throws Exception {
        if(!ReplayClock.active)return;
        output.writeBoolean((Boolean)Native.field(component,"szreplay$overrideProgress"));
        output.writeShort(((Number)Native.field(component,"szreplay$userProgress")).shortValue());
        output.writeShort(((Number)Native.field(component,"szreplay$progress")).shortValue());
    }
    public static boolean visualProgressEquals(boolean equal,Object self,Object other)throws Exception {
        if(!equal||!ReplayClock.active)return equal;
        for(String name:List.of("szreplay$overrideProgress","szreplay$userProgress","szreplay$progress"))if(!Native.field(self,name).equals(Native.field(other,name)))return false;
        return true;
    }
    public static void end(){context.remove();hooks.remove();}
    public static boolean read(Object object,java.io.DataInput input)throws Exception {Hooks value=hooks.get();try{return value!=null&&value.replace(object,input);}catch(Exception|LinkageError e){if(value!=null)value.deferredFailure(e);throw e;}}
    public static void after(Object object,java.io.DataInput input)throws Exception {Hooks value=hooks.get();if(value!=null)value.after(object,input);}
    public static boolean apply(Object packet)throws Exception {Hooks value=hooks.get();try{return value!=null&&value.apply(packet);}catch(Exception|LinkageError e){if(value!=null)value.deferredFailure(e);throw e;}}
    public static boolean itemDetails(Object definition,Object item,java.io.DataInput input)throws Exception {
        if(SceneEquipmentRead.active()){SceneEquipmentRead.install(item,input);return true;}
        Hooks value=hooks.get();try{return value!=null&&value.itemDetails(definition,item,input);}
        catch(Exception|LinkageError failure){if(value!=null)value.deferredFailure(failure);throw failure;}
    }
    public static Object gameObject(java.io.DataInput input)throws Exception {
        Hooks value=hooks.get();try{return value==null?null:value.gameObject(input);}
        catch(Exception|LinkageError failure){if(value!=null)value.deferredFailure(failure);throw failure;}
    }
    public static boolean componentDetails(Object component,java.io.DataInput input)throws Exception {
        Hooks value=hooks.get();try{return value!=null&&value.componentDetails(component,input);}
        catch(Exception|LinkageError failure){if(value!=null)value.deferredFailure(failure);throw failure;}
    }
    public static void consumed(Object buffer)throws Exception {
        if(context.get()==null)return;
        int remaining=((byte[])Native.field(buffer,"catch")).length-((Number)Native.field(buffer,"private")).intValue();
        if(remaining!=0)throw new java.io.IOException("Unread native entity bootstrap bytes: "+remaining);
    }
    public static int packet(int id){return translate("594335374",id);}
    public static int attribute(int id){return translate("entity_attributes",id);}
    public static int itemProperty(int id){return translate("item_properties",id & 255);}
    public static int entityType(int id){return translate("entity_types",id & 255);}
    public static int member(int id,Object registry){return translate(registry.getClass().getName(),id);}
    private static int translate(String registry,int id){
        Map<String,Map<Integer,Integer>> maps=context.get();if(maps==null)return id;
        Map<Integer,Integer> translations=maps.get(registry);
        Integer target=translations==null?null:translations.get(id);
        if(target==null)throw new IllegalArgumentException("Unmapped nested type: registry="+registry+" source="+id);
        return target;
    }
}
