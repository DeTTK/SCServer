package vr;
import static vr.Math3.*;

/** Down/up wrist flicks work with either one or two hands; tracking gaps never latch. */
final class BreakActionGesture {
    enum Gesture { NONE, OPEN, CLOSE, EMPTY }
    private Pose previous;
    private V start;
    private long time,cooldown,shakeStarted;
    private int shakeSign,reversals;
    void reset(){previous=null;start=null;time=cooldown=shakeStarted=0;shakeSign=reversals=0;}
    Gesture update(long now,Pose hand,boolean opened,boolean eligible){
        Pose old=previous;long before=time;previous=hand;time=now;
        double dt=(now-before)*1e-9;
        if(!eligible||old==null||dt<.004||dt>.12){start=hand.p();shakeSign=reversals=0;return Gesture.NONE;}
        V speed=hand.p().sub(old.p()).mul(1/dt);
        if(speed.length()>6){start=hand.p();shakeSign=reversals=0;return Gesture.NONE;}
        if(now<cooldown)return Gesture.NONE;
        Q turn=old.q().inverse().mul(hand.q());
        double pitch=2*Math.atan2(turn.x(),Math.abs(turn.w()))/dt;
        double dy=hand.p().y()-start.y();
        boolean open=pitch< -3.2||speed.y()<-.95&&dy<-.055;
        boolean close=pitch>3.2||hand.q().rotate(new V(0,0,1)).y()>-.65&&speed.y()>.95&&dy>.055;
        if(!opened&&open||opened&&close){cooldown=now+350_000_000L;start=hand.p();shakeSign=reversals=0;return opened?Gesture.CLOSE:Gesture.OPEN;}
        if(Math.abs(speed.y())<.25)start=hand.p();
        V rear=hand.q().rotate(new V(0,0,1));
        if(!opened||rear.y()>-.65){shakeSign=reversals=0;return Gesture.NONE;}
        double axial=speed.dot(rear);int sign=axial>.45?1:axial<-.45?-1:0;
        if(sign!=0){if(shakeSign==0||now-shakeStarted>650_000_000L){shakeStarted=now;reversals=0;}else if(sign!=shakeSign)reversals++;shakeSign=sign;
            if(reversals>=2){cooldown=now+500_000_000L;shakeSign=reversals=0;return Gesture.EMPTY;}}
        return Gesture.NONE;
    }
}
