package szserver.client;

import java.io.*;import java.nio.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.*;
import szserver.net.Wire;

/** Original packet/component readers and writers, isolated on the authoring worker. */
public final class NpcEditorCodec implements AutoCloseable {
    private final ClientPackets packets;private final ClassLoader loader;
    public NpcEditorCodec(Path runtime)throws Exception{packets=new ClientPackets(runtime);Field f=ClientPackets.class.getDeclaredField("loader");f.setAccessible(true);loader=(ClassLoader)f.get(packets);}
    private Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
    public static Object field(Object value,String name)throws Exception{return value.getClass().getField(name).get(value);}
    private static void set(Object value,String name,Object next)throws Exception{value.getClass().getField(name).set(value,next);}
    public Object decode(String name,byte[] data)throws Exception{
        if(name.equals("466145232")){
            if(data.length<12||data.length>32768)throw new IOException("npc_component_packet_limit");var b=ByteBuffer.wrap(data);int component=b.getInt(),n=b.getInt();if(component<0||component>4||n!=b.remaining())throw new IOException("npc_component_layout");byte[] encoded=new byte[n];b.get(encoded);byte[] raw;
            if((encoded[0]&255)==31&&(encoded[1]&255)==139){try(var gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(encoded))){raw=gzip.readNBytes(65537);}}
            else{long size=com.github.luben.zstd.Zstd.decompressedSize(encoded);if(size<4||size>65536)throw new IOException("npc_component_expansion_limit");raw=com.github.luben.zstd.Zstd.decompress(encoded,(int)size);}
            if(raw.length>65536)throw new IOException("npc_component_expansion_limit");SceneNbt.readTile(raw);
        }
        return packets.decode(name,data);
    }
    public Object ref(UUID id)throws Exception{return type("58840983").getConstructor(UUID.class).newInstance(id);}
    public UUID refId(Object ref)throws Exception{if(!ref.getClass().getName().equals("58840983"))throw new IllegalArgumentException("runtime_npc_reference_not_supported");return (UUID)field(ref,"finally");}
    public byte[] componentPacket(int component,byte[] nbt)throws Exception{Object p=make("466145232",Map.of("int",type("821128913").getEnumConstants()[component],"private",compound(nbt)));return Wire.bytes(out->p.getClass().getMethod("switch",DataOutput.class).invoke(p,out));}
    public byte[] component(Object component,byte[] nbt)throws Exception{return encoded("610316810",make("610316810",Map.of("do",-2000000,"implements",component,"for",compound(nbt))));}
    private Object compound(byte[] bytes)throws Exception{SceneNbt.readTile(bytes);return type("1464336358").getMethod("int",DataInput.class).invoke(null,new DataInputStream(new ByteArrayInputStream(bytes)));}
    public byte[] nbt(Object compound)throws Exception{return Wire.bytes(out->type("1464336358").getMethod("char",type("98008865"),DataOutput.class).invoke(null,compound,out));}
    private Object make(String name,Map<String,Object> fields)throws Exception{Object p=type(name).getConstructor().newInstance();for(var f:fields.entrySet())set(p,f.getKey(),f.getValue());return p;}
    private byte[] encoded(String name,Object p)throws Exception{return Wire.dynamic(name,Wire.bytes(out->type(name).getMethod("switch",DataOutput.class).invoke(p,out)));}
    public record Summary(UUID id,String key,String name,String description){}
    public byte[] list(List<Summary> rows)throws Exception{List<Object> list=new ArrayList<>();for(var r:rows)list.add(type("1203574050").getConstructor(type("1058130208"),String.class,String.class,String.class,int.class,String.class,long.class).newInstance(ref(r.id),r.name,r.key,r.description,0,"custom-server",0L));return encoded("306881531",make("306881531",Map.of("byte",true,"int",list)));}
    public byte[] locations(UUID id)throws Exception{return encoded("1295176231",make("1295176231",Map.of("for",ref(id),"return",List.of())));}
    public byte[] open(UUID id,byte[] entity)throws Exception{Object context=type("403066430").getConstructor(type("241824258"),type("1058130208")).newInstance(type("241824258").getField("SHARED").get(null),ref(id));String[] strings=(String[])type("1281655698").getField("if").get(null);var values=new LinkedHashMap<>(SceneNbt.readTile(entity).values());values.put(strings[39125],SceneNbt.string(strings[7758]));byte[] selfDescribing=SceneNbt.writeTile(new SceneNbt.Root("",values));return encoded("868626947",make("868626947",Map.of("goto",-2000000,"do",context,"static",compound(selfDescribing),"instanceof",false,"case",true,"native",false)));}
    public NpcVisuals.Profile profile(int index,String key,byte[] config,NpcVisuals.Profile base)throws Exception{
        Object p=type("936207013").getConstructor().newInstance();var in=new DataInputStream(new ByteArrayInputStream(base.data()));if(in.readInt()!=0)throw new IOException("npc_initial_state_map");Arrays.stream(type("936207013").getMethods()).filter(m->m.getName().equals("int")&&m.getReturnType()==void.class&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{DataInput.class})).findFirst().orElseThrow().invoke(p,in);if(in.available()!=0)throw new IOException("npc_profile_trailing");Object display=field(p,"switch");display.getClass().getMethod("boolean",type("98008865")).invoke(display,compound(config));String skin=(String)field(display,"assert"),animation=(String)field(display,"final");if(skin.length()>64||animation.length()>64)throw new IllegalArgumentException("npc_display_limit");byte[] bytes=Wire.bytes(out->{out.writeInt(0);type("936207013").getMethod("catch",DataOutput.class).invoke(p,out);});UUID marker=(UUID)field(p,"int");byte[] needle=ByteBuffer.allocate(16).putLong(marker.getMostSignificantBits()).putLong(marker.getLeastSignificantBits()).array();int offset=-1;for(int i=0;i<=bytes.length-16;i++)if(Arrays.equals(Arrays.copyOfRange(bytes,i,i+16),needle)){if(offset!=-1)throw new IOException("npc_profile_uuid_ambiguous");offset=i;}return new NpcVisuals.Profile(index,key,skin,animation,24,offset,bytes);
    }
    public void close()throws Exception{packets.close();}
}
