package replay;

import java.io.*;

/** Full inherited spawn header from 67380058, not just its subclass prefix. */
final class SourceSpawn {
    record State(int entity,int x,int y,int z,int yaw,int pitch,byte[] typeState,byte[] attributes,byte[] components) {}
    static byte[] blob(DataInput in)throws IOException {int length=(int)SourceAdapters.var(in,5);return length==0?null:SourceAdapters.bytes(in,length);}
    static State read(DataInput in)throws IOException {
        int pitch=Motion.r8(in.readUnsignedByte(),4);int y=in.readInt()-635337538;
        int z=Integer.rotateRight(in.readInt(),11)+1382185604;int yaw=(byte)(in.readByte()-62);
        int x=in.readInt()-1852056439-595658098;int entity=Integer.rotateRight(in.readInt(),31)^0x33C76438;
        return new State(entity,x,y,z,yaw,(byte)pitch,blob(in),blob(in),blob(in));
    }
    static void nativeFields(Object object,State value)throws Exception {
        put(object,"do",value.entity()-1630978953);
        put(object,"case",Integer.rotateLeft(value.x(),17)-1385050365);
        put(object,"final",value.y()-291337310-814158175-1210348287);
        put(object,"double",(value.z()+716018694)^0xC7A787D8);
        put(object,"continue",(byte)(value.yaw()^0x1A));
        put(object,"void",(byte)(Motion.r8(value.pitch(),3)+112+99));
        put(object,"break",value.typeState());put(object,"instanceof",value.attributes());put(object,"boolean",value.components());
    }
    static void put(Object object,String name,Object value)throws Exception {
        // Subclasses reuse obfuscated names, including boolean as a scalar byte.
        var field=Native.type("1570813014").getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
}
