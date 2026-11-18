package replay;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Preserve the installed TP rig, while selecting an NPC's actual weapon. */
public final class ReplayRender {
    static final SceneTextureResidency textureResidency=new SceneTextureResidency();
    // The mod and the bootstrap agent use different class loaders. Their
    // shared residency owner must be accessed through public bridge methods.
    public static void retainSceneTexture(Object texture,long bytes,long budget)throws java.io.IOException {textureResidency.acquire(texture,bytes,budget);}
    public static void releaseSceneTexture(Object texture){textureResidency.release(texture);}
    public static long sceneTextureBytes(){return textureResidency.bytes();}
    public static int sceneTextureCount(){return textureResidency.textures();}
    /** Ordinary textures retain native streaming. Only recorded, explicitly
     * budgeted resources keep their prepared mip chain while replay is active. */
    public static boolean textureTrim(Object texture)throws Exception {
        if(ReplayClock.active&&textureResidency.owns(texture))return false;
        return (boolean)Native.call(texture,"szreplay$original$synchronized","boolean");
    }
    /** Optional preparation-only observer. The ordinary viewer retains the native hot path. */
    public interface MapChanges {void changed(Object object);}
    public static volatile MapChanges mapChanges;
    private static final ThreadLocal<Object> quarantinedChunk=new ThreadLocal<>();
    public static Object beginSceneChunk(Object chunk){Object previous=quarantinedChunk.get();quarantinedChunk.set(chunk);return previous;}
    public static void endSceneChunk(Object previous){if(previous==null)quarantinedChunk.remove();else quarantinedChunk.set(previous);}
    public static boolean sceneChunkNotificationAllowed(Object chunk){return quarantinedChunk.get()!=chunk;}
    public static void chunkLoadedInvoke(Object chunk,byte[] contents,int sections,int highIds,boolean full)throws Exception {
        // Canonical 1359994658.const can create a debug animal corpse in the
        // live world. Prepared chunks contain values, not live load events.
        if(!sceneChunkNotificationAllowed(chunk))return;
        Native.call(Native.type("1359994658"),"const","void",chunk,contents,sections,highIds,full);
    }
    public interface IndexedEffects {boolean manages(Object engine);boolean owns(Object layer);boolean captures(Object layer);default void rendered(Object engine,Object layer)throws Exception{}}
    public static volatile IndexedEffects indexedEffects;
    public static boolean effectLayerSkipped(Object engine,Object layer){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&selected.manages(engine)&&!selected.owns(layer);}
    public static boolean effectLayerEmpty(boolean original,Object layer){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&(selected.owns(layer)||selected.captures(layer))?false:original;}
    public static int effectLayerCount(int original,Object layer){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&selected.owns(layer)?Math.max(1,original):original;}
    public static boolean effectOriginFrozen(Object layer){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&selected.owns(layer);}
    public static float effectCaptureDistance(float original,Object layer){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&selected.captures(layer)?0f:original;}
    public static double effectCaptureWeaponDistance(double original){IndexedEffects selected=indexedEffects;return ReplayClock.active&&selected!=null&&selected.captures(null)?0d:original;}
    public static boolean effectCaptureVisibility(boolean original,Object effect){
        if(original||!ReplayClock.active||indexedEffects==null)return original;
        try{return indexedEffects.captures(Native.field(effect,"default"));}catch(Exception failure){ReplayClock.failure=failure;return original;}
    }
    public static void effectRendered(Object engine,Object layer){IndexedEffects selected=indexedEffects;if(ReplayClock.active&&selected!=null)try{selected.rendered(engine,layer);}catch(Exception failure){ReplayClock.failure=failure;}}
    public interface IndexedTracers {void born(Object beam)throws Exception;void origin(Object beam,float x,float y,float z);Set<?> textures(Set<?> original);List<?> beams(Object texture,List<?> original);Float progress(Object beam);}
    public static volatile IndexedTracers indexedTracers;
    public interface IndexedSprites {List<?> groups(Object renderer,List<?> original);}
    public static volatile IndexedSprites indexedSprites;
    public static List<?> sceneLegacySprites(List<?> original,Object renderer){IndexedSprites selected=indexedSprites;return ReplayClock.active&&selected!=null?selected.groups(renderer,original):original;}
    public static void tracerBorn(Object beam){IndexedTracers selected=indexedTracers;if(ReplayClock.active&&selected!=null)try{selected.born(beam);}catch(Exception failure){ReplayClock.failure=failure;}}
    public static void tracerOrigin(Object beam,float x,float y,float z){IndexedTracers selected=indexedTracers;if(ReplayClock.active&&selected!=null)selected.origin(beam,x,y,z);}
    public static Set<?> tracerTextures(Set<?> original){IndexedTracers selected=indexedTracers;return ReplayClock.active&&selected!=null?selected.textures(original):original;}
    public static List<?> tracerBeams(List<?> original,Object texture){IndexedTracers selected=indexedTracers;return ReplayClock.active&&selected!=null?selected.beams(texture,original):original;}
    public static float tracerProgress(float original,Object beam){IndexedTracers selected=indexedTracers;Float value=ReplayClock.active&&selected!=null?selected.progress(beam):null;return value==null?original:value;}
    public static void sceneMapChanged(Object object){MapChanges observer=mapChanges;if(ReplayClock.active&&observer!=null)observer.changed(object);}
    /** A published scene frame owns its presentation values; simulation objects remain untouched. */
    public interface ScenePresentation {
        Object position(Object entity);
        Object pose(Object graph);
        Object environment();
        default Object bodyPose(Object renderer){return null;}
        Object attribute(Object attribute,Object original);
        default Float look(Object entity,int axis){return null;}
        default boolean simulationFrozen(){return false;}
        default Collection<?> entities(Object world,Collection<?> original){return original;}
        default Object entity(Object world,int id,Object original){return original;}
        default Integer sourceId(Object entity){return null;}
        default boolean actorVisible(Object renderer,Object entity){return false;}
        default Object chunk(Object provider,int x,int z){return null;}
        default Object mapManager(Object world){return null;}
        default boolean ownsMapObject(Object prefab){return false;}
        default Object prefabSpatial(Object prefab,int slot,Object original){return original;}
        default Object terrainGrid(Object renderer,Object original){return original;}
        default boolean updateTerrain(Object grid,Object view)throws Exception {return false;}
        default boolean ownsTerrainCell(Object cell){return false;}
        default Integer drawTerrain(Object grid,Object view,int pass)throws Exception {return null;}
        default long timeNs(){return -1;}
        default Long visualNanos(){return null;}
        default Long environmentNanos(){return null;}
        default Long wallMillis(){return null;}
        default Long dayTicks(){return null;}
        default void beginRender()throws Exception {}
    }
    public static volatile ScenePresentation scenePresentation;
    public static Object scenePrefabSpatial(Object prefab,Object original,int slot){ScenePresentation selected=ReplayClock.active?scenePresentation:null;return selected==null?original:selected.prefabSpatial(prefab,slot,original);}
    public static volatile Object viewerCamera;
    public static double shadowCameraAxis(double original,int axis)throws Exception {
        Object camera=viewerCamera;if(!sceneIndependentVisibility()||camera==null)return original;
        Object position=Native.field(camera,"assert");return ((Number)Native.field(position,axis==0?"case":axis==1?"void":"import")).doubleValue();
    }
    /** Real GUI clock, isolated from recorded world and actor simulation clocks. */
    public interface UiTicks {int sample(Object timer,int original);default double time(Object timer,double original){return original;}}
    public static volatile UiTicks uiTicks;
    private static final ThreadLocal<Integer> uiDepth=ThreadLocal.withInitial(()->0);
    public static boolean nativeUiScope(){return uiDepth.get()>0;}
    /** Scoped to the unregistered FP actor's preparation on this thread. */
    public static final ThreadLocal<Double> fpTime=new ThreadLocal<>();
    static final SceneRenderTime renderTime=new SceneRenderTime();
    static volatile Thread renderThread;
    public static double uiTimeInvoke(Object timer,double original){
        Double fp=fpTime.get();if(fp!=null)return fp;
        UiTicks ui=uiTicks;if(nativeUiScope())return ui!=null?ui.time(timer,original):original;
        // GPU buffer scheduling must keep advancing even on a paused scene.
        return original;
    }
    public static long sceneDayTicks(long original){ScenePresentation scene=ReplayClock.active?scenePresentation:null;Long day=scene==null?null:scene.dayTicks();return day==null?original:day;}
    public static double sceneDayPhase(double original){ScenePresentation scene=ReplayClock.active?scenePresentation:null;Long day=scene==null?null:scene.dayTicks();return day==null?original:(day+46666.66666666667)/160000.0;}
    public static Object sceneSun(Object original)throws Exception {return sceneIndependentVisibility()?Native.field(Native.field(Native.type("569545768"),"assert"),"catch"):original;}
    public static float sceneShadowAlpha(float original){return sceneIndependentVisibility()?1f:original;}
    private static long lastRenderFrameNs;
    static volatile float realFrameSeconds=1f/60;
    static void recordFrameTime(long now){
        long previous=lastRenderFrameNs;lastRenderFrameNs=now;
        if(previous!=0&&now>previous)realFrameSeconds=(float)Math.max(.001,Math.min(.25,(now-previous)/1e9));
    }
    /** Shadow GPU work is scheduled by rendered frames, never by simulation debt. */
    public static float shadowFrameDelta(float original){return sceneIndependentVisibility()?realFrameSeconds:original;}
    public static void nativeUiInvoke(Object target,String method,Object[] args)throws Exception {
        int depth=uiDepth.get();uiDepth.set(depth+1);
        try{
            Class<?>[] types=method.equals("else")?new Class<?>[]{Native.type("622415611")}:method.equals("for")?new Class<?>[]{int.class,int.class,float.class}:new Class<?>[0];
            publicMethod(target.getClass(),"szreplay$ui$"+method,types).invoke(target,args);
        }
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Error error)throw error;if(cause instanceof Exception exception)throw exception;throw failure;}
        finally{if(depth==0)uiDepth.remove();else uiDepth.set(depth);}
    }
    public static int uiTickInvoke(Object timer,int original){
        UiTicks ui=uiTicks;return sceneIndependentVisibility()&&ui!=null?ui.sample(timer,original):original;
    }
    public static volatile long sceneRenderEpoch;
    public static void beginSceneRenderFrame(){
        recordFrameTime(System.nanoTime());
        ReplayClock.frameHeartbeat();sceneRenderEpoch++;renderThread=Thread.currentThread();
        ScenePresentation scene=ReplayClock.active?scenePresentation:null;
        if(scene!=null)try{scene.beginRender();}catch(Exception failure){ReplayClock.failure=failure;}
    }
    public static boolean sceneActorVisible(Object renderer,Object entity){
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        return selected!=null&&selected.simulationFrozen()&&selected.actorVisible(renderer,entity);
    }
    public static int sceneCameraTarget(Object entity)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;Integer source=selected==null?null:selected.sourceId(entity);
        return source!=null?source:((Number)Native.call(entity,"for","int")).intValue();
    }
    /** Resource descriptors of quarantined recorder variants, removed by their owner. */
    public static final Map<Object,Object> sceneFpItems=Collections.synchronizedMap(new IdentityHashMap<>());
    public record FpView(Object actor,Object owner,Object item) {}
    private static final ThreadLocal<FpView> fpView=new ThreadLocal<>();
    /** Bind legacy local-player weapon queries only inside the selected FP draw. */
    public static AutoCloseable beginFpView(Object actor,Object owner,Object item){
        FpView previous=fpView.get();fpView.set(new FpView(actor,owner,item));
        return ()->{if(previous==null)fpView.remove();else fpView.set(previous);};
    }
    public static Object sceneFpOwnerOverride(Object requested){
        FpView view=ReplayClock.active?fpView.get():null;
        return view!=null&&(requested==view.actor()||requested==recorder)?view.owner():null;
    }
    public static boolean sceneFpStaticModel(boolean original,Object item)throws Exception {
        if(original||fpTime.get()==null)return original;
        boolean owned=false;synchronized(sceneFpItems){for(Object value:sceneFpItems.values())if(value==item){owned=true;break;}}
        if(!owned)return false;Object definition=Native.call(item,"continue","168071319");
        return definition!=null&&Native.type("64739610").isInstance(definition)&&Native.call(definition,"public","1942284906")!=null;
    }
    public static final Map<Object,Object> sceneFpPoses=Collections.synchronizedMap(new IdentityHashMap<>());
    public static Object sceneFpItemInvoke(Object entity)throws Exception {
        FpView view=ReplayClock.active?fpView.get():null;if(view!=null&&(entity==view.actor()||entity==recorder))return view.item();
        synchronized(sceneFpItems){if(sceneFpItems.containsKey(entity))return sceneFpItems.get(entity);}
        return Native.call(Native.type("764970879"),"szreplay$original$enum","211097523",entity);
    }
    public static final Map<Object,Object> sceneBodyViews=Collections.synchronizedMap(new IdentityHashMap<>());
    public static final Map<Object,Object> sceneEquipmentItems=Collections.synchronizedMap(new IdentityHashMap<>());
    public static final Map<Object,Object> sceneEquipmentStyles=Collections.synchronizedMap(new IdentityHashMap<>());
    public static Object sceneBodyViewInvoke(Object entity)throws Exception {
        synchronized(sceneBodyViews){if(sceneBodyViews.containsKey(entity))return sceneBodyViews.get(entity);}
        return Native.call(entity,"szreplay$original$case","658914576");
    }
    public static Object sceneEquipmentItemInvoke(Object provider)throws Exception {
        synchronized(sceneEquipmentItems){if(sceneEquipmentItems.containsKey(provider))return sceneEquipmentItems.get(provider);}
        return Native.call(provider,"szreplay$original$if","211097523");
    }
    public static Object sceneEquipmentStyleInvoke(Object entity)throws Exception {
        synchronized(sceneEquipmentStyles){if(sceneEquipmentStyles.containsKey(entity))return sceneEquipmentStyles.get(entity);}
        return Native.call(Native.type("916325480"),"szreplay$original$double","916325480",entity);
    }
    public static long scenePositionReads,scenePoseReads,sceneEnvironmentReads,sceneAttributeReads;
    public static long sceneMapManagerReads;
    public static final java.util.concurrent.atomic.AtomicLong sceneMapMeshSubmissions=new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong sceneTerrainMeshSubmissions=new java.util.concurrent.atomic.AtomicLong();
    public static volatile boolean sceneSubmissionAudit;
    public static void sceneTerrainMeshSubmitted(boolean rendered,Object cell){if(!sceneSubmissionAudit)return;ScenePresentation selected=ReplayClock.active?scenePresentation:null;if(rendered&&selected!=null&&selected.ownsTerrainCell(cell))sceneTerrainMeshSubmissions.incrementAndGet();}
    public static void sceneMapMeshSubmitted(Object prefab){if(!sceneSubmissionAudit)return;ScenePresentation selected=ReplayClock.active?scenePresentation:null;if(selected!=null&&selected.ownsMapObject(prefab))sceneMapMeshSubmissions.incrementAndGet();}
    /** Temporal GPU queries describe an older camera/world and are invalid for a selected scene.
     * Native frustum, distance, material and depth tests continue to run. */
    public static boolean sceneIndependentVisibility(){ScenePresentation selected=ReplayClock.active?scenePresentation:null;return selected!=null&&selected.simulationFrozen();}
    /** Preserves native stable signed-material ordering with linear radix passes. */
    public static void sceneBatchSort(Object list,Object scratch)throws Exception {
        if(!sceneIndependentVisibility()){Native.call(list,"boolean","void",scratch);return;}
        Object[] values=(Object[])Native.field(list,"const");int size=((Number)Native.field(list,"try")).intValue();
        SceneBatchOrder.sort(values,size);
    }
    public static Object sceneTerrainGrid(Object renderer,Object original){ScenePresentation selected=ReplayClock.active?scenePresentation:null;return selected==null?original:selected.terrainGrid(renderer,original);}
    public static boolean sceneTerrainUpdate(Object grid,Object view)throws Exception {ScenePresentation selected=ReplayClock.active?scenePresentation:null;if(selected==null||!selected.simulationFrozen())return false;selected.updateTerrain(grid,view);return true;}
    public static int sceneTerrainDrawInvoke(Object grid,Object view,int pass,String method)throws Exception {ScenePresentation selected=ReplayClock.active?scenePresentation:null;Integer count=selected==null?null:selected.drawTerrain(grid,view,pass);return count!=null?count:((Number)Native.call(grid,"szreplay$original$"+method,"int",view,pass)).intValue();}
    public static Object scenePositionInvoke(Object entity,float alpha)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        Object value=selected==null?null:selected.position(entity);
        if(value!=null)scenePositionReads++;
        return value!=null?value:Native.call(Native.type("576005245"),"szreplay$original$char","153304200",entity,alpha);
    }
    public static Object scenePoseInvoke(Object graph)throws Exception {
        return scenePoseMethodInvoke(graph,"final");
    }
    public static Object scenePoseEvaluateInvoke(Object graph)throws Exception {
        return scenePoseMethodInvoke(graph,"extends");
    }
    public static Object sceneRemainsPoseInvoke(Object graph)throws Exception {
        return scenePoseMethodInvoke(graph,"try");
    }
    public static Object sceneBodyRenderPose(Object original,Object renderer){ScenePresentation selected=ReplayClock.active?scenePresentation:null;Object pose=selected==null?null:selected.bodyPose(renderer);return pose==null?original:pose;}
    public static boolean sceneBodyRendererFrozen(Object renderer){ScenePresentation selected=ReplayClock.active?scenePresentation:null;return selected!=null&&selected.bodyPose(renderer)!=null;}
    /** Render admission only. The entity's container/bag mode is unchanged. */
    public static Boolean sceneCorpseDrawDecision(Object body)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        if(selected==null||!selected.simulationFrozen()||selected.sourceId(body)==null)return null;
        Object renderer=Native.field(body,"long");
        if(renderer!=null&&!Native.type("1326794892").isInstance(renderer))return null;
        if(renderer==null||selected.bodyPose(renderer)==null)return null;
        if((boolean)Native.call(Native.field(Native.type("799166442"),"else"),"const","boolean",Native.field(Native.type("1368116611"),"RAGDOLL")))return false;
        return !(boolean)Native.call(body,"g","boolean")
            && !(boolean)Native.type("544082630").getDeclaredField("abstract").get(body);
    }
    static Object scenePoseMethodInvoke(Object graph,String method)throws Exception {
        Object fp=ReplayClock.active?sceneFpPoses.get(graph):null;if(fp!=null)return fp;
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        Object value=selected==null?null:selected.pose(graph);
        if(value!=null)scenePoseReads++;
        return value!=null?value:Native.call(graph,"szreplay$original$"+method,"1118769848");
    }
    public static float sceneLookInvoke(Object entity,String method,int axis)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        Float value=selected==null?null:selected.look(entity,axis);
        return value!=null?value:((Number)Native.call(entity,"szreplay$original$"+method,"float")).floatValue();
    }
    public static Object sceneEnvironmentInvoke()throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        Object value=selected==null?null:selected.environment();
        if(value!=null)sceneEnvironmentReads++;
        return value!=null?value:Native.call(Native.type("569545768"),"szreplay$original$final","648055939");
    }
    /** Native fog/light preparation uses the shader's field and companion getter,
     * not just the public static getter. Keep those paths on the published frame. */
    public static Object sceneEnvironmentOrOriginal(Object original){
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        Object value=selected!=null&&selected.simulationFrozen()?selected.environment():null;
        return value!=null?value:original;
    }
    public static void sceneWeatherModifierInvoke(Object modifier,Object parameters)throws Exception {
        // Camera-dependent fog-height smoothing is already baked into the DTO.
        // Running it again would mutate a paused frame and depend on seek order.
        if(sceneIndependentVisibility())return;
        Native.call(modifier,"break","void",parameters);
    }
    public static Object sceneAttributeInvoke(Object attribute,Object original)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        if(selected!=null)sceneAttributeReads++;
        return selected==null?original:selected.attribute(attribute,original);
    }
    public static Collection<?> sceneEntitiesInvoke(Object world)throws Exception {
        Collection<?> original=(Collection<?>)Native.call(world,"szreplay$original$volatile","java.util.Collection");
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        return selected==null?original:selected.entities(world,original);
    }
    public static Object sceneEntityInvoke(Object world,int id)throws Exception {
        Object original=Native.call(world,"szreplay$original$continue","1217489680",id);
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;
        return selected==null?original:selected.entity(world,id,original);
    }
    public static Object sceneChunkInvoke(Object provider,int x,int z)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;Object chunk=selected==null?null:selected.chunk(provider,x,z);
        return chunk!=null?chunk:Native.call(provider,"szreplay$original$abstract","812508934",x,z);
    }
    public static Object sceneMapManagerInvoke(Object world)throws Exception {
        ScenePresentation selected=ReplayClock.active?scenePresentation:null;Object manager=selected==null?null:selected.mapManager(world);
        if(manager!=null)sceneMapManagerReads++;
        return manager!=null?manager:Native.call(world,"szreplay$original$do","493563411");
    }
    /** Stops native gameplay from recapturing/centering a spectator-owned cursor. */
    public static volatile boolean cursorOwned;
    /** Hide the game's recorded HUD while retaining native menus and tools. */
    public static boolean hideGameHud(boolean original){return ReplayClock.active||original;}
    /** The stock backend overwrites its queued press with the polled state.
     * Retain short press/release pairs for one frame, as ImGui expects. */
    public static void cursorUiMouseUpdate(Object backend)throws Exception {
        int presses=0;if(cursorOwned){boolean[] pending=(boolean[])Native.field(backend,"void");for(int i=0;i<pending.length;i++)if(pending[i])presses|=1<<i;}
        Native.call(backend,"szreplay$original$double","void");
        if(presses!=0){var io=imgui.ImGui.getIO();for(int i=0;i<5;i++)if((presses&(1<<i))!=0)io.setMouseDown(i,true);}
        headlessMouse();
    }
    public static volatile Object offlineConnection;
    public static volatile Object recorder,recorderWorld;
    public static volatile boolean recorderVisible;
    private static boolean restoreUiViewports;
    /** Changing this flag mid-frame skips the platform completion of that frame.
     * The native GUI calls this before polling its backend and starting the next frame. */
    public static void beforeUiFrame(){
        var io=imgui.ImGui.getIO();
        // Cursor ownership does not own ImGui's platform windows. Keep the
        // native viewport configuration so panels can be dragged to other monitors.
    }
    /** Re-enable platform windows only after the disabled frame has ended. */
    public static void afterUiFrame(){
        if(!restoreUiViewports)return;
        var io=imgui.ImGui.getIO();
        if(!io.hasConfigFlags(1024)){
            io.addConfigFlags(1024);
            if(imgui.ImGui.getFrameCount()>0)imgui.ImGui.updatePlatformWindows();
        }
        restoreUiViewports=false;
    }
    public static boolean allowConnection(Object connection){return !ReplayClock.active||connection==offlineConnection;}
    /** Preserve other mods' input policies; the replay does not gate native actions. */
    public static boolean actionBlocked(Object context)throws Exception {
        return (boolean)Native.call(Native.type("szmods.runtime.NativeFlightHooks"),"actionBlocked","boolean");
    }
    /** Viewer tools belong to the offline viewer, not the recorded player's rank. */
    public static boolean localViewerTools(Object entity){
        return sceneIndependentVisibility()&&entity!=null&&entity==recorder;
    }
    public static boolean viewerLowTools(Object entity)throws Exception {
        return localViewerTools(entity)||(boolean)Native.call(Native.type("353451775"),"boolean","boolean",entity);
    }
    public static boolean viewerFullTools(Object entity)throws Exception {
        return localViewerTools(entity)||(boolean)Native.call(Native.type("353451775"),"assert","boolean",entity);
    }
    public static boolean nativeUiCaptures(Object game)throws Exception {
        if(Native.call(game,"float","622415611")!=null)return true;
        return (boolean)Native.call(Native.field(Native.type("925863226"),"void"),"for","boolean");
    }
    public static void cursorFocusInvoke(Object game)throws Exception {
        if(!cursorOwned){Native.call(game,"szreplay$original$extends","void");return;}
        // A native click-to-focus callback must not dismiss an open menu or
        // editor when the viewer owns mouse input.
        if(nativeUiCaptures(game))return;
        long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        if(window!=0&&(testInput()||org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_FOCUSED)==org.lwjgl.glfw.GLFW.GLFW_TRUE)
                &&!(boolean)Native.call(game,"try","boolean")){
            Native.call(game,"abstract","void",true);
            Native.type("1402131154").getMethod("else",Native.type("622415611")).invoke(game,new Object[]{null});
        }
    }
    public interface WorldTicks {void completed(Object game)throws Exception;}
    public static volatile WorldTicks worldTicks;
    public static void nativeTickCompleted(Object game){
        WorldTicks provider=worldTicks;
        if(!ReplayClock.active||provider==null||!Boolean.getBoolean("sz.replay.qa.tickloop"))return;
        try{provider.completed(game);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public interface ActorTimes {
        void created(Object actor)throws Exception;
        void configured(Object actor,Object timer)throws Exception;
        Double sample(Object timer)throws Exception;
        default void stateChanged(Object actor)throws Exception {}
        default void graphUpdated(Object rig)throws Exception {}
        default void tailApplied(Object actor,long order,double received,double consumed,int flags,int bytes,String trigger)throws Exception {}
        default boolean allowGraphUpdate(Object graphObject)throws Exception{return true;}
        default boolean allowSceneGraphPreparation(){return false;}
    }
    public static volatile ActorTimes actorTimes;
    public static boolean allowNativeGraphUpdate(Object object){
        ActorTimes provider=actorTimes;
        // Indexed poses have no animation simulation to advance. Explicit model
        // preparation is thread scoped; ordinary frame getters never scan births.
        if(sceneIndependentVisibility())return provider!=null&&provider.allowSceneGraphPreparation();
        if(!ReplayClock.active||provider==null||!Boolean.getBoolean("sz.replay.qa.graphschedule"))return true;
        try{return provider.allowGraphUpdate(object);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;return false;}
    }
    public static void nativeActorStateChanged(Object actor){
        ActorTimes provider=actorTimes;if(!ReplayClock.active||provider==null)return;
        try{provider.stateChanged(actor);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static void actorGraphUpdated(boolean changed,Object rig){
        ActorTimes provider=actorTimes;if(!changed||!ReplayClock.active||provider==null||!Boolean.getBoolean("sz.replay.qa.tailtrace"))return;
        try{provider.graphUpdated(rig);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static void actorCreated(Object actor){
        ActorTimes provider=actorTimes;if(!ReplayClock.active||provider==null)return;
        try{provider.created(actor);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static void actorTimerConfigured(Object timer,Object actor){
        ActorTimes provider=actorTimes;if(!ReplayClock.active||provider==null||timer==null)return;
        try{provider.configured(actor,timer);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static double actorTimeInvoke(Object timer)throws Exception {
        ActorTimes provider=actorTimes;
        if(ReplayClock.active&&provider!=null)try{Double value=provider.sample(timer);if(value!=null)return value;}catch(Exception|LinkageError failure){ReplayClock.failure=failure;throw failure;}
        return ((Number)Native.call(timer,"szreplay$original$do","double")).doubleValue();
    }
    public interface BodyPoses {
        void prepared(Object remains,Object suppliedPose,int stage)throws Exception;
        default Object initial(Object remains,Object original)throws Exception{return original;}
        default void retired(Object actor)throws Exception{}
        default void admitted(Object actor)throws Exception{}
        default void physicsCreated(Object body)throws Exception{}
        default boolean physicsStep(Object world)throws Exception{return false;}
    }
    public static volatile BodyPoses bodyPoses;
    public static Object bodyInitialPose(Object original,Object remains){BodyPoses provider=bodyPoses;if(!ReplayClock.active||provider==null)return original;try{return provider.initial(remains,original);}catch(Exception failure){ReplayClock.failure=failure;return original;}}
    public static void bodyOwnerRetired(Object actor){BodyPoses provider=bodyPoses;if(ReplayClock.active&&provider!=null)try{provider.retired(actor);}catch(Exception failure){ReplayClock.failure=failure;}}
    public static void bodyOwnerAdmitted(Object actor){BodyPoses provider=bodyPoses;if(ReplayClock.active&&provider!=null)try{provider.admitted(actor);}catch(Exception failure){ReplayClock.failure=failure;}}
    public static void bodyPrepared(Object remains,Object suppliedPose,int stage){
        BodyPoses provider=bodyPoses;if(!ReplayClock.active||provider==null)return;
        try{provider.prepared(remains,suppliedPose,stage);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static void bodyPhysicsCreated(Object body){
        BodyPoses provider=bodyPoses;if(!ReplayClock.active||provider==null||body==null)return;
        try{provider.physicsCreated(body);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static void bodyPhysicsInvoke(Object world,float originalDelta)throws Exception {
        BodyPoses provider=bodyPoses;
        if(ReplayClock.active&&provider!=null)try{if(provider.physicsStep(world))return;}catch(Exception|LinkageError failure){ReplayClock.failure=failure;throw failure;}
        Native.call(world,"szreplay$original$while","void",originalDelta);
    }
    public interface Extractors {
        boolean accepts(Object component);
        void tick(Object component)throws Exception;
        void progress(Object component)throws Exception;
        void configured(Object component,Object configuration)throws Exception;
        void removed(Object component)throws Exception;
        default void hudUpdated(Object hud)throws Exception{}
        default void hudDraw(Object hud,int width,float partial)throws Exception{}
        default String activationPrompt(Object component)throws Exception{return null;}
    }
    public static volatile Extractors extractors;
    public static boolean sourceExtractorActive(Object component){Extractors provider=extractors;return ReplayClock.active&&provider!=null&&provider.accepts(component);}
    public static boolean sourceExtractor(Object component,int operation,Object configuration){
        if(!sourceExtractorActive(component))return false;
        try{switch(operation){case 0->extractors.tick(component);case 1->extractors.progress(component);case 2->extractors.configured(component,configuration);case 3->extractors.removed(component);default->throw new IllegalArgumentException("Extractor operation "+operation);}}
        catch(Exception|LinkageError failure){ReplayClock.failure=failure;}return true;
    }
    public static void sourceExtractorHudUpdated(Object hud){
        Extractors provider=extractors;if(!ReplayClock.active||provider==null)return;
        try{provider.hudUpdated(hud);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}
    }
    public static boolean sourceExtractorHudDraw(Object hud,int width,float partial){
        Extractors provider=extractors;if(!ReplayClock.active||provider==null)return false;
        try{provider.hudDraw(hud,width,partial);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}return true;
    }
    public static String sourceExtractorActivationPrompt(Object component){
        if(!sourceExtractorActive(component))return null;
        try{return extractors.activationPrompt(component);}catch(Exception|LinkageError failure){ReplayClock.failure=failure;return "";}
    }
    public record WeatherBlend(float electric,Object electricKind,Map<Object,Float> timeline,boolean replaceNative) {
        public WeatherBlend(float electric,Object electricKind,Map<Object,Float> timeline){this(electric,electricKind,timeline,false);}
        public WeatherBlend {timeline=Map.copyOf(timeline);}
        public static final WeatherBlend EMPTY=new WeatherBlend(0,null,Map.of());
        boolean empty(){return !replaceNative&&electric==0&&timeline.isEmpty();}
    }
    public interface WeatherWeights {WeatherBlend snapshot();}
    public static volatile WeatherWeights weatherWeights;
    public static volatile long weatherZoneCalls,weatherBlendCalls;
    public static volatile float weatherBlendStrength;
    public interface WeatherTick {void tick()throws Exception;}
    public static volatile WeatherTick weatherTick;
    /** Source screen effects own a nullable background runner; the old client owns a fixed snow runner. */
    public interface WeatherSound {
        Object runner();
        void enabledChange(Object shader,boolean enabled)throws Exception;
        void stopped(Object runner)throws Exception;
    }
    public static volatile WeatherSound weatherSound;
    public static boolean sourceWeatherEffectsActive(){return ReplayClock.active&&weatherSound!=null;}
    public static Object weatherSound(Object original){WeatherSound provider=weatherSound;return ReplayClock.active&&provider!=null?provider.runner():original;}
    public static void weatherEnabledChange(Object shader,boolean enabled)throws Exception {WeatherSound provider=weatherSound;if(ReplayClock.active&&provider!=null)provider.enabledChange(shader,enabled);}
    public static void weatherSoundUpdate(Object runner,float level,int minutes)throws Exception {if(runner!=null)Native.call(runner,"throw","void",level,minutes);}
    public static void weatherSoundStop(Object runner)throws Exception {
        if(runner==null)return;
        Native.call(runner,"byte","void");WeatherSound provider=weatherSound;if(ReplayClock.active&&provider!=null)provider.stopped(runner);
    }
    public static void sourceWeatherTick(){WeatherTick listener=weatherTick;if(!ReplayClock.active||listener==null)return;try{listener.tick();}catch(Exception|LinkageError failure){ReplayClock.failure=failure;}}
    public static volatile boolean shakeClockEnabled;
    public static volatile long shakeClockCalls,cameraShakeCalls;
    private static final Map<Object,Double> shakeClocks=new WeakHashMap<>();
    public static void resetShakeClock(boolean enabled){shakeClocks.clear();shakeClockCalls=cameraShakeCalls=0;shakeClockEnabled=enabled;}
    public static float shakeDelta(Object controller,float realDelta){if(!ReplayClock.active||!shakeClockEnabled)return realDelta;double now=ReplayClock.seconds;Double previous=shakeClocks.put(controller,now);shakeClockCalls++;return previous==null?0f:(float)Math.max(0,now-previous);}
    private static final ThreadLocal<ArrayDeque<WeatherBlend>> weatherZones=ThreadLocal.withInitial(ArrayDeque::new);
    public static void beginWeatherZone(){WeatherWeights provider=weatherWeights;WeatherBlend blend=ReplayClock.active&&provider!=null?provider.snapshot():WeatherBlend.EMPTY;weatherZones.get().push(blend);if(ReplayClock.active){weatherZoneCalls++;if(!blend.empty())weatherBlendCalls++;}}
    public static void endWeatherZone(){ArrayDeque<WeatherBlend> zones=weatherZones.get();if(!zones.isEmpty())zones.pop();if(zones.isEmpty())weatherZones.remove();}
    static WeatherBlend weatherZone(){ArrayDeque<WeatherBlend> zones=weatherZones.get();return zones.isEmpty()?WeatherBlend.EMPTY:zones.peek();}
    public static Object weatherZoneInvoke(Object builder,Object world,Object zone,double time)throws Exception {
        beginWeatherZone();try{return Native.call(builder,"szreplay$original$catch","648055939",world,zone,time);}finally{endWeatherZone();}
    }
    public static float weatherStrength(float ejection,float snowstorm){
        WeatherBlend blend=weatherZone();if(blend.empty())return Math.max(ejection,snowstorm);float weight=blend.replaceNative()?blend.electric():Math.max(Math.max(ejection,snowstorm),blend.electric());
        for(float extra:blend.timeline().values())weight=Math.max(weight,extra);weatherBlendStrength=weight;return weight;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) public static Map weatherMap(Map nativeMap){
        WeatherBlend blend=weatherZone();if(blend.empty())return nativeMap;
        // Source inserts electric, then timeline entries AFTER legacy entries.
        Map result=blend.replaceNative()?new HashMap():new HashMap(nativeMap);
        if(blend.electric()>0){if(blend.electricKind()==null)throw new IllegalStateException("Unbound electric weather phenomenon");result.put(blend.electricKind(),blend.electric());}
        for(var entry:blend.timeline().entrySet())if(entry.getValue()>0)result.put(entry.getKey(),entry.getValue());
        return result;
    }
    public interface QuestHud {int draw(Object renderer,Object position,int offset)throws Exception;}
    public static volatile QuestHud questHud;
    public interface EffectTransform {Object transform(Object matrix,Object entity,boolean initial)throws Exception;}
    public static volatile EffectTransform effectTransform;
    /** Resource names resolve through the game's existing FMOD file callbacks. */
    public static final Map<Long,String> soundResources=new ConcurrentHashMap<>();
    public static void soundResourceLoaded(long handle,String path){if(handle!=0&&path!=null)soundResources.put(handle,path);}
    public static void soundResourceReleased(int result,long handle){if(result==0)soundResources.remove(handle);}
    public interface AudioEvents {void started(long handle);void channel(long handle);void positioned(boolean event,long handle,int millis);default void configured(long handle){}default void requested(int id){}default void configured(int id,long handle){configured(handle);}default void authored(long handle,int millis){}default void releaseAuthored(long handle){}}
    public static volatile AudioEvents audioEvents;
    public static void eventStarted(int result,long handle){AudioEvents listener=audioEvents;if(result==0&&ReplayClock.active&&listener!=null)listener.started(handle);}
    public static void channelStarted(int result,long address){AudioEvents listener=audioEvents;if(result==0&&address!=0&&ReplayClock.active&&listener!=null)listener.channel(org.lwjgl.system.MemoryUtil.memGetAddress(address));}
    public static void channelConfigured(long handle){AudioEvents listener=audioEvents;if(handle!=0&&ReplayClock.active&&listener!=null)listener.configured(handle);}
    public static void channelRequested(int id){AudioEvents listener=audioEvents;if(ReplayClock.active&&listener!=null)listener.requested(id);}
    public static void channelConfigured(long handle,int id){AudioEvents listener=audioEvents;if(handle!=0&&ReplayClock.active&&listener!=null)listener.configured(id,handle);}
    public static final ThreadLocal<Boolean> audioPositionOverride=ThreadLocal.withInitial(()->false);
    public static void audioPositioned(int result,boolean event,long handle,int millis,int unit){AudioEvents listener=audioEvents;if(result==0&&unit==1&&ReplayClock.active&&listener!=null&&!audioPositionOverride.get())listener.positioned(event,handle,millis);}
    public static void timelineSoundStarted(long handle,int millis){AudioEvents listener=audioEvents;if(handle!=0&&ReplayClock.active&&listener!=null)listener.authored(handle,millis);}
    public static void timelineSoundStopped(long handle){AudioEvents listener=audioEvents;if(handle!=0&&ReplayClock.active&&listener!=null)listener.releaseAuthored(handle);}
    private static final Map<String,Object> resourceFallbacks=new ConcurrentHashMap<>();
    public static void clearResources(){resourceFallbacks.clear();}
    public static Map<String,String> resources(){Map<String,String> result=new TreeMap<>();resourceFallbacks.forEach((key,value)->result.put(key,value.toString()));return result;}
    /** Keep the real resource ID for the native model's path-bound signature. */
    public static Object resource(Object resource)throws Exception {
        if(!ReplayClock.active||resource==null)return resource;
        String path=(String)Native.call(resource,"continue","java.lang.String");
        if(!path.endsWith("_lod.mcsb")&&!path.endsWith("_lod.mcmtl"))return resource;
        String key=resource.toString();Object cached=resourceFallbacks.get(key);if(cached!=null)return cached;
        if((boolean)Native.call(Native.type("1198595589"),"return","boolean",resource))return resource;
        String full=path.replaceFirst("_lod\\.(mcsb|mcmtl)$",".$1");
        Object candidate=Native.type("779455578").getConstructor(String.class,String.class).newInstance(Native.call(resource,"private","java.lang.String"),full);
        if(!(boolean)Native.call(Native.type("1198595589"),"return","boolean",candidate))return resource;
        resourceFallbacks.put(key,candidate);return candidate;
    }
    public static Object effectMatrix(Object matrix,Object entity,boolean initial){
        EffectTransform handler=effectTransform;if(!ReplayClock.active||handler==null)return matrix;
        try{return handler.transform(matrix,entity,initial);}
        catch(Exception|LinkageError failure){ReplayClock.failure=failure;return matrix;}
    }
    public static int drawQuests(Object renderer,Object hint,Object position,int offset)throws Exception {
        QuestHud handler=questHud;
        if(ReplayClock.active&&handler!=null){
            try{return handler.draw(renderer,position,offset)-offset;}
            catch(Exception|LinkageError failure){ReplayClock.failure=failure;return 0;}
        }
        return ((Number)Native.call(renderer,"extends","int",hint,position,offset)).intValue();
    }
    private static double testX,testY;private static boolean testCursor;
    private static final boolean[] testButtons=new boolean[5];
    public static boolean testInput(){return Boolean.getBoolean("sz.replay.qa.input")&&Boolean.getBoolean("standard.jvm.headless.hideWindow");}
    public static void testCursor(double x,double y){if(testInput()){testX=x;testY=y;testCursor=true;}}
    public static void testButton(int button,int action){if(testInput()&&button>=0&&button<5)testButtons[button]=action==1;}
    public static boolean testMouseDown(int button){return testInput()&&button>=0&&button<5&&testButtons[button];}
    /** Hidden GLFW windows cannot acquire OS focus; feed the same backend IO. */
    public static void headlessMouse(){
        if(!testInput()||!testCursor)return;
        var io=imgui.ImGui.getIO();var viewport=imgui.ImGui.getMainViewport();
        io.setMousePos((float)testX+viewport.getPosX(),(float)testY+viewport.getPosY());
        for(int n=0;n<5;n++)io.setMouseDown(n,testButtons[n]);
    }
    public static java.util.Map<String,Object> inputDiagnostic(){return Map.of("enabled",testInput(),"cursor_received",testCursor,"x",testX,"y",testY,"button0",testButtons[0]);}
    private record MethodKey(String name,List<Class<?>> parameters){}
    private static final ClassValue<Map<MethodKey,Method>> lookups=new ClassValue<>(){protected Map<MethodKey,Method> computeValue(Class<?> owner){return new ConcurrentHashMap<>();}};
    public static Method publicMethod(Class<?> owner,String name,Class<?>[] parameters)throws NoSuchMethodException {
        if(!ReplayClock.active)return owner.getMethod(name,parameters);
        MethodKey key=new MethodKey(name,List.of(parameters));Method value=lookups.get(owner).get(key);
        if(value==null){value=owner.getMethod(name,parameters);lookups.get(owner).putIfAbsent(key,value);}return value;
    }
    public static Object npcArmor(Object npc)throws Exception {
        // Registry identity: for() is equippedArmor (26), continue() is
        // equippedBackpack (28). The installed rig's NPC branch uses the latter.
        return Native.call(npc,ReplayClock.active?"for":"continue","211097523");
    }
    public static Object call(Object receiver,String method,String returns,Object[] arguments)throws Exception {
        if(ReplayClock.active){
            if(receiver instanceof Class<?> owner&&owner.getName().equals("764970879")&&method.equals("implements")&&returns.equals("211097523")&&arguments.length==1&&arguments[0]!=null){
                if(Native.type("1422788721").isInstance(arguments[0]))return Native.call(arguments[0],"synchronized","211097523");
                // Native corpse actors retain an armor stack, not a held gun.
                // The TP body rig also visits these actors for their skinning.
                if(Native.type("468333307").isInstance(arguments[0]))return null;
            }
            return Native.call(receiver,method,returns,arguments);
        }
        Method original=Native.type("szgmod.SourceRigHooks").getDeclaredMethod("call",Object.class,String.class,String.class,Object[].class);original.setAccessible(true);
        try{return original.invoke(null,receiver,method,returns,arguments);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception failure)throw failure;throw e;}
    }
}
