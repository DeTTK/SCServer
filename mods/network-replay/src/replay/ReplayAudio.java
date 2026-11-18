package replay;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Owns only temporary pause/rate overrides on the game's existing FMOD voices. */
final class ReplayAudio implements ReplayRender.AudioEvents {
    record Key(boolean event,long handle){}
    record State(boolean paused,float pitch,Integer timelineMillis){}
    record Window(int length,int loopStart,int loopEnd,int repeats){}
    interface Driver {
        Set<Key> voices()throws Exception;
        State read(Key voice)throws Exception;
        default boolean valid(Key voice)throws Exception {return read(voice)!=null;}
        boolean pause(Key voice,boolean paused)throws Exception;
        boolean pitch(Key voice,float pitch)throws Exception;
        default boolean stop(Key voice)throws Exception {return false;}
        default Window window(Key voice)throws Exception {return null;}
        default boolean seek(Key voice,int millis)throws Exception {return false;}
        default void commit()throws Exception {}
        default Integer playback(Key voice)throws Exception {return null;}
    }
    static final class Fmod implements Driver {
        Object engine()throws Exception{return Native.field(Native.call(Native.type("1831969298"),"extends","1831969298"),"else");}
        public Set<Key> voices()throws Exception {
            Set<Key> voices=new LinkedHashSet<>();Object engine=engine();
            for(boolean event:new boolean[]{true,false}){
                Object registry=Native.field(engine,event?"const":"continue");
                for(Object value:(Collection<?>)Native.call(registry,"volatile","1725079532")){long handle=((Number)value).longValue();if(handle!=0)voices.add(new Key(event,handle));}
            }return voices;
        }
        public State read(Key voice)throws Exception {
            Class<?> api=Native.type(voice.event?"1069936767":"1141491285");
            try(MemoryStack stack=MemoryStack.stackPush()){
                var paused=stack.callocInt(1);var pitch=stack.callocFloat(1);
                int result=((Number)Native.call(api,voice.event?"switch":"throws","int",voice.handle,MemoryUtil.memAddress(paused))).intValue();if(result!=0)return null;
                result=((Number)(voice.event?Native.call(api,"do","int",voice.handle,MemoryUtil.memAddress(pitch),0L):Native.call(api,"do","int",voice.handle,MemoryUtil.memAddress(pitch)))).intValue();if(result!=0)return null;
                Integer timeline=null;var position=stack.callocInt(1);result=((Number)(voice.event?Native.call(api,"const","int",voice.handle,MemoryUtil.memAddress(position)):Native.call(api,"boolean","int",voice.handle,MemoryUtil.memAddress(position),1))).intValue();if(result==0)timeline=position.get(0);
                return new State(paused.get(0)!=0,pitch.get(0),timeline);
            }
        }
        /** Indexed playback only needs handle validity, not mixer pitch or playback position. */
        public boolean valid(Key voice)throws Exception {
            try(MemoryStack stack=MemoryStack.stackPush()){
                var paused=stack.callocInt(1);
                return ((Number)Native.call(Native.type(voice.event?"1069936767":"1141491285"),voice.event?"switch":"throws","int",voice.handle,MemoryUtil.memAddress(paused))).intValue()==0;
            }
        }
        public boolean pause(Key voice,boolean paused)throws Exception{return ((Number)Native.call(Native.type(voice.event?"1069936767":"1141491285"),voice.event?"package":"while","int",voice.handle,paused?1:0)).intValue()==0;}
        public boolean pitch(Key voice,float pitch)throws Exception{return ((Number)Native.call(Native.type(voice.event?"1069936767":"1141491285"),voice.event?"assert":"do","int",voice.handle,pitch)).intValue()==0;}
        public boolean stop(Key voice)throws Exception{return ((Number)(voice.event?Native.call(Native.type("1069936767"),"instanceof","int",voice.handle,1):Native.call(Native.type("1141491285"),"import","int",voice.handle))).intValue()==0;}
        public boolean seek(Key voice,int millis)throws Exception{return ((Number)(voice.event?Native.call(Native.type("1069936767"),"float","int",voice.handle,millis):Native.call(Native.type("1141491285"),"protected","int",voice.handle,millis,1))).intValue()==0;}
        public void commit()throws Exception {
            ReplayPerformance.enter("audio-submit");try{
                // break(J) is Studio::System::update: submit to the existing
                // asynchronous Studio worker, including when native ticks pause.
                // void(J) is flushCommands and stalls the render thread until
                // that worker/mixer finishes the entire outstanding queue.
                long system=((Number)Native.field(engine(),"volatile")).longValue();
                if(system!=0&&((Number)Native.call(Native.type("1069936767"),"break","int",system)).intValue()!=0)throw new IllegalStateException("FMOD replay commands were not submitted");
            }finally{ReplayPerformance.exit("audio-submit");}
        }
        public Integer playback(Key voice)throws Exception {if(!voice.event)return null;try(MemoryStack stack=MemoryStack.stackPush()){var value=stack.callocInt(1);return ((Number)Native.call(Native.type("1069936767"),"throws","int",voice.handle,MemoryUtil.memAddress(value))).intValue()==0?value.get(0):null;}}
        public Window window(Key voice)throws Exception {
            Class<?> api=Native.type(voice.event?"1069936767":"1141491285");
            try(MemoryStack stack=MemoryStack.stackPush()){
                var object=stack.callocPointer(1);var length=stack.callocInt(1);var mode=stack.callocInt(1);
                if(((Number)Native.call(api,voice.event?"extends":"k","int",voice.handle,MemoryUtil.memAddress(object))).intValue()!=0||object.get(0)==0)return null;
                if(voice.event){
                    if(((Number)Native.call(api,"if","int",object.get(0),MemoryUtil.memAddress(length))).intValue()!=0||((Number)Native.call(api,"default","int",object.get(0),MemoryUtil.memAddress(mode))).intValue()!=0)return null;
                    // Parameter-driven persistent Studio events own their authored loop/transition logic.
                    return mode.get(0)!=0?new Window(length.get(0),0,0,0):null;
                }
                if(((Number)Native.call(api,"const","int",object.get(0),MemoryUtil.memAddress(length),1)).intValue()!=0||length.get(0)<=0)return null;
                if(((Number)Native.call(api,"while","int",voice.handle,MemoryUtil.memAddress(mode))).intValue()!=0)return null;
                if((mode.get(0)&6)==0)return new Window(length.get(0),0,0,0);
                // Bidirectional loops need mixer direction state; never substitute a forward loop.
                if((mode.get(0)&4)!=0)return null;
                var start=stack.callocInt(1);var end=stack.callocInt(1);var count=stack.callocInt(1);
                if(((Number)Native.call(api,"assert","int",voice.handle,MemoryUtil.memAddress(start),1,MemoryUtil.memAddress(end),1)).intValue()!=0||((Number)Native.call(api,"i","int",voice.handle,MemoryUtil.memAddress(count))).intValue()!=0)return null;
                return new Window(length.get(0),start.get(0),Math.min(length.get(0),end.get(0)+1),count.get(0));
            }
        }
    }
    static final class Override {
        boolean originalPause,lastPause;float originalPitch,lastPitch;
        Override(State state){originalPause=lastPause=state.paused;originalPitch=lastPitch=state.pitch;}
    }
    final Driver driver;final Map<Key,Override> overrides=new HashMap<>();
    final Map<Key,Double> started=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Key,Long> generations=new java.util.concurrent.ConcurrentHashMap<>();final java.util.concurrent.atomic.AtomicLong nextGeneration=new java.util.concurrent.atomic.AtomicLong();
    final Map<Key,Integer> offsets=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Key,Window> windows=new java.util.concurrent.ConcurrentHashMap<>();
    final Set<Key> invalid=new HashSet<>();
    final Set<Key> authored=java.util.concurrent.ConcurrentHashMap.newKeySet();
    final Set<Key> baseline=new HashSet<>(),owned=new HashSet<>();
    int events,channels,pausedVoices,expired,repositioned,finished;double speed=1;boolean paused,catchup,pendingCommands;
    ReplayAudio(){this(new Fmod());}
    ReplayAudio(Driver driver){this.driver=driver;}
    void bind()throws Exception{owned.clear();baseline.clear();baseline.addAll(driver.voices());ReplayRender.audioEvents=this;}
    java.util.function.Consumer<Key> captureStarted;
    void started(Key key){if(key.handle!=0){offsets.remove(key);windows.remove(key);generations.put(key,nextGeneration.incrementAndGet());started.put(key,ReplayClock.seconds);if(key.event&&captureStarted!=null)captureStarted.accept(key);}}
    public void started(long handle){started(new Key(true,handle));}
    public void channel(long handle){started(new Key(false,handle));}
    // Core playSound returns a paused channel with the resource's default 2D
    // mode. The native caller still has to assign 3D position, gain and range.
    // Acquire its lifetime immediately, but sample only the configured voice.
    public void configured(long handle){Key key=new Key(false,handle);if(started.containsKey(key)&&captureStarted!=null)captureStarted.accept(key);}
    final Map<Integer,Double> requestedChannels=new HashMap<>();
    public void requested(int id){if(id>=0)requestedChannels.put(id,ReplayClock.seconds);}
    public void configured(int id,long handle){Double origin=requestedChannels.remove(id);if(origin!=null&&handle!=0)started.put(new Key(false,handle),origin);configured(handle);}
    public void positioned(boolean event,long handle,int millis){Key key=new Key(event,handle);if(started.containsKey(key)){started.put(key,ReplayClock.seconds);offsets.put(key,millis);}}
    public void authored(long handle,int millis){Key key=new Key(true,handle);if(!started.containsKey(key))generations.put(key,nextGeneration.incrementAndGet());started.put(key,ReplayClock.seconds);offsets.put(key,millis);authored.add(key);}
    public void releaseAuthored(long handle){Key key=new Key(true,handle);authored.remove(key);started.remove(key);offsets.remove(key);windows.remove(key);}
    void configure(boolean pause,double rate){paused=pause;speed=rate;}
    void clockLag(long lagNs,long tickNs){
        if(lagNs<0||tickNs<1||tickNs>Long.MAX_VALUE/2)throw new IllegalArgumentException("Invalid audio clock lag");
        // A pending native tick is ordinary playback, not a reconstruction.
        // Enter after two ticks of debt, leave within one, so voices do not
        // pause/reseek at every 20-Hz simulation frontier.
        catchup=lagNs>(catchup?tickNs:2*tickNs);
    }
    static boolean same(float first,float second){return Math.abs(first-second)<=1e-5*Math.max(1,Math.abs(second));}
    void sync(boolean pause,double rate)throws Exception {
        if(!Double.isFinite(rate)||rate<=0||rate>16)throw new IllegalArgumentException("Invalid replay audio rate");
        paused=pause;speed=rate;events=channels=pausedVoices=0;Set<Key> current=new LinkedHashSet<>(driver.voices());current.addAll(started.keySet());for(Key key:current)if(!baseline.contains(key)||started.containsKey(key))owned.add(key);overrides.keySet().retainAll(current);owned.retainAll(current);invalid.retainAll(current);invalid.removeAll(started.keySet());
        for(Key key:current){if(invalid.contains(key))continue;State nativeState=driver.read(key);if(nativeState==null){overrides.remove(key);started.remove(key);invalid.add(key);expired++;continue;}
            if(!Float.isFinite(nativeState.pitch)||nativeState.pitch<0)throw new IllegalStateException("Invalid native voice pitch");
            Override value=overrides.computeIfAbsent(key,k->new Override(nativeState));
            // Native animation/modulation may change these properties between frames.
            if(nativeState.paused!=value.lastPause)value.originalPause=nativeState.paused;
            if(!same(nativeState.pitch,value.lastPitch))value.originalPitch=nativeState.pitch;
            boolean wantedPause=pause||value.originalPause;float wantedPitch=(float)(value.originalPitch*rate);
            if(nativeState.paused!=wantedPause){if(!driver.pause(key,wantedPause)){overrides.remove(key);expired++;continue;}if(key.event)pendingCommands=true;}
            if(!same(nativeState.pitch,wantedPitch)){if(!driver.pitch(key,wantedPitch)){overrides.remove(key);expired++;continue;}if(key.event)pendingCommands=true;}
            value.lastPause=wantedPause;value.lastPitch=wantedPitch;if(key.event)events++;else channels++;if(wantedPause)pausedVoices++;
        }
    }
    void restore()throws Exception {
        try{for(var entry:overrides.entrySet()){
            Key key=entry.getKey();Override value=entry.getValue();State current=driver.read(key);if(current==null)continue;
            if(current.paused==value.lastPause&&current.paused!=value.originalPause)driver.pause(key,value.originalPause);
            if(same(current.pitch,value.lastPitch)&&!same(current.pitch,value.originalPitch))driver.pitch(key,value.originalPitch);
        }}finally{overrides.clear();events=channels=pausedVoices=0;paused=false;speed=1;}
    }
    void reconstruct(double seconds)throws Exception {
        for(var entry:started.entrySet()){
            Key key=entry.getKey();Override override=overrides.get(key);if(override==null||authored.contains(key))continue;
            Window window=windows.get(key);if(window==null){window=driver.window(key);if(window!=null)windows.put(key,window);}if(window==null||window.length<=0)continue;
            double millis=offsets.getOrDefault(key,0)+Math.max(0,seconds-entry.getValue())*1000*override.originalPitch;
            int loop=window.loopEnd-window.loopStart;
            if(loop>0&&window.repeats!=0&&millis>=window.loopEnd){
                double repeats=Math.floor((millis-window.loopStart)/loop);if(window.repeats<0||repeats<=window.repeats)millis=window.loopStart+(millis-window.loopStart)%loop;else millis-=loop*(double)window.repeats;
            }
            if(millis>=window.length){if(driver.stop(key))finished++;overrides.remove(key);started.remove(key);offsets.remove(key);windows.remove(key);invalid.add(key);continue;}
            ReplayRender.audioPositionOverride.set(true);try{if(driver.seek(key,(int)millis)){repositioned++;if(key.event)pendingCommands=true;}}finally{ReplayRender.audioPositionOverride.remove();}
        }
    }
    /** Continuous source scripts own their event lifetime, including parameter-driven Studio events. */
    void reconstructAuthored(double seconds)throws Exception {
        for(Key key:authored){Double origin=started.get(key);Override value=overrides.get(key);if(origin==null||value==null)continue;
            int millis=(int)(offsets.getOrDefault(key,0)+Math.max(0,seconds-origin)*1000*value.originalPitch);
            State state=driver.read(key);if(state==null||state.timelineMillis==null)continue;
            // Avoid repeatedly retriggering Studio timeline instruments during ordinary playback.
            if(Math.abs((long)state.timelineMillis-millis)<=(paused?0:50))continue;
            ReplayRender.audioPositionOverride.set(true);try{if(driver.seek(key,millis)){repositioned++;pendingCommands=true;}}finally{ReplayRender.audioPositionOverride.remove();}
        }
    }
    void commit()throws Exception {if(pendingCommands){driver.commit();pendingCommands=false;}}
    void finish()throws Exception {
        if(ReplayRender.audioEvents==this)ReplayRender.audioEvents=null;
        owned.addAll(started.keySet());
        try{for(Key key:owned){if(driver.read(key)!=null)driver.stop(key);overrides.remove(key);}}
        finally{requestedChannels.clear();started.clear();generations.clear();offsets.clear();windows.clear();authored.clear();invalid.clear();owned.clear();baseline.clear();catchup=false;pendingCommands=false;restore();}
    }
    List<Map<String,Object>> details()throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();for(Key key:overrides.keySet()){
            State state=driver.read(key);if(state==null)continue;Map<String,Object> entry=new LinkedHashMap<>();entry.put("event",key.event);entry.put("handle",key.handle);entry.put("paused",state.paused);entry.put("pitch",state.pitch);if(state.timelineMillis!=null)entry.put("timeline_ms",state.timelineMillis);if(authored.contains(key)){entry.put("source_timeline_owned",true);entry.put("recorded_origin_seconds",started.get(key));entry.put("recorded_offset_ms",offsets.get(key));Integer playback=driver.playback(key);if(playback!=null)entry.put("native_playback_state",playback);}result.add(entry);
        }return result;
    }
}
