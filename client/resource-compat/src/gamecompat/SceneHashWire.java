package gamecompat;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Name hashes on the wire; old ordinal input is retained for authored local maps only. */
public final class SceneHashWire {
    public static final short MARKER=-1;
    public static final java.util.concurrent.atomic.LongAdder hashReads=new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder missingHashes=new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder legacyReads=new java.util.concurrent.atomic.LongAdder();
    private static final ClassValue<Registry> REGISTRIES=new ClassValue<>() {
        protected Registry computeValue(Class<?> type){return new Registry(type);}
    };
    private static final class Registry {
        final Class<?> type;final Field names,name;final Method legacy;
        volatile Map<Long,Object> hashes;
        Registry(Class<?> type){try{this.type=type;names=type.getField("char");name=type.getField("while");legacy=type.getMethod("public",short.class);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        synchronized void prepare(){
            try {
                Map<Long,Object> next=new HashMap<>();
                for(Object value:((Map<?,?>)names.get(null)).values()) {
                    String text=(String)name.get(value);long hash=hash(text);
                    Object old=next.putIfAbsent(hash,value);
                    if(old!=null&&old!=value)throw new IllegalStateException("scene_hash_collision:"+text);
                }
                hashes=Map.copyOf(next);
            }catch(IllegalAccessException e){throw new IllegalStateException(e);}
        }
    }
    public static long hash(String name){long h=0xcbf29ce484222325L;for(byte b:name.getBytes(StandardCharsets.UTF_8)){h^=(long)b;h*=0x100000001b3L;}return h;}
    public static void prepare(Class<?> type){REGISTRIES.get(type).prepare();}
    public static void clear(Class<?> type){REGISTRIES.remove(type);}
    public static Object read(DataInput in,Class<?> type)throws IOException {
        short prefix=in.readShort();Registry registry=REGISTRIES.get(type);
        if(prefix==MARKER){long hash=in.readLong();if(registry.hashes==null)registry.prepare();Object value=registry.hashes.get(hash);hashReads.increment();if(value==null)missingHashes.increment();return value;}
        if(prefix<0)throw new IOException("invalid_scene_type_marker");
        legacyReads.increment();
        try{return registry.legacy.invoke(null,prefix);}catch(ReflectiveOperationException e){throw new IOException("legacy_scene_type",e);}
    }
    public static void write(DataOutput out,Object factory)throws IOException {
        try{Registry registry=REGISTRIES.get(factory.getClass());out.writeShort(MARKER);out.writeLong(hash((String)registry.name.get(factory)));}
        catch(IllegalAccessException e){throw new IOException("scene_type_name",e);}
    }
}
