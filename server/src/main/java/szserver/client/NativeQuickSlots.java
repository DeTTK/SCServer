package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import szserver.inventory.*;

/** Stock quick-slot settings, binding rules, inventory predicates, comparator and packet codecs. */
final class NativeQuickSlots {
    private final NativeItemFactories host;private final Class<?> settings,capability,actor;
    private final Method write,set,assign,lookup,matches,id,comparator;private final Field entries;
    private final Object bindPacket,usePacket;private final Method bindRead,bindWrite,useRead;private final Field bindIndex,bindSource,bindLabel,useIndex;
    private final Method owner,addressAggregate,addressContainer,addressSlot;private final Constructor<?> syncPacket;private final Method syncWrite;
    NativeQuickSlots(NativeItemFactories host)throws Exception{
        this.host=host;settings=host.type("1756593341");capability=host.type("669790692");actor=host.type("200241649");Class<?> binding=host.type("965067844"),address=host.type("949164803");
        write=NativeMethods.instance(settings,"extends",void.class,DataOutput.class);set=NativeMethods.instance(settings,"native",void.class,int.class,binding);assign=NativeMethods.instance(settings,"abstract",void.class,actor,int.class,host.stack,String.class);lookup=NativeMethods.instance(settings,"private",binding,int.class);entries=settings.getField("case");
        matches=NativeMethods.instance(binding,"catch",boolean.class,actor,host.stack);id=NativeMethods.instance(host.stack,"continue",int.class);comparator=NativeMethods.instance(capability,"if",Comparator.class,actor);
        Class<?> bind=host.type("423821855"),use=host.type("1491633278"),base=host.type("1799496656"),sync=host.type("1309545617");bindPacket=bind.getConstructor().newInstance();usePacket=use.getConstructor().newInstance();bindRead=NativeMethods.instance(bind,"extends",void.class,DataInput.class);bindWrite=NativeMethods.instance(bind,"switch",void.class,DataOutput.class);useRead=NativeMethods.instance(use,"extends",void.class,DataInput.class);bindIndex=bind.getField("class");bindSource=bind.getField("while");bindLabel=bind.getField("case");useIndex=use.getField("const");owner=NativeMethods.instance(base,"volatile",int.class);
        addressAggregate=NativeMethods.instance(address,"protected",int.class);addressContainer=NativeMethods.instance(address,"break",int.class);addressSlot=NativeMethods.instance(address,"default",int.class);syncPacket=sync.getConstructor(settings);syncWrite=NativeMethods.instance(sync,"switch",void.class,DataOutput.class);
        Object sample=bind.getConstructor(int.class,int.class,address,String.class).newInstance(0,2,address.getConstructor(int.class,int.class,int.class).newInstance(1,0,7),"test");var bytes=new ByteArrayOutputStream();bindWrite.invoke(sample,new DataOutputStream(bytes));var request=bindRequest(bytes.toByteArray());if(request.owner()!=0||request.index()!=2||!request.source().equals(new Inventory.Address(1,0,7)))throw new IOException("native_quick_binding_packet_warmup");
    }
    private static void index(int index)throws IOException{if(index<0||index>=9)throw new IOException("quick_slot_index");}
    private Object decode(byte[] raw)throws Exception{if(raw.length==0)return settings.getConstructor().newInstance();if(raw.length>16384)throw new IOException("quick_slots_size");NativeNbtLimits.validate(raw);var input=new BoundedNativeInput(raw);Object value=settings.getConstructor(DataInput.class).newInstance(input);if(input.available()!=0)throw new IOException("quick_slots_trailing");return value;}
    private Object stack(byte[] raw)throws Exception{if(raw.length<10||raw.length>8192)throw new IOException("quick_item_size");var in=new BoundedNativeInput(raw);Object value=host.stack.getConstructor(DataInput.class).newInstance(in);if(in.available()!=0)throw new IOException("quick_item_trailing");return value;}
    private GameItems.QuickSlots snapshot(Object value)throws Exception{
        var map=(Map<?,?>)entries.get(value);if(map.size()>9)throw new IOException("quick_slot_count");Map<Integer,GameItems.QuickBinding> result=new TreeMap<>();for(var e:map.entrySet()){
            int slot=(Integer)e.getKey();index(slot);Object binding=e.getValue();int factory=binding.getClass().getField("throw").getInt(binding);String label=(String)binding.getClass().getField("enum").get(binding);UUID uuid=(UUID)binding.getClass().getField("this").get(binding);if(factory<1||factory>65535||!host.classes.containsKey(factory)||label==null||label.length()>512)throw new IOException("quick_binding_invalid");result.put(slot,new GameItems.QuickBinding(factory,label,uuid));
        }
        var raw=new ByteArrayOutputStream();write.invoke(value,new DataOutputStream(raw));if(raw.size()>16384)throw new IOException("quick_slots_size");var wire=new ByteArrayOutputStream();syncWrite.invoke(syncPacket.newInstance(value),new DataOutputStream(wire));return new GameItems.QuickSlots(raw.toByteArray(),szserver.net.Wire.dynamic("1309545617",wire.toByteArray()),result);
    }
    GameItems.QuickSlots read(byte[] raw)throws Exception{return snapshot(decode(raw));}
    GameItems.QuickSlots bind(byte[] raw,int slot,byte[] source,String label)throws Exception{
        index(slot);if(label==null||label.length()>512)throw new IOException("quick_binding_label");Object value=decode(raw);if(source==null){set.invoke(value,slot,null);return snapshot(value);}Object item=stack(source);Set<Integer> ids=new HashSet<>();for(var binding:((Map<?,?>)entries.get(value)).values())ids.add(binding.getClass().getField("throw").getInt(binding));ids.add((Integer)id.invoke(item));for(int factory:ids)host.factory(factory);
        return host.bound(ids,()->{Object factory=host.factory((Integer)id.invoke(item));if(!capability.isInstance(factory))throw new IOException("quick_item_incompatible");assign.invoke(value,null,slot,item,label);return snapshot(value);});
    }
    Inventory.Address select(byte[] raw,int slot,Map<Inventory.Address,ItemStack> candidates)throws Exception{
        index(slot);Object binding=lookup.invoke(decode(raw),slot);if(binding==null)return null;int factory=binding.getClass().getField("throw").getInt(binding);if(!capability.isInstance(host.factory(factory)))return null;Set<Integer> ids=new HashSet<>();ids.add(factory);for(var e:candidates.entrySet())if(e.getKey().aggregate()==1&&e.getKey().container()==0)ids.add(e.getValue().id());
        // Only native quick-capable factories are needed; unrelated cargo must not construct world/render factories.
        for(int candidate:ids)if(capability.isAssignableFrom(host.type(host.classes.get(candidate))))host.factory(candidate);
        return host.bound(ids,()->{Comparator<Object> order=(Comparator<Object>)comparator.invoke(host.factory(factory),new Object[]{null});Inventory.Address selected=null;Object selectedItem=null;
            for(var e:new TreeMap<>(candidates).entrySet())if(e.getKey().aggregate()==1&&e.getKey().container()==0){byte[] wire=szserver.net.InventoryPackets.stack(e.getValue());Object item=stack(Arrays.copyOfRange(wire,1,wire.length));if(!(Boolean)matches.invoke(binding,null,item))continue;if(selectedItem==null||order.compare(selectedItem,item)<0){selected=e.getKey();selectedItem=item;}}return selected;
        });
    }
    synchronized ClientStateAdapter.QuickBindRequest bindRequest(byte[] raw)throws Exception{
        if(raw.length>2048)throw new IOException("quick_binding_request_size");var in=new BoundedNativeInput(raw);bindRead.invoke(bindPacket,in);if(in.available()!=0)throw new IOException("quick_binding_request_trailing");var bytes=new ByteArrayOutputStream();bindWrite.invoke(bindPacket,new DataOutputStream(bytes));if(!Arrays.equals(raw,bytes.toByteArray()))throw new IOException("quick_binding_request_noncanonical");int slot=bindIndex.getInt(bindPacket);index(slot);String label=(String)bindLabel.get(bindPacket);if(label.length()>512)throw new IOException("quick_binding_label");Object source=bindSource.get(bindPacket);return new ClientStateAdapter.QuickBindRequest((Integer)owner.invoke(bindPacket),slot,source==null?null:new Inventory.Address((Integer)addressAggregate.invoke(source),(Integer)addressContainer.invoke(source),(Integer)addressSlot.invoke(source)),label);
    }
    synchronized int useRequest(byte[] raw)throws Exception{if(raw.length!=4)throw new IOException("quick_use_size");useRead.invoke(usePacket,new BoundedNativeInput(raw));int index=useIndex.getInt(usePacket);index(index);return index;}
}
