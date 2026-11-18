package szserver.net;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Native stream framing and bounded binary primitives. */
public final class Wire {
    @FunctionalInterface public interface Writer {void write(DataOutputStream out)throws Exception;}
    public static byte[] bytes(Writer writer){try{var b=new ByteArrayOutputStream();writer.write(new DataOutputStream(b));return b.toByteArray();}catch(Exception e){throw new IllegalArgumentException("wire_encode",e);}}
    public static void varint(DataOutput out,int n)throws IOException{if(n<0)throw new IOException("negative_length");do{int v=n&127;n>>>=7;out.writeByte(v|(n==0?0:128));}while(n!=0);}
    public static int varint(ByteBuffer b)throws IOException{int n=0;for(int i=0;i<5;i++){int v=b.get()&255;if(i==4&&(v&240)!=0)throw new IOException("varint_overflow");n|=(v&127)<<(7*i);if((v&128)==0)return n;}throw new IOException("varint_limit");}
    public static byte[] frame(int id,byte[] body){byte[] m=bytes(o->{varint(o,id);o.write(body);});return bytes(o->{varint(o,m.length<<1);o.write(m);});}
    public static byte[] dynamic(String name,byte[] body){return frame(5,bytes(o->{o.writeUTF(name);o.write(body);}));}
    public static DataInputStream input(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    public static byte[] concat(byte[]... chunks){return bytes(o->{for(byte[] b:chunks)o.write(b);});}
    public static byte[] readFrame(InputStream in)throws IOException{int descriptor=0;for(int i=0;i<5;i++){int b=in.read();if(b<0)throw new EOFException();descriptor|=(b&127)<<(7*i);if((b&128)==0){if((descriptor&1)!=0||(descriptor>>>1)>65536)throw new IOException("gateway_frame_limit");byte[] data=in.readNBytes(descriptor>>>1);if(data.length!=(descriptor>>>1))throw new EOFException();return data;}}throw new IOException("gateway_varint_limit");}
}
