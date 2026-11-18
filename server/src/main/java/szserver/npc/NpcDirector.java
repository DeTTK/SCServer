package szserver.npc;

import szserver.world.*;
import szserver.client.NpcVisuals;
import java.util.*;

/** All methods except published() run on WorldLoop. No alternate movement physics and no IO. */
public final class NpcDirector {
    public record Status(String actor,String program,int step,String action,String error){}
    private static final Map<World,NpcDirector> directors=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Run{final NpcProgram program;final UUID player;int index;long until=-1;String error="";Run(NpcProgram p,UUID u){program=p;player=u;}}
    private final Map<String,Run> runs=new LinkedHashMap<>();
    private final Map<String,ActorLocomotion.Gait> gaits=new HashMap<>();
    private volatile List<Status> published=List.of();
    public static NpcDirector of(World world){synchronized(directors){return directors.computeIfAbsent(world,k->new NpcDirector());}}
    public static void tick(World world){NpcDirector d; synchronized(directors){d=directors.get(world);}if(d!=null)d.advance(world);}
    public static ActorLocomotion.Gait gait(World world,String id){NpcDirector d; synchronized(directors){d=directors.get(world);}return d==null?ActorLocomotion.Gait.JOG:d.gaits.getOrDefault(id,ActorLocomotion.Gait.JOG);}
    public List<Status> published(){return published;}
    /** Manual native commands take ownership of navigation without resetting their new goal. */
    public void cancel(String id){runs.remove(id);gaits.remove(id);publish();}
    public void startMany(World world,List<String> ids,NpcProgram program,UUID player){
        if(ids.isEmpty()||ids.size()>16||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("npc_group_size");
        for(String id:ids)if(!NpcVisuals.managed(id)||actor(world,id).health()<=0)throw new IllegalArgumentException("npc_group_actor");
        long retained=runs.keySet().stream().filter(id->!ids.contains(id)).count();if(retained+ids.size()>64)throw new IllegalArgumentException("npc_program_capacity");
        for(String id:ids)start(world,id,program,player);
    }
    public void start(World world,String id,NpcProgram program,UUID player){if(!NpcVisuals.managed(id))throw new IllegalArgumentException("select_authored_npc");var a=actor(world,id);if(a.health()<=0)throw new IllegalArgumentException("npc_dead");if(!runs.containsKey(id)&&runs.size()>=64)throw new IllegalArgumentException("npc_program_capacity");stop(world,id);runs.put(id,new Run(program,player));publish();}
    public void stop(World world,String id){Run r=runs.remove(id);gaits.remove(id);var a=actor(world,id);world.goal(id,new World.Vec(a.x(),a.y(),a.z()));if(r!=null)publish();}
    private static World.View actor(World w,String id){return w.snapshot().entities().stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("npc_missing"));}
    private void advance(World w){
        World.Snapshot snapshot=w.snapshot();Map<String,World.View> actors=new HashMap<>();snapshot.entities().forEach(a->actors.put(a.id(),a));long tick=snapshot.tick();
        for(var it=runs.entrySet().iterator();it.hasNext();){var e=it.next();String id=e.getKey();Run r=e.getValue();var a=actors.get(id);if(a==null||a.health()<=0){gaits.remove(id);it.remove();continue;}if(!r.error.isEmpty())continue;
            try{
                NpcProgram.Step s=r.program.steps().get(r.index);
                if(r.until<0){r.until=tick+Math.max(1,s.ticks());switch(s.action()){
                    case "move"->{gaits.put(id,s.gait());w.goal(id,s.point());}
                    case "follow"->{gaits.put(id,ActorLocomotion.Gait.JOG);w.follow(id,new World.Follow(r.player,2.5));}
                    case "wait","look","stop"->{w.goal(id,new World.Vec(a.x(),a.y(),a.z()));gaits.remove(id);}
                    case "state"->w.condition(id,new ActorControllerEngine.Condition(ActorControllerEngine.ConditionKind.valueOf(s.state()),s.enabled()));
                    default->throw new IllegalStateException("validated_program_action");
                }}
                if(s.action().equals("look"))w.control(id,new ActorControllerEngine.Input(s.yaw(),0,0,ActorLocomotion.Gait.NONE,false,false,false));
                boolean done=s.action().equals("move")?Math.hypot(a.x()-s.point().x(),a.z()-s.point().z())<.4&&Math.abs(a.y()-s.point().y())<.7:tick>=r.until;
                if(!done&&s.action().equals("move")&&tick>=r.until)throw new IllegalArgumentException("route_timeout_at_step_"+(r.index+1));
                if(done){if(s.action().equals("follow"))w.goal(id,new World.Vec(a.x(),a.y(),a.z()));r.index++;r.until=-1;if(r.index>=r.program.steps().size()){if(r.program.loop())r.index=0;else{gaits.remove(id);it.remove();}}}
            }catch(RuntimeException failure){r.error=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());gaits.remove(id);try{w.goal(id,new World.Vec(a.x(),a.y(),a.z()));}catch(RuntimeException ignored){}}
        }
        publish();
    }
    private void publish(){published=runs.entrySet().stream().map(e->{Run r=e.getValue();return new Status(e.getKey(),r.program.name(),r.index+1,r.program.steps().get(r.index).action(),r.error);}).toList();}
}
