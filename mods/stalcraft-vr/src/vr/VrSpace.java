package vr;
import static vr.Math3.*;

/** Recenter and artificial locomotion transform; physical tracking stays unsmoothed. */
public final class VrSpace {
    private Pose origin=Pose.ID;
    private double snapLatch;
    private long previous;
    public void recenter(Pose head,Pose worldHead){
        V forward=head.forward();double yaw=Math.atan2(-forward.x(),-forward.z());
        Q rotation=worldHead.q().mul(Q.axis(new V(0,1,0),-yaw));origin=new Pose(worldHead.p().sub(rotation.rotate(head.p())),rotation);
    }
    public Pose world(Pose tracking){return origin.compose(tracking);}
    public V offset(){return origin.p();}
    public void translate(V worldDelta){origin=new Pose(origin.p().add(worldDelta),origin.q());}
    public void turn(Pose trackingHead,double radians){Q rotation=Q.axis(new V(0,1,0),radians);V pivot=world(trackingHead).p();origin=new Pose(pivot.add(rotation.rotate(origin.p().sub(pivot))),rotation.mul(origin.q()));}
    public void locomotion(Tracking t,double speed,double snapDegrees){
        double dt=previous==0?0:Math.max(0,Math.min(.05,(t.time()-previous)*1e-9));previous=t.time();
        if(!t.focused()){snapLatch=0;return;}
        Tracking.Hand left=t.left(),right=t.right();
        double turn=right.valid()?right.stickX():0;
        if(Math.abs(turn)<.25)snapLatch=0;
        if(Math.abs(turn)>.7&&snapLatch==0){snapLatch=Math.signum(turn);Q rotation=Q.axis(new V(0,1,0),-snapLatch*Math.toRadians(snapDegrees));V pivot=world(t.head()).p();origin=new Pose(pivot.add(rotation.rotate(origin.p().sub(pivot))),rotation.mul(origin.q()));}
        if(!left.valid())return;
        double x=left.stickX(),y=left.stickY(),m=Math.sqrt(x*x+y*y);if(m<.15)return;
        double scale=Math.min(1,(m-.15)/.85)/m;
        V forward=world(t.head()).forward();forward=new V(forward.x(),0,forward.z()).unit();V rightward=forward.cross(new V(0,1,0));
        // Diagnostic locomotion only. Game adapter must pass this through native collision.
        V delta=rightward.mul(x*scale).add(forward.mul(y*scale)).mul(speed*dt);origin=new Pose(origin.p().add(delta),origin.q());
    }
}
