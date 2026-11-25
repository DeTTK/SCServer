package vr;

import java.nio.*;
import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.*;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.openxr.KHROpenGLEnable.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.glfw.GLFWNativeWGL.*;
import static org.lwjgl.glfw.GLFWNativeWin32.*;
import static org.lwjgl.system.windows.User32.*;
import static vr.Math3.*;

/** Uses the game's existing Win32 OpenGL context on its owning thread. */
public final class OpenXr implements AutoCloseable {
    public interface Renderer {
        /** Once per XR frame, before either eye. Never tick simulation per eye. */
        void tracking(Tracking tracking)throws Exception;
        default void inactive()throws Exception {}
        void eye(Tracking tracking,int eye,int framebuffer,int width,int height)throws Exception;
    }
    private final Consumer<String> log;
    final VrFrameStats timings=new VrFrameStats();
    private final Thread thread=Thread.currentThread();
    private XrInstance instance;
    private XrSession session;
    private XrSpace local,view;
    private XrActionSet actionSet;
    private final Map<String,XrAction> actions=new LinkedHashMap<>();
    private final long[] handPaths=new long[2];
    private final XrSpace[] gripSpaces=new XrSpace[2],aimSpaces=new XrSpace[2];
    private XrView.Buffer views;
    private final Swapchain[] eyes=new Swapchain[2];
    private XrEventDataBuffer events;
    private long system,format,windowHandle,deviceContext;
    private int state;
    private boolean running,closed,exit;
    public String runtime="";
    private static final class Swapchain {
        XrSwapchain handle;XrSwapchainImageOpenGLKHR.Buffer images;int width,height,fbo,depth;
    }
    public OpenXr(Consumer<String> log){this.log=log;}
    private void owner(){if(Thread.currentThread()!=thread)throw new IllegalStateException("OpenXR called outside its GL thread");if(closed)throw new IllegalStateException("OpenXR is closed");}
    public void initialize(long window)throws Exception {
        owner();if(window==0||glfwGetWGLContext(window)==0)throw new IllegalStateException("Current Win32 OpenGL context required");
        try(MemoryStack s=MemoryStack.stackPush()){
            IntBuffer count=s.mallocInt(1);check(xrEnumerateInstanceExtensionProperties((ByteBuffer)null,count,null));
            XrExtensionProperties.Buffer extensions=XrExtensionProperties.calloc(count.get(0),s);extensions.forEach(e->e.type$Default());
            check(xrEnumerateInstanceExtensionProperties((ByteBuffer)null,count,extensions));
            if(extensions.stream().noneMatch(e->e.extensionNameString().equals(XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)))throw new IllegalStateException("Runtime does not support OpenGL");
            PointerBuffer ptr=s.mallocPointer(1);
            XrInstanceCreateInfo ci=XrInstanceCreateInfo.calloc(s).type$Default().enabledExtensionNames(s.pointers(s.UTF8(XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)));
            ci.applicationInfo().applicationName(s.UTF8("STALCRAFT VR")).applicationVersion(1).engineName(s.UTF8("StalcraftCustom")).engineVersion(1).apiVersion(XR_API_VERSION_1_0);
            check(xrCreateInstance(ci,ptr));instance=new XrInstance(ptr.get(0),ci);
            XrInstanceProperties properties=XrInstanceProperties.calloc(s).type$Default();check(xrGetInstanceProperties(instance,properties));runtime=properties.runtimeNameString();log.accept("OpenXR runtime: "+runtime);
            LongBuffer id=s.mallocLong(1);check(xrGetSystem(instance,XrSystemGetInfo.calloc(s).type$Default().formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY),id));system=id.get(0);
            XrGraphicsRequirementsOpenGLKHR requirements=XrGraphicsRequirementsOpenGLKHR.calloc(s).type$Default();check(xrGetOpenGLGraphicsRequirementsKHR(instance,system,requirements));
            long glVersion=XR_MAKE_VERSION(glGetInteger(GL_MAJOR_VERSION),glGetInteger(GL_MINOR_VERSION),0);
            if(glVersion<requirements.minApiVersionSupported()||glVersion>requirements.maxApiVersionSupported())throw new IllegalStateException("Game GL version outside runtime requirements: "+glGetString(GL_VERSION));
            windowHandle=glfwGetWin32Window(window);deviceContext=GetDC(windowHandle);
            XrGraphicsBindingOpenGLWin32KHR graphics=XrGraphicsBindingOpenGLWin32KHR.calloc(s).type$Default().hDC(deviceContext).hGLRC(glfwGetWGLContext(window));
            check(xrCreateSession(instance,XrSessionCreateInfo.calloc(s).type$Default().systemId(system).next(graphics.address()),ptr));
            session=new XrSession(ptr.get(0),instance);
            local=space(XR_REFERENCE_SPACE_TYPE_LOCAL,s);view=space(XR_REFERENCE_SPACE_TYPE_VIEW,s);
            createActions(s);createSwapchains(s);
            views=XrView.calloc(2);views.forEach(v->v.type$Default());events=XrEventDataBuffer.calloc().type$Default();
        }catch(Throwable failure){close();throw failure;}
    }
    private XrSpace space(int kind,MemoryStack s){PointerBuffer ptr=s.mallocPointer(1);XrReferenceSpaceCreateInfo ci=XrReferenceSpaceCreateInfo.calloc(s).type$Default().referenceSpaceType(kind);ci.poseInReferenceSpace().orientation().w(1);check(xrCreateReferenceSpace(session,ci,ptr));return new XrSpace(ptr.get(0),session);}
    private long path(String value,MemoryStack s){LongBuffer out=s.mallocLong(1);check(xrStringToPath(instance,s.UTF8(value),out));return out.get(0);}
    private XrAction action(String name,int kind,MemoryStack s){PointerBuffer out=s.mallocPointer(1);
        check(xrCreateAction(actionSet,XrActionCreateInfo.calloc(s).type$Default().actionName(s.UTF8(name)).localizedActionName(s.UTF8(name.replace('_',' '))).actionType(kind).subactionPaths(s.longs(handPaths)),out));
        XrAction action=new XrAction(out.get(0),actionSet);actions.put(name,action);return action;}
    private void createActions(MemoryStack s){PointerBuffer out=s.mallocPointer(1);
        check(xrCreateActionSet(instance,XrActionSetCreateInfo.calloc(s).type$Default().actionSetName(s.UTF8("gameplay")).localizedActionSetName(s.UTF8("STALCRAFT VR")),out));actionSet=new XrActionSet(out.get(0),instance);
        handPaths[0]=path("/user/hand/left",s);handPaths[1]=path("/user/hand/right",s);
        action("grip_pose",XR_ACTION_TYPE_POSE_INPUT,s);action("aim_pose",XR_ACTION_TYPE_POSE_INPUT,s);
        action("trigger",XR_ACTION_TYPE_FLOAT_INPUT,s);action("squeeze",XR_ACTION_TYPE_FLOAT_INPUT,s);action("stick",XR_ACTION_TYPE_VECTOR2F_INPUT,s);
        for(String name:List.of("primary","secondary","stick_click","menu"))action(name,XR_ACTION_TYPE_BOOLEAN_INPUT,s);
        action("vibration",XR_ACTION_TYPE_VIBRATION_OUTPUT,s);
        suggest("/interaction_profiles/oculus/touch_controller","thumbstick","squeeze/value","x","y","a","b",s);
        suggest("/interaction_profiles/valve/index_controller","thumbstick","squeeze/value","a","b","a","b",s);
        suggest("/interaction_profiles/htc/vive_controller","trackpad","squeeze/click",null,"menu",null,"menu",s);
        suggest("/interaction_profiles/microsoft/motion_controller","thumbstick","squeeze/click",null,"menu",null,"menu",s);
        for(int h=0;h<2;h++){
            XrActionSpaceCreateInfo ci=XrActionSpaceCreateInfo.calloc(s).type$Default().subactionPath(handPaths[h]);ci.poseInActionSpace().orientation().w(1);
            check(xrCreateActionSpace(session,ci.action(actions.get("grip_pose")),out));gripSpaces[h]=new XrSpace(out.get(0),session);
            check(xrCreateActionSpace(session,ci.action(actions.get("aim_pose")),out));aimSpaces[h]=new XrSpace(out.get(0),session);
        }
        check(xrAttachSessionActionSets(session,XrSessionActionSetsAttachInfo.calloc(s).type$Default().actionSets(s.pointers(actionSet.address()))));
    }
    private void suggest(String profile,String stick,String squeeze,String lp,String ls,String rp,String rs,MemoryStack s){
        List<String[]> pairs=new ArrayList<>();
        for(int h=0;h<2;h++){
            String hand=h==0?"left":"right",prefix="/user/hand/"+hand;
            for(String[] binding:new String[][]{{"grip_pose","/input/grip/pose"},{"aim_pose","/input/aim/pose"},{"trigger","/input/trigger/value"},{"squeeze","/input/"+squeeze},{"stick","/input/"+stick},{"stick_click","/input/"+stick+"/click"},{"vibration","/output/haptic"}})pairs.add(new String[]{binding[0],prefix+binding[1]});
            String primary=h==0?lp:rp,secondary=h==0?ls:rs;
            if(primary!=null)pairs.add(new String[]{"primary",prefix+"/input/"+primary+"/click"});
            else if(h==1)pairs.add(new String[]{"primary",prefix+"/input/"+stick+"/click"});
            if(secondary!=null)pairs.add(new String[]{"secondary",prefix+"/input/"+secondary+"/click"});
            if(h==0&&profile.contains("oculus"))pairs.add(new String[]{"menu",prefix+"/input/menu/click"});
        }
        XrActionSuggestedBinding.Buffer bindings=XrActionSuggestedBinding.calloc(pairs.size(),s);
        for(int i=0;i<pairs.size();i++)bindings.get(i).action(actions.get(pairs.get(i)[0])).binding(path(pairs.get(i)[1],s));
        int result=xrSuggestInteractionProfileBindings(instance,XrInteractionProfileSuggestedBinding.calloc(s).type$Default().interactionProfile(path(profile,s)).suggestedBindings(bindings));
        if(result<0)log.accept("Controller profile unavailable: "+profile+" ("+result+")");
    }
    private void createSwapchains(MemoryStack s){IntBuffer count=s.mallocInt(1);check(xrEnumerateSwapchainFormats(session,count,null));LongBuffer formats=s.mallocLong(count.get(0));check(xrEnumerateSwapchainFormats(session,count,formats));
        format=0;for(long desired:new long[]{GL_SRGB8_ALPHA8,GL_RGBA8})for(int i=0;i<formats.limit();i++)if(formats.get(i)==desired&&format==0)format=desired;
        if(format==0)throw new IllegalStateException("Runtime does not offer RGBA OpenGL swapchain");
        check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,null));
        if(count.get(0)!=2)throw new IllegalStateException("Stereo view count must be 2");
        XrViewConfigurationView.Buffer sizes=XrViewConfigurationView.calloc(2,s);sizes.forEach(v->v.type$Default());check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,sizes));
        int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        try {for(int h=0;h<2;h++){
            Swapchain eye=new Swapchain();eyes[h]=eye;eye.width=sizes.get(h).recommendedImageRectWidth();eye.height=sizes.get(h).recommendedImageRectHeight();
            PointerBuffer out=s.mallocPointer(1);check(xrCreateSwapchain(session,XrSwapchainCreateInfo.calloc(s).type$Default().usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT|XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT).format(format).sampleCount(1).width(eye.width).height(eye.height).faceCount(1).arraySize(1).mipCount(1),out));eye.handle=new XrSwapchain(out.get(0),session);
            check(xrEnumerateSwapchainImages(eye.handle,count,null));eye.images=XrSwapchainImageOpenGLKHR.calloc(count.get(0));eye.images.forEach(i->i.type$Default());
            check(xrEnumerateSwapchainImages(eye.handle,count,XrSwapchainImageBaseHeader.create(eye.images.address(),eye.images.capacity())));
            eye.fbo=glGenFramebuffers();eye.depth=glGenTextures();glBindTexture(GL_TEXTURE_2D,eye.depth);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT24,eye.width,eye.height,0,GL_DEPTH_COMPONENT,GL_UNSIGNED_INT,0L);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            log.accept("Eye "+h+": "+eye.width+"x"+eye.height+", "+eye.images.capacity()+" images");
        }}finally{glBindTexture(GL_TEXTURE_2D,oldTexture);}
    }
    public boolean poll(){owner();if(session==null)return false;
        try(MemoryStack s=MemoryStack.stackPush()){
            while(true){events.clear();events.type$Default();int result=xrPollEvent(instance,events);if(result==XR_EVENT_UNAVAILABLE)break;check(result);
                if(events.type()==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED){XrEventDataSessionStateChanged event=XrEventDataSessionStateChanged.create(events.address());state=event.state();log.accept("XR session state "+state);
                    if(state==XR_SESSION_STATE_READY&&!running){check(xrBeginSession(session,XrSessionBeginInfo.calloc(s).type$Default().primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)));running=true;}
                    if(state==XR_SESSION_STATE_STOPPING&&running){check(xrEndSession(session));running=false;}
                    if(state==XR_SESSION_STATE_EXITING||state==XR_SESSION_STATE_LOSS_PENDING)exit=true;
                }else if(events.type()==XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING)exit=true;
            }
        }return !exit;
    }
    public boolean running(){return running;}
    public boolean focused(){return running&&state==XR_SESSION_STATE_FOCUSED;}
    public void haptic(int hand,float strength,long duration){owner();if(session==null||state!=XR_SESSION_STATE_FOCUSED)return;
        try(MemoryStack s=MemoryStack.stackPush()){check(xrApplyHapticFeedback(session,XrHapticActionInfo.calloc(s).type$Default().action(actions.get("vibration")).subactionPath(handPaths[hand]),XrHapticBaseHeader.create(XrHapticVibration.calloc(s).type$Default().amplitude(Math.max(0,Math.min(1,strength))).duration(duration).frequency(XR_FREQUENCY_UNSPECIFIED).address())));}}
    private static Pose pose(XrPosef p){return new Pose(new V(p.position$().x(),p.position$().y(),p.position$().z()),new Q(p.orientation().x(),p.orientation().y(),p.orientation().z(),p.orientation().w()).unit());}
    private static Fov fov(XrFovf f){return new Fov(f.angleLeft(),f.angleRight(),f.angleUp(),f.angleDown());}
    private Pose locate(XrSpace space,long time,MemoryStack s){XrSpaceLocation location=XrSpaceLocation.calloc(s).type$Default();check(xrLocateSpace(space,local,time,location));long valid=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;if((location.locationFlags()&valid)!=valid)return null;Pose result=pose(location.pose());return result.finite()?result:null;}
    private XrActionStateGetInfo query(String action,int h,MemoryStack s){return XrActionStateGetInfo.calloc(s).type$Default().action(actions.get(action)).subactionPath(handPaths[h]);}
    private float analog(String name,int h,MemoryStack s){XrActionStateFloat out=XrActionStateFloat.calloc(s).type$Default();check(xrGetActionStateFloat(session,query(name,h,s),out));return out.isActive()?out.currentState():0;}
    private boolean button(String name,int h,MemoryStack s){XrActionStateBoolean out=XrActionStateBoolean.calloc(s).type$Default();check(xrGetActionStateBoolean(session,query(name,h,s),out));return out.isActive()&&out.currentState();}
    private Tracking.Hand hand(int h,long time,MemoryStack s){
        XrActionStatePose action=XrActionStatePose.calloc(s).type$Default();check(xrGetActionStatePose(session,query("grip_pose",h,s),action));if(!action.isActive())return Tracking.Hand.LOST;
        Pose grip=locate(gripSpaces[h],time,s),aim=locate(aimSpaces[h],time,s);if(grip==null||aim==null)return Tracking.Hand.LOST;
        XrActionStateVector2f stick=XrActionStateVector2f.calloc(s).type$Default();check(xrGetActionStateVector2f(session,query("stick",h,s),stick));
        return new Tracking.Hand(grip,aim,true,analog("trigger",h,s),analog("squeeze",h,s),stick.isActive()?stick.currentState().x():0,stick.isActive()?stick.currentState().y():0,button("primary",h,s),button("secondary",h,s),button("stick_click",h,s),button("menu",h,s));
    }
    public boolean frame(Renderer renderer)throws Exception {
        owner();if(!running||exit)return false;
        timings.begin();boolean complete=false,successful=false;long period=0;
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        try(MemoryStack s=MemoryStack.stackPush()){
            XrFrameState frame=XrFrameState.calloc(s).type$Default();long waitStart=timings.mark();check(xrWaitFrame(session,XrFrameWaitInfo.calloc(s).type$Default(),frame));timings.cpu(VrFrameStats.WAIT,waitStart);period=frame.predictedDisplayPeriod();
            long beginStart=timings.mark();check(xrBeginFrame(session,XrFrameBeginInfo.calloc(s).type$Default()));timings.cpu(VrFrameStats.BEGIN,beginStart);timings.gpu.begin();
            XrCompositionLayerProjection layer=XrCompositionLayerProjection.calloc(s).type$Default().space(local);
            Throwable failure=null;
            try {
                long trackingStart=timings.mark();
                XrActiveActionSet.Buffer active=XrActiveActionSet.calloc(1,s);active.get(0).actionSet(actionSet);
                int sync=xrSyncActions(session,XrActionsSyncInfo.calloc(s).type$Default().activeActionSets(active));if(sync!=XR_SESSION_NOT_FOCUSED)check(sync);
                XrViewState valid=XrViewState.calloc(s).type$Default();IntBuffer count=s.mallocInt(1);
                check(xrLocateViews(session,XrViewLocateInfo.calloc(s).type$Default().viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO).displayTime(frame.predictedDisplayTime()).space(local),valid,count,views));
                long flags=XR_VIEW_STATE_POSITION_VALID_BIT|XR_VIEW_STATE_ORIENTATION_VALID_BIT;
                Pose head=locate(view,frame.predictedDisplayTime(),s);
                if(frame.shouldRender()&&head!=null&&(valid.viewStateFlags()&flags)==flags&&count.get(0)==2){
                    boolean focused=state==XR_SESSION_STATE_FOCUSED;
                    Tracking snapshot=new Tracking(frame.predictedDisplayTime(),focused,head,new Tracking.Eye(pose(views.get(0).pose()),fov(views.get(0).fov())),new Tracking.Eye(pose(views.get(1).pose()),fov(views.get(1).fov())),focused?hand(0,frame.predictedDisplayTime(),s):Tracking.Hand.LOST,focused?hand(1,frame.predictedDisplayTime(),s):Tracking.Hand.LOST);
                    renderer.tracking(snapshot);
                    timings.tracking(trackingStart);
                    XrCompositionLayerProjectionView.Buffer layers=XrCompositionLayerProjectionView.calloc(2,s);layers.forEach(v->v.type$Default());
                    for(int h=0;h<2;h++){
                        Swapchain eye=eyes[h];IntBuffer index=s.mallocInt(1);long swapStart=timings.mark();check(xrAcquireSwapchainImage(eye.handle,XrSwapchainImageAcquireInfo.calloc(s).type$Default(),index));
                        boolean waited=false;
                        try {
                            check(xrWaitSwapchainImage(eye.handle,XrSwapchainImageWaitInfo.calloc(s).type$Default().timeout(XR_INFINITE_DURATION)));waited=true;
                            timings.cpu(VrFrameStats.SWAP,swapStart);
                            glBindFramebuffer(GL_FRAMEBUFFER,eye.fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,eye.images.get(index.get(0)).image(),0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,eye.depth,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);
                            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete XR eye framebuffer");
                            glViewport(0,0,eye.width,eye.height);glDisable(GL_SCISSOR_TEST);if(format==GL_SRGB8_ALPHA8)glEnable(GL_FRAMEBUFFER_SRGB);else glDisable(GL_FRAMEBUFFER_SRGB);
                            renderer.eye(snapshot,h,eye.fbo,eye.width,eye.height);
                        }finally{if(waited){long releaseStart=timings.mark();try{check(xrReleaseSwapchainImage(eye.handle,XrSwapchainImageReleaseInfo.calloc(s).type$Default()));}finally{timings.cpu(VrFrameStats.SWAP,releaseStart);}}}
                        layers.get(h).pose(views.get(h).pose()).fov(views.get(h).fov()).subImage(i->i.swapchain(eye.handle).imageRect(r->r.offset(o->o.set(0,0)).extent(e->e.set(eye.width,eye.height))));
                    }
                    layer.views(layers);complete=true;
                }else {renderer.inactive();timings.tracking(trackingStart);}
            }catch(Throwable e){failure=e;throw e;}
            finally {long submitStart=timings.mark();try{check(xrEndFrame(session,XrFrameEndInfo.calloc(s).type$Default().displayTime(frame.predictedDisplayTime()).environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE).layers(complete?s.pointers(layer.address()):null)));}catch(Throwable end){if(failure!=null)failure.addSuppressed(end);else throw end;}finally{timings.cpu(VrFrameStats.SUBMIT,submitStart);}}
            successful=true;
            return complete;
        }finally{try{glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);if(scissor)glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);if(srgb)glEnable(GL_FRAMEBUFFER_SRGB);else glDisable(GL_FRAMEBUFFER_SRGB);}finally{timings.gpu.finish(successful&&complete);timings.finish(successful?(complete?1:0):-1,period);}}
    }
    private static void check(int result){if(result<0)throw new XrResultException(result);}
    @Override public void close(){if(closed)return;if(Thread.currentThread()!=thread)throw new IllegalStateException("Close XR on GL thread");closed=true;
        timings.close();
        for(XrSpace space:gripSpaces)if(space!=null)xrDestroySpace(space);for(XrSpace space:aimSpaces)if(space!=null)xrDestroySpace(space);
        if(view!=null)xrDestroySpace(view);if(local!=null)xrDestroySpace(local);
        for(Swapchain eye:eyes)if(eye!=null){if(eye.handle!=null)xrDestroySwapchain(eye.handle);if(eye.images!=null)eye.images.free();if(eye.fbo!=0)glDeleteFramebuffers(eye.fbo);if(eye.depth!=0)glDeleteTextures(eye.depth);}
        if(session!=null)xrDestroySession(session);if(deviceContext!=0)ReleaseDC(windowHandle,deviceContext);for(XrAction action:actions.values())xrDestroyAction(action);if(actionSet!=null)xrDestroyActionSet(actionSet);if(instance!=null)xrDestroyInstance(instance);
        if(events!=null)events.free();if(views!=null)views.free();running=false;
    }
}
