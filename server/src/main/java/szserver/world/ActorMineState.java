package szserver.world;

/** Shared block-entity clock; independent of any NPC lifetime or controller capsule. */
public record ActorMineState(World.Block block,int cooldown) {
    public ActorMineState {
        if(block==null||Math.abs((long)block.x())>MapBounds.HORIZONTAL||Math.abs((long)block.z())>MapBounds.HORIZONTAL||block.y()<0||block.y()>255||cooldown<0||cooldown>1_000_000)throw new IllegalArgumentException("actor_mine_state");
    }
}
