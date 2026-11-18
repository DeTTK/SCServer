package replay;

import java.io.*;

/** Clock origins/rate are held tracks: a later server clock packet must not affect the past. */
final class SceneClockCodec {
    record Clock(long visualOriginNs,long environmentOriginNs,long epochMillis,double originSeconds,float rate) {
        Clock {if(visualOriginNs<0||!Double.isFinite(originSeconds)||originSeconds<0||!Float.isFinite(rate)||rate<0)throw new IllegalArgumentException("Invalid scene clock");}
        long visual(long time){return Math.addExact(visualOriginNs,time);}
        long environment(long time){return Math.addExact(environmentOriginNs,time);}
        long wall(long time){return (long)(epochMillis+(time/1e9-originSeconds)*1000.0*rate);}
    }
    static SceneTracks.Bytes encode(Clock value)throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0x53434c4b);out.writeInt(1);
        out.writeLong(value.visualOriginNs());out.writeLong(value.environmentOriginNs());out.writeLong(value.epochMillis());out.writeDouble(value.originSeconds());out.writeFloat(value.rate());return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Clock decode(SceneTracks.Value value)throws IOException {
        if(!(value instanceof SceneTracks.Bytes bytes))throw new IOException("Invalid scene clock value");var input=new DataInputStream(new ByteArrayInputStream(bytes.data()));
        if(input.readInt()!=0x53434c4b||input.readInt()!=1)throw new IOException("Unsupported scene clock codec");
        Clock result;try{result=new Clock(input.readLong(),input.readLong(),input.readLong(),input.readDouble(),input.readFloat());}catch(IllegalArgumentException failure){throw new IOException("Invalid scene clock",failure);}
        if(input.available()!=0)throw new IOException("Trailing scene clock bytes");return result;
    }
}
