package szserver.world;

/** Locomotion dimensions exported from the installed character state, in blocks. */
public enum ActorPose {
    STAND(0,1.8f), CROUCH(1,1.5f), PRONE(7,.6f);
    public static final double RADIUS=.6f/2d;
    public static final double STEP=.5f;
    private final int nativeId;private final double height;
    ActorPose(int nativeId,float height){this.nativeId=nativeId;this.height=height;}
    public int nativeId(){return nativeId;}
    public double height(){return height;}
    /** Primary segment only. PRONE route candidates require the complete native body check. */
    public SceneCollision.Box body(double x,double y,double z){return new SceneCollision.Box(x-RADIUS,y,z-RADIUS,x+RADIUS,y+height,z+RADIUS);}
}
