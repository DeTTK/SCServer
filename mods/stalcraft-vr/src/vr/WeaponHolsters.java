package vr;
import static vr.Math3.*;

/** Distinct torso-yaw mounts, independent of headset pitch and active native slot. */
final class WeaponHolsters {
    private boolean down=true;
    static Pose secondary(Pose belt){return belt.compose(new Pose(new V(.43,0,.23),Q.ID));}
    static String zone(Pose belt,V point){
        if(point.sub(Ak15.backHolster(belt).p()).length()<.22)return "WEAPON_PRIMARY";
        if(point.sub(secondary(belt).p()).length()<.22)return "WEAPON_SECONDARY";
        return null;
    }
    String update(Tracking tracking,Pose belt,boolean blocked,boolean held){
        var hand=tracking.right();boolean next=hand.valid()&&hand.squeeze()>.6;
        if(blocked||!tracking.focused()||!hand.valid()){down=true;return null;}
        String slot=!held&&next&&!down?zone(belt,hand.grip().p()):null;
        down=next;return slot;
    }
    void reset(){down=true;}
}
