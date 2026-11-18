package szserver.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Real channel reads; malformed slots must not prevent reading an adjacent valid chunk. */
public final class RegionRecordBoundsTests {
    private static int checks;
    private static void rejected(FileChannel channel,int index,String expected)throws Exception {
        try { RegionTerrain.regionRecord(channel,index);throw new AssertionError("accepted slot "+index); }
        catch(IOException error){if(!expected.equals(error.getMessage()))throw new AssertionError(error);checks++;}
    }
    public static void main(String[] args)throws Exception {
        Path file=Files.createTempFile("szr-bounds-", ".szr");
        try {
            byte[] payload=new byte[41];Arrays.fill(payload,(byte)7);
            CRC32 crc=new CRC32();crc.update(payload);
            ByteBuffer data=ByteBuffer.allocate(16400+payload.length);
            data.putInt(0x535A5231).putInt(0).putInt(0).putInt(0);
            data.position(16);data.putLong(16400).putInt(payload.length).putInt((int)crc.getValue());
            data.putLong(Long.MAX_VALUE-10).putInt(41).putInt(0);
            data.putLong(16399).putInt(41).putInt(0);
            data.putLong(16400).putInt(42).putInt(0);
            data.putLong(16400).putInt(41).putInt(0);
            data.position(16400);data.put(payload);Files.write(file,data.array());
            try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)){
                RegionTerrain.regionHeader(channel,0,0);checks++;
                rejected(channel,-1,"invalid_region_chunk_index");
                rejected(channel,1024,"invalid_region_chunk_index");
                for(int index=1;index<=3;index++)rejected(channel,index,"missing_or_invalid_chunk");
                rejected(channel,4,"region_crc_mismatch");
                if(RegionTerrain.regionRecord(channel,5)!=null)throw new AssertionError("empty chunk");checks++;
                if(!Arrays.equals(payload,RegionTerrain.regionRecord(channel,0)))throw new AssertionError("adjacent chunk");checks++;
            }
            Files.write(file,new byte[20]);
            try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ)){
                rejected(channel,0,"truncated_region");
            }
            System.out.println("RegionRecordBoundsTests PASS checks="+checks);
        }finally{Files.deleteIfExists(file);}
    }
}
