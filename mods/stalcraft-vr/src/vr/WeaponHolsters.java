package vr;
import static vr.Math3.*;

/** Distinct torso-yaw mounts, independent of headset pitch and active native slot. */
final class WeaponHolsters {
    private boolean down=true;
    double shoulderHeight=.65,waistHeight=0,lateralOffset=.43,pouchHeight=0;

    V pouch(Pose belt){return belt.transform(new V(0,pouchHeight,0));}
    String selectedZone(Pose belt,V point){
        double primary=point.sub(belt.transform(new V(lateralOffset,shoulderHeight,.23))).length();
        double secondary=point.sub(belt.transform(new V(lateralOffset,waistHeight,.23))).length();
        if(primary<=secondary&&primary<.22)return "WEAPON_PRIMARY";
        return secondary<.22?"WEAPON_SECONDARY":null;
    }
    String update(Tracking tracking,Pose belt,boolean blocked,boolean held){
        var hand=tracking.right();boolean next=hand.valid()&&hand.squeeze()>.6;
        if(blocked||!tracking.focused()||!hand.valid()){down=true;return null;}
        String slot=!held&&next&&!down?selectedZone(belt,hand.grip().p()):null;
        down=next;return slot;
    }
    void reset(){down=true;}
}
