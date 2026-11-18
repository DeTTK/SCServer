package szserver.client;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiPredicate;
import szserver.world.ActorVitals;

/** Executes the untouched 351062380.long, including its original head/metadata query and air counter.
 * Binds the server damage service to the native admission and damage reduction methods. */
final class NativeActorLife {
    private static final double PERIOD=(double).05f;
    private final ClassLoader loader;private final Class<?> living,life,property;
    private final Field air,lifeStore,maxHealth,baseStore,handler,remainder,submerged,properties;
    private final Method tick,readAir,readHealth,writeHealth,damage,reject,invulnerable,creative,creativeComponent,voidDamage,head;
    private final Object water;
    private final Field recoveryState,healthRules,recoveryFraction;
    private static final long MAX_CLOCK=1_000_000_000_000L;
    /** Immutable so a controller transaction also restores the exact recovery clock. */
    private record Recovery(long tick,long age){}
    NativeActorLife(ClassLoader loader)throws Exception{
        this.loader=loader;living=type("820497227");life=type("351062380");property=type("1798539107");
        air=field(living,"public");lifeStore=field(life,"protected");maxHealth=field(life,"catch");baseStore=field(living,"const");
        Class<?> bridge=type("szserver.bridge.ActorLivingProfile");handler=bridge.getField("livingDamage");remainder=bridge.getField("livingRemainder");submerged=bridge.getField("livingSubmerged");properties=bridge.getField("livingProperties");
        recoveryState=bridge.getField("recoveryState");healthRules=bridge.getField("healthRules");recoveryFraction=bridge.getField("recoveryFraction");
        tick=life.getMethod("long");readAir=method(living,"throw",int.class);readHealth=method(life,"boolean",float.class);writeHealth=life.getMethod("long",float.class);
        damage=life.getMethod("switch",type("49549491"),float.class);reject=type("1023573494").getMethod("transient",life,type("49549491"),float.class);invulnerable=method(living,"new",boolean.class);
        Class<?> status=type("1409908300");creativeComponent=status.getMethod("protected",type("200241649"));creative=method(status,"new",boolean.class);voidDamage=method(type("49549491"),"default",boolean.class);
        Class<?> material=type("393979275");water=material.getField("class").get(null);head=living.getMethod("goto",material);
    }
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Method method(Class<?> type,String name,Class<?> result){return Arrays.stream(type.getMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==0&&m.getReturnType()==result).findFirst().orElseThrow();}
    private Object property(Object actor,String name,Object initial,String... flags)throws Exception{
        Object metadata=type("1993742576").getField("it").get(null),attribute=metadata.getClass().getField(name).get(metadata);
        Object builder=attribute.getClass().getMethod("catch",type("1667320743"),Object.class).invoke(attribute,actor,initial);
        for(String flag:flags)builder=builder.getClass().getMethod(flag).invoke(builder);
        return builder.getClass().getMethod("public").invoke(builder);
    }
    void prepare(Object actor,szserver.net.HealthRules rules)throws Exception{
        healthRules.set(actor,Objects.requireNonNull(rules));recoveryState.set(actor,new Recovery(-1,MAX_CLOCK));
        // The same typed attributes and native stores registered by the original constructors.
        Object oxygen=property(actor,"byte",(short)300,"finally","super"),hp=property(actor,"interface",100f,"finally"),maximum=property(actor,"throws",100f,"finally"),fire=property(actor,"finally",(byte)0,"finally");
        air.set(actor,oxygen);
        Object store=type("258939").getConstructor().newInstance();lifeStore.set(actor,store);
        store.getClass().getMethod("transient",int.class,int.class,property).invoke(store,427700767,-1672717326,hp);
        maxHealth.set(actor,maximum);
        Object bank=baseStore.get(actor);bank.getClass().getMethod("native",int.class,int.class,property).invoke(bank,803095872,-1111601756,fire);
        properties.set(actor,new Object[]{oxygen,hp,maximum,fire});
        actor.getClass().getField("equipmentStats").set(actor,Map.of());
        // Native head-corner suffocation uses this constructor width, independent of render geometry.
        field(living,"c").setFloat(actor,.6f);
        handler.set(actor,(BiPredicate<Object,Float>)(source,amount)->applyDamage(actor,source,amount));
    }
    private boolean applyDamage(Object actor,Object source,float amount){
        try{
            // Original lethal environmental requests can legitimately be 1e9.
            if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("native_living_damage");
            if((boolean)reject.invoke(null,actor,source,amount)||(boolean)invulnerable.invoke(actor)||health(actor)<=0)return false;
            if((boolean)creative.invoke(creativeComponent.invoke(null,actor))&&!(boolean)voidDamage.invoke(source))return false;
            if(!(boolean)voidDamage.invoke(source))amount*=szserver.inventory.CombatModifiers.received(stats(actor));
            float before=health(actor);damage.invoke(actor,source,amount);if(health(actor)<before){injured(actor);return true;}return false;
        }catch(InvocationTargetException e){throw error(e.getCause());}catch(Exception e){throw error(e);}
    }
    private static RuntimeException error(Throwable value){return value instanceof RuntimeException r?r:new IllegalStateException("native_actor_living",value);}
    float health(Object actor)throws Exception{return (float)readHealth.invoke(actor);}
    @SuppressWarnings("unchecked") static Map<String,Float> stats(Object actor)throws Exception{Object value=actor.getClass().getField("equipmentStats").get(actor);return value==null?Map.of():(Map<String,Float>)value;}
    float maximum(Object actor)throws Exception{return (float)method(life,"instanceof",float.class).invoke(actor);}
    void stats(Object actor,Map<String,Float> values)throws Exception{
        var checked=szserver.world.ActorEquipment.checkedStats(values);float maximum=szserver.net.HealthRules.maximum(checked);
        actor.getClass().getField("equipmentStats").set(actor,checked);
        property.getMethod("assert",Object.class).invoke(maxHealth.get(actor),maximum);
        if(health(actor)>maximum)writeHealth.invoke(actor,maximum);
    }
    void health(Object actor,float value)throws Exception{if(!Float.isFinite(value)||value<0||value>maximum(actor))throw new IllegalArgumentException("actor_health");float before=health(actor);if(before!=value){writeHealth.invoke(actor,value);if(value<before)injured(actor);}}
    private Recovery recovery(Object actor)throws Exception{return (Recovery)recoveryState.get(actor);}
    private void injured(Object actor)throws Exception{Recovery s=recovery(actor);recoveryState.set(actor,new Recovery(s.tick(),0));}
    long clock(Object actor)throws Exception{return recovery(actor).tick();}
    long durationClock(Object actor,float seconds)throws Exception{
        if(!Float.isFinite(seconds)||seconds<=0||seconds>1)throw new IllegalArgumentException("actor_recovery_duration");
        if(clock(actor)<0)bindClock(actor,0);double time=recoveryFraction.getDouble(actor)+seconds;long steps=(long)Math.floor(time/PERIOD);
        recoveryFraction.setDouble(actor,time-steps*PERIOD);return Math.addExact(clock(actor),steps);
    }
    void bindClock(Object actor,long tick)throws Exception{
        if(tick<0||tick>MAX_CLOCK)throw new IllegalArgumentException("actor_recovery_clock");Recovery s=recovery(actor);
        if(s.tick()>tick)throw new IllegalArgumentException("actor_recovery_clock_future");if(s.tick()<0)recoveryState.set(actor,new Recovery(tick,s.age()));
    }
    void periodic(Object actor,long tick)throws Exception{
        Recovery s=recovery(actor);if(s.tick()<0||tick!=s.tick()+1)throw new IllegalArgumentException("actor_recovery_step");
        long age=Math.min(MAX_CLOCK,s.age()+1);float before=health(actor);
        float after=((szserver.net.HealthRules)healthRules.get(actor)).step(before,maximum(actor),stats(actor),age/20.0,.05);
        // Periodic damage must not restart its own cooldown; direct/environmental damage does.
        if(before!=after)writeHealth.invoke(actor,after);recoveryState.set(actor,new Recovery(tick,age));
    }
    void writeRecovery(java.io.DataOutput out,Object actor)throws Exception{Recovery s=recovery(actor);out.writeLong(s.tick());out.writeLong(s.age());out.writeDouble(recoveryFraction.getDouble(actor));}
    Runnable readRecovery(java.io.DataInput in,Object actor)throws Exception{
        long tick=in.readLong(),age=in.readLong();double fraction=in.readDouble();if(tick< -1||tick>MAX_CLOCK||age<0||age>MAX_CLOCK||!Double.isFinite(fraction)||fraction<0||fraction>=PERIOD)throw new java.io.IOException("actor_recovery_state");
        Recovery state=new Recovery(tick,age);return ()->{try{recoveryState.set(actor,state);recoveryFraction.setDouble(actor,fraction);}catch(Exception e){throw error(e);}};
    }
    void legacyRecovery(Object actor)throws Exception{recoveryState.set(actor,new Recovery(-1,0));}
    ActorVitals vitals(Object actor)throws Exception{return new ActorVitals(health(actor),(int)readAir.invoke(actor),submerged.getBoolean(actor),maximum(actor));}
    void validate(Object actor)throws Exception{vitals(actor);if(maximum(actor)!=szserver.net.HealthRules.maximum(stats(actor)))throw new java.io.IOException("actor_living_maximum");}
    void step(Object actor,float seconds)throws Exception{
        double time=remainder.getDouble(actor)+seconds;
        while(time>=PERIOD){tick.invoke(actor);time-=PERIOD;}
        remainder.setDouble(actor,time);submerged.setBoolean(actor,(boolean)head.invoke(actor,water));
    }
    Set<Integer> propertyIds(Object actor)throws Exception{
        Set<Integer> ids=new HashSet<>();
        for(Object value:(Object[])properties.get(actor))
            ids.add(Short.toUnsignedInt(property.getField("throw").getShort(value)));
        return ids;
    }
    void write(java.io.DataOutput out,Object actor)throws Exception{out.writeDouble(remainder.getDouble(actor));out.writeBoolean(submerged.getBoolean(actor));}
    Runnable read(java.io.DataInput in,Object actor)throws Exception{
        double time=in.readDouble();boolean wet=in.readBoolean();if(!Double.isFinite(time)||time<0||time>=PERIOD)throw new java.io.IOException("actor_living_remainder");
        return ()->{try{remainder.setDouble(actor,time);submerged.setBoolean(actor,wet);}catch(Exception e){throw error(e);}};
    }
}
