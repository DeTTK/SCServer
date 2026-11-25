package vr;
import static vr.Math3.*;

/** Fixed skeleton basis conversion; neither IK nor weapon support moves the palms. */
final class ControllerHands {
    // OpenXR grip -Z runs from little finger to thumb. Native FP wrist frame
    // is authored with thumb +Y and firearm -Z; convert axes rather than raw q.
    static final Pose TO_WRIST=new Pose(V.ZERO,Q.axis(new V(1,0,0),-Math.PI/2));
    static Pose wrist(Tracking.Hand hand){return hand.grip().compose(TO_WRIST);}
}
