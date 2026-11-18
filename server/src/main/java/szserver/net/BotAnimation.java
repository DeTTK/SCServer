package szserver.net;

import szserver.client.PlayerAnimationPackets;

/** Published actor locomotion, without controlling player or FPV physics. */
public final class BotAnimation {
    public static float heading(szserver.world.World.View actor,float previous){return actor.bodyParts().isEmpty()?heading(actor.vx(),actor.vz(),previous):actor.yaw();}
    public static float heading(double vx,double vz,float previous){
        return Math.hypot(vx,vz)>.01?(float)Math.toDegrees(Math.atan2(-vx,vz)):previous;
    }
    public static PlayerAnimationPackets.State state(boolean ladder,double vx,double vy,double vz,boolean grounded,float yaw){
        return state(ladder,false,vx,vy,vz,grounded,yaw);
    }
    public static PlayerAnimationPackets.State state(boolean ladder,boolean water,double vx,double vy,double vz,boolean grounded,float yaw){
        return state(ladder,water,vx,vy,vz,grounded,yaw,0);
    }
    public static PlayerAnimationPackets.State state(boolean ladder,boolean water,double vx,double vy,double vz,boolean grounded,float yaw,int jumps){return state(ladder,water,vx,vy,vz,grounded,yaw,jumps,0);}
    public static PlayerAnimationPackets.State state(boolean ladder,boolean water,double vx,double vy,double vz,boolean grounded,float yaw,int jumps,int posture){
        double speed=ladder?Math.sqrt(vx*vx+vy*vy+vz*vz):Math.hypot(vx,vz);
        var animation=new PlayerAnimation();animation.input(new PlayerAnimationPackets.Motion(0,jumps&255,null,null,null,false));return animation.snapshot(ladder?5:grounded?posture:6,0,speed>.05?water&&!ladder?2:3:0,speed,!ladder&&grounded,yaw,false);
    }
    public static PlayerAnimationPackets.State state(szserver.world.World.View actor,float yaw){
        double speed=actor.ladder()?Math.sqrt(actor.vx()*actor.vx()+actor.vy()*actor.vy()+actor.vz()*actor.vz()):Math.hypot(actor.vx(),actor.vz());
        var animation=new PlayerAnimation();animation.input(new PlayerAnimationPackets.Motion(0,actor.jumps()&255,null,edge(actor.traversal()),transition(actor.traversal()),true));
        animation.aim(actor.aiming());return animation.snapshot(actor.locomotion(),actor.previousLocomotion(),actor.movementMode(),speed,!actor.ladder()&&actor.grounded(),yaw,false).zipline(actor.traversal().cable());
    }
    private static PlayerAnimationPackets.Edge edge(szserver.world.ActorTraversal t){var e=t.edge();return e==null?null:new PlayerAnimationPackets.Edge(e.x1(),e.y1(),e.z1(),e.x2(),e.y2(),e.z2(),e.phase());}
    private static PlayerAnimationPackets.Transition transition(szserver.world.ActorTraversal t){var e=t.transition();return e==null?null:new PlayerAnimationPackets.Transition(PlayerAnimationPackets.controllerCoordinate(e.x()),PlayerAnimationPackets.controllerCoordinate(e.y()),PlayerAnimationPackets.controllerCoordinate(e.z()),e.phase(),PlayerAnimationPackets.controllerCoordinate(e.a()),PlayerAnimationPackets.controllerCoordinate(e.b()),e.p(),e.q(),e.r(),e.active(),e.previous());}
    private BotAnimation(){}
}
