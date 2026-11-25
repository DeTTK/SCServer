package vr;
import static vr.Math3.*;

/** Controller gestures in torso/world space, independent of the shooting eye. */
final class RevolverGesture {
    enum Gesture { NONE, LEFT, RIGHT, EMPTY }
    private Pose previous;
    private long time,cooldown,shakeStarted;
    private V strokeStart;
    private int shakeSign,reversals;
    void reset(){previous=null;time=cooldown=shakeStarted=0;strokeStart=null;shakeSign=reversals=0;}
    Gesture update(long now,Pose weapon,Pose head,boolean opened,boolean eligible){
        Pose old=previous;long before=time;previous=weapon;time=now;
        double dt=(now-before)*1e-9;
        if(!eligible||old==null||dt<.004||dt>.12){strokeStart=weapon.p();shakeSign=reversals=0;return Gesture.NONE;}
        V velocity=weapon.p().sub(old.p()).mul(1/dt);
        if(velocity.length()>6){strokeStart=weapon.p();shakeSign=reversals=0;return Gesture.NONE;}
        if(now<cooldown)return Gesture.NONE;
        V right=head.q().rotate(new V(1,0,0));right=new V(right.x(),0,right.z()).unit();
        double sideways=velocity.dot(right);
        if(strokeStart==null)strokeStart=old.p();
        double displacement=weapon.p().sub(strokeStart).dot(right);
        Q turn=weapon.q().mul(old.q().inverse());double yawSpeed=2*Math.atan2(turn.y(),Math.abs(turn.w()))/dt;
        boolean left=sideways<-.85&&displacement<-.055||yawSpeed>3.8;
        boolean close=sideways>.85&&displacement>.055||yawSpeed< -3.8;
        if(!opened&&left||opened&&close){cooldown=now+400_000_000L;strokeStart=weapon.p();shakeSign=reversals=0;return opened?Gesture.RIGHT:Gesture.LEFT;}
        if(Math.abs(sideways)<.25)strokeStart=weapon.p();
        // +Z is the rear face of the chambers, through which rounds fall out.
        V rear=weapon.q().rotate(new V(0,0,1));
        if(!opened||rear.y()>-.65){shakeSign=reversals=0;return Gesture.NONE;}
        double axial=velocity.dot(rear);int sign=axial>.45?1:axial<-.45?-1:0;
        if(sign!=0){
            if(shakeSign==0||now-shakeStarted>650_000_000L){shakeStarted=now;reversals=0;}
            else if(sign!=shakeSign)reversals++;
            shakeSign=sign;
            if(reversals>=2){cooldown=now+500_000_000L;shakeSign=reversals=0;return Gesture.EMPTY;}
        }
        return Gesture.NONE;
    }
}
