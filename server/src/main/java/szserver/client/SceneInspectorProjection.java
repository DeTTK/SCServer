package szserver.client;

import szserver.world.*;
import szserver.net.Wire;
import java.nio.*;
import java.util.*;

/** Generic native Inspector projection on editor workers. Behavior bytes remain opaque and exact. */
public final class SceneInspectorProjection {
    /** Translation-only old blob projection when a FULL_SAVE row has no stored matrix. */
    public static SceneObject position(SceneObject object,float x,float y,float z){
        byte[] raw=object.record();int at=SceneObject.prefix(raw).transformAt()+36;ByteBuffer.wrap(raw).putFloat(at,x).putFloat(at+4,y).putFloat(at+8,z);return new SceneObject(object.asset(),raw);
    }
    public static SceneObject transform(SceneObject object,float[] matrix){
        if(matrix.length!=16)throw new IllegalArgumentException("scene_editor_matrix_size");byte[] raw=object.record();ByteBuffer out=ByteBuffer.wrap(raw);int at=SceneObject.prefix(raw).transformAt();int[] indexes={0,1,2,4,5,6,8,9,10,12,13,14};for(int i=0;i<12;i++){float value=matrix[indexes[i]];if(!Float.isFinite(value))throw new IllegalArgumentException("scene_editor_matrix");out.putFloat(at+i*4,value);}return new SceneObject(object.asset(),raw);
    }
    private static void vector(java.io.DataOutputStream out,World.Vec vector)throws java.io.IOException{out.writeFloat((float)vector.x());out.writeFloat((float)vector.y());out.writeFloat((float)vector.z());}
    public static SceneObject apply(SceneObject object,SceneEditPackets.Edit edit){
        return apply(object,edit,null);
    }
    public static SceneObject apply(SceneObject object,SceneEditPackets.Edit edit,String behavior){
        SceneObject moved=transform(object,edit.transform());var p=edit.properties();if(p==null)return moved;byte[] raw=moved.record();SceneObject.Prefix prefix=SceneObject.prefix(raw);int flags=prefix.flags(),packed=prefix.packed();
        var layout=SceneAttributes.layout(raw,behavior);ByteBuffer in=ByteBuffer.wrap(raw);int headerEnd=7+((flags&0x80)!=0?16:0);in.position(headerEnd);if((flags&0x8000)!=0)ScenePayload.utf(in);int legacyAt=in.position();if((flags&0x4000)!=0)in.getInt();if((flags&0x2000)!=0)in.getInt();int legacyEnd=in.position();in.position(prefix.transformAt()+48);byte kind=in.get();String oldInstance=null;if((flags&0x1000)!=0)oldInstance=utf(ScenePayload.utf(in));int mode=(flags&0x800)!=0?in.get()&255:0;byte fixed=in.get(),render=in.get();byte[] nativeName=(flags&0x200)!=0?ScenePayload.utf(in):new byte[0];int nbtEnd=layout.tailAt();byte[] behaviour=Arrays.copyOfRange(raw,prefix.behaviourAt(),nbtEnd);int editorMode,editorFlags;
        // The generic wire tail writes editor flags BEFORE editor mode. Locate it independently below.
        in.position(layout.generic().editorAt());editorFlags=(flags&0x40)!=0?in.getInt():-1;editorMode=in.get()&255;int uuidAt=layout.generic().referenceAt();byte[] oldUuid=(flags&4)!=0?Arrays.copyOfRange(raw,uuidAt,uuidAt+16):new byte[0];byte[] developerPrefix=Arrays.copyOfRange(raw,nbtEnd,layout.generic().editorAt()),features=Arrays.copyOfRange(raw,layout.generic().featuresAt(),layout.generic().vector2At());
        if(p.mode()<0){int keptFlags=flags,keptPacked=packed;byte[] changed=Wire.bytes(out->{out.write(raw,0,2);out.writeInt(p.tag().isEmpty()?keptFlags&~0x8000:keptFlags|0x8000);out.writeByte(keptPacked);out.write(raw,7,headerEnd-7);if(!p.tag().isEmpty())out.writeUTF(p.tag());out.write(raw,legacyAt,raw.length-legacyAt);});return new SceneObject(object.asset(),changed);}
        flags&=~(0x8000|0x1000|0x800|0x40|0x20|0x10|8|0x10000|0x20000);if(!p.tag().isEmpty())flags|=0x8000;String instance=p.instanceName();if(instance!=null)flags|=0x1000;flags|=0x800|0x20|0x10|8|0x10000|0x20000;int ef=p.editorFlags()==null?editorFlags:p.editorFlags(),em=p.editorMode()==null?editorMode:p.editorMode();if(ef!=-1)flags|=0x40;packed=p.flag3()?packed|2:packed&~2;int nextFlags=flags,nextPacked=packed;byte[] projected=Wire.bytes(out->{
            out.write(raw,0,2);out.writeInt(nextFlags);out.writeByte(nextPacked);out.write(raw,7,headerEnd-7);if(!p.tag().isEmpty())out.writeUTF(p.tag());out.write(raw,legacyAt,legacyEnd-legacyAt);out.write(raw,legacyEnd,prefix.transformAt()+48-legacyEnd);out.writeByte(kind);if(instance!=null)out.writeUTF(instance);out.writeByte(p.mode());out.writeByte(fixed);out.writeByte(p.renderFlags());out.write(nativeName);out.write(behaviour);out.write(developerPrefix);if(ef!=-1)out.writeInt(ef);out.writeByte(em);vector(out,p.vector1());out.writeFloat(p.float1());out.writeBoolean(p.flag1());vector(out,p.vector3());out.write(oldUuid);out.writeBoolean(p.flag2());if((nextPacked&2)!=0)out.writeUTF(p.stateName()==null?"":p.stateName());out.writeBoolean(p.flag4());out.write(features);vector(out,p.vector2());out.writeFloat(p.float2());
        });return new SceneObject(object.asset(),projected);
    }
    private static String utf(byte[] raw){try{return Wire.input(raw).readUTF();}catch(java.io.IOException invalid){throw new IllegalArgumentException("scene_editor_utf",invalid);}}
    private SceneInspectorProjection(){}
}
