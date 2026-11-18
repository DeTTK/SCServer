package cinema;

/** A world-space shot, independent of actor bones, movement and look. */
final class StationaryCamera {
    Pose pose;double fov=70;
    void place(Pose pose,double fov){
        if(pose==null||!pose.p().finite()||!Double.isFinite(fov)||fov<20||fov>120)throw new IllegalArgumentException("Invalid stationary camera");
        this.pose=new Pose(pose.p(),pose.q().unit());this.fov=fov;
    }
    void clear(){pose=null;}
    static boolean pilotPass(boolean enabled,boolean editing,boolean mainReady,long frame){return enabled&&!editing&&mainReady&&(frame&1)==1;}
}
