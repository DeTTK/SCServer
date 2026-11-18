package szserver.net;

import com.google.gson.JsonObject;
import java.util.*;

/** Resource rules for a native common/strong/fast attack. Units: seconds, blocks, HP. */
public record MeleeProfile(double delay,double cooldown,double reach,double angle,float damage,float piercing) {
    public static MeleeProfile from(JsonObject item,int kind,int combo){
        if(kind<0||kind>2||combo<0||combo>32)throw new IllegalArgumentException("melee_attack_invalid");
        if(!item.has("type")||!Set.of("melee_weapon","melee").contains(item.get("type").getAsString()))throw new IllegalArgumentException("melee_item_required");
        String name=List.of("common","strong","fast").get(kind);String key="delay_"+name;double delay=number(item,key+"_"+combo,number(item,key,0))/20;
        float damage=0;if(item.has("damage_"+name)){String[] values=item.get("damage_"+name).getAsString().split("\\.\\.");if(values.length>2)throw new IllegalArgumentException("melee_damage_range");for(String value:values)damage+=Float.parseFloat(value);damage/=values.length;}
        if(!Float.isFinite(damage)||damage<0)throw new IllegalArgumentException("melee_damage_invalid");
        return new MeleeProfile(Math.max(0,delay),Math.max(.1,number(item,"cd_"+name,10)/20),Math.max(0,Math.min(10,number(item,"radius_"+name,2.1))),Math.toRadians(Math.max(0,Math.min(180,number(item,"angle_"+name,20)))),damage,(float)Math.max(0,Math.min(100,number(item,"piercing",0))));
    }
    public float damage(Map<String,Float> attacker,Map<String,Float> victim){
        double modifier=attacker.getOrDefault("melee_attack_damage_modifier",0f)/100d,multiplier=modifier>0?1+modifier:Math.pow(.5,-modifier);
        double resistance=Math.max(-95,victim.getOrDefault("tear_dmg_factor",0f))*(1-piercing/100d),received=szserver.inventory.CombatModifiers.received(victim);
        return (float)(damage*multiplier*100/(100+Math.max(-95,resistance))*received);
    }
    private static double number(JsonObject item,String key,double fallback){double value=item.has(key)?item.get(key).getAsDouble():fallback;if(!Double.isFinite(value))throw new IllegalArgumentException("melee_resource_nonfinite");return value;}
}
