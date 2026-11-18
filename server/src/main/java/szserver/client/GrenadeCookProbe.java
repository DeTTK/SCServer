package szserver.client;

import java.io.*;

/** QA supplies only elapsed charge to the untouched native grenade cooking method. */
final class GrenadeCookProbe {
    static byte[] bytes()throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0xcafebabe);out.writeShort(0);out.writeShort(49);out.writeShort(12);
        utf(out,"szserver/bridge/GrenadeCookProbe");clazz(out,1);utf(out,"850786143");clazz(out,3);utf(out,"Code");utf(out,"ticks");utf(out,"I");out.writeByte(12);out.writeShort(6);out.writeShort(7);out.writeByte(9);out.writeShort(2);out.writeShort(8);utf(out,"extends");utf(out,"(L1761650403;Z)I");
        out.writeShort(0x21);out.writeShort(2);out.writeShort(4);out.writeShort(0);out.writeShort(1);out.writeShort(1);out.writeShort(6);out.writeShort(7);out.writeShort(0);
        out.writeShort(1);out.writeShort(1);out.writeShort(10);out.writeShort(11);out.writeShort(1);out.writeShort(5);out.writeInt(17);out.writeShort(1);out.writeShort(3);out.writeInt(5);out.write(new byte[]{42,(byte)180,0,9,(byte)172});out.writeShort(0);out.writeShort(0);out.writeShort(0);return bytes.toByteArray();
    }
    private static void utf(DataOutputStream out,String text)throws IOException{out.writeByte(1);out.writeUTF(text);}
    private static void clazz(DataOutputStream out,int utf)throws IOException{out.writeByte(7);out.writeShort(utf);}
}
