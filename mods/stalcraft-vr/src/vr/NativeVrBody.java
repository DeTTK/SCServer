package vr;
import java.util.*;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** Existing FP body/clothes providers, owned poses and a world-space body root. */
final class NativeVrBody {
    record Part(Object model,NativePose pose){}
    final List<Part> parts=new ArrayList<>();
    Pose root;
    void prepare(GameSession game)throws Exception {
        parts.clear();root=null;
        if(game.player==null||game.headBlocked||!game.fresh())return;
        Object body=call(type("1478690128"),"do","254941120",game.player);
        call(body,"try","boolean",true,null);
        Object skeleton=call(body,"catch","1612003613");if(skeleton==null)return;
        Object pose=call(body,"enum","1118769848"),packPose=call(body,"if","1118769848"),none=get(type("1969001007"),"NONE");
        add(call(body,"void","1304833431",none),skeleton,pose);
        add(call(body,"break","1304833431",none),skeleton,pose);
        add(call(body,"boolean","1304833431"),skeleton,packPose);
        // The full character pose already contains actor yaw. The existing FP
        // body's 1597325473.transient only translates to actor origin; applying
        // heading again rotates the torso away from the headset a second time.
        root=new Pose(origin(game.player,1),Q.ID);
    }
    private void add(Object resource,Object skeleton,Object source)throws Exception {
        if(resource==null||source==null)return;Object model=call(resource,"byte","java.lang.Object");if(model==null)return;
        NativePose pose=new NativePose(skeleton,source);
        // Head/helmet cannot surround the eyes; arm geometry is replaced by the
        // controller rig and its equipped sleeves, never drawn a second time.
        for(String name:new String[]{"Head","head","Head01","Neck","neck","RightArm","LeftArm","RightShoulder","LeftShoulder","arm_r","arm_l","r_shoulder","l_shoulder"})pose.hide(name);
        parts.add(new Part(model,pose));
    }
}
