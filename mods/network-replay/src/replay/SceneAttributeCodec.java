package replay;

import java.io.*;
import java.time.*;

/** Scene values preserve temporal sentinels and nanoseconds beyond the network codec's range. */
final class SceneAttributeCodec {
    static final java.util.Set<Class<?>> IMMUTABLE_TYPES=java.util.Set.of(Boolean.class,Byte.class,Short.class,Integer.class,Long.class,Float.class,Double.class,Character.class,String.class,java.util.UUID.class,Instant.class,Duration.class);
    static boolean shareable(Class<?> type){return type.isEnum()||IMMUTABLE_TYPES.contains(type);}
    record Encoded(String codec,byte[] bytes) {Encoded {bytes=bytes.clone();}public byte[] bytes(){return bytes.clone();}}
    static Encoded encode(Object attribute)throws Exception {
        Class<?> type=(Class<?>)Native.field(attribute,"return");Object value=Native.field(attribute,"do");String codec="native-v1";
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        if(type==Instant.class){codec="instant-seconds-nanos-v1";out.writeBoolean(value==null);if(value!=null){Instant v=(Instant)value;out.writeLong(v.getEpochSecond());out.writeInt(v.getNano());}}
        else if(type==Duration.class){codec="duration-seconds-nanos-v1";out.writeBoolean(value==null);if(value!=null){Duration v=(Duration)value;out.writeLong(v.getSeconds());out.writeInt(v.getNano());}}
        else if(type.getName().equals("1160390453")){
            // Incoming equipment wrappers deliberately have an empty write cache.
            // The network writer assumes a server-owned prefilled cache; scene
            // export must serialize the actual selected item instead of that cache.
            codec="equipment-value-v1";out.writeBoolean(value==null);
            if(value!=null)Native.call(type,"long","void",Native.call(value,"instanceof","211097523"),out);
        }
        else Native.call(attribute,"if","void",out);
        return new Encoded(codec,bytes.toByteArray());
    }
    /** Decode into a separate value; do not modify a current entity during validation. */
    static Object decode(Class<?> type,String codec,byte[] bytes)throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));Object value;
        if(codec.equals("native-v1")||codec.equals("equipment-value-v1")){
            if(codec.equals("equipment-value-v1")&&!type.getName().equals("1160390453"))throw new IOException("Scene equipment codec/type mismatch");
            try(var canonical=SceneEquipmentRead.canonical()){
                value=Native.call(Native.type("1536302723"),"enum","java.lang.Object",null,in,type);
            }
            if(value!=null&&codec.equals("equipment-value-v1"))value=type.getConstructor(Native.type("211097523"),byte[].class).newInstance(Native.call(value,"instanceof","211097523"),java.util.Arrays.copyOfRange(bytes,1,bytes.length));
        }
        else {
            if(!codec.equals(type==Instant.class?"instant-seconds-nanos-v1":type==Duration.class?"duration-seconds-nanos-v1":""))throw new IOException("Scene attribute codec/type mismatch");
            if(in.readBoolean())value=null;else {long seconds=in.readLong();int nanos=in.readInt();if(nanos<0||nanos>=1_000_000_000)throw new IOException("Invalid scene temporal fraction");value=type==Instant.class?Instant.ofEpochSecond(seconds,nanos):Duration.ofSeconds(seconds,nanos);}
        }
        if(in.available()!=0)throw new IOException("Trailing scene attribute data");return value;
    }
}
