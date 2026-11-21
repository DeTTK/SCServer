package szserver.world;

import szserver.inventory.ItemStack;
import java.util.*;

/** Authored NPC movement properties and the real immutable held stack. No player physics authority. */
public record ActorEquipment(ActorLocomotion.Profile movement,float energyCapacity,ItemStack held,ActorPresentation presentation,Map<String,Float> stats) {
    public ActorEquipment(ActorLocomotion.Profile movement,float energyCapacity,ItemStack held,ActorPresentation presentation){this(movement,energyCapacity,held,presentation,Map.of());}
    public ActorEquipment(ActorLocomotion.Profile movement,float energyCapacity,ItemStack held){this(movement,energyCapacity,held,ActorPresentation.EMPTY);}
    public ActorEquipment {
        Objects.requireNonNull(movement);
        Objects.requireNonNull(presentation);
        stats=checkedStats(stats);
        if(!Float.isFinite(energyCapacity)||energyCapacity<.01f||energyCapacity>100)throw new IllegalArgumentException("actor_equipment_energy");
        if(held!=null&&(held.count()!=1||held.residentBytes()>8192))throw new IllegalArgumentException("actor_equipment_held");
    }
    /** Same speed/sprint stat projection used by the existing server protocol; the native consumer owns movement. */
    public static ActorEquipment stats(Map<String,Float> stats,boolean encumbered,ItemStack held){
        stats=checkedStats(stats);
        return new ActorEquipment(new ActorLocomotion.Profile(Math.max(0,1+stats.getOrDefault("speed_modifier",0f)/100),stats.getOrDefault("sprint_speed_modifier",0f)/100,1,1,1,encumbered),Math.min(100,Math.max(.01f,1+stats.getOrDefault("stamina_bonus",0f)/100)),held,ActorPresentation.EMPTY,stats);
    }
    public static Map<String,Float> checkedStats(Map<String,Float> stats){
        Objects.requireNonNull(stats);if(stats.size()>512)throw new IllegalArgumentException("actor_equipment_stats");
        for(var e:stats.entrySet())if(e.getKey()==null||!e.getKey().matches("[a-z][a-z0-9_]{0,95}")||e.getValue()==null||!Float.isFinite(e.getValue())||Math.abs(e.getValue())>100000&&!szserver.inventory.StatValues.unlimitedProtection(e.getKey(),e.getValue()))throw new IllegalArgumentException("actor_equipment_stat");
        return Map.copyOf(stats);
    }
}
