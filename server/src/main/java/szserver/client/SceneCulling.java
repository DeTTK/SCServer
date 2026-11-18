package szserver.client;

import szserver.world.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Generic visibility fields of 249806; called only on scene/editor workers. */
public final class SceneCulling {
    public record State(UUID volume,boolean ignored){}
    private static int referenceAt(byte[] raw,String behavior){
        var prefix=SceneObject.prefix(raw);
        return SceneAttributes.layout(raw,behavior).generic().referenceAt();
    }
    public static State read(SceneObject object,String behavior){
        byte[] raw=object.record();int at=referenceAt(raw,behavior);boolean present=(SceneObject.prefix(raw).flags()&4)!=0;
        ByteBuffer in=ByteBuffer.wrap(raw);in.position(at);UUID volume=present?new UUID(in.getLong(),in.getLong()):null;
        return new State(volume,ScenePayload.bool(in));
    }
    /** null volume clears the link only when replaceVolume is true; null ignored keeps its flag. */
    public static SceneObject apply(SceneObject object,String behavior,boolean replaceVolume,UUID volume,Boolean ignored){
        byte[] raw=object.record();int at=referenceAt(raw,behavior),flags=SceneObject.prefix(raw).flags();
        boolean present=(flags&4)!=0;int oldSize=present?16:0,newSize=replaceVolume?(volume==null?0:16):oldSize;
        if(raw.length-oldSize+newSize>ScenePayload.MAX_OBJECT)throw new IllegalArgumentException("scene_object_bounds");
        byte[] changed=new byte[raw.length-oldSize+newSize];System.arraycopy(raw,0,changed,0,at);
        if(replaceVolume&&volume!=null)ByteBuffer.wrap(changed).putLong(at,volume.getMostSignificantBits()).putLong(at+8,volume.getLeastSignificantBits());
        else if(!replaceVolume&&present)System.arraycopy(raw,at,changed,at,16);
        System.arraycopy(raw,at+oldSize,changed,at+newSize,raw.length-at-oldSize);
        if(replaceVolume)ByteBuffer.wrap(changed).putInt(2,volume==null?flags&~4:flags|4);
        if(ignored!=null)changed[at+newSize]=(byte)(ignored?1:0);
        SceneAttributes.validateCurrent(changed,behavior);
        return Arrays.equals(raw,changed)?object:new SceneObject(object.asset(),changed);
    }
    private SceneCulling(){}
}
