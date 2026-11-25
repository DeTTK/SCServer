package vr;
import static vr.Math3.*;

/** Client-owned rigid props: bounded fixed steps, swept contacts, bounce and sleep. */
final class DebrisPhysics {
    interface Collision {boolean clear(V from,V to)throws Exception;}
    Pose pose;V velocity,spin;final double radius;boolean sleeping;int contacts,quiet;
    DebrisPhysics(Pose pose,V velocity,V spin,double radius){this.pose=pose;this.velocity=velocity;this.spin=spin;this.radius=radius;}
    void step(double seconds,Collision collision)throws Exception {
        if(sleeping)return;
        int steps=Math.max(1,(int)Math.ceil(Math.min(.10,Math.max(0,seconds))*60));double dt=Math.min(.10,Math.max(0,seconds))/steps;
        for(int s=0;s<steps;s++){
            velocity=velocity.add(new V(0,-9.81*dt,0));V p=pose.p();boolean floor=false;
            for(int axis=0;axis<3;axis++){
                double speed=axis==0?velocity.x():axis==1?velocity.y():velocity.z();
                V direction=axis==0?new V(1,0,0):axis==1?new V(0,1,0):new V(0,0,1),shift=direction.mul(speed*dt),edge=direction.mul(Math.copySign(radius,speed));
                if(shift.length()<1e-7)continue;
                if(collision.clear(p.add(edge),p.add(shift).add(edge))){p=p.add(shift);continue;}
                double lo=0,hi=1;for(int i=0;i<5;i++){double mid=(lo+hi)*.5;if(collision.clear(p.add(edge),p.add(shift.mul(mid)).add(edge)))lo=mid;else hi=mid;}
                p=p.add(shift.mul(Math.max(0,lo-.02)));contacts++;
                if(axis==0)velocity=new V(-speed*.22,velocity.y(),velocity.z());
                if(axis==1){velocity=new V(velocity.x()*.65,-speed*.22,velocity.z()*.65);floor=speed<0;}
                if(axis==2)velocity=new V(velocity.x(),velocity.y(),-speed*.22);
                spin=spin.mul(.6);
            }
            Q rotation=spin.length()>1e-5?Q.axis(spin,spin.length()*dt).mul(pose.q()):pose.q();pose=new Pose(p,rotation.unit());
            if(floor&&velocity.length()<.15){if(++quiet>=4){sleeping=true;velocity=spin=V.ZERO;}}else quiet=0;
        }
    }
}
