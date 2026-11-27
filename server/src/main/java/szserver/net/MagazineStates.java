package szserver.net;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Native empty and loaded states for magazines whose full state table would be unbounded in practice. */
final class MagazineStates {
    private static final int EAGER_LIMIT=1000;

    @FunctionalInterface
    interface Encoder {
        byte[] encode(int rounds) throws Exception;
    }
    @FunctionalInterface
    interface Resizer {
        byte[] resize(byte[] state,int rounds) throws Exception;
    }

    private final byte[][] eager;
    private final byte[] empty,loaded;
    private final int countOffset,capacity;

    private MagazineStates(byte[][] eager,byte[] empty,byte[] loaded,int countOffset,int capacity){
        this.eager=eager;
        this.empty=empty;
        this.loaded=loaded;
        this.countOffset=countOffset;
        this.capacity=capacity;
    }

    static MagazineStates prepare(int capacity,Encoder encoder,Resizer resizer)throws Exception{
        if(capacity<=EAGER_LIMIT){
            byte[][] states=new byte[capacity+1][];
            for(int rounds=0;rounds<=capacity;rounds++)states[rounds]=encoder.encode(rounds);
            return new MagazineStates(states,null,null,-1,capacity);
        }
        byte[] empty=encoder.encode(0),loaded=encoder.encode(1);
        // An inner native ammunition stack stores its count as a big-endian int.
        // Derive the offset using the actual serializer and verify it against another native encoding.
        int marker=0x010203;
        byte[] sample=resizer.resize(loaded,marker);
        int offset=Arrays.mismatch(loaded,sample)-1;
        if(offset<0||offset>loaded.length-Integer.BYTES||ByteBuffer.wrap(loaded).getInt(offset)!=1
                ||!Arrays.equals(withCount(loaded,offset,marker),sample)
                ||!Arrays.equals(withCount(loaded,offset,capacity),resizer.resize(loaded,capacity)))
            throw new IllegalArgumentException("native_magazine_count_layout");
        return new MagazineStates(null,empty,loaded,offset,capacity);
    }

    byte[] state(int rounds){
        if(rounds<0||rounds>capacity)throw new IllegalArgumentException("magazine_rounds");
        if(eager!=null)return eager[rounds];
        return rounds==0?empty:withCount(loaded,countOffset,rounds);
    }

    private static byte[] withCount(byte[] loaded,int offset,int count){
        byte[] bytes=loaded.clone();
        ByteBuffer.wrap(bytes).putInt(offset,count);
        return bytes;
    }
}
