package replay;
import java.util.*;import java.util.function.LongSupplier;

/** Quarantined native map construction can resume without restarting a large change set. */
final class SceneNativeMapChanges {
    interface Factory {
        Object chunk(SceneChunkCodec.Chunk value)throws Exception;
        Object prefab(UUID identity,SceneTracks.Bytes value,byte[] payload)throws Exception;
    }
    final SceneMapIndex.Selection source;
    final SceneDomainMap<SceneMapIndex.Coordinate,Object> baseChunks;
    final SceneDomainMap<UUID,Object> basePrefabs;
    final SceneDomainMap.Builder<SceneMapIndex.Coordinate,Object> chunks;
    final SceneDomainMap.Builder<UUID,Object> prefabs;
    final Map<SceneTracks.Bytes,byte[]> payloads;
    final List<SceneDomainMap.Change<SceneMapIndex.Coordinate,SceneChunkCodec.Chunk>> chunkChanges;
    final List<SceneDomainMap.Change<UUID,SceneTracks.Bytes>> prefabChanges;
    int chunkCursor,prefabCursor;
    SceneNativeMapChanges(SceneMapIndex.Selection previous,SceneMapPreparation.Prepared prepared,SceneDomainMap<SceneMapIndex.Coordinate,Object> chunks,SceneDomainMap<UUID,Object> prefabs){
        source=prepared.selection();payloads=prepared.prefabPayloads();baseChunks=chunks;basePrefabs=prefabs;this.chunks=chunks.edit();this.prefabs=prefabs.edit();
        chunkChanges=SceneDomainMap.changes(previous==null?Map.of():previous.chunks(),source.chunks());
        prefabChanges=SceneDomainMap.changes(previous==null?Map.of():previous.prefabs(),source.prefabs());
    }
    boolean matches(SceneMapIndex.Selection values,SceneDomainMap<SceneMapIndex.Coordinate,Object> chunks,SceneDomainMap<UUID,Object> prefabs){return source.chunks()==values.chunks()&&source.prefabs()==values.prefabs()&&baseChunks==chunks&&basePrefabs==prefabs;}
    boolean advance(Factory factory,long deadline,LongSupplier clock)throws Exception {
        while(chunkCursor<chunkChanges.size()){
            if(clock.getAsLong()>=deadline)return false;var change=chunkChanges.get(chunkCursor);
            if(change.after()==null)chunks.remove(change.key());else chunks.put(change.key(),Objects.requireNonNull(factory.chunk(change.after())));
            chunkCursor++;
        }
        while(prefabCursor<prefabChanges.size()){
            if(clock.getAsLong()>=deadline)return false;var change=prefabChanges.get(prefabCursor);
            if(change.after()==null)prefabs.remove(change.key());else{
                byte[] payload=payloads.get(change.after());if(payload==null)throw new IllegalStateException("Missing prepared prefab payload");
                prefabs.put(change.key(),Objects.requireNonNull(factory.prefab(change.key(),change.after(),payload)));
            }
            prefabCursor++;
        }
        return true;
    }
}
