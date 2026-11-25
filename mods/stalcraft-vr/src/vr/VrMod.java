package vr;
import szmods.api.*;
import static org.lwjgl.glfw.GLFW.*;

/** VR lifecycle. Inactive by default, with calibration and native world modes. */
public final class VrMod implements ClientMod {
    private ModContext context;
    private OpenXr xr;
    private DiagnosticScene diagnostic;
    private StereoWorld world;
    private final GameSession gameplay=new GameSession();
    private SettingsRegistry.BooleanSetting worldEnabled;
    private SettingsRegistry.BooleanSetting mirrorEnabled;
    private SettingsRegistry.BooleanSetting reducedScope;
    private SettingsRegistry.BooleanSetting performance;
    private long nextPerformanceLog;
    private SettingsRegistry.ChoiceSetting mirrorEye;
    private SettingsRegistry.ChoiceSetting eyeResolution;
    private Object game;
    private long submitted=-1,frameNumber;
    private boolean keyDown;
    private final VrReconnect reconnect=new VrReconnect();
    private boolean retrying;
    private boolean steamRuntime;
    private Thread renderThread;
    private String status="VR выключен";
    private String startupNotice;
    public void onLoad(ModContext context){this.context=context;steamRuntime=SteamVrReady.steamRuntime(System.getenv("XR_RUNTIME_JSON"));gameplay.audio.context=context;context.log("STALCRAFT VR: OpenXR controls ready. F12 toggles the headset session.");}
    public void registerKeybinds(KeybindRegistry registry){registry.register("toggle","Включить VR",GLFW_KEY_F12);}
    public void registerSettings(SettingsRegistry registry){var tab=registry.registerTab("vr","Виртуальная реальность");tab.section("status","OpenXR / SteamVR. F12 — подключение шлема. Левая X/A — инвентарь.");worldEnabled=tab.booleanSetting("world-preview","Игровой VR",true);
        mirrorEnabled=tab.booleanSetting("desktop-mirror","Изображение VR на мониторе",true);
        mirrorEye=tab.choiceSetting("desktop-eye","Глаз для записи","Авто (прицел)",java.util.List.of("Авто (прицел)","Правый","Левый"));
        eyeResolution=tab.choiceSetting("eye-resolution","Мир VR: ширина изображения одного глаза","1280",java.util.List.of("Штатное","960","1280","1600","По шлему"));
        reducedScope=tab.booleanSetting("reduced-scope","Экономить ресурсы на оптике (картинка 512×512)",false);
        performance=tab.booleanSetting("performance","Измерять время VR-кадра (меню / журнал)",false);
    }
    public void onFrame(ClientFrame frame){renderThread=Thread.currentThread();game=frame.game();frameNumber=frame.number();long window=glfwGetCurrentContext();if(window==0)return;
        boolean pressed=glfwGetKey(window,GLFW_KEY_F12)==GLFW_PRESS;
        if(pressed&&!keyDown){if(xr!=null||reconnect.wanted()){reconnect.cancel();stop();startupNotice=null;status="VR выключен";}else start(window);}keyDown=pressed;
        if(xr==null&&reconnect.due(System.nanoTime())){retrying=true;try{start(window);}finally{retrying=false;}}
        if(xr==null)return;
        xr.timings.enabled(performance!=null&&performance.get());xr.timings.refresh(System.nanoTime());
        if(xr.timings.enabled&&System.nanoTime()>=nextPerformanceLog){nextPerformanceLog=System.nanoTime()+5_000_000_000L;context.log(xr.timings.report);}
        try{if(!xr.poll()){stop();reconnect.disconnected(System.nanoTime());status=startupNotice="Связь со SteamVR потеряна. Ожидаю шлем; F12 — отменить подключение.";}else if(worldEnabled!=null&&worldEnabled.get()&&xr.running()){System.setProperty("sz.vr.active","true");gameplay.frame(game,xr.focused());}else{System.clearProperty("sz.vr.active");gameplay.close();}}catch(Throwable e){fail(e);}
    }
    private void start(long window){
        long attemptedAt=System.currentTimeMillis();String previous=startupNotice;startupNotice=null;reconnect.request();
        if(!SteamVrReady.ready(steamRuntime||previous!=null&&XrFailure.missingHeadset(previous))){reconnect.disconnected(System.nanoTime());status=startupNotice="SteamVR: шлем не подключён. Запустите SteamVR и подключите шлем; ожидаю готовности. F12 — отменить.";if(!status.equals(previous))context.log(status);return;}
        try{diagnostic=new DiagnosticScene();xr=new OpenXr(context::log);xr.initialize(window);world=new StereoWorld(xr,gameplay);reconnect.connected();status="OpenXR: "+xr.runtime;context.log(status);}
        catch(Throwable e){String reason=XrFailure.message(e,attemptedAt);boolean missing=XrFailure.missingHeadset(reason);
            if(!retrying||!missing)fail(e);else try{stop();}catch(Throwable cleanup){context.log("VR cleanup: "+cleanup);}
            if(missing){reconnect.request();reconnect.disconnected(System.nanoTime());reason=reason.replace("затем F12.","ожидаю подключения. F12 — отменить.");}else reconnect.cancel();
            status=startupNotice=reason;if(!reason.equals(previous))context.log(startupNotice);
        }
    }
    private void fail(Throwable e){boolean lost=reconnect.wanted()&&XrResultException.disconnected(e);reconnect.cancel();java.io.StringWriter detail=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(detail));context.log("VR: "+detail);if(xr!=null&&xr.timings.enabled){xr.timings.refresh(Long.MAX_VALUE-1_000_000_000L);context.log(xr.timings.report);}status=startupNotice="VR ошибка: "+e.getMessage()+". Подробности в журнале клиента; F12 — повторное подключение.";try{stop();}catch(Throwable cleanup){context.log("VR cleanup: "+cleanup);}if(lost){reconnect.request();reconnect.disconnected(System.nanoTime());status=startupNotice="Связь со SteamVR потеряна. Ожидаю шлем; F12 — отменить подключение.";}}
    private void stop(){Object nativeWrapper=null;try{nativeWrapper=NativeFramebuffer.current();}catch(Exception e){context.log("VR framebuffer capture: "+e);}try{try{gameplay.close();}catch(Exception e){context.log("VR input cleanup: "+e);}try{if(world!=null){try{try{world.rig.masks.close();}finally{try{world.menuDraw.close();}finally{try{world.screen.close();}finally{try{world.color.close();}finally{try{world.scopeDraw.close();}finally{try{world.auxiliaryTargets.close();}finally{world.eyeTargets.close();}}}}}}}finally{world.mirror.close();}}}finally{if(xr!=null)xr.close();}}finally{xr=null;world=null;submitted=-1;System.clearProperty("sz.vr.active");try{if(diagnostic!=null)diagnostic.close();}finally{diagnostic=null;try{NativeFramebuffer.restore(nativeWrapper);}catch(Exception e){context.log("VR framebuffer cleanup: "+e);}}}}
    public Object onClientHook(String hook,Object...args)throws Exception {
        if(hook.equals("network.vr.weapon.v1")){gameplay.reload.receive(args[0],(byte[])args[1]);return null;}
        if(world==null)return null;
        if(hook.equals("vr.action-focus.v1"))return Boolean.getBoolean("sz.vr.active")&&xr!=null&&xr.focused()&&gameplay.actionFocus(game);
        if(hook.equals("vr.desktop-ui.v1")){world.screen.capture(game);submitFallback();
            if(world!=null&&Boolean.getBoolean("sz.vr.active")&&submitted==frameNumber&&world.mirrorEnabled&&NativeAccess.call(game,"float","622415611")==null){
                long window=glfwGetCurrentContext();int[] w={0},h={0};glfwGetFramebufferSize(window,w,h);
                // Native world colour was copied before the scope overlay.
                // Recorders reading that target must receive the completed eye too.
                Object nativeWrapper=NativeFramebuffer.current();try{world.mirror.publishNative(world.color,world.sourceFbo,world.sourceViewport[2],world.sourceViewport[3]);world.mirror.present(0,org.lwjgl.opengl.GL33.GL_BACK,w[0],h[0]);}
                finally{NativeFramebuffer.restore(nativeWrapper);}
            }
            return null;
        }
        if(Boolean.getBoolean("sz.vr.active")){
            if(hook.equals("weapon.tracer-offset.v1"))return Boolean.FALSE;
            if(hook.equals("fp.effect-bone.v1"))return world.rig.effectBone(args[0],(String)args[1],gameplay);
            if(hook.equals("input.key.v1"))return Boolean.FALSE;
            if(hook.equals("player.movement-intent.v1"))return gameplay.movement(args[0]);
            if(hook.equals("input.action-block.v1"))return Boolean.TRUE;
            if(hook.equals("view.block-body-look.v1"))return Boolean.TRUE;
            if(hook.equals("weapon.aim.v1"))return gameplay.aim(args[0]);
            if(hook.equals("fp.screen-pass.v1")&&args[0]==world.player)return Boolean.TRUE;
            if(hook.equals("fp.body-visible.v1"))return world.rig.bodyPass()?Boolean.TRUE:Boolean.FALSE;
            if(hook.equals("model.actor-visible.v1")&&args[0]==world.player)return Boolean.FALSE;
            if(hook.equals("model.body-visible.v1")&&args[0]!=null&&NativeAccess.type("1078236163").getDeclaredField("assert").get(args[0])==world.player)return Boolean.FALSE;
            if(hook.equals("model.mesh-visible.v2"))return world.rig.mesh(args[0],args[1]);
            if(hook.equals("model.mesh-draw.v2"))return world.rig.mask(args[0],args[1],args[2]);
            if(hook.equals("scene.drones.v1")&&world.own(args[0])){if(world.auxiliaryPose==null){world.rig.draw(world);world.captureDepth();}return null;}
        }
        if(hook.equals("vr.second-eye.v1"))return world.eyeIndex==1||world.auxiliaryPose!=null?Boolean.TRUE:null;
        if(hook.equals("vr.effects-prepared.v1"))return world.eyeIndex>=0&&world.effectsPrepared?Boolean.TRUE:null;
        if(hook.equals("vr.render-view-ready.v1")&&world.eyeIndex>=0){world.sceneCommands.replay(world.camera,args[0]);return null;}
        if(hook.equals("camera.native-view.v1")&&world.own(args[0]))return world.view(args[1]);
        if(hook.equals("vr.projection.v1")&&world.own(args[0])){world.projection(args[1]);return null;}
        if(hook.equals("vr.render-world.v1")&&worldEnabled!=null&&worldEnabled.get()&&xr.running()&&submitted!=frameNumber){
            Object player=NativeAccess.get(game,"e");if(player==null)return null;
            Object camera=NativeAccess.call(player,"enum","1389510037");
            if(NativeAccess.call(NativeAccess.type("495112732"),"byte","353488815")!=camera)return null;
            System.setProperty("sz.vr.active","true");
            try{world.reducedScope=reducedScope!=null&&reducedScope.get();world.mirrorEnabled=mirrorEnabled==null||mirrorEnabled.get();String choice=mirrorEye==null?"Авто (прицел)":mirrorEye.get();world.mirrorEye="Левый".equals(choice)?0:"Правый".equals(choice)?1:-1;
                String resolution=eyeResolution==null?"1280":eyeResolution.get();world.eyeWidthPixels=switch(resolution){case "960"->960;case "1280"->1280;case "1600"->1600;default->0;};world.eyeScale="По шлему".equals(resolution)?1:0;
                boolean rendered=world.render(args[0],player,camera,((Number)args[1]).floatValue());submitted=frameNumber;return rendered?Boolean.TRUE:null;}
            catch(Throwable e){fail(e);return null;}
        }
        return null;
    }
    private void submitFallback(){
        if(xr==null||!xr.running()||submitted==frameNumber)return;
        Object nativeWrapper=null;try{nativeWrapper=NativeFramebuffer.current();xr.frame(new OpenXr.Renderer(){public void tracking(Tracking t)throws Exception {world.screen.update(t,game,false);diagnostic.tracking(t);}public void eye(Tracking t,int eye,int fbo,int width,int height){diagnostic.draw(t,eye,width,height);Tracking.Eye view=eye==0?t.leftEye():t.rightEye();world.screen.draw(view.pose(),view.fov());}});submitted=frameNumber;}catch(Throwable e){fail(e);}finally{try{NativeFramebuffer.restore(nativeWrapper);}catch(Exception e){context.log("VR framebuffer cleanup: "+e);}}
    }
    public void registerHud(HudRegistry registry){registry.register("vr-submit",(hud,w,h,partial)->{try{if(NativeAccess.call(game,"float","622415611")==null)submitFallback();}catch(Throwable e){fail(e);}
        if(xr==null&&startupNotice!=null)try(AutoCloseable state=(AutoCloseable)NativeAccess.call(NativeAccess.type("1973624787"),"do","1973624787")){
            NativeAccess.call(NativeAccess.type("899237048"),"synchronized","void",12,12,Math.min(w-12,780),44,0xcc141820);
            Object font=NativeAccess.get(hud,"throw");if(font!=null)NativeAccess.call(font,"static","double",startupNotice,20,20,0xffffc878);
        }catch(Exception e){context.log("VR status overlay: "+e);startupNotice=null;}
    });}
    public void onUnload(){reconnect.cancel();if(Thread.currentThread()==renderThread)stop();}
    public String status(){return status;}
}
