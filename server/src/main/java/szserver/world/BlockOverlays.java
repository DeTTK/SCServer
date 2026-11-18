package szserver.world;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Indexed by chunk. The owner mutates live state; the command barrier may prepare a detached replacement. */
public final class BlockOverlays {
    private final MapLimits limits;
    public BlockOverlays(){this(MapLimits.DEFAULT);}
    public BlockOverlays(MapLimits limits){this.limits=Objects.requireNonNull(limits);}
    public MapLimits limits(){return limits;}
    public record Voxel(int id,int metadata,long revision){}
    public record Snapshot(long revision,Map<Integer,Voxel> cells,Map<Integer,TilePayload.Tile> tiles,Map<UUID,ScenePayload.Entry> scenes,long refreshRevision){
        public Snapshot(long revision,Map<Integer,Voxel> cells,Map<Integer,TilePayload.Tile> tiles,Map<UUID,ScenePayload.Entry> scenes){this(revision,cells,tiles,scenes,tiles.isEmpty()&&scenes.isEmpty()?0:revision);}
        public Snapshot(long revision,Map<Integer,Voxel> cells,Map<Integer,TilePayload.Tile> tiles){this(revision,cells,tiles,Map.of());}
        public Snapshot(long revision,Map<Integer,Voxel> cells){this(revision,cells,Map.of());}
    }
    private static final Snapshot EMPTY=new Snapshot(0,Map.of(),Map.of());
    private static final class Entry {final VoxelCells cells=new VoxelCells();final TileCells tiles=new TileCells();final Map<UUID,ScenePayload.Entry> scenes=new HashMap<>();final Map<Integer,Integer> tileIds=tiles.idView();volatile long revision;long refreshRevision;Snapshot cached;}
    private int sceneEntries;
    public boolean hasScene(int x,int z,UUID id){Entry e=chunks.get(key(x,z));return e!=null&&e.scenes.containsKey(id);}
    ScenePayload.Entry scene(int x,int z,UUID id){Entry e=chunks.get(key(x,z));return e==null?null:e.scenes.get(id);}
    public void validateSceneCapacity(int additions){if((long)sceneEntries+additions>100000)throw new IllegalArgumentException("scene_override_limit");}
    void putScene(int x,int z,UUID id,ScenePayload.Entry value){Entry e=chunks.computeIfAbsent(key(x,z),k->new Entry());if(e.scenes.containsKey(id)&&Objects.equals(e.scenes.get(id),value))return;if(!e.scenes.containsKey(id)){validateSceneCapacity(1);sceneEntries++;}e.scenes.put(id,value);e.cached=null;e.revision++;e.refreshRevision=e.revision;epoch++;sceneEpoch++;}
    private int tileEntries,payloadEntries;
    public record TileStats(int entries,int payloads,long completeBytes,int limit){}
    private volatile TileStats tileStats=new TileStats(0,0,0,0);
    private void publishTiles(){tileStats=new TileStats(tileEntries,payloadEntries,completeTileBytes,limits.tileStates());}
    public Map<String,Object> tileMetrics(){TileStats s=tileStats;return Map.of("states",s.entries(),"clearMarkers",s.entries()-s.payloads(),"payloads",s.payloads(),"stateLimit",limits.tileStates(),"payloadLimit",100000,"completeBytes",s.completeBytes(),"completeByteLimit",32L*1024*1024,"compactClearMarkers",true);}
    public int tilePayloadCount(){return payloadEntries;}
    private long completeTileBytes;
    public void validateCompleteTileBytes(long delta){if(completeTileBytes+delta<0||completeTileBytes+delta>32L*1024*1024)throw new IllegalArgumentException("complete_tile_byte_limit");}
    private volatile Map<Long,Entry> chunks=new ConcurrentHashMap<>();
    private volatile long epoch;
    public long epoch(){return epoch;}
    private volatile long sceneEpoch;
    private int voxelEntries;
    public int voxelCount(){return voxelEntries;}
    public void validateVoxelCapacity(int additions){if((long)voxelEntries+additions>limits.voxels())throw new IllegalArgumentException("block_override_limit");}
    public record CellChange(World.Block position,int id,int metadata,boolean voxelChanged,boolean tileChanged,TilePayload.Tile tile){}
    public record SceneChange(int x,int z,UUID id,ScenePayload.Entry value){}
    record SceneGeneration(BlockOverlays owner,long epoch){}
    /** Read only under the serialized command barrier; epoch guards catch concurrent live mutation. */
    public static final class Capture {
        private final BlockOverlays owner;private final Map<Long,Entry> root;private final long epoch,sceneEpoch;private final int voxels,tiles,payloads,scenes;private final long bytes;
        private Capture(BlockOverlays owner){this.owner=owner;root=owner.chunks;epoch=owner.epoch;sceneEpoch=owner.sceneEpoch;voxels=owner.voxelEntries;tiles=owner.tileEntries;payloads=owner.payloadEntries;scenes=owner.sceneEntries;bytes=owner.completeTileBytes;}
        private void current(){if(owner.chunks!=root||owner.epoch!=epoch)throw new IllegalArgumentException("block_publication_stale");}
        public void checkCurrent(){current();}
        public int voxelCount(){return voxels;}
        public int sceneCount(){return scenes;}
        public int tileCount(){return tiles;}
        public long completeTileBytes(){return bytes;}
        public boolean sameScenes(Capture other){return other!=null&&owner==other.owner&&sceneEpoch==other.sceneEpoch;}
        SceneGeneration sceneGeneration(){return new SceneGeneration(owner,sceneEpoch);}
        /** Copy only scene overrides, including tombstones; never freeze millions of voxel edits. */
        public List<SceneChange> sceneChanges(){
            SceneCollision.worker();current();if(scenes==0)return List.of();List<SceneChange> result=new ArrayList<>(scenes);
            try{for(var chunk:root.entrySet())for(var row:chunk.getValue().scenes.entrySet())result.add(new SceneChange((int)(chunk.getKey()>>32),(int)(long)chunk.getKey(),row.getKey(),row.getValue()));}
            catch(ConcurrentModificationException stale){throw new IllegalArgumentException("block_publication_stale",stale);}
            current();if(result.size()!=scenes)throw new IllegalArgumentException("block_publication_stale");return List.copyOf(result);
        }
        public int voxelLimit(){return owner.limits.voxels();}
        public void validateCapacity(int voxelAdditions,int tileAdditions,long completeDelta){validateCapacity(voxelAdditions,tileAdditions,0,completeDelta);}
        public void validateCapacity(int voxelAdditions,int tileAdditions,int payloadDelta,long completeDelta){current();if((long)voxels+voxelAdditions>voxelLimit())throw new IllegalArgumentException("block_override_limit");if((long)tiles+tileAdditions>owner.limits.tileStates())throw new IllegalArgumentException("tile_clear_limit");if((long)payloads+payloadDelta>100000||payloads+payloadDelta<0)throw new IllegalArgumentException("tile_override_limit");if(bytes+completeDelta<0||bytes+completeDelta>32L*1024*1024)throw new IllegalArgumentException("complete_tile_byte_limit");}

