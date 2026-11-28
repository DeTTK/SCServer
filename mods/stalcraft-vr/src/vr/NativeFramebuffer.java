package vr;
import static vr.NativeAccess.*;
import static org.lwjgl.opengl.GL33.*;

/** Native fade widgets need the wrapper as well as the restored GL binding. */
final class NativeFramebuffer {
    static Object current()throws Exception{return get(type("105717861"),"throws");}
    static void restore(Object wrapper)throws Exception {
        if(wrapper!=null&&((Number)get(wrapper,"catch")).intValue()!=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING))wrapper=null;
        set(type("105717861"),"throws",wrapper);
    }
}
