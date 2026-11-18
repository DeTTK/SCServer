package szserver.client;

import com.google.gson.*;
import szserver.world.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Original GameplayEffects/stat banks. Owned states are immutable; expiry uses the fixed world clock. */
final class NativeActorBuffs {
    private static final long EPOCH=1735689600000L,MAX_TICK=1_000_000_000_000L;
    private final Class<?> component,bank,builder,time,attribute,kind,stat;
    private final Constructor<?> componentCtor,bankCtor,builderCtor,timeCtor;
    private final Method bankPut,bankExport,attachBank,attachKind,attachAttribute,make,register,rebuild,clearDirty,active;
    private final Field stateField,nextField,effectiveBank;
    private final Object medicineKind,timerAttribute,itemAttribute,groupAttribute,priorityAttribute;
    private final Map<String,Object> statKeys=new HashMap<>();
    private final Map<Integer,ActorBuff> medicines=new HashMap<>();
    private final NativeActorLife life;
    private record Prepared(NativeActorBuffs owner,ActorBuff definition) implements ActorControllerEngine.PreparedMedicine {}
    private record Base(ActorLocomotion.Profile movement,float energy,Map<String,Float> stats){}
    private record Entry(int id,ActorBuff definition,long start,Object timer){long until(){return Math.addExact(start,definition.durationTicks());}}
    private record State(Base base,List<Entry> entries,Map<String,Long> cooldowns,int next,long tick,Object component,Map<String,Float> effective){}

