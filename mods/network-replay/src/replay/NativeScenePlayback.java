package replay;

import java.util.*;

/** User-visible preview of the indexed domains. Never labels partial recordings complete. */
final class NativeScenePlayback implements Timeline.Sink,AutoCloseable {
    final SceneTracks tracks;final NativeSceneSelection actors;final NativeSceneMapSelection map;
    final NativeSceneAudio audio;final NativePackets.World world;long selections;boolean closed;
    NativeSceneEffects effects;
    NativeSceneBlood blood;boolean bloodReady;
    long actorWaits,terrainWaits,effectWaits,archiveWaits;String waiting="";
    long mapDataWaits;
    long pagePrefetchTime=Long.MIN_VALUE;double pagePrefetchSpeed;
    long actorPrefetchTime=Long.MIN_VALUE;SceneTracks.Frame actorPrefetch;
    final Set<SceneTracks.ObjectId> recorderIds=new HashSet<>();boolean cameraPlaced;
    final SceneRecorderMotion recorderMotion;
    SceneTracks.Frame selectedFrame;NativeReplayFp firstPerson=new NativeReplayFp(this);
    SceneFpCatalog fpCatalog;boolean fpReady;
    int nextFpId=-1_900_000_000;
    int fpRuntimeId(){if(nextFpId==Integer.MIN_VALUE)throw new IllegalStateException("FP runtime IDs exhausted");return nextFpId--;}
    SceneTracks.Frame pendingFrame;
    SceneActorCatalog actorCatalog;
    SceneTerrainCatalog terrainCatalog;
    SceneMapCatalog mapCatalog;boolean mapAssetsReady,mapIndexReady,mapViewsReady,actorAssetsReady,effectsReady;
    Exception resourceFailure;
    final SceneResidentValues residentValues;
    boolean preparingResources(){return !fpReady||!bloodReady||!residentValues.ready||effects!=null&&!effectsReady||actorCatalog!=null&&(!actorCatalog.ready||!actorAssetsReady)||terrainCatalog!=null&&!terrainCatalog.ready||mapCatalog!=null&&(!mapCatalog.ready||!mapAssetsReady||!mapIndexReady||!mapViewsReady);}
    String resourceStatus(){return fpCatalog!=null&&!fpReady?"Подготовка вида от первого лица…":actorCatalog!=null&&(!actorCatalog.ready||!actorAssetsReady)?"Загрузка персонажей записи…":terrainCatalog!=null&&!terrainCatalog.ready?"Загрузка геометрии записи…":"Загрузка объектов карты…";}
    boolean resourcesReady()throws Exception {
        if(resourceFailure!=null)throw resourceFailure;
        try{
        boolean valuesReady=residentValues.prepare();
        effectsReady=effects==null||effects.prepareHistory();
        long deadline=System.nanoTime()+3_000_000L;
        boolean actorsReady=actorCatalog==null||actorCatalog.step(actors::catalogue,deadline,System::nanoTime);
        actorAssetsReady=actorCatalog==null||actorsReady&&actors.assets.ready(deadline,System::nanoTime);
        if(actorsReady&&actorAssetsReady){if(blood==null)blood=new NativeSceneBlood(this);bloodReady=blood.prepare(System.nanoTime()+2_000_000L);}
        boolean terrainReady=terrainCatalog==null||terrainCatalog.step(map.terrain::catalogue,deadline,System::nanoTime);
        boolean mapReady=mapCatalog==null||mapCatalog.step(map::catalogue,deadline,System::nanoTime);
        mapIndexReady=map==null||mapReady&&map.preparation.completeIndex(tracks);
        mapViewsReady=map==null||terrainReady&&mapIndexReady&&map.prepareViews(tracks,deadline);
        mapAssetsReady=map==null||mapReady&&map.assets.ready(deadline,System::nanoTime);
        if(valuesReady&&actorsReady&&actorAssetsReady&&terrainReady&&mapReady&&mapAssetsReady&&mapIndexReady&&mapViewsReady&&effectsReady){if(fpCatalog==null)fpCatalog=new SceneFpCatalog(this);fpReady=fpCatalog.step(System.nanoTime()+2_000_000L);}
        return fpReady&&bloodReady&&valuesReady&&effectsReady&&actorsReady&&actorAssetsReady&&terrainReady&&mapReady&&mapAssetsReady&&mapIndexReady&&mapViewsReady;
        }catch(Exception failure){resourceFailure=failure;throw failure;}
    }
    NativeScenePlayback(SceneTracks tracks,NativePackets.World world,NativeSceneAudio audio)throws Exception {
        this.tracks=tracks;this.world=world;this.audio=audio;residentValues=new SceneResidentValues(tracks);recorderMotion=new SceneRecorderMotion(tracks,world.packets.demo);actors=new NativeSceneSelection(world);actors.equipmentVariants=new SceneEquipmentVariants(tracks);
        for(var object:tracks.objects)if(object.type().equals("961798934")||object.channels().containsKey("recorder-template"))recorderIds.add(object.id());
        NativeSceneMapSelection candidate=null;
        try{Set<String> physical=new HashSet<>();for(var object:tracks.objects)if(!object.type().startsWith("world-")&&Native.type("544082630").isAssignableFrom(Native.type(object.type())))physical.add(object.type());if(tracks.objects.stream().anyMatch(o->o.type().startsWith("world-map-"))){candidate=new NativeSceneMapSelection(actors.world);candidate.terrain.incremental=true;terrainCatalog=new SceneTerrainCatalog(tracks);mapCatalog=new SceneMapCatalog(tracks);}actorCatalog=new SceneActorCatalog(tracks,physical);}catch(Exception e){if(mapCatalog!=null)mapCatalog.close();if(terrainCatalog!=null)terrainCatalog.close();if(candidate!=null)candidate.close();actors.close();throw e;}map=candidate;
    }
    public boolean directTime(){return true;}
    void prefetch(long target,double speed)throws Exception {
        ReplayPerformance.enter("scene-prefetch");try{
        if(pagePrefetchTime==Long.MIN_VALUE||Math.abs(target-pagePrefetchTime)>=50_000_000L||speed!=pagePrefetchSpeed){tracks.prepare(target,(long)Math.copySign(250_000_000L*Math.max(1,Math.min(4,Math.abs(speed))),speed),false);pagePrefetchTime=target;pagePrefetchSpeed=speed;}
        if(effects!=null)effects.prefetch(target,speed);
        long actorTime=Math.max(0,Math.min(tracks.durationNs,target+(long)Math.copySign(500_000_000L*Math.max(1,Math.min(4,Math.abs(speed))),speed)));
        if(actorPrefetchTime==Long.MIN_VALUE||Math.abs(actorTime-actorPrefetchTime)>=100_000_000L){actorPrefetchTime=actorTime;actorPrefetch=null;}
        if(actorPrefetch==null&&tracks.prepareResources(actorPrefetchTime))actorPrefetch=recorderMotion.sample(tracks.sampleResources(actorPrefetchTime));
        if(actorPrefetch!=null)actors.prefetch(actorPrefetch,System.nanoTime()+500_000L);
        if(map==null)return;
        long horizon=(long)Math.copySign(500_000_000L*Math.max(1,Math.abs(speed)),speed);
        long next=Math.max(0,Math.min(tracks.durationNs,target+horizon));
        map.prefetch(next,tracks.mapRowsAt(next),System.nanoTime()+2_000_000L);
        }finally{ReplayPerformance.exit("scene-prefetch");}
    }
    public boolean presentTime(long time)throws Exception {
        if(closed)throw new IllegalStateException("Scene playback closed");
        waiting="";
        if(pendingFrame!=null&&pendingFrame.timeNs()!=time){pendingFrame=null;actors.cancelPreparation();}
        if(pendingFrame==null&&!tracks.prepare(time,0,true)){archiveWaits++;waiting="archive";return false;}
        try{if(effects!=null)effects.ensureReady(time);}catch(NativeSceneEffects.Pending pending){effectWaits++;waiting="effects";return false;}
        SceneTracks.Frame sampled=pendingFrame;ReplayPerformance.enter("scene-sample");try{if(sampled==null)pendingFrame=sampled=recorderMotion.sample(tracks.sample(time));}finally{ReplayPerformance.exit("scene-sample");}
        ReplayPerformance.enter("scene-publish");try{if(!actors.present(sampled,(s,c)->{ReplayPerformance.enter("scene-map");try{if(map!=null)map.prepare(s,c);if(effects!=null)effects.present(time);}finally{ReplayPerformance.exit("scene-map");}})){actorWaits++;waiting="actors";return false;}}catch(NativeSceneMapSelection.Pending pending){mapDataWaits++;waiting=pending.phase;return false;}catch(NativeSceneTerrainSelection.Pending pending){terrainWaits++;waiting="terrain";return false;}catch(NativeSceneEffects.Pending pending){effectWaits++;waiting="effects";return false;}finally{ReplayPerformance.exit("scene-publish");}
        if(blood!=null)blood.present(time);selectedFrame=sampled;pendingFrame=null;selections++;return true;
    }
    Object recorder(){for(var id:recorderIds){var actor=actors.pool.selected.get(id);if(actor!=null)return actor.entity;}return null;}
    void camera(FreeCamera camera)throws Exception {Object next=recorder();if(!cameraPlaced&&next!=null){camera.visit(next);cameraPlaced=true;}}
    String limitations(){return effects==null?"У этой записи нет индекса эффектов. Часть звуков пока не поддерживает обратное проигрывание.":"Часть звуков пока не поддерживает обратное проигрывание.";}
    Map<String,Object> diagnostic(){var state=new TreeMap<String,Object>();state.put("mode","indexed-preview");state.put("complete",tracks.complete());state.put("coverage",tracks.coverage);state.put("scene_objects",tracks.objects.size());state.put("direct_selections",selections);state.put("waiting",waiting);state.put("waits",Map.of("actors",actorWaits,"terrain",terrainWaits,"effects",effectWaits,"archive",archiveWaits,"map_data",mapDataWaits));state.put("actors",actors.diagnostic());if(map!=null)state.put("map",map.diagnostic());if(audio!=null)state.put("audio",audio.diagnostic());if(blood!=null)state.put("blood",blood.diagnostic());if(tracks.storage instanceof ScenePagedArchive.Store store)state.put("archive",Map.of("decoded_hits",store.decodedHits,"decoded_misses",store.decodedMisses,"decoded_evictions",store.decodedEvictions,"decoded_bytes",store.decodedBytes,"page_reads",store.pageReads));return state;}
    public void reset(){throw new UnsupportedOperationException("Indexed playback does not replay packets");}
    public void apply(DemoFile.Event event){throw new UnsupportedOperationException("Indexed playback does not dispatch packets");}
    public void close()throws Exception {if(closed)return;closed=true;Exception failure=null;try{if(actorCatalog!=null)actorCatalog.close();}catch(Exception e){failure=e;}try{if(terrainCatalog!=null)terrainCatalog.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{if(mapCatalog!=null)mapCatalog.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{residentValues.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{if(fpCatalog!=null)fpCatalog.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{firstPerson.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{if(blood!=null)blood.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{actors.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{if(map!=null)map.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{tracks.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
}

