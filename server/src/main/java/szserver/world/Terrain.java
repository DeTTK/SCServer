package szserver.world;

public interface Terrain extends AutoCloseable {
    final class SceneSource {
        private final byte[] record;
        private final java.util.function.Supplier<byte[]> encoder;
        public SceneSource(byte[] record){this.record=record.clone();encoder=null;}
        /** Encoder owns an immutable chunk read view and must run on an editor/projection worker. */
        public SceneSource(java.util.function.Supplier<byte[]> encoder){this.encoder=java.util.Objects.requireNonNull(encoder);record=null;}
        public byte[] record(){return record==null?encoder.get().clone():record.clone();}
    }
    record TileCell(TilePayload.Tile tile,int blockId){}
    /** Worker-held source chunk. RegionTerrain retains immutable arrays across cache eviction. */
    interface EditChunk {
        int voxel(int cell);
        TilePayload.Tile tile(int cell);
        java.util.Map<Integer,TileCell> tiles();
    }
    /** Scene frames/indexes are decoded on a loader/editor worker before owner publication. */
    interface SceneEditChunk extends EditChunk {
        java.util.Map<java.util.UUID,ScenePayload.Entry> scenes();
        SceneSource sceneSource();
        boolean rawSceneContainer();
    }
    default String identity(){return "flat:arena-v1";}
    /** Resident backends expose revision-checked immutable reads, never their native mutable objects. */
    default ResidentMapReads<? extends EditChunk> residentReads(){return null;}
    /** Immutable authored/native baseline used by derived spatial indexes. */
    default NativeCommitIndex nativeBase(){return NativeCommitIndex.empty();}
    /** Acquire without disk IO on the caller. A cold region requests its existing loader and retries. */
    default EditChunk editChunk(int x,int z){
        var index=tileIndex(x,z);Terrain source=this;
        return new EditChunk(){
            public int voxel(int cell){return source.voxel(x*16+(cell&15),cell>>>8,z*16+(cell>>>4&15));}
            public TilePayload.Tile tile(int cell){return source.tile(x*16+(cell&15),cell>>>8,z*16+(cell>>>4&15));}
            public java.util.Map<Integer,TileCell> tiles(){return index;}
        };
    }
    /** Unknown terrain is an obstacle, never air. */
    boolean solid(int x, int y, int z);
    default int voxel(int x,int y,int z) { throw new IllegalArgumentException("terrain_voxel_unavailable"); }
    default TilePayload.Tile tile(int x,int y,int z) { return null; }
    /** Immutable tile index, built by the loader; counting happens on editor workers. */
    default java.util.Map<Integer,TileCell> tileIndex(int chunkX,int chunkZ){return java.util.Map.of();}
    default java.util.Map<java.util.UUID,ScenePayload.Entry> sceneIndex(int x,int z){throw new IllegalArgumentException("scene_edit_disabled");}
    /** Immutable per-asset templates prepared by the scene loader. */
    default ScenePayload.Entry sceneTemplate(int x,int z,String asset){return null;}
    record SceneHome(int x,int z){}
    /** Immutable global catalog. A loading catalog throws terrain_not_ready_retry, never false absence. */
    default SceneHome sceneHome(java.util.UUID identity){return null;}
    /** Actual geometry coordinates, independent of SQL homes and alternate templates. */
    default SceneHome nativeSceneHome(java.util.UUID identity){return sceneHome(identity);}
    default boolean sceneShared(java.util.UUID identity){return false;}
    /** Actual gameplay placements, including every source copy of a repeated UUID. */
    default java.util.Set<Long> nativeSceneHomes(java.util.UUID identity){SceneHome home=nativeSceneHome(identity);return home==null?java.util.Set.of():java.util.Set.of(BlockOverlays.key(home.x(),home.z()));}
    /** Immutable volume summaries from the gameplay map, independent of template-map/catalog homes. */
    default java.util.Map<java.util.UUID,SceneCullingVolume> sceneCullingVolumes(){return java.util.Map.of();}
    /** Gameplay-map homes containing explicit native references, prepared by the catalog loader. */
    default java.util.Map<java.util.UUID,java.util.Set<Long>> sceneCullingReferences(){return java.util.Map.of();}
    /** Unreviewed bodies with a reference presence flag require worker inspection before removal. */
    default java.util.Set<Long> sceneCullingUnreviewedReferences(){return java.util.Set.of();}
    default ScenePayload.Entry globalSceneTemplate(String asset){return null;}
    default boolean globalScenes(){return false;}
    /** Alternate catalog homes are only a fallback after already materialized gameplay chunks. */
    default boolean visibleScenesFirst(){return false;}
    /** UUID coordinates of previously materialized gameplay objects, retained across voxel eviction. */
    default SceneHome rememberedSceneHome(java.util.UUID id){return null;}
    /** A cache-only read: must not schedule IO or warm the supplied chunk. */
    default java.util.Map<java.util.UUID,ScenePayload.Entry> cachedSceneIndex(int x,int z){return java.util.Map.of();}
    default java.util.Map<java.util.UUID,NativePortal> portalIndex(int x,int z){return java.util.Map.of();}
    default boolean portalIndexReady(int x,int z){return ready(x*16,z*16);}
    default long portalOrder(java.util.UUID id){return Long.MAX_VALUE;}
    default NativePortal basePortal(java.util.UUID id){return null;}
    default NativePortal portalTemplate(String asset){return null;}
    default boolean sceneAssetKnown(String asset){return !sceneBehaviour(asset).isEmpty();}
    default String sceneBehaviour(String asset){return "";}
    default boolean rawSceneContainer(int x,int z){return true;}
    default String sceneRegistryDigest(){return "";}
    default SceneSource sceneSource(int x,int z){throw new IllegalArgumentException("scene_edit_disabled");}
    default boolean ready(int x, int z) { return true; }
    /** Source verification on bootstrap/checkpoint worker;unknown terrain stays unbound. */
    default String checkpointBinding()throws java.io.IOException{return null;}
    @Override default void close() throws Exception {}
    static Terrain flat() { return flat(0); }
    static Terrain flat(int surface) { return new Terrain(){
        public String checkpointBinding(){return CheckpointSources.textDigest("szwc-flat-v1:"+surface);}
        public boolean solid(int x,int y,int z){return y<surface;}
        public int voxel(int x,int y,int z){int top=surface-1;return y<0||y>top?0:(y==0?7:y<top-2?1:y<top?3:2)<<4;}
    }; }
}
