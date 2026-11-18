package szserver.client;

import java.io.*;
import java.util.*;

/** Bounds checked before the reviewed native readers allocate arrays or recurse. */
final class BoundedNativeInput implements DataInput {
    private static final StackWalker WALKER=StackWalker.getInstance();
    private final DataInputStream input;
    private int bufferValue,bufferShift;
    BoundedNativeInput(byte[] bytes){input=new DataInputStream(new ByteArrayInputStream(bytes));}
    int available()throws IOException{return input.available();}
    @Override public int readInt()throws IOException{
        int value=input.readInt();StackWalker.StackFrame caller=WALKER.walk(frames->frames.skip(1).findFirst().orElseThrow());
        String type=caller.getClassName(),method=caller.getMethodName();
        if((type.equals("1181466981")||type.equals("905755434"))&&method.equals("extends")&&(value<0||value>256||value>available()))throw new IOException("native_actor_collection_limit");
        if(type.equals("1536302723")&&method.equals("enum")&&caller.getByteCodeIndex()==242){
            if(value<0||value>8192||value>available())throw new IOException("native_actor_property_nbt");
            input.mark(value);byte[] compressed=input.readNBytes(value);input.reset();NativeNbtLimits.expand(compressed);
        }
        if(type.equals("1943200621")&&method.equals("extends")){
            int at=caller.getByteCodeIndex();
            if(at==371||at==487){int unit=at==371?8:4;if(value!=13||(long)value*unit>available())throw new IOException("native_actor_pose_array");}
            if(at==1369&&(value<0||value>64||(long)value*48>available()))throw new IOException("native_actor_body_array");
        }
        int width=type.equals("58006653")&&method.equals("extends")&&caller.getByteCodeIndex()==46?4:
            type.equals("1046176253")&&method.equals("public")&&caller.getDescriptor().endsWith(")L98008865;")?1:
            type.equals("1046176253")&&method.equals("const")&&caller.getDescriptor().endsWith(")Ljava/util/BitSet;")?8:0;
        if(width!=0&&value>=0&&(value>8192||(long)value*width>available()))throw new IOException("native_array_exceeds_input");
        if(width==1&&value>=0){input.mark(value);byte[] compressed=input.readNBytes(value);input.reset();NativeNbtLimits.expand(compressed);}
        return value;
    }
    @Override public boolean readBoolean()throws IOException{
        long depth=WALKER.walk(frames->frames.filter(f->f.getClassName().equals("211097523")&&f.getMethodName().equals("<init>")).limit(9).count());
        if(depth>8)throw new IOException("native_item_depth_limit");return input.readBoolean();
    }
    @Override public float readFloat()throws IOException{float value=input.readFloat();if(!Float.isFinite(value))throw new IOException("native_float_nonfinite");return value;}
    @Override public double readDouble()throws IOException{double value=input.readDouble();if(!Double.isFinite(value))throw new IOException("native_double_nonfinite");return value;}
    @Override public String readUTF()throws IOException{String value=input.readUTF();if(value.length()>1024)throw new IOException("native_string_limit");return value;}
    @Override public void readFully(byte[] bytes)throws IOException{input.readFully(bytes);}
    @Override public void readFully(byte[] bytes,int offset,int length)throws IOException{input.readFully(bytes,offset,length);}
    @Override public int skipBytes(int n)throws IOException{return input.skipBytes(n);}
    @Override public byte readByte()throws IOException{
        byte value=input.readByte();boolean buffer=WALKER.walk(frames->frames.limit(8).anyMatch(f->f.getClassName().equals("1046176253")&&f.getMethodName().equals("const")&&f.getDescriptor().endsWith(")L1754157392;")));
        if(buffer){if(bufferShift>28)throw new IOException("native_buffer_varint");bufferValue|=(value&127)<<bufferShift;if((value&128)!=0)bufferShift+=7;else{int length=bufferValue;bufferShift=bufferValue=0;if(length<0||length>8192||length>available())throw new IOException("native_buffer_exceeds_input");}}
        return value;
    }
    @Override public int readUnsignedByte()throws IOException{return input.readUnsignedByte();}
    @Override public short readShort()throws IOException{return input.readShort();}
    @Override public int readUnsignedShort()throws IOException{return input.readUnsignedShort();}
    @Override public char readChar()throws IOException{return input.readChar();}
    @Override public long readLong()throws IOException{return input.readLong();}
    @Override public String readLine()throws IOException{throw new IOException("native_read_line_unsupported");}
}
