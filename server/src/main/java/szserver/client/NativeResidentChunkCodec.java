package szserver.client;

import com.github.luben.zstd.Zstd;
import szserver.net.Wire;
import szserver.world.*;
import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** Writable canonical chunk/section objects; serialization is detached from native owner state. */
public final class NativeResidentChunkCodec implements ResidentChunks.Codec<NativeResidentChunkCodec.Chunk,NativeResidentChunkCodec.Snapshot> {
    private static final int MAGIC=0x535a4e43,VERSION=2,MAX_RECORD=4*1024*1024;
    private volatile SceneTypeIds.Catalog persistenceCatalog;
    private volatile String persistenceRegistry;
    /** Bootstrap/loader setup only; the immutable catalog was prepared with the registry. */
    public synchronized void persistenceRegistry(SceneRegistry.Manifest registry){
        Objects.requireNonNull(registry);
        if(persistenceRegistry!=null&&!persistenceRegistry.equals(registry.digest()))throw new IllegalStateException("resident_persistence_registry_changed");
        persistenceCatalog=registry.persistent();persistenceRegistry=registry.digest();
    }
    private static final Map<String,String> HASHES=Map.of(
        "812508934","58b779c77e074b560e7f16d515dba7fb8ec8298ef5ebc227a62274d851650251",
        "368005700","ffe5f264eb298e607488fc831ca8381f117c78dbbd75f4b355acef79cace33b1",
        "1425466732","1cf9c8b8ef2a812ed945772ae7edad25cf19a90bcf19f0f6715e161d5e0b679a");
    private final Object world;private final Constructor<?> chunkConstructor,sectionConstructor,nibbleConstructor;
    private final Field sections,low,high,biomes,extraLight,sectionCount;private final Field[] groups;
    private final Method nibbleBytes,recount;
    private final Constructor<?>[] tileConstructors;private final Constructor<?> compoundConstructor;
    private final Method tileCompactRead,tilePersistentRead,tilePersistentWrite,readNbt,writeNbt,attachTile,detachTile,getTile,tileAllowed,tileFactory;
    private final Field tileWorld;private final Field[] tilePosition;
    private final MethodHandle getId,getMetadata,setId,setMetadata;
    private final MethodHandle worldId,worldMetadata;private final Method worldTile;
    private final Object[] blocks;
    private final Map<ResidentChunks.Key,Chunk> active=new HashMap<>();
    private Thread owner;

