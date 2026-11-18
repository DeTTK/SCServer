package szserver.net;

import szserver.client.NativeBlockPackets;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Incremental receive buffer: each byte copied O(1), including fragmented multi-MiB SZBD. */
public final class ReliableInput {
    public record Message(int id,byte[] body){}
    private static final int LIMIT=NativeBlockPackets.MAX_INPUT+32;
    private byte[] data=new byte[1024];private int start,end;
    public void append(byte[] bytes)throws IOException{
        if((long)end-start+bytes.length>LIMIT)throw new IOException("stream_limit");
        if(data.length-end<bytes.length&&start>0){System.arraycopy(data,start,data,0,end-start);end-=start;start=0;}
        if(data.length-end<bytes.length)data=Arrays.copyOf(data,Math.min(LIMIT,Math.max(end+bytes.length,data.length*2)));
        System.arraycopy(bytes,0,data,end,bytes.length);end+=bytes.length;
    }
    public Message next()throws IOException{
        if(start==end)return null;ByteBuffer b=ByteBuffer.wrap(data,start,end-start);int descriptor;
        try{descriptor=Wire.varint(b);}catch(BufferUnderflowException error){return null;}
        int size=descriptor>>>1;if((descriptor&1)!=0||size==0||size>NativeBlockPackets.MAX_INPUT+5)throw new IOException("stream_frame_limit");
        int payload=b.position(),id;b.limit(Math.min(end,payload+size));try{id=Wire.varint(b);}catch(BufferUnderflowException error){if(size<=end-payload)throw new IOException("stream_packet_id");return null;}
        if(size<b.position()-payload)throw new IOException("stream_message_body_empty");
        if(size>65536&&id!=1345&&id!=1978&&id!=szserver.client.ScreenshotPackets.RESPONSE&&id!=szserver.client.ScreenshotPackets.ENVELOPE)throw new IOException("stream_frame_limit");
        if(id==szserver.client.ScreenshotPackets.RESPONSE&&size>szserver.client.ScreenshotPackets.MAX_COMPRESSED+13)throw new IOException("screenshot_frame_limit");
        if(id==szserver.client.ScreenshotPackets.ENVELOPE&&size>szserver.client.ScreenshotPackets.MAX_COMPRESSED+64)throw new IOException("screenshot_frame_limit");
        if(size>end-payload)return null;
        byte[] body=Arrays.copyOfRange(data,b.position(),payload+size);start=payload+size;if(start==end){start=end=0;if(data.length>65536)data=new byte[1024];}return new Message(id,body);
    }
    public int retainedBytes(){return end-start;}
}
