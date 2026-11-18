package replay;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Audio voices selected by absolute time. Resources use the installed game's file callbacks. */
final class NativeSceneAudio implements AutoCloseable {
    record Sample(long time,int millis,float pitch,float volume,float frequency,int mode,float[] spatial,float minDistance,float maxDistance){}
    static final class Clip {
        final ReplayAudio.Key source;final double origin;final long born;final long sound;final String resource;
        final NavigableMap<Long,Sample> samples=new TreeMap<>();long dead=Long.MAX_VALUE;
        final ReplayAudio.Window window;
        long generation;double anchorOrigin=Double.NaN,logicalMillis;
        long group;
        Clip(ReplayAudio.Key key,double origin,long born,long sound,String resource,ReplayAudio.Window window){source=key;this.origin=origin;this.born=born;this.sound=sound;this.resource=resource;this.window=window;}
        Sample at(long ns){var e=samples.floorEntry(ns);return e==null?null:e.getValue();}
    }
    final ReplayAudio source;final Object engine;final ReplayAudio.Fmod driver=new ReplayAudio.Fmod();
    final List<Clip> clips=new ArrayList<>();final Map<ReplayAudio.Key,Clip> capturing=new HashMap<>();
    final Map<Clip,ReplayAudio.Key> playing=new IdentityHashMap<>();final Map<String,Long> reloaded=new HashMap<>();
    final SceneSoundResources resources;
    boolean pendingResources;
    final Set<Clip> completed=Collections.newSetFromMap(new IdentityHashMap<>());
    record Applied(Sample sample,double speed,boolean paused){}
    final Map<Clip,Applied> applied=new IdentityHashMap<>();
    final List<String> failures=new ArrayList<>();long previous=-1,sourceSamples;int selections,seeks,reverseChannels,missing,voiceStarts,voiceRestarts;boolean recording=true,enabled=true;
    boolean previousPaused,previousEnabled;double previousSpeed;long previousRevision=Long.MIN_VALUE;
    NativeSceneAudio(ReplayAudio audio)throws Exception {source=audio;engine=driver.engine();source.captureStarted=this::started;
        resources=new SceneSoundResources(new SceneSoundResources.Access(){
            public long open(String path)throws Exception {return ((Number)Native.call(engine,"import","long",path,0x10100)).longValue();}
            public int state(long sound)throws Exception {try(MemoryStack stack=MemoryStack.stackPush()){
                var state=stack.callocInt(1);int code=result(Native.call(Native.type("1141491285"),"volatile","int",sound,address(state),0L,0L,0L));
                if(code!=0)throw new java.io.IOException("FMOD sample open state failed: "+code);return state.get(0);
            }}
        },reloaded);
    }
    void prepareResources()throws Exception {resources.pump(System.nanoTime()+2_000_000L,System::nanoTime);}
    boolean resourcesReady(){return resources.ready();}
    java.nio.file.Path archivePath;String eventHash,fingerprint,cacheError="";long duration;
    boolean cached;
    java.util.concurrent.CompletableFuture<SceneAudioArchive.Data> cacheRead;
    java.util.concurrent.CompletableFuture<Void> cacheWrite;
    NativeSceneAudio(ReplayAudio audio,DemoFile demo)throws Exception {
        this(audio);archivePath=SceneAudioArchive.beside(demo.path);duration=demo.durationNs;eventHash=demo.manifest.get("event_stream_sha256").getAsString();fingerprint=demo.compatibilityFingerprint;
        if(java.nio.file.Files.isRegularFile(archivePath)&&!Boolean.getBoolean("sz.replay.audio-rebuild")){
            var path=archivePath;var hash=eventHash;var client=fingerprint;long end=duration;
            cacheRead=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return SceneAudioArchive.read(path,hash,client,end);}catch(java.io.IOException failure){throw new java.util.concurrent.CompletionException(failure);}});
        }
    }
    static Map<String,Long> namedGroups(Object engine)throws Exception {
        Object manager=Native.call(Native.type("1831969298"),"extends","1831969298");Map<?,?> handles=(Map<?,?>)Native.field(engine,"long");var groups=new HashMap<String,Long>();
        for(var field:manager.getClass().getFields())if(field.getType().getName().equals("1651937268")){
            Object group=field.get(manager);if(group==null)continue;Object handle=handles.get(Native.field(group,"void"));if(!(handle instanceof Number n)||n.longValue()==0)continue;
            String name=(String)Native.field(group,"try");Long previous=groups.put(name,n.longValue());if(previous!=null&&previous.longValue()!=n.longValue())throw new java.io.IOException("Ambiguous native audio group: "+name);
        }
        return groups;
    }
    boolean restoreReady()throws Exception {
        if(cacheRead==null)return true;if(!cacheRead.isDone())return false;
        var future=cacheRead;cacheRead=null;
        try{
            SceneAudioArchive.Data data=future.join();var groups=namedGroups(engine);var restored=new ArrayList<Clip>();long samples=0;
            for(var voice:data.voices()){
                Long group=voice.event()?0L:groups.get(voice.group());if(group==null)throw new java.io.IOException("Recorded audio group unavailable: "+voice.group());
                var clip=new Clip(new ReplayAudio.Key(voice.event(),restored.size()+1L),voice.origin(),voice.born(),0,voice.resource(),voice.window());clip.dead=voice.dead();clip.group=group;
                for(var sample:voice.samples())clip.samples.put(sample.time(),sample);samples+=voice.samples().size();restored.add(clip);
            }
            clips.clear();clips.addAll(restored);sourceSamples=samples;failures.clear();failures.addAll(data.errors());recording=false;cached=true;source.captureStarted=null;
            resources.request(clips.stream().filter(c->!c.source.event()).map(c->c.resource).toList());
        }catch(Exception failure){Throwable root=failure;while(root.getCause()!=null)root=root.getCause();cacheError=root.toString();throw new java.io.IOException("Подготовленная звуковая дорожка повреждена или несовместима; повторный прогон при открытии запрещён",root);}
        return true;
    }
    void error(Throwable e){if(failures.size()<12)failures.add(e.toString());}
    void started(ReplayAudio.Key key){if(cacheRead!=null||!recording)return;try{capture(key,(long)(source.started.getOrDefault(key,ReplayClock.seconds)*1e9));}catch(Exception e){error(e);}}
    static int result(Object value){return ((Number)value).intValue();}
    static long address(java.nio.Buffer buffer){return MemoryUtil.memAddress(buffer);}
    void capture(ReplayAudio.Key key,long ns)throws Exception {
        Double origin=source.started.get(key);if(origin==null)return;
        ReplayAudio.State state=driver.read(key);if(state==null)return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            Clip clip=capturing.get(key);
            long generation=source.generations.getOrDefault(key,0L);
            if(clip==null||clip.generation!=generation){
                if(clip!=null)clip.dead=ns;
                var ptr=stack.callocPointer(1);long sound=0;String path;
                if(key.event()){
                    if(result(Native.call(Native.type("1069936767"),"extends","int",key.handle(),MemoryUtil.memAddress(ptr)))!=0)return;
                    var utf8=stack.calloc(4096);var required=stack.callocInt(1);
                    if(result(Native.call(Native.type("1069936767"),"synchronized","int",ptr.get(0),address(utf8),4096,address(required)))!=0)return;
                    path=MemoryUtil.memUTF8(address(utf8));
                }else{
                    if(result(Native.call(Native.type("1141491285"),"k","int",key.handle(),MemoryUtil.memAddress(ptr)))!=0)return;sound=ptr.get(0);
                    path=ReplayRender.soundResources.get(sound);
                    if(path==null){var utf8=stack.calloc(4096);if(result(Native.call(Native.type("1141491285"),"return","int",sound,address(utf8),4096))!=0)return;path=MemoryUtil.memUTF8(address(utf8));}
                }
                clip=new Clip(key,origin,(long)(origin*1e9),sound,path,driver.window(key));clip.generation=generation;capturing.put(key,clip);clips.add(clip);
            }
            var volume=stack.callocFloat(1);float gain=1;
            if(key.event()){var finalVolume=stack.callocFloat(1);if(result(Native.call(Native.type("1069936767"),"default","int",key.handle(),address(volume),address(finalVolume)))==0)gain=volume.get(0);}
            else if(result(Native.call(Native.type("1141491285"),"try","int",key.handle(),address(volume)))==0)gain=volume.get(0);
            var spatial=stack.callocFloat(key.event()?12:6);float[] values=new float[spatial.capacity()];int mode=0;float frequency=0,minDistance=1,maxDistance=10000;
            if(key.event()){
                if(result(Native.call(Native.type("1069936767"),"instanceof","int",key.handle(),address(spatial)))==0)spatial.get(values);
            }else{
                var flags=stack.callocInt(1);if(result(Native.call(Native.type("1141491285"),"while","int",key.handle(),address(flags)))==0)mode=flags.get(0);
                if((mode&16)!=0&&result(Native.call(Native.type("1141491285"),"transient","int",key.handle(),address(spatial),address(spatial)+12))==0)spatial.get(values);
                var hz=stack.callocFloat(1);if(result(Native.call(Native.type("1141491285"),"e","int",key.handle(),address(hz)))==0)frequency=hz.get(0);
                var min=stack.callocFloat(1);var max=stack.callocFloat(1);if(result(Native.call(Native.type("1141491285"),"public","int",key.handle(),address(min),address(max)))==0){minDistance=min.get(0);maxDistance=max.get(0);}
                var group=stack.callocPointer(1);if(result(Native.call(Native.type("1141491285"),"h","int",key.handle(),MemoryUtil.memAddress(group)))==0)clip.group=group.get(0);
            }
            ReplayAudio.Override override=source.overrides.get(key);float pitch=override==null?state.pitch():override.originalPitch;
            // Preparation advances simulation independently of the mixer. The
            // mixer's delayed getPosition is not a recorded sound clock.
            Sample last=clip.samples.isEmpty()?null:clip.samples.lastEntry().getValue();
            clip.logicalMillis=logicalMillis(clip.logicalMillis,last,ns,origin,clip.anchorOrigin,source.offsets.getOrDefault(key,0),pitch);
            clip.anchorOrigin=origin;
            Sample sample=new Sample(ns,(int)Math.min(Integer.MAX_VALUE,clip.logicalMillis),pitch,gain,frequency,mode,values,minDistance,maxDistance);
            if(!Float.isFinite(pitch)||!Float.isFinite(gain)||!Float.isFinite(frequency))throw new IllegalStateException("Nonfinite recorded sound state");
            clip.samples.put(ns,sample);sourceSamples++;
        }
    }
    static double logicalMillis(double accumulated,Sample last,long ns,double origin,double previousOrigin,int offset,float pitch){
        if(last==null||Double.compare(origin,previousOrigin)!=0)return Math.max(0,offset+Math.max(0,ns/1e9-origin)*1000*pitch);
        return Math.max(0,accumulated+Math.max(0,ns-last.time())/1e6*last.pitch());
    }
    void sample(long ns)throws Exception {
        if(!recording)return;
        for(var key:new ArrayList<>(source.started.keySet()))try{capture(key,ns);}catch(Exception e){error(e);}
        for(var iterator=capturing.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!source.started.containsKey(entry.getKey())||driver.read(entry.getKey())==null){entry.getValue().dead=ns;iterator.remove();}}
    }
    void finish(long ns){boolean captured=recording;recording=false;source.captureStarted=null;for(Clip clip:capturing.values())clip.dead=ns+1;capturing.clear();if(captured&&archivePath!=null)saveArchive();}
    void saveArchive(){
        if(archivePath==null||cacheWrite!=null)return;
        try{
            var byHandle=new HashMap<Long,String>();namedGroups(engine).forEach((name,handle)->byHandle.put(handle,name));
            List<Clip> captured=List.copyOf(clips);List<String> errors=List.copyOf(failures);var path=archivePath;var hash=eventHash;var client=fingerprint;long end=duration;
            cacheWrite=java.util.concurrent.CompletableFuture.runAsync(()->{
                try{var voices=new ArrayList<SceneAudioArchive.Voice>();for(var clip:captured){
                    String group=clip.source.event()?"":clip.group==0?"MASTER":byHandle.get(clip.group);if(group==null)throw new java.io.IOException("Unmapped recorded audio group for "+clip.resource);
                    voices.add(new SceneAudioArchive.Voice(clip.source.event(),clip.resource,group,clip.origin,clip.born,clip.dead,clip.window,new ArrayList<>(clip.samples.values())));
                }SceneAudioArchive.write(path,hash,client,new SceneAudioArchive.Data(end,voices,errors));}
                catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            });
        }catch(Exception failure){cacheError=failure.toString();}
    }
    long decodedSound(Clip clip)throws Exception {
        // Concurrent shots share immutable PCM. FMOD_NONBLOCKING loads it on
        // FMOD's own loader; an unopened sample never blocks a replay frame.
        return resources.sound(clip.resource);
    }
    ReplayAudio.Key create(Clip clip)throws Exception {
        try(MemoryStack stack=MemoryStack.stackPush()){
            var result=stack.callocPointer(1);
            if(clip.source.event()){
                var utf8=stack.UTF8(clip.resource);var description=stack.callocPointer(1);long studio=((Number)Native.field(engine,"volatile")).longValue();
                if(result(Native.call(Native.type("1069936767"),"extends","int",studio,address(utf8),MemoryUtil.memAddress(description)))!=0)throw new IllegalStateException("Sound event not found: "+clip.resource);
                if(result(Native.call(Native.type("1069936767"),"final","int",description.get(0),MemoryUtil.memAddress(result)))!=0)throw new IllegalStateException("Sound event creation failed: "+clip.resource);
                var key=new ReplayAudio.Key(true,result.get(0));driver.pause(key,true);
                if(result(Native.call(Native.type("1069936767"),"native","int",key.handle()))!=0){Native.call(Native.type("1069936767"),"switch","int",key.handle());throw new IllegalStateException("Sound event start failed: "+clip.resource);}return key;
            }
            long sound=clip.sound;var length=stack.callocInt(1);
            if(!clip.resource.equals(ReplayRender.soundResources.get(sound))||result(Native.call(Native.type("1141491285"),"const","int",sound,address(length),1))!=0){
                sound=decodedSound(clip);
            }
            long system=((Number)Native.field(engine,"switch")).longValue();
            int code=result(Native.call(Native.type("1141491285"),"import","int",system,sound,clip.group,1,MemoryUtil.memAddress(result)));
            // A native stream owns one decoder. Overlapping recorded incarnations
            // must not compete for that decoder when a backwards seek revives them.
            if(code!=0&&sound!=reloaded.getOrDefault(clip.resource,0L)){
                sound=decodedSound(clip);
                code=result(Native.call(Native.type("1141491285"),"import","int",system,sound,clip.group,1,MemoryUtil.memAddress(result)));
            }
            if(code!=0)throw new IllegalStateException("Sound channel creation failed (FMOD "+code+"): "+clip.resource);
            return new ReplayAudio.Key(false,result.get(0));
        }
    }
    static long millisAt(Clip clip,Sample sample,long ns){
        double ms=sample.millis()+Math.max(0,ns-sample.time())/1e6*sample.pitch();ReplayAudio.Window w=clip.window;
        if(w!=null&&w.loopEnd()>w.loopStart()&&w.repeats()!=0&&ms>=w.loopEnd()){
            int loop=w.loopEnd()-w.loopStart();double repeats=Math.floor((ms-w.loopStart())/loop);
            if(w.repeats()<0||repeats<=w.repeats())ms=w.loopStart()+(ms-w.loopStart())%loop;else ms-=loop*(double)w.repeats();
        }
        return Math.max(0,(long)ms);
    }
    void stop(ReplayAudio.Key key)throws Exception {driver.stop(key);if(key.event())Native.call(Native.type("1069936767"),"switch","int",key.handle());}
    void listener(Pose pose)throws Exception {
        if(pose==null)return;
        Pose.V p=pose.p(),forward=pose.q().rotate(new Pose.V(0,0,-1)),up=pose.q().rotate(new Pose.V(0,1,0));
        Native.call(engine,"szmods$original$listener","void",(float)p.x(),(float)p.y(),(float)p.z(),0f,0f,0f,(float)forward.x(),(float)forward.y(),(float)forward.z(),(float)up.x(),(float)up.y(),(float)up.z());
    }
    void present(long ns,boolean paused,double speed,long revision)throws Exception {
        ReplayPerformance.enter("scene-audio");try{presentSelected(ns,paused,speed,revision);}finally{ReplayPerformance.exit("scene-audio");}
    }
    static boolean reposition(boolean fresh,long revision,long previousRevision,double speed,double previousSpeed){
        return fresh||revision!=previousRevision||Math.signum(speed)!=Math.signum(previousSpeed);
    }
    static boolean updateNeeded(Applied last,Sample sample,double speed,boolean paused,boolean selection,boolean jump){
        return last==null||last.sample()!=sample||Double.compare(last.speed(),speed)!=0||last.paused()!=paused||selection||jump;
    }
    void presentSelected(long ns,boolean paused,double speed,long revision)throws Exception {
        if(recording)throw new IllegalStateException("Audio capture has not finished");
        if(paused&&previousPaused&&ns==previous&&revision==previousRevision&&enabled==previousEnabled&&Double.compare(speed,previousSpeed)==0&&!pendingResources)return;
        pendingResources=false;
        boolean selection=reposition(false,revision,previousRevision,speed,previousSpeed)||enabled&&!previousEnabled;
        boolean jump=selection||paused&&!previousPaused;
        if(selection)completed.clear();
        previous=ns;selections++;reverseChannels=0;
        Set<Clip> wanted=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Clip clip:clips){if(ns<clip.born||ns>=clip.dead)continue;Sample sample=clip.at(ns);if(sample==null)continue;
            if(completed.contains(clip))continue;
            long millis=millisAt(clip,sample,ns);if(clip.window!=null&&clip.window.length()>0&&(clip.window.loopEnd()<=clip.window.loopStart()||clip.window.repeats()==0)&&millis>=clip.window.length())continue;
            wanted.add(clip);ReplayAudio.Key key=playing.get(clip);
            try{
                boolean wantedPause=!enabled||paused||clip.source.event()&&speed<0;
                Applied previousApplied=key==null?null:applied.get(clip);
                // FMOD continues decoding independently. An unchanged authored
                // sample needs no queries or commands. Recorded death/length is
                // still evaluated above; every changed state and explicit seek
                // validates a potentially expired handle before using it.
                if(key!=null&&!updateNeeded(previousApplied,sample,speed,wantedPause,selection,jump)){
                    if(!key.event()&&speed<0&&sample.frequency()>0)reverseChannels++;
                    continue;
                }
                boolean ended=key!=null&&!driver.valid(key);
                // A naturally completed decoder must not be resurrected for
                // the last few milliseconds of a quantized recorded lifetime.
                if(ended&&!selection){completed.add(clip);playing.remove(clip);applied.remove(clip);continue;}
                boolean fresh=key==null||ended;if(fresh){if(ended)voiceRestarts++;key=create(clip);voiceStarts++;playing.put(clip,key);}
                Applied last=fresh?null:applied.get(clip);Sample before=last==null?null:last.sample();boolean rate=last==null||Double.compare(speed,last.speed())!=0;
                try(MemoryStack stack=MemoryStack.stackPush()){
                    boolean spatialChanged=before==null||!Arrays.equals(before.spatial(),sample.spatial());
                    if(key.event()){
                        if(spatialChanged){var spatial=stack.floats(sample.spatial());Native.call(Native.type("1069936767"),"this","int",key.handle(),address(spatial));}
                        if(before==null||before.volume()!=sample.volume())Native.call(Native.type("1069936767"),"native","int",key.handle(),sample.volume());
                        if(rate||before.pitch()!=sample.pitch())driver.pitch(key,(float)(sample.pitch()*Math.abs(speed)));
                    }else{
                        if(before==null||before.mode()!=sample.mode())Native.call(Native.type("1141491285"),"short","int",key.handle(),sample.mode());
                        if((sample.mode()&16)!=0){if(spatialChanged){var spatial=stack.floats(sample.spatial());Native.call(Native.type("1141491285"),"case","int",key.handle(),address(spatial),address(spatial)+12);}if(before==null||before.minDistance()!=sample.minDistance()||before.maxDistance()!=sample.maxDistance())Native.call(Native.type("1141491285"),"volatile","int",key.handle(),sample.minDistance(),sample.maxDistance());}
                        if(before==null||before.volume()!=sample.volume())Native.call(Native.type("1141491285"),"boolean","int",key.handle(),sample.volume());
                        if(sample.frequency()>0){if(speed<0)reverseChannels++;if(rate||before.frequency()!=sample.frequency()){int result=result(Native.call(Native.type("1141491285"),"float","int",key.handle(),(float)(sample.frequency()*speed)));if(result!=0){driver.pause(key,true);missing++;continue;}}}
                        else if(rate||before.pitch()!=sample.pitch())driver.pitch(key,(float)(sample.pitch()*Math.abs(speed)));
                    }
                    // Let FMOD continuously decode between explicit selections.
                    // Repeated drift seeks cut Core tails and retrigger Studio
                    // instruments; even a short slider seek has a revision.
                    if(fresh||jump){
                        ReplayRender.audioPositionOverride.set(true);try{if(driver.seek(key,(int)Math.min(Integer.MAX_VALUE,millis)))seeks++;}finally{ReplayRender.audioPositionOverride.remove();}
                    }
                    if(last==null||last.paused()!=wantedPause)driver.pause(key,wantedPause);
                    if(last==null||last.sample()!=sample||rate||last.paused()!=wantedPause)applied.put(clip,new Applied(sample,speed,wantedPause));
                }
            }catch(SceneSoundResources.Pending pending){pendingResources=true;}
            catch(Exception failure){missing++;error(failure);if(key!=null){try{stop(key);}catch(Exception ignored){}playing.remove(clip);}}
        }
        for(var iterator=playing.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!wanted.contains(entry.getKey())){stop(entry.getValue());applied.remove(entry.getKey());iterator.remove();}}
        driver.commit();
        previousPaused=paused;previousEnabled=enabled;previousSpeed=speed;previousRevision=revision;
    }
    Map<String,Object> diagnostic(){if(cacheWrite!=null&&cacheWrite.isCompletedExceptionally())try{cacheWrite.join();}catch(Exception failure){Throwable root=failure;while(root.getCause()!=null)root=root.getCause();cacheError=root.toString();}var state=new LinkedHashMap<String,Object>();state.put("recorded_clips",clips.size());state.put("captured_states",sourceSamples);state.put("active_voices",playing.size());state.put("direct_selections",selections);state.put("native_seek_commands",seeks);state.put("voice_starts",voiceStarts);state.put("unexpected_voice_restarts",voiceRestarts);state.put("reverse_core_voices",reverseChannels);state.put("missing_or_unsupported",missing);state.put("enabled",enabled);state.put("errors",List.copyOf(failures));state.put("reverse_studio_events_supported",false);state.put("cache_reused",cached);state.put("cache_loading",cacheRead!=null);state.put("cache_saved",cacheWrite!=null&&cacheWrite.isDone()&&!cacheWrite.isCompletedExceptionally());state.put("cache_error",cacheError);return state;}
    public void close()throws Exception {source.captureStarted=null;Exception failure=null;for(var key:playing.values())try{stop(key);}catch(Exception e){if(failure==null)failure=e;}playing.clear();for(long sound:reloaded.values())try{Native.call(engine,"boolean","void",sound);}catch(Exception e){if(failure==null)failure=e;}reloaded.clear();if(failure!=null)throw failure;}
}
