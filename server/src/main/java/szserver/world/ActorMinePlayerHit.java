package szserver.world;

import java.util.*;

/** Exposure is tied to this session incarnation and life, never to a later respawn. */
public record ActorMinePlayerHit(UUID character,UUID incarnation,long life,float damage) {
    public ActorMinePlayerHit {Objects.requireNonNull(character);Objects.requireNonNull(incarnation);if(life<0||!Float.isFinite(damage)||damage<=0||damage>10000)throw new IllegalArgumentException("actor_mine_player_hit");}
}
