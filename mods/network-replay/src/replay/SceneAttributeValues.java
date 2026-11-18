package replay;

import java.util.*;

/** Validates complete properties before publication, materializing mutable native
 * values only if that selected frame actually asks for them. Never shares a
 * mutable decoder result between frames or exposes its private encoded bytes. */
final class SceneAttributeValues {
    record Key(SceneIdentityAliases.Key bytes,Class<?> type,String codec){}
    record Prepared(Class<?> type,String codec,byte[] bytes,boolean shared,Object immutable){}
    static final class Value {
        final Prepared source;Object decoded;boolean ready;
        Value(Prepared source,Object decoded,boolean ready){this.source=source;this.decoded=decoded;this.ready=ready;}
        boolean reusable(){return source.shared;}
        synchronized Object get(){
            if(!ready){try{decoded=decode(source.type,source.codec,source.bytes);ready=true;}catch(Exception failure){throw new IllegalStateException("Validated scene attribute cannot be restored",failure);}}
            return decoded;
        }
    }
    final LinkedHashMap<Key,Prepared> prepared=new LinkedHashMap<>(16,.75f,true);
    final int limit;final long budget;long bytes,validations,materializations;
    SceneAttributeValues(){this(4096,16L*1024*1024);}
    SceneAttributeValues(int limit,long budget){this.limit=limit;this.budget=budget;}
    Value select(Class<?> type,String codec,SceneTracks.Bytes encoded)throws Exception {
        Key key=new Key(new SceneIdentityAliases.Key(encoded),type,codec);Prepared cached=prepared.get(key);
        if(cached!=null)return new Value(cached,cached.immutable,cached.shared);
        byte[] data=encoded.data();Object value=decode(type,codec,data);validations++;
        boolean shared=value==null||SceneAttributeCodec.shareable(type);
        Prepared selected=new Prepared(type,codec,data,shared,shared?value:null);
        if(data.length<=budget){
            while(!prepared.isEmpty()&&(prepared.size()>=limit||bytes+data.length>budget)){var it=prepared.entrySet().iterator();var old=it.next();bytes-=old.getValue().bytes.length;it.remove();}
            prepared.put(key,selected);bytes+=data.length;
        }
        return new Value(selected,value,true);
    }
    static Object decode(Class<?> type,String codec,byte[] data)throws Exception {
        String phase="scene-attribute-decode/"+type.getName();
        ReplayPerformance.enter(phase);try{return SceneAttributeCodec.decode(type,codec,data);}finally{ReplayPerformance.exit(phase);}
    }
    void clear(){prepared.clear();bytes=0;}
}
