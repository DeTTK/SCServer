package cinema;

import static cinema.Native.*;
import static org.lwjgl.opengl.GL33.*;

/** Draws the native skinned hands and weapon at their actor-relative FP world basis.
 * A separate opt-in native hook suppresses the screen-space pass. No animation copy. */
final class FpScene {
    final FpDepthLayer depthLayer=new FpDepthLayer();
    Object renderer;
    long rendered=-1;
    String status="Ожидание мирового прохода";
    static Pose modelView(Pose world,Pose view){return new Pose(view.q().inverse().rotate(world.p().sub(view.p())),view.q().inverse().mul(world.q()));}
    void render(CinematicCamera c,Object cam)throws Exception {
        if(!c.detachedFp()||cam!=c.camera||rendered==c.frame||!c.prepared)return;
        rendered=c.frame;
        if(c.fpActor==null)return;
        Object component=call(type("1638940636"),"public","1638940636",c.fpActor);
        Object context=call(component,"volatile","1382318679");if(context==null){status="FP контекст недоступен";return;}
        if(!c.replayFp){call(context,"protected","void");call(context,"try","void");}
        if(!(boolean)call(context,"szFp_enum","boolean")){status="FP анимации не готовы — экипируйте оружие";return;}
        AutoCloseable replayScope=c.replayFp?(AutoCloseable)Native.runtimeHook("replay.fp-render-scope.v1",c.fpActor):null;
        try{renderScoped(c,context);}finally{if(replayScope!=null)replayScope.close();}
    }
    void renderScoped(CinematicCamera c,Object context)throws Exception {
        Bones hands=Native.bones(c.fpActor,1,c.replayFp?c.subject:c.fpActor);if(hands==null){status="Скелет рук недоступен";return;}
        if(renderer==null)renderer=type("sz.animation.fp.N1320579730").getConstructor().newInstance();
        int program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),buffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int framebuffer=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);int depthFunc=glGetInteger(GL_DEPTH_FUNC);
        double[] depthRange=new double[2];glGetDoublev(GL_DEPTH_RANGE,depthRange);
        Object state=call(get(type("1973624787"),"package"),"strictfp","1973624787");
        call(type("889335306"),"if","void");
        call(type("1308394360"),"static","void");
        try {
            // 889335306 holds MODEL-VIEW, not a world model matrix. The old path
            // discarded the scene view, making arms move with the viewer.
            Pose.V origin=hands.origin();
            Pose world=new Pose(origin,hands.basis());float[] matrix=new float[16];double[] model=modelView(world,c.renderPose).model();for(int i=0;i<16;i++)matrix[i]=(float)model[i];
            call(get(type("889335306"),"implements"),"transient","447252483",(Object)matrix);
            field(type("889335306"),"else").setBoolean(null,true);
            if(c.replayFp&&!c.editing){
                Object definition=call(context,"szFp_this","168071319"),item=call(context,"szFp_static","211097523");
                // Native FP assets have an item-specific projection. A world-camera
                // projection clips the hands/weapon, especially on wide displays.
                call(definition,"goto","void",item);
            }else call(get(type("1308394360"),"interface"),"finally","447252483",get(c.camera,"throws"));
            field(type("1308394360"),"abstract").setBoolean(null,true);
            call(renderer,"szFp_super","void"); // refresh FP shader projection * model-view cache
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);glDepthRange(0,1);
            glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
            Class<?> fn=type("kotlin.jvm.functions.Function0");
            Object draw=java.lang.reflect.Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},new FpWorldDraw(c,renderer,context));
            Object materials=call(get(type("449323257"),"do"),"return","1895043948");
            if(c.replayFp&&!c.active){try(var layer=depthLayer.begin()){call(materials,"if","void",draw,true,true);}status="Руки и оружие: FP проход";}
            else {call(materials,"if","void",draw,true,true);status="Руки и оружие: мировой проход";}
        }finally {
            call(type("1308394360"),"final","void");call(type("889335306"),"default","void");((AutoCloseable)state).close();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);glViewport(vp[0],vp[1],vp[2],vp[3]);
            glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);
            ScenePreview.toggle(GL_DEPTH_TEST,depth);ScenePreview.toggle(GL_BLEND,blend);ScenePreview.toggle(GL_CULL_FACE,cull);glDepthMask(mask);
            ScenePreview.toggle(GL_STENCIL_TEST,stencil);ScenePreview.toggle(GL_SCISSOR_TEST,scissor);glDepthFunc(depthFunc);glDepthRange(depthRange[0],depthRange[1]);
        }
        if(!c.editing)SceneDebug.capture(c);
    }
    void close(){depthLayer.close();}
}
