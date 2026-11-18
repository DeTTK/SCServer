package szserver.world;
/** Navigation controls only: gain clearance through the native jump before grabbing a cable. */
public final class ActorZiplineControl {
    public static ActorControllerEngine.Input toward(ActorZipline.Route route,World.Vec feet,boolean attached,boolean grounded,boolean armed){
        boolean hop=!attached&&grounded&&armed;
        double dx=route.grab().x()-feet.x(),dz=route.grab().z()-feet.z();
        // A vertical handler does not teleport the body sideways onto its line.
        // Step/jump off the landing toward the reel before requesting attachment,
        // otherwise native collision immediately drops the rider on that same floor.
        boolean approach=route.vertical()&&!attached&&Math.hypot(dx,dz)>.12;
        float yaw=approach?(float)Math.toDegrees(Math.atan2(-dx,dz)):route.yaw();
        return new ActorControllerEngine.Input(yaw,route.pitch(feet),0,approach?ActorLocomotion.Gait.JOG:ActorLocomotion.Gait.NONE,hop,false,false,attached||hop||approach?null:new ActorControllerEngine.Zipline(route.object(),false));
    }
    private ActorZiplineControl(){}
}
