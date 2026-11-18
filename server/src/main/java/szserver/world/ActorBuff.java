package szserver.world;
import java.util.*;

/** Resource-prepared medicine effect; actual inventory consumption is a separate actor action. */
public record ActorBuff(int item,String group,int priority,long durationTicks,long cooldownTicks,float instant,Map<String,Float> stats){
    public ActorBuff{
        if(item<1||item>65535||group==null||group.isEmpty()||group.length()>128||durationTicks<0||durationTicks>630720000||cooldownTicks<0||cooldownTicks>630720000||!Float.isFinite(instant)||instant<0||instant>100000)throw new IllegalArgumentException("actor_buff_definition");
        stats=ActorEquipment.checkedStats(stats);if(stats.size()>128)throw new IllegalArgumentException("actor_buff_stats");
    }
    public record View(int id,int item,String group,int priority,long remainingTicks,Map<String,Float> stats){public View{stats=Map.copyOf(stats);}}
}
