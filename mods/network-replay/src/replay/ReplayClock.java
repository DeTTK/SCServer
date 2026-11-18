package replay;

import java.lang.reflect.*;

/** Agent hook is inert outside offline playback; menus keep real frame time. */
public final class ReplayClock {
    private static volatile long frameHeartbeatNanos=System.nanoTime();
    private static volatile boolean frameHeartbeatSeen;
    /** Watchdog observes render progress, never the reversible simulation clock. */
    public static void frameHeartbeat(){frameHeartbeatNanos=System.nanoTime();frameHeartbeatSeen=true;}
    public static double watchdogSeconds(double originalSimulationSeconds){return frameHeartbeatSeen?frameHeartbeatNanos/1e9:originalSimulationSeconds;}
    public static volatile boolean active;
    public static volatile double seconds;
    public static volatile long epoch;
    public static volatile Throwable failure;
    public static volatile double effectRenderSeconds;public static volatile long effectRenderCalls;
    public static long presentationNanos(){ReplayRender.ScenePresentation selected=active?ReplayRender.scenePresentation:null;long time=selected==null?-1:selected.timeNs();return time>=0?time:(long)(seconds*1e9);}
    public static double presentationSeconds(){return presentationNanos()/1e9;}
    public static double renderSeconds(double realSeconds){if(!active)return realSeconds;effectRenderSeconds=presentationSeconds();effectRenderCalls++;return effectRenderSeconds;}
    private static double wallBase,wallOrigin;private static float wallRate=1;
    private static long environmentBase;
    private static volatile long visualBase;
    public static void seedVisual(long originNanos){if(originNanos<0)throw new IllegalArgumentException("Negative capture clock origin");visualBase=originNanos;}
    /** Imported body/weapon samplers use the capture clock, including its phase. */
    public static long visualNanos(){if(!active)return System.nanoTime();ReplayRender.ScenePresentation scene=ReplayRender.scenePresentation;Long selected=scene==null?null:scene.visualNanos();return selected!=null?selected:Math.addExact(visualBase,presentationNanos());}
    private static final java.util.Map<Field,Object> savedClocks=new java.util.LinkedHashMap<>();
    public static void seedWall(long epochMillis,double atSeconds,float rate){
        if(!Double.isFinite(atSeconds)||!Float.isFinite(rate)||rate<0)throw new IllegalArgumentException("Invalid recorded world clock");
        wallBase=epochMillis;wallOrigin=atSeconds;wallRate=rate;
    }
    public static long wallMillis(){ReplayRender.ScenePresentation scene=active?ReplayRender.scenePresentation:null;Long selected=scene==null?null:scene.wallMillis();return selected!=null?selected:(long)(wallBase+(presentationSeconds()-wallOrigin)*1000.0*wallRate);}
    /** The installed sky bridge receives recorded elapsed time, including pause. */
    public static long environmentNanos(){if(!active)return System.nanoTime();ReplayRender.ScenePresentation scene=ReplayRender.scenePresentation;Long selected=scene==null?null:scene.environmentNanos();return selected!=null?selected:Math.addExact(environmentBase,presentationNanos());}
    public record ClockOrigins(long visualOriginNs,long environmentOriginNs,long epochMillis,double originSeconds,float rate){}
    public static ClockOrigins captureClocks(){return new ClockOrigins(visualBase,environmentBase,(long)wallBase,wallOrigin,wallRate);}
    private static Field saveClockField(Class<?> type,String name)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);if(!savedClocks.containsKey(field))savedClocks.put(field,field.get(null));return field;
    }
    public static void environmentPacket(Object packet)throws Exception {
        long epoch=((Number)Native.field(packet,"catch")).longValue();float rate=((Number)Native.field(packet,"switch")).floatValue();
        Class<?> shared=Native.type("1553688601");for(String name:new String[]{"throw","finally","default","float","case"})saveClockField(shared,name);
        Class<?> bridge=Native.type("StandardJvmBootstrap");
        saveClockField(bridge,"environmentEpochMillis").setLong(null,epoch);
        saveClockField(bridge,"environmentClockSpeed").setFloat(null,rate);
        saveClockField(bridge,"environmentReceivedNanos").setLong(null,environmentNanos());
        seedWall(epoch,seconds,rate);
    }
    private static final ThreadLocal<Boolean> decoding=ThreadLocal.withInitial(()->false);
    public record WorldTime(long ticks,double seconds,long revision) {}
    private static final java.util.Map<Object,WorldTime> worldTimes=new java.util.WeakHashMap<>();
    private static long worldTimeRevision;
    /** Only packet dispatch writes the recorded baseline; filming overrides never replace it. */
    public static void recordedWorldTime(Object info,long ticks){
        if(active&&decoding.get())synchronized(worldTimes){worldTimes.put(info,new WorldTime(ticks,seconds,++worldTimeRevision));}
    }
    public static WorldTime worldTime(Object info){synchronized(worldTimes){return worldTimes.get(info);}}
    private static void clearWorldTimes(){synchronized(worldTimes){worldTimes.clear();}}
    public static void beginDecode(){decoding.set(true);}
    public static void endDecode(){decoding.remove();}
    private static final java.util.Map<Object,Float> rates=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    static final class TimerState {double base,origin,previous,debt;int baseTicks,executed;}
    private static final java.util.Map<Object,TimerState> states=new java.util.WeakHashMap<>();
    public static void configured(Object timer,float rate){if(!Float.isFinite(rate)||rate<=0||rate>1000)throw new IllegalArgumentException("Invalid native timer constructor rate "+rate);rates.put(timer,rate);}
    public static void begin(){ReplayRender.renderTime.clear();ReplayRender.renderThread=null;ReplayRender.scenePresentation=null;ReplayRender.mapChanges=null;ReplayRender.uiTicks=null;clearWorldTimes();epoch++;states.clear();seconds=0;visualBase=0;effectRenderSeconds=0;effectRenderCalls=0;failure=null;wallBase=System.currentTimeMillis();wallOrigin=0;wallRate=1;environmentBase=System.nanoTime();active=true;}
    /** New reconstruction epoch: keep each native timer's current baseline. */
    public static void rebase(){if(active){epoch++;states.clear();seconds=0;clearWorldTimes();}}
    public static void end(){ReplayRender.renderTime.clear();ReplayRender.renderThread=null;ReplayRender.scenePresentation=null;ReplayRender.mapChanges=null;ReplayRender.uiTicks=null;clearWorldTimes();active=false;ReplayRender.offlineConnection=null;ReplayRender.recorder=ReplayRender.recorderWorld=null;ReplayRender.recorderVisible=false;epoch++;states.clear();for(var entry:savedClocks.entrySet())try{entry.getKey().set(null,entry.getValue());}catch(IllegalAccessException e){failure=e;}savedClocks.clear();}
    public static Field field(Object o,String name)throws Exception{
        return Native.reflectedField(o,name);
    }
    public static void imported(Object object){
        if(!active)return;
        try{
            Float rate=rates.get(object);if(rate==null)throw new IllegalStateException("Native timer constructor was not captured");double hz=rate;
            TimerState state=states.get(object);if(state==null){state=new TimerState();state.base=field(object,"char").getDouble(object);state.baseTicks=field(object,"else").getInt(object);state.origin=state.previous=seconds;states.put(object,state);}
            ReplayRender.ScenePresentation scene=ReplayRender.scenePresentation;
            if(scene!=null&&scene.simulationFrozen()){
                // Selected poses already describe the requested instant. Moving
                // the playhead must never accrue forward physics/gameplay debt.
                field(object,"volatile").setInt(object,0);field(object,"native").setDouble(object,0);
                field(object,"char").setDouble(object,state.base+presentationSeconds()-state.origin);field(object,"else").setInt(object,state.baseTicks+state.executed);
                Native.call(object,"default","void",state.debt);Native.call(object,"char","void",0d);
                state.debt=0;state.previous=seconds;return;
            }
            double before=state.previous,delta=Math.max(0,seconds-before),previousFraction=state.debt;state.previous=seconds;
            state.debt+=delta*hz;int ticks=(int)(state.debt+1e-9);state.debt=Math.max(0,state.debt-ticks);
            field(object,"volatile").setInt(object,ticks);field(object,"native").setDouble(object,delta);
            // The first extends() subtracts the previous fraction, and
            // continue() adds the new fraction. Start from prior frame time.
            field(object,"char").setDouble(object,state.base+before-state.origin);field(object,"else").setInt(object,state.baseTicks+state.executed);state.executed+=ticks;
            // These are separate encrypted native stores. default() is the
            // previous frame's phase, subtracted by the first extends();
            // char() is the current phase, added by continue() and published
            // as render alpha. Leaving char() untouched leaks real GLFW time
            // into replay lighting and entity interpolation.
            Native.call(object,"default","void",previousFraction);
            Native.call(object,"char","void",state.debt);
        }catch(Exception e){failure=e;active=false;states.clear();}
    }
    /** Next ordinary tick of a primed native timer, in this reconstruction epoch. */
    public static long nextTickNs(Object timer){
        Float rate=rates.get(timer);TimerState state=states.get(timer);
        if(rate==null||state==null)throw new IllegalStateException("Replay simulation clock was not primed");
        return Math.round((state.origin+(state.executed+1.0)/rate)*1e9);
    }
    public static java.util.Map<String,Object> timerSnapshot(Object timer){
        TimerState state=states.get(timer);Float rate=rates.get(timer);
        if(state==null||rate==null)return java.util.Map.of();
        return java.util.Map.of("rate",rate,"origin_seconds",state.origin,"previous_seconds",state.previous,"executed_ticks",state.executed,"debt",state.debt,"next_tick_ns",nextTickNs(timer));
    }
    public static int boundedAllocation(int length){if(decoding.get()&&(length<0||length>32*1024*1024))throw new IllegalArgumentException("Invalid replay decode allocation: "+length);return length;}
}


