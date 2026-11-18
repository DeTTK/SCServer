package szserver.client;

import java.util.Map;

/** Horde zombies are native HumanMale NPCs with npcZombie, not transformed players.
 * Kept as an empty compatibility hook for older server call sites. */
public final class ZombiePackets {
    public static Map<Integer,byte[]> appearance(){return Map.of();}
    private ZombiePackets(){}
}
