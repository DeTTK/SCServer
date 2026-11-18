package replay;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Source prefab keys are signed-byte FNV-1a 64; native keys are registry shorts. */
final class SourceGameObjects {
    static long key(String name){long hash=-3750763034362895579L;for(byte value:name.getBytes(StandardCharsets.UTF_8)){hash^=(long)value;hash*=1099511628211L;}return hash;}
    static byte[] payload(byte[] source,int defaultInstance)throws IOException {
        ByteArrayInputStream bytes=new ByteArrayInputStream(source);DataInputStream in=new DataInputStream(bytes);ByteArrayOutputStream translated=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(translated)){
            int flags=in.readInt();out.writeInt(flags);out.writeByte(in.readUnsignedByte());
            if((flags&128)!=0){out.writeLong(in.readLong());out.writeLong(in.readLong());}
            if((flags&32768)!=0)out.writeUTF(in.readUTF());
            if((flags&16384)!=0)out.writeInt(in.readInt());
            if((flags&8192)!=0)out.writeInt(in.readInt());
            out.writeInt((flags&1024)!=0?in.readInt():defaultInstance);out.write(in.readAllBytes());
        }return translated.toByteArray();
    }
}
