package szserver.world;

import java.util.*;

/** Immutable per-actor contact publication. A deferred physics step never commits this state. */
public final class ActorContacts {
    public record Key(int homeX,int homeZ,UUID object,boolean container,int occurrence,int cell){}
    public record Living(float health,float maximum){
        public Living{if(!Float.isFinite(health)||!Float.isFinite(maximum)||maximum<0)throw new IllegalArgumentException("actor_obstacle_health");}
    }
    public record Obstacle(float deceleration,Living living){
        public Obstacle(float deceleration){this(deceleration,null);}
        public Obstacle{if(!Float.isFinite(deceleration)||deceleration<0||deceleration>1)throw new IllegalArgumentException("actor_obstacle_deceleration");}
        public boolean applies(){return living==null||living.health()>0;}
    }
    public record Volume(Key key,SceneCollision.Box bounds,Obstacle obstacle){}
    public record State(Map<Key,Obstacle> active,long entered,long stayed,long left){
        public static final State EMPTY=new State(Map.of(),0,0,0);
        public State{active=Map.copyOf(active);}
        public State next(Map<Key,Obstacle> contacts){
            // The native dispatcher calls stay for every current contact, including its entry frame.
            long enter=0,stay=contacts.size(),leave=0;
            for(var key:contacts.keySet())if(!active.containsKey(key))enter++;
            for(var key:active.keySet())if(!contacts.containsKey(key))leave++;
            return new State(contacts,entered+enter,stayed+stay,left+leave);
        }
    }
    private ActorContacts(){}
}
