package szserver.world;

import com.github.luben.zstd.Zstd;
import java.nio.*;
import java.util.*;
import szserver.net.Wire;

/** Bounded native ProBuilder/Carpenter tail. Parsed only by loader/projection/codec workers. */
public final class TilePayload {
    public static final int MAX_TILES=65536,MAX_RAW=2*1024*1024;
    public static final class Tile {
        private final int x,y,z,type,flags,shapeData,shapeExtension,centralCoverId;private final byte[] record,completeState;private final double[][] builderCells,profileCells;
        Tile(int x,int y,int z,int type,int flags,byte[] record){this(x,y,z,type,flags,record,null);}
        private Tile(int x,int y,int z,int type,int flags,byte[] record,byte[] completeState){this(x,y,z,type,flags,record,completeState,null);}
        private Tile(int x,int y,int z,int type,int flags,byte[] record,byte[] completeState,Tile derived){this.x=x;this.y=y;this.z=z;this.type=type;this.flags=flags;this.record=record;this.completeState=completeState==null?null:completeState.clone();ByteBuffer b=ByteBuffer.wrap(record);centralCoverId=b.getShort((flags&8)!=0?26:14)&4095;shapeData=b.getInt(record.length-(type==1?8:4));shapeExtension=type==1?b.getInt(record.length-4):-1;builderCells=derived!=null?derived.builderCells:type==1?BuilderShapes.cells(shapeData,shapeExtension):null;profileCells=derived!=null?derived.profileCells:type==0?CarpenterProfiles.prepared(shapeData):null;}
        /** Decoded once on workers; collision/navigation never clone or decompress tile records. */
        public int shapeData(){return shapeData;}public int shapeExtension(){return shapeExtension;}
        /** Central native cover only; side covers do not disable collision. Metadata uses the upper four bits. */
        public int centralCoverId(){return centralCoverId;}
        double[][] builderCells(){return builderCells;}
        double[][] profileCells(){return profileCells;}
        public byte[] completeState(){return completeState==null?null:completeState.clone();}
        public int completeStateSize(){return completeState==null?0:completeState.length;}
        /** Attach only after CompleteTileState validated the compound on a worker. */
        public Tile withCompleteState(byte[] state){return new Tile(x,y,z,type,flags,record,state,this);}
        public int x(){return x;}public int y(){return y;}public int z(){return z;}public int type(){return type;}public int flags(){return flags;}
        public int cell(){return (y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);}
        public byte[] record(){return record.clone();}
        public int recordSize(){return record.length;}
        /** Immutable relocation; all appearance fields remain byte-identical. */
        public Tile at(World.Block p){
            if(p.x()<-szserver.world.MapBounds.HORIZONTAL||p.x()>szserver.world.MapBounds.HORIZONTAL||p.z()<-szserver.world.MapBounds.HORIZONTAL||p.z()>szserver.world.MapBounds.HORIZONTAL||p.y()<0||p.y()>255)throw new IllegalArgumentException("tile_position_bounds");
            if(p.x()==x&&p.y()==y&&p.z()==z)return this;
            byte[] moved=record.clone();ByteBuffer.wrap(moved).putInt(1,p.x()).putInt(5,p.y()).putInt(9,p.z());return new Tile(p.x(),p.y(),p.z(),type,flags,moved,completeState==null?null:szserver.client.CompleteTileState.relocate(completeState,p),this);
        }
        @Override public boolean equals(Object other){return other instanceof Tile tile&&Arrays.equals(record,tile.record)&&Arrays.equals(completeState,tile.completeState);}
        @Override public int hashCode(){return 31*Arrays.hashCode(record)+Arrays.hashCode(completeState);}
    }
    public static List<Tile> decode(byte[] raw,int arrays,int chunkX,int chunkZ){
        if(arrays<0||arrays>raw.length-4||raw.length>MAX_RAW)throw new IllegalArgumentException("tile_tail_bounds");
        ByteBuffer input=ByteBuffer.wrap(raw);input.position(arrays);int count=input.getInt();
        if(count<0||count>MAX_TILES||count>input.remaining()/20)throw new IllegalArgumentException("tile_count_limit");
        List<Tile> result=new ArrayList<>(count);Set<Integer> cells=new HashSet<>();
        for(int i=0;i<count;i++){
            if(input.remaining()<20)throw new IllegalArgumentException("tile_record_truncated");int start=input.position();int type=input.get()&255,x=input.getInt(),y=input.getInt(),z=input.getInt(),flags=input.get()&255;
            if(type>1||(flags&128)!=0)throw new IllegalArgumentException("tile_type_or_flags");
            if(Math.floorDiv(x,16)!=chunkX||Math.floorDiv(z,16)!=chunkZ||y<0||y>255)throw new IllegalArgumentException("tile_position_bounds");
            int bytes=((flags&8)!=0?14:2)+((flags&2)!=0?14:0)+((flags&4)!=0?2:0)+((flags&1)!=0?7:0)+((flags&16)!=0?2:0)+((flags&32)!=0?1:0)+((flags&64)!=0?6:0)+4+(type==1?4:0);
            if(input.remaining()<bytes)throw new IllegalArgumentException("tile_record_truncated");input.position(input.position()+bytes);
            Tile tile=new Tile(x,y,z,type,flags,Arrays.copyOfRange(raw,start,input.position()));if(!cells.add(tile.cell()))throw new IllegalArgumentException("duplicate_tile_cell");result.add(tile);
        }
        if(input.hasRemaining())throw new IllegalArgumentException("tile_tail_trailing_bytes");return List.copyOf(result);
    }
    public static byte[] encode(List<Tile> tiles){return Wire.bytes(out->{out.writeInt(tiles.size());for(Tile tile:tiles)out.write(tile.record);});}
    /** Validate a journal tile on its command worker, using the same bounded native parser. */
    public static Tile single(byte[] record){
        if(record.length<20||record.length>68)throw new IllegalArgumentException("tile_record_bounds");
        ByteBuffer b=ByteBuffer.wrap(record);byte[] tail=ByteBuffer.allocate(4+record.length).putInt(1).put(record).array();
        return decode(tail,0,Math.floorDiv(b.getInt(1),16),Math.floorDiv(b.getInt(9),16)).get(0);
    }
    public static byte[] raw(byte[] record){
        if(record.length<60)throw new IllegalArgumentException("tile_chunk_short");ByteBuffer header=ByteBuffer.wrap(record);int size=header.getInt(37);
        if(size<4||(long)size+41>record.length||record.length>ScenePayload.MAX_RECORD)throw new IllegalArgumentException("tile_chunk_layout");byte[] compressed=Arrays.copyOfRange(record,41,41+size);long decoded=Zstd.decompressedSize(compressed);
        if(decoded<4||decoded>MAX_RAW)throw new IllegalArgumentException("tile_decompression_limit");return Zstd.decompress(compressed,(int)decoded);
    }
    public static List<Tile> fromRecord(byte[] record){ByteBuffer header=ByteBuffer.wrap(record);return decode(raw(record),header.getInt(33),header.getInt(),header.getInt());}
}
