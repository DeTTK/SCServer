package szserver.net;

import szserver.world.World;
import java.util.*;

/** Mini-game attack timing. Native controllers still own movement and body geometry. */
public final class HordeCombat {
    public static final MeleeProfile ATTACK=new MeleeProfile(.35,1.2,1.9,Math.PI,12,0);
    public record Target(UUID id,World.Vec position,int locomotion,long life){}
    public record Strike(World.View actor,Target target,long sequence){}
    private record Key(String id,long incarnation){}
    private static final class State {long ready,next,sequence;int swing;long life;UUID target;}
    private final Map<Key,State> states=new HashMap<>();private long swings,strikes;
    public static boolean reaches(World.View actor,Target target){
        double[] point=PlayerPosture.closestPoint(new double[]{actor.x(),actor.y()+1,actor.z()},new double[]{target.position().x(),target.position().y(),target.position().z()},target.locomotion());
        double dx=point[0]-actor.x(),dy=point[1]-(actor.y()+1),dz=point[2]-actor.z();return dx*dx+dy*dy+dz*dz<=ATTACK.reach()*ATTACK.reach();
    }
    public List<Strike> advance(UUID owner,List<World.View> actors,List<Target> targets,long now){
        Target target=targets.stream().filter(t->t.id().equals(owner)).findFirst().orElse(null);Set<Key> live=new HashSet<>();List<Strike> out=new ArrayList<>();
        for(var actor:actors)if(ZombieHorde.owns(actor.id())&&actor.health()>0){
            Key key=new Key(actor.id(),actor.incarnation());live.add(key);State s=states.computeIfAbsent(key,k->new State());
            if(target==null||!reaches(actor,target)){s.ready=0;continue;}
            if(s.ready!=0){
                if(!target.id().equals(s.target)||target.life()!=s.life){s.ready=0;continue;}
                if(now>=s.ready){s.ready=0;strikes++;out.add(new Strike(actor,target,s.sequence));}continue;
            }
            if(now>=s.next){s.ready=now+350_000_000L;s.next=now+1_200_000_000L;s.target=target.id();s.life=target.life();s.sequence++;s.swing=(s.swing+1)&255;swings++;}
        }
        states.keySet().retainAll(live);return List.copyOf(out);
    }
    public int swing(World.View actor){var state=states.get(new Key(actor.id(),actor.incarnation()));return state==null?0:state.swing;}
    public Map<String,Object> metrics(){return Map.of("tracked",states.size(),"swings",swings,"strikes",strikes,"damage",ATTACK.damage(),"reach",ATTACK.reach(),"cooldownSeconds",ATTACK.cooldown());}
}
