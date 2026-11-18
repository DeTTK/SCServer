package szserver.net;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Current SC's dynamic envelope is 1501. Preserve the legacy server codecs and
 * translate whole outbound frames only for clients which negotiated SCV1. */
public final class VehicleEnvelope {
    public static byte[] current(byte[] stream){
        try{
            ByteBuffer b=ByteBuffer.wrap(stream);ByteArrayOutputStream result=null;
            while(b.hasRemaining()){
                int start=b.position(),descriptor=Wire.varint(b),size=descriptor>>>1,body=b.position();
                if(size<1||size>b.remaining())throw new IOException("vehicle_outbound_frame_size");
                int end=body+size;boolean rewrite=false;
                if((descriptor&1)==0){int oldLimit=b.limit();b.limit(end);int id=Wire.varint(b);rewrite=id==5;b.limit(oldLimit);}
                if(rewrite){
                    if(result==null){result=new ByteArrayOutputStream(stream.length+16);result.write(stream,0,start);}
                    result.write(Wire.frame(1501,Arrays.copyOfRange(stream,b.position(),end)));
                }else if(result!=null)result.write(stream,start,end-start);
                b.position(end);
            }
            return result==null?stream:result.toByteArray();
        }catch(IOException|BufferUnderflowException e){throw new IllegalArgumentException("vehicle_outbound_framing",e);}
    }
    private VehicleEnvelope(){}
}