        /** Immutable snapshot copied on the command worker, never placed in the live entry cache. */
        public Snapshot snapshot(int x,int z){current();Entry e=root.get(key(x,z));Snapshot result=e==null?EMPTY:e.cached==null?BlockOverlays.snapshot(e):e.cached;current();return result;}
    }
    public static final class Prepared {
        private Set<World.ScenePlacement> retiredScenes=Set.of();private Set<UUID> retiredIdentities=Set.of();
        public Set<World.ScenePlacement> retiredScenes(){return retiredScenes;}public Set<UUID> retiredIdentities(){return retiredIdentities;}
        public int sceneCount(){return scenes;}
        private final Capture capture;private final Map<Long,Entry> root;private final int voxels,tiles,payloads,scenes;private final long bytes;private final boolean sceneChanged;
        private Prepared(Capture capture,Map<Long,Entry> root,int voxels,int tiles,int payloads,int scenes,long bytes,boolean sceneChanged){this.capture=capture;this.root=root;this.voxels=voxels;this.tiles=tiles;this.payloads=payloads;this.scenes=scenes;this.bytes=bytes;this.sceneChanged=sceneChanged;}
        public int voxelCount(){return voxels;}
        public int tileCount(){return tiles;}
        public long completeTileBytes(){return bytes;}
    }
    public Capture capture(){return new Capture(this);}
    /** Command barrier only: detached mutable containers, shared immutable scene/tile bodies.
     * Later owner edits or native retirement cannot change a checkpoint being streamed. */
    BlockOverlays detached(){
        SceneCollision.worker();BlockOverlays result=new BlockOverlays(limits);for(var row:chunks.entrySet())result.chunks.put(row.getKey(),copy(row.getValue()));
        result.voxelEntries=voxelEntries;result.tileEntries=tileEntries;result.payloadEntries=payloadEntries;result.sceneEntries=sceneEntries;result.completeTileBytes=completeTileBytes;result.epoch=epoch;result.sceneEpoch=sceneEpoch;return result;
    }
    /** Clone changed chunks and build immutable cached snapshots on a worker, never inside a tick. */
    public static Prepared prepare(Capture capture,Iterable<CellChange> changes){
        return prepare(capture,changes,List.of());
    }
    /** Worker plan: retire voxel, tile and scene bodies only at their durable complete chunk revision.
     * The caller prepares global scene metadata and terrain before publishing these roots together. */
    public static Prepared prepareNativeRetirement(Capture capture,NativeCommitIndex committed){
        SceneCollision.worker();capture.current();Map<Long,Entry> root=new ConcurrentHashMap<>(capture.root);int voxels=capture.voxels,tiles=capture.tiles,payloads=capture.payloads;long bytes=capture.bytes;
        int scenes=capture.scenes;Set<World.ScenePlacement> placements=new HashSet<>();Set<UUID> identities=new HashSet<>(),later=new HashSet<>();
        for(var row:capture.root.entrySet()){Entry old=row.getValue();long key=row.getKey();if(old.revision!=committed.revision((int)(key>>32),(int)key)){later.addAll(old.scenes.keySet());continue;}if(old.cells.isEmpty()&&old.tiles.isEmpty()&&old.scenes.isEmpty())continue;
            Entry next=new Entry();next.revision=old.revision;next.refreshRevision=old.refreshRevision;next.cached=snapshot(next);root.put(key,next);voxels-=old.cells.size();tiles-=old.tiles.size();scenes-=old.scenes.size();for(UUID id:old.scenes.keySet()){identities.add(id);placements.add(new World.ScenePlacement((int)(key>>32),(int)key,id));}for(var tile:old.tiles.values())if(tile!=null){payloads--;bytes-=tile.completeStateSize();}
        }
        identities.removeAll(later);capture.current();Prepared prepared=new Prepared(capture,root,voxels,tiles,payloads,scenes,bytes,scenes!=capture.scenes);prepared.retiredScenes=Set.copyOf(placements);prepared.retiredIdentities=Set.copyOf(identities);return prepared;
    }
    public static Prepared prepare(Capture capture,Iterable<CellChange> changes,List<SceneChange> sceneChanges){
        capture.current();Map<Long,Entry> root=new ConcurrentHashMap<>(capture.root);Map<Long,Entry> touched=new HashMap<>();int voxels=capture.voxels,tiles=capture.tiles,payloads=capture.payloads,scenes=capture.scenes;long bytes=capture.bytes;int count=0;
        for(var change:changes){
            if(++count>2_000_000)throw new IllegalArgumentException("block_publication_count");World.Block p=change.position();int id=change.id(),metadata=change.metadata();
            if(p==null||p.x()<-szserver.world.MapBounds.HORIZONTAL||p.x()>szserver.world.MapBounds.HORIZONTAL||p.z()<-szserver.world.MapBounds.HORIZONTAL||p.z()>szserver.world.MapBounds.HORIZONTAL||p.y()<0||p.y()>255||id<0||id>4095||metadata<0||metadata>15)throw new IllegalArgumentException("invalid_block");
            if(change.tile()!=null&&(change.tile().x()!=p.x()||change.tile().y()!=p.y()||change.tile().z()!=p.z()||id==0))throw new IllegalArgumentException("tile_position_or_block_mismatch");
            if(!change.voxelChanged()&&!change.tileChanged())continue;
            long key=key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));Entry e=touched.get(key);if(e==null){e=copy(capture.root.get(key));touched.put(key,e);root.put(key,e);}int cell=cell(p);
            if(change.voxelChanged()&&e.tiles.containsKey(cell)&&(id==0||e.tileIds.getOrDefault(cell,0)!=id)){
                long[] delta=tile(e,cell,null,id);tiles+=Math.toIntExact(delta[0]);bytes+=delta[1];payloads+=Math.toIntExact(delta[2]);
            }
            if(change.voxelChanged()){
                Voxel old=e.cells.get(cell);if(old==null||old.id()!=id||old.metadata()!=metadata){if(old==null)voxels++;long revision=Math.addExact(e.revision,1);e.cells.put(cell,new Voxel(id,metadata,revision));e.revision=revision;}
            }
            if(change.tileChanged()){long[] delta=tile(e,cell,change.tile(),id);tiles+=Math.toIntExact(delta[0]);bytes+=delta[1];payloads+=Math.toIntExact(delta[2]);}
            if(voxels>capture.voxelLimit()||tiles>capture.owner.limits.tileStates()||payloads>100000||bytes<0||bytes>32L*1024*1024)throw new IllegalArgumentException(voxels>capture.voxelLimit()?"block_override_limit":tiles>capture.owner.limits.tileStates()?"tile_clear_limit":payloads>100000?"tile_override_limit":"complete_tile_byte_limit");
        }
        if(sceneChanges.size()>SceneLimits.PLACEMENTS)throw new IllegalArgumentException("scene_edit_count");boolean sceneChanged=false;
        for(var change:sceneChanges){
            if(Math.abs((long)change.x())>szserver.world.MapBounds.CHUNK||Math.abs((long)change.z())>szserver.world.MapBounds.CHUNK||change.id()==null||change.value()!=null&&!change.id().equals(change.value().object().identity()))throw new IllegalArgumentException("scene_edit_identity_or_bounds");
            long key=key(change.x(),change.z());Entry e=touched.get(key);if(e==null){e=copy(capture.root.get(key));touched.put(key,e);root.put(key,e);}
            if(e.scenes.containsKey(change.id())&&Objects.equals(e.scenes.get(change.id()),change.value()))continue;
            sceneChanged=true;
            if(!e.scenes.containsKey(change.id())&&++scenes>100000)throw new IllegalArgumentException("scene_override_limit");
            e.scenes.put(change.id(),change.value());e.revision=Math.addExact(e.revision,1);e.refreshRevision=e.revision;
        }
        for(Entry e:touched.values())e.cached=snapshot(e);capture.current();return new Prepared(capture,root,voxels,tiles,payloads,scenes,bytes,sceneChanged);
    }
    private static Entry copy(Entry old){Entry result=new Entry();if(old!=null){result.cells.copyFrom(old.cells);result.tiles.copyFrom(old.tiles);result.scenes.putAll(old.scenes);result.revision=old.revision;result.refreshRevision=old.refreshRevision;}return result;}
    private static long[] tile(Entry e,int cell,TilePayload.Tile tile,int id){
        if(e.tiles.containsKey(cell)&&Objects.equals(e.tiles.get(cell),tile)&&e.tileIds.getOrDefault(cell,0)==id)return new long[]{0,0,0};
        TilePayload.Tile old=e.tiles.get(cell);long bytes=(tile==null?0:tile.completeStateSize())-(old==null?0:old.completeStateSize());int addition=e.tiles.containsKey(cell)?0:1;e.tiles.put(cell,tile);e.tileIds.put(cell,id);e.revision=Math.addExact(e.revision,1);e.refreshRevision=e.revision;return new long[]{addition,bytes,(tile==null?0:1)-(old==null?0:1)};
    }
    /** Constant-time publication on the owner. The whole prepared root becomes visible together. */
    public void publish(Prepared prepared){
        if(prepared.capture.owner!=this)throw new IllegalArgumentException("block_publication_owner");prepared.capture.current();
        voxelEntries=prepared.voxels;tileEntries=prepared.tiles;payloadEntries=prepared.payloads;completeTileBytes=prepared.bytes;sceneEntries=prepared.scenes;chunks=prepared.root;epoch++;if(prepared.sceneChanged)sceneEpoch++;publishTiles();
    }
    /** Backed, shallow views: only the serialized command barrier may hold these while exporting.
     * No global copy or native decoding runs on the simulation owner. */
    record CheckpointChunk(long key,long revision,Map<Integer,Voxel> cells,Map<Integer,TilePayload.Tile> tiles,Map<Integer,Integer> tileIds,Map<UUID,ScenePayload.Entry> scenes){}
    Iterable<CheckpointChunk> checkpointChunks(){return ()->chunks.entrySet().stream().map(row->{Entry e=row.getValue();return new CheckpointChunk(row.getKey(),e.revision,Collections.unmodifiableMap(e.cells),Collections.unmodifiableMap(e.tiles),Collections.unmodifiableMap(e.tileIds),Collections.unmodifiableMap(e.scenes));}).iterator();}
    int checkpointChunkCount(){return chunks.size();}
    void restoreChunk(long key,long revision){if(revision<1||chunks.putIfAbsent(key,new Entry())!=null)throw new IllegalArgumentException("checkpoint_chunk_duplicate_or_revision");chunks.get(key).revision=revision;epoch++;}
    private Entry restoring(long key,int cell){Entry e=chunks.get(key);if(e==null||cell<0||cell>65535)throw new IllegalArgumentException("checkpoint_chunk_or_cell");return e;}
    void restoreVoxel(long key,int cell,Voxel value){Entry e=restoring(key,cell);validateVoxelCapacity(1);if(value.id()<0||value.id()>4095||value.metadata()<0||value.metadata()>15||value.revision()<1||value.revision()>e.revision||e.cells.putIfAbsent(cell,value)!=null)throw new IllegalArgumentException("checkpoint_voxel");voxelEntries++;epoch++;}
    /** Build restored immutable views before any listeners or owner ticks can access them. */
    void finishRestore(){for(Entry entry:chunks.values()){if(!entry.scenes.isEmpty()||!entry.tiles.isEmpty())entry.refreshRevision=entry.revision;entry.cached=snapshot(entry);}}
    void restoreTile(long key,int cell,TilePayload.Tile tile,int id){Entry e=restoring(key,cell);if(id<0||id>4095||e.tiles.containsKey(cell))throw new IllegalArgumentException("checkpoint_tile");validateTileCapacity(1);validateTilePayloadCapacity(tile==null?0:1);long bytes=tile==null?0:tile.completeStateSize();validateCompleteTileBytes(bytes);tileEntries++;if(tile!=null)payloadEntries++;completeTileBytes+=bytes;e.tiles.put(cell,tile);e.tileIds.put(cell,id);epoch++;publishTiles();}
    void restoreScene(long key,UUID id,ScenePayload.Entry scene){Entry e=chunks.get(key);if(e==null||e.scenes.containsKey(id)||scene!=null&&!id.equals(scene.object().identity()))throw new IllegalArgumentException("checkpoint_scene");validateSceneCapacity(1);sceneEntries++;e.scenes.put(id,scene);epoch++;sceneEpoch++;}
    public static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public long version(int x,int z){Entry entry=chunks.get(key(x,z));return entry==null?0:entry.revision;}
    /** Owner-only bounded read for transport deltas. Never copy scene or tile bodies. */
    public Snapshot smallVoxels(int x,int z,int limit){
        Entry entry=chunks.get(key(x,z));if(entry==null)return EMPTY;
        if(entry.cells.size()>limit)return null;
        return new Snapshot(entry.revision,entry.cells.freeze(),Map.of(),Map.of(),entry.refreshRevision);
    }
    void put(World.Block block,int id,int metadata){
        Entry entry=chunks.computeIfAbsent(key(Math.floorDiv(block.x(),16),Math.floorDiv(block.z(),16)),key->new Entry());
        int cell=(block.y()<<8)|(Math.floorMod(block.z(),16)<<4)|Math.floorMod(block.x(),16);
        Voxel old=entry.cells.get(cell);if(old!=null&&old.id==id&&old.metadata==metadata)return;
        if(old==null)voxelEntries++;long revision=entry.revision+1;entry.cells.put(cell,new Voxel(id,metadata,revision));entry.cached=null;entry.revision=revision;epoch++;
    }
    Voxel get(World.Block block){Entry entry=chunks.get(key(Math.floorDiv(block.x(),16),Math.floorDiv(block.z(),16)));return entry==null?null:entry.cells.get((block.y()<<8)|(Math.floorMod(block.z(),16)<<4)|Math.floorMod(block.x(),16));}
    int packed(int x,int y,int z){if(y<0||y>255)return -1;Entry entry=chunks.get(key(Math.floorDiv(x,16),Math.floorDiv(z,16)));return entry==null?-1:entry.cells.packed((y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16));}
    private Entry entry(World.Block p){return chunks.get(key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));}
    private static int cell(World.Block p){return (p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);}
    boolean hasTile(World.Block p){Entry e=entry(p);return e!=null&&e.tiles.containsKey(cell(p));}
    TilePayload.Tile tile(World.Block p){Entry e=entry(p);return e==null?null:e.tiles.get(cell(p));}
    int tileId(World.Block p){Entry e=entry(p);return e==null?0:e.tileIds.getOrDefault(cell(p),0);}
    void validateTileCapacity(int additions){if((long)tileEntries+additions>limits.tileStates())throw new IllegalArgumentException("tile_clear_limit");}
    void validateTilePayloadCapacity(int delta){if((long)payloadEntries+delta>100000||payloadEntries+delta<0)throw new IllegalArgumentException("tile_override_limit");}
    void putTile(World.Block p,TilePayload.Tile tile,int id){
        if(id<0||id>4095)throw new IllegalArgumentException("tile_cell_or_id");long key=key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));Entry e=chunks.get(key);int cell=cell(p);
        if(e!=null&&e.tiles.containsKey(cell)&&Objects.equals(e.tiles.get(cell),tile)&&e.tileIds.getOrDefault(cell,0)==id)return;
        TilePayload.Tile old=e==null?null:e.tiles.get(cell);int addition=e!=null&&e.tiles.containsKey(cell)?0:1,payloadDelta=(tile==null?0:1)-(old==null?0:1);long delta=(tile==null?0:tile.completeStateSize())-(old==null?0:old.completeStateSize());validateTileCapacity(addition);validateTilePayloadCapacity(payloadDelta);validateCompleteTileBytes(delta);
        if(e==null){e=new Entry();chunks.put(key,e);}tileEntries+=addition;payloadEntries+=payloadDelta;completeTileBytes+=delta;e.tiles.put(cell,tile);e.tileIds.put(cell,id);e.cached=null;e.revision++;e.refreshRevision=e.revision;epoch++;publishTiles();
    }
    public void prepareBootstrapSnapshots(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("map_snapshot_prepare_on_owner");for(Entry e:chunks.values())if(e.cached==null)e.cached=snapshot(e);}
    private static Snapshot snapshot(Entry entry){var tiles=entry.tiles.freeze();return new Snapshot(entry.revision,entry.cells.freeze(),tiles.tiles(),Collections.unmodifiableMap(new HashMap<>(entry.scenes)),entry.refreshRevision);}
    Snapshot snapshot(int x,int z){Entry entry=chunks.get(key(x,z));if(entry==null)return EMPTY;if(entry.cached==null)entry.cached=snapshot(entry);return entry.cached;}
}
