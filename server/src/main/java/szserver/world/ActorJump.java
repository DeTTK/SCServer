package szserver.world;

/** Per-actor native jump admission and energy; neither player nor FPV physics. */
public interface ActorJump {
    record State(double time,double lastJump,boolean armed,float stamina,int jumps,int repetitions,double lastPoseJump,int mode){
        public static State initial(){return new State(0,0,false,1,0,0,0,0);}
    }
    record Result(State state,double velocityY,boolean grounded,boolean accepted){}
    Result jumpStep(State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt);
    default Result jumpStep(State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt,ActorPose pose){return jumpStep(old,ground,ladder,held,moving,water,velocityY,feetY,dt);}
    default Result jumpStep(State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt,ActorPose pose,int requestedMode){return jumpStep(old,ground,ladder,held,moving,water,velocityY,feetY,dt,pose);}
}
