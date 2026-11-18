package szserver.commands;

import com.google.gson.*;
import szserver.net.TerrainProjection;
import szserver.util.Json;
import szserver.world.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Bounded, command-barrier reads of the effective map. Never called on a simulation owner. */
public final class MapReads {
    public static final int MAX_BYTES=32*1024*1024;
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public record Chunk(int x,int z){}
    public record Request(String operation,List<Chunk> chunks,List<World.Block> blocks){
        public Request {
            chunks=List.copyOf(chunks);blocks=List.copyOf(blocks);
            if(!Set.of("raw","batch","record","revision","summary","surface","block","blocks").contains(operation))throw new IllegalArgumentException("invalid_map_read");
            boolean block=operation.equals("block")||operation.equals("blocks");
            if(block){if(!chunks.isEmpty()||blocks.isEmpty()||blocks.size()>(operation.equals("block")?1:256))throw new IllegalArgumentException("invalid_block_batch");for(var p:blocks)if(p.x()<-szserver.world.MapBounds.HORIZONTAL||p.x()>szserver.world.MapBounds.HORIZONTAL||p.z()<-szserver.world.MapBounds.HORIZONTAL||p.z()>szserver.world.MapBounds.HORIZONTAL||p.y()<0||p.y()>255)throw new IllegalArgumentException("invalid_block");}
            else {if(!blocks.isEmpty()||chunks.isEmpty()||chunks.size()>(operation.equals("batch")?128:1))throw new IllegalArgumentException("invalid_chunk_batch");for(var p:chunks)if(p.x()<-szserver.world.MapBounds.CHUNK||p.x()>szserver.world.MapBounds.CHUNK||p.z()<-szserver.world.MapBounds.CHUNK||p.z()>szserver.world.MapBounds.CHUNK)throw new IllegalArgumentException("invalid_chunk");}
        }
    }
    /** The admission reservation survives completion until the HTTP writer releases its payload. */
    public static final class Reply implements AutoCloseable {
        private final byte[] bytes;private final String contentType;private final Runnable release;private final AtomicBoolean closed=new AtomicBoolean();
        Reply(byte[] bytes,String contentType,Runnable release){this.bytes=bytes;this.contentType=contentType;this.release=release;}
        public byte[] bytes(){return bytes;}public String contentType(){return contentType;}
        @Override public void close(){if(closed.compareAndSet(false,true))release.run();}
    }
    private static final class Buffer extends ByteArrayOutputStream {
        private void room(int length){if(length<0||(long)count+length>MAX_BYTES)throw new IllegalArgumentException("map_response_byte_limit");}
        @Override public synchronized void write(int value){room(1);super.write(value);}
        @Override public synchronized void write(byte[] value,int offset,int length){room(length);super.write(value,offset,length);}
    }
    private final Terrain terrain;private final BlockReadView view;private final BlockReadView.Cursor cursor;
    private final BooleanSupplier cancelled;private final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    private MapReads(Terrain terrain,BlockReadView view,BooleanSupplier cancelled){this.terrain=terrain;this.view=view;this.cursor=view.cursor();this.cancelled=cancelled;}
    private void check(){if(cancelled.getAsBoolean())throw new CancellationException("map_read_cancelled");if(System.nanoTime()>deadline)throw new RejectedExecutionException("map_read_deadline");view.checkCurrent();}
    private byte[] base(Chunk p)throws InterruptedException {
        if(view.resident())return cursor.nativeRecord(p.x(),p.z());
        if(!(terrain instanceof RegionTerrain regions))throw new IllegalArgumentException("native_map_records_unavailable");
        while(true){check();byte[] value=regions.nativeTerrainRecord(p.x(),p.z());if(value!=null)return value;Thread.sleep(5);}
    }
    private void warm(World.Block p)throws InterruptedException {
        if(view.resident()){cursor.nativeVoxel(p);return;}
        if(terrain instanceof RegionTerrain)base(new Chunk(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));
    }
    private byte[] record(Chunk p)throws Exception {byte[] record=TerrainProjection.project(base(p),cursor.overlay(p.x(),p.z()));check();return record;}
    private static BigInteger revision(byte[] record){return new BigInteger(1,Arrays.copyOfRange(record,17,25));}
    private static void raw(DataOutputStream out,Chunk p,byte[] record,BlockOverlays.Snapshot overlay)throws IOException {
        out.writeInt(0x535A4E32);out.writeInt(p.x());out.writeInt(p.z());out.writeLong(ByteBuffer.wrap(record).getLong(17));out.writeInt(record.length);out.write(record);
        SortedSet<Integer> cells=new TreeSet<>(overlay.cells().keySet());cells.addAll(overlay.tiles().keySet());out.writeInt(cells.size());
        byte[] geometry=null;for(int cell:cells){var voxel=overlay.cells().get(cell);if(voxel==null){if(geometry==null)geometry=TilePayload.raw(record);int packed=voxel(record,geometry,cell);voxel=new BlockOverlays.Voxel(packed>>>4,packed&15,0);}out.writeByte(cell&15);out.writeByte(cell>>>8);out.writeByte((cell>>>4)&15);out.writeShort(voxel.id());out.writeByte(voxel.metadata());var tile=overlay.tiles().get(cell);byte[] state=tile==null?null:tile.completeState();out.writeInt(state==null?-1:state.length);if(state!=null)out.write(state);}
    }
    private static int voxel(byte[] record,byte[] raw,int cell){
        ByteBuffer h=ByteBuffer.wrap(record);int mask=h.getInt(9),add=h.getInt(13),section=cell>>>12,index=cell&4095;if((mask&(1<<section))==0)return 0;int count=Integer.bitCount(mask),ordinal=Integer.bitCount(mask&((1<<section)-1));int id=raw[ordinal*4096+index]&255;
        if((add&(1<<section))!=0){int at=count*12288+Integer.bitCount(add&((1<<section)-1))*2048+(index>>>1);id|=((raw[at]>>>((index&1)*4))&15)<<8;}int meta=(raw[count*4096+ordinal*2048+(index>>>1)]>>>((index&1)*4))&15;return id<<4|meta;
    }
    private Object blocks(Request request)throws Exception {
        JsonObject[] rows=new JsonObject[request.blocks().size()];long previous=Long.MIN_VALUE;long stateBytes=0;
        for(long word:BlockReadView.ordered(request.blocks())){check();int index=BlockReadView.index(word);World.Block p=request.blocks().get(index);long key=BlockOverlays.key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));if(key!=previous){warm(p);previous=key;}int packed=cursor.voxel(p);var tile=cursor.tile(p);byte[] state=tile==null?null:tile.completeState();stateBytes+=state==null?0:state.length;if(stateBytes>8*1024*1024)throw new IllegalArgumentException("map_state_byte_limit");JsonObject row=Json.tree(Map.of("x",p.x(),"y",p.y(),"z",p.z(),"blockId",packed>>>4,"metadata",packed&15));row.add("tile",JsonNull.INSTANCE);row.add("stateData",state==null?JsonNull.INSTANCE:new JsonPrimitive(Base64.getEncoder().encodeToString(state)));rows[index]=row;}
        return request.operation().equals("block")?rows[0]:Map.of("blocks",rows);
    }
    private Object surface(Chunk p)throws Exception {
        warm(new World.Block(p.x()*16,0,p.z()*16));double[] heights=new double[256];
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){check();for(int y=255;y>=0;y--)if(cursor.voxel(new World.Block(p.x()*16+x,y,p.z()*16+z))>>>4!=0){heights[z*16+x]=y+1;break;}}
        return Map.of("chunkX",p.x(),"chunkZ",p.z(),"heights",heights);
    }
    public static Reply execute(Request request,Terrain terrain,BlockReadView view,BooleanSupplier cancelled,Runnable release)throws Exception {
        MapReads read=new MapReads(terrain,view,cancelled);Buffer buffer=new Buffer();String type="application/json; charset=utf-8";Object json=null;String op=request.operation();
        if(op.equals("block")||op.equals("blocks"))json=read.blocks(request);
        else if(op.equals("surface"))json=read.surface(request.chunks().get(0));
        else if(op.equals("raw")||op.equals("batch")||op.equals("record")){
            type="application/octet-stream";DataOutputStream output=new DataOutputStream(buffer);
            if(op.equals("batch")){output.writeInt(0x535A4231);output.writeInt(request.chunks().size());}
            for(Chunk p:request.chunks()){
                read.check();byte[] record=read.record(p);
                if(op.equals("record")){output.write(record);continue;}
                Buffer row=new Buffer();raw(new DataOutputStream(row),p,record,view.resident()?read.cursor.nativeSidecars(p.x(),p.z()):read.cursor.overlay(p.x(),p.z()));if(op.equals("batch"))output.writeInt(row.size());row.writeTo(output);
            }
        }else {Chunk p=request.chunks().get(0);byte[] record=read.record(p);json=op.equals("revision")?Map.of("chunkX",p.x(),"chunkZ",p.z(),"revision",revision(record)):Map.of("chunkX",p.x(),"chunkZ",p.z(),"recordBytes",record.length);}
        if(json!=null){Writer writer=new OutputStreamWriter(buffer,StandardCharsets.UTF_8);JSON.toJson(json,writer);writer.flush();}
        read.check();return new Reply(buffer.toByteArray(),type,release);
    }
}
