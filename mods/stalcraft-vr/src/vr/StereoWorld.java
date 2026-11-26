package vr;
import static org.lwjgl.opengl.GL33.*;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** Render-only boundary: native client tick/begin-frame and desktop HUD run once. */
final class StereoWorld implements OpenXr.Renderer {
    final VrSpace space=new VrSpace();
    final OpenXr xr;
    final VrFrameStats timings;
    final GameSession gameplay;
    final NativeRigDraw rig=new NativeRigDraw();
    final SpatialMenu menu=new SpatialMenu();
    final SpatialMenuDraw menuDraw=new SpatialMenuDraw();
    final NativeScreen screen=new NativeScreen();
    final ColorTransfer color=new ColorTransfer();
    final ScopeDraw scopeDraw=new ScopeDraw();
    final NativeAuxTargets auxiliaryTargets=new NativeAuxTargets();
    final NativeAuxTargets eyeTargets=new NativeAuxTargets();
    double eyeScale;
    int eyeWidthPixels;
    int eyeFallbacks;
    boolean reducedScope;
    long auxEnterNanos,auxRenderNanos,auxCopyNanos,auxRestoreNanos;int auxMeasured;
    final DesktopMirror mirror=new DesktopMirror();
    boolean mirrorEnabled=true;
    int mirrorEye=-1;
    int recordingEye=1;
    long desktopCopies;
    final NativeScene sceneCommands=new NativeScene();
    Pose auxiliaryPose;
    Fov auxiliaryFov;
    int sceneDepth,sceneSamples;
    final int[] sceneViewport=new int[4];
    Object renderer,player,camera;
    float partial;
    int eyeIndex=-1;
    int sourceFbo,sourceSamples;
    private Object sourceWrapper,nativeWrapper;
    final int[] sourceViewport=new int[4];
    boolean effectsPrepared;
    private Tracking snapshot;
    private boolean calibrated;
    private Object lastPlayer;
    private V lastOrigin;
    private int nativeRead,nativeDraw;
    private final int[] nativeViewport=new int[4];
    private Object nativeState,nativeScope;
    StereoWorld(OpenXr xr,GameSession gameplay){this.xr=xr;this.gameplay=gameplay;timings=xr==null?new VrFrameStats():xr.timings;}
    public void inactive()throws Exception {gameplay.gun.suspend();gameplay.roomscale.reset();gameplay.tracking=null;gameplay.received=0;menu.open=false;gameplay.menu=false;gameplay.controls.release();}
    boolean render(Object renderer,Object player,Object camera,float partial)throws Exception {
        if(eyeIndex>=0)return false;
        this.renderer=renderer;this.player=player;this.camera=camera;this.partial=partial;
        mirror.beginFrame();sourceFbo=-1;sourceWrapper=null;captureNativeTarget();Throwable failure=null;
        try{
            boolean rendered=xr.frame(this);
            // Install the completed eye as the world background before the game draws its HUD.
            if(rendered&&mirrorEnabled)mirror.publishNative(color,sourceFbo,sourceViewport[2],sourceViewport[3]);
            return rendered;
        }catch(Exception|Error error){failure=error;throw error;}finally{
            eyeIndex=-1;auxiliaryPose=null;auxiliaryFov=null;snapshot=null;effectsPrepared=false;
            // Restore native camera-derived matrices/frustum after the two eye views.
            try{try{rebuildCamera(camera,call(player,"transient","1500514786"));syncShaderCamera();}finally{
                restoreNativeState();
                // The stock world pass ends on its postprocessed/UI target.
                // xr.frame restores the ENTRY scene FBO instead. With native
                // AA disabled, the next UI stage intentionally does not rebind
                // a framebuffer: drawing there would lose every menu widget.
                if(sourceFbo>=0){glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sourceFbo);glViewport(sourceViewport[0],sourceViewport[1],sourceViewport[2],sourceViewport[3]);NativeFramebuffer.restore(sourceWrapper);}
            }}
            catch(Exception|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    void captureNativeTarget()throws Exception {
        sceneCommands.begin(call(call(type("1402131154"),"try","1402131154"),"implements","1376962110"),camera,renderer);
        effectsPrepared=false;nativeWrapper=NativeFramebuffer.current();nativeRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);nativeDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);glGetIntegerv(GL_VIEWPORT,nativeViewport);
        nativeScope=call(get(type("1973624787"),"package"),"strictfp","1973624787");
        // strictfp pushes a mutable CURRENT state. Keep a separate immutable copy
        // for replay; otherwise eye 1 inherits eye 0's exposure/texture bindings.
        try{nativeState=call(nativeScope,"throws","1973624787");}catch(Exception|Error error){try{restoreNativeState();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    void prepareEffects()throws Exception {
        if(effectsPrepared)return;
        Object scene=call(call(type("1402131154"),"try","1402131154"),"implements","1376962110");
        double delta=((Number)call(get(type("1402131154"),"h"),"static","float")).doubleValue();
        Object primary=get(scene,"short"),secondary=get(scene,"transient");
        call(primary,"continue","void",delta);call(secondary,"continue","void",delta);
        call(primary,"char","void",delta);call(secondary,"char","void",delta);
        // Both eye draws observe the same completed effect simulation snapshot.
        effectsPrepared=true;
    }
    void restoreNativeState()throws Exception {
        Object scope=nativeScope;nativeScope=null;nativeState=null;
        try{if(scope!=null)((AutoCloseable)scope).close();}
        finally{NativeFramebuffer.restore(nativeWrapper);}
    }
    public void tracking(Tracking t)throws Exception {
        snapshot=t;V actor=origin(player,partial);
        if(!calibrated||lastPlayer!=player){
            V offset=floatVector(call(camera,"try","239386075"));
            space.recenter(t.head(),new Pose(actor.add(offset),heading(player)));gameplay.roomscale.reset();
            // During the first world frame the native FP camera can still have
            // its zero constructor offset. Retry until native eye height exists.
            calibrated=offset.finite()&&offset.y()>.1;lastPlayer=player;
        }
        else if(lastOrigin!=null){V delta=actor.sub(lastOrigin);space.translate(delta.sub(gameplay.roomscale.actorCompensation(delta)));}
        lastOrigin=actor;
        // Input focus must see this frame's tracking before inventory bindings.
        gameplay.observe(t,space);boolean previousMenu=gameplay.menu;
        menu.update(GameSession.world(t,space),gameplay);
        if(menu.recenter){V offset=floatVector(call(camera,"try","239386075"));space.recenter(t.head(),new Pose(actor.add(offset),heading(player)));gameplay.roomscale.reset();menu.recenter=false;}
        if(menu.inventory){screen.toggle(call(type("1402131154"),"try","1402131154"));menu.inventory=false;}
        boolean nativeMenu=screen.update(GameSession.world(t,space),call(type("1402131154"),"try","1402131154"),menu.open);gameplay.menu=menu.open||nativeMenu;
        if(previousMenu!=gameplay.menu&&t.focused())gameplay.audio.play(gameplay.spec,gameplay.menu?"ui_open":"ui_close");
        gameplay.tracking(t,space,call(type("1402131154"),"try","1402131154"));
        if(gameplay.shotHaptic){xr.haptic(gameplay.gun.mainHand(),.65f,35_000_000L);gameplay.shotHaptic=false;}
        if(gameplay.result!=null&&gameplay.result.event()!=Ak15.Event.NONE)xr.haptic(gameplay.gun.offHand(),.25f,20_000_000L);
        long rigStart=timings.mark();int rigGpu=timings.gpuStart(VrFrameStats.RIG);
        try{rig.prepare(gameplay);}finally{timings.end(VrFrameStats.RIG,rigStart,rigGpu);}
    }
    public void eye(Tracking t,int eye,int target,int width,int height)throws Exception {
        eyeIndex=eye;
        long prepareStart=timings.mark();int prepareGpu=timings.gpuStart(VrFrameStats.WORLD);
        try{prepareEffects();if(eye==0)NativePostprocess.prepare(renderer);}finally{timings.end(VrFrameStats.WORLD,prepareStart,prepareGpu);}
        NativeOptic optic=rig.optic;
        OpticMath.View opticView=gameplay.headBlocked?null:optic.view(eyePose());
        optic.eye(opticView);
        // Reflex sights need only their transparent reticle. Tube sights have
        // opaque authored inner geometry: retain a 1:1 aperture camera for them,
        // and narrow that camera's FOV only for >=2.4x optics.
        boolean scopeImage=opticView!=null&&opticView.transmission()>0&&optic.portal();
        if(scopeImage){
            long scopeStart=timings.mark();int scopeGpu=timings.gpuStart(VrFrameStats.SCOPE);
            // A render-only second world camera. No new tracking/input/tick, shadows or FX simulation.
            auxiliaryPose=opticView.camera();auxiliaryFov=opticView.fov();
            long entered=System.nanoTime();AutoCloseable targets=null;
            try{targets=reducedScope?auxiliaryTargets.enter(renderer,512,512):null;long rendering=System.nanoTime();drawNativeView();long copying=System.nanoTime();copyView(scopeDraw.target(),512,512);long restoring=System.nanoTime();auxEnterNanos+=rendering-entered;auxRenderNanos+=copying-rendering;auxCopyNanos+=restoring-copying;}
            finally{long restoring=System.nanoTime();try{if(targets!=null)targets.close();}finally{auxRestoreNanos+=System.nanoTime()-restoring;auxMeasured++;auxiliaryPose=null;auxiliaryFov=null;timings.end(VrFrameStats.SCOPE,scopeStart,scopeGpu);}}
        }
        sceneDepth=0;sceneSamples=0;
        AutoCloseable scaled=null;NativeAuxTargets.Output desktop=null;int ownedSource=0,ownedBuffer=0,ownedSamples=0;int[] ownedViewport=null,vp=null;
        if(eyeWidthPixels>0)scaled=eyeTargets.enter(renderer,eyeWidthPixels,Math.max(1,(int)Math.round((double)height*eyeWidthPixels/width)));
        else if(Double.isFinite(eyeScale)&&eyeScale>=.25&&eyeScale<=1)scaled=eyeTargets.enter(renderer,Math.max(1,(int)Math.round(width*eyeScale)),Math.max(1,(int)Math.round(height*eyeScale)));
        try{
        long worldStart=timings.mark();int worldGpu=timings.gpuStart(VrFrameStats.WORLD);
        try{drawNativeView();}finally{timings.end(VrFrameStats.WORLD,worldStart,worldGpu);}
        if(scaled!=null){ownedSource=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);desktop=eyeTargets.originalOutput(ownedSource);
            if(desktop==null||desktop.viewport()[0]!=0||desktop.viewport()[1]!=0){scaled.close();scaled=null;desktop=null;eyeFallbacks++;drawNativeView();}
            else{ownedBuffer=glGetInteger(GL_DRAW_BUFFER0);ownedSamples=glGetInteger(GL_SAMPLES);ownedViewport=new int[4];glGetIntegerv(GL_VIEWPORT,ownedViewport);eyeTargets.verifyOutput(ownedViewport);}
        }
        long copyStart=timings.mark();int copyGpu=timings.gpuStart(VrFrameStats.COPY);
        try{sourceWrapper=NativeFramebuffer.current();vp=copyView(target,width,height);}finally{timings.end(VrFrameStats.COPY,copyStart,copyGpu);}
        }finally{if(scaled!=null)scaled.close();}
        // The stock desktop UI must see its original complete, full-size target.
        // Keep the copied eye depth alive for the reticle's occlusion test.
        if(desktop!=null){long desktopStart=timings.mark();int desktopGpu=timings.gpuStart(VrFrameStats.COPY);
            try{color.copyToNative(ownedSource,ownedBuffer,ownedViewport[0],ownedViewport[1],ownedViewport[2],ownedViewport[3],ownedSamples,desktop.framebuffer(),desktop.viewport()[2],desktop.viewport()[3]);desktopCopies++;sourceFbo=desktop.framebuffer();System.arraycopy(desktop.viewport(),0,sourceViewport,0,4);}
            finally{int draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,desktop.framebuffer());int[] draws=new int[desktop.draws().size()];for(int i=0;i<draws.length;i++)draws[i]=desktop.draws().get(i);glDrawBuffers(draws);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);timings.end(VrFrameStats.COPY,desktopStart,desktopGpu);}
        }
        long overlayStart=timings.mark();int overlayGpu=timings.gpuStart(VrFrameStats.OVERLAY);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,target);glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glViewport(0,0,width,height);
        if(!gameplay.headBlocked){
            scopeDraw.draw(optic,opticView,eyePose(),eyeFov(),width,height,sceneDepth,sceneSamples,sceneViewport,optic.portal());
        }
        if(gameplay.headBlocked){
            float[] clear=new float[4];glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);boolean scissor=glIsEnabled(GL_SCISSOR_TEST);var mask=org.lwjgl.BufferUtils.createByteBuffer(4);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
            try{glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);}finally{glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);glClearColor(clear[0],clear[1],clear[2],clear[3]);if(scissor)glEnable(GL_SCISSOR_TEST);}
        }
        try{Fov fov=(eye==0?t.leftEye():t.rightEye()).fov();screen.draw(eyePose(),fov);menuDraw.draw(menu,gameplay,eyePose(),fov,gameplay.tracking.right(),timings.enabled?timings:null);
            if(mirrorEnabled){
                // Both eye poses and the lens belong to this same snapshot.
                // Select before capture instead of copying left then overwriting it.
                if(eye==0)recordingEye=mirrorEye>=0?mirrorEye:gameplay.headBlocked?1:OpticMath.recordingScore(optic.view(space.world(t.leftEye().pose())),optic.rx,optic.ry)>OpticMath.recordingScore(optic.view(space.world(t.rightEye().pose())),optic.rx,optic.ry)?0:1;
                if(eye==recordingEye)mirror.consider(target,width,height,OpticMath.recordingScore(opticView,optic.rx,optic.ry));
            }
        }
        finally{try{glViewport(vp[0],vp[1],vp[2],vp[3]);}finally{timings.end(VrFrameStats.OVERLAY,overlayStart,overlayGpu);}}
    }
    private void drawNativeView()throws Exception {
        call(nativeState,"else","void",false);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,nativeRead);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,nativeDraw);glViewport(nativeViewport[0],nativeViewport[1],nativeViewport[2],nativeViewport[3]);
        set(type("105717861"),"throws",null);
        // Camera preparation normally runs before the world boundary. Rebuild all
        // derived matrices and culling frusta for each eye, not just the GL matrix.
        Object scene=call(call(type("1402131154"),"try","1402131154"),"implements","1376962110");call(scene,"static","void");
        rebuildCamera(camera,call(player,"transient","1500514786"));
        syncShaderCamera();
        // XR binds its output before this callback. Rebind and clear the native
        // scene attachments for every eye; the GL cache otherwise retains the
        // pre-XR binding and the second eye retains the first eye's depth/color.
        glDepthMask(true);glClearDepth(1);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);
        // XR/raw blits bypass the native framebuffer object cache. A cache hit
        // would attach native colour/depth images to the wrong bound GL FBO.
        // Force short() to bind the native target before changing attachments.
        set(type("105717861"),"throws",null);
        call(type("449323257"),"break","void",true);
        // Discard pre-XR primary lists and regenerate against this eye's frustum.
        // The completed stock shadow pass and client simulation are untouched.
        sceneCommands.culling(eyePose(),eyeFov(),auxiliaryPose!=null);
        sceneCommands.view(scene,camera,renderer);
        // Native render owns its intermediates and viewport; copy its completed image.
        // This first adapter intentionally keeps native render resolution independent
        // of the XR target. Resize/postprocess-history adaptation is a later step.
        call(renderer,"szmods$vr$world","void",partial);
    }
    private int[] copyView(int target,int width,int height){
        int source=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),sourceBuffer=glGetInteger(GL_DRAW_BUFFER0),samples=glGetInteger(GL_SAMPLES);
        sourceFbo=source;sourceSamples=samples;
        int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);
        if(auxiliaryPose!=null&&reducedScope)auxiliaryTargets.verifyOutput(vp);
        System.arraycopy(vp,0,sourceViewport,0,4);
        color.copy(source,sourceBuffer,vp[0],vp[1],vp[2],vp[3],samples,target,width,height);
        return vp;
    }
    void captureDepth(){
        if(auxiliaryPose!=null||glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)==0)return;
        if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_TEXTURE){
            sceneDepth=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);sceneSamples=glGetInteger(GL_SAMPLES);glGetIntegerv(GL_VIEWPORT,sceneViewport);
        }
    }
    boolean own(Object camera){return eyeIndex>=0&&this.camera==camera;}
    /** Native material/fog/shadow coordinates must use the same eye as geometry. */
    private void syncShaderCamera()throws Exception {
        Object manager=call(type("495112732"),"byte","495112732");
        call(manager,"abstract","void");
    }
    Pose eyePose(){if(auxiliaryPose!=null)return auxiliaryPose;Tracking.Eye eye=eyeIndex==0?snapshot.leftEye():snapshot.rightEye();return space.world(eye.pose());}
    Fov eyeFov(){return auxiliaryFov!=null?auxiliaryFov:(eyeIndex==0?snapshot.leftEye():snapshot.rightEye()).fov();}
    Object view(Object matrix)throws Exception {
        call(camera,"szmods$flight$enum","void",matrix);
        eyeView(camera,matrix,eyePose(),eyeFov());return Boolean.TRUE;
    }
    void projection(Object projection)throws Exception {NativeAccess.projection(projection,eyeFov());}
}
