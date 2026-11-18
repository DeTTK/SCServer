package szserver.world;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Serialized command-barrier view. All sorting, chunk reads, copies and overlap indexing are worker work. */
public final class BlockReadView {
    record ActorBox(double x,double y,double z,double xx,double yy,double zz){}
    /** Arrays are owned by the calling command worker. Native tile objects remain immutable. */
    public record Values(int[] voxels,TilePayload.Tile[] tiles,BitSet tileStates){}
    private final Terrain terrain;private final BlockOverlays.Capture overlays;private final List<ActorBox> actors;
    private final NativeCommitIndex nativeBase;
    private final Map<World.ScenePlacement,World.ResidentSceneChange> nativeScenes;private final long sceneEpoch;private final java.util.function.LongSupplier currentSceneEpoch;
    private ResidentMapReads<? extends Terrain.EditChunk>.Capture residentCapture;
    private Set<World.Block> occupied;
    BlockReadView(Terrain terrain,BlockOverlays.Capture overlays,List<ActorBox> actors){this(terrain,overlays,actors,null,0,()->0);}
    BlockReadView(Terrain terrain,BlockOverlays.Capture overlays,List<ActorBox> actors,Map<World.ScenePlacement,World.ResidentSceneChange> scenes,long sceneEpoch,java.util.function.LongSupplier currentSceneEpoch){this.terrain=terrain;this.overlays=overlays;this.actors=actors;nativeBase=terrain.nativeBase();nativeScenes=scenes;this.sceneEpoch=sceneEpoch;this.currentSceneEpoch=currentSceneEpoch;}
    public NativeCommitIndex nativeBase(){return nativeBase;}
    public boolean resident(){return terrain.residentReads()!=null;}
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("block_scan_on_owner");}
    public void checkCurrent(){NavigationBudget.check();overlays.checkCurrent();if(nativeScenes!=null&&sceneEpoch!=currentSceneEpoch.getAsLong())throw new IllegalArgumentException("scene_metadata_publication_stale");var resident=terrain.residentReads();if(resident!=null){if(residentCapture==null)residentCapture=resident.capture();residentCapture.checkCurrent();}}
    BlockOverlays.Capture sceneCapture(){return overlays;}
    Object sceneGeneration(){return nativeScenes==null?overlays.sceneGeneration():nativeScenes;}
    List<BlockOverlays.SceneChange> sceneChanges(){worker();checkCurrent();return nativeScenes==null?overlays.sceneChanges():nativeScenes.entrySet().stream().map(row->new BlockOverlays.SceneChange(row.getKey().x(),row.getKey().z(),row.getKey().id(),row.getValue().entry())).toList();}
    public void validateCapacity(int blocks,int tiles,long bytes){worker();overlays.validateCapacity(blocks,tiles,bytes);}
    public void validateCapacity(int blocks,int tiles,int payloads,long bytes){worker();overlays.validateCapacity(blocks,tiles,payloads,bytes);}
    private static void bounds(World.Block p){if(p==null||p.x()<-szserver.world.MapBounds.HORIZONTAL||p.x()>szserver.world.MapBounds.HORIZONTAL||p.z()<-szserver.world.MapBounds.HORIZONTAL||p.z()>szserver.world.MapBounds.HORIZONTAL||p.y()<0||p.y()>255)throw new IllegalArgumentException("invalid_block");}
    /** A primitive 8-byte row groups at most two million cells; no boxed per-chunk index lists. */
    public static long[] ordered(List<World.Block> cells){
        worker();if(cells.size()>2_000_000)throw new IllegalArgumentException("invalid_edit_count");long[] order=new long[cells.size()];
        boolean wide=false;
        for(int i=0;i<order.length;i++){World.Block p=cells.get(i);bounds(p);if(Math.abs((long)p.x())>1000000||Math.abs((long)p.z())>1000000){wide=true;break;}}
        long[] keys=wide?new long[order.length]:null;
        for(int i=0;i<order.length;i++){World.Block p=cells.get(i);bounds(p);
            if(wide){keys[i]=BlockOverlays.key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));order[i]=i;}
            else{long x=Math.floorDiv(p.x(),16)+62500L,z=Math.floorDiv(p.z(),16)+62500L;order[i]=(((x<<17)|z)<<21)|i;}
        }
        // Full domain chunk coordinates need44 bits, plus21 index bits: they cannot
        // share one64-bit packed row. A separate primitive key array avoids aliases.
        // Preserve the original8-byte-per-cell path for existing compact edits.
        sort(order,keys,0,order.length-1,order.length==0?0:2*(31-Integer.numberOfLeadingZeros(order.length)));return order;
    }
    private static int compare(long a,long b,long[] keys){
        if(keys==null)return Long.compare(a,b);
        long ka=keys[(int)a],kb=keys[(int)b];int x=Long.compare(ka>>32,kb>>32);
        if(x!=0)return x;int z=Integer.compare((int)ka,(int)kb);return z!=0?z:Long.compare(a,b);
    }
    private static void sort(long[] rows,long[] keys,int low,int high,int depth){
        while(high-low>24){
            if(depth--==0){heapSort(rows,keys,low,high);return;}
            long a=rows[low],b=rows[(low+high)>>>1],c=rows[high],pivot;
            if(compare(a,b,keys)<0)pivot=compare(b,c,keys)<0?b:(compare(a,c,keys)<0?c:a);
            else pivot=compare(a,c,keys)<0?a:(compare(b,c,keys)<0?c:b);
            int left=low,right=high;
            while(left<=right){while(compare(rows[left],pivot,keys)<0)left++;while(compare(rows[right],pivot,keys)>0)right--;if(left<=right){long value=rows[left];rows[left++]=rows[right];rows[right--]=value;}}
            if(right-low<high-left){if(low<right)sort(rows,keys,low,right,depth);low=left;}else{if(left<high)sort(rows,keys,left,high,depth);high=right;}
        }
        for(int i=low+1;i<=high;i++){long value=rows[i];int j=i;while(j>low&&compare(rows[j-1],value,keys)>0){rows[j]=rows[j-1];j--;}rows[j]=value;}
    }
    private static void heapSort(long[] rows,long[] keys,int low,int high){
        int count=high-low+1;for(int i=count/2-1;i>=0;i--)sift(rows,keys,low,i,count);
        for(int end=count-1;end>0;end--){long value=rows[low];rows[low]=rows[low+end];rows[low+end]=value;sift(rows,keys,low,0,end);}
    }
    private static void sift(long[] rows,long[] keys,int low,int root,int count){
        long value=rows[low+root];for(int child=root*2+1;child<count;child=root*2+1){if(child+1<count&&compare(rows[low+child],rows[low+child+1],keys)<0)child++;if(compare(value,rows[low+child],keys)>=0)break;rows[low+root]=rows[low+child];root=child;}rows[low+root]=value;
    }
    public static int index(long word){return (int)(word&0x1fffff);}
    public Values read(List<World.Block> cells)throws InterruptedException{
        worker();checkCurrent();int[] voxels=new int[cells.size()];TilePayload.Tile[] tiles=new TilePayload.Tile[cells.size()];BitSet tileStates=new BitSet(cells.size());Cursor cursor=cursor();
        for(long word:ordered(cells)){int i=index(word);World.Block p=cells.get(i);voxels[i]=cursor.voxel(p);tiles[i]=cursor.tile(p);if(cursor.hasTileState(p))tileStates.set(i);}
        checkCurrent();return new Values(voxels,tiles,tileStates);
    }
    public void validateBlock(Cursor cursor,World.Block p,int packed){
        worker();bounds(p);if(packed<0||packed>65535)throw new IllegalArgumentException("invalid_block");
        if(overlays.voxelCount()>=overlays.voxelLimit()&&!cursor.hasOverride(p))throw new IllegalArgumentException("block_override_limit");
        int id=packed>>>4;if(id==0||id==8||id==9||id==10||id==11)return;
        double[][] shapes=ResourceBlockShapes.cells(packed);if(shapes==null)shapes=BuiltinBlockShapes.cells(packed);if(shapes==null)shapes=CustomBlockShapes.cells(packed);
        if(shapes!=null&&shapes.length==0)return;
        if(occupied==null){occupied=new HashSet<>();for(var box:actors)for(int y=(int)Math.floor(box.y);y<(int)Math.ceil(box.yy);y++)for(int z=(int)Math.floor(box.z);z<(int)Math.ceil(box.zz);z++)for(int x=(int)Math.floor(box.x);x<(int)Math.ceil(box.xx);x++)occupied.add(new World.Block(x,y,z));}
        if(occupied.contains(p))throw new IllegalArgumentException("block_overlaps_entity");
    }
    public Cursor cursor(){worker();checkCurrent();return new Cursor();}
    /** Held immutable arrays and overlay; native route frames never call the terrain loader. */
    public record ResidentChunk(Terrain.EditChunk base,BlockOverlays.Snapshot overlay){
        public int voxel(int cell){var edit=overlay.cells().get(cell);return edit==null?base.voxel(cell):(edit.id()<<4)|edit.metadata();}
        public TilePayload.Tile tile(int cell){if(overlay.tiles().containsKey(cell))return overlay.tiles().get(cell);var edit=overlay.cells().get(cell);return edit!=null&&edit.id()!=(base.voxel(cell)>>>4)?null:base.tile(cell);}
    }
    public final class Cursor {
        public ResidentChunk pin(int x,int z)throws InterruptedException{worker();BlockOverlays.Snapshot snapshot=at(x,z);return new ResidentChunk(base(),snapshot);}
        private long key=Long.MIN_VALUE;private BlockOverlays.Snapshot overlay;private Terrain.EditChunk base;
        private BlockOverlays.Snapshot at(World.Block p){bounds(p);return at(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));}
        private BlockOverlays.Snapshot at(int x,int z){long next=BlockOverlays.key(x,z);if(overlay==null||key!=next){overlay=overlays.snapshot(x,z);key=next;base=null;}return overlay;}
        private Terrain.EditChunk base()throws InterruptedException{
            if(base==null){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){checkCurrent();try{base=residentCapture==null?terrain.editChunk((int)(key>>32),(int)key):residentCapture.chunk((int)(key>>32),(int)key);break;}catch(IllegalArgumentException e){if(!"terrain_not_ready_retry".equals(e.getMessage()))throw e;if(System.nanoTime()>=deadline)throw new IllegalArgumentException("block_edit_terrain_load_timeout");Thread.sleep(5);}}}return base;
        }
        private int cell(World.Block p){return (p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}
        private void retry(IllegalArgumentException error,long deadline)throws InterruptedException{if(!"terrain_not_ready_retry".equals(error.getMessage()))throw error;checkCurrent();if(System.nanoTime()>=deadline)throw new IllegalArgumentException("block_edit_terrain_load_timeout");base=null;Thread.sleep(5);}
        private int baseVoxel(int cell)throws InterruptedException{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true)try{return base().voxel(cell);}catch(IllegalArgumentException e){retry(e,deadline);}}
        private TilePayload.Tile baseTile(int cell)throws InterruptedException{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true)try{return base().tile(cell);}catch(IllegalArgumentException e){retry(e,deadline);}}
        public int voxel(World.Block p)throws InterruptedException{worker();BlockOverlays.Voxel value=at(p).cells().get(cell(p));return value==null?baseVoxel(cell(p)):(value.id()<<4)|value.metadata();}
        /** Old native terrain reads return air outside stored sections, but still require a known chunk. */
        public int nativeVoxel(World.Block p)throws InterruptedException{
            if(p.y()>=0&&p.y()<=255)return voxel(p);
            worker();at(new World.Block(p.x(),0,p.z()));baseVoxel(0);return 0;
        }
        public TilePayload.Tile tile(World.Block p)throws InterruptedException{
            worker();BlockOverlays.Snapshot snapshot=at(p);int cell=cell(p);if(snapshot.tiles().containsKey(cell))return snapshot.tiles().get(cell);
            TilePayload.Tile tile=baseTile(cell);if(tile==null)return null;BlockOverlays.Voxel edit=snapshot.cells().get(cell);int id=edit==null?baseVoxel(cell)>>>4:edit.id();return id==0||edit!=null&&id!=(baseVoxel(cell)>>>4)?null:tile;
        }
        public boolean hasOverride(World.Block p){worker();return at(p).cells().containsKey(cell(p));}
        public boolean hasTileOverride(World.Block p){worker();return at(p).tiles().containsKey(cell(p));}
        public boolean hasTilePayloadOverride(World.Block p){worker();return at(p).tiles().get(cell(p))!=null;}
        /** Include explicit null overlays and hidden authored tiles, not only effective appearance. */
        public boolean hasTileState(World.Block p)throws InterruptedException{worker();return at(p).tiles().containsKey(cell(p))||baseTile(cell(p))!=null;}
        public int completeTileBytes(World.Block p){worker();TilePayload.Tile tile=at(p).tiles().get(cell(p));return tile==null?0:tile.completeStateSize();}
        public World.TileChunk tileChunk(int x,int z)throws InterruptedException{worker();BlockOverlays.Snapshot snapshot=at(x,z);return new World.TileChunk(base().tiles(),snapshot);}
        public BlockOverlays.Snapshot overlay(int x,int z){worker();return at(x,z);}
        /** Effective native geometry from this same captured revision, for backend exports. */
        public byte[] nativeRecord(int x,int z)throws InterruptedException{worker();at(x,z);if(!(base() instanceof Terrain.SceneEditChunk chunk))throw new IllegalArgumentException("native_map_records_unavailable");var source=chunk.sceneSource();if(source==null)throw new IllegalArgumentException("native_map_records_unavailable");byte[] record=source.record();checkCurrent();return record;}
        public BlockOverlays.Snapshot nativeSidecars(int x,int z)throws InterruptedException{worker();at(x,z);Map<Integer,TilePayload.Tile> tiles=new HashMap<>();for(var row:base().tiles().entrySet())if(row.getValue().tile().completeStateSize()>0)tiles.put(row.getKey(),row.getValue().tile());checkCurrent();return new BlockOverlays.Snapshot(0,Map.of(),tiles,Map.of());}
    }
}
