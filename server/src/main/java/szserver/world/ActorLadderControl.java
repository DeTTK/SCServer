package szserver.world;

/** Navigation intent only. Admission, speed, trajectory and exits stay in the game controller. */
public final class ActorLadderControl {
    public record Intent(float yaw,float heading,boolean moving,boolean jump,boolean crouch){}
    public static Intent toward(World.Vec p,World.Vec target,ActorLadderGuide.Contact source,ActorLadderGuide.Contact destination,Float attachedYaw,boolean armed,boolean grounded){
        double dx=target.x()-p.x(),dy=target.y()-p.y(),dz=target.z()-p.z(),distance=Math.hypot(dx,dz);
        if(attachedYaw==null){if(source==null||Math.abs(dy)<.12||distance>1.3)return null;return new Intent(source.yaw(),0,true,armed&&grounded,false);}
        float yaw=attachedYaw;double angle=Math.toRadians(yaw),forward=-Math.sin(angle)*dx+Math.cos(angle)*dz,side=Math.cos(angle)*dx+Math.sin(angle)*dz;
        if(destination==null&&distance>.25){
            boolean ledge=forward>.15&&dy>=-.12;
            return new Intent(yaw,0,true,ledge&&armed,!ledge);
        }
        float heading=(float)-Math.toDegrees(Math.atan2(side,dy));
        return new Intent(yaw,heading,Math.abs(dy)>.12||Math.abs(side)>.15,false,false);
    }
    private ActorLadderControl(){}
}
