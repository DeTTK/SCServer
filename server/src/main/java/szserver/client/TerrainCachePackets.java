package szserver.client;

import java.nio.ByteBuffer;
import szserver.net.GamePackets;

/** Native cache-hit record: retain scene channels, omit voxel arrays and terrain UUID. */
public final class TerrainCachePackets {
    private TerrainCachePackets(){}
    public static byte[] objectOnly(byte[] record){
        if(record.length<9)throw new IllegalArgumentException("cache_record_short");int flag=record[8]&255;
        if(flag==0)return record.clone();if(flag!=1||record.length<41)throw new IllegalArgumentException("cache_record_header");
        int compressed=ByteBuffer.wrap(record).getInt(37);long end=41L+compressed;
        if(compressed<4||end>record.length)throw new IllegalArgumentException("cache_record_geometry_size");
        byte[] result=new byte[9+record.length-(int)end];System.arraycopy(record,0,result,0,8);System.arraycopy(record,(int)end,result,9,record.length-(int)end);return result;
    }
    public static byte[] frame(byte[] record){return GamePackets.terrainChunk(objectOnly(record));}
}
