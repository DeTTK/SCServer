package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import szserver.inventory.Inventory;

/** Original warmed request readers/writers. Native inventory transactions remain owned by the journal. */
final class NativeItemSlotPackets {
    private record Codec(Object packet,Method read,Method write,Field target,Field source,Field slot){}
    private final Codec bolt,socket;private final Method owner,sequence,aggregate,container,slot;
    NativeItemSlotPackets(NativeItemFactories host)throws Exception{
        Class<?> address=host.type("949164803"),base=host.type("1799496656");owner=NativeMethods.instance(base,"volatile",int.class);sequence=NativeMethods.instance(base,"boolean",int.class);aggregate=NativeMethods.instance(address,"protected",int.class);container=NativeMethods.instance(address,"break",int.class);slot=NativeMethods.instance(address,"default",int.class);
        bolt=codec(host,"1271395251","class","synchronized",null);socket=codec(host,"1950979903","void","public","short");
        Object target=address.getConstructor(int.class,int.class,int.class).newInstance(1,0,7),source=address.getConstructor(int.class,int.class,int.class).newInstance(1,0,9);
        for(Codec codec:List.of(bolt,socket)){for(boolean attached:List.of(false,true)){codec.target().set(codec.packet(),target);codec.source().set(codec.packet(),attached?source:null);if(codec.slot()!=null)codec.slot().setInt(codec.packet(),2);base.getField("volatile").setInt(codec.packet(),0);base.getField("this").setInt(codec.packet(),19);var bytes=new ByteArrayOutputStream();codec.write().invoke(codec.packet(),new DataOutputStream(bytes));var result=read(codec,bytes.toByteArray());if(result.owner()!=0||result.sequence()!=19||!result.target().equals(new Inventory.Address(1,0,7))||(result.source()!=null)!=attached||codec.slot()!=null&&result.slot()!=2)throw new IOException("native_item_slot_packet_warmup");}}
    }
    private static Codec codec(NativeItemFactories host,String name,String target,String source,String slot)throws Exception{Class<?> type=host.type(name);return new Codec(type.getConstructor().newInstance(),NativeMethods.instance(type,"extends",void.class,DataInput.class),NativeMethods.instance(type,"switch",void.class,DataOutput.class),type.getField(target),type.getField(source),slot==null?null:type.getField(slot));}
    private Inventory.Address address(Object value)throws Exception{return value==null?null:new Inventory.Address((Integer)aggregate.invoke(value),(Integer)container.invoke(value),(Integer)slot.invoke(value));}
    synchronized ClientStateAdapter.ItemSlotRequest read(boolean isBolt,byte[] body)throws Exception{return read(isBolt?bolt:socket,body);}
    private ClientStateAdapter.ItemSlotRequest read(Codec codec,byte[] body)throws Exception{
        if(body.length>64)throw new IOException("item_slot_request_size");var in=new BoundedNativeInput(body);codec.read().invoke(codec.packet(),in);if(in.available()!=0)throw new IOException("item_slot_request_tail");var canonical=new ByteArrayOutputStream();codec.write().invoke(codec.packet(),new DataOutputStream(canonical));if(!Arrays.equals(body,canonical.toByteArray()))throw new IOException("item_slot_request_noncanonical");return new ClientStateAdapter.ItemSlotRequest((Integer)owner.invoke(codec.packet()),(Integer)sequence.invoke(codec.packet()),address(codec.target().get(codec.packet())),address(codec.source().get(codec.packet())),codec.slot()==null?0:codec.slot().getInt(codec.packet()));
    }
}