    NativeActorBuffs(ClassLoader loader,Path definitions)throws Exception{
        component=type(loader,"1967986234");bank=type(loader,"964863275");builder=type(loader,"222945433");time=type(loader,"1819519616");attribute=type(loader,"461891820");kind=type(loader,"1585835196");stat=type(loader,"1924679062");
        componentCtor=component.getConstructor(boolean.class,boolean.class);bankCtor=bank.getConstructor();builderCtor=builder.getConstructor();timeCtor=time.getConstructor(Instant.class,Duration.class);
        bankPut=bank.getMethod("double",stat,float.class);bankExport=Arrays.stream(bank.getMethods()).filter(m->m.getName().equals("float")&&m.getParameterCount()==0&&Map.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        attachBank=builder.getMethod("native",bank);attachKind=builder.getMethod("catch",kind);attachAttribute=builder.getMethod("instanceof",attribute,Object.class);make=builder.getMethod("char");register=component.getMethod("char",type(loader,"837608172"));rebuild=component.getMethod("synchronized");clearDirty=component.getMethod("new");active=time.getMethod("public",Instant.class);
        nextField=component.getField("volatile");effectiveBank=component.getField("break");stateField=type(loader,"szserver.bridge.ActorLivingProfile").getField("buffState");life=new NativeActorLife(loader);
        Class<?> attributes=type(loader,"626983914");timerAttribute=attributes.getField("try").get(null);itemAttribute=attributes.getField("assert").get(null);groupAttribute=attributes.getField("import").get(null);priorityAttribute=attributes.getField("boolean").get(null);medicineKind=kind.getField("MEDICINE").get(null);
        for(Object key:stat.getEnumConstants())statKeys.put((String)stat.getField("for").get(key),key);
        Method parse=type(loader,"com.google.gson.JsonParser").getMethod("parse",String.class),numeric=bank.getMethod("long",type(loader,"com.google.gson.JsonObject"));Object parser=type(loader,"com.google.gson.JsonParser").getConstructor().newInstance();
        try(var text=Files.newBufferedReader(definitions);var input=new com.google.gson.stream.JsonReader(text)){
            input.beginObject();while(input.hasNext()){
                int id=Integer.parseInt(input.nextName());JsonObject row=JsonParser.parseReader(input).getAsJsonObject();if(!row.has("type")||!row.get("type").getAsString().equals("medicine"))continue;
                Object json=parse.invoke(parser,row.toString());Object nativeBank=numeric.invoke(null,json.getClass().getMethod("getAsJsonObject").invoke(json));
                medicines.put(id,new ActorBuff(id,row.has("effect_type")?row.get("effect_type").getAsString():"medicine",(int)number(row,"effect_priority"),ticks(row,"duration"),ticks(row,"cooldown"),(float)number(row,"instant_regen"),export(nativeBank)));
            }input.endObject();
        }
        // Warm native builders, timer/stat codecs and the aggregate before a world owner exists.
        ActorBuff example=medicines.values().stream().findFirst().orElseThrow();build(new Base(ActorLocomotion.Profile.DEFAULT,1,Map.of()),List.of(new Entry(1_000_000,example,0,null)),Map.of(),1_000_001,0);
    }
    private static Class<?> type(ClassLoader loader,String name)throws Exception{return Class.forName(name,true,loader);}
    private static double number(JsonObject row,String key){double v=row.has(key)?row.get(key).getAsDouble():0;if(!Double.isFinite(v))throw new IllegalArgumentException("actor_buff_resource_number");return v;}
    private static long ticks(JsonObject row,String key){double v=number(row,key);if(v<0||v>630720000||v!=Math.rint(v))throw new IllegalArgumentException("actor_buff_resource_ticks");return (long)v;}
    private static void worker(){if(Set.of("world-tick","native-game-owner").contains(Thread.currentThread().getName()))throw new IllegalStateException("actor_medicine_prepare_on_owner");}
    ActorControllerEngine.PreparedMedicine prepare(int item){worker();var definition=medicines.get(item);if(definition==null)throw new IllegalArgumentException("actor_medicine_unknown");return new Prepared(this,definition);}
    private Map<String,Float> export(Object nativeBank)throws Exception{
        var result=new TreeMap<String,Float>();for(var e:((Map<?,?>)bankExport.invoke(nativeBank)).entrySet())result.put((String)stat.getField("for").get(e.getKey()),((Number)e.getValue()).floatValue());return ActorEquipment.checkedStats(result);
    }
    private static Instant now(long tick){if(tick<0||tick>MAX_TICK)throw new IllegalArgumentException("actor_buff_clock");return Instant.ofEpochMilli(EPOCH+tick*50);}
    private State state(Object actor)throws Exception{return (State)stateField.get(actor);}
    private State build(Base base,List<Entry> rows,Map<String,Long> cooldowns,int next,long tick)throws Exception{
        if(rows.size()>32||cooldowns.size()>64||next<1_000_000||next==Integer.MAX_VALUE&&rows.stream().anyMatch(e->e.id()>=next))throw new IllegalArgumentException("actor_buff_limit");now(tick);
        Object target=componentCtor.newInstance(false,false);var entries=new ArrayList<Entry>();Set<Integer> ids=new HashSet<>();Set<String> groups=new HashSet<>();
        for(var row:rows){
            if(row.id()<1_000_000||row.id()>=next||!ids.add(row.id())||!groups.add(row.definition().group())||row.start()<0||row.start()>tick||row.until()<=tick)throw new IllegalArgumentException("actor_buff_entry");
            Object values=bankCtor.newInstance();for(var e:row.definition().stats().entrySet()){Object key=statKeys.get(e.getKey());if(key==null)throw new IllegalArgumentException("actor_buff_stat_unknown");bankPut.invoke(values,key,e.getValue());}
            Object b=builderCtor.newInstance(),timer=timeCtor.newInstance(now(row.start()),Duration.ofMillis(row.definition().durationTicks()*50));attachBank.invoke(b,values);attachKind.invoke(b,medicineKind);
            attachAttribute.invoke(b,timerAttribute,timer);attachAttribute.invoke(b,itemAttribute,(short)row.definition().item());attachAttribute.invoke(b,groupAttribute,row.definition().group());attachAttribute.invoke(b,priorityAttribute,(byte)Math.max(-128,Math.min(127,row.definition().priority())));
            nextField.setInt(target,row.id());register.invoke(target,make.invoke(b));entries.add(new Entry(row.id(),row.definition(),row.start(),timer));
        }
        nextField.setInt(target,next);rebuild.invoke(target);clearDirty.invoke(target);
        var effective=new TreeMap<>(base.stats());export(effectiveBank.get(target)).forEach((key,value)->effective.merge(key,value,Float::sum));
        return new State(base,List.copyOf(entries),Map.copyOf(cooldowns),next,tick,target,ActorEquipment.checkedStats(effective));
    }
    private static Object config(Object controller)throws Exception{return controller.getClass().getField("abstract").get(controller);}
    private static float field(Object owner,String name)throws Exception{return owner.getClass().getField(name).getFloat(owner);}
    private Base captureBase(Object actor,Object controller)throws Exception{
        Object config=config(controller);return new Base(new ActorLocomotion.Profile(field(config,"super"),field(config,"native"),field(config,"while"),1,1,config.getClass().getField("case").getBoolean(config)),field(config,"static"),NativeActorLife.stats(actor));
    }
    void initialize(Object actor,Object controller)throws Exception{stateField.set(actor,build(captureBase(actor,controller),List.of(),Map.of(),1_000_000,0));}
    void legacy(Object actor,Object controller)throws Exception{stateField.set(actor,build(captureBase(actor,controller),List.of(),Map.of(),1_000_000,0));}
    private void publish(Object actor,Object controller,State value)throws Exception{
        encode(value);Object config=config(controller);var p=value.base().movement();Map<String,Float> delta=export(effectiveBank.get(value.component()));
        config.getClass().getField("super").setFloat(config,Math.max(0,p.baseSpeed()+delta.getOrDefault("speed_modifier",0f)/100));
        config.getClass().getField("native").setFloat(config,p.sprintBonus()+delta.getOrDefault("sprint_speed_modifier",0f)/100);
        config.getClass().getField("static").setFloat(config,Math.max(.01f,Math.min(100,value.base().energy()+delta.getOrDefault("stamina_bonus",0f)/100)));
        life.stats(actor,value.effective());stateField.set(actor,value);
    }
    void equipment(Object actor,Object controller,ActorEquipment value)throws Exception{
        State old=state(actor);publish(actor,controller,build(new Base(value.movement(),value.energyCapacity(),value.stats()),old.entries(),old.cooldowns(),old.next(),old.tick()));
    }
    long tick(Object actor)throws Exception{return state(actor).tick();}
    void advance(Object actor,Object controller,long tick)throws Exception{
        State old=state(actor);if(tick<old.tick())throw new IllegalArgumentException("actor_buff_clock_rewind");if(tick==old.tick()||old.entries().isEmpty()&&old.cooldowns().isEmpty())return;
        List<Entry> kept=new ArrayList<>();for(var e:old.entries())if(life.health(actor)>0&&(boolean)active.invoke(e.timer(),now(tick)))kept.add(e);
        var cooldowns=new TreeMap<String,Long>();old.cooldowns().forEach((key,end)->{if(end>tick)cooldowns.put(key,end);});
        if(kept.size()==old.entries().size()&&cooldowns.size()==old.cooldowns().size())stateField.set(actor,new State(old.base(),old.entries(),old.cooldowns(),old.next(),tick,old.component(),old.effective()));
        else publish(actor,controller,build(old.base(),kept,cooldowns,old.next(),tick));
    }
    void use(Object actor,Object controller,ActorControllerEngine.PreparedMedicine prepared,long tick)throws Exception{
        if(!(prepared instanceof Prepared p)||p.owner()!=this)throw new IllegalArgumentException("actor_medicine_loader");if(life.health(actor)<=0)throw new IllegalArgumentException("actor_medicine_dead");advance(actor,controller,tick);State old=state(actor);ActorBuff definition=p.definition();
        if(old.cooldowns().getOrDefault(definition.group(),0L)>tick)throw new IllegalArgumentException("actor_medicine_cooldown");
        if(old.entries().stream().anyMatch(e->e.definition().group().equals(definition.group())&&e.definition().priority()>definition.priority()))throw new IllegalArgumentException("actor_medicine_priority");
        var entries=new ArrayList<>(old.entries().stream().filter(e->!e.definition().group().equals(definition.group())).toList());int next=old.next();
        if(definition.durationTicks()>0){if(next==Integer.MAX_VALUE)throw new IllegalArgumentException("actor_buff_sequence");entries.add(new Entry(next++,definition,tick,null));}
        var cooldowns=new TreeMap<>(old.cooldowns());if(definition.cooldownTicks()>0)cooldowns.put(definition.group(),Math.addExact(tick,definition.cooldownTicks()));
        publish(actor,controller,build(old.base(),entries,cooldowns,next,tick));float healed=szserver.net.HealthRules.instant(life.health(actor),NativeActorLife.stats(actor),definition.instant());life.health(actor,healed);
    }
    void remove(Object actor,Object controller,int id,long tick)throws Exception{advance(actor,controller,tick);State old=state(actor);if(id<0||id!=0&&old.entries().stream().noneMatch(e->e.id()==id))throw new IllegalArgumentException("actor_buff_not_found");publish(actor,controller,build(old.base(),id==0?List.of():old.entries().stream().filter(e->e.id()!=id).toList(),old.cooldowns(),old.next(),tick));}
    void death(Object actor,Object controller)throws Exception{State old=state(actor);if(!old.entries().isEmpty())publish(actor,controller,build(old.base(),List.of(),old.cooldowns(),old.next(),old.tick()));}
    List<ActorBuff.View> views(Object actor)throws Exception{State s=state(actor);return s.entries().stream().map(e->new ActorBuff.View(e.id(),e.definition().item(),e.definition().group(),e.definition().priority(),e.until()-s.tick(),e.definition().stats())).toList();}
    private static void stats(DataOutput out,Map<String,Float> stats)throws IOException{out.writeInt(stats.size());for(var e:new TreeMap<>(stats).entrySet()){out.writeUTF(e.getKey());out.writeFloat(e.getValue());}}
    private static Map<String,Float> stats(DataInput in)throws IOException{int n=in.readInt();if(n<0||n>512)throw new IOException("actor_buff_stats_count");var values=new TreeMap<String,Float>();for(int i=0;i<n;i++)if(values.put(in.readUTF(),in.readFloat())!=null)throw new IOException("actor_buff_stat_duplicate");return ActorEquipment.checkedStats(values);}
    private byte[] encode(State s)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeLong(s.tick());out.writeInt(s.next());var p=s.base().movement();for(float v:new float[]{p.baseSpeed(),p.sprintBonus(),p.speedMultiplier(),p.statusMultiplier(),p.conditionFactor(),s.base().energy()})out.writeFloat(v);out.writeBoolean(p.encumbered());stats(out,s.base().stats());
        out.writeInt(s.entries().size());for(var e:s.entries()){var d=e.definition();out.writeInt(e.id());out.writeLong(e.start());out.writeInt(d.item());out.writeUTF(d.group());out.writeInt(d.priority());out.writeLong(d.durationTicks());out.writeLong(d.cooldownTicks());out.writeFloat(d.instant());stats(out,d.stats());}
        out.writeInt(s.cooldowns().size());for(var e:new TreeMap<>(s.cooldowns()).entrySet()){out.writeUTF(e.getKey());out.writeLong(e.getValue());}if(bytes.size()>65536)throw new IOException("actor_buff_state_size");return bytes.toByteArray();
    }
    byte[] write(Object actor)throws Exception{return encode(state(actor));}
    Runnable read(Object actor,Object controller,byte[] raw,Map<String,Float> expected)throws Exception{
        var in=new BoundedNativeInput(raw);if(in.readInt()!=1)throw new IOException("actor_buff_version");long tick=in.readLong();int next=in.readInt();var profile=new ActorLocomotion.Profile(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),false);float energy=in.readFloat();boolean encumbered=in.readBoolean();profile=new ActorLocomotion.Profile(profile.baseSpeed(),profile.sprintBonus(),profile.speedMultiplier(),profile.statusMultiplier(),profile.conditionFactor(),encumbered);if(!Float.isFinite(energy)||energy<.01f||energy>100)throw new IOException("actor_buff_energy");var base=new Base(profile,energy,stats(in));
        int count=in.readInt();if(count<0||count>32)throw new IOException("actor_buff_count");var entries=new ArrayList<Entry>();for(int i=0;i<count;i++){int id=in.readInt();long start=in.readLong();entries.add(new Entry(id,new ActorBuff(in.readInt(),in.readUTF(),in.readInt(),in.readLong(),in.readLong(),in.readFloat(),stats(in)),start,null));}
        count=in.readInt();if(count<0||count>64)throw new IOException("actor_buff_cooldown_count");var cooldowns=new TreeMap<String,Long>();for(int i=0;i<count;i++){String group=in.readUTF();long until=in.readLong();if(group.isEmpty()||group.length()>128||until<=tick||until>MAX_TICK||cooldowns.put(group,until)!=null)throw new IOException("actor_buff_cooldown");}
        if(in.available()!=0)throw new IOException("actor_buff_trailing");State value=build(base,entries,cooldowns,next,tick);if(!value.effective().equals(expected))throw new IOException("actor_buff_effective_mismatch");return ()->{try{stateField.set(actor,value);}catch(Exception error){throw new IllegalStateException(error);}};
    }
}
