package szserver.world;

/** Committed native living state. Body water contact and head submersion are different queries. */
public record ActorVitals(float health,int air,boolean submerged,float maximum) {
    public ActorVitals(float health,int air,boolean submerged){this(health,air,submerged,100);}
    public ActorVitals {
        if(!Float.isFinite(health)||!Float.isFinite(maximum)||maximum<1||maximum>10000||health<0||health>maximum||air<-19||air>300)throw new IllegalArgumentException("actor_vitals");
    }
}
