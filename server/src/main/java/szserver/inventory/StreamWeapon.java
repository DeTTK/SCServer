package szserver.inventory;

import java.util.Map;
import szserver.net.BlastRules;

/** Values extracted from the native streaming-weapon and ammunition factories. */
public record StreamWeapon(String primary,String secondary,float secondaryFactor,float seriesFactor,
                           int seriesLimit,long seriesResetNanos,float npcMultiplier,float hipSpread,float aimedSpread) {
    public float damage(WeaponProfile weapon,double distance,int consecutive,boolean npc,
                        Map<String,Float> attacker,Map<String,Float> target){
        double series=consecutive<2?1:1+seriesFactor*Math.min(consecutive,seriesLimit);
        double raw=weapon.damageAt(distance)*series*(npc?npcMultiplier:1)
                *Math.max(0,1+attacker.getOrDefault("damage_bonus",0f)/100.);
        return (float)(raw*(BlastRules.factor(primary,target)+secondaryFactor*BlastRules.factor(secondary,target))
                *CombatModifiers.received(target));
    }
}
