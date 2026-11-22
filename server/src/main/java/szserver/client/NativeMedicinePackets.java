package szserver.client;

import java.io.*;
import java.lang.reflect.*;
import szserver.inventory.Inventory;

/** Original use-request reader, warmed once. Reading never executes a client-sensitive packet constructor. */
final class NativeMedicinePackets {
    private final Object packet;
    private final Method read,owner,sequence,address,aggregate,container,slot;
    NativeMedicinePackets(NativeItemFactories host)throws Exception{
        Class<?> type=host.type("1166767938"),base=host.type("1799496656"),location=host.type("949164803");
        read=NativeMethods.instance(type,"extends",void.class,DataInput.class);owner=NativeMethods.instance(base,"volatile",int.class);sequence=NativeMethods.instance(base,"boolean",int.class);address=NativeMethods.instance(type,"new",location);
        aggregate=NativeMethods.instance(location,"protected",int.class);container=NativeMethods.instance(location,"break",int.class);slot=NativeMethods.instance(location,"default",int.class);
        packet=type.getConstructor().newInstance();Object sample=type.getConstructor(location).newInstance(location.getConstructor(int.class,int.class,int.class).newInstance(1,0,0));base.getField("volatile").setInt(sample,0);base.getField("this").setInt(sample,7);
        var bytes=new ByteArrayOutputStream();NativeMethods.instance(type,"switch",void.class,DataOutput.class).invoke(sample,new DataOutputStream(bytes));var decoded=read(bytes.toByteArray());if(decoded.owner()!=0||decoded.sequence()!=7||!decoded.address().equals(new Inventory.Address(1,0,0)))throw new IOException("native_medicine_packet_warmup");
    }
    synchronized ClientStateAdapter.MedicineRequest read(byte[] raw)throws Exception{
        if(raw.length>64)throw new IOException("medicine_request_size");var input=new BoundedNativeInput(raw);read.invoke(packet,input);if(input.available()!=0)throw new IOException("medicine_request_tail");Object value=address.invoke(packet);
        Inventory.Address result=value==null?null:new Inventory.Address((Integer)aggregate.invoke(value),(Integer)container.invoke(value),(Integer)slot.invoke(value));return new ClientStateAdapter.MedicineRequest((Integer)owner.invoke(packet),(Integer)sequence.invoke(packet),result);
    }
}
