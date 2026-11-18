package scvehicles;

import java.util.*;

/** Two-bone seating IK on a private render pose. Bone lengths and avatar scale stay native. */
final class VehicleSeatPose {
    final String[] names;final int[] parents;final Map<String,Integer> indices=new HashMap<>();
    VehicleSeatPose(String[] names,int[] parents){this.names=names;this.parents=parents;for(int i=0;i<names.length;i++)indices.put(names[i],i);}
    float[] seated(float[] nativePose,double heading,VehicleFleet.Type type){
        float[] pose=VehicleRider.transform(nativePose,VehiclePose.quaternion(Math.toRadians(heading),0,0));
        Integer pelvis=indices.get("pelvic");if(pelvis==null)return pose;
        double[] origin=point(pose,pelvis);for(int i=0;i<pose.length;i+=7)for(int a=0;a<3;a++)pose[i+a]-=origin[a];
        // The PDA clip can leave its unmapped head end in locomotion/world space.
        // Repair that single invalid end joint in this copy; never change the rig.
        Integer head=indices.get("head_nub"),neck=indices.get("neck");
        if(head!=null&&neck!=null&&length(sub(point(pose,head),point(pose,neck)))>.45){
            double[] n=point(pose,neck);for(int a=0;a<3;a++)pose[head*7+a]=(float)(n[a]+(a==1?.19:0));
            System.arraycopy(pose,neck*7+3,pose,head*7+3,4);
        }
        for(String side:new String[]{"l","r"}){
            Integer upper=indices.get(side+"_leg"),lower=indices.get(side+"_calf"),end=indices.get(side+"_foot");
            if(upper!=null&&lower!=null&&end!=null){double[] foot=VehicleInteractionPoints.relative(VehicleInteractionPoints.floor(type,side),type.seat);foot[1]+=VehicleInteractionPoints.BOOT_ANKLE_HEIGHT;ik(pose,upper,lower,end,foot,new double[]{foot[0],.05,1});}
            upper=indices.get(side+"_arm");lower=indices.get(side+"_forearm");end=indices.get(side.equals("l")?"left_arm_scaler":"r_arm_scaler");
            if(upper!=null&&lower!=null&&end!=null){
                double[] wheel=type.seatIndex>0?new double[]{type.seat[0],type.seat[1]-.05,type.seat[2]+.25}:type.steering!=null?type.steering:new double[]{type.seat[0],type.seat[1]+.37,type.seat[2]+.52};
                double x=wheel[0]-type.seat[0]+(side.equals("l")?.13:-.13);
                ik(pose,upper,lower,end,new double[]{x,wheel[1]-type.seat[1]-.025,wheel[2]-type.seat[2]-.035},new double[]{side.equals("l")?1:-1,-.4,.15});
            }
        }
        return pose;
    }
    void ik(float[] pose,int upper,int lower,int end,double[] target,double[] pole){
        double[] a=point(pose,upper),b=point(pose,lower),c=point(pose,end);
        double l1=length(sub(b,a)),l2=length(sub(c,b));if(l1<1e-5||l2<1e-5)return;
        double[] direction=sub(target,a);double distance=Math.max(Math.abs(l1-l2)+1e-5,Math.min(length(direction),l1+l2-1e-5));normalize(direction);
        double[] bend=sub(pole,a);double dot=dot(bend,direction);for(int k=0;k<3;k++)bend[k]-=dot*direction[k];if(length(bend)<1e-5)return;normalize(bend);
        double along=(l1*l1-l2*l2+distance*distance)/(2*distance),height=Math.sqrt(Math.max(0,l1*l1-along*along));
        double[] elbow=new double[3],hand=new double[3];for(int k=0;k<3;k++){elbow[k]=a[k]+direction[k]*along+bend[k]*height;hand[k]=a[k]+direction[k]*distance;}
        rotate(pose,upper,between(sub(b,a),sub(elbow,a)));
        b=point(pose,lower);c=point(pose,end);rotate(pose,lower,between(sub(c,b),sub(hand,b)));
    }
    void contacts(float[] pose,VehicleFleet.Type type,double weight){
        for(String side:new String[]{"l","r"}){
            Integer a=indices.get(side+"_leg"),b=indices.get(side+"_calf"),c=indices.get(side+"_foot");
            if(a!=null&&b!=null&&c!=null){double[] foot=VehicleInteractionPoints.relative(VehicleInteractionPoints.floor(type,side),type.seat);foot[1]+=VehicleInteractionPoints.BOOT_ANKLE_HEIGHT;fit(pose,a,b,c,foot,new double[]{foot[0],.05,1},weight);}
            a=indices.get(side+"_arm");b=indices.get(side+"_forearm");c=indices.get(side.equals("l")?"left_arm_scaler":"r_arm_scaler");
            if(a!=null&&b!=null&&c!=null){double[] wheel=type.seatIndex>0?new double[]{type.seat[0],type.seat[1]-.05,type.seat[2]+.25}:type.steering!=null?type.steering:new double[]{type.seat[0],type.seat[1]+.37,type.seat[2]+.52};fit(pose,a,b,c,new double[]{wheel[0]-type.seat[0]+(side.equals("l")?.13:-.13),wheel[1]-type.seat[1]-.025,wheel[2]-type.seat[2]-.035},new double[]{side.equals("l")?1:-1,-.4,.15},weight);}
        }
    }
    void fit(float[] pose,int upper,int lower,int end,double[] target,double[] pole,double weight){
        weight=Math.max(0,Math.min(1,weight));if(weight==0)return;
        if(weight==1){ik(pose,upper,lower,end,target,pole);return;}
        // Blending only the endpoint still applies the new elbow/knee pole in
        // full, even at zero weight. Blend the solved limb rotations instead.
        float[] original=pose.clone(),solved=pose.clone();ik(solved,upper,lower,end,target,pole);
        for(int bone:new int[]{upper,lower}){
            int at=bone*7;double[] a={original[at+6],original[at+3],original[at+4],original[at+5]},b={solved[at+6],solved[at+3],solved[at+4],solved[at+5]},q=new double[4];
            double sign=VehiclePhysics.dot(a,b)<0?-1:1,n=0;for(int i=0;i<4;i++){q[i]=a[i]+(sign*b[i]-a[i])*weight;n+=q[i]*q[i];}n=Math.sqrt(n);for(int i=0;i<4;i++)q[i]/=n;
            rotate(pose,bone,VehicleRider.multiply(q,new double[]{pose[at+6],-pose[at+3],-pose[at+4],-pose[at+5]}));
        }
    }
    void blend(float[] pose,float[] target,double weight){
        float[] original=pose.clone();weight=Math.max(0,Math.min(1,weight));
        for(int i=0;i<parents.length;i++){
            int at=i*7;double[] a={original[at+6],original[at+3],original[at+4],original[at+5]},b={target[at+6],target[at+3],target[at+4],target[at+5]},q=new double[4];
            double sign=a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3]<0?-1:1,n=0;
            for(int k=0;k<4;k++){q[k]=a[k]+(sign*b[k]-a[k])*weight;n+=q[k]*q[k];}n=Math.sqrt(n);for(int k=0;k<4;k++)q[k]/=n;
            double[] current={pose[at+6],-pose[at+3],-pose[at+4],-pose[at+5]};rotate(pose,i,VehicleRider.multiply(q,current));
        }
    }
    /** Blend parent-relative joint rotations so children follow the torso naturally. */
    void blendLocal(float[] pose,float[] target,double weight,float[] reference){
        weight=Math.max(0,Math.min(1,weight));float[] original=pose.clone();
        for(int i=0;i<parents.length;i++){
            double[] a=rotation(original,i),b=rotation(target,i),ref=rotation(reference,i);int parent=parents[i];
            if(parent>=0){a=VehicleRider.multiply(inverse(rotation(original,parent)),a);b=VehicleRider.multiply(inverse(rotation(target,parent)),b);ref=VehicleRider.multiply(inverse(rotation(reference,parent)),ref);}
            // Fix the quaternion hemisphere for the whole transition. Choosing
            // the shortest branch anew each frame flips near 180-degree joints.
            double sa=VehiclePhysics.dot(a,ref)<0?-1:1,sb=VehiclePhysics.dot(b,ref)<0?-1:1,n=0;double[] q=new double[4];
            for(int k=0;k<4;k++){q[k]=sa*a[k]*(1-weight)+sb*b[k]*weight;n+=q[k]*q[k];}n=Math.sqrt(n);for(int k=0;k<4;k++)q[k]/=n;
            if(parent>=0)q=VehicleRider.multiply(rotation(pose,parent),q);
            rotate(pose,i,VehicleRider.multiply(q,inverse(rotation(pose,i))));
        }
    }
    private static double[] rotation(float[] pose,int bone){int at=bone*7;return new double[]{pose[at+6],pose[at+3],pose[at+4],pose[at+5]};}
    private static double[] inverse(double[] q){return new double[]{q[0],-q[1],-q[2],-q[3]};}
    void rotate(float[] pose,int root,double[] q){
        double[] origin=point(pose,root);
        for(int i=0;i<parents.length;i++)if(descendant(i,root)){
            int at=i*7;double[] p=VehiclePose.rotate(q,pose[at]-origin[0],pose[at+1]-origin[1],pose[at+2]-origin[2]);
            for(int k=0;k<3;k++)pose[at+k]=(float)(origin[k]+p[k]);
            double[] r=VehicleRider.multiply(q,new double[]{pose[at+6],pose[at+3],pose[at+4],pose[at+5]});
            for(int k=0;k<3;k++)pose[at+3+k]=(float)r[k+1];pose[at+6]=(float)r[0];
        }
    }
    private boolean descendant(int bone,int root){for(int i=bone;i>=0;i=parents[i])if(i==root)return true;return false;}
    static double[] between(double[] a,double[] b){
        normalize(a);normalize(b);double d=dot(a,b);double[] q;
        if(d<-.99999){double[] axis=Math.abs(a[0])<.8?new double[]{0,a[2],-a[1]}:new double[]{-a[2],0,a[0]};normalize(axis);return new double[]{0,axis[0],axis[1],axis[2]};}
        q=new double[]{1+d,a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};double n=Math.sqrt(Arrays.stream(q).map(v->v*v).sum());for(int i=0;i<4;i++)q[i]/=n;return q;
    }
    static double[] point(float[] pose,int index){return new double[]{pose[index*7],pose[index*7+1],pose[index*7+2]};}
    static double[] sub(double[] a,double[] b){return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    static double length(double[] a){return Math.sqrt(dot(a,a));}
    static void normalize(double[] a){double n=length(a);if(n>1e-12)for(int k=0;k<3;k++)a[k]/=n;}
}
