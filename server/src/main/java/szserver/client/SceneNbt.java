package szserver.client;

import szserver.net.Wire;
import java.io.*;
import java.util.*;

/** Lossless typed named NBT on scene workers, including native float-bit int arrays and mesh bytes. */
public final class SceneNbt {
    public record Tag(int type,Object value) {
        public Tag { Objects.requireNonNull(value);if(value instanceof List<?> list)value=List.copyOf(list);if(value instanceof Map<?,?> map)value=Collections.unmodifiableMap(new LinkedHashMap<>(map)); }
    }
    public record ListValue(int type,List<Tag> values) {public ListValue{values=List.copyOf(values);}}
    public record Root(String name,Map<String,Tag> values) {public Root{values=Collections.unmodifiableMap(new LinkedHashMap<>(values));}}
    public static Root read(byte[] bytes){
        SceneConfigurationPackets.validate(bytes);
        return readChecked(bytes);
    }
    /** Complete tile compounds can exceed the scene-specific 48 KiB cap. Worker-only. */
    public static Root readTile(byte[] bytes){
        if(bytes.length<4||bytes.length>1024*1024||bytes[0]!=10)throw new IllegalArgumentException("tile_nbt_size_or_root");
        try{java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(bytes);szserver.world.ScenePayload.namedNbt(input);if(input.hasRemaining())throw new IllegalArgumentException("tile_nbt_trailing");}catch(java.nio.BufferUnderflowException malformed){throw new IllegalArgumentException("tile_nbt_truncated",malformed);}
        return readChecked(bytes);
    }
    private static Root readChecked(byte[] bytes){
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){in.readByte();String name=in.readUTF();return new Root(name,compound(in));}catch(IOException invalid){throw new IllegalArgumentException("scene_settings_nbt",invalid);}
    }
    public static byte[] writeTile(Root root){byte[] bytes=Wire.bytes(out->{out.writeByte(10);out.writeUTF(root.name());writeCompound(out,root.values());});readTile(bytes);return bytes;}
    public static byte[] write(Root root){byte[] bytes=Wire.bytes(out->{out.writeByte(10);out.writeUTF(root.name());writeCompound(out,root.values());});SceneConfigurationPackets.validate(bytes);return bytes;}
    public static Tag number(int type,Number value){return new Tag(type,value);}
    public static Tag string(String value){return new Tag(8,value);}
    public static Tag ints(List<Integer> value){return new Tag(11,value);}
    public static Tag bytes(byte[] value){List<Byte> list=new ArrayList<>(value.length);for(byte b:value)list.add(b);return new Tag(7,list);}
    private static Map<String,Tag> compound(DataInputStream in)throws IOException{Map<String,Tag> map=new LinkedHashMap<>();for(int type;(type=in.readUnsignedByte())!=0;){String key=in.readUTF();if(map.put(key,readTag(in,type))!=null)throw new IOException("scene_settings_duplicate_key");}return map;}
    private static Tag readTag(DataInputStream in,int type)throws IOException{return new Tag(type,switch(type){
        case 1->in.readByte();case 2->in.readShort();case 3->in.readInt();case 4->in.readLong();case 5->in.readFloat();case 6->in.readDouble();case 8->in.readUTF();
        case 7->{int n=in.readInt();List<Byte> list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(in.readByte());yield list;}
        case 11->{int n=in.readInt();List<Integer> list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(in.readInt());yield list;}
        case 9->{int child=in.readUnsignedByte(),n=in.readInt();List<Tag> list=new ArrayList<>(n);for(int i=0;i<n;i++)list.add(readTag(in,child));yield new ListValue(child,list);}
        case 10->compound(in);default->throw new IOException("scene_settings_type");});}
    private static void writeCompound(DataOutputStream out,Map<String,Tag> map)throws IOException{for(var entry:map.entrySet()){out.writeByte(entry.getValue().type());out.writeUTF(entry.getKey());writeTag(out,entry.getValue());}out.writeByte(0);}
    @SuppressWarnings("unchecked") private static void writeTag(DataOutputStream out,Tag tag)throws IOException{switch(tag.type()){
        case 1->out.writeByte(((Number)tag.value()).byteValue());case 2->out.writeShort(((Number)tag.value()).shortValue());case 3->out.writeInt(((Number)tag.value()).intValue());case 4->out.writeLong(((Number)tag.value()).longValue());case 5->out.writeFloat(((Number)tag.value()).floatValue());case 6->out.writeDouble(((Number)tag.value()).doubleValue());case 8->out.writeUTF((String)tag.value());
        case 7->{var list=(List<Byte>)tag.value();out.writeInt(list.size());for(byte value:list)out.writeByte(value);}
        case 11->{var list=(List<Integer>)tag.value();out.writeInt(list.size());for(int value:list)out.writeInt(value);}
        case 9->{var list=(ListValue)tag.value();out.writeByte(list.type());out.writeInt(list.values().size());for(var value:list.values()){if(value.type()!=list.type())throw new IllegalArgumentException("scene_nbt_list_type");writeTag(out,value);}}
        case 10->writeCompound(out,(Map<String,Tag>)tag.value());default->throw new IllegalArgumentException("scene_settings_type");}}
    private SceneNbt(){}
}