    NativeResidentChunkCodec(Path runtime,ClassLoader loader,Class<?> host,Object world)throws Exception {
        this.world=Objects.requireNonNull(world);
        for(var entry:HASHES.entrySet()){
            byte[] bytes=RuntimeFiles.read(runtime.resolve("classes").resolve(entry.getKey()+".class"));
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(entry.getValue()))throw new IllegalStateException("resident_native_release_changed:"+entry.getKey());
        }
        Class<?> chunk=Class.forName("812508934",true,loader),section=Class.forName("368005700",true,loader),nibble=Class.forName("1425466732",true,loader),worldType=Class.forName("1500514786",true,loader);
        chunkConstructor=chunk.getConstructor(worldType,int.class,int.class);sectionConstructor=section.getConstructor(int.class);nibbleConstructor=nibble.getConstructor(byte[].class,int.class);
        sections=chunk.getField("extends");biomes=chunk.getField("native");low=section.getField("boolean");high=section.getField("final");
        extraLight=section.getField("throw");sectionCount=section.getField("goto");
        groups=new Field[]{section.getField("instanceof"),section.getField("public"),section.getField("static"),section.getField("void")};
        nibbleBytes=nibble.getMethod("synchronized");recount=section.getMethod("synchronized");
        blocks=(Object[])Class.forName("1959919792",true,loader).getField("switch").get(null);
        MethodHandles.Lookup lookup=MethodHandles.publicLookup();
        getId=lookup.unreflect(chunk.getMethod("default",int.class,int.class,int.class)).asType(MethodType.methodType(int.class,Object.class,int.class,int.class,int.class));
        getMetadata=lookup.unreflect(chunk.getMethod("float",int.class,int.class,int.class)).asType(MethodType.methodType(int.class,Object.class,int.class,int.class,int.class));
        setId=lookup.unreflect(section.getMethod("class",int.class,int.class,int.class,int.class)).asType(MethodType.methodType(void.class,Object.class,int.class,int.class,int.class,int.class));
        setMetadata=lookup.unreflect(section.getMethod("const",int.class,int.class,int.class,int.class)).asType(MethodType.methodType(void.class,Object.class,int.class,int.class,int.class,int.class));
        worldId=lookup.unreflect(host.getMethod("strictfp",int.class,int.class,int.class)).asType(MethodType.methodType(int.class,Object.class,int.class,int.class,int.class));
        worldMetadata=lookup.unreflect(host.getMethod("interface",int.class,int.class,int.class)).asType(MethodType.methodType(int.class,Object.class,int.class,int.class,int.class));worldTile=host.getMethod("abstract",int.class,int.class,int.class);
        Class<?> tile=Class.forName("1123841866",true,loader),carpenter=Class.forName("1218866699",true,loader),builder=Class.forName("132153080",true,loader),compound=Class.forName("98008865",true,loader);
        tileConstructors=new Constructor<?>[]{carpenter.getConstructor(),builder.getConstructor()};compoundConstructor=compound.getConstructor();
        // The dedicated server has no client mod entrypoint to register these
        // persistent names. Use the native registry API and reject conflicts.
        Method register=tile.getMethod("interface",Class.class,String.class);
        Map<?,?> names=(Map<?,?>)tile.getField("private").get(null),types=(Map<?,?>)tile.getField("break").get(null);
        for(var mapping:Map.of(carpenter,"TileEntityCarpentersBlock",builder,"TileEntityCarpentersProBuilder").entrySet()){
            Object oldName=names.get(mapping.getKey()),oldType=types.get(mapping.getValue());
            if(oldName!=null&&!oldName.equals(mapping.getValue())||oldType!=null&&oldType!=mapping.getKey())throw new IllegalStateException("resident_native_tile_registry_conflict");
            if(oldName==null)register.invoke(null,mapping.getKey(),mapping.getValue());
        }
        tileCompactRead=carpenter.getMethod("volatile",DataInput.class);tilePersistentRead=tile.getMethod("volatile",compound);tilePersistentWrite=tile.getMethod("static",compound);
        readNbt=Class.forName("724465038",true,loader).getMethod("native",DataInput.class);writeNbt=Class.forName("1464336358",true,loader).getMethod("break",compound);
        attachTile=chunk.getMethod("implements",int.class,int.class,int.class,tile);detachTile=chunk.getMethod("synchronized",int.class,int.class,int.class);
        getTile=chunk.getMethod("case",int.class,int.class,int.class);Class<?> blockBase=Class.forName("1959919792",true,loader);tileAllowed=blockBase.getMethod("finally",int.class);tileFactory=blockBase.getMethod("native",worldType,int.class);
        tileWorld=tile.getField("switch");tilePosition=new Field[]{tile.getField("else"),tile.getField("float"),tile.getField("super")};
        host.getField("ids").set(world,(ToIntFunction<int[]>)p->p[1]<0||p[1]>255?0:worldVoxel(p[0],p[1],p[2])>>>4);
        host.getField("metas").set(world,(ToIntFunction<int[]>)p->p[1]<0||p[1]>255?0:worldVoxel(p[0],p[1],p[2])&15);
        host.getField("tiles").set(world,(Function<int[],Object>)p->{owner();if(p[1]<0||p[1]>255)return null;return resident(p[0],p[2]).nativeTile((p[1]<<8)|(Math.floorMod(p[2],16)<<4)|Math.floorMod(p[0],16));});
        host.getField("resident").set(world,(ToIntFunction<int[]>)p->{owner();return active.containsKey(new ResidentChunks.Key(p[0],p[1]))?1:0;});
        host.getField("chunks").set(world,(Function<int[],Object>)p->{owner();Chunk value=active.get(new ResidentChunks.Key(p[0],p[1]));if(value==null)throw new IllegalArgumentException("terrain_not_ready_retry");return value.nativeChunk;});
    }
    private void owner(){if(owner!=Thread.currentThread())throw new IllegalStateException("resident_native_wrong_owner");}
    private Chunk resident(int x,int z){Chunk result=active.get(new ResidentChunks.Key(Math.floorDiv(x,16),Math.floorDiv(z,16)));if(result==null)throw new IllegalArgumentException("terrain_not_ready_retry");return result;}
    public int worldVoxel(int x,int y,int z){owner();if(y<0||y>255)throw new IllegalArgumentException("resident_native_cell_bounds");return resident(x,z).voxel(Math.floorMod(x,16),y,Math.floorMod(z,16));}
    public ReadSnapshot readSnapshot(ResidentChunks.Key key){owner();Chunk chunk=active.get(key);if(chunk==null)throw new IllegalArgumentException("terrain_not_ready_retry");return chunk.readSnapshot();}
    /** The same entrypoints game block callbacks use, with JVM descriptor selection cached. */
    public int nativeWorldVoxel(int x,int y,int z){owner();if(y>=0&&y<=255)resident(x,z);try{return ((int)worldId.invokeExact(world,x,y,z)<<4)|(int)worldMetadata.invokeExact(world,x,y,z);}catch(Throwable failure){throw nativeFailure(failure);}}
    public TilePayload.Tile nativeWorldTile(int x,int y,int z)throws Exception {
        owner();if(y<0||y>255)return null;Chunk chunk=resident(x,z);int cell=(y<<8)|(Math.floorMod(z,16)<<4)|Math.floorMod(x,16);
        Object actual=worldTile.invoke(world,x,y,z);if(actual!=chunk.nativeTile(cell))throw new IllegalStateException("resident_native_world_tile_diverged");return chunk.nativeTileSnapshot(cell);
    }
    public final class Chunk {
        private final Object nativeChunk;private final Object[] nativeSections;
        private final int x,z;private final UUID sourceRevision;private byte[] sideChannels;private SceneData sceneData;
        private final Object sceneOrigin=new Object();private long sceneVersion;
        private final Map<Integer,TilePayload.Tile> tiles=new TreeMap<>();private boolean changed;
        private long completeTileBytes;private int compactTileBytes;
        private final Map<Integer,Object> nativeTiles=new HashMap<>();
        private Thread chunkOwner;
        private Transaction transaction;
        private ReadSnapshot readSnapshot;private int dirtyReadSections=65535;private boolean dirtyReadTiles=true;
        private Chunk(Object nativeChunk,int x,int z,UUID sourceRevision,byte[] sideChannels)throws Exception {
            this.nativeChunk=nativeChunk;this.nativeSections=(Object[])sections.get(nativeChunk);this.x=x;this.z=z;this.sourceRevision=sourceRevision;this.sideChannels=sideChannels;
        }
        public ResidentChunks.Key key(){return new ResidentChunks.Key(x,z);}
        /** Loader only, before a native chunk becomes visible to the world owner. */
        public void prepareSceneReads(SceneRegistry.Manifest registry){SceneCollision.worker();if(chunkOwner!=null)throw new IllegalStateException("resident_scene_index_after_activation");persistenceRegistry(registry);sceneData=new SceneData(key(),sideChannels,Objects.requireNonNull(registry));readSnapshot=null;dirtyReadSections=65535;dirtyReadTiles=true;readSnapshot();}
        public long sectionGrowth(int y,int packed){position(0,y,0);try{Object section=nativeSections[y>>4];return section==null?24576:(packed>>>4)>255&&high.get(section)==null?6144:0;}catch(IllegalAccessException failure){throw nativeFailure(failure);}}
        private void current(){if(chunkOwner!=null&&chunkOwner!=Thread.currentThread())throw new IllegalStateException("resident_native_wrong_owner");}
        private void position(int localX,int y,int localZ){current();if((localX|localZ)<0||localX>15||localZ>15||y<0||y>255)throw new IllegalArgumentException("resident_native_cell_bounds");}
        public int voxel(int localX,int y,int localZ){position(localX,y,localZ);try{return ((int)getId.invokeExact(nativeChunk,localX,y,localZ)<<4)|(int)getMetadata.invokeExact(nativeChunk,localX,y,localZ);}catch(Throwable failure){throw nativeFailure(failure);}}
        public boolean voxel(int localX,int y,int localZ,int id,int metadata){
            position(localX,y,localZ);if(id<0||id>4095||metadata<0||metadata>15)throw new IllegalArgumentException("resident_native_voxel_bounds");
            if(id!=0&&(id>=blocks.length||blocks[id]==null))throw new IllegalArgumentException("resident_native_unknown_block:"+id);
            int before=voxel(localX,y,localZ);if(before==((id<<4)|metadata))return false;
            try{dirtyReadSections|=1<<(y>>4);if(transaction!=null)transaction.copySection(y>>4);Object section=nativeSections[y>>4];if(section==null)nativeSections[y>>4]=section=sectionConstructor.newInstance(y&~15);
                setId.invokeExact(section,localX,y&15,localZ,id);setMetadata.invokeExact(section,localX,y&15,localZ,metadata);
                if((before>>>4)!=id)removeTile((y<<8)|(localZ<<4)|localX);changed=true;return true;
            }catch(Throwable failure){throw nativeFailure(failure);}
        }
        public TilePayload.Tile tile(int cell){current();if(cell<0||cell>65535)throw new IllegalArgumentException("resident_native_cell_bounds");return tiles.get(cell);}
        public void validatePersistentLimits(){current();if(tiles.size()>TilePayload.MAX_TILES)throw new IllegalArgumentException("resident_tile_count_limit");if(completeTileBytes>32L*1024*1024)throw new IllegalArgumentException("resident_tile_persistent_byte_limit");if(compactTileBytes+4L+16L*14336+256>TilePayload.MAX_RAW)throw new IllegalArgumentException("resident_tile_compact_byte_limit");}
        private void cacheTile(int cell,TilePayload.Tile tile){TilePayload.Tile before=tile==null?tiles.remove(cell):tiles.put(cell,tile);completeTileBytes+=(tile==null?0:tile.completeStateSize())-(long)(before==null?0:before.completeStateSize());compactTileBytes+=(tile==null?0:tile.recordSize())-(before==null?0:before.recordSize());dirtyReadTiles=true;}
        public void tile(TilePayload.Tile tile){Objects.requireNonNull(tile);if(Math.floorDiv(tile.x(),16)!=x||Math.floorDiv(tile.z(),16)!=z||voxel(Math.floorMod(tile.x(),16),tile.y(),Math.floorMod(tile.z(),16))>>>4==0)throw new IllegalArgumentException("resident_native_tile_position");if(tile.equals(tiles.get(tile.cell())))return;try{installTile(this,tile);changed=true;}catch(Exception failure){throw nativeFailure(failure);}}
        public void removeTile(int cell){current();if(cell<0||cell>65535)throw new IllegalArgumentException("resident_native_cell_bounds");if(tiles.containsKey(cell))try{detachTile.invoke(nativeChunk,cell&15,cell>>>8,(cell>>4)&15);cacheTile(cell,null);nativeTiles.remove(cell);changed=true;}catch(Exception failure){throw nativeFailure(failure);}}
        Object nativeTile(int cell){return nativeTiles.get(cell);}
        public Transaction transaction(Collection<Integer> cells)throws Exception{current();if(transaction!=null)throw new IllegalStateException("resident_native_nested_transaction");return transaction=new Transaction(cells);}
        public final class Transaction implements AutoCloseable {
            private final Map<Integer,Object> beforeSections=new HashMap<>(),beforeNativeTiles=new LinkedHashMap<>(),beforeNativeData=new HashMap<>();
            private final Map<Integer,TilePayload.Tile> beforeTiles=new LinkedHashMap<>();private final boolean wasChanged=changed;private boolean done;
            private Transaction(Collection<Integer> cells)throws Exception{for(int cell:cells){if(cell<0||cell>65535||beforeTiles.containsKey(cell))throw new IllegalArgumentException("resident_native_transaction_cells");beforeTiles.put(cell,tiles.get(cell));Object tile=nativeTiles.get(cell);beforeNativeTiles.put(cell,tile);if(tile!=null){Object compound=compoundConstructor.newInstance();tilePersistentWrite.invoke(tile,compound);beforeNativeData.put(cell,compound);}}}
            private void copySection(int index)throws Exception {
                if(beforeSections.containsKey(index))return;Object before=nativeSections[index];beforeSections.put(index,before);if(before==null)return;
                Object copy=sectionConstructor.newInstance(index*16);low.set(copy,((byte[])low.get(before)).clone());sectionCount.setInt(copy,sectionCount.getInt(before));
                for(Field field:groups)copyNibble(field,before,copy);copyNibble(high,before,copy);copyNibble(extraLight,before,copy);nativeSections[index]=copy;
            }
            private void copyNibble(Field field,Object source,Object destination)throws Exception{Object value=field.get(source);field.set(destination,value==null?null:nibbleConstructor.newInstance(((byte[])nibbleBytes.invoke(value)).clone(),4));}
            public void commit(){current();if(done||transaction!=this)throw new IllegalStateException("resident_native_transaction_closed");done=true;transaction=null;}
            @Override public void close(){current();if(done)return;try{
                for(var entry:beforeSections.entrySet())nativeSections[entry.getKey()]=entry.getValue();
                for(var entry:beforeTiles.entrySet()){int cell=entry.getKey();Object before=beforeNativeTiles.get(cell);Object now=nativeTiles.get(cell);
                    if(before!=null){tilePersistentRead.invoke(before,beforeNativeData.get(cell));var position=entry.getValue();tilePosition[0].setInt(before,position.x());tilePosition[1].setInt(before,position.y());tilePosition[2].setInt(before,position.z());tileWorld.set(before,world);}
                    if(now!=before){if(now!=null)detachTile.invoke(nativeChunk,cell&15,cell>>>8,(cell>>4)&15);if(before!=null)attachTile.invoke(nativeChunk,cell&15,cell>>>8,(cell>>4)&15,before);}
                    cacheTile(cell,entry.getValue());if(before==null)nativeTiles.remove(cell);else nativeTiles.put(cell,before);
                }dirtyReadTiles=true;for(int section:beforeSections.keySet())dirtyReadSections|=1<<section;changed=wasChanged;done=true;transaction=null;
            }catch(Exception failure){throw nativeFailure(failure);}}
        }
        /** Persist a successful native tool operation, retaining fields unknown to its writer. */
        public boolean refreshTile(int cell)throws Exception {TilePayload.Tile next=nativeTileSnapshot(cell);if(next==null)return false;if(next.equals(tiles.get(cell)))return false;cacheTile(cell,next);changed=true;return true;}
        /** Bounded immutable worker view; clone only sections changed since the last publication. */
        public ReadSnapshot readSnapshot(){
            current();if(transaction!=null)throw new IllegalStateException("resident_native_read_during_transaction");
            if(readSnapshot!=null&&dirtyReadSections==0&&!dirtyReadTiles&&readSnapshot.sideChannels==sideChannels)return readSnapshot;
            ReadSection[] copied=readSnapshot==null?new ReadSection[16]:readSnapshot.sections.clone();
            try{for(int s=0;s<16;s++)if((dirtyReadSections&(1<<s))!=0){Object section=nativeSections[s];copied[s]=section==null?null:new ReadSection(((byte[])low.get(section)).clone(),readNibble(groups[0],section),readNibble(high,section),readNibble(groups[1],section),readNibble(groups[2],section),readNibble(groups[3],section));}}
            catch(ReflectiveOperationException failure){throw nativeFailure(failure);}
            Map<Integer,Terrain.TileCell> copiedTiles;
            if(readSnapshot!=null&&!dirtyReadTiles)copiedTiles=readSnapshot.tiles;
            else{Map<Integer,Terrain.TileCell> next=new HashMap<>();for(var entry:tiles.entrySet())next.put(entry.getKey(),new Terrain.TileCell(entry.getValue(),ReadSnapshot.voxel(copied,entry.getKey())>>>4));copiedTiles=Map.copyOf(next);}
            try{readSnapshot=new ReadSnapshot(key(),copied,copiedTiles,sideChannels,sceneOrigin,sceneVersion,sceneData,((byte[])biomes.get(nativeChunk)).clone(),sourceRevision,changed);}catch(ReflectiveOperationException failure){throw nativeFailure(failure);}dirtyReadSections=0;dirtyReadTiles=false;return readSnapshot;
        }
        public void validateScenes(PreparedScenes prepared){current();if(transaction!=null||!key().equals(prepared.key)||sceneOrigin!=prepared.origin||sceneVersion!=prepared.version)throw new IllegalArgumentException("resident_scene_basis_stale");if(sceneVersion==Long.MAX_VALUE)throw new IllegalStateException("resident_scene_revision_exhausted");}
        /** Only a prevalidated worker result is installed; no framing/decompression runs on owner. */
        public void applyScenes(PreparedScenes prepared){validateScenes(prepared);sideChannels=prepared.bytes;sceneData=prepared.sceneData;sceneVersion++;changed=true;}
        public TilePayload.Tile nativeTileSnapshot(int cell)throws Exception {
            TilePayload.Tile original=tile(cell);Object nativeTile=nativeTiles.get(cell);if(original==null||nativeTile==null)return null;
            Object compound=compoundConstructor.newInstance();tilePersistentWrite.invoke(nativeTile,compound);byte[] encoded=(byte[])writeNbt.invoke(null,compound);
            SceneNbt.Root current=SceneNbt.readTile(CompleteTileState.raw(encoded));
            if(original.completeStateSize()==0)return CompleteTileState.decode(encoded);
            SceneNbt.Root source=SceneNbt.readTile(CompleteTileState.raw(original.completeState()));Map<String,SceneNbt.Tag> merged=new LinkedHashMap<>(source.values());merged.putAll(current.values());
            if(merged.equals(source.values()))return original;
            return CompleteTileState.decode(SceneNbt.writeTile(new SceneNbt.Root(source.name(),merged)));
        }
    }
    private byte[] readNibble(Field field,Object section)throws ReflectiveOperationException {Object value=field.get(section);return value==null?null:((byte[])nibbleBytes.invoke(value)).clone();}
    private record ReadSection(byte[] ids,byte[] metadata,byte[] high,byte[] blockLight,byte[] sky,byte[] auxiliary) {}
    /** No mutable game objects escape to geometry, editor or networking workers. */
    private static final class SceneData {
        final SceneRegistry.Manifest registry;final Map<UUID,ScenePayload.Entry> index;final boolean raw;final long bytes;
        SceneData(ResidentChunks.Key key,byte[] tail,SceneRegistry.Manifest registry){SceneCollision.worker();this.registry=registry;byte[] record=Wire.bytes(out->{out.writeInt(key.x());out.writeInt(key.z());out.writeBoolean(false);out.write(tail);});var channels=ScenePayload.parse(record).channels();index=ScenePayload.index(channels,registry);raw=channels.raw();long retained=index.size()*256L;for(var value:index.values())retained+=value.recordBytes()+value.occurrences().size()*128L;bytes=retained;}
    }
    public static final class ReadSnapshot implements Terrain.SceneEditChunk {
        private volatile UUID projectedRevision;
        public UUID networkRevision(){SceneCollision.worker();if(!changed)return nativeRevision;UUID found=projectedRevision;if(found==null){ByteBuffer record=ByteBuffer.wrap(networkRecord());found=new UUID(record.getLong(17),record.getLong(25));projectedRevision=found;}return found;}
        private final ResidentChunks.Key key;private final ReadSection[] sections;private final Map<Integer,Terrain.TileCell> tiles;
        private final byte[] sideChannels;private final Object sceneOrigin;private final long sceneVersion;private final SceneData sceneData;
        private final byte[] biomes;private final UUID nativeRevision;private final boolean changed;private final Terrain.SceneSource source;
        private ReadSnapshot(ResidentChunks.Key key,ReadSection[] sections,Map<Integer,Terrain.TileCell> tiles,byte[] sideChannels,Object sceneOrigin,long sceneVersion,SceneData sceneData,byte[] biomes,UUID nativeRevision,boolean changed){this.key=key;this.sections=sections;this.tiles=tiles;this.sideChannels=sideChannels;this.sceneOrigin=sceneOrigin;this.sceneVersion=sceneVersion;this.sceneData=sceneData;this.biomes=biomes;this.nativeRevision=nativeRevision;this.changed=changed;source=new Terrain.SceneSource(this::networkRecord);}
        public ResidentChunks.Key key(){return key;}
        private static int nibble(byte[] data,int cell){return data==null?0:(data[cell>>1]>>>((cell&1)*4))&15;}
        private static int voxel(ReadSection[] sections,int cell){if(cell<0||cell>65535)throw new IllegalArgumentException("resident_read_cell_bounds");ReadSection section=sections[cell>>>12];if(section==null)return 0;int local=cell&4095;return (((section.ids[local]&255)|(nibble(section.high,local)<<8))<<4)|nibble(section.metadata,local);}
        @Override public int voxel(int cell){return voxel(sections,cell);}
        @Override public TilePayload.Tile tile(int cell){if(cell<0||cell>65535)throw new IllegalArgumentException("resident_read_cell_bounds");var found=tiles.get(cell);return found==null?null:found.tile();}
        @Override public Map<Integer,Terrain.TileCell> tiles(){return tiles;}
        private SceneData sceneData(){if(sceneData==null)throw new IllegalArgumentException("resident_scene_registry_required");return sceneData;}
        @Override public Map<UUID,ScenePayload.Entry> scenes(){return sceneData().index;}
        @Override public Terrain.SceneSource sceneSource(){return source;}
        @Override public boolean rawSceneContainer(){return sceneData().raw;}
        /** Scene-only native record, for worker parsing; geometry remains in immutable sections. */
        public byte[] sceneRecord(){return Wire.bytes(out->{out.writeInt(key.x());out.writeInt(key.z());out.writeBoolean(false);out.write(sideChannels);});}
        public byte[] networkRecord(){
            SceneCollision.worker();try{return encodeRecord(geometry());}catch(Exception failure){throw new IllegalStateException("resident_network_encode",failure);}
        }
        private Geometry geometry(){
            int mask=0,additional=0;for(int s=0;s<16;s++)if(sections[s]!=null){mask|=1<<s;if(sections[s].high!=null)additional|=1<<s;}
            int primary=mask,upper=additional;byte[] arrays=Wire.bytes(out->{for(var section:sections)if(section!=null)out.write(section.ids);for(int group=0;group<4;group++)for(var section:sections)if(section!=null){byte[] value=switch(group){case 0->section.metadata;case 1->section.blockLight;case 2->section.sky;default->section.auxiliary;};out.write(value==null?new byte[2048]:value);}for(int s=0;s<16;s++)if((upper&(1<<s))!=0)out.write(sections[s].high);out.write(biomes);});
            var states=tiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(row->row.getValue().tile()).toList();return new Geometry(key.x(),key.z(),primary,upper,nativeRevision,arrays,sideChannels,states,changed);
        }
        private long bytes(){long bytes=512+tiles.size()*96L+(sceneData==null?0:sceneData.bytes);for(var section:sections)if(section!=null){bytes+=96+section.ids.length;for(byte[] group:new byte[][]{section.metadata,section.high,section.blockLight,section.sky,section.auxiliary})if(group!=null)bytes+=group.length;}return bytes;}
    }
    /** Complete detached native chunk prepared off-owner. */
    public final class PreparedTerrain {
        private final ReadSnapshot source;private final Chunk chunk;private final long bytes;private boolean consumed;
        private PreparedTerrain(ReadSnapshot source,Chunk chunk){this.source=source;this.chunk=chunk;bytes=residentBytes(chunk);}
        public ResidentChunks.Key key(){return source.key;}
        public long bytes(){return bytes;}
        public ReadSnapshot snapshot(){return chunk.readSnapshot;}
        public void validate(Chunk previous){owner();previous.current();if(consumed||active.get(key())!=previous||previous.readSnapshot()!=source)throw new IllegalArgumentException("resident_bulk_basis_stale");}
        public Chunk claim(Chunk previous){validate(previous);consumed=true;return chunk;}
    }
    /** Native array and full-tile work stays on the command worker even for large edits. */
    public PreparedTerrain prepareTerrain(ReadSnapshot source,List<ResidentBlockOperations.Delta> edits,PreparedScenes scene,SceneRegistry.Manifest registry)throws Exception{
        SceneCollision.worker();List<TilePayload.Tile> full=source.tiles.values().stream().map(Terrain.TileCell::tile).toList();Chunk next=decode(importRecord(source.networkRecord(),full));
        for(var edit:edits){var p=edit.position();if(!source.key.equals(new ResidentChunks.Key(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16))))throw new IllegalArgumentException("resident_bulk_chunk");int cell=(p.y()<<8)|(Math.floorMod(p.z(),16)<<4)|Math.floorMod(p.x(),16);if(source.voxel(cell)!=edit.expectedPacked()||!Objects.equals(source.tile(cell),edit.expectedTile()))throw new IllegalArgumentException("resident_block_compare_failed");next.voxel(Math.floorMod(p.x(),16),p.y(),Math.floorMod(p.z(),16),edit.packed()>>>4,edit.packed()&15);if(edit.tile()==null)next.removeTile(cell);else next.tile(edit.tile());}
        if(scene!=null){if(!scene.key.equals(source.key)||scene.origin!=source.sceneOrigin||scene.version!=source.sceneVersion)throw new IllegalArgumentException("resident_scene_basis_stale");next.sideChannels=scene.bytes;next.sceneData=scene.sceneData;next.changed=true;}
        next.validatePersistentLimits();if(registry!=null)next.prepareSceneReads(registry);else next.readSnapshot();byte[] record=next.readSnapshot.networkRecord();if(szserver.net.GamePackets.terrainChunk(record).length>240000)throw new IllegalArgumentException("terrain_frame_limit");for(var tile:next.tiles.values())if(tile.completeStateSize()>0)CompleteTileState.frame(tile);return new PreparedTerrain(source,next);
    }
    public static final class PreparedScenes {
        private final ResidentChunks.Key key;private final Object origin;private final long version;private final byte[] bytes,history;private final long growth;private final SceneData sceneData;
        private PreparedScenes(ReadSnapshot source,byte[] bytes,byte[] history,SceneData sceneData){key=source.key;origin=source.sceneOrigin;version=source.sceneVersion;this.bytes=bytes;this.history=history;this.sceneData=sceneData;growth=Math.max(0,bytes.length+(sceneData==null?0:sceneData.bytes)-(long)source.sideChannels.length-(source.sceneData==null?0:source.sceneData.bytes));}
        public ResidentChunks.Key key(){return key;}
        public long growth(){return growth;}
        public long retainedBytes(){return bytes.length+(long)history.length+(sceneData==null?0:sceneData.bytes);}
        /** Holds only changed object bodies, not every unrelated frame in the prepared chunk. */
        public ResidentOperationLog.Payload history(){byte[] immutable=history;return new ResidentOperationLog.Payload(immutable.length,()->immutable.clone());}
    }
    /** Editor worker preparation preserves raw/NBT containers, meshes and all untouched object bodies. */
    public static PreparedScenes prepareScenes(ReadSnapshot source,Map<UUID,ScenePayload.Entry> changes){return prepareScenes(source,changes,null);}
    public static PreparedScenes prepareScenes(ReadSnapshot source,Map<UUID,ScenePayload.Entry> changes,SceneRegistry.Manifest registry){
        SceneCollision.worker();var base=ScenePayload.parse(source.sceneRecord()).channels();byte[] bytes=ScenePayload.apply(base,changes,source.key.x(),source.key.z()).encode();ScenePayload.parse(Wire.bytes(out->{out.writeInt(source.key.x());out.writeInt(source.key.z());out.writeBoolean(false);out.write(bytes);}));
        Map<Integer,String> assets=new HashMap<>();for(var entry:changes.values())if(entry!=null)for(var occurrence:entry.occurrences())assets.put(occurrence.object().networkId(),occurrence.object().asset());
        Map<UUID,List<ScenePayload.Occurrence>> before=new HashMap<>();for(boolean container:new boolean[]{false,true})for(byte[] frame:container?base.containerObjects():base.objects()){
            UUID id=SceneObject.prefix(frame).identity();if(!changes.containsKey(id))continue;int networkId=ByteBuffer.wrap(frame).getShort();var definition=registry==null?null:registry.ids().get(networkId);String asset=definition==null?assets.getOrDefault(networkId,""):definition.name();before.computeIfAbsent(id,ignored->new ArrayList<>()).add(new ScenePayload.Occurrence(container,new SceneObject(asset,frame)));
        }
        byte[] history=Wire.bytes(out->{out.writeInt(source.key.x());out.writeInt(source.key.z());out.writeInt(changes.size());for(UUID id:new TreeSet<>(changes.keySet())){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());sceneHistory(out,before.getOrDefault(id,List.of()));var next=changes.get(id);sceneHistory(out,next==null?List.of():next.occurrences());}});
        SceneRegistry.Manifest effective=source.sceneData==null?registry:source.sceneData.registry;if(registry!=null&&source.sceneData!=null&&!registry.equals(effective))throw new IllegalArgumentException("resident_scene_registry_changed");return new PreparedScenes(source,bytes,history,effective==null?null:new SceneData(source.key,bytes,effective));
    }
    private static void sceneHistory(DataOutputStream out,List<ScenePayload.Occurrence> frames)throws IOException{out.writeInt(frames.size());for(var occurrence:frames){SceneObject object=occurrence.object();if(object.asset().length()>1024)throw new IllegalArgumentException("resident_scene_asset_name");out.writeBoolean(occurrence.container());out.writeUTF(object.asset());byte[] frame=object.record();out.writeInt(frame.length);out.write(frame);}}
    public static final class Snapshot {
        private final ReadSnapshot read;
        private Snapshot(ReadSnapshot read){this.read=read;}
    }
    private record Geometry(int x,int z,int mask,int additional,UUID revision,byte[] arrays,byte[] sideChannels,List<TilePayload.Tile> tiles,boolean changed) {}
    private static IllegalStateException nativeFailure(Throwable failure){if(failure instanceof InvocationTargetException target&&target.getCause()!=null)failure=target.getCause();return new IllegalStateException("resident_native_chunk_operation",failure);}
    public boolean supportsTile(int id,int metadata,int type)throws Exception {
        if(id<1||id>=blocks.length||metadata<0||metadata>15||type<0||type>1||blocks[id]==null||!(Boolean)tileAllowed.invoke(blocks[id],metadata))return false;
        Object prototype=tileFactory.invoke(blocks[id],world,metadata);return prototype!=null&&tileConstructors[type].getDeclaringClass().isInstance(prototype)&&tileConstructors[1].getDeclaringClass().isInstance(prototype)==(type==1);
    }
    public void validateVoxel(int packed,TilePayload.Tile tile)throws Exception {
        int id=packed>>>4,metadata=packed&15;if(packed<0||packed>65535||id!=0&&(id>=blocks.length||blocks[id]==null))throw new IllegalArgumentException("resident_native_unknown_block:"+id);
        if(tile!=null&&!supportsTile(id,metadata,tile.type()))throw new IllegalArgumentException("resident_block_tile_mismatch");
        if(tile==null&&id!=0&&(Boolean)tileAllowed.invoke(blocks[id],metadata))throw new IllegalArgumentException("resident_block_missing_tile_state");
    }
    /** Worker-side native factory defaults for deployment/tooling; never fabricate a client tile layout. */
    public TilePayload.Tile defaultTile(int id,int metadata,World.Block position)throws Exception{
        SceneCollision.worker();if(id<1||id>=blocks.length||blocks[id]==null||metadata<0||metadata>15||!(Boolean)tileAllowed.invoke(blocks[id],metadata))throw new IllegalArgumentException("resident_native_tile_factory");Object tile=tileFactory.invoke(blocks[id],world,metadata);if(tile==null||!tileConstructors[0].getDeclaringClass().isInstance(tile))throw new IllegalArgumentException("resident_native_tile_factory");tilePosition[0].setInt(tile,position.x());tilePosition[1].setInt(tile,position.y());tilePosition[2].setInt(tile,position.z());tileWorld.set(tile,world);Object compound=compoundConstructor.newInstance();tilePersistentWrite.invoke(tile,compound);return CompleteTileState.decode((byte[])writeNbt.invoke(null,compound));
    }
    private void installTile(Chunk chunk,TilePayload.Tile tile)throws Exception {
        int packed=chunk.voxel(Math.floorMod(tile.x(),16),tile.y(),Math.floorMod(tile.z(),16)),id=packed>>>4;
        if(id==0||id>=blocks.length||blocks[id]==null||!(Boolean)tileAllowed.invoke(blocks[id],packed&15))throw new IOException("resident_native_tile_block_mismatch:"+id);
        Object instance=tileFactory.invoke(blocks[id],world,packed&15);
        if(instance==null||!tileConstructors[tile.type()].getDeclaringClass().isInstance(instance)||tileConstructors[1].getDeclaringClass().isInstance(instance)!=(tile.type()==1))throw new IOException("resident_native_tile_factory_mismatch:"+id);
        readTileState(instance,tile);
        attachTile.invoke(chunk.nativeChunk,Math.floorMod(tile.x(),16),tile.y(),Math.floorMod(tile.z(),16),instance);
        if(getTile.invoke(chunk.nativeChunk,Math.floorMod(tile.x(),16),tile.y(),Math.floorMod(tile.z(),16))!=instance)throw new IOException("resident_native_tile_not_attached");
        chunk.cacheTile(tile.cell(),tile);chunk.nativeTiles.put(tile.cell(),instance);
    }
    private void readTileState(Object instance,TilePayload.Tile tile)throws Exception {
        if(tile.completeStateSize()>0){byte[] raw=CompleteTileState.raw(tile.completeState());try(var in=new DataInputStream(new ByteArrayInputStream(raw))){Object compound=readNbt.invoke(null,in);if(in.available()!=0)throw new IOException("resident_native_tile_nbt_trailing");tilePersistentRead.invoke(instance,compound);}}
        else{byte[] record=tile.record();try(var in=new DataInputStream(new ByteArrayInputStream(record,13,record.length-13))){tileCompactRead.invoke(instance,in);if(in.available()!=0)throw new IOException("resident_native_tile_compact_trailing");}}
        tilePosition[0].setInt(instance,tile.x());tilePosition[1].setInt(instance,tile.y());tilePosition[2].setInt(instance,tile.z());tileWorld.set(instance,world);
    }
    /** The envelope retains full persistent compounds which the native compact chunk tail omits. */
    public static byte[] importRecord(byte[] record,Collection<TilePayload.Tile> complete)throws Exception {
        TilePayload.fromRecord(record);return envelope(record,complete);
    }
    private static byte[] envelope(byte[] record,Collection<TilePayload.Tile> tiles)throws IOException {
        return envelope(record,tiles,1);
    }
    private static byte[] envelope(byte[] record,Collection<TilePayload.Tile> tiles,int version)throws IOException {
        if(record.length<41||record.length>(version==1?MAX_RECORD:ScenePersistence.MAX_RECORD))throw new IOException("resident_native_record_size");
        var full=tiles.stream().filter(tile->tile.completeStateSize()>0).sorted(Comparator.comparingInt(TilePayload.Tile::cell)).toList();
        return Wire.bytes(out->{out.writeInt(MAGIC);out.writeInt(version);out.writeInt(record.length);out.write(record);out.writeInt(full.size());for(var tile:full){byte[] state=tile.completeState();out.writeInt(tile.cell());out.writeInt(state.length);out.write(state);}});
    }
    @Override public Chunk decode(byte[] bytes)throws Exception {
        byte[] record;int version;Map<Integer,byte[]> full=new HashMap<>();
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC)throw new IOException("resident_native_envelope_version");version=in.readInt();if(version<1||version>VERSION)throw new IOException("resident_native_envelope_version");int length=in.readInt();if(length<41||length>(version==1?MAX_RECORD:ScenePersistence.MAX_RECORD)||length>in.available())throw new IOException("resident_native_record_size");record=in.readNBytes(length);
            int count=in.readInt();if(count<0||count>TilePayload.MAX_TILES)throw new IOException("resident_native_complete_count");long retained=0;
            for(int i=0;i<count;i++){int cell=in.readInt(),size=in.readInt();if(cell<0||cell>65535||size<4||size>CompleteTileState.MAX_STATE||size>in.available()||(retained+=size)>32L*1024*1024||full.put(cell,in.readNBytes(size))!=null)throw new IOException("resident_native_complete_state");}
            if(in.available()!=0)throw new IOException("resident_native_envelope_trailing");
        }
        if(version==2)record=ScenePersistence.decodeRecord(record,persistenceCatalog);
        ByteBuffer header=ByteBuffer.wrap(record);int x=header.getInt(0),z=header.getInt(4),mask=header.getInt(9),additional=header.getInt(13),size=header.getInt(33),count=Integer.bitCount(mask);
        if((mask&~65535)!=0||(additional&~mask)!=0||size!=count*12288+Integer.bitCount(additional)*2048+256)throw new IOException("resident_native_array_layout");
        byte[] raw=TilePayload.raw(record);List<TilePayload.Tile> tiles=TilePayload.decode(raw,size,x,z);ScenePayload.Layout scene=ScenePayload.parse(record);
        Chunk result=new Chunk(chunkConstructor.newInstance(world,x,z),x,z,new UUID(header.getLong(17),header.getLong(25)),Arrays.copyOfRange(record,scene.terrainEnd(),record.length));
        int ordinal=0,highAt=count*12288;
        for(int s=0;s<16;s++)if((mask&(1<<s))!=0){Object section=sectionConstructor.newInstance(s*16);result.nativeSections[s]=section;byte[] ids=Arrays.copyOfRange(raw,ordinal*4096,(ordinal+1)*4096);low.set(section,ids);
            if((additional&(1<<s))!=0){high.set(section,nibbleConstructor.newInstance(Arrays.copyOfRange(raw,highAt,highAt+2048),4));highAt+=2048;}
            for(int group=0;group<4;group++){int start=count*(4096+group*2048)+ordinal*2048;groups[group].set(section,nibbleConstructor.newInstance(Arrays.copyOfRange(raw,start,start+2048),4));}
            // Native recount may discard unknown IDs; reject them explicitly beforehand.
            Object upper=high.get(section);byte[] highs=upper==null?null:(byte[])nibbleBytes.invoke(upper);
            for(int cell=0;cell<4096;cell++){int id=ids[cell]&255;if(highs!=null)id|=((highs[cell>>1]>>>((cell&1)*4))&15)<<8;if(id!=0&&(id>=blocks.length||blocks[id]==null))throw new IOException("resident_native_unknown_block:"+id);}
            recount.invoke(section);ordinal++;
        }
        biomes.set(result.nativeChunk,Arrays.copyOfRange(raw,size-256,size));
        for(var tile:tiles){byte[] state=full.remove(tile.cell());if(state!=null){TilePayload.Tile complete=CompleteTileState.decode(state);if(!Arrays.equals(complete.record(),tile.record()))throw new IOException("resident_native_complete_appearance");tile=complete;}
            int packed=result.voxel(Math.floorMod(tile.x(),16),tile.y(),Math.floorMod(tile.z(),16));
            // Authored chunks can retain old compact tile records under air/another block.
            // Preserve those bytes for native streaming and saves; do not manufacture a block
            // or attach an incompatible live tile. New edits still pass validateVoxel/installTile.
            if(!supportsTile(packed>>>4,packed&15,tile.type()))result.cacheTile(tile.cell(),tile);else installTile(result,tile);
        }
        if(!full.isEmpty())throw new IOException("resident_native_complete_without_tile");result.readSnapshot();return result;
    }
    @Override public long residentBytes(Chunk chunk){try{long bytes=4096+chunk.sideChannels.length+256;for(Object section:chunk.nativeSections)if(section!=null){bytes+=4096+128;for(Field group:groups){Object nibble=group.get(section);if(nibble!=null)bytes+=2048+48;}if(high.get(section)!=null)bytes+=2048+48;}bytes+=chunk.compactTileBytes+chunk.completeTileBytes+chunk.tiles.size()*768L;if(chunk.readSnapshot!=null)bytes+=chunk.readSnapshot.bytes();return bytes;}catch(ReflectiveOperationException failure){throw nativeFailure(failure);}}
    @Override public Snapshot freeze(Chunk chunk)throws Exception {
        chunk.current();if(chunk.transaction!=null)throw new IllegalStateException("resident_native_snapshot_during_transaction");
        return new Snapshot(chunk.readSnapshot());
    }
    private static byte[] encodeRecord(Geometry state)throws Exception {
        byte[] geometry=Wire.bytes(out->{out.write(state.arrays);out.write(TilePayload.encode(state.tiles));});byte[] compressed=Zstd.compress(geometry,3);UUID revision=state.revision;
        if(state.changed){MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(geometry);hash.update(state.sideChannels);for(var tile:state.tiles)if(tile.completeStateSize()>0)hash.update(tile.completeState());revision=UUID.nameUUIDFromBytes(hash.digest());}UUID version=revision;
        byte[] record=Wire.bytes(out->{out.writeInt(state.x);out.writeInt(state.z);out.writeBoolean(true);out.writeInt(state.mask);out.writeInt(state.additional);out.writeLong(version.getMostSignificantBits());out.writeLong(version.getLeastSignificantBits());out.writeInt(state.arrays.length);out.writeInt(compressed.length);out.write(compressed);out.write(state.sideChannels);});
        return record;
    }
    @Override public byte[] encode(Snapshot state)throws Exception {Geometry geometry=state.read.geometry();return envelope(ScenePersistence.encodeRecord(encodeRecord(geometry),persistenceCatalog),geometry.tiles,VERSION);}
    public byte[] networkRecord(Snapshot snapshot)throws Exception {byte[] record=encodeRecord(snapshot.read.geometry());if(record.length>MAX_RECORD)throw new IOException("resident_native_record_size");return record;}
    @Override public void activate(Chunk chunk){if(owner==null)owner=Thread.currentThread();owner();if(active.putIfAbsent(chunk.key(),chunk)!=null)throw new IllegalStateException("resident_native_duplicate_chunk");chunk.chunkOwner=owner;}
    @Override public void activate(ResidentChunks.Key expected,Chunk chunk,long revision){if(!expected.equals(chunk.key()))throw new IllegalArgumentException("resident_native_chunk_coordinate");activate(chunk);}
    @Override public void replace(ResidentChunks.Key key,Chunk previous,Chunk replacement,long revision){owner();if(!key.equals(replacement.key())||replacement.chunkOwner!=null||!active.replace(key,previous,replacement))throw new IllegalStateException("resident_native_replace");replacement.chunkOwner=owner;}
    public boolean active(Chunk chunk){if(owner!=null)owner();return active.get(chunk.key())==chunk;}
    @Override public void release(Chunk chunk){chunk.current();active.remove(chunk.key(),chunk);/* Native sections own Java arrays; no renderer/GPU resources are created. */}
}

