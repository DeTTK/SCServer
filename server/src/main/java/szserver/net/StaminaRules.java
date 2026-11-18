package szserver.net;
import java.util.Map;
/** Rates copied from the installed native normal-movement settings at startup. */
public record StaminaRules(float idle,float walk,float jog,float sprint,float sprintEntry) {
 public StaminaRules{for(float value:new float[]{idle,walk,jog,sprint,sprintEntry})if(!Float.isFinite(value))throw new IllegalArgumentException("stamina_rules_nonfinite");if(idle<0||walk<0||jog<0||sprint>0||sprintEntry<0)throw new IllegalArgumentException("stamina_rules_invalid");}
 public float capacity(Map<String,Float> stats){return Math.min(100,Math.max(.01f,1+stats.getOrDefault("stamina_bonus",0f)/100));}
 public float enterSprint(float value){return Math.max(0,value-sprintEntry);}
 public float step(float value,float maximum,int mode,Map<String,Float> stats,double dt){float rate=switch(mode){case 3->walk;case 4->jog;case 5->sprint;default->idle;};if(rate<0)rate*=Math.max(0,1+stats.getOrDefault("stamina_consume_modifier",0f)/100);else rate+=stats.getOrDefault("stamina_regeneration_bonus",0f)/100;return (float)Math.max(0,Math.min(maximum,value+rate*dt));}
 public float correction(float authoritative,float reported,float maximum){return Math.max(-1,Math.min(1,(authoritative-reported)/maximum));}
}
