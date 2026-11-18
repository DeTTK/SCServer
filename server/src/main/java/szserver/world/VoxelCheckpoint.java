package szserver.world;

import java.nio.*;
import java.util.*;
import java.util.function.BiConsumer;

/** SZVC1: sorted uint16 cells/packed voxels and positive int64 revisions, at most one chunk. */
final class VoxelCheckpoint {
    static final int MAGIC=0x535a5643,HEADER=8,MAX_BYTES=HEADER+65536*12;
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("checkpoint_voxel_on_owner");}
    static byte[] encode(Map<Integer,BlockOverlays.Voxel> cells){
        worker();
        if(cells.isEmpty()||cells.size()>65536)throw new IllegalArgumentException("checkpoint_voxel_chunk_count");
        char[] keys=new char[cells.size()];int at=0;for(Integer key:cells.keySet()){if(key==null||key<0||key>65535)throw new IllegalArgumentException("checkpoint_voxel_chunk_cell");keys[at++]=(char)(int)key;}Arrays.sort(keys);
        ByteBuffer bytes=ByteBuffer.allocate(HEADER+12*keys.length).putInt(MAGIC).putInt(keys.length);
        for(char key:keys){var value=cells.get((int)key);if(value==null||value.id()<0||value.id()>4095||value.metadata()<0||value.metadata()>15||value.revision()<1)throw new IllegalArgumentException("checkpoint_voxel");bytes.putChar(key).putChar((char)((value.id()<<4)|value.metadata())).putLong(value.revision());}
        return bytes.array();
    }
    static void read(byte[] bytes,int count,BiConsumer<Integer,BlockOverlays.Voxel> consumer){
        worker();
        if(count<1||count>65536||bytes.length!=HEADER+12*count)throw new IllegalArgumentException("checkpoint_voxel_chunk_count");
        ByteBuffer input=ByteBuffer.wrap(bytes);if(input.getInt()!=MAGIC||input.getInt()!=count)throw new IllegalArgumentException("checkpoint_voxel_chunk_header");int previous=-1;
        for(int n=0;n<count;n++){int cell=input.getChar(),packed=input.getChar();long revision=input.getLong();if(cell<=previous||revision<1)throw new IllegalArgumentException("checkpoint_voxel");previous=cell;consumer.accept(cell,new BlockOverlays.Voxel(packed>>>4,packed&15,revision));}
    }
}
