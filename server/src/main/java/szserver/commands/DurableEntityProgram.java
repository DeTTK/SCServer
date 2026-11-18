package szserver.commands;

import com.google.gson.*;
import szserver.world.World;
import szserver.world.ActorRuntime;
import szserver.util.Json;
import java.util.*;

/** Command-owner original spawn/last goal/remove program. Runtime input, physics and damage
 * do not overwrite it:checkpoint restart must match complete canonical-journal replay. */
final class DurableEntityProgram {
    private final Map<String,World.DurableEntity> rows=new LinkedHashMap<>();private long counter;
    private volatile ActorRuntime.Snapshot runtime;
    ActorRuntime.Snapshot runtime(){return runtime;}
    void runtime(ActorRuntime.Snapshot snapshot){
        if(snapshot.counter()!=counter||snapshot.actors().size()!=rows.size())throw new IllegalArgumentException("actor_runtime_program_mismatch");
        for(var a:snapshot.actors()){var row=rows.get(a.id());if(row==null||!row.kind().equals(a.kind())||row.incarnation()!=a.incarnation())throw new IllegalArgumentException("actor_runtime_program_mismatch");}runtime=snapshot;
    }
    Map<String,World.DurableEntity> rows(){return Collections.unmodifiableMap(rows);}
    long counter(){return counter;}
    private static World.Vec position(JsonObject event){JsonObject p=Json.object(event,"position");return new World.Vec(Json.number(p,"x",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),Json.number(p,"y",0,255),Json.number(p,"z",-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));}
    void apply(JsonObject event){String type=Json.text(event,"type",32);if(!Set.of("spawn","goal","patrol","follow","remove").contains(type))return;String id=Json.text(event,"entityId",36);runtime=null;
        switch(type){case "spawn"->{if(rows.containsKey(id)||rows.size()>=512)throw new IllegalArgumentException("durable_actor_duplicate_or_limit");rows.put(id,new World.DurableEntity(id,Json.text(event,"kind",16),position(event),null,Math.addExact(counter,1)));counter++;}
            case "goal"->{var before=rows.get(id);if(before==null)throw new IllegalArgumentException("durable_actor_missing");rows.put(id,new World.DurableEntity(id,before.kind(),before.spawn(),position(event),before.incarnation()));}
            case "patrol"->{var before=rows.get(id);if(before==null)throw new IllegalArgumentException("durable_actor_missing");rows.put(id,new World.DurableEntity(id,before.kind(),before.spawn(),null,before.incarnation(),CommandService.patrolRoute(event)));}
            case "follow"->{var before=rows.get(id);if(before==null)throw new IllegalArgumentException("durable_actor_missing");rows.put(id,new World.DurableEntity(id,before.kind(),before.spawn(),null,before.incarnation(),List.of(),CommandService.followTarget(event)));}
            case "remove"->{if(rows.remove(id)==null)throw new IllegalArgumentException("durable_actor_missing");}}
    }
    void restore(List<World.DurableEntity> values,long counter){if(!rows.isEmpty()||this.counter!=0)throw new IllegalStateException("checkpoint_actor_program_not_empty");World validation=new World(szserver.world.Terrain.flat());validation.restoreDurableEntities(values,counter);for(var row:values)rows.put(row.id(),row);this.counter=counter;}
    void restoreWorld(World world){world.restoreDurableEntities(List.copyOf(rows.values()),counter);if(runtime!=null)world.restoreActorRuntime(runtime);}
}
