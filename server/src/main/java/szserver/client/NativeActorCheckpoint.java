package szserver.client;

import java.lang.reflect.*;
import java.util.*;

/** In-memory transaction for the dispatcher and its owned kinematic data.
 * Preserves aliases/transient state as well as native wire fields. World/entity effects need their own transactions. */
final class NativeActorCheckpoint {
    private record Slot(Object owner,Field field,Object value){}
    private record ArrayValue(Object owner,Object values){}
    private record CollectionValue(Collection<Object> owner,List<Object> values){}
    private record MapValue(Map<Object,Object> owner,Map<Object,Object> values){}
    private final List<Slot> slots=new ArrayList<>();private final List<ArrayValue> arrays=new ArrayList<>();
    private final List<CollectionValue> collections=new ArrayList<>();private final List<MapValue> maps=new ArrayList<>();
    private final Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Class<?> module,state,box,property;
    private final Object actor;
    private static final Set<String> OWNED=Set.of("1704556748","1899091998","1115","284743","299188","769798186","4977","1166095647","153304200","1362694342","1158354165","1276542651","1429083604","632909433","1543383034","1585574065","1338767630","1767064101","930637873","1140796292","1525692938","1536302723");
    NativeActorCheckpoint(ClassLoader loader,Object controller,Object actor,Object input)throws Exception {
        this.actor=actor;module=Class.forName("568168347",true,loader);state=Class.forName("1943200621",true,loader);box=Class.forName("242639986",true,loader);property=Class.forName("1798539107",true,loader);capture(controller);capture(input);
    }
    @SuppressWarnings("unchecked") private void capture(Object value)throws Exception {
        if(value==null||value instanceof Enum<?>||value instanceof Class<?>||seen.contains(value))return;
        Class<?> type=value.getClass();boolean container=type.isArray()||value instanceof Collection<?>||value instanceof Map<?,?>;
        // Native property stores implement small generated numeric interfaces (e.g. 4977 -> 64911 -> 88551).
        // Copy their fields too; retaining only the outer flags object misses encoded writes made by state.do(actor).
        boolean encodedStore=type.getClassLoader()==state.getClassLoader()&&type.getName().matches("[0-9]{1,6}");
        if(!container&&!encodedStore&&value!=actor&&!module.isInstance(value)&&!state.isInstance(value)&&!box.isInstance(value)&&!property.isInstance(value)&&!OWNED.contains(type.getName()))return;
        if(seen.size()>=8192)throw new IllegalStateException("actor_checkpoint_graph_limit");seen.add(value);
        if(type.isArray()){
            int size=Array.getLength(value);if(size>8192)throw new IllegalStateException("actor_checkpoint_array_limit");Object copy=Array.newInstance(type.componentType(),size);System.arraycopy(value,0,copy,0,size);arrays.add(new ArrayValue(value,copy));
            if(!type.componentType().isPrimitive())for(int i=0;i<size;i++)capture(Array.get(value,i));return;
        }
        if(value instanceof Map<?,?> map){
            if(map.size()>4096)throw new IllegalStateException("actor_checkpoint_map_limit");var copy=new LinkedHashMap<>((Map<Object,Object>)map);
            if(mutable(type))maps.add(new MapValue((Map<Object,Object>)map,copy));for(var e:copy.entrySet()){capture(e.getKey());capture(e.getValue());}return;
        }
        if(value instanceof Collection<?> collection){
            if(collection.size()>4096)throw new IllegalStateException("actor_checkpoint_collection_limit");var copy=new ArrayList<>((Collection<Object>)collection);
            if(mutable(type))collections.add(new CollectionValue((Collection<Object>)collection,copy));for(Object element:copy)capture(element);return;
        }
        for(Class<?> owner=type;owner!=null&&owner!=Object.class;owner=owner.getSuperclass())for(Field field:owner.getDeclaredFields()){
            if(Modifier.isStatic(field.getModifiers()))continue;field.setAccessible(true);Object before=field.get(value);slots.add(new Slot(value,field,before));if(!field.getType().isPrimitive())capture(before);
        }
    }
    private static boolean mutable(Class<?> type){return !type.getName().startsWith("java.util.ImmutableCollections$")&&!type.getName().startsWith("java.util.Collections$");}
    void restore()throws Exception {
        // Restore references first, then contents of the original objects; native modules keep their original aliases.
        for(Slot slot:slots)slot.field().set(slot.owner(),slot.value());
        for(ArrayValue array:arrays)System.arraycopy(array.values(),0,array.owner(),0,Array.getLength(array.owner()));
        for(MapValue map:maps){map.owner().clear();map.owner().putAll(map.values());}
        for(CollectionValue collection:collections){collection.owner().clear();collection.owner().addAll(collection.values());}
    }
}
