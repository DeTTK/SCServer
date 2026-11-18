package szserver.world;

import com.github.luben.zstd.Zstd;
import szserver.net.Wire;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** The old native-map fallback palette and light layout, prepared once before listening. */
final class FlatChunk {
    final short[] blocks=new short[65536];
    private final byte[] compressed;
    private final int surface,mask,arraysSize;
    FlatChunk(int surface){
        if(surface<0||surface>255)throw new IllegalArgumentException("flat_surface_out_of_range");
        this.surface=surface;int count=surface/16+1;mask=(1<<count)-1;arraysSize=count*12288+256;
        byte[] raw=new byte[arraysSize+4];
        for(int y=0;y<=surface;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int index=(y<<8)|(z<<4)|x;
            int id=y==0?7:y<surface-2?1:y<surface?3:2;blocks[index]=(short)id;raw[index]=(byte)id;
            if(y==surface&&y>0){int local=index&4095,at=count*8192+(y/16)*2048+(local>>1);raw[at]|=(byte)((local&1)==0?15:240);}
        }
        compressed=Zstd.compress(raw,3);
    }
    byte[] record(int x,int z){
        UUID revision=revision(x,z,surface);
        return Wire.bytes(out->{out.writeInt(x);out.writeInt(z);out.writeBoolean(true);out.writeInt(mask);out.writeInt(0);out.writeLong(revision.getMostSignificantBits());out.writeLong(revision.getLeastSignificantBits());out.writeInt(arraysSize);out.writeInt(compressed.length);out.write(compressed);out.writeByte(0);out.write(new byte[]{1,1,0,0,0,0});out.writeInt(x);out.writeInt(z);out.writeInt(0);});
    }
    /** UUIDv5 namespace URL, matching the old Python fallback's revision exactly. */
    private static UUID revision(int x,int z,int surface){
        try{MessageDigest digest=MessageDigest.getInstance("SHA-1");digest.update(ByteBuffer.allocate(16).putLong(0x6ba7b8119dad11d1L).putLong(0x80b400c04fd430c8L).array());byte[] hash=digest.digest(("szserver-flat-native-v1:"+x+":"+z+":"+surface).getBytes(StandardCharsets.UTF_8));hash[6]=(byte)((hash[6]&15)|80);hash[8]=(byte)((hash[8]&63)|128);ByteBuffer b=ByteBuffer.wrap(hash);return new UUID(b.getLong(),b.getLong());}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
