package szserver.net;

import com.github.luben.zstd.Zstd;
import szserver.client.NativeResidentChunkCodec;
import szserver.world.*;
import szserver.util.Workers;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Full initial geometry plus bounded deltas from the same immutable world overlay. */
public final class TerrainProjection implements AutoCloseable {
    public record Delta(int cell,BlockOverlays.Voxel voxel){}
    /** Native voxel messages require neither chunk decompression nor recompression. */
    public record TileUpdate(long revision,byte[] frame){public TileUpdate{frame=frame.clone();}@Override public byte[] frame(){return frame.clone();}int bytes(){return frame.length;}}
    public record Changes(long revision,long baseRevision,long refreshRevision,List<Delta> deltas,List<TileUpdate> tiles){
        public Changes{deltas=List.copyOf(deltas);tiles=List.copyOf(tiles);}
        public Changes(long revision,long baseRevision,long refreshRevision,List<Delta> deltas){this(revision,baseRevision,refreshRevision,deltas,List.of());}
        private static final Changes FULL=new Changes(-1,0,0,List.of());
        boolean usable(long sent){return revision>=sent&&baseRevision<=sent&&refreshRevision<=sent;}
        long bytes(){return 96+deltas.size()*64L+tiles.stream().mapToLong(TileUpdate::bytes).sum();}
    }
    public static final class Prepared {
        private static final int MAX_VOXEL_DELTAS=128;
        private final byte[] record,frame,objectFrame;
        private final UUID nativeRevision;
        private final long revision;
        private final long baseRevision;
        private final long refreshRevision;
        private final boolean tileRefresh;
        private final List<Delta> deltas;
        private final List<byte[]> completeFrames;
        private final Set<Integer> tileCells=new HashSet<>();
        private final java.lang.ref.WeakReference<szserver.client.NativeResidentChunkCodec.ReadSnapshot> residentBasis;
        Prepared(byte[] record,BlockOverlays.Snapshot overlay,boolean tileRefresh,List<TilePayload.Tile> baseTiles){this(record,overlay,tileRefresh,baseTiles,0);}
        Prepared(byte[] record,BlockOverlays.Snapshot overlay,boolean tileRefresh,List<TilePayload.Tile> baseTiles,long baseRevision){this(record,overlay,tileRefresh,baseTiles,baseRevision,null,null);}
        private Prepared(byte[] record,BlockOverlays.Snapshot overlay,boolean tileRefresh,List<TilePayload.Tile> baseTiles,long baseRevision,szserver.client.NativeResidentChunkCodec.ReadSnapshot basis,SceneHashWire.SceneInstances sceneInstances){this.baseRevision=baseRevision;this.tileRefresh=tileRefresh;residentBasis=new java.lang.ref.WeakReference<>(basis);
            // Existing scenes/tiles do not make every later voxel edit a full chunk update.
            // A changed base tile still requires its complete appearance to be replaced.
            long refresh=overlay.refreshRevision();for(var tile:baseTiles){var voxel=overlay.cells().get(tile.cell());if(voxel!=null)refresh=Math.max(refresh,voxel.revision());}refreshRevision=refresh;this.record=record;byte[] wireRecord=SceneHashWire.record(record,sceneInstances);ByteBuffer header=ByteBuffer.wrap(wireRecord);nativeRevision=new UUID(header.getLong(17),header.getLong(25));frame=GamePackets.terrainChunk(wireRecord);objectFrame=szserver.client.TerrainCachePackets.frame(wireRecord);if(frame.length>MAX_TERRAIN_FRAME_BYTES)throw new IllegalArgumentException("terrain_frame_limit");Map<Integer,TilePayload.Tile> full=new HashMap<>();for(var tile:baseTiles)full.put(tile.cell(),tile);for(var entry:overlay.tiles().entrySet())if(entry.getValue()==null||entry.getValue().completeStateSize()==0)full.remove(entry.getKey());else full.put(entry.getKey(),entry.getValue());tileCells.addAll(full.keySet());for(var tile:TilePayload.fromRecord(record))tileCells.add(tile.cell());completeFrames=TilePayload.fromRecord(record).stream().sorted(Comparator.comparingInt(TilePayload.Tile::cell)).filter(tile->{var state=full.get(tile.cell());return state!=null&&state.completeStateSize()>0&&Arrays.equals(tile.record(),state.record());}).map(tile->szserver.client.CompleteTileState.frame(full.get(tile.cell()))).toList();revision=overlay.revision();deltas=overlay.cells().entrySet().stream().map(e->new Delta(e.getKey(),e.getValue())).sorted(Comparator.comparingLong(delta->delta.voxel.revision())).toList();}
        public byte[] record(){return record.clone();}
        public int completeFrameCount(){return completeFrames.size();}
        byte[] completeFrame(int index){return completeFrames.get(index);}
        byte[] frame(){return frame;}
        public UUID nativeRevision(){return nativeRevision;}
        byte[] frame(UUID cached){return nativeRevision.equals(cached)?objectFrame:frame;}
        public boolean tileRefresh(){return tileRefresh;}
        /** Large edits use one compressed native snapshot instead of thousands of reliable messages. */
        public boolean refreshChunk(int nextDelta){if(nextDelta<0||nextDelta>deltas.size())throw new IllegalArgumentException("delta_index");return tileRefresh||deltas.size()-nextDelta>MAX_VOXEL_DELTAS;}
        /** A recipient behind the materialized base cannot recover from retired deltas. */
        public boolean refreshChunk(int nextDelta,long recipientRevision){if(nextDelta<0||nextDelta>deltas.size())throw new IllegalArgumentException("delta_index");return baseRevision>recipientRevision||refreshRevision>recipientRevision||deltas.size()-nextDelta>MAX_VOXEL_DELTAS;}
        public long revision(){return revision;}
        public long baseRevision(){return baseRevision;}
        public List<Delta> deltas(){return deltas;}
        long retainedBytes(){long bytes=256L+record.length+frame.length+objectFrame.length+deltas.size()*64L+tileCells.size()*32L;for(byte[] complete:completeFrames)bytes+=24L+complete.length;return bytes;}
        public int after(long version){int lo=0,hi=deltas.size();while(lo<hi){int mid=(lo+hi)>>>1;if(deltas.get(mid).voxel.revision()<=version)lo=mid+1;else hi=mid;}return lo;}
    }
    private final SceneHashWire.SceneInstances sceneInstances=new SceneHashWire.SceneInstances();
    private final WorldLoop world;
    private final RegionTerrain regions;
    private final GamePackets packets;
    private final int capacity;
    private final ThreadPoolExecutor worker=Workers.pool("terrain-projection",1,64);
    private final ConcurrentHashMap<Long,Long> retryAfter=new ConcurrentHashMap<>();
    private volatile String lastError="";
    private void projectionFailed(long key,Exception failure){errors.incrementAndGet();Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();String reason=cause.getClass().getSimpleName()+":"+Objects.toString(cause.getMessage(),"");lastError=(int)(key>>32)+","+(int)key+":"+reason.substring(0,Math.min(200,reason.length()));if(retryAfter.size()<capacity||retryAfter.containsKey(key))retryAfter.put(key,System.nanoTime()+TimeUnit.SECONDS.toNanos(1));}
    private final Map<Long,Prepared> cache=new LinkedHashMap<>(32,.75f,true);
    // A complete chunk may span several reliable windows. It still fits the
    // transport's bounded terrain backlog, including headers and scene data.
    static final int MAX_TERRAIN_FRAME_BYTES=256*1024;
    static final int STREAM_CACHE_ENTRIES=4096;
    private static final int CHANGES_CACHE_ENTRIES=256;
    private static final long CACHE_BYTES=128L*1024*1024;private long cachedBytes;
    /** Native chunks already bound complete tile state to 32 MiB. Keep legacy oversized records streamable. */
    private void remember(long key,Prepared prepared){synchronized(cache){var previous=cache.put(key,prepared);if(previous!=null)cachedBytes-=previous.retainedBytes();cachedBytes+=prepared.retainedBytes();while(cache.size()>capacity||cachedBytes>CACHE_BYTES&&cache.size()>1){var first=cache.entrySet().iterator();var row=first.next();cachedBytes-=row.getValue().retainedBytes();first.remove();}}}
    private final Set<Long> loading=ConcurrentHashMap.newKeySet();
    private final Map<Long,Changes> changesCache=new LinkedHashMap<>(32,.75f,true);
    private final Set<Long> changesLoading=new HashSet<>();
    private final AtomicLong deltaReads=new AtomicLong();
    private final AtomicLong errors=new AtomicLong(),builds=new AtomicLong(),rejected=new AtomicLong();
    private volatile boolean residentMode;private long changesBytes;
    private ResidentMapRuntime resident(){var map=world.residentMap();if(map!=null&&!residentMode){synchronized(cache){cache.clear();cachedBytes=0;}synchronized(changesCache){changesCache.clear();changesBytes=0;}residentMode=true;}return map;}
    public TerrainProjection(WorldLoop world,RegionTerrain regions,GamePackets packets,int capacity){if(capacity<16||capacity>4096)throw new IllegalArgumentException("projection_capacity");this.world=world;this.regions=regions;this.packets=packets;this.capacity=capacity;if(regions!=null)SceneHashWire.configure(regions.collisionDefinitions().values());}
    public long version(int x,int z){var map=resident();if(map!=null){var view=map.reads().peek(new ResidentChunks.Key(x,z));return view==null?0:view.revision();}return world.overlayVersions().version(x,z);}
    private long baseRevision(int x,int z){if(resident()!=null)return 0;return regions==null?0:regions.liveCommits().revision(x,z);}
    /** Null means a bounded owner read is pending; FULL requests the usual snapshot path. */
    public Changes changes(int x,int z,long sent){
        var map=resident();if(map!=null)return residentChanges(map,x,z,sent);
        long key=BlockOverlays.key(x,z),version=version(x,z),base=baseRevision(x,z);Prepared known;
        synchronized(cache){known=cache.get(key);}
        if(known==null||known.baseRevision!=base||known.revision>sent)return Changes.FULL;
        synchronized(changesCache){
            Changes found=changesCache.get(key);
            if(found!=null&&found.revision==version&&found.baseRevision==base)return found;
            if(changesLoading.contains(key)||changesLoading.size()>=32)return null;
            changesLoading.add(key);
        }
        Prepared basis=known;
        world.submit(w->{
            var overlay=w.overlayVersions().smallVoxels(x,z,128);long capturedBase=baseRevision(x,z);
            if(overlay==null||capturedBase!=basis.baseRevision)return new Changes(version(x,z),capturedBase,Long.MAX_VALUE,List.of());
            long refresh=overlay.refreshRevision();
            for(int cell:basis.tileCells){var voxel=overlay.cells().get(cell);if(voxel!=null)refresh=Math.max(refresh,voxel.revision());}
            List<Delta> deltas=overlay.cells().entrySet().stream().map(e->new Delta(e.getKey(),e.getValue())).sorted(Comparator.comparingLong(d->d.voxel.revision())).toList();
            return new Changes(overlay.revision(),capturedBase,refresh,deltas);
        }).whenComplete((value,error)->{
            synchronized(changesCache){changesLoading.remove(key);if(error==null){changesCache.put(key,value);while(changesCache.size()>Math.min(capacity,CHANGES_CACHE_ENTRIES))changesCache.remove(changesCache.keySet().iterator().next());deltaReads.incrementAndGet();}else rejected.incrementAndGet();}
        });
        return null;
    }
    /** A cached older revision is usable: later deltas follow its frame on the same lane. */
    public Prepared prepared(int x,int z){var resident=resident();if(resident!=null)return residentPrepared(resident,x,z);long key=BlockOverlays.key(x,z);Prepared found;synchronized(cache){found=cache.get(key);}if(found!=null&&found.baseRevision!=baseRevision(x,z))found=null;if(found!=null&&found.revision==version(x,z))return found;
        if(loading.add(key)){
            var nativeChunk=regions==null?null:regions.nativeChunkSnapshot(x,z);byte[] base=nativeChunk==null?null:nativeChunk.record();List<TilePayload.Tile> baseTiles=nativeChunk==null?List.of():nativeChunk.completeTiles();
            if(regions!=null&&base==null){loading.remove(key);return found;}
            world.submit(w->w.overlay(x,z)).whenComplete((overlay,error)->{
                if(error!=null){rejected.incrementAndGet();loading.remove(key);return;}
                try{worker.execute(()->{try{byte[] record=base==null?packets.chunkRecord(x,z):base;Prepared built=new Prepared(project(record,overlay),overlay,!overlay.scenes().isEmpty()||!overlay.tiles().isEmpty()||!TilePayload.fromRecord(record).isEmpty(),baseTiles,nativeChunk==null?0:nativeChunk.baseRevision(),null,sceneInstances);if(world.residentMap()!=null||built.baseRevision!=baseRevision(x,z))return;remember(key,built);builds.incrementAndGet();}catch(Exception failed){errors.incrementAndGet();}finally{loading.remove(key);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();loading.remove(key);}
            });
        }
        return found;
    }
    private Changes residentChanges(ResidentMapRuntime map,int x,int z,long sent){
        var chunk=new ResidentChunks.Key(x,z);var view=map.replication().view(chunk);if(view==null||sent<view.floor())return Changes.FULL;long key=BlockOverlays.key(x,z);
        synchronized(changesCache){var ready=changesCache.get(key);if(ready!=null&&ready.revision()==view.revision())return ready;if(changesLoading.contains(key)||changesLoading.size()>=32)return null;changesLoading.add(key);}
        try{worker.execute(()->{try{
            List<Delta> deltas=new ArrayList<>();List<TileUpdate> tiles=new ArrayList<>();long wireBytes=0,refresh=0;
            for(var point:view.cells().values().stream().sorted(Comparator.comparingLong(ResidentReplication.Point::revision).thenComparingInt(ResidentReplication.Point::cell)).toList()){
                deltas.add(new Delta(point.cell(),new BlockOverlays.Voxel(point.packed()>>>4,point.packed()&15,point.revision())));
                if(point.tile()!=null){if(point.tile().completeStateSize()==0){refresh=view.revision();break;}byte[] frame=szserver.client.CompleteTileState.frame(point.tile());wireBytes+=frame.length;if(wireBytes>1500000){refresh=view.revision();break;}tiles.add(new TileUpdate(point.revision(),frame));}
            }
            var prepared=new Changes(view.revision(),view.floor(),refresh,deltas,tiles);synchronized(changesCache){var before=changesCache.put(key,prepared);if(before!=null)changesBytes-=before.bytes();changesBytes+=prepared.bytes();while(changesCache.size()>Math.min(capacity,CHANGES_CACHE_ENTRIES)||changesBytes>64L*1024*1024){var first=changesCache.entrySet().iterator();var row=first.next();changesBytes-=row.getValue().bytes();first.remove();}}deltaReads.incrementAndGet();
        }catch(Exception failure){errors.incrementAndGet();}finally{synchronized(changesCache){changesLoading.remove(key);}}});}catch(RejectedExecutionException full){synchronized(changesCache){changesLoading.remove(key);}rejected.incrementAndGet();}return null;
    }
    private Prepared residentPrepared(ResidentMapRuntime map,int x,int z){
        long key=BlockOverlays.key(x,z);var point=new ResidentChunks.Key(x,z);var current=map.reads().peek(point);Prepared found;synchronized(cache){found=cache.get(key);}if(current!=null&&found!=null&&found.revision()==current.revision()&&found.residentBasis.get()==current.data())return found;
        if(System.nanoTime()<retryAfter.getOrDefault(key,Long.MIN_VALUE))return null;
        if(loading.add(key)){
            if(current!=null){
                // Snapshot is immutable. Its current identity was published by the
                // map owner, so the projection can start without another tick.
                projectResident(map,point,key,current.data(),current.revision());
            }else world.submit(world->map.load(point)).thenCompose(future->future).whenComplete((snapshot,error)->{
                if(error!=null){loading.remove(key);rejected.incrementAndGet();return;}
                var basis=map.reads().peek(point);
                if(basis==null||basis.data()!=snapshot){loading.remove(key);return;}
                projectResident(map,point,key,snapshot,basis.revision());
            });
        }
        // Initial/re-entry streaming must wait for an authoritative resident revision.
        return null;
    }
    private void projectResident(ResidentMapRuntime map,ResidentChunks.Key point,long key,
                                 NativeResidentChunkCodec.ReadSnapshot snapshot,long revision){
        try{worker.execute(()->{try{
            byte[] record=snapshot.networkRecord();
            var tiles=snapshot.tiles().values().stream().map(Terrain.TileCell::tile).toList();
            Prepared ready=new Prepared(record,new BlockOverlays.Snapshot(revision,Map.of(),Map.of(),Map.of()),true,tiles,revision,snapshot,sceneInstances);
            var active=map.reads().peek(point);
            if(active==null||active.data()!=snapshot)return;
            remember(key,ready);
            retryAfter.remove(key);
            builds.incrementAndGet();
        }catch(Exception failure){projectionFailed(key,failure);}finally{loading.remove(key);}});}
        catch(RejectedExecutionException full){loading.remove(key);rejected.incrementAndGet();}
    }
    private static int nibble(byte[] array,int index){return (array[index>>1]>>>((index&1)*4))&15;}
    private static void nibble(byte[] array,int index,int value){int at=index>>1,shift=(index&1)*4;array[at]=(byte)((array[at]&~(15<<shift))|(value<<shift));}
    /** Native arrays use separate packed groups, not interleaved section records. */
    public static byte[] project(byte[] base,BlockOverlays.Snapshot overlay)throws Exception{
        if(overlay.cells().isEmpty()&&overlay.tiles().isEmpty()&&overlay.scenes().isEmpty())return base.clone();
        if(base.length<60)throw new IllegalArgumentException("projection_record_short");ByteBuffer header=ByteBuffer.wrap(base);int mask=header.getInt(9),add=header.getInt(13),size=header.getInt(33),compressed=header.getInt(37);
        if((mask&~65535)!=0||(add&~mask)!=0||size!=Integer.bitCount(mask)*12288+Integer.bitCount(add)*2048+256||compressed<4||(long)compressed+41>base.length)throw new IllegalArgumentException("projection_arrays_layout");
        ScenePayload.Layout scene=ScenePayload.parse(base);byte[] sideChannels=overlay.scenes().isEmpty()?Arrays.copyOfRange(base,scene.terrainEnd(),base.length):ScenePayload.apply(scene.channels(),overlay.scenes(),header.getInt(0),header.getInt(4)).encode();byte[] raw=TilePayload.raw(base);List<TilePayload.Tile> tiles=TilePayload.decode(raw,size,header.getInt(0),header.getInt(4));int count=Integer.bitCount(mask),highAt=count*12288,ordinal=0;
        byte[][] lows=new byte[16][],metadata=new byte[16][],blockLight=new byte[16][],sky=new byte[16][],aux=new byte[16][],high=new byte[16][];
        for(int section=0;section<16;section++)if((mask&(1<<section))!=0){lows[section]=Arrays.copyOfRange(raw,ordinal*4096,(ordinal+1)*4096);metadata[section]=Arrays.copyOfRange(raw,count*4096+ordinal*2048,count*4096+(ordinal+1)*2048);blockLight[section]=Arrays.copyOfRange(raw,count*6144+ordinal*2048,count*6144+(ordinal+1)*2048);sky[section]=Arrays.copyOfRange(raw,count*8192+ordinal*2048,count*8192+(ordinal+1)*2048);aux[section]=Arrays.copyOfRange(raw,count*10240+ordinal*2048,count*10240+(ordinal+1)*2048);high[section]=(add&(1<<section))==0?new byte[2048]:Arrays.copyOfRange(raw,highAt,highAt+2048);if((add&(1<<section))!=0)highAt+=2048;ordinal++;}
        for(var e:overlay.cells().entrySet()){int cell=e.getKey();if(cell<0||cell>65535)throw new IllegalArgumentException("projection_cell_bounds");int section=cell>>>12,index=cell&4095;BlockOverlays.Voxel voxel=e.getValue();if(voxel.id()<0||voxel.id()>4095||voxel.metadata()<0||voxel.metadata()>15)throw new IllegalArgumentException("projection_voxel_bounds");
            if(lows[section]==null){lows[section]=new byte[4096];metadata[section]=new byte[2048];blockLight[section]=new byte[2048];sky[section]=new byte[2048];aux[section]=new byte[2048];high[section]=new byte[2048];mask|=1<<section;}
            lows[section][index]=(byte)voxel.id();nibble(high[section],index,voxel.id()>>>8);nibble(metadata[section],index,voxel.metadata());
        }
        add=header.getInt(13);for(int section=0;section<16;section++)if(high[section]!=null)for(byte value:high[section])if(value!=0){add|=1<<section;break;}
        final int primary=mask,additional=add;int arrays=Integer.bitCount(mask)*12288+Integer.bitCount(add)*2048+256;
        byte[] geometry=Wire.bytes(out->{for(byte[][] group:new byte[][][]{lows,metadata,blockLight,sky,aux})for(byte[] section:group)if(section!=null)out.write(section);for(int section=0;section<16;section++)if((additional&(1<<section))!=0)out.write(high[section]);out.write(raw,size-256,256);Map<Integer,TilePayload.Tile> kept=new TreeMap<>();for(TilePayload.Tile tile:tiles){BlockOverlays.Voxel edit=overlay.cells().get(tile.cell());int section=tile.cell()>>>12,index=tile.cell()&4095,oldOrdinal=Integer.bitCount(header.getInt(9)&((1<<section)-1)),baseId=0;if((header.getInt(9)&(1<<section))!=0){baseId=raw[oldOrdinal*4096+index]&255;if((header.getInt(13)&(1<<section))!=0){int hi=count*12288+Integer.bitCount(header.getInt(13)&((1<<section)-1))*2048+(index>>1);baseId|=((raw[hi]>>>((index&1)*4))&15)<<8;}}if(edit==null||edit.id()!=0&&edit.id()==baseId)kept.put(tile.cell(),tile);}for(var change:overlay.tiles().entrySet()){
            int cell=change.getKey();if(cell<0||cell>65535)throw new IllegalArgumentException("projection_tile_cell_bounds");TilePayload.Tile tile=change.getValue();
            if(tile==null){kept.remove(cell);continue;}
            if(tile.cell()!=cell||Math.floorDiv(tile.x(),16)!=header.getInt(0)||Math.floorDiv(tile.z(),16)!=header.getInt(4))throw new IllegalArgumentException("projection_tile_position_mismatch");
            int section=cell>>>12,index=cell&4095;if(lows[section]==null||((lows[section][index]&255)|(nibble(high[section],index)<<8))==0)throw new IllegalArgumentException("projection_tile_in_air");kept.put(cell,tile);
        }if(kept.size()>TilePayload.MAX_TILES)throw new IllegalArgumentException("tile_count_limit");out.write(TilePayload.encode(List.copyOf(kept.values())));});
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(Arrays.copyOfRange(base,17,33));for(int cell:new TreeSet<>(overlay.cells().keySet())){var voxel=overlay.cells().get(cell);digest.update(ByteBuffer.allocate(7).putInt(cell).putShort((short)voxel.id()).put((byte)voxel.metadata()).array());}for(int cell:new TreeSet<>(overlay.tiles().keySet())){digest.update(ByteBuffer.allocate(4).putInt(cell).array());TilePayload.Tile tile=overlay.tiles().get(cell);digest.update((byte)(tile==null?0:1));if(tile!=null){digest.update(tile.record());if(tile.completeStateSize()>0)digest.update(tile.completeState());}}if(!overlay.scenes().isEmpty())digest.update(sideChannels);UUID revision=UUID.nameUUIDFromBytes(digest.digest());byte[] encoded=Zstd.compress(geometry,3);
        int x=header.getInt(0),z=header.getInt(4);return Wire.bytes(out->{out.writeInt(x);out.writeInt(z);out.writeBoolean(true);out.writeInt(primary);out.writeInt(additional);out.writeLong(revision.getMostSignificantBits());out.writeLong(revision.getLeastSignificantBits());out.writeInt(arrays);out.writeInt(encoded.length);out.write(encoded);out.write(sideChannels);});
    }
    public Map<String,Object> metrics(){synchronized(cache){var view=new LinkedHashMap<String,Object>(Map.of("cached",cache.size(),"capacity",capacity,"cachedBytes",cachedBytes,"byteLimit",CACHE_BYTES,"loading",loading.size(),"queued",worker.getQueue().size(),"builds",builds.get(),"errors",errors.get(),"rejected",rejected.get(),"deltaReads",deltaReads.get()));view.put("lastError",lastError);return Collections.unmodifiableMap(view);}}
    @Override public void close()throws InterruptedException{Workers.stop(worker);}
}
