package szserver.client;

import java.nio.ByteBuffer;
import java.io.IOException;

/** VR gesture intent. Item, spawn position and flight rules remain server-owned. */
public record VrBoltIntent(float yaw,float pitch,float strength) {
    public static final String REQUEST="szmods.bridge.VrBolt";
    private static final int MAGIC=0x56524231;

    private static boolean matches(byte[] data){
        return data.length>=4&&ByteBuffer.wrap(data).getInt()==MAGIC;
    }

    public static VrBoltIntent read(byte[] data)throws IOException{
        if(data.length!=16||!matches(data))throw new IOException("vr_bolt_size");
        ByteBuffer buffer=ByteBuffer.wrap(data);
        buffer.getInt();
        float yaw=buffer.getFloat(),pitch=buffer.getFloat(),strength=buffer.getFloat();
        if(!Float.isFinite(yaw)||Math.abs(yaw)>360||!Float.isFinite(pitch)||Math.abs(pitch)>90
           ||!Float.isFinite(strength)||strength<.35f||strength>1.5f)throw new IOException("vr_bolt_bounds");
        return new VrBoltIntent(yaw,pitch,strength);
    }
}
