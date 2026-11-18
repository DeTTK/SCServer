package szserver.client;

import java.nio.*;
import java.io.*;

/** Current 110575208 constructs use/place class 1778134113; only empty-hand portal use is interpreted here. */
public final class PortalPackets {
    public static boolean useClass(String value){return value.equals("1778134113");}
    public static boolean emptyHand(byte[] body)throws IOException{
        if(body.length<29||body.length>8200)throw new IOException("portal_use_size");ByteBuffer b=ByteBuffer.wrap(body);int x=b.getInt(),y=b.getInt(),z=b.getInt(),face=b.getInt();if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||y<0||y>255||face<0||face>5)throw new IOException("portal_use_target");
        for(int at=body.length-12;at<body.length;at+=4){float v=b.getFloat(at);if(!Float.isFinite(v)||v<0||v>1)throw new IOException("portal_use_hit");}
        return (body.length==29||body.length==30)&&body[16]==0;
    }
    private PortalPackets(){}
}
