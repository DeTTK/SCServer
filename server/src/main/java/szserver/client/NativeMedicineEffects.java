package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Native GameplayEffects builder and SELF delta codec; the journal still owns durable transactions. */
final class NativeMedicineEffects {
    private final Constructor<?> componentCtor,builderCtor,bankCtor,timerCtor;
    private final NativeMedicines.Export export;private final Field effectiveBank;private final Method rebuild;
    private final Method bankPut,attachBank,attachKind,attachAttribute,attachFlag,make,register,remove,clear,write,varint;
    private final Field next;
    private final Object medicineKind,activeKind,activeKey,timerAttribute,itemAttribute,groupAttribute,priorityAttribute,persistentFlag,self;
    private final Map<String,Object> statKeys=new HashMap<>();
    NativeMedicineEffects(NativeItemFactories host,NativeMedicines.Export export)throws Exception{
        this.export=export;
        Class<?> component=host.type("1967986234"),builder=host.type("222945433"),bank=host.type("964863275"),timer=host.type("1819519616"),attribute=host.type("461891820"),kind=host.type("1585835196"),stat=host.type("1924679062"),effect=host.type("837608172"),flag=host.type("1419211943"),audience=host.type("1120690568");
        componentCtor=component.getConstructor(boolean.class,boolean.class);builderCtor=builder.getConstructor();bankCtor=bank.getConstructor();timerCtor=timer.getConstructor(Instant.class,Duration.class);
        bankPut=NativeMethods.instance(bank,"double",void.class,stat,float.class);attachBank=NativeMethods.instance(builder,"native",builder,bank);attachKind=NativeMethods.instance(builder,"catch",builder,kind);attachAttribute=NativeMethods.instance(builder,"instanceof",builder,attribute,Object.class);attachFlag=NativeMethods.instance(builder,"finally",builder,flag,boolean.class);make=NativeMethods.instance(builder,"char",effect);
        register=NativeMethods.instance(component,"char",effect,effect);remove=NativeMethods.instance(component,"long",void.class,effect);clear=NativeMethods.instance(component,"new",void.class);write=NativeMethods.instance(component,"protected",void.class,DataOutput.class,audience);varint=NativeMethods.staticMethod(host.type("666192130"),"abstract",void.class,int.class,DataOutput.class);next=component.getField("volatile");effectiveBank=component.getField("break");rebuild=NativeMethods.instance(component,"synchronized",void.class);
        Class<?> attributes=host.type("626983914");timerAttribute=attributes.getField("try").get(null);itemAttribute=attributes.getField("assert").get(null);groupAttribute=attributes.getField("import").get(null);priorityAttribute=attributes.getField("boolean").get(null);medicineKind=kind.getField("MEDICINE").get(null);activeKind=kind.getField("MEDICINE_ACTIVE").get(null);activeKey=attributes.getField("synchronized").get(null);persistentFlag=flag.getField("PERSISTENT").get(null);self=audience.getField("SELF").get(null);
        for(Object key:stat.getEnumConstants())statKeys.put((String)stat.getField("for").get(key),key);
        // Exercise both add/remove branches and timer/attribute codecs before the network owner starts.
        var sample=new GameItems.Medicine(1,null,0,false,0,1,0,0,Map.of());delta(List.of(1_000_000),List.of(new GameItems.MedicineEffect(1_000_001,2_000,sample)),1_000);aggregate(List.of(Map.of("gravity_modifier",1f),Map.of("gravity_modifier",-.5f)));
    }
    private Object effect(GameItems.MedicineEffect row,long now)throws Exception{
        var definition=row.medicine();Object bank=bank(definition.stats());
        Object builder=builderCtor.newInstance();attachBank.invoke(builder,bank);attachKind.invoke(builder,definition.rules().active()?activeKind:medicineKind);if(definition.rules().active())attachAttribute.invoke(builder,activeKey,definition.rules().stages().key());attachFlag.invoke(builder,persistentFlag,definition.rules().persistent());
        attachAttribute.invoke(builder,timerAttribute,timerCtor.newInstance(Instant.ofEpochMilli(now),Duration.ofMillis(Math.max(1,row.until()-now))));attachAttribute.invoke(builder,itemAttribute,(short)definition.id());attachAttribute.invoke(builder,groupAttribute,definition.group());attachAttribute.invoke(builder,priorityAttribute,(byte)definition.priority());return make.invoke(builder);
    }
    private Object bank(Map<String,Float> values)throws Exception{Object bank=bankCtor.newInstance();for(var entry:values.entrySet()){Object key=statKeys.get(entry.getKey());if(!Float.isFinite(entry.getValue()))throw new IOException("medicine_native_stat");if(key==null)continue;bankPut.invoke(bank,key,entry.getValue());}return bank;}
    Map<String,Float> aggregate(Collection<Map<String,Float>> banks)throws Exception{
        if(banks.size()>128)throw new IOException("medicine_stat_bank_limit");Object target=componentCtor.newInstance(false,false);
        for(var values:banks){Object builder=builderCtor.newInstance();attachBank.invoke(builder,bank(values));attachKind.invoke(builder,medicineKind);register.invoke(target,make.invoke(builder));}
        rebuild.invoke(target);return export.read(effectiveBank.get(target));
    }
    byte[] delta(List<Integer> removed,Collection<GameItems.MedicineEffect> added,long now)throws Exception{
        if(removed.size()>32||added.size()>32)throw new IOException("medicine_effect_delta_limit");Object component=componentCtor.newInstance(false,true);List<Object> old=new ArrayList<>();
        for(int id:removed){Object marker=builderCtor.newInstance();attachBank.invoke(marker,bankCtor.newInstance());attachKind.invoke(marker,medicineKind);next.setInt(component,id);old.add(register.invoke(component,make.invoke(marker)));}
        clear.invoke(component);for(Object marker:old)remove.invoke(component,marker);
        for(var row:added){next.setInt(component,row.id());register.invoke(component,effect(row,now));}
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);varint.invoke(null,3,out);write.invoke(component,out,self);return bytes.toByteArray();
    }
}
