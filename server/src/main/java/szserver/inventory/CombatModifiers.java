package szserver.inventory;
import java.util.Map;
public final class CombatModifiers {
 private CombatModifiers(){}
 public static double received(Map<String,Float> stats){return 100/(100+Math.max(-99,stats.getOrDefault("receive_damage_modifier",0f)));}
}
