package szserver.world;

import com.github.luben.zstd.Zstd;
import szserver.util.Workers;
import szserver.net.Wire;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/** Checked SZR1 voxel/tile/scene source with a published native cache base.
 * IO, decoding and replacement-cache allocation run off both gameplay owners. */
public final class RegionTerrain implements Terrain {
    private record Key(int x,int z) {}
    private final Path root;
    private final String bindingNamespace;
    private final String worldIdentity;
    private volatile NativeCommitIndex archiveCommits=NativeCommitIndex.empty();
    /** Detached exports keep their own captured durable native root. */
    public void archiveCommits(NativeCommitIndex committed){archiveCommits=Objects.requireNonNull(committed);}
    private volatile boolean closed;
    private final ThreadPoolExecutor loader=Workers.pool("terrain-loader",1,64);
    private record Chunk(short[] blocks,byte[] metadata,Map<Integer,TileCell> tiles,Map<UUID,ScenePayload.Entry> scenes,Map<UUID,NativePortal> portals,boolean rawContainer,SceneSource source,Map<String,ScenePayload.Entry> templates,List<TilePayload.Tile> completeTiles) {
        Chunk(short[] blocks,byte[] metadata,Map<Integer,TileCell> tiles,Map<UUID,ScenePayload.Entry> scenes,Map<UUID,NativePortal> portals,boolean rawContainer,SceneSource source){this(blocks,metadata,tiles,scenes,portals,rawContainer,source,templates(scenes),tiles.values().stream().map(TileCell::tile).filter(t->t.completeStateSize()>0).toList());}
        private static Map<String,ScenePayload.Entry> templates(Map<UUID,ScenePayload.Entry> scenes){Map<String,ScenePayload.Entry> result=new HashMap<>();scenes.values().stream().sorted(Comparator.comparing(e->e.object().identity())).forEach(e->{for(var o:e.occurrences())result.putIfAbsent(o.object().asset(),new ScenePayload.Entry(o.container(),o.object()));});return Map.copyOf(result);}
    }
    private record Cache(NativeCommitIndex committed,ConcurrentHashMap<Key,Chunk> chunks,ArrayDeque<Key> order,NativeSceneCatalog scenes){}
    private volatile Cache cache=new Cache(NativeCommitIndex.empty(),new ConcurrentHashMap<>(),new ArrayDeque<>(),null);
    private final ConcurrentHashMap<UUID,SceneHome> rememberedScenes=new ConcurrentHashMap<>();
    private final Set<Key> loading=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Key,Long> retryAfter=new ConcurrentHashMap<>();
    public final class LivePublication {
        private final Cache before,after;private LivePublication(Cache before,Cache after){this.before=before;this.after=after;}
        public void checkCurrent(){if(cache!=before)throw new IllegalArgumentException("native_base_cache_stale");}
        public void publish(){checkCurrent();cache=after;}
        public NativePortal portal(UUID id){return after.scenes==null?templates==null?null:templates.basePortal(id):after.scenes.portal(id);}
    }
    public NativeCommitIndex liveCommits(){return cache.committed();}
    @Override public NativeCommitIndex nativeBase(){return liveCommits();}
    public CompletableFuture<LivePublication> prepareLive(NativeCommitIndex next){
        var result=new CompletableFuture<LivePublication>();try{loader.execute(()->{try{
            Cache before=cache;ConcurrentHashMap<Key,Chunk> ready=new ConcurrentHashMap<>(before.chunks());ArrayDeque<Key> order=new ArrayDeque<>(before.order());
            for(Key key:ready.keySet())if(before.committed().revision(key.x,key.z)!=next.revision(key.x,key.z))ready.put(key,load(key.x,key.z,next));
            NativeSceneCatalog metadata=next.generation().isEmpty()||scenes==null?null:NativeSceneCatalog.prepare(before.scenes,catalog,templates,next,this::originalSceneEntries,scenes.target());
            result.complete(new LivePublication(before,new Cache(next,ready,order,metadata)));
        }catch(Throwable error){result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    public String checkpointBindingFor(NativeCommitIndex committed)throws IOException{
        return checkpointBindingFor(committed,true);
    }
    public String checkpointBindingFor(NativeCommitIndex committed,boolean sceneBase)throws IOException{
        if(checkpointBase==null)return null;if(!checkpointSourceMetadata.equals(CheckpointSources.regions(root,scenes,templateRoot,templateRegistry,checkpointFallback,false,bindingNamespace)))throw new IOException("checkpoint_base_changed_since_start");String original=checkpointBase;
        return committed.generation().isEmpty()?original:CheckpointSources.textDigest((sceneBase?"szwc-native-live-v2:":"szwc-native-live-v1:")+original+":"+committed.generation());
    }
    /** Constant-work identity for an immutable captured root; the checkpoint IO worker
     * validates original source identities before and after writing this root. */
    public String capturedCheckpointBinding(NativeCommitIndex committed){return checkpointBase==null?null:committed.generation().isEmpty()?checkpointBase:CheckpointSources.textDigest("szwc-native-live-v2:"+checkpointBase+":"+committed.generation());}
    private final AtomicLong errors=new AtomicLong();
    private volatile String lastError="";
    private final int capacity;
    private final FlatChunk flatFallback;
    private final SceneRegistry scenes;
    private final SceneRegistry manifestScenes;
    private final SceneCatalog catalog;
    private final SceneCatalog templates;
    private final Path templateRoot;
    private final SceneRegistry templateRegistry;
    private final Integer checkpointFallback;
    private final String checkpointBase;
    private final String checkpointSourceMetadata;
    public record CatalogSource(Path path,SceneRegistry registry){}
    public Path sceneResources(){return manifestScenes==null?null:manifestScenes.resources();}
    private final AtomicLong fallbackLoads=new AtomicLong();
    private static final class MissingChunk extends IOException {MissingChunk(int x,int z){super("missing_chunk:"+x+","+z);}}
    public RegionTerrain(Path path,int capacity) throws IOException {
        this(path,capacity,null);
    }
    public RegionTerrain(Path path,int capacity,Integer fallbackSurface) throws IOException {
        this(path,capacity,fallbackSurface,null);
    }
    public RegionTerrain(Path path,int capacity,Integer fallbackSurface,SceneRegistry scenes) throws IOException {
        this(path,capacity,fallbackSurface,scenes,null);
    }
    public RegionTerrain(Path path,int capacity,Integer fallbackSurface,SceneRegistry scenes,CatalogSource source) throws IOException {
        this(path,capacity,fallbackSurface,scenes,source,null);
    }
    public RegionTerrain(Path path,int capacity,Integer fallbackSurface,SceneRegistry scenes,CatalogSource source,String namespace) throws IOException {
        root=(Files.isDirectory(path.resolve("region"))?path.resolve("region"):path).toRealPath();
        boolean managed=namespace!=null&&namespace.matches("content-v2:[0-9a-f]{64}");
        bindingNamespace=managed?"managed-map-content-v2":namespace==null?root.toString():namespace;
        worldIdentity="regions:"+(managed?namespace:bindingNamespace);
        if(capacity<16 || capacity>4096) throw new IllegalArgumentException("terrain_cache_capacity");
        Path alternate=source==null?root:(Files.isDirectory(source.path().resolve("region"))?source.path().resolve("region"):source.path()).toRealPath();
        if(source!=null&&(scenes==null||source.registry()==null||!source.registry().target().digest().equals(scenes.target().digest())))throw new IllegalArgumentException("scene_template_target_registry_mismatch");
        this.capacity=capacity;
        this.scenes=scenes;
        manifestScenes=scenes==null?null:scenes.fork();
        flatFallback=fallbackSurface==null?null:new FlatChunk(fallbackSurface);
        templateRegistry=source==null||alternate.equals(root)?scenes:source.registry();checkpointFallback=fallbackSurface;
        String initialBinding,initialMetadata;try{initialMetadata=CheckpointSources.regions(root,scenes,alternate,templateRegistry,fallbackSurface,false,bindingNamespace);initialBinding=CheckpointSources.regions(root,scenes,alternate,templateRegistry,fallbackSurface,true,bindingNamespace);if(!initialMetadata.equals(CheckpointSources.regions(root,scenes,alternate,templateRegistry,fallbackSurface,false,bindingNamespace)))throw new IOException("checkpoint_source_changed_during_read");}catch(IOException unavailable){initialBinding=null;initialMetadata=null;}checkpointBase=initialBinding;checkpointSourceMetadata=initialMetadata;
        if(managed&&!worldIdentity.equals("regions:content-v2:"+checkpointBase))throw new IOException("portable_managed_map_content_changed");
        catalog=scenes==null?null:new SceneCatalog(root,scenes);
        templateRoot=alternate;
        templates=source==null||alternate.equals(root)?catalog:new SceneCatalog(alternate,source.registry());
    }
    @Override public boolean ready(int x,int z) { return chunk(Math.floorDiv(x,16),Math.floorDiv(z,16))!=null; }
    public String identity(){return worldIdentity;}
    NativeMapArchive.Source archiveSource(){return new NativeMapArchive.Source(root,scenes==null?null:scenes.fork(),flatFallback,archiveCommits);}
    /** Original content is hashed at startup; saves verify source file identities without re-reading the map. */
    public String nativeCommitBinding(){return scenes==null?null:checkpointBase;}
    /** Only terminal global-catalogue failures poison service health; local chunk faults can recover. */
    public boolean healthy(){return !closed&&(catalog==null||catalog.healthy())&&(templates==null||templates.healthy());}
    public boolean ready(){return healthy()&&(catalog==null||catalog.ready())&&(templates==null||templates.ready());}
    public SceneCollisionCatalog collisionCatalog(Path boxes,long bytes){return new SceneCollisionCatalog(root,scenes,boxes,bytes,this::liveCommits);}
    public SceneCollisionCatalog collisionCatalog(Path boxes,long bytes,ActorMotion motion){return new SceneCollisionCatalog(root,scenes,boxes,bytes,this::liveCommits,motion);}
    public Map<String,SceneRegistry.Definition> collisionDefinitions(){return scenes==null?Map.of():scenes.target().names();}
    @Override public String checkpointBinding()throws IOException{NativeTileStates.worker();return checkpointBindingFor(cache.committed());}
    /** Dedicated catalogue worker only. Missing chunks are absent, corruption is an explicit error. */
    public synchronized UUID manifestRevision(int x,int z)throws IOException{
        if(Thread.currentThread().getName().equals("native-game-owner")||Thread.currentThread().getName().equals("world-tick"))throw new IllegalStateException("chunk_manifest_io_on_owner");
        var nativeChunk=cache.committed().readChunk(x,z);if(nativeChunk!=null){ByteBuffer h=ByteBuffer.wrap(nativeChunk.record());return new UUID(h.getLong(17),h.getLong(25));}
        int rx=Math.floorDiv(x,32),rz=Math.floorDiv(z,32);byte[] record;
        try(FileChannel channel=FileChannel.open(root.resolve("r."+rx+"."+rz+".szr"),StandardOpenOption.READ)){
            regionHeader(channel,rx,rz);record=regionRecord(channel,Math.floorMod(z,32)*32+Math.floorMod(x,32));
        }catch(NoSuchFileException absent){record=null;}
        if(record==null){if(flatFallback==null)return null;ByteBuffer fallback=ByteBuffer.wrap(flatFallback.record(x,z));return new UUID(fallback.getLong(17),fallback.getLong(25));}
        ByteBuffer header=ByteBuffer.wrap(record);if(header.getInt()!=x||header.getInt()!=z||header.get()!=1)throw new IOException("invalid_terrain_record");
        long high=header.getLong(17),low=header.getLong(25);if(manifestScenes==null)return new UUID(high,low);
        try{byte[] projected=manifestScenes.project(record,capturedSceneChannels(record)).encode();return sceneRevision(high,low,manifestScenes.target().digest(),projected);}catch(Exception error){throw new IOException("scene_manifest_projection_failed:"+error.getMessage(),error);}
    }
    private static UUID sceneRevision(long high,long low,String registry,byte[] side){return UUID.nameUUIDFromBytes(Wire.bytes(out->{out.writeLong(high);out.writeLong(low);out.writeUTF(registry);out.write(side);}));}
    /** Cached native terrain record, prepared off both transport and simulation owners.
     * Scene objects require their separate registry/framing projection and are not substituted. */
    public byte[] nativeTerrainRecord(int x,int z){Chunk found=chunk(x,z);return found==null?null:found.source.record();}
    public record NativeChunkSnapshot(byte[] record,List<TilePayload.Tile> completeTiles,long baseRevision){
        public NativeChunkSnapshot(byte[] record,List<TilePayload.Tile> completeTiles){this(record,completeTiles,0);}
    }
    /** One cached generation carries compact geometry and its complete tile compounds together. */
    public NativeChunkSnapshot nativeChunkSnapshot(int x,int z){Cache view=cache;Chunk found=chunk(x,z);return found==null||view!=cache?null:new NativeChunkSnapshot(found.source.record(),found.completeTiles(),view.committed().revision(x,z));}
    private Chunk chunk(int x,int z) {
        Cache view=cache;Key key=new Key(x,z); Chunk found=view.chunks().get(key);
        if(found!=null)return found;
        if(System.nanoTime()<retryAfter.getOrDefault(key,0L))return null;
        if(loading.add(key)) {
            try { loader.execute(() -> {
                try {
                    Chunk values=load(x,z,view.committed());if(cache!=view)return;
                    if(templates!=catalog){int added=0;for(UUID id:values.scenes.keySet())if(!rememberedScenes.containsKey(id))added++;if((long)rememberedScenes.size()+added>SceneCatalog.MAX_IDENTITIES)throw new IOException("scene_remembered_identity_limit");SceneHome home=new SceneHome(x,z);for(UUID id:values.scenes.keySet())rememberedScenes.put(id,home);}
                    while(view.chunks().size()>=capacity)view.chunks().remove(view.order().removeFirst());
                    view.chunks().put(key,values); view.order().addLast(key); retryAfter.remove(key);
                } catch(Exception e) {
                    errors.incrementAndGet();lastError=e.getMessage();
                    if(retryAfter.size()>=capacity)retryAfter.clear();
                    retryAfter.put(key,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                } finally { loading.remove(key); }
            }); } catch(RejectedExecutionException e) {loading.remove(key);}
        }
        return null;
    }
    @Override public boolean solid(int x,int y,int z) {
        if(y<0)return true;
        if(y>=256)return false;
        Chunk found=chunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
        short[] values=found==null?null:found.blocks;
        if(values==null)return true;
        int id=Short.toUnsignedInt(values[(y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16)]);
        return id!=0 && id!=8 && id!=9 && id!=10 && id!=11;
    }
    @Override public int voxel(int x,int y,int z){
        if(y<0||y>255)throw new IllegalArgumentException("invalid_block");
        Chunk found=chunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
        if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");
        int cell=(y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);
        return (Short.toUnsignedInt(found.blocks[cell])<<4)|(found.metadata[cell]&15);
    }
    @Override public TilePayload.Tile tile(int x,int y,int z){
        if(y<0||y>255)throw new IllegalArgumentException("invalid_block");Chunk found=chunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
        if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");TileCell value=found.tiles.get((y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16));return value==null?null:value.tile();
    }
    @Override public Map<Integer,TileCell> tileIndex(int x,int z){Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");return found.tiles;}
    @Override public EditChunk editChunk(int x,int z){
        Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");
        return new EditChunk(){
            private void bounds(int cell){if(cell<0||cell>65535)throw new IllegalArgumentException("invalid_block");}
            public int voxel(int cell){bounds(cell);return (Short.toUnsignedInt(found.blocks[cell])<<4)|(found.metadata[cell]&15);}
            public TilePayload.Tile tile(int cell){bounds(cell);TileCell value=found.tiles.get(cell);return value==null?null:value.tile();}
            public Map<Integer,TileCell> tiles(){return found.tiles;}
        };
    }
    @Override public Map<UUID,ScenePayload.Entry> sceneIndex(int x,int z){if(scenes==null)throw new IllegalArgumentException("scene_edit_disabled");Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");return found.scenes;}
    @Override public ScenePayload.Entry sceneTemplate(int x,int z,String asset){Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");return found.templates.get(asset);}
    @Override public SceneHome sceneHome(UUID identity){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null&&nativeScenes.changedIdentity(identity)?nativeScenes.home(identity):templates==null?null:templates.home(identity);}
    @Override public boolean sceneShared(UUID identity){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes==null?catalog!=null&&catalog.shared(identity):nativeScenes.shared(identity);}
    @Override public Set<Long> nativeSceneHomes(UUID identity){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.homes(identity):catalog==null?Set.of():catalog.nativeHomes(identity);}
    @Override public Map<UUID,SceneCullingVolume> sceneCullingVolumes(){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.volumes():catalog==null?Map.of():catalog.cullingVolumes();}
    @Override public Map<UUID,Set<Long>> sceneCullingReferences(){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.references():catalog==null?Map.of():catalog.cullingReferences();}
    @Override public Set<Long> sceneCullingUnreviewedReferences(){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.unreviewed():catalog==null?Set.of():catalog.cullingUnreviewedReferences();}
    @Override public ScenePayload.Entry globalSceneTemplate(String asset){return templates==null?null:templates.template(asset);}
    @Override public boolean globalScenes(){return catalog!=null;}
    @Override public boolean visibleScenesFirst(){return templates!=catalog;}
    @Override public SceneHome nativeSceneHome(UUID id){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.nativeHome(id):catalog==null?null:catalog.nativeHome(id);}
    @Override public SceneHome rememberedSceneHome(UUID id){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null&&nativeScenes.changedIdentity(id)?nativeScenes.nativeHome(id):rememberedScenes.get(id);}
    @Override public Map<UUID,ScenePayload.Entry> cachedSceneIndex(int x,int z){Chunk found=cache.chunks().get(new Key(x,z));return found==null?Map.of():found.scenes;}
    @Override public boolean rawSceneContainer(int x,int z){Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");return found.rawContainer;}
    @Override public String sceneRegistryDigest(){return scenes==null?"":scenes.target().digest();}
    @Override public String sceneBehaviour(String asset){SceneRegistry.Definition found=scenes==null?null:scenes.target().names().get(asset);return found==null?"":found.behaviour();}
    @Override public SceneRegistry.Definition sceneDefinition(String asset){return scenes==null?null:scenes.target().names().get(asset);}
    @Override public Map<UUID,NativePortal> portalIndex(int x,int z){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.portals(x,z):templates==null?Map.of():templates.portals(x,z);}
    @Override public boolean portalIndexReady(int x,int z){return templates==null||templates.portalReady();}
    @Override public long portalOrder(UUID id){return templates==null?Long.MAX_VALUE:templates.portalOrder(id);}
    @Override public NativePortal basePortal(UUID id){NativeSceneCatalog nativeScenes=cache.scenes;return nativeScenes!=null?nativeScenes.portal(id):templates==null?null:templates.basePortal(id);}
    @Override public NativePortal portalTemplate(String asset){return templates==null?null:templates.portalTemplate(asset);}
    @Override public boolean sceneAssetKnown(String asset){return scenes!=null&&scenes.target().names().containsKey(asset);}
    @Override public SceneSource sceneSource(int x,int z){Chunk found=chunk(x,z);if(found==null)throw new IllegalArgumentException("terrain_not_ready_retry");return found.source;}
    private static ByteBuffer read(FileChannel f,long offset,int size)throws IOException {
        ByteBuffer b=ByteBuffer.allocate(size);
        while(b.hasRemaining()){if(f.read(b,offset+b.position())<0)throw new EOFException("truncated_region");}
        return b.flip();
    }
    static void regionHeader(FileChannel f,int rx,int rz)throws IOException{
        ByteBuffer h=read(f,0,16);if(h.getInt()!=0x535A5231||h.getInt()!=rx||h.getInt()!=rz||f.size()<16400)throw new IOException("invalid_region_header");
    }
    static byte[] regionRecord(FileChannel f,int index)throws IOException{
        if(index<0||index>=1024)throw new IOException("invalid_region_chunk_index");
        ByteBuffer entry=read(f,16L+index*16,16);long offset=entry.getLong();int size=entry.getInt(),checksum=entry.getInt();if(offset==0&&size==0&&checksum==0)return null;
        long fileSize=f.size();
        if(offset<16400||size<41||size>ScenePayload.MAX_RECORD||offset>fileSize||size>fileSize-offset)throw new IOException("missing_or_invalid_chunk");byte[] record=new byte[size];read(f,offset,size).get(record);CRC32 crc=new CRC32();crc.update(record);if((int)crc.getValue()!=checksum)throw new IOException("region_crc_mismatch");return record;
    }
    /** Locate old extra header bytes without decompressing the voxel arrays for catalog work. */
    static ScenePayload.Channels capturedSceneChannels(byte[] record)throws IOException{
        ByteBuffer in=ByteBuffer.wrap(record);int x=in.getInt(),z=in.getInt(),terrain=in.get()&255;if(terrain==0)return ScenePayload.parseChannels(record,9,x,z);if(terrain!=1)throw new IOException("invalid_terrain_record");
        int mask=in.getInt(),add=in.getInt();if((mask&~65535)!=0||(add&~mask)!=0)throw new IOException("invalid_terrain_mask");
        for(int extra=0;extra<4;extra++){int at=33+extra;if(at+12>record.length)continue;int raw=in.getInt(at),size=in.getInt(at+4);if(raw>=0&&raw<=2*1024*1024&&size>=4&&(long)at+8+size<=record.length&&in.getInt(at+8)==0x28B52FFD)return ScenePayload.parseChannels(record,at+8+size,x,z);}throw new IOException("terrain_zstd_not_found");
    }
    /** Canonical original geometry for sparse metadata comparisons; worker only, no cache warming. */
    Map<UUID,ScenePayload.Entry> originalSceneEntries(int x,int z)throws Exception{
        SceneCollision.worker();byte[] record=originalRecord(x,z);if(record==null)return Map.of();return ScenePayload.index(scenes.project(record,capturedSceneChannels(record)),scenes.target());
    }
    private byte[] originalRecord(int x,int z)throws IOException{
        try(FileChannel file=FileChannel.open(root.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".szr"),StandardOpenOption.READ)){regionHeader(file,Math.floorDiv(x,32),Math.floorDiv(z,32));return regionRecord(file,Math.floorMod(z,32)*32+Math.floorMod(x,32));}catch(NoSuchFileException absent){return null;}
    }
    private Chunk load(int x,int z,NativeCommitIndex committed)throws IOException {
        var nativeChunk=committed.readChunk(x,z);if(nativeChunk!=null)return decodeRecord(nativeChunk.record(),x,z,nativeChunk.completeTiles());
        try{return loadCaptured(x,z);}catch(NoSuchFileException|MissingChunk missing){if(flatFallback==null)throw missing;fallbackLoads.incrementAndGet();byte[] geometry=flatFallback.record(x,z);return new Chunk(flatFallback.blocks,new byte[65536],Map.of(),Map.of(),Map.of(),true,new SceneSource(geometry));}
    }
    private Chunk loadCaptured(int x,int z)throws IOException {
        int rx=Math.floorDiv(x,32),rz=Math.floorDiv(z,32);
        Path path=root.resolve("r."+rx+"."+rz+".szr");
        byte[] record;
        try(FileChannel f=FileChannel.open(path,StandardOpenOption.READ)){
            regionHeader(f,rx,rz);
            int index=Math.floorMod(z,32)*32+Math.floorMod(x,32);
            record=regionRecord(f,index);if(record==null)throw new MissingChunk(x,z);
        }
        return decodeRecord(record,x,z,null);
    }
    private Chunk decodeRecord(byte[] record,int x,int z,List<TilePayload.Tile> complete)throws IOException{
        ByteBuffer b=ByteBuffer.wrap(record);
        if(b.getInt()!=x || b.getInt()!=z || b.get()!=1)throw new IOException("invalid_terrain_record");
        int mask=b.getInt(),add=b.getInt();
        if((mask&~65535)!=0 || (add&~mask)!=0)throw new IOException("invalid_terrain_mask");
        int rawSize=-1,compressedSize=-1,start=-1;
        for(int extra=0;extra<4;extra++) {
            int candidate=33+extra;
            if(candidate+12>record.length)continue;
            int raw=b.getInt(candidate),size=b.getInt(candidate+4);
            if(raw>=0 && raw<=2*1024*1024 && size>=4 && (long)candidate+8+size<=record.length
                && b.getInt(candidate+8)==0x28B52FFD){rawSize=raw;compressedSize=size;start=candidate+8;break;}
        }
        if(start<0)throw new IOException("terrain_zstd_not_found");
        byte[] compressed=Arrays.copyOfRange(record,start,start+compressedSize);
        long size=Zstd.decompressedSize(compressed);
        if(size<=0 || size>2*1024*1024)throw new IOException("terrain_decompression_limit");
        byte[] raw=Zstd.decompress(compressed,(int)size);
        int count=Integer.bitCount(mask),required=count*12288+Integer.bitCount(add)*2048+256;
        if(rawSize<required || rawSize>raw.length)throw new IOException("terrain_arrays_truncated");
        short[] blocks=new short[65536];byte[] metadata=new byte[65536];int lowOffset=0,highOffset=count*12288;
        for(int section=0;section<16;section++){
            if((mask&(1<<section))==0)continue;
            boolean high=(add&(1<<section))!=0;
            for(int i=0;i<4096;i++){
                int hi=high?((raw[highOffset+(i>>1)]>>((i&1)*4))&15):0;
                metadata[section*4096+i]=(byte)((raw[count*4096+(lowOffset/4096)*2048+(i>>1)]>>((i&1)*4))&15);
                blocks[section*4096+i]=(short)((raw[lowOffset+i]&255)|(hi<<8));
            }
            lowOffset+=4096;if(high)highOffset+=2048;
        }
        // All framing/registry work stays on this loader, including scene serializer migrations.
        List<TilePayload.Tile> decodedTiles=TilePayload.decode(raw,rawSize,x,z);if(complete==null)decodedTiles=NativeTileStates.attach(root,x,z,decodedTiles);else{Map<Integer,TilePayload.Tile> full=new HashMap<>();for(var tile:complete)full.put(tile.cell(),tile);List<TilePayload.Tile> merged=new ArrayList<>();for(var tile:decodedTiles)merged.add(full.getOrDefault(tile.cell(),tile));decodedTiles=List.copyOf(merged);}Map<Integer,TileCell> tileIndex=new HashMap<>();for(TilePayload.Tile tile:decodedTiles)tileIndex.put(tile.cell(),new TileCell(tile,Short.toUnsignedInt(blocks[tile.cell()])));
        byte[] tiles=TilePayload.encode(decodedTiles);
        byte[] geometryRaw=new byte[required+tiles.length];System.arraycopy(raw,0,geometryRaw,0,required);System.arraycopy(tiles,0,geometryRaw,required,tiles.length);
        byte[] geometryZstd=Zstd.compress(geometryRaw,3);final int arraysSize=required;
        long revisionHigh=b.getLong(17),revisionLow=b.getLong(25);
        byte[] sideChannels=scenes==null?Wire.bytes(out->{out.writeByte(0);out.write(new byte[]{1,1,0,0,0,0});out.writeInt(x);out.writeInt(z);out.writeInt(0);}):complete!=null?ScenePayload.parseChannels(record,start+compressedSize,x,z).encode():projectScenes(record,start+compressedSize,x,z);
        UUID revision=scenes==null||complete!=null?new UUID(revisionHigh,revisionLow):sceneRevision(revisionHigh,revisionLow,scenes.target().digest(),sideChannels);
        byte[] geometry=Wire.bytes(out->{out.writeInt(x);out.writeInt(z);out.writeBoolean(true);out.writeInt(mask);out.writeInt(add);out.writeLong(revision.getMostSignificantBits());out.writeLong(revision.getLeastSignificantBits());out.writeInt(arraysSize);out.writeInt(geometryZstd.length);out.write(geometryZstd);out.write(sideChannels);});
        try{ScenePayload.Channels channels=ScenePayload.parse(geometry).channels();Map<UUID,ScenePayload.Entry> index=scenes==null?Map.of():ScenePayload.index(channels,scenes.target());Map<UUID,NativePortal> portals=new HashMap<>();for(ScenePayload.Entry entry:index.values()){NativePortal portal=NativePortal.from(entry,this::sceneBehaviour);if(portal!=null)portals.put(portal.id(),portal);}return new Chunk(blocks,metadata,Map.copyOf(tileIndex),index,Map.copyOf(portals),channels.raw(),new SceneSource(geometry));}catch(Exception error){throw new IOException("scene_index_failed:"+error.getMessage(),error);}
    }
    private byte[] projectScenes(byte[] record,int terrainEnd,int x,int z)throws IOException{
        try{return scenes.project(record,ScenePayload.parseChannels(record,terrainEnd,x,z)).encode();}catch(Exception error){throw new IOException("scene_projection_failed:"+error.getMessage(),error);}
    }
    public Map<String,Object> sceneMetrics(){if(scenes==null)return Map.of("enabled",false);Map<String,Object> result=new HashMap<>(scenes.metrics());result.put("catalog",templates.metrics());if(cache.scenes!=null)result.put("nativeCatalog",cache.scenes.metrics());if(templates!=catalog){result.put("geometryCatalog",catalog.metrics());result.put("templateRoot",templateRoot.toString());}return Map.copyOf(result);}
    public Map<String,Object> metrics(){return Map.of("loadedChunks",cache.chunks().size(),"capacity",capacity,"loading",loading.size(),"errors",errors.get(),"lastError",lastError,"flatFallback",flatFallback!=null,"fallbackLoads",fallbackLoads.get(),"rememberedSceneIdentities",rememberedScenes.size());}
    @Override public void close()throws InterruptedException{closed=true;try{if(templates!=catalog&&templates!=null)templates.close();}finally{try{if(catalog!=null)catalog.close();}finally{Workers.stop(loader);}}}
}
