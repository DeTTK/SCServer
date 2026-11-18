package cinema;
import java.lang.reflect.*;
import static cinema.Native.*;
/** Run native skinned meshes inside the native material/lighting batch. */
final class FpWorldDraw implements InvocationHandler {
    final CinematicCamera camera;final Object renderer,context;
    FpWorldDraw(CinematicCamera camera,Object renderer,Object context){this.camera=camera;this.renderer=renderer;this.context=context;}
    public Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
        if(!method.getName().equals("invoke"))return null;
        BodyVisibility.load(camera);
        boolean fp=field(type("885517060"),"default").getBoolean(null);
        field(type("885517060"),"default").setBoolean(null,true);
        try{
            BodyVisibility.scope(camera,"Руки",renderer,"szFp_final",camera.fpActor,context);
            BodyVisibility.scope(camera,"Рукава брони",renderer,"szFp_extends",camera.fpActor,context);
            BodyVisibility.scope(camera,"Оружие",renderer,"szFp_throw",context);
            // Native FP flushes the opaque weapon before the unblurred sight pass.
            Native.call(renderer,"szFp_extends","void");
            BodyVisibility.scope(camera,"Оружие",renderer,"szFp_private",context);
        }finally{field(type("885517060"),"default").setBoolean(null,fp);}
        return get(type("kotlin.Unit"),"INSTANCE");
    }
}
