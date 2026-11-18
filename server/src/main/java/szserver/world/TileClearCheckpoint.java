package szserver.world;

import java.nio.*;
import java.util.*;
import java.util.function.BiConsumer;

/** SZTC1: sorted uint16 clear cells and their associated block IDs; no payload or revision. */
final class TileClearCheckpoint {
    static final int MAGIC=0x535a5443,HEADER=8,MAX_BYTES=HEADER+65536*4;
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("checkpoint_tile_clear_on_owner");}
    static byte[] encode(Map<Integer,TilePayload.Tile> tiles,Map<Integer,Integer> ids,int count){
        worker();if(count<1||count>65536)throw new IllegalArgumentException("checkpoint_tile_clear_count");char[] cells=new char[count];int at=0;
        for(var row:tiles.entrySet())if(row.getValue()==null){int cell=row.getKey();if(cell<0||cell>65535||at>=count)throw new IllegalArgumentException("checkpoint_tile_clear_cell");cells[at++]=(char)cell;}
        if(at!=count)throw new IllegalArgumentException("checkpoint_tile_clear_count");Arrays.sort(cells);ByteBuffer bytes=ByteBuffer.allocate(HEADER+4*count).putInt(MAGIC).putInt(count);
        for(char cell:cells){Integer id=ids.get((int)cell);if(id==null||id<0||id>4095)throw new IllegalArgumentException("checkpoint_tile_clear_id");bytes.putChar(cell).putChar((char)(int)id);}return bytes.array();
    }
    static void read(byte[] bytes,int count,BiConsumer<Integer,Integer> consumer){
        worker();if(count<1||count>65536||bytes.length!=HEADER+4*count)throw new IllegalArgumentException("checkpoint_tile_clear_count");ByteBuffer input=ByteBuffer.wrap(bytes);if(input.getInt()!=MAGIC||input.getInt()!=count)throw new IllegalArgumentException("checkpoint_tile_clear_header");int previous=-1;
        for(int n=0;n<count;n++){int cell=input.getChar(),id=input.getChar();if(cell<=previous||id>4095)throw new IllegalArgumentException("checkpoint_tile_clear_cell_or_id");previous=cell;consumer.accept(cell,id);}
    }
}
