package vr;
import static vr.Math3.*;

/** Immutable snapshot; both eyes and gameplay consume one prediction timestamp. */
public record Tracking(long time,boolean focused,Pose head,Eye leftEye,Eye rightEye,Hand left,Hand right){
    public record Eye(Pose pose,Fov fov){}
    public record Hand(Pose grip,Pose aim,boolean valid,float trigger,float squeeze,float stickX,float stickY,boolean primary,boolean secondary,boolean stickClick,boolean menu){
        public static final Hand LOST=new Hand(Pose.ID,Pose.ID,false,0,0,0,0,false,false,false,false);
    }
}
