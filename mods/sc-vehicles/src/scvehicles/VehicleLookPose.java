package scvehicles;
import static scvehicles.VehicleSeatPose.*;
/** Upper-body aiming leaves the pelvis and vehicle contact points in place. */
final class VehicleLookPose {
    static double weight(VehicleActionsClient.Sample s){if(s==null)return 0;return switch(s.clip()==null?"":s.clip()){case "lean_idle"->1;case "lean_out"->.35+.65*VehicleInteractionPoints.smooth(s.progress());case "lean_in"->.35+.65*(1-VehicleInteractionPoints.smooth(s.progress()));case "seated"->.35;default->0;};}
    static double[] localLook(scvehicles.protocol.VehicleWire car,double yaw,double pitch){double p=Math.toRadians(pitch);double[] q=VehiclePose.quaternion(car.yaw,car.pitch,car.roll);return VehiclePose.rotate(new double[]{q[0],-q[1],-q[2],-q[3]},Math.sin(yaw)*Math.cos(p),-Math.sin(p),Math.cos(yaw)*Math.cos(p));}
    static void apply(VehicleSeatPose rig,float[] pose,scvehicles.protocol.VehicleWire car,VehicleActionsClient.Sample s,double yaw,double pitch){
        double weight=weight(s);if(weight<=0)return;double[] d=localLook(car,yaw,pitch);double localYaw=Math.atan2(d[0],d[2]),localPitch=Math.atan2(-d[1],Math.hypot(d[0],d[2]));
        double freedom=(weight-.35)/.65;double yawLimit=1.35+(Math.PI-1.35)*freedom,pitchLimit=.7+(Math.PI*.5-.7)*freedom;localYaw=Math.max(-yawLimit,Math.min(yawLimit,localYaw));localPitch=Math.max(-pitchLimit,Math.min(pitchLimit,localPitch));
        double[] desired=VehiclePose.quaternion(localYaw+Math.PI,-localPitch,0);
        Integer chest=rig.indices.get("breast");if(chest!=null)orient(rig,pose,chest,desired,weight);
        Integer head=rig.indices.get("neck");if(head!=null)orient(rig,pose,head,desired,weight);
    }
    /** Reuse the native weapon grip/reload pose, aligned at the chest; never guess weapon mesh axes. */
    static void weaponArms(VehicleSeatPose rig,float[] pose,float[] nativePose,VehicleActionsClient.Sample s){
        if(s==null||s.clip()==null||!s.clip().startsWith("lean_"))return;
        Integer chest=rig.indices.get("breast");if(chest==null)return;
        double weight=s.clip().equals("lean_idle")?1:s.clip().equals("lean_out")?VehicleInteractionPoints.smooth(s.progress()):1-VehicleInteractionPoints.smooth(s.progress());
        int at=chest*7;double[] q={pose[at+6],pose[at+3],pose[at+4],pose[at+5]},inverse={nativePose[at+6],-nativePose[at+3],-nativePose[at+4],-nativePose[at+5]};
        float[] target=VehicleRider.transform(nativePose,VehicleRider.multiply(q,inverse));
        for(String side:new String[]{"l","r"}){
            Integer shoulder=rig.indices.get(side+"_shoulder");if(shoulder==null)continue;
            for(int bone=0;bone<rig.names.length;bone++){
                boolean arm=false;for(int parent=bone;parent>=0;parent=rig.parents[parent])if(parent==shoulder){arm=true;break;}
                if(arm){int i=bone*7;orient(rig,pose,bone,new double[]{target[i+6],target[i+3],target[i+4],target[i+5]},weight);}
            }
        }
    }
    private static void orient(VehicleSeatPose rig,float[] pose,int bone,double[] desired,double weight){int at=bone*7;double[] a={pose[at+6],pose[at+3],pose[at+4],pose[at+5]},q=new double[4];double sign=VehiclePhysics.dot(a,desired)<0?-1:1,n=0;for(int i=0;i<4;i++){q[i]=a[i]+(sign*desired[i]-a[i])*weight;n+=q[i]*q[i];}n=Math.sqrt(n);for(int i=0;i<4;i++)q[i]/=n;rig.rotate(pose,bone,VehicleRider.multiply(q,new double[]{a[0],-a[1],-a[2],-a[3]}));}
    private VehicleLookPose(){}
}
