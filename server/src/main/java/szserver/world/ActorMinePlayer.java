package szserver.world;

import java.util.*;

/** Immutable validated player observation; never an input to server movement simulation. */
public record ActorMinePlayer(UUID character,UUID incarnation,long life,World.Vec position,Map<String,Float> stats,boolean alive,boolean invulnerable,int locomotion) {
    public ActorMinePlayer(UUID character,UUID incarnation,long life,World.Vec position,Map<String,Float> stats,boolean alive,boolean invulnerable){this(character,incarnation,life,position,stats,alive,invulnerable,0);}
    public ActorMinePlayer {
        Objects.requireNonNull(character);Objects.requireNonNull(incarnation);Objects.requireNonNull(position);stats=Map.copyOf(stats);
        if(life<0||locomotion<0||locomotion>12||stats.size()>512||!Double.isFinite(position.x())||!Double.isFinite(position.y())||!Double.isFinite(position.z())||Math.abs(position.x())>MapBounds.HORIZONTAL||Math.abs(position.z())>MapBounds.HORIZONTAL||position.y()<-64||position.y()>4096)throw new IllegalArgumentException("actor_mine_player");
        for(var entry:stats.entrySet())if(!Float.isFinite(entry.getValue()))throw new IllegalArgumentException("actor_mine_player_stats");
    }
}
