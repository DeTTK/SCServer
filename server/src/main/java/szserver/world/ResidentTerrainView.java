package szserver.world;

import java.util.*;

/** Existing terrain/query API backed exclusively by admitted resident chunk snapshots. */
public final class ResidentTerrainView<C extends Terrain.EditChunk> implements Terrain {
    private final ResidentMapReads<C> reads;
    private final Terrain catalog;
    public ResidentTerrainView(ResidentMapReads<C> reads){this(reads,null);}
    /** Original catalog supplies authored global indexes only. Physical chunk reads never fall back to it. */
    public ResidentTerrainView(ResidentMapReads<C> reads,Terrain catalog){this.reads=Objects.requireNonNull(reads);this.catalog=catalog;}
    @Override public String identity(){return catalog==null?Terrain.super.identity():catalog.identity();}
    @Override public String sceneRegistryDigest(){return catalog==null?"":catalog.sceneRegistryDigest();}
    @Override public String checkpointBinding()throws java.io.IOException{return catalog==null?null:catalog.checkpointBinding();}
    private Terrain.SceneEditChunk scenes(int x,int z){var data=chunk(x,z).data();if(!(data instanceof Terrain.SceneEditChunk scene))throw new IllegalArgumentException("resident_scene_registry_required");return scene;}
    @Override public Map<UUID,ScenePayload.Entry> sceneIndex(int x,int z){return scenes(x,z).scenes();}
    @Override public Map<UUID,ScenePayload.Entry> cachedSceneIndex(int x,int z){var found=reads.peek(new ResidentChunks.Key(x,z));return found!=null&&found.data() instanceof Terrain.SceneEditChunk scene?scene.scenes():Map.of();}
    @Override public ScenePayload.Entry sceneTemplate(int x,int z,String asset){for(var value:sceneIndex(x,z).values())if(value.object().asset().equals(asset))return value;return null;}
    @Override public SceneSource sceneSource(int x,int z){return scenes(x,z).sceneSource();}
    @Override public boolean rawSceneContainer(int x,int z){return scenes(x,z).rawSceneContainer();}
    @Override public SceneHome sceneHome(UUID id){return catalog==null?null:catalog.sceneHome(id);}
    @Override public SceneHome nativeSceneHome(UUID id){return catalog==null?null:catalog.nativeSceneHome(id);}
    @Override public Set<Long> nativeSceneHomes(UUID id){return catalog==null?Set.of():catalog.nativeSceneHomes(id);}
    @Override public boolean sceneShared(UUID id){return catalog!=null&&catalog.sceneShared(id);}
    @Override public Map<UUID,SceneCullingVolume> sceneCullingVolumes(){return catalog==null?Map.of():catalog.sceneCullingVolumes();}
    @Override public Map<UUID,Set<Long>> sceneCullingReferences(){return catalog==null?Map.of():catalog.sceneCullingReferences();}
    @Override public Set<Long> sceneCullingUnreviewedReferences(){return catalog==null?Set.of():catalog.sceneCullingUnreviewedReferences();}
    @Override public ScenePayload.Entry globalSceneTemplate(String asset){return catalog==null?null:catalog.globalSceneTemplate(asset);}
    @Override public boolean globalScenes(){return catalog!=null&&catalog.globalScenes();}
    @Override public boolean visibleScenesFirst(){return true;}
    @Override public SceneHome rememberedSceneHome(UUID id){return catalog==null?null:catalog.rememberedSceneHome(id);}
    @Override public Map<UUID,NativePortal> portalIndex(int x,int z){return catalog==null?Map.of():catalog.portalIndex(x,z);}
    @Override public boolean portalIndexReady(int x,int z){return catalog==null||catalog.portalIndexReady(x,z);}
    @Override public long portalOrder(UUID id){return catalog==null?Long.MAX_VALUE:catalog.portalOrder(id);}
    @Override public NativePortal basePortal(UUID id){return catalog==null?null:catalog.basePortal(id);}
    @Override public NativePortal portalTemplate(String asset){return catalog==null?null:catalog.portalTemplate(asset);}
    @Override public boolean sceneAssetKnown(String asset){return catalog!=null&&catalog.sceneAssetKnown(asset);}
    @Override public String sceneBehaviour(String asset){return catalog==null?"":catalog.sceneBehaviour(asset);}
    @Override public ResidentMapReads<C> residentReads(){return reads;}
    @Override public NativeCommitIndex nativeBase(){return catalog==null?NativeCommitIndex.empty():catalog.nativeBase();}
    private ResidentMapReads.Chunk<C> chunk(int x,int z){return reads.require(new ResidentChunks.Key(x,z));}
    @Override public EditChunk editChunk(int x,int z){return chunk(x,z).data();}
    @Override public int voxel(int x,int y,int z){if(y<0||y>255)throw new IllegalArgumentException("invalid_block");C data=chunk(Math.floorDiv(x,16),Math.floorDiv(z,16)).data();return data.voxel((y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16));}
    @Override public TilePayload.Tile tile(int x,int y,int z){if(y<0||y>255)throw new IllegalArgumentException("invalid_block");C data=chunk(Math.floorDiv(x,16),Math.floorDiv(z,16)).data();return data.tile((y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16));}
    @Override public Map<Integer,TileCell> tileIndex(int x,int z){return chunk(x,z).data().tiles();}
    @Override public boolean ready(int x,int z){try{chunk(Math.floorDiv(x,16),Math.floorDiv(z,16));return true;}catch(IllegalArgumentException unavailable){if(!"terrain_not_ready_retry".equals(unavailable.getMessage())&&!"resident_map_stopped".equals(unavailable.getMessage()))throw unavailable;return false;}}
    @Override public boolean solid(int x,int y,int z){if(y<0)return true;if(y>255)return false;try{int id=voxel(x,y,z)>>>4;return id!=0&&id!=8&&id!=9&&id!=10&&id!=11;}catch(IllegalArgumentException unavailable){if(!"terrain_not_ready_retry".equals(unavailable.getMessage())&&!"resident_map_stopped".equals(unavailable.getMessage()))throw unavailable;return true;}}
}
