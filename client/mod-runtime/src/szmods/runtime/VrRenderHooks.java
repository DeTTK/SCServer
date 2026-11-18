package szmods.runtime;
import java.util.Deque;

/** Optional stereo boundary below the client tick/frame preparation and above HUD. */
public final class VrRenderHooks {
    private VrRenderHooks(){}
    public static boolean world(Object renderer,float partial){return Boolean.TRUE.equals(ModLoader.clientHook("vr.render-world.v1",renderer,partial));}
    public static void viewReady(Object renderer){ModLoader.clientHook("vr.render-view-ready.v1",renderer);}
    public static void projection(Object camera,Object matrix){ModLoader.clientHook("vr.projection.v1",camera,matrix);}
    public static Object poll(Deque<?> queue){return Boolean.TRUE.equals(ModLoader.clientHook("vr.second-eye.v1"))?null:queue.poll();}
    public static void effects(Object manager,double delta){
        if(Boolean.TRUE.equals(ModLoader.clientHook("vr.effects-prepared.v1"))||Boolean.TRUE.equals(ModLoader.clientHook("vr.second-eye.v1")))return;
        try{manager.getClass().getMethod("continue",double.class).invoke(manager,delta);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException("Native effect update",e.getCause());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Native effect manager contract",e);}
    }
    public static void effectsFinish(Object manager,double delta){
        if(Boolean.TRUE.equals(ModLoader.clientHook("vr.effects-prepared.v1"))||Boolean.TRUE.equals(ModLoader.clientHook("vr.second-eye.v1")))return;
        try{manager.getClass().getMethod("char",double.class).invoke(manager,delta);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException("Native effect finish",e.getCause());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Native effect manager contract",e);}
    }
    public static void desktop(Object renderer,float partial){ModLoader.clientHook("vr.desktop-ui.v1",renderer,partial);}
    /** Native self-FP tracer adjustment is already included in a physical VR muzzle ray. */
    public static boolean offsetTracer(int shooter,int local){
        return shooter==local&&!Boolean.FALSE.equals(ModLoader.clientHook("weapon.tracer-offset.v1",shooter,local));
    }
    /** Replace the bone basis before authored flash/smoke transforms are applied. */
    public static void fpEffect(Object context,String bone,Object matrix){
        Object value=ModLoader.clientHook("fp.effect-bone.v1",context,bone);
        if(!(value instanceof float[] transform)||transform.length!=16)return;
        for(float n:transform)if(!Float.isFinite(n))return;
        try{matrix.getClass().getMethod("transient",float[].class).invoke(matrix,(Object)transform);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException("VR effect transform",e.getCause());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Native effect matrix contract",e);}
    }
}
