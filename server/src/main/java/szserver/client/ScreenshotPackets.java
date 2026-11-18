package szserver.client;

import szserver.net.Wire;
import java.io.*;

/** Installed client: explicit DESKTOP capture, independent of the game window mode. */
public final class ScreenshotPackets {
    public static final int REQUEST=1863, RESPONSE=436, ENVELOPE=1548, MAX_COMPRESSED=4*1024*1024;
    public static final int DESKTOP=2;
    public static byte[] request(){return Wire.dynamic("1287798778",Wire.bytes(out->out.writeInt(DESKTOP)));}
    public static byte[] image(byte[] body)throws IOException{
        if(body.length<9||body.length>MAX_COMPRESSED+8)throw new IOException("screenshot_size");
        var in=Wire.input(body);int length=in.readInt();
        if(length<=0||length>MAX_COMPRESSED||length!=body.length-8)throw new IOException("screenshot_length");
        byte[] image=in.readNBytes(length);
        if(in.readInt()!=DESKTOP)throw new IOException("screenshot_desktop_required");
        return image;
    }
    /** Native break() sends 1574040243(inner packet, boolean), rather than a bare reply. */
    public static byte[] envelope(byte[] body)throws IOException{
        var buffer=java.nio.ByteBuffer.wrap(body);int id;
        try{id=Wire.varint(buffer);}catch(java.nio.BufferUnderflowException truncated){throw new IOException("screenshot_envelope",truncated);}
        if(id==5){
            var in=Wire.input(java.util.Arrays.copyOfRange(body,buffer.position(),body.length));
            if(!in.readUTF().equals("1174074903"))return null;
            buffer.position(body.length-in.available());
        }else if(id!=RESPONSE)return null;
        if(body.length-buffer.position()<10||body.length-buffer.position()>MAX_COMPRESSED+9||body[body.length-1]!=0)throw new IOException("screenshot_envelope");
        return java.util.Arrays.copyOfRange(body,buffer.position(),body.length-1);
    }
    private ScreenshotPackets(){}
}
