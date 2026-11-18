package replay;

import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Captures native renderer inputs and keeps installed-game resources alive; no copied textures or effect assets. */
final class NativeSceneEffects implements ReplayRender.IndexedEffects,AutoCloseable {
    final NativePackets.World replay;final SceneParticleStore store;final SceneBakeClock clock=new SceneBakeClock();
    final NativeSceneTracers tracers;final NativeSceneCapture poses;final boolean cached;
    final Map<Long,String> registered=new HashMap<>();final Set<Object> retained=Collections.newSetFromMap(new IdentityHashMap<>());
    SceneAsyncFrames<long[]> prepared;
    static final class Pending extends Exception {Pending(){super(null,null,false,false);}}
    Object layer,engine;long manager,previous=-1,requested=-1,selected=-1,captures,commands,published,weaponPasses,frameWaits;boolean recording=true,closed;
    final List<Object> preload=new ArrayList<>();long loadingSince;
    final float[] savedOrigin=new float[3];
    NativeSceneEffects(NativePackets.World replay,Path index)throws Exception {
        this.replay=replay;
        if(((Number)api("szReplayParticleVersion","int")).intValue()!=1)throw new java.io.IOException("Indexed particle native interface is unavailable");
        String hash=replay.packets.demo.manifest.get("event_stream_sha256").getAsString(),fingerprint=replay.packets.demo.compatibilityFingerprint;Path beams=index.resolveSibling(index.getFileName()+".tracers");
        SceneParticleStore existing=null;NativeSceneTracers tracerIndex=null;
        if(Files.isRegularFile(index)&&Files.isRegularFile(beams)&&!Boolean.getBoolean("sz.replay.effects-rebuild"))try{existing=SceneParticleStore.open(index,hash,fingerprint);tracerIndex=new NativeSceneTracers(beams,hash,fingerprint,true);}catch(Exception stale){if(existing!=null)existing.close();existing=null;}
        if(existing==null&&System.getProperty("sz.replay.prepare-scene","").isBlank())throw new java.io.IOException("У записи нет совместимой дорожки эффектов. Повторный прогон при открытии запрещён; запись нужно подготовить отдельно.");
        cached=existing!=null;store=cached?existing:SceneParticleStore.create(index,hash,fingerprint);tracers=cached?tracerIndex:new NativeSceneTracers(beams,hash,fingerprint,false);poses=new NativeSceneCapture(null,replay.packets.demo);
        ReplayRender.indexedEffects=this;
    }
    static Object api(String name,String type,Object...args)throws Exception {return Native.call(Native.type("exbo.stalcraft.engine.efk.client.Effekseer"),name,type,args);}
    long drive(Timeline timeline)throws Exception {return cached?-1:clock.elapsed(timeline,replay.bootstrapped,previous==timeline.position,!recording);}
    void bind()throws Exception {
        if(layer!=null)return;Object world=Native.call(replay.connection,"instanceof","1054393445");layer=Native.call(world,"transient","432112703");engine=Native.call(layer,"package","221705413");manager=((Number)Native.call(engine,"int","long")).longValue();float[] origin=origin();System.arraycopy(origin,0,savedOrigin,0,3);
        if(cached){Object cache=Native.field(engine,"static");for(String path:store.assets.values()){Object resource=Native.call(cache,"void","1520153458",Native.type("779455578").getConstructor(String.class).newInstance(path));Native.call(resource,"while","void");retained.add(resource);preload.add(resource);}loadingSince=System.nanoTime();}
    }
    float[] origin()throws Exception {Object vector=Native.field(layer,"strictfp");return new float[]{((Number)Native.field(vector,"assert")).floatValue(),((Number)Native.field(vector,"byte")).floatValue(),((Number)Native.field(vector,"catch")).floatValue()};}
    void origin(float[] value)throws Exception {Native.call(Native.field(layer,"strictfp"),"finally","1987590071",value[0],value[1],value[2]);}
    void resources()throws Exception {
        Object cache=Native.field(engine,"static");for(var entry:((Map<?,?>)Native.field(cache,"super")).entrySet()){
            Object reference=entry.getValue();Object resource=Native.call(reference,"float","java.lang.Object");if(resource==null||!Native.type("1392529543").isInstance(resource))continue;
            long pointer=((Number)Native.call(resource,"this","long")).longValue();if(pointer==0||registered.containsKey(pointer))continue;
            String path=entry.getKey().toString();byte[] id=MessageDigest.getInstance("SHA-256").digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            api("szReplayParticleRegisterEffect","void",manager,pointer,id);if(retained.add(reference))Native.call(reference,"while","void");registered.put(pointer,path);if(!cached)store.assets.put(HexFormat.of().formatHex(id),path);
        }
    }
    void sample(long time)throws Exception {
        if(!recording||!replay.bootstrapped||cached||time==previous||requested==time)return;bind();prepareWeapons();requested=time;
    }
    void prepareWeapons()throws Exception {
        Object world=Native.call(replay.connection,"instanceof","1054393445");
        for(Object entity:NativeSceneCapture.entities(world))if(Native.type("351062380").isInstance(entity)){
            Object controller=SceneControllerItem.controller(entity);if(controller==null)continue;
            poses.forcePose(entity,replay);
            // Match the canonical TP renderer, which can retain an animation
            // item independently of the entity's inventory/weapon slot.
            Object weapon=Native.call(controller,"throws","211097523");if(weapon==null)continue;
            Object hands=SceneSecondaryRigs.hands(entity);Object model=hands==null?null:Native.call(hands,"float","461253740");
            if(!SceneRemains.is(entity)){var actorPose=NativeSceneCapture.characterPose(entity);if(actorPose!=null)replay.bodyPoses.observed(entity,ScenePoseCodec.read(actorPose.pose()));}
            Object graph=model==null?null:Native.call(model,"float","java.lang.Object");if(graph!=null)NativeSceneCapture.evaluateAttachment(graph);
            if(SceneWeaponCapture.capture(entity,weapon))weaponPasses++;
        }
    }
    public void rendered(Object value,Object selectedLayer)throws Exception {
        if(!recording||cached||requested<0||previous==requested||value!=engine||selectedLayer!=layer)return;
        resources();byte[][] stages=new byte[3][];
        for(int stage=0;stage<3;stage++){int id=((Number)Native.field(layer,stage==0?"protected":"private")).intValue();stages[stage]=(byte[])api("szReplayParticleCapture","[B",manager,stage,1<<id);commands+=ByteBuffer.wrap(stages[stage]).order(ByteOrder.LITTLE_ENDIAN).getInt(12);}
        store.append(requested,origin(),stages);previous=requested;captures++;
    }
    boolean ready()throws Exception {
        if(!cached)return previous==replay.packets.demo.durationNs;bind();resources();
        for(Object ref:preload)if(Native.call(ref,"float","java.lang.Object")==null){if(System.nanoTime()-loadingSince>30_000_000_000L)throw new java.io.IOException("Recorded effect resource loading timed out: "+ref);return false;}return true;
    }
    void finish()throws Exception {store.finish();tracers.finish();recording=false;}
    boolean prepareHistory()throws Exception {
        if(recording||manager==0)return true;
        if(prepared==null)prepared=new SceneAsyncFrames<>("Replay effect preparation",128,Math.min(256L*1024*1024,Runtime.getRuntime().maxMemory()/8),key->{
            var entry=store.frames.get(key);long[] frames=new long[3];
            try{if(entry!=null){byte[][] data=new byte[3][];for(int stage=0;stage<3;stage++)data[stage]=store.read(entry.stages()[stage]);frames=(long[])api("szReplayParticlePrepareStages","[J",manager,data);}return frames;}
            catch(Exception failure){for(long handle:frames)if(handle!=0)api("szReplayParticleRelease","void",handle);throw failure;}
        },frames->{for(long handle:frames)if(handle!=0)api("szReplayParticleRelease","void",handle);},frames->{long total=0;for(long handle:frames)if(handle!=0)total=Math.addExact(total,((Number)api("szReplayParticleBytes","long",handle)).longValue());return total;});
        // Prepare the empty selection; actual frames follow the selected time.
        return prepared.request(-1,true)!=null;
    }
    void prefetch(long time,double speed)throws Exception {
        if(prepared==null||recording||manager==0)return;
        var entry=store.at(time);long key=entry==null?-1:entry.getKey();
        prepared.reserve(selected==key?Set.of(key):Set.of(selected,key));
        prepared.request(key,true);
        Long next=key;
        for(int i=0;i<16;i++){next=store.frames.higherKey(next);if(next==null)break;prepared.request(next,false);}
    }
    void present(long time)throws Exception {
        ReplayPerformance.enter("scene-effects");try{
        if(recording||manager==0)return;tracers.present(time);var entry=store.at(time);long key=entry==null?-1:entry.getKey();if(key==selected)return;
        long[] frames=prepared.request(key,true);if(frames==null)throw new Pending();
        api("szReplayParticlePublish","void",manager,frames);if(entry!=null)origin(entry.getValue().origin());selected=key;published++;
        }finally{ReplayPerformance.exit("scene-effects");}
    }
    void ensureReady(long time)throws Exception {if(recording||manager==0)return;var entry=store.at(time);long key=entry==null?-1:entry.getKey();if(prepared!=null){prepared.reserve(selected==key?Set.of(key):Set.of(selected,key));if(prepared.request(key,true)!=null)return;}frameWaits++;throw new Pending();}
    public boolean manages(Object value){return !recording&&value==engine;}
    public boolean owns(Object value){return !recording&&value==layer;}
    public boolean captures(Object value){return recording&&!cached&&(value==null||layer==null||value==layer);}
    Map<String,Object> diagnostic(){var result=new TreeMap<String,Object>();result.put("captured_frames",captures);result.put("stored_frames",store.frames.size());result.put("renderer_commands",commands);result.put("selected_time_ns",selected);result.put("published_frames",published);result.put("prepared_frames",prepared==null?0:prepared.loaded);result.put("effect_frame_waits",frameWaits);result.put("resident_history",prepared==null?Map.of():prepared.diagnostic());result.put("installed_effect_resources",registered.size());result.put("compressed_bytes",store.bytes);result.put("decoded_pages",store.decoded);result.put("recording",recording);result.put("cache_reused",cached);result.put("weapon_preparation_passes",weaponPasses);result.put("tracers",tracers.diagnostic());return result;}
    public void close()throws Exception {
        if(closed)return;closed=true;if(ReplayRender.indexedEffects==this)ReplayRender.indexedEffects=null;Exception failure=null;
        try{tracers.close();}catch(Exception e){failure=e;}
        try{if(prepared!=null)prepared.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{if(manager!=0){api("szReplayParticleClear","void",manager);origin(savedOrigin);}}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        for(Object resource:retained)try{Native.call(resource,"char","void");}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{store.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;
    }
}


