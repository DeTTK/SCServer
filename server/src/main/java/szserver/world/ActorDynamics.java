package szserver.world;

/** Actor velocity controller; player/FPV control stays on its own client. */
public interface ActorDynamics {
    record State(double vx,double vy,double vz,boolean grounded,double airborneTime,boolean water,boolean viscous,ActorContacts.State contacts){
        public State{java.util.Objects.requireNonNull(contacts);}
        public State(double vx,double vy,double vz,boolean grounded,double airborneTime,boolean water,boolean viscous){this(vx,vy,vz,grounded,airborneTime,water,viscous,ActorContacts.State.EMPTY);}
    }
    record Result(State state,ActorMotion.Result motion){}
    record Contact(ActorMotion.Result motion,double movedX,double movedZ){}
    interface Movement {Contact move(World.Vec velocity);}
    Result advance(State previous,World.Vec steering,double dt,Movement movement);
    default Result advance(State previous,World.Vec steering,double dt,Movement movement,ActorLocomotion.Control control){return advance(previous,steering,dt,movement);}
    default double travelSpeed(ActorPose pose,boolean water){return water?2.5:3;}
    /** Native controller validates its retained ground latch before admitting input/jumps. */
    default boolean grounded(boolean previous,double airborneTime,java.util.function.BooleanSupplier support){return previous;}
    double jumpSpeed();
    boolean water(World.Vec position,BlockShapes.Voxels source);
    default boolean water(World.Vec position,BlockShapes.Voxels source,ActorPose pose){return water(position,source);}
}
