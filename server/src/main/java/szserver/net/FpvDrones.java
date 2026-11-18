package szserver.net;

import szserver.client.FpvPackets;
import szserver.client.FpvPackets.*;
import szserver.client.FpvPackets.Vector;
import java.util.*;

/** Transport-owner lifecycle only. The pilot owns flight, collision, motors and battery. */
public final class FpvDrones<O> {
    public static final int LIMIT=32;public static final long TIMEOUT=10_000_000_000L;
    public static final class Drone<O>{
        private final O owner;private State state;private boolean piloting;private long updated;
        private Drone(O owner,State state,boolean piloting,long now){this.owner=owner;this.state=state;this.piloting=piloting;updated=now;}
        public O owner(){return owner;}public State state(){return state;}public boolean piloting(){return piloting;}
    }
    public record Change<O>(Drone<O> drone,String event){public boolean detonated(){return "exploded".equals(event);}}
    private final Map<UUID,Drone<O>> drones=new LinkedHashMap<>();private final Map<O,Drone<O>> owners=new HashMap<>();
    private long accepted,stale,retired,timeouts;
    public Drone<O> owned(O owner){return owners.get(owner);}
    public Drone<O> byId(UUID id){return drones.get(id);}
    public void checkDeploy(O owner,UUID id){Objects.requireNonNull(owner);Objects.requireNonNull(id);if(owners.containsKey(owner))throw new IllegalArgumentException("fpv_drone_already_deployed");if(drones.containsKey(id))throw new IllegalArgumentException("drone_not_owned");if(drones.size()>=LIMIT)throw new IllegalArgumentException("fpv_drone_limit");}
    public Drone<O> require(O owner,UUID id){Drone<O> d=drones.get(id);if(d==null||!d.owner.equals(owner))throw new IllegalArgumentException("drone_not_owned");return d;}
    public Drone<O> deploy(O owner,UUID id,Vector position,boolean piloting,long now){
        checkDeploy(owner,id);
        var state=new State(id,-1,new Rotation(1,0,0,0),position,new Vector(0,0,0),1,false,false,new Motors(0,0,0,0),0);Drone<O> d=new Drone<>(owner,state,piloting,now);drones.put(id,d);owners.put(owner,d);return d;
    }
    public Change<O> submit(O owner,State state,long now){
        Drone<O> d=require(owner,state.id());if(state.sequence()<=d.state.sequence()){stale++;return null;}d.state=state.normalized();d.updated=now;accepted++;
        if(state.impact()!=0){remove(d);return new Change<>(d,state.impact()==1?"exploded":"recalled");}return new Change<>(d,null);
    }
    public Drone<O> piloting(O owner,UUID id,boolean enabled){Drone<O> d=require(owner,id);if(enabled&&d.state.battery()<=.02f)throw new IllegalArgumentException("fpv_battery_empty");d.piloting=enabled;return d;}
    private void remove(Drone<O> d){drones.remove(d.state.id(),d);owners.remove(d.owner,d);retired++;}
    public Change<O> recall(O owner){Drone<O> d=owners.get(owner);if(d==null)return null;remove(d);return new Change<>(d,"recalled");}
    public List<Change<O>> expire(long now){List<Change<O>> out=new ArrayList<>();for(Drone<O> d:new ArrayList<>(drones.values()))if(now-d.updated>TIMEOUT){remove(d);timeouts++;out.add(new Change<>(d,"recalled"));}return out;}
    public Map<String,Object> metrics(){return Map.of("active",drones.size(),"piloting",drones.values().stream().filter(Drone::piloting).count(),"states",accepted,"stale",stale,"retired",retired,"timeouts",timeouts,"pilotPhysics",true);}
}
