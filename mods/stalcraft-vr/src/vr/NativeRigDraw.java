package vr;
import java.lang.reflect.*;
import java.util.Objects;
import static org.lwjgl.opengl.GL33.*;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** Native materials, equipped sleeves and attachments with owned IK poses and world transforms. */
final class NativeRigDraw {
    private Object renderer,context,proxy;
    private Object handsModel,handsPlayer,handsSkin;
    private boolean handsVisible;
    private NativePose hands,sleeves,weapon,magazine;
    private Pose body;
    private boolean scope;
    private int meshes;
    private WorldDebris debris;
    final NativeMeshMask masks=new NativeMeshMask();
    final NativeOptic optic=new NativeOptic();
    final NativeFpEffects effects=new NativeFpEffects();
    final NativeVrBody torso=new NativeVrBody();
    private boolean bodyPass;
    boolean bodyPass(){return bodyPass;}
    void prepare(GameSession game)throws Exception {
        debris=game.debris;
        torso.prepare(game);
        optic.clear();
        hands=sleeves=weapon=magazine=null;context=proxy=null;
        if(game.item==null||game.result==null||game.headBlocked||!game.fresh()||!game.profile.load())return;
        Object component=call(type("1638940636"),"public","1638940636",game.player);context=call(component,"volatile","1382318679");if(context==null)return;
        call(context,"protected","void");call(context,"try","void");if(!(boolean)call(context,"szFp_enum","boolean"))return;
        Object data=call(get(context,"double"),"byte","java.lang.Object");if(data==null)return;
        hands=rig(data,"instanceof");sleeves=rig(data,"for");weapon=game.profile.weapon(game);
        if(game.gun.magazinePose()!=null&&game.gun.interactionHand(game.tracking).valid())magazine=game.profile.magazine();
        if(hands==null)return;
        V forward=game.tracking.head().forward();Q yaw=Q.axis(new V(0,1,0),Math.atan2(-forward.x(),-forward.z()));
        Object cameraBone=call(type("137379477"),"native","470347082");String name=(String)get(cameraBone,"this");
        V centre=hands.sample(name).p();body=new Pose(game.tracking.head().p().sub(yaw.rotate(centre)),yaw);
        NativePose original=new NativePose(hands.skeleton,hands.pose);solve(hands,game);
        if(sleeves!=null){if(sleeves.index.containsKey("arm_r")||sleeves.index.containsKey("arm_l"))sleeves.followForearms(original,hands);else solve(sleeves,game);}
        if(renderer==null)renderer=type("sz.animation.fp.N1320579730").getConstructor().newInstance();
        // The native getter constructs a base model even on a material-cache hit.
        // Resolve it only when the owner/skin changes, before either eye draws.
        Object skin=get(call(game.player,"const","658914576"),"super");
        if(handsModel==null||handsPlayer!=game.player||!Objects.equals(handsSkin,skin)){
            Object model=call(renderer,"szFp_protected","303756944",game.player);
            call(model,"while","303756944");handsModel=model;handsPlayer=game.player;handsSkin=skin;
        }
        // Preserve both native hand-visibility gates from szFp_final.
        Object visibility=call(type("916325480"),"double","916325480",game.player);
        handsVisible=(visibility==null||!(boolean)get(visibility,"private"))&&(boolean)call(call(context,"szFp_this","168071319"),"szFp_handsEnabled","boolean",game.item);
        proxy=FpContext.create(context,hands.pose,sleeves==null?null:sleeves.pose,weapon.pose,game.attachments.weaponItem(game));
        optic.prepare(game,context,weapon);
    }
    private NativePose rig(Object data,String name)throws Exception {
        Object rig=call(data,name,"387875964");if(rig==null)return null;
        Object skeleton=call(rig,"strictfp","1612003613");NativePose pose=new NativePose(skeleton,call(rig,"extends","1118769848"));
        // Native transition animations can expose a temporarily degenerate pose.
        // Fall back to the rig's bind pose, without ending the headset session.
        for(String side:new String[]{"Right","Left"})if(pose.index.containsKey(side+"Hand")){
            Pose a=pose.sample(side+"Arm"),b=pose.sample(side+"ForeArm"),c=pose.sample(side+"Hand");
            if(!a.finite()||!b.finite()||!c.finite()||b.p().sub(a.p()).length()<.01||c.p().sub(b.p()).length()<.01)return new NativePose(skeleton,get(skeleton,"double"));
        }
        return pose;
    }
    private void solve(NativePose pose,GameSession game)throws Exception {
        Pose inverse=body.inverse();
        if(game.tracking.right().valid())pose.arm("Right",inverse.compose(game.gun.rightWrist(game.tracking.right())),inverse.transform(game.tracking.head().p().add(body.q().rotate(new V(.55,-.45,.15)))));
        else pose.hide("RightArm");
        if(game.tracking.left().valid()){
            Pose target=game.gun.leftWrist(game.tracking.left());
            pose.arm("Left",inverse.compose(target),inverse.transform(game.tracking.head().p().add(body.q().rotate(new V(-.55,-.45,.15)))));
        }
        else pose.hide("LeftArm");
    }
    void draw(StereoWorld world)throws Exception {
        GameSession game=world.gameplay;boolean rigReady=hands!=null&&proxy!=null&&game.result!=null;if(!rigReady&&torso.parts.isEmpty()&&(debris==null||debris.items.isEmpty()))return;
        if(renderer==null)renderer=type("sz.animation.fp.N1320579730").getConstructor().newInstance();
        Pose eye=world.eyePose();
        int program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),buffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK),stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);
        int func=glGetInteger(GL_DEPTH_FUNC);double[] range=new double[2];glGetDoublev(GL_DEPTH_RANGE,range);
        Object state=call(get(type("1973624787"),"package"),"strictfp","1973624787");boolean fp=(boolean)get(type("885517060"),"default");int light=((Number)call(type("1881987517"),"do","int")).intValue();
        call(type("889335306"),"if","void");call(type("1308394360"),"static","void");
        try{
            // This is a world model: native scene depth/fog, not a screen FP pass.
            set(type("885517060"),"default",false);glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);glDepthRange(0,1);glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
            // The native mesh shader reads a global packed block/sky light. Without
            // sampling this actor it inherits whichever entity was drawn last.
            call(renderer,"szFp_this","void",game.player);
            call(get(type("1308394360"),"interface"),"finally","447252483",get(world.camera,"throws"));set(type("1308394360"),"abstract",true);
            Class<?> fn=type("kotlin.jvm.functions.Function0");Object draw=Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(p,m,a)->{
                if(!m.getName().equals("invoke"))return null;
                scope=true;
                try{
                    if(torso.root!=null){matrix(torso.root,eye);bodyPass=true;
                        try{for(NativeVrBody.Part part:torso.parts)call(type("656386660"),"long","void",part.model(),true,false,part.pose().pose);}
                        finally{bodyPass=false;}
                        call(renderer,"szFp_extends","void");
                    }
                    if(rigReady){matrix(body,eye);if(handsVisible)call(call(handsModel,"public","631538241"),"for","void",hands.pose);if(sleeves!=null)call(renderer,"szFp_extends","void",game.player,proxy);
                    if(game.gun.held){matrix(game.result.weapon(),eye);meshes=0;call(renderer,"szFp_throw","void",proxy);
                    // A native non-firearm/no-op provider cannot silently erase the rifle.
                    if(meshes==0)call(call(game.profile.model,"public","631538241"),"for","void",weapon.pose);}
                    if(magazine!=null&&game.gun.magazinePose()!=null){matrix(game.gun.magazinePose(),eye);if(!game.attachments.drawMagazine(game))call(call(game.profile.model,"public","631538241"),"for","void",magazine.pose);}
                    call(renderer,"szFp_extends","void");
                    if(game.gun.held)effects.draw(world);}
                    if(debris!=null)for(WorldDebris.Item prop:debris.items){matrix(prop.body().pose,eye);call(call(prop.model(),"public","631538241"),"for","void",prop.mesh().pose);}
                    call(renderer,"szFp_extends","void");
                }finally{scope=false;}
                return get(type("kotlin.Unit"),"INSTANCE");
            });
            // Add to the existing scene bloom attachments; never clear world bloom here.
            call(call(get(type("449323257"),"do"),"return","1895043948"),"if","void",draw,true,false);
        }finally{
            scope=false;call(type("1881987517"),"while","void",light);set(type("885517060"),"default",fp);call(type("1308394360"),"final","void");call(type("889335306"),"default","void");((AutoCloseable)state).close();
            glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_STENCIL_TEST,stencil);toggle(GL_SCISSOR_TEST,scissor);glDepthMask(mask);glDepthFunc(func);glDepthRange(range[0],range[1]);
        }
    }
    private void matrix(Pose model,Pose eye)throws Exception {
        // Attachments/sleeves may enqueue native meshes. Finish them while the
        // previous model matrix is still current, before switching to the rifle.
        call(renderer,"szFp_extends","void");
        call(get(type("889335306"),"implements"),"transient","447252483",(Object)Pose.matrix(eye.inverse().compose(model)));set(type("889335306"),"else",true);call(renderer,"szFp_super","void");
    }
    float[] effectBone(Object owner,String bone,GameSession game)throws Exception {
        if(owner!=context||weapon==null||game.result==null||!game.gun.held||!game.fresh())return null;
        if(weapon.index.containsKey(bone))return Pose.matrix(game.result.weapon().compose(weapon.sample(bone)));
        if(hands!=null&&hands.index.containsKey(bone))return Pose.matrix(body.compose(hands.sample(bone)));
        return null;
    }
    Object mesh(Object mesh,Object pose)throws Exception {if(!scope)return null;if(optic.replaces(get(mesh,"short")))return Boolean.FALSE;call(mesh,"throws","void",false,pose,null);meshes++;return Boolean.FALSE;}
    Object mask(Object mesh,Object pose,Object shader)throws Exception {
        if(!scope)return null;
        for(NativeVrBody.Part part:torso.parts)if(part.pose().pose==pose)return masks.draw(mesh,shader,part.pose());
        for(NativePose candidate:new NativePose[]{hands,sleeves,weapon,magazine})if(candidate!=null&&candidate.pose==pose)return masks.draw(mesh,shader,candidate);
        if(debris!=null)for(WorldDebris.Item prop:debris.items)if(prop.mesh().pose==pose)return masks.draw(mesh,shader,prop.mesh());
        return null;
    }
    private static void toggle(int capability,boolean value){if(value)glEnable(capability);else glDisable(capability);}
}
