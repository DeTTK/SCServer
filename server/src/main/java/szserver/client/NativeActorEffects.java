package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Native anomaly component and achievement progress. Only confirmed contact semantics are advanced.
 * The native component stage precedes controller/world movement in 1172351731.switch/void.
 * Other anomaly object lifecycles and server-only event bodies remain separate obligations. */
final class NativeActorEffects {
    private final Class<?> component,contextType,playerType,progressType;
    private final Field context,effectsTable,progressState,progressTable,world,clockRate,clockAnchor,clockOffset;
    private final Method tick,progressWrite,progressRead,makeProgress,definition;
    private final Object registry;
    private final Field[] flags;private final Field[] deadlines;
    NativeActorEffects(ClassLoader loader)throws Exception{
        component=Class.forName("1448531254",true,loader);contextType=Class.forName("358035993",true,loader);playerType=Class.forName("200241649",true,loader);progressType=Class.forName("776835576",true,loader);
        context=playerType.getDeclaredField("protected");context.setAccessible(true);effectsTable=contextType.getDeclaredField("void");effectsTable.setAccessible(true);progressState=contextType.getField("class");progressTable=Class.forName("1451253642",true,loader).getField("for");world=Class.forName("1217489680",true,loader).getDeclaredField("interface");world.setAccessible(true);
        tick=component.getMethod("break");flags=new Field[6];String[] names={"goto","double","transient","default","new","case"};for(int i=0;i<names.length;i++)flags[i]=component.getField(names[i]);deadlines=new Field[]{component.getField("while"),component.getField("assert")};
        Class<?> achievement=Class.forName("870287846",true,loader),base=Class.forName("636748791",true,loader);makeProgress=base.getMethod("break",achievement);progressWrite=progressType.getMethod("switch",DataOutput.class);progressRead=progressType.getMethod("extends",DataInput.class);
        var registrations=Class.forName("1470377155",true,loader);registry=registrations.getMethod("if").invoke(null);definition=registrations.getMethod("finally",String.class);
        for(String key:List.of("kissel","deactivation"))if(definition.invoke(registry,key)==null)throw new IllegalStateException("native_actor_achievement_missing:"+key);
        var clock=Class.forName("1553688601",true,loader);clockRate=clock.getField("throw");clockAnchor=clock.getField("finally");clockOffset=clock.getField("default");
    }
    private Object context(Object actor)throws Exception{return context.get(actor);}
    private Object component(Object actor)throws Exception{
        for(Object value:((Map<?,?>)effectsTable.get(context(actor))).values())if(component.isInstance(value))return value;throw new IllegalStateException("native_actor_anomaly_component_missing");
    }
    @SuppressWarnings("unchecked") private Map<Object,Object> progress(Object actor)throws Exception{return (Map<Object,Object>)progressTable.get(progressState.get(context(actor)));}
    private void supported(Object value)throws Exception{
        if(component.getField("if").get(value)!=null||!((List<?>)component.getField("finally").get(value)).isEmpty()||!((List<?>)component.getField("volatile").get(value)).isEmpty()||!((Map<?,?>)component.getField("for").get(value)).isEmpty())throw new IllegalStateException("native_actor_anomaly_lifecycle_unported");
    }
    private NativeActorRandom random(Object actor)throws Exception{
        Object nativeWorld=world.get(actor);try{Object value=nativeWorld.getClass().getField("contactRandom").get(nativeWorld);return (NativeActorRandom)value;}catch(NoSuchFieldException absent){return null;}
    }
    final class ClockScope implements AutoCloseable {
        final float rate;final long anchor,offset;
        ClockScope(double seconds)throws Exception{if(!Double.isFinite(seconds)||seconds<0||seconds>9e12)throw new IllegalArgumentException("native_actor_effect_clock");rate=clockRate.getFloat(null);anchor=clockAnchor.getLong(null);offset=clockOffset.getLong(null);clockRate.setFloat(null,0f);clockAnchor.setLong(null,Math.round(seconds*1000));clockOffset.setLong(null,0L);}
        public void close()throws Exception{clockRate.setFloat(null,rate);clockAnchor.setLong(null,anchor);clockOffset.setLong(null,offset);}
    }
    ClockScope clock(double seconds)throws Exception{return new ClockScope(seconds);}
    void step(Object actor)throws Exception{Object value=component(actor);supported(value);tick.invoke(value);}
    Set<Integer> propertyIds(Object actor)throws Exception{
        Object value=component(actor);Set<Integer> result=new HashSet<>();for(String name:new String[]{"protected","void","continue"}){Object property=component.getField(name).get(value);result.add(Short.toUnsignedInt(property.getClass().getField("throw").getShort(property)));}
        Field livingStore=component.getClassLoader().loadClass("820497227").getDeclaredField("const");livingStore.setAccessible(true);Object store=livingStore.get(actor);Object property=store.getClass().getMethod("super",int.class,int.class).invoke(store,598778349,-387986683);result.add(Short.toUnsignedInt(property.getClass().getField("throw").getShort(property)));return result;
    }
    byte[] write(Object actor)throws Exception{
        Object value=component(actor);supported(value);var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer);out.writeInt(1);
        for(Field flag:flags)out.writeBoolean(flag.getBoolean(value));for(Field field:deadlines){Instant deadline=(Instant)field.get(value);out.writeBoolean(deadline!=null);if(deadline!=null){out.writeLong(deadline.getEpochSecond());out.writeInt(deadline.getNano());}}
        var rows=new TreeMap<String,Object>();for(var entry:progress(actor).entrySet()){
            String key=(String)entry.getKey().getClass().getMethod("extends").invoke(entry.getKey());if(!Set.of("kissel","deactivation").contains(key)||entry.getValue().getClass()!=progressType||rows.put(key,entry.getValue())!=null)throw new IOException("native_actor_achievement_scope");
        }
        out.writeInt(rows.size());for(var row:rows.entrySet()){out.writeUTF(row.getKey());var data=new ByteArrayOutputStream();progressWrite.invoke(row.getValue(),new DataOutputStream(data));byte[] bytes=data.toByteArray();if(bytes.length!=2)throw new IOException("native_actor_trigger_layout");out.write(bytes);}
        NativeActorRandom random=random(actor);out.writeBoolean(random!=null);if(random!=null)out.writeLong(random.state());return buffer.toByteArray();
    }
    Runnable read(Object actor,byte[] bytes)throws Exception{
        if(bytes.length>4096)throw new IOException("native_actor_effect_size");Object value=component(actor);supported(value);var in=new BoundedNativeInput(bytes);if(in.readInt()!=1)throw new IOException("native_actor_effect_version");boolean[] values=new boolean[flags.length];for(int i=0;i<values.length;i++)values[i]=in.readBoolean();Instant[] times=new Instant[deadlines.length];
        for(int i=0;i<times.length;i++)if(in.readBoolean()){long seconds=in.readLong();int nanos=in.readInt();if(seconds<0||seconds>9e12||nanos<0||nanos>999_999_999)throw new IOException("native_actor_effect_deadline");times[i]=Instant.ofEpochSecond(seconds,nanos);}
        int count=in.readInt();if(count<0||count>2)throw new IOException("native_actor_achievement_count");var rows=new LinkedHashMap<Object,Object>();Set<String> keys=new HashSet<>();
        for(int i=0;i<count;i++){String key=in.readUTF();if(!Set.of("kissel","deactivation").contains(key)||!keys.add(key))throw new IOException("native_actor_achievement_key");Object registered=definition.invoke(registry,key);if(registered==null)throw new IOException("native_actor_achievement_definition");Object state=makeProgress.invoke(null,registered);if(state.getClass()!=progressType)throw new IOException("native_actor_achievement_type");progressRead.invoke(state,in);rows.put(registered,state);}
        NativeActorRandom random=random(actor);boolean hasRandom=in.readBoolean();if(hasRandom!=(random!=null))throw new IOException("native_actor_random_host");long seed=hasRandom?in.readLong():0;if(hasRandom&&(seed<0||seed>=(1L<<48)))throw new IOException("native_actor_random_state");if(in.available()!=0)throw new IOException("native_actor_effect_trailing");
        return ()->{try{for(int i=0;i<flags.length;i++)flags[i].setBoolean(value,values[i]);for(int i=0;i<deadlines.length;i++)deadlines[i].set(value,times[i]);var target=progress(actor);target.clear();target.putAll(rows);if(random!=null)random.restore(seed);}catch(Exception error){throw new IllegalStateException("native_actor_effect_restore",error);}};
    }
    Runnable checkpoint(Object actor)throws Exception{
        write(actor);Object value=component(actor);boolean[] before=new boolean[flags.length];for(int i=0;i<flags.length;i++)before[i]=flags[i].getBoolean(value);Object[] times=new Object[deadlines.length];for(int i=0;i<times.length;i++)times[i]=deadlines[i].get(value);
        var rows=new LinkedHashMap<>(progress(actor));Map<Object,boolean[]> states=new IdentityHashMap<>();Field complete=progressType.getField("abstract"),trigger=progressType.getField("short");for(Object state:rows.values())states.put(state,new boolean[]{complete.getBoolean(state),trigger.getBoolean(state)});NativeActorRandom random=random(actor);long seed=random==null?0:random.state();
        return ()->{try{for(int i=0;i<flags.length;i++)flags[i].setBoolean(value,before[i]);for(int i=0;i<times.length;i++)deadlines[i].set(value,times[i]);var target=progress(actor);target.clear();target.putAll(rows);for(var state:states.entrySet()){complete.setBoolean(state.getKey(),state.getValue()[0]);trigger.setBoolean(state.getKey(),state.getValue()[1]);}if(random!=null)random.restore(seed);}catch(Exception error){throw new IllegalStateException("native_actor_effect_checkpoint",error);}};
    }
}
