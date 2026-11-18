package replay;

import java.io.*;

/** The animation controller can retain a held model independently of entity slots. */
final class SceneControllerItem {
    static Object controller(Object entity)throws Exception {
        if(Native.type("200241649").isInstance(entity)){
            Object component=Native.call(Native.type("1478690128"),"new","1579323378",entity);return component==null?null:Native.call(component,"static","1805188833");
        }
        if(Native.type("1848416318").isInstance(entity)){
            Object owner=Native.field(entity,"native");return owner!=null&&Native.type("859153379").isInstance(owner)?Native.call(owner,"short","1805188833"):null;
        }
        return null;
    }
    static SceneTracks.Bytes encode(Object item)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBoolean(item==null);
        if(item!=null)Native.call(Native.type("1160390453"),"long","void",item,out);
        return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Object decode(SceneTracks.Bytes encoded)throws Exception {
        Object wrapper=SceneAttributeCodec.decode(Native.type("1160390453"),"equipment-value-v1",encoded.data());
        return wrapper==null?null:Native.call(wrapper,"instanceof","211097523");
    }
}
