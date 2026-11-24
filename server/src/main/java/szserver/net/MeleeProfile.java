package szserver.net;

import java.util.Map;

/** Resource rules for a native common/strong/fast attack. Units: seconds, blocks, HP. */
public record MeleeProfile(double delay,double cooldown,double reach,double angle,float damage,float piercing) {
    public float damage(Map<String,Float> attacker,Map<String,Float> victim){
        double modifier=attacker.getOrDefault("melee_attack_damage_modifier",0f)/100d,multiplier=modifier>0?1+modifier:Math.pow(.5,-modifier);
        double resistance=Math.max(-95,victim.getOrDefault("tear_dmg_factor",0f))*(1-piercing/100d),received=szserver.inventory.CombatModifiers.received(victim);
        return (float)(damage*multiplier*100/(100+Math.max(-95,resistance))*received);
    }
}
