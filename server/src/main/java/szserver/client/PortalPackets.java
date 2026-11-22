package szserver.client;

import java.nio.*;
import java.io.*;

/** Native aimed scene interaction and bounded use/place validation; use/place is not portal interaction. */
public final class PortalPackets {
    /** Native aimed scene interaction, emitted by 1592843593.strictfp (F). */
    public static final int INTERACT_ID=1632;
    public static final String INTERACT_CLASS="1580860650";
    public static java.util.UUID interaction(byte[] body)throws IOException{if(body.length!=16)throw new IOException("portal_interaction_size");ByteBuffer b=ByteBuffer.wrap(body);return new java.util.UUID(b.getLong(),b.getLong());}
    public record Settings(java.util.UUID identity,byte[] compressed){public Settings{compressed=compressed.clone();}@Override public byte[] compressed(){return compressed.clone();}}
    /** Shared 559 RPC: portals carry compressed named NBT; anomalies carry their typed DTO. */
    public static Settings settings(byte[] body)throws IOException{
        if(body.length<29)return null;ByteBuffer b=ByteBuffer.wrap(body);b.position(24);int magic=b.getInt();if(magic!=0x28b52ffd&&(magic>>>16)!=0x1f8b)return null;
        b.position(0);java.util.UUID id=new java.util.UUID(b.getLong(),b.getLong());int outer=b.getInt(),size=b.getInt();if(body.length>SceneConfigurationPackets.MAX_NBT+25||outer!=body.length-20||size<4||size!=body.length-25||(body[body.length-1]&255)>1)throw new IOException("portal_settings_size");byte[] compressed=new byte[size];b.get(compressed);return new Settings(id,compressed);
    }
    public static boolean useClass(String value){return value.equals("1778134113");}
    public static boolean emptyHand(byte[] body)throws IOException{
        if(body.length<29||body.length>8200)throw new IOException("portal_use_size");ByteBuffer b=ByteBuffer.wrap(body);int x=b.getInt(),y=b.getInt(),z=b.getInt(),face=b.getInt();if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||y<0||y>255||face<0||face>5)throw new IOException("portal_use_target");
        for(int at=body.length-12;at<body.length;at+=4){float v=b.getFloat(at);if(!Float.isFinite(v)||v<0||v>1)throw new IOException("portal_use_hit");}
        return (body.length==29||body.length==30)&&body[16]==0;
    }
    private PortalPackets(){}
}
