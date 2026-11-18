package szserver.inventory;
import com.google.gson.*;
import java.util.*;

/** Resource combat parameters. No prototype-wide damage, RPM or magazine constants. */
public record WeaponProfile(int capacity,List<Integer> ammunition,double damage,double distantDamage,double range,double falloffStart,double falloffEnd,double rpm,double reload,double tacticalReload,double headMultiplier) {
    private static double value(JsonObject p,String name,double fallback){return p.has(name)?p.get(name).getAsDouble():fallback;}
    public static WeaponProfile from(JsonObject p){
        if(!p.has("clip_size")||!p.has("bullets_id"))return null;JsonElement bullets=p.get("bullets_id");List<Integer> ammo=new ArrayList<>();if(bullets.isJsonArray())for(JsonElement id:bullets.getAsJsonArray())ammo.add(id.getAsInt());else if(bullets.isJsonPrimitive())ammo.add(bullets.getAsInt());if(ammo.isEmpty())return null;
        int capacity=p.get("clip_size").getAsInt();double damage=value(p,"damage",0),range=value(p,"max_distance",120),rpm=value(p,"rate_of_fire",0),reload=value(p,"reload_time",3);
        if(capacity<1||capacity>1000||damage<=0||damage>=10000||rpm<=0||rpm>6000||range<=0||range>1024)throw new IllegalArgumentException("invalid_weapon_profile");
        return new WeaponProfile(capacity,List.copyOf(ammo),damage,value(p,"damage_distant",damage),range,value(p,"damage_decrease_start",0),value(p,"damage_decrease_end",range),rpm,reload,value(p,"reload_time_tactical",reload),value(p,"head_damage_modifier",1));
    }
    public double damageAt(double distance){double fraction=Math.max(0,Math.min(1,(distance-falloffStart)/Math.max(.001,falloffEnd-falloffStart)));return damage+fraction*(distantDamage-damage);}
    public WeaponProfile withMagazine(JsonObject p){if(!p.has("type")||!p.get("type").getAsString().equals("magazine"))return this;int size=(int)value(p,"clip_size",capacity);if(p.has("additive_clip")&&p.get("additive_clip").getAsBoolean())size+=capacity;if(size<1||size>1000)throw new IllegalArgumentException("invalid_magazine_capacity");return new WeaponProfile(size,ammunition,damage,distantDamage,range,falloffStart,falloffEnd,rpm,value(p,"reload_time",reload),value(p,"reload_time_tactical",tacticalReload),headMultiplier);}
    public double bulletDamage(double distance,boolean head,JsonObject armor,JsonObject bullet){double resistance=Math.max(-95,value(armor,"bullet_dmg_factor",0)),piercing=Math.max(-100,Math.min(100,value(bullet,"piercing",0)));double raw=damageAt(distance)*(head?headMultiplier:1)*Math.max(0,1+value(bullet,"damage",0)/100);return raw*100/(100+Math.max(-95,resistance*(1-piercing/100)));}
    public double bulletDamage(double distance,boolean head,Map<String,Float> targetStats,JsonObject bullet){double resistance=Math.max(-95,targetStats.getOrDefault("bullet_dmg_factor",0f)),piercing=Math.max(-100,Math.min(100,value(bullet,"piercing",0)));double raw=damageAt(distance)*(head?headMultiplier:1)*Math.max(0,1+value(bullet,"damage",0)/100);return raw*100/(100+Math.max(-95,resistance*(1-piercing/100)))*CombatModifiers.received(targetStats);}
    public double bulletDamage(double distance,boolean head,Map<String,Float> attackerStats,Map<String,Float> targetStats,JsonObject bullet){return bulletDamage(distance,head,targetStats,bullet)*Math.max(0,1+attackerStats.getOrDefault("damage_bonus",0f)/100.);}
}
