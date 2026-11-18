package replay;

import java.io.*;
import java.util.*;

/** Creates a quarantined scene entity from its own spawn recipe, without world admission,
 * gameplay handler dispatch, packet-prefix replay or a simulation tick. */
final class NativeSceneEntityFactory {
    final NativePackets packets;
    NativeSceneEntityFactory(NativePackets packets){this.packets=packets;}
    Object create(Object sceneWorld,SceneTracks.ObjectState selected,int runtimeId)throws Exception {
        // Source network IDs may be reused. Native renderer caches also key actors
        // by ID, so simultaneous quarantined incarnations need separate runtime IDs.
        if(runtimeId>=-1_000_000_000||Native.call(sceneWorld,"continue","1217489680",runtimeId)!=null)throw new IOException("Scene runtime ID is not isolated");
        for(Object pending:(Set<?>)Native.call(sceneWorld,"throws","java.util.Set"))if(((Number)Native.call(pending,"for","int")).intValue()==runtimeId)throw new IOException("Scene runtime ID is already pending");
        if(!(selected.channels().get("spawn-event") instanceof SceneTracks.Bytes recipe)){
            if(SceneNativeTemplate.supports(selected.type()))return SceneNativeTemplate.create(sceneWorld,selected,runtimeId);
            throw new IOException("Selected entity lacks its creation recipe: "+selected.id());
        }
        DemoFile.Event event=SceneSpawnRecipe.decode(recipe);if(event.timeNs()>packets.demo.durationNs)throw new IOException("Creation recipe outside recording");
        Object packet=packets.decode(event);Class<?> spawn=Native.type("1570813014");
        if(!spawn.isInstance(packet))throw new IOException("Scene creation recipe is not a native entity spawn");
        int id=((Number)Native.call(spawn,"final","int",field(packet,"do"))).intValue();if(id!=selected.id().networkId())throw new IOException("Scene recipe/entity identity mismatch");
        Object entity=null;
        ReplayDecode.begin(packets.registryMaps);if(packets.adapters!=null)ReplayDecode.use(packets.adapters);ReplayClock.beginDecode();
        try{
            entity=Native.call(packet,"final","1217489680",sceneWorld);
            if(!entity.getClass().getName().equals(selected.type()))throw new IOException("Scene creation recipe produced another entity type");
            Native.call(entity,"static","void",runtimeId);
            double[] p=SceneEnvironmentCodec.numbers(selected.channels().get("position"),3);Native.call(entity,"throw","void",p[0],p[1],p[2]);
            if(Native.type("820497227").isInstance(entity)){
                double[] look=SceneEnvironmentCodec.numbers(selected.channels().get("look"),2);Native.call(entity,"class","void",(float)look[0],(float)look[1]);
            }
            byte[] typeState=(byte[])field(packet,"break"),attributes=(byte[])field(packet,"instanceof"),components=(byte[])field(packet,"boolean");
            Object created=entity;
            if(typeState!=null&&Native.type("1532297644").isInstance(entity))read(typeState,input->Native.call(created,"default","void",input));
            if(attributes!=null)read(attributes,input->Native.call(Native.call(created,"float","1536302723"),"native","void",input));
            if(components!=null)read(components,input->{for(Object component:(List<?>)Native.call(created,"static","java.util.List"))Native.call(component,"if","void",input,Native.field(Native.type("1120690568"),"OTHERS"));});
        }catch(Exception failure){
            if(entity!=null)try{Native.call(sceneWorld,"default","void",List.of(entity),new HashSet<>());}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
        }finally{ReplayClock.endDecode();ReplayDecode.end();}
        return entity;
    }
    static Object field(Object packet,String name)throws Exception {var f=Native.type("1570813014").getDeclaredField(name);f.setAccessible(true);return f.get(packet);}
    interface Reader {void read(Object input)throws Exception;}
    static void read(byte[] bytes,Reader reader)throws Exception {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){reader.read(input);if(input.available()!=0)throw new IOException("Unread native scene creation data");}
    }
}
