package szserver.net;
import com.google.gson.JsonObject;
import java.util.Map;

/** Fixed-step health effects. Dead characters stay dead until an explicit respawn. */
public record HealthRules(double recoveryMinimum,double recoveryBase,float damageRegenFactor) {
 public HealthRules{if(!Double.isFinite(recoveryMinimum)||!Double.isFinite(recoveryBase)||!Float.isFinite(damageRegenFactor)||recoveryMinimum<0||recoveryBase<0||damageRegenFactor<0||damageRegenFactor>1)throw new IllegalArgumentException("health_rules_invalid");}
 public static HealthRules read(JsonObject config){return new HealthRules(config.get("regen_time_min").getAsDouble(),config.get("regen_time_base").getAsDouble(),config.get("dmg_cd_health_regen_factor").getAsFloat());}
 public static float maximum(Map<String,Float> stats){return Math.min(10000,Math.max(1,100+stats.getOrDefault("health_bonus",0f)));}
 public static float instant(float health,Map<String,Float> stats,float raw){return Math.min(maximum(stats),Math.max(0,health+raw*Math.max(0,1+stats.getOrDefault("heal_efficiency",0f)/100)));}
 /** Native 1409908300.abstract treats this property as a percentage of the configured delay. */
 public double recoverySeconds(Map<String,Float> stats){return Math.max(recoveryMinimum,Math.max(0,recoveryBase*(1+stats.getOrDefault("regeneration_time",0f)/100.0)));}
 public float step(float health,float maximum,Map<String,Float> stats,double sinceDamage,double dt){
  if(health<=0)return 0;
  double recovery=recoverySeconds(stats);float ordinary=Math.max(0,2.5f+stats.getOrDefault("regeneration_bonus",0f));if(sinceDamage<recovery)ordinary*=damageRegenFactor;
  float rate=(ordinary+stats.getOrDefault("artefakt_heal",0f))*Math.max(0,1+stats.getOrDefault("heal_efficiency",0f)/100)-Math.max(0,stats.getOrDefault("artefakt_damage",0f));
  return (float)Math.max(0,Math.min(maximum,health+rate*dt));
 }
}
