package szserver.world;

import szserver.inventory.ExplosiveProfile;
import java.util.*;

/** Committed native pressure contact, carrying the startup-prepared resource profile. */
public record ActorMineTrigger(UUID id,World.Block block,World.Vec position,ExplosiveProfile profile,List<ActorMineTarget> targets,List<ActorMinePlayerHit> players) {
    public ActorMineTrigger(UUID id,World.Block block,World.Vec position,ExplosiveProfile profile){this(id,block,position,profile,List.of(),List.of());}
    public ActorMineTrigger {Objects.requireNonNull(id);Objects.requireNonNull(block);Objects.requireNonNull(position);Objects.requireNonNull(profile);targets=List.copyOf(targets);players=List.copyOf(players);if(targets.size()>512||players.size()>512)throw new IllegalArgumentException("actor_mine_targets");}
    public ActorMineTrigger withTargets(List<ActorMineTarget> targets){return new ActorMineTrigger(id,block,position,profile,targets,players);}
    public ActorMineTrigger withPlayers(List<ActorMinePlayerHit> players){return new ActorMineTrigger(id,block,position,profile,targets,players);}
}
