package szserver.client;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BiFunction;

/** Restores only the stripped SERVER factory. Client selection and the native entity constructor stay intact. */
final class NativeCreatureServerFactory {
    static final String NATIVE="1652313690";
    static final String LOGIC="szserver.bridge.CreatureServerLogic";
    static final String SHA="f1cb5098c9568290807625ce9bc3bc63e96bd63e773a10dbd1f3cbfa9a5cb81f";

    static void install(Path runtime,BiFunction<String,byte[],Class<?>> define)throws Exception {
        byte[] original=Files.readAllBytes(runtime.resolve("classes/"+NATIVE+".class"));
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)).equals(SHA))throw new IOException("creature_server_factory_release");
        define.apply(LOGIC,NativeActorBridge.creatureLogic());
        define.apply(NATIVE,patch(original));
    }

    /** Replace one exact static null-return method; preserve every other member and attribute byte for byte. */
    static byte[] patch(byte[] raw)throws IOException {
        ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt()!=0xcafebabe)throw new IOException("creature_factory_class");
        in.position(8);int count=Short.toUnsignedInt(in.getShort());Map<Integer,String> utf=new HashMap<>();
        for(int n=1;n<count;n++)switch(Byte.toUnsignedInt(in.get())){
            case 1->{int size=Short.toUnsignedInt(in.getShort());byte[] value=new byte[size];in.get(value);utf.put(n,new String(value,java.nio.charset.StandardCharsets.UTF_8));}
            case 3,4->in.position(in.position()+4);case 5,6->{in.position(in.position()+8);n++;}
            case 7,8,16,19,20->in.position(in.position()+2);case 9,10,11,12,17,18->in.position(in.position()+4);case 15->in.position(in.position()+3);
            default->throw new IOException("creature_factory_constant");
        }
        int poolEnd=in.position();in.position(poolEnd+6);int interfaces=Short.toUnsignedInt(in.getShort());in.position(in.position()+2*interfaces);
        int fields=Short.toUnsignedInt(in.getShort());for(int n=0;n<fields;n++){in.position(in.position()+6);skip(in);}
        int methods=Short.toUnsignedInt(in.getShort()),start=-1,end=-1,codeName=-1;
        for(int n=0;n<methods;n++){
            int flags=Short.toUnsignedInt(in.getShort());String name=utf.get(Short.toUnsignedInt(in.getShort())),descriptor=utf.get(Short.toUnsignedInt(in.getShort()));
            int attributes=Short.toUnsignedInt(in.getShort());
            for(int a=0;a<attributes;a++){
                int at=in.position(),key=Short.toUnsignedInt(in.getShort()),size=in.getInt();
                if(name.equals("volatile")&&descriptor.equals("(L1422788721;)L1070955899;")&&"Code".equals(utf.get(key))){
                    if(start!=-1||(flags&8)==0||size<14||in.getShort()!=1||in.getShort()!=1||in.getInt()!=3||in.get()!=0||in.get()!=1||Byte.toUnsignedInt(in.get())!=176)throw new IOException("creature_factory_server_boundary_changed");
                    start=at;end=at+6+size;codeName=key;
                }
                in.position(at+6+size);
            }
        }
        if(start<0)throw new IOException("creature_factory_server_boundary_missing");
        var added=new ByteArrayOutputStream();var constants=new DataOutputStream(added);
        constants.writeByte(1);constants.writeUTF(LOGIC.replace('.','/'));constants.writeByte(7);constants.writeShort(count);
        constants.writeByte(1);constants.writeUTF("<init>");constants.writeByte(1);constants.writeUTF("(L1422788721;)V");
        constants.writeByte(12);constants.writeShort(count+2);constants.writeShort(count+3);
        constants.writeByte(10);constants.writeShort(count+1);constants.writeShort(count+4);
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.write(raw,0,8);out.writeShort(count+6);out.write(raw,10,poolEnd-10);out.write(added.toByteArray());out.write(raw,poolEnd,start-poolEnd);
        byte[] code={(byte)187,(byte)((count+1)>>>8),(byte)(count+1),89,42,(byte)183,(byte)((count+5)>>>8),(byte)(count+5),(byte)176};
        out.writeShort(codeName);out.writeInt(12+code.length);out.writeShort(3);out.writeShort(1);out.writeInt(code.length);out.write(code);out.writeShort(0);out.writeShort(0);
        out.write(raw,end,raw.length-end);return bytes.toByteArray();
    }
    private static void skip(ByteBuffer in){int count=Short.toUnsignedInt(in.getShort());for(int n=0;n<count;n++){in.getShort();int size=in.getInt();in.position(in.position()+size);}}
    private NativeCreatureServerFactory(){}
}
