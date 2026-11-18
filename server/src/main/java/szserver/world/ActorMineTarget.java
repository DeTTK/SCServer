package szserver.world;

/** Damage admitted against the same resident geometry as the pressure contact. */
public record ActorMineTarget(String id,long incarnation,float damage) {
    public ActorMineTarget {if(id==null||incarnation<1||!Float.isFinite(damage)||damage<=0||damage>10000)throw new IllegalArgumentException("actor_mine_target");}
}
