package replay;

import java.io.*;
import java.util.*;

/** Proves selected values survive canonical reconstruction. GPU map publication is separate. */
final class NativeSceneMapValuesQa implements AutoCloseable {
    final Object world;final SceneMapIndex index=new SceneMapIndex();
    final IdentityHashMap<SceneChunkCodec.Chunk,Object> chunks=new IdentityHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,Object> prefabs=new IdentityHashMap<>();
    final List<Object> originalChunks;final Map<UUID,Object> originalPrefabs;
    long selections,chunkChecks,prefabChecks;
    long queryPublications;
    final NativeSceneMapSelection models;
    final boolean playbackProfile=Boolean.getBoolean("sz.replay.qa.scene-playback-profile");
    Map<String,Object> finalDiagnostic;
    Map<SceneMapIndex.Coordinate,Object> selectedChunks=Map.of();
    NativeSceneMapValuesQa(Object world)throws Exception {
        this.world=world;originalChunks=List.copyOf((Collection<?>)Native.call(Native.field(Native.call(world,"short","1554765982"),"private"),"do","1018845839"));
        originalPrefabs=new HashMap<>((Map<UUID,Object>)Native.call(Native.call(Native.type("493563411"),"try","493563411",world),"long","java.util.Map"));
        models=Boolean.getBoolean("sz.replay.qa.scene-map-models")?new NativeSceneMapSelection(world):null;
    }
    void select(SceneTracks.Frame frame,NativeScenePresenter.Frame candidateFrame)throws Exception {
        if(playbackProfile){
            if(models==null)throw new IllegalStateException("Scene playback profile requires the native map renderer");
            models.prepare(frame,candidateFrame);selectedChunks=candidateFrame.chunks;
            assertOriginal();selections++;return;
        }
        SceneMapIndex.Selection selected=index.select(frame);
        Map<SceneMapIndex.Coordinate,Object> candidate=new TreeMap<>();
        for(var entry:selected.chunks().entrySet()){
            SceneChunkCodec.Chunk value=entry.getValue();Object nativeValue=chunks.get(value);
            if(nativeValue==null){nativeValue=SceneChunkCodec.create(world,value);chunks.put(value,nativeValue);}
            if(!SceneChunkCodec.capture(nativeValue).equals(SceneChunkCodec.encode(value)))throw new IOException("Canonical selected chunk differs: "+entry.getKey());chunkChecks++;
            candidate.put(entry.getKey(),nativeValue);
        }
        for(var entry:selected.prefabs().entrySet()){
            SceneTracks.Bytes value=entry.getValue();Object nativeValue=prefabs.get(value);
            if(nativeValue==null){nativeValue=Native.call(Native.type("1886271463"),"public","1886271463",SceneChunkCodec.tag(value.data()));if(nativeValue==null)throw new IOException("Selected prefab definition unavailable");prefabs.put(value,nativeValue);}
            if(!entry.getKey().equals(Native.call(nativeValue,"const","java.util.UUID")))throw new IOException("Selected prefab UUID changed");
            Object tag=Native.type("98008865").getConstructor().newInstance();Native.call(Native.type("1886271463"),"boolean","98008865",Native.field(Native.type("1886848284"),"FULL_SAVE"),nativeValue,tag);
            if(!new SceneTracks.Bytes(SceneChunkCodec.tag(tag)).equals(value))throw new IOException("Canonical prefab reconstruction differs: "+entry.getKey());prefabChecks++;
        }
        assertOriginal();selectedChunks=Map.copyOf(candidate);selections++;
        if(models!=null)models.prepare(frame,candidateFrame);
        candidateFrame.chunkProvider=Native.call(world,"short","1554765982");candidateFrame.emptyChunk=Native.field(candidateFrame.chunkProvider,"catch");candidateFrame.chunks=selectedChunks;
    }
    void publishQueries(NativeScenePresenter.Frame frame)throws Exception {
        if(frame.chunks!=selectedChunks)throw new IOException("Map query frame was not composed before publication");
        for(var entry:selectedChunks.entrySet())if(Native.call(frame.chunkProvider,"abstract","812508934",entry.getKey().x(),entry.getKey().z())!=entry.getValue())throw new IOException("Native map query did not select recorded chunk");
        Set<SceneMapIndex.Coordinate> absent=new HashSet<>();for(Object chunk:originalChunks){var coordinate=new SceneMapIndex.Coordinate(((Number)Native.field(chunk,"transient")).intValue(),((Number)Native.field(chunk,"float")).intValue());if(!selectedChunks.containsKey(coordinate))absent.add(coordinate);}
        for(var coordinate:absent)if(Native.call(frame.chunkProvider,"abstract","812508934",coordinate.x(),coordinate.z())!=frame.emptyChunk)throw new IOException("Unrecorded chunk leaked into selected queries");
        for(var entry:selectedChunks.entrySet())if(Native.call(world,"else","812508934",entry.getKey().x(),entry.getKey().z())!=entry.getValue())throw new IOException("Native world chunk lookup bypassed selected map");
        queryPublications++;
    }
    void assertOriginal()throws Exception {
        Collection<?> actual=(Collection<?>)Native.call(Native.field(Native.call(world,"short","1554765982"),"private"),"do","1018845839");Set<Object> identity=Collections.newSetFromMap(new IdentityHashMap<>());identity.addAll(actual);if(identity.size()!=originalChunks.size()||!identity.containsAll(originalChunks))throw new IOException("Map value preparation admitted a native chunk");
        Map<?,?> actualPrefabs=(Map<?,?>)Native.call(Native.call(world,"szreplay$original$do","493563411"),"long","java.util.Map");if(actualPrefabs.size()!=originalPrefabs.size())throw new IOException("Map value preparation admitted a prefab");for(var entry:originalPrefabs.entrySet())if(actualPrefabs.get(entry.getKey())!=entry.getValue())throw new IOException("Map preparation replaced live prefab");
    }
    Map<String,Object> diagnostic(){if(finalDiagnostic!=null)return finalDiagnostic;Map<String,Object> result=new TreeMap<>(Map.of("selected_frames",selections,"canonical_chunk_checks",chunkChecks,"canonical_prefab_checks",prefabChecks,"prepared_chunk_versions",chunks.size(),"prepared_prefab_versions",prefabs.size(),"native_world_admission",false,"packet_prefix_replayed",false,"native_query_publications",queryPublications,"gpu_map_publication_verified",false));result.put("playback_profile",playbackProfile);if(models!=null)result.put("selected_geometry",models.diagnostic());return result;}
    public void close()throws Exception {if(finalDiagnostic!=null)return;assertOriginal();finalDiagnostic=Map.copyOf(diagnostic());try{if(models!=null)models.close();}finally{chunks.clear();prefabs.clear();selectedChunks=Map.of();}}
}

