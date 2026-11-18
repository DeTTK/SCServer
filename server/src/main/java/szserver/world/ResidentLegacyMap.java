package szserver.world;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Offline/startup handoff: static native map stays the base; only old changed chunks are imported. */
public final class ResidentLegacyMap implements RegionSnapshotStore.Base {
    private record Frozen(RegionTerrain terrain,NativeMapArchive.Source source,BlockOverlays overlays,BlockOverlays.Capture liveGuard){}
    private final WorldLoop world;
    private final Frozen frozen;
    private final NativeMapArchive.Source original;
    private final List<Long> changed;
    private final String sourceBinding,binding;
    private static final BlockOverlays.Snapshot EMPTY=new BlockOverlays.Snapshot(0,Map.of());

    /** Call after old checkpoint/journal recovery, before starting any simulation/listener/commit policy. */
    public static ResidentLegacyMap freeze(WorldLoop world)throws Exception{
        NativeTileStates.worker();Frozen frozen=world.bootstrap(w->{if(!(w.checkpointTerrain() instanceof RegionTerrain terrain))throw new IllegalArgumentException("resident_import_requires_region_map");return new Frozen(terrain,terrain.archiveSource(),w.overlayVersions().detached(),w.overlayVersions().capture());});
        return new ResidentLegacyMap(world,frozen);
    }
    private ResidentLegacyMap(WorldLoop world,Frozen frozen)throws Exception{
        this.world=world;this.frozen=frozen;original=frozen.source().original();sourceBinding=frozen.terrain().checkpointBinding();if(sourceBinding==null)throw new IOException("resident_import_unbound_source");
        if(!frozen.source().committed.generation().equals(frozen.terrain().liveCommits().generation()))throw new IOException("resident_import_native_handoff_pending");
        Set<Long> keys=new HashSet<>(frozen.source().committed.revisions().keySet());for(var chunk:frozen.overlays().checkpointChunks())keys.add(chunk.key());changed=keys.stream().sorted().toList();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
            out.writeUTF("resident-legacy-map-v1");out.writeUTF(sourceBinding);out.writeUTF(frozen.source().committed.generation());
            for(long key:changed){out.writeLong(key);var overlay=frozen.overlays().snapshot((int)(key>>32),(int)key);out.writeLong(overlay.revision());
                out.writeInt(overlay.cells().size());for(int cell:new TreeSet<>(overlay.cells().keySet())){var voxel=overlay.cells().get(cell);out.writeInt(cell);out.writeInt(voxel.id());out.writeInt(voxel.metadata());out.writeLong(voxel.revision());}
                out.writeInt(overlay.tiles().size());for(int cell:new TreeSet<>(overlay.tiles().keySet())){out.writeInt(cell);var tile=overlay.tiles().get(cell);out.writeBoolean(tile!=null);if(tile!=null){bytes(out,tile.record());bytes(out,tile.completeStateSize()==0?new byte[0]:tile.completeState());}}
                out.writeInt(overlay.scenes().size());for(UUID id:new TreeSet<>(overlay.scenes().keySet())){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());var scene=overlay.scenes().get(id);out.writeInt(scene==null?-1:scene.occurrences().size());if(scene!=null)for(var occurrence:scene.occurrences()){out.writeBoolean(occurrence.container());bytes(out,occurrence.object().record());}}
            }
        }binding="resident-legacy-map-v1:"+HexFormat.of().formatHex(digest.digest());verifySource();
    }
    private static void bytes(DataOutputStream out,byte[] data)throws IOException{out.writeInt(data.length);out.write(data);}
    public String binding(){return binding;}
    public int changedChunks(){return changed.size();}
    /** Only unchanged authored terrain is consulted after migration, never the legacy mutation layer. */
    @Override public ResidentChunks.Saved load(ResidentChunks.Key key)throws Exception{return original.resident(key,EMPTY);}
    /** Does not retain the old overlay root/history after the migration tool is released. */
    public RegionSnapshotStore.Base originalBase(){var source=original;return key->source.resident(key,EMPTY);}
    public static RegionSnapshotStore.Base originalBase(RegionTerrain terrain){var source=terrain.archiveSource().original();return key->source.resident(key,EMPTY);}
    private void verifySource()throws IOException{if(!world.beforeStart())throw new IOException("resident_import_after_world_start");try{frozen.liveGuard().checkCurrent();}catch(IllegalArgumentException changed){throw new IOException("resident_import_world_changed",changed);}if(!sourceBinding.equals(frozen.terrain().checkpointBinding()))throw new IOException("resident_import_source_changed");}
    public void verifyOffline()throws IOException{verifySource();}
    /** Bounded batches are retryable while the new server remains offline; caller publishes cutover only after ALL state/history is ready. */
    public Map<String,Object> copyChanges(RegionSnapshotStore target,int batchBytes,Consumer<Map<String,Object>> progress)throws Exception{
        NativeTileStates.worker();Objects.requireNonNull(progress);if(batchBytes<1||batchBytes>64*1024*1024)throw new IllegalArgumentException("resident_import_batch_bytes");verifySource();
        if(!binding.equals(target.worldBinding()))throw new IOException("resident_import_target_binding");
        List<ResidentChunks.Write> batch=new ArrayList<>();long retained=0,processed=0,written=0,skipped=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(long raw:changed){var key=new ResidentChunks.Key((int)(raw>>32),(int)raw);var overlay=frozen.overlays().snapshot(key.x(),key.z());var saved=frozen.source().resident(key,overlay);if(saved.revision()<1)throw new IOException("resident_import_missing_changed_revision");byte[] data=saved.bytes();
            digest.update(java.nio.ByteBuffer.allocate(20).putInt(key.x()).putInt(key.z()).putLong(saved.revision()).putInt(data.length).array());digest.update(data);
            var existing=target.load(key);if(existing.revision()==saved.revision()){if(!Arrays.equals(existing.bytes(),data))throw new IOException("resident_import_existing_payload_mismatch");skipped++;}
            else{if(existing.revision()!=0)throw new IOException("resident_import_existing_revision_conflict");if(!batch.isEmpty()&&retained+data.length>batchBytes){target.save(batch);written+=batch.size();batch.clear();retained=0;}batch.add(new ResidentChunks.Write(key,saved.revision(),data));retained+=data.length;}
            processed++;if(processed%64==0)progress.accept(Map.of("processed",processed,"total",changed.size(),"written",written,"skipped",skipped));
        }
        if(!batch.isEmpty()){target.save(batch);written+=batch.size();}verifySource();
        Map<String,Object> result=Map.of("status","CHUNKS_COPIED_NOT_CUTOVER","binding",binding,"chunks",processed,"written",written,"skipped",skipped,"payloadSha256",HexFormat.of().formatHex(digest.digest()));progress.accept(result);return result;
    }
}
