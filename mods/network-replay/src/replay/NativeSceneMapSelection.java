package replay;

import java.io.*;
import java.util.*;

/** Selected map geometry owns its registries and spatial trees. No chunk/prefab admission,
 * component activation, scripts, world ticks or packet dispatch is involved. */
final class NativeSceneMapSelection implements AutoCloseable {
    final Object world,provider,emptyChunk;
    final SceneMapPreparation preparation;
    final SceneMapAssets assets=new SceneMapAssets();
    SceneNativeMapViews views;
    boolean prepareViews(SceneTracks tracks,long deadline)throws Exception {if(views==null)views=new SceneNativeMapViews(this,tracks);return views.step(deadline);}
    void catalogue(SceneMapCatalog.Prepared value)throws Exception {
        if(value.type().equals("world-map-chunk")){
            preparation.index.coordinates.put(value.key(),(SceneMapIndex.Coordinate)value.coordinate());
            preparation.index.decoded.put(value.value(),value.chunk());resolveChunk(value.chunk());
        }else{
            preparation.index.ids.put(value.key(),(UUID)value.coordinate());preparation.payloads.put(value.value(),value.payload());
            Object prefab=resolvePrefab((UUID)value.coordinate(),value.value(),value.payload());
            if(appearanceInitialized.add(prefab))Native.call(prefab,"const","void");
            assets.prefab(prefab);
        }
    }
    static final class Pending extends Exception {final String phase;Pending(){this("map-data");}Pending(String phase){super(null,null,false,false);this.phase=phase;}}
    final NativeSceneTerrainSelection terrain;
    final IdentityHashMap<SceneChunkCodec.Chunk,Object> chunks=new IdentityHashMap<>();
    final IdentityHashMap<SceneTracks.Bytes,Object> prefabs=new IdentityHashMap<>();
    final Set<Object> initialized=Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Object> appearanceInitialized=Collections.newSetFromMap(new IdentityHashMap<>());
    SceneDomainMap<UUID,Object> visible=new SceneDomainMap<>();Object manager;
    SceneDomainMap<Object,Boolean> objectMembership=SceneDomainMap.identityKeys();
    SceneMapIndex.Selection previous;SceneDomainMap<SceneMapIndex.Coordinate,Object> visibleChunks=new SceneDomainMap<>();Set<Object> visibleObjects=Set.of();
    record Prepared(SceneMapIndex.Selection source,SceneDomainMap<SceneMapIndex.Coordinate,Object> baseChunks,SceneDomainMap<UUID,Object> basePrefabs,SceneDomainMap<SceneMapIndex.Coordinate,Object> chunks,SceneDomainMap<UUID,Object> prefabs,List<SceneDomainMap.Change<UUID,Object>> changes){}
    Prepared pending;
    SceneNativeMapChanges pendingChanges;
    long selections,rebuilds,prepared,registryAdds,registryRemoves;
    final boolean profilePhases=Boolean.getBoolean("sz.replay.qa.scene-native-phases");Map<String,Long> lastPhases=Map.of();
    NativeSceneMapSelection(Object world)throws Exception {
        this.world=world;provider=Native.call(world,"short","1554765982");emptyChunk=Native.field(provider,"catch");terrain=new NativeSceneTerrainSelection(world);preparation=new SceneMapPreparation();
    }
    void prefetch(long time,Map<SceneTracks.ObjectId,SceneTracks.ObjectState> rows,long deadline)throws Exception {
        var selected=preparation.request(time,rows,false);if(selected!=null)terrain.prefetchDecoded(selected.selection().terrain(),deadline);
    }
    void prepare(SceneTracks.Frame selected,NativeScenePresenter.Frame candidate)throws Exception {
        long began=profilePhases?System.nanoTime():0;
        if(views!=null&&views.ready){
            var view=views.select(selected.indexedMapObjects());
            var decoded=preparation.request(selected,true);if(decoded==null)throw new Pending();
            terrain.prepare(decoded.selection(),candidate);
            manager=view.manager();visibleChunks=view.chunks();visible=view.prefabs();visibleObjects=view.objects();
            candidate.mapSpatial=view.spatial();compose(candidate);selections++;
            if(profilePhases)lastPhases=Map.of("index",0L,"values",0L,"registry",0L,"terrain",System.nanoTime()-began);
            return;
        }
        var decoded=preparation.request(selected,true);if(decoded==null)throw new Pending();
        SceneMapIndex.Selection values=decoded.selection();
        long indexing=profilePhases?System.nanoTime()-began:0;if(profilePhases)began=System.nanoTime();
        if(previous!=null&&values.chunks()==previous.chunks()&&values.prefabs()==previous.prefabs()){
            terrain.prepare(values,candidate);compose(candidate);selections++;if(profilePhases)lastPhases=Map.of("index",indexing,"values",0L,"registry",0L,"terrain",System.nanoTime()-began);return;
        }
        Prepared preparedValues=pending;
        if(preparedValues==null||preparedValues.baseChunks()!=visibleChunks||preparedValues.basePrefabs()!=visible||preparedValues.source().chunks()!=values.chunks()||preparedValues.source().prefabs()!=values.prefabs()){
            if(pendingChanges==null||!pendingChanges.matches(values,visibleChunks,visible))pendingChanges=new SceneNativeMapChanges(previous,decoded,visibleChunks,visible);
            if(!pendingChanges.advance(new SceneNativeMapChanges.Factory(){
                public Object chunk(SceneChunkCodec.Chunk value)throws Exception{return resolveChunk(value);}
                public Object prefab(UUID id,SceneTracks.Bytes value,byte[] payload)throws Exception{return resolvePrefab(id,value,payload);}
            },System.nanoTime()+2_000_000L,System::nanoTime))throw new Pending("map-objects");
            var selectedChunks=pendingChanges.chunks.finish();var selectedPrefabs=pendingChanges.prefabs.finish();
            preparedValues=pending=new Prepared(values,visibleChunks,visible,selectedChunks,selectedPrefabs,SceneDomainMap.changes(visible,selectedPrefabs));pendingChanges=null;
        }
        var selectedChunks=preparedValues.chunks();var selectedPrefabs=preparedValues.prefabs();var nativeChanges=preparedValues.changes();
        long valuePreparation=profilePhases?System.nanoTime()-began:0;if(profilePhases)began=System.nanoTime();
        // Upload before changing any visible spatial registry. A bounded upload
        // can leave this candidate pending while the old scene remains intact.
        terrain.prepare(values,candidate);
        Object selectedManager=manager;
        if(selectedManager==null||!nativeChanges.isEmpty()){
            if(selectedManager==null){selectedManager=Native.type("493563411").getConstructor(Native.type("1500514786")).newInstance(world);rebuilds++;}
            Map<UUID,Object> registry=(Map<UUID,Object>)Native.call(selectedManager,"long","java.util.Map");
            var membership=objectMembership.edit();
            for(var change:nativeChanges)if(change.before()!=null){
                Native.call(selectedManager,"try","void",change.before());registry.remove(change.key());membership.remove(change.before());registryRemoves++;
            }
            for(var change:nativeChanges)if(change.after()!=null){
                Object prefab=change.after();SourceAdapters.put(prefab,"l",Native.call(selectedManager,"continue","334744794",prefab));
                Native.call(selectedManager,"class","void",prefab,Native.call(prefab,"char","java.util.List"));
                // Appearance/light constructors, without activation or network callbacks.
                if(initialized.add(prefab))Native.call(prefab,"int","void");
                if(appearanceInitialized.add(prefab))Native.call(prefab,"const","void");
                registry.put(change.key(),prefab);membership.put(prefab,Boolean.TRUE);registryAdds++;
            }
            manager=selectedManager;visible=selectedPrefabs;objectMembership=membership.finish();
            var selectedMembership=objectMembership;
            visibleObjects=Collections.unmodifiableSet(new AbstractSet<>(){
                public int size(){return selectedMembership.size();}
                public boolean contains(Object value){return selectedMembership.containsKey(value);}
                public Iterator<Object> iterator(){return selectedMembership.keySet().iterator();}
            });
        }
        visibleChunks=selectedChunks;previous=values;pending=null;
        long registry=profilePhases?System.nanoTime()-began:0;if(profilePhases)began=System.nanoTime();
        compose(candidate);selections++;
        if(profilePhases)lastPhases=Map.of("index",indexing,"values",valuePreparation,"registry",registry,"terrain",System.nanoTime()-began);
    }
    Object resolveChunk(SceneChunkCodec.Chunk value)throws Exception {
        Object chunk=chunks.get(value);if(chunk==null){chunk=SceneChunkCodec.create(world,value);chunks.put(value,chunk);}return chunk;
    }
    Object resolvePrefab(UUID id,SceneTracks.Bytes value,byte[] payload)throws Exception {
        Object prefab=prefabs.get(value);if(prefab!=null)return prefab;
        prefab=Native.call(Native.type("1886271463"),"public","1886271463",SceneChunkCodec.tag(payload));
        if(prefab==null)throw new IOException("Selected prefab definition unavailable: "+id);
        if(!id.equals(Native.call(prefab,"const","java.util.UUID")))throw new IOException("Selected prefab UUID changed");
        Native.call(prefab,"break","void");SourceAdapters.put(prefab,"q",world);
        SourceAdapters.put(prefab,"try",Native.call(Native.type("1765591907"),"synchronized","float",Native.field(prefab,"m")));
        prefabs.put(value,prefab);prepared++;return prefab;
    }
    void compose(NativeScenePresenter.Frame candidate){candidate.chunkProvider=provider;candidate.emptyChunk=emptyChunk;candidate.chunks=visibleChunks;candidate.mapWorld=world;candidate.mapManager=manager;candidate.mapObjects=visibleObjects;}
    Map<String,Object> diagnostic(){return Map.of("selections",selections,"manager_rebuilds",rebuilds,"registry_adds",registryAdds,"registry_removes",registryRemoves,"prepared_prefab_versions",prepared,"selected_prefabs",visible.size(),"native_world_admission",false,"packet_prefix_replayed",false,"terrain",terrain.diagnostic());}
    static boolean sameIdentities(Map<UUID,Object> before,Map<UUID,Object> after){if(before==after)return true;if(before.size()!=after.size())return false;for(var entry:before.entrySet())if(after.get(entry.getKey())!=entry.getValue())return false;return true;}
    public void close()throws Exception {try{preparation.close();}finally{try{if(views!=null)views.close();assets.close();}finally{try{terrain.close();}finally{chunks.clear();prefabs.clear();initialized.clear();appearanceInitialized.clear();visible=new SceneDomainMap<>();visibleChunks=new SceneDomainMap<>();objectMembership=SceneDomainMap.identityKeys();visibleObjects=Set.of();previous=null;pending=null;pendingChanges=null;manager=null;views=null;}}}}
}
