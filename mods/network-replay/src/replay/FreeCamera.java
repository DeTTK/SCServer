package replay;

import static replay.Pose.*;

/** Render-only spectator: player entities, collisions and packets stay native. */
final class FreeCamera {
    Object game,player,camera,neutral,shakeRotation;Pose pose;double yaw,pitch;double speed=8;boolean recordedAnchor,dragging;
    int following=-1;
    long recorderBodyDraws;
    long recorderActorDraws;
    LocalMotion sourceMotion;long sourceEpoch;
    szmods.api.InputSnapshot input;double mouseX,mouseY,lastMouseX,lastMouseY;boolean rightMouse,mouseBlocked,externalView;
    void reset(){if(ReplayRender.viewerCamera==camera)ReplayRender.viewerCamera=null;game=player=camera=null;pose=null;recordedAnchor=dragging=false;following=-1;recorderBodyDraws=recorderActorDraws=0;sourceMotion=null;sourceEpoch=0;input=null;rightMouse=mouseBlocked=externalView=false;}
    void prepare(Object game)throws Exception {
        this.game=game;
        Object next=Native.field(game,"e");if(next!=player){player=next;if(!ReplayRender.sceneIndependentVisibility()){pose=null;recordedAnchor=false;following=-1;}}if(player==null){camera=null;return;}
        camera=Native.call(player,"enum","1389510037");
        ReplayRender.viewerCamera=camera;
        if(neutral==null)neutral=Native.type("1899091998").getConstructor().newInstance();
        if(pose==null)at(player);
        // The perspective hook intercepts setters, not the getter used by
        // native actor culling. Flying away never calls those setters.
        // Publish the spectator mode before culling as well as the view matrix.
        if(!externalView)publishPerspective(eye());
    }
    void recorded(Object game,LocalMotion motion)throws Exception {
        if(!ReplayRender.sceneIndependentVisibility()&&(sourceMotion!=motion||sourceEpoch!=motion.cameraEpoch)){
            sourceMotion=motion;sourceEpoch=motion.cameraEpoch;pose=null;recordedAnchor=dragging=false;following=-1;
        }
        prepare(game);
        if(!recordedAnchor&&player!=null){
            if(!anchor(motion.cameraAnchor))at(player);
        }
    }
    void sampleFollowing()throws Exception {
        if(following==-1||game==null)return;
        Object world=Native.call(game,"throws","1054393445");Object target=world==null?null:Native.call(world,"continue","1217489680",following);
        if(target==null){following=-1;return;}
        float alpha=renderAlpha();
        sampleEntity(target,alpha);
    }
    void sampleEntity(Object target,float alpha)throws Exception {
        float currentYaw=((Number)Native.call(target,"catch","float")).floatValue(),previousYaw=((Number)Native.call(target,"throws","float")).floatValue();
        float currentPitch=((Number)Native.call(target,"volatile","float")).floatValue(),previousPitch=((Number)Native.call(target,"class","float")).floatValue();
        Object p=Native.call(Native.type("576005245"),"char","153304200",target,alpha);
        yaw=interpolateYaw(previousYaw,currentYaw,alpha);pitch=previousPitch+(currentPitch-previousPitch)*alpha;
        Pose sampled=new Pose(new V(((Number)Native.field(p,"case")).doubleValue(),((Number)Native.field(p,"void")).doubleValue()+1.8,((Number)Native.field(p,"import")).doubleValue()),Q.euler(yaw,pitch,0));
        pose=new Pose(sampled.p().add(sampled.q().rotate(new V(0,0,3))),sampled.q());
    }
    static float renderAlpha()throws Exception {return Math.max(0f,Math.min(1f,((Number)Native.call(Native.field(Native.type("1402131154"),"h"),"class","float")).floatValue()));}
    static double interpolateYaw(float previous,float current,float alpha)throws Exception {return ((Number)Native.call(Native.type("189575079"),"package","float",previous,current,alpha)).doubleValue();}
    boolean anchor(LocalMotion.CameraAnchor source){
        if(recordedAnchor||player==null||source==null||source.player()!=player)return false;
        pose=source.pose();yaw=source.yaw();pitch=source.pitch();recordedAnchor=true;return true;
    }
    void at(Object entity)throws Exception {
        yaw=((Number)Native.call(entity,"catch","float")).doubleValue();pitch=((Number)Native.call(entity,"volatile","float")).doubleValue();
        Object p=Native.call(Native.type("576005245"),"char","153304200",entity,1f);
        pose=new Pose(new V(((Number)Native.field(p,"case")).doubleValue(),((Number)Native.field(p,"void")).doubleValue()+1.8,((Number)Native.field(p,"import")).doubleValue()),Q.euler(yaw,pitch,0));
    }
    void behind(Object entity)throws Exception {at(entity);pose=new Pose(pose.p().add(pose.q().rotate(new V(0,0,3))),pose.q());}
    boolean insideBody(V eye){return pose!=null&&pose.p().add(eye.mul(-1)).length()<1.5;}
    V eye()throws Exception {
        if(ReplayRender.sceneIndependentVisibility()&&pose!=null)return pose.p();
        Object p=Native.call(Native.type("576005245"),"char","153304200",player,1f);
        return new V(((Number)Native.field(p,"case")).doubleValue(),((Number)Native.field(p,"void")).doubleValue()+1.8,((Number)Native.field(p,"import")).doubleValue());
    }
    Object perspective()throws Exception {return perspective(eye());}
    Object perspective(V eye)throws Exception {
        return Native.field(Native.type("1308185038"),"THIRD_PERSON_BACK");
    }
    void publishPerspective(V eye)throws Exception {SourceAdapters.put(camera,"do",perspective(eye));if(ReplayRender.recorder==player)ReplayRender.recorderVisible=true;}
    void visit(Object entity)throws Exception {following=-1;behind(entity);recordedAnchor=true;dragging=false;}
    void follow(Object entity)throws Exception {behind(entity);recordedAnchor=true;following=ReplayRender.sceneCameraTarget(entity);dragging=false;}
    static boolean key(int k)throws Exception{return (boolean)Native.call(Native.type("877572775"),"szmods$original$interface","boolean",k);}
    void input(szmods.api.InputSnapshot next,double x,double y,boolean right){input=next;mouseX=x;mouseY=y;rightMouse=right;}
    void blockInput(boolean nativeMenu,boolean panelHover){
        // Once a flight drag has started outside the panel it owns the mouse
        // until release. Hovering a window along the way cannot steal it.
        mouseBlocked=nativeMenu||(panelHover&&!dragging);
    }
    boolean pressed(int key){return input!=null&&input.keyDown(key);}
    void move(double dt)throws Exception {
        // Frame callbacks also run while a new world has no ImGui context.
        // Camera input comes from the runtime's hardware snapshot, never JNI UI.
        if(externalView||pose==null||input==null||!rightMouse||mouseBlocked){dragging=false;return;}
        following=-1;recordedAnchor=true;if(!dragging){dragging=true;lastMouseX=mouseX;lastMouseY=mouseY;return;}
        yaw=Math.IEEEremainder(yaw-(mouseX-lastMouseX)*.2,360);pitch=Math.max(-89.8,Math.min(89.8,pitch-(mouseY-lastMouseY)*.2));
        lastMouseX=mouseX;lastMouseY=mouseY;
        Q q=Q.euler(yaw,pitch,0);V direction=q.rotate(new V((pressed(68)?1:0)-(pressed(65)?1:0),0,(pressed(83)?1:0)-(pressed(87)?1:0))).add(new V(0,(pressed(69)?1:0)-(pressed(81)?1:0),0));
        pose=new Pose(pose.p().add(direction.unit().mul(Math.max(0,Math.min(.1,dt))*speed*(pressed(340)?4:1))),q);
    }
    void view(Object matrix)throws Exception {
        Native.call(matrix,"interface","1102042093",(Object)pose.view());
        if(!ReplayRender.shakeClockEnabled)return;
        Object shake=Native.field(Native.type("1427337337"),"byte");float magnitude=0;
        for(String field:new String[]{"catch","implements"})for(String axis:new String[]{"assert","byte","catch"})magnitude+=Math.abs(((Number)Native.field(Native.field(shake,field),axis)).floatValue());
        if(magnitude==0)return;
        // The native camera applies shake before orientation and world translation.
        double[] rotation=new Pose(new V(0,0,0),pose.q()).view();float[] floats=new float[16];for(int c=0;c<4;c++)for(int r=0;r<4;r++)floats[r*4+c]=(float)rotation[c*4+r];
        if(shakeRotation==null)shakeRotation=Native.type("447252483").getConstructor().newInstance();Native.call(shakeRotation,"default","447252483",(Object)floats);
        Native.call(matrix,"interface","1102042093",(Object)new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
        Native.call(shake,"private","void",matrix);Native.call(matrix,"int","1102042093",shakeRotation,matrix);Native.call(matrix,"else","1102042093",-pose.p().x(),-pose.p().y(),-pose.p().z());
        ReplayRender.cameraShakeCalls++;
    }
    Object hook(String name,Object[] args)throws Exception {
        if(camera==null)return null;
        if(name.equals("model.actor-visible.v1")&&args[0]==player)recorderActorDraws++;
        // 1478690128 shadows its component owner's instance field with a static registry.
        if(name.equals("model.body-visible.v1")&&args[0]!=null&&Native.type("1078236163").getField("assert").get(args[0])==player)recorderBodyDraws++;
        if(name.equals("fp.screen-pass.v1")&&args[0]==player)return Boolean.TRUE;
        if(name.equals("fp.body-visible.v1"))return Boolean.FALSE;
        // Native movement/actions are gated separately; menus and mod hotkeys stay available.
        if(name.equals("player.movement-intent.v1"))return neutral;
        if(name.equals("view.block-body-look.v1"))return Boolean.TRUE;
        if(externalView&&name.startsWith("camera."))return null;
        // The native FOV multiplier is advanced by the local gameplay tick.
        // Indexed spectator playback has no such tick; use the game setting
        // directly instead of an uninitialized/stale movement multiplier.
        if(name.equals("camera.fov.v1")&&args[0]==camera&&ReplayRender.sceneIndependentVisibility())
            return ((Number)Native.call(Native.field(Native.type("1546247182"),"a"),"class","float")).floatValue();
        if(name.equals("camera.perspective.v1")&&args[0]==camera)return perspective();
        if(name.equals("camera.native-view.v1")&&args[0]==camera){
            sampleFollowing();publishPerspective(eye());
            Object matrix=args[1];
            // Indexed scenes supply the complete spectator matrix. The legacy
            // FP calculation reads animation objects from the old simulation;
            // their resources can disappear when scene variants are evicted.
            if(!ReplayRender.sceneIndependentVisibility())Native.call(camera,"szmods$flight$enum","void",matrix);
            view(matrix);
            Native.call(Native.field(camera,"assert"),"enum","153304200",pose.p().x(),pose.p().y(),pose.p().z());
            return Boolean.TRUE;
        }
        return null;
    }
}
