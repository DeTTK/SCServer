package szserver.net;

import java.util.*;

/** One composition for live stats and medicine planning; zone doses remain a separate input. */
public final class PlayerEffectStats {
    public static Map<String,Float> compose(Map<String,Float> equipment,Collection<Map<String,Float>> medicines,Collection<Map<String,Float>> areas,Map<String,Float> illness,Set<String> accumulations,java.util.function.Function<Collection<Map<String,Float>>,Map<String,Float>> aggregate){
        List<Map<String,Float>> effects=new ArrayList<>(medicines);effects.addAll(areas);Map<String,Float> result=new TreeMap<>(equipment);
        aggregate.apply(effects).forEach((key,value)->{if(!accumulations.contains(key))result.merge(key,value,Float::sum);});
        for(var bank:medicines)bank.forEach((key,value)->{if(accumulations.contains(key))result.merge(key,value,Float::sum);});
        illness.forEach((key,value)->result.merge(key,value,Float::sum));if(result.values().stream().anyMatch(v->!Float.isFinite(v)))throw new IllegalArgumentException("player_effect_stats_overflow");return Map.copyOf(result);
    }
    private PlayerEffectStats(){}
}
