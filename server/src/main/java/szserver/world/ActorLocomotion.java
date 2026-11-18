package szserver.world;

/** Inputs to the installed character controller, separate from AI route choice. */
public final class ActorLocomotion {
    public enum Gait { NONE, SLOW, WADE, WALK, JOG, SPRINT }
    public record Profile(float baseSpeed,float sprintBonus,float speedMultiplier,float statusMultiplier,float conditionFactor,boolean encumbered) {
        public static final Profile DEFAULT=new Profile(1,0,1,1,1,false);
        public Profile {
            for(float value:new float[]{baseSpeed,speedMultiplier,statusMultiplier,conditionFactor})
                if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("actor_movement_profile");
            if(!Float.isFinite(sprintBonus)||Math.abs(sprintBonus)>1000)throw new IllegalArgumentException("actor_sprint_bonus");
        }
    }
    public record Control(int mode,int posture,int previousPosture,double time,double poseSince,Profile profile) {
        public Control {
            if(mode<0||mode>=Gait.values().length||posture<0||previousPosture<0||!Double.isFinite(time)||!Double.isFinite(poseSince)||profile==null)
                throw new IllegalArgumentException("actor_movement_control");
        }
    }
    private ActorLocomotion(){}
}
