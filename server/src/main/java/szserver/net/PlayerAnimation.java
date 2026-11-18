package szserver.net;

import szserver.client.PlayerAnimationPackets;

/** Transport-owned visual events. Neither movement physics nor inventory ownership lives here. */
public final class PlayerAnimation {
    private int jump,boosted,reload,swing,strike;
    private float heading,duration;
    private boolean aiming;
    private PlayerAnimationPackets.Pose pose;
    private PlayerAnimationPackets.Edge edge;
    private PlayerAnimationPackets.Transition transition;
    public void input(PlayerAnimationPackets.Motion motion){heading=motion.heading();if(motion.jump()!=null)jump=motion.jump();if(motion.boosted()!=null)boosted=motion.boosted();if(motion.snapshot()){pose=motion.pose();edge=motion.edge();transition=motion.transition();}}
    public boolean aiming(){return aiming;}
    public void aim(boolean enabled){aiming=enabled;}
    public void reload(float seconds){duration=seconds;reload=(reload+1)&255;aiming=false;}
    public void swing(int kind){if(kind==1)strike=(strike+1)&255;else swing=(swing+1)&255;}
    public void cancel(){aiming=false;duration=0;}
    public void reset(){cancel();pose=null;edge=null;transition=null;heading=0;}
    public PlayerAnimationPackets.State snapshot(int locomotion,int previous,int mode,double speed,boolean ground,float yaw,boolean pilot){
        return new PlayerAnimationPackets.State(pilot?11:locomotion,pilot?11:previous,pilot?0:mode,pilot?0:speed,ground,yaw, pilot?0:speed>1e-5||mode!=0?-heading:0,jump,boosted,!pilot&&aiming,reload,duration,swing,strike,pilot?null:edge,pilot?null:transition).pose(pilot?null:pose);
    }
}
