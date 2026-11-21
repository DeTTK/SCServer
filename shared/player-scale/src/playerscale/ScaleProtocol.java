package playerscale;

import java.io.*;
import java.nio.ByteBuffer;

/** Versioned payload inside the existing authenticated dynamic game envelope. */
public final class ScaleProtocol {
    private ScaleProtocol(){}
    public static final String INPUT="playerscale.bridge.ScaleInput",SNAPSHOT="playerscale.bridge.ScaleSnapshot";
    public static final int MAGIC=0x53435331,VERSION=1,BYTES=22,SET=1,REMOVE=2;
    public record Snapshot(int operation,int entity,long revision,ScaleValue scale){
        public Snapshot {
            if((operation!=SET&&operation!=REMOVE)||(entity!=0&&entity<2)||revision<0||scale==null||operation==REMOVE&&scale.factor()!=1)throw new IllegalArgumentException("character_scale_snapshot");
        }
        public byte[] encode(){return ByteBuffer.allocate(BYTES).putInt(MAGIC).put((byte)VERSION).put((byte)operation).putInt(entity).putLong(revision).putFloat(scale.factor()).array();}
    }
    public static byte[] hello(){return ByteBuffer.allocate(5).putInt(MAGIC).put((byte)VERSION).array();}
    public static void hello(byte[] raw){if(raw==null||raw.length!=5||!java.util.Arrays.equals(raw,hello()))throw new IllegalArgumentException("character_scale_version");}
    public static Snapshot decode(byte[] raw){
        if(raw==null||raw.length!=BYTES)throw new IllegalArgumentException("character_scale_snapshot_length");ByteBuffer b=ByteBuffer.wrap(raw);
        if(b.getInt()!=MAGIC||b.get()!=VERSION)throw new IllegalArgumentException("character_scale_version");
        return new Snapshot(b.get()&255,b.getInt(),b.getLong(),new ScaleValue(b.getFloat()));
    }
    public static byte[] read(DataInput in)throws IOException {
        byte[] raw=new byte[BYTES];in.readFully(raw);try{decode(raw);}catch(IllegalArgumentException bad){throw new IOException(bad.getMessage(),bad);}return raw;
    }
}
