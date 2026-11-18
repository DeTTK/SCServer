package scvehicles;
import java.util.*;
import scvehicles.protocol.*;
import static scvehicles.VehicleSeatPose.*;

/** Direction retargeting preserves target bone lengths, helpers, clothes and avatar scale. */
final class VehicleMotionPose {
    static float[] render(VehicleSeatPose rig,float[] nativePose,double heading,VehicleFleet.Type type,VehicleActionsClient.Sample motion){
        float[] pose=apply(rig,nativePose,heading,type,motion.clip(),motion.phase(),motion.mirror());
        if(motion.action()==VehicleAction.ENTER&&!motion.clip().equals("seated"))pose=VehicleRider.transform(pose,VehiclePose.quaternion(VehicleEntryMotion.heading(type.id,motion.phase())*((type.seat[0]>=0)==(VehicleActionProfiles.SEAT[type.id][0]>=0)?1:-1),0,0));
        if(motion.progress()<1){
            String idle=null;double weight=0,idlePhase=0;
            double blendIn=motion.action()==VehicleAction.EXIT?.24:.16;
            if(motion.progress()<blendIn&&motion.action()!=VehicleAction.ENTER){idle=motion.fromClip()!=null?motion.fromClip():motion.posture()==1?"lean_idle":"seated";idlePhase=motion.fromPhase();weight=1-motion.progress()/blendIn;}
            else if(motion.progress()>(motion.action()==VehicleAction.ENTER?.88:.80)&&motion.action()!=VehicleAction.EXIT){int target=VehicleAction.posture(motion.action());idle=target==1?"lean_idle":"seated";weight=motion.action()==VehicleAction.ENTER?(motion.progress()-.88)/.12:(motion.progress()-.80)/.20;}
            if(idle!=null){boolean mirror=idle.startsWith("lean_")?type.seat[0]>=0:type.seat[0]<0;float[] hold=apply(rig,nativePose,heading,type,idle,idlePhase,mirror);fit(rig,hold,type,new VehicleActionsClient.Sample(idle,idlePhase,motion.anchor(),1,0,idle.equals("lean_idle")?1:0,mirror,idle.equals("lean_idle"),0));if(motion.action()==VehicleAction.EXIT)rig.blendLocal(pose,hold,weight*weight*(3-2*weight),apply(rig,nativePose,heading,type,motion.clip(),0,motion.mirror()));else rig.blend(pose,hold,weight*weight*(3-2*weight));}
        }
        if(motion.action()==VehicleAction.EXIT){
            double weight=VehicleInteractionPoints.smooth((motion.progress()-.65)/.35);float[] stand=VehicleBindPose.reference(rig,nativePose);if(weight>0)rig.blendLocal(pose,stand,weight,apply(rig,nativePose,heading,type,motion.clip(),1,motion.mirror()));
            // .82 wire pelvis -> .76 native pelvis, retaining 2 cm sole clearance.
            for(int at=1;at<pose.length;at+=7)pose[at]-=.04f*weight;
        }
        fit(rig,pose,type,motion);
        return pose;
    }
    private static final String[][] CHAINS={
        {"pelvic_0","spine","j_mainroot","j_spine4"},{"spine","breast","j_spine4","j_neck"},{"breast","neck","j_neck","j_head"},
        {"l_shoulder","l_arm","j_clavicle_le","j_shoulder_le"},{"l_arm","l_forearm","j_shoulder_le","j_elbow_le"},{"l_forearm","left_arm_scaler","j_elbow_le","j_wrist_le"},
        {"r_shoulder","r_arm","j_clavicle_ri","j_shoulder_ri"},{"r_arm","r_forearm","j_shoulder_ri","j_elbow_ri"},{"r_forearm","r_arm_scaler","j_elbow_ri","j_wrist_ri"},
        {"l_leg","l_calf","j_hip_le","j_knee_le"},{"l_calf","l_foot","j_knee_le","j_ankle_le"},{"l_foot","l_toe","j_ankle_le","j_ball_le"},
        {"r_leg","r_calf","j_hip_ri","j_knee_ri"},{"r_calf","r_foot","j_knee_ri","j_ankle_ri"},{"r_foot","r_toe","j_ankle_ri","j_ball_ri"}
    };
    static float[] apply(VehicleSeatPose rig,float[] nativePose,double heading,VehicleFleet.Type type,String clip,double phase,boolean mirror){
        if(!VehicleBindPose.supported(rig))return rig.seated(nativePose,heading,type);
        String sourceClip=clip.equals("seated")&&VehicleActionProfiles.FAMILY[type.id].equals("truck")?"truck_seated":clip;
        float[] pose=VehicleBindPose.reference(rig,nativePose),source=VehicleMotionClips.get(sourceClip).sample(phase);Integer hip=rig.indices.get("pelvic"),spine=rig.indices.get("spine");
        if(hip==null||spine==null)return pose;
        orient(rig,pose,hip,source,"rt_pelvis","j_mainroot",mirror);
        for(String[] chain:CHAINS){Integer a=rig.indices.get(chain[0]),b=rig.indices.get(chain[1]);if(a==null||b==null)continue;
            String frame=switch(chain[0]){case "l_leg"->"rt_l_thigh";case "r_leg"->"rt_r_thigh";case "l_calf"->"rt_l_calf";case "r_calf"->"rt_r_calf";case "l_foot"->"rt_l_foot";case "r_foot"->"rt_r_foot";default->null;};
            if(frame!=null){if(mirror)frame=frame.startsWith("rt_l_")?frame.replace("rt_l_","rt_r_"):frame.replace("rt_r_","rt_l_");orient(rig,pose,a,source,frame,chain[2],mirror);}
            double[] desired=direction(source,chain[2],chain[3],mirror);if(length(desired)>1e-6)rig.rotate(pose,a,between(sub(point(pose,b),point(pose,a)),desired));}
        Integer neck=rig.indices.get("neck");if(neck!=null)orient(rig,pose,neck,source,"rt_head","j_head",mirror);
        return pose;
    }
    private static void orient(VehicleSeatPose rig,float[] pose,int bone,float[] source,String prefix,String origin,boolean mirror){
        if(!VehicleMotionClips.JOINTS.containsKey(prefix+"_x"))return; // legacy clip resource
        double[] x=direction(source,origin,prefix+"_x",mirror),y=direction(source,origin,prefix+"_y",mirror);
        // Reflection is S R S: reflecting the X basis requires a second sign.
        if(mirror)for(int i=0;i<3;i++)x[i]=-x[i];
        double[] z=cross(x,y);if(length(z)<1e-6)return;normalize(z);y=cross(z,x);normalize(y);
        double[] delta=frame(x,y,z),desired=VehicleRider.multiply(delta,VehiclePose.quaternion(Math.PI,0,0));int at=bone*7;
        rig.rotate(pose,bone,VehicleRider.multiply(desired,new double[]{pose[at+6],-pose[at+3],-pose[at+4],-pose[at+5]}));
    }
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static double[] frame(double[] x,double[] y,double[] z){
        double w,a,b,c,t=x[0]+y[1]+z[2];
        if(t>0){double s=Math.sqrt(t+1)*2;w=s/4;a=(y[2]-z[1])/s;b=(z[0]-x[2])/s;c=(x[1]-y[0])/s;}
        else if(x[0]>y[1]&&x[0]>z[2]){double s=Math.sqrt(1+x[0]-y[1]-z[2])*2;w=(y[2]-z[1])/s;a=s/4;b=(y[0]+x[1])/s;c=(z[0]+x[2])/s;}
        else if(y[1]>z[2]){double s=Math.sqrt(1+y[1]-x[0]-z[2])*2;w=(z[0]-x[2])/s;a=(y[0]+x[1])/s;b=s/4;c=(z[1]+y[2])/s;}
        else{double s=Math.sqrt(1+z[2]-x[0]-y[1])*2;w=(x[1]-y[0])/s;a=(z[0]+x[2])/s;b=(z[1]+y[2])/s;c=s/4;}
        return new double[]{w,a,b,c};
    }
    static void fit(VehicleSeatPose rig,float[] pose,VehicleFleet.Type type,VehicleActionsClient.Sample motion){
        double p=motion.progress();int action=motion.action();
        if(action==VehicleAction.EXIT){
            // Release the door before the root leaves the cabin. Preserve the
            // authored elbow plane and never stretch an arm back to the car.
            String side=type.seat[0]>=0?"l":"r";Integer a=rig.indices.get(side+"_arm"),b=rig.indices.get(side+"_forearm"),c=rig.indices.get(side.equals("l")?"left_arm_scaler":"r_arm_scaler");
            double weight=VehicleInteractionPoints.contact(p,.03,.08,.16,.25);
            if(a!=null&&b!=null&&c!=null&&weight>0){
                double[] target=VehicleInteractionPoints.relative(VehicleInteractionPoints.push(type,motion.door()),motion.anchor());
                double reach=length(sub(point(pose,b),point(pose,a)))+length(sub(point(pose,c),point(pose,b))),distance=length(sub(target,point(pose,a))),error=length(sub(target,point(pose,c)));
                weight*=VehicleInteractionPoints.smooth((reach*.97-distance)/.12)*VehicleInteractionPoints.smooth((.28-error)/.12)*VehicleInteractionPoints.smooth((distance-.12)/.1);
                if(weight>0)rig.fit(pose,a,b,c,target,point(pose,b),weight);
            }
            // The source step and local stand blend own both legs. Forcing both
            // ankles onto the release point while the pelvis turns folds them.
            return;
        }
        // The authored root/legs are intact. Only the contacting arm adapts to
        // a different handle height using length-preserving joint rotations.
        if(action==VehicleAction.ENTER&&!motion.clip().equals("seated")){
            boolean truck=VehicleActionProfiles.FAMILY[type.id].equals("truck");double opening=1-VehicleInteractionPoints.smooth((p-(truck?.13:.17))/(truck?.09:.08));
            double closing=truck?VehicleInteractionPoints.contact(p,.64,.67,.79,.85):VehicleInteractionPoints.contact(p,.34,.38,.50,.56);
            String side=type.seat[0]>=0?"l":"r";
            hand(rig,pose,side,VehicleInteractionPoints.relative(VehicleInteractionPoints.handle(type,motion.door()),motion.anchor()),opening);
            hand(rig,pose,side,VehicleInteractionPoints.relative(VehicleInteractionPoints.push(type,motion.door()),motion.anchor()),closing);
            return;
        }
        double seated=motion.clip().equals("seated")?1:0;
        // Exit already crossfades from the fitted seated pose. Applying the
        // recline there again doubles it at the first frame and creates a jerk.
        if(motion.clip().equals("seated")&&type.seatRecline!=0){Integer hip=rig.indices.get("pelvic");if(hip!=null)rig.rotate(pose,hip,VehiclePose.quaternion(0,-Math.toRadians(type.seatRecline),0));}
        if(seated>0)rig.contacts(pose,type,seated*seated*(3-2*seated));
        double windowWeight=motion.clip().equals("lean_idle")?1:motion.clip().equals("lean_in")?1-VehicleInteractionPoints.smooth(p):VehicleInteractionPoints.smooth(p);
        if(motion.clip().startsWith("lean_"))for(String side:new String[]{"l","r"}){
            Integer a=rig.indices.get(side+"_leg"),b=rig.indices.get(side+"_calf"),c=rig.indices.get(side+"_foot");
            if(a!=null&&b!=null&&c!=null){double[] foot=VehicleInteractionPoints.relative(VehicleInteractionPoints.floor(type,side),motion.anchor());foot[1]+=VehicleInteractionPoints.BOOT_ANKLE_HEIGHT;rig.fit(pose,a,b,c,foot,new double[]{foot[0],foot[1]+.4,foot[2]+.5},windowWeight);}
        }
        boolean roof=motion.clip().equals("roof_idle"),climbing=(action==VehicleAction.ROOF_UP||action==VehicleAction.ROOF_DOWN)&&p<1;
        if(roof)for(String side:new String[]{"l","r"}){
            Integer a=rig.indices.get(side+"_leg"),b=rig.indices.get(side+"_calf"),c=rig.indices.get(side+"_foot");
            if(a!=null&&b!=null&&c!=null){double y=VehicleActionProfiles.ROOF_POINT[type.id][1]-VehicleActionProfiles.ROOF_PELVIS_HEIGHT-motion.anchor()[1]+.035;rig.ik(pose,a,b,c,new double[]{side.equals("l")?.12:-.12,y,.15},new double[]{side.equals("l")?.2:-.2,0,1});}
        }
        if(climbing){
            double w=VehicleInteractionPoints.contact(p,.1,.22,.65,.82);double[] point=VehicleInteractionPoints.hinge(type);point[1]=VehicleActionProfiles.ROOF_POINT[type.id][1]-VehicleActionProfiles.ROOF_PELVIS_HEIGHT;
            hand(rig,pose,type.seat[0]>=0?"l":"r",VehicleInteractionPoints.relative(point,motion.anchor()),w);
        }
    }
    private static void hand(VehicleSeatPose rig,float[] pose,String side,double[] target,double weight){
        Integer a=rig.indices.get(side+"_arm"),b=rig.indices.get(side+"_forearm"),c=rig.indices.get(side.equals("l")?"left_arm_scaler":"r_arm_scaler");
        if(a!=null&&b!=null&&c!=null&&weight>0)rig.fit(pose,a,b,c,target,new double[]{side.equals("l")?1:-1,-.2,.25},weight);
    }
    private static void foot(VehicleSeatPose rig,float[] pose,String side,double[] target,double weight){
        Integer a=rig.indices.get(side+"_leg"),b=rig.indices.get(side+"_calf"),c=rig.indices.get(side+"_foot");
        // Contacts specify the sole, while the native ankle is 10.9 cm above it.
        target[1]+=.109;
        if(a!=null&&b!=null&&c!=null&&weight>0)rig.fit(pose,a,b,c,target,new double[]{target[0],target[1]+.35,target[2]+.5},weight);
    }
    private static double[] direction(float[] source,String a,String b,boolean mirror){
        if(mirror){a=swap(a);b=swap(b);}double[] d=sub(VehicleMotionClips.point(source,b),VehicleMotionClips.point(source,a));if(mirror)d[0]=-d[0];normalize(d);return d;
    }
    private static String swap(String name){return name.endsWith("_le")?name.substring(0,name.length()-3)+"_ri":name.endsWith("_ri")?name.substring(0,name.length()-3)+"_le":name;}
    private VehicleMotionPose(){}
}
