package szserver.inventory;
import com.google.gson.*;
import java.util.*;

/** Resource combat parameters. No prototype-wide damage, RPM or magazine constants. */
public record WeaponProfile(int capacity,List<Integer> ammunition,double damage,double distantDamage,double range,double falloffStart,double falloffEnd,double rpm,double reload,double tacticalReload,double headMultiplier,List<Double> reloadByRounds,ReloadAnimation reloadAnimation) {
    private static final int MAX_CAPACITY=1_000_000;
    public record ReloadTiming(float interrupt,float complete,float visual){
        public ReloadTiming{
            if(!Float.isFinite(interrupt)||!Float.isFinite(complete)||!Float.isFinite(visual)||interrupt<0||complete<0||visual<0)
                throw new IllegalArgumentException("invalid_reload_timing");
        }
        public static ReloadTiming uniform(float seconds){return new ReloadTiming(seconds,seconds,seconds);}
    }
    public record ShellReload(float empty,float tactical,float insertion,float crossing,int threshold,boolean firstIncluded,float modifier){
        ReloadTiming timing(int loaded,int added){
            float time=(loaded==0?empty:tactical)-(firstIncluded?insertion:0)+insertion*added;
            if(loaded<threshold&&loaded+added>threshold)time+=crossing;
            return ReloadTiming.uniform(time*modifier);
        }
    }
    public record ReloadAnimation(ReloadTiming empty,ReloadTiming tactical,ShellReload shells,int soundTimeline){
        ReloadTiming timing(int loaded,int added){
            return shells!=null?shells.timing(loaded,added):loaded==0?empty:tactical;
        }
    }
    public WeaponProfile(int capacity,List<Integer> ammunition,double damage,double distantDamage,double range,double falloffStart,double falloffEnd,double rpm,double reload,double tacticalReload,double headMultiplier){this(capacity,ammunition,damage,distantDamage,range,falloffStart,falloffEnd,rpm,reload,tacticalReload,headMultiplier,List.of());}
    public WeaponProfile(int capacity,List<Integer> ammunition,double damage,double distantDamage,double range,double falloffStart,double falloffEnd,double rpm,double reload,double tacticalReload,double headMultiplier,List<Double> reloadByRounds){this(capacity,ammunition,damage,distantDamage,range,falloffStart,falloffEnd,rpm,reload,tacticalReload,headMultiplier,reloadByRounds,null);}
    public double reloadDuration(int rounds){if(rounds<0||rounds>capacity)throw new IllegalArgumentException("magazine_rounds");return reloadByRounds.isEmpty()?(rounds==0?reload:tacticalReload):reloadByRounds.get(rounds);}
    public ReloadTiming reloadTiming(int rounds,int added){
        if(rounds<0||added<=0||rounds+added>capacity)throw new IllegalArgumentException("reload_ammunition_count");
        return reloadAnimation==null?ReloadTiming.uniform((float)reloadDuration(rounds)):reloadAnimation.timing(rounds,added);
    }
    public int reloadSoundTimeline(){return reloadAnimation==null?-2:reloadAnimation.soundTimeline();}
    public WeaponProfile{ammunition=List.copyOf(ammunition);reloadByRounds=List.copyOf(reloadByRounds);if(!reloadByRounds.isEmpty()&&(reloadByRounds.size()!=capacity+1||reloadByRounds.stream().anyMatch(v->!Double.isFinite(v)||v<0)))throw new IllegalArgumentException("invalid_reload_timeline");if(ammunition.isEmpty()||ammunition.stream().anyMatch(id->id<1||id>65535)||java.util.stream.DoubleStream.of(damage,distantDamage,range,falloffStart,falloffEnd,rpm,reload,tacticalReload,headMultiplier).anyMatch(v->!Double.isFinite(v))||capacity<1||capacity>MAX_CAPACITY||damage<=0||damage>1_000_000||distantDamage<0||distantDamage>1_000_000||rpm<=0||rpm>6000||range<=0||range>1024||reload<0||tacticalReload<0||headMultiplier<0)throw new IllegalArgumentException("invalid_weapon_profile");}
    private static double value(JsonObject p,String name,double fallback){return p.has(name)?p.get(name).getAsDouble():fallback;}
    public static WeaponProfile from(JsonObject p){
        if(!p.has("clip_size")||!p.has("bullets_id"))return null;JsonElement bullets=p.get("bullets_id");List<Integer> ammo=new ArrayList<>();if(bullets.isJsonArray())for(JsonElement id:bullets.getAsJsonArray())ammo.add(id.getAsInt());else if(bullets.isJsonPrimitive())ammo.add(bullets.getAsInt());if(ammo.isEmpty())return null;
        int capacity=p.get("clip_size").getAsInt();double damage=value(p,"damage",0),range=value(p,"max_distance",120),rpm=value(p,"rate_of_fire",0),reload=value(p,"reload_time",3);
        double distant=value(p,"damage_distant",damage),start=value(p,"damage_decrease_start",0),end=value(p,"damage_decrease_end",range),tactical=value(p,"reload_time_tactical",reload),head=value(p,"head_damage_modifier",1);
        // The authored admin Gauss deals 30000 damage. Keep bounded finite
        // resource validation without rejecting a legitimate equipped weapon.
        return new WeaponProfile(capacity,List.copyOf(ammo),damage,distant,range,start,end,rpm,reload,tactical,head);
    }
    public double damageAt(double distance){double fraction=Math.max(0,Math.min(1,(distance-falloffStart)/Math.max(.001,falloffEnd-falloffStart)));return damage+fraction*(distantDamage-damage);}
    public WeaponProfile withMagazine(JsonObject p){if(!p.has("type")||!p.get("type").getAsString().equals("magazine"))return this;int size=(int)value(p,"clip_size",capacity);if(p.has("additive_clip")&&p.get("additive_clip").getAsBoolean())size+=capacity;if(size<1||size>1000)throw new IllegalArgumentException("invalid_magazine_capacity");return new WeaponProfile(size,ammunition,damage,distantDamage,range,falloffStart,falloffEnd,rpm,value(p,"reload_time",reload),value(p,"reload_time_tactical",tacticalReload),headMultiplier);}
    public double bulletDamage(double distance,boolean head,JsonObject armor,JsonObject bullet){double resistance=Math.max(-95,value(armor,"bullet_dmg_factor",0)),piercing=Math.max(-100,Math.min(100,value(bullet,"piercing",0)));double raw=damageAt(distance)*(head?headMultiplier:1)*Math.max(0,1+value(bullet,"damage",0)/100);return raw*100/(100+Math.max(-95,resistance*(1-piercing/100)));}
    public double bulletDamage(double distance,boolean head,Map<String,Float> targetStats,JsonObject bullet){double resistance=Math.max(-95,targetStats.getOrDefault("bullet_dmg_factor",0f)),piercing=Math.max(-100,Math.min(100,value(bullet,"piercing",0)));double raw=damageAt(distance)*(head?headMultiplier:1)*Math.max(0,1+value(bullet,"damage",0)/100);return raw*100/(100+Math.max(-95,resistance*(1-piercing/100)))*CombatModifiers.received(targetStats);}
    public double bulletDamage(double distance,boolean head,Map<String,Float> attackerStats,Map<String,Float> targetStats,JsonObject bullet){return bulletDamage(distance,head,targetStats,bullet)*Math.max(0,1+attackerStats.getOrDefault("damage_bonus",0f)/100.);}
}
