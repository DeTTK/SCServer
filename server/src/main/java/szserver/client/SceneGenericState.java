package szserver.client;

import szserver.net.Wire;
import szserver.world.ScenePayload;
import java.nio.ByteBuffer;
import java.time.*;
import java.io.IOException;

/** Reviewed 1886271463 generic tail, including PLAYER_SYNC_DEV fields. No native allocation on owners. */
public final class SceneGenericState {
    public record Layout(int editorAt,int referenceAt,int featuresAt,int vector2At,int end){}
    private static void utf(ByteBuffer in){byte[] raw=ScenePayload.utf(in);try{Wire.input(raw).readUTF();}catch(IOException error){throw new IllegalArgumentException("scene_generic_utf",error);}}
    private static void finite(float value){if(!Float.isFinite(value))throw new IllegalArgumentException("scene_generic_nonfinite");}
    private static void vector(ByteBuffer in){for(int n=0;n<3;n++)finite(in.getFloat());}
    public static Layout read(ByteBuffer in,int flags,int packed){
        if((flags&256)!=0){long seconds=in.getLong();int nanos=in.getInt();try{Instant.ofEpochSecond(seconds,nanos);}catch(DateTimeException invalid){throw new IllegalArgumentException("scene_generic_instant",invalid);}}
        if((flags&128)!=0)utf(in);int editorAt=in.position();if((flags&64)!=0)in.getInt();if((in.get()&255)>=3)throw new IllegalArgumentException("scene_brightness_enum");if((flags&32)!=0)vector(in);if((flags&16)!=0)finite(in.getFloat());ScenePayload.bool(in);if((flags&8)!=0)vector(in);int referenceAt=in.position();if((flags&4)!=0)ScenePayload.take(in,16);ScenePayload.bool(in);if((packed&2)!=0)utf(in);ScenePayload.bool(in);int featuresAt=in.position();
        if((flags&2)!=0){int count=in.getInt();if(count<0||count>in.remaining()/2)throw new IllegalArgumentException("scene_feature_count");for(int n=0;n<count;n++)utf(in);}if((flags&1)!=0)utf(in);int vector2At=in.position();if((flags&0x10000)!=0)vector(in);if((flags&0x20000)!=0)finite(in.getFloat());return new Layout(editorAt,referenceAt,featuresAt,vector2At,in.position());
    }
    private SceneGenericState(){}
}
