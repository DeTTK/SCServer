package szserver.inventory;

import szserver.world.Grenades;
import java.util.*;
import java.util.concurrent.*;

/** Ordered durability boundary: completion snapshot, area activation, then projectile terminal.
 * A crash at either boundary retains the spent item and the first persisted area clock. */
public final class GrenadeCompletion {
    private final GrenadeOutbox projectiles;private final GrenadeAreaStore areas;
    public GrenadeCompletion(GrenadeOutbox projectiles,GrenadeAreaStore areas){this.projectiles=Objects.requireNonNull(projectiles);this.areas=Objects.requireNonNull(areas);}
    private static GrenadeAreaStore.Activation activation(GrenadeReceipt r,Grenades.Saved s,long now,Set<UUID> targets){return activation(r,s,now,targets,Map.of());}
    private static GrenadeAreaStore.Activation activation(GrenadeReceipt r,Grenades.Saved s,long now,Set<UUID> targets,Map<UUID,UUID> revive){return new GrenadeAreaStore.Activation(r.projectile(),r.character(),r.name(),r.item(),s.position(),now,r.areas(),r.ownerContext(),r.areas().stream().anyMatch(p->p.oneShot()&&!p.revive())?targets:null,r.areas().stream().anyMatch(GrenadeAreaProfile::revive)?revive:null);}
    /** Startup worker only, before either store accepts gameplay requests. */
    public void recover(long now)throws Exception{
        for(var state:projectiles.states())if(!state.receipt().areas().isEmpty()){
            if(state.saved().reason()==null)areas.restoreReservation(state.receipt().projectile(),state.receipt().areas().size());
            else areas.repair(projectiles.pulses().getOrDefault(state.receipt().projectile(),activation(state.receipt(),state.saved(),now,Set.of())));
        }
        areas.expire(now).get(10,TimeUnit.SECONDS);
    }
    public CompletableFuture<Void> save(List<Grenades.Saved> states,List<UUID> terminal,long now){
        return save(states,terminal,now,Map.of());
    }
    public CompletableFuture<Void> save(List<Grenades.Saved> states,List<UUID> terminal,long now,Map<UUID,Set<UUID>> pulseTargets){
        return save(states,terminal,now,pulseTargets,Map.of());
    }
    public CompletableFuture<Void> save(List<Grenades.Saved> states,List<UUID> terminal,long now,Map<UUID,Set<UUID>> pulseTargets,Map<UUID,Map<UUID,UUID>> reviveTargets){
        if(now<0||states.size()>Grenades.LIMIT||terminal.size()>Grenades.LIMIT)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_completion_bounds"));
        Map<UUID,GrenadeReceipt> receipts=new HashMap<>();projectiles.states().forEach(s->receipts.put(s.receipt().projectile(),s.receipt()));
        Map<UUID,Grenades.Saved> saved=new HashMap<>();for(var s:states)if(saved.put(s.id(),s)!=null)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_completion_duplicate"));
        Set<UUID> seen=new HashSet<>();List<GrenadeAreaStore.Activation> requests=new ArrayList<>();
        for(UUID id:terminal){if(!seen.add(id))return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_completion_duplicate_terminal"));GrenadeReceipt r=receipts.get(id);Grenades.Saved s=saved.get(id);
            if(r==null||s==null||s.reason()==null)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_completion_missing_snapshot"));
            if(!r.areas().isEmpty())requests.add(projectiles.pulses().getOrDefault(id,activation(r,s,now,pulseTargets.getOrDefault(id,Set.of()),reviveTargets.getOrDefault(id,Map.of()))));
        }
        List<Grenades.Saved> detached=List.copyOf(states);List<UUID> finished=List.copyOf(terminal);
        // No disk or native codec executes in the caller's network/world owner.
        Map<UUID,GrenadeAreaStore.Activation> captured=new HashMap<>();requests.stream().filter(a->a.pulseTargets()!=null||a.reviveTargets()!=null).forEach(a->captured.put(a.projectile(),a));CompletableFuture<Void> chain=projectiles.save(detached,List.of(),captured);
        for(var r:requests)chain=chain.thenCompose(ignored->areas.activate(r).thenApply(value->null));
        return finished.isEmpty()?chain:chain.thenCompose(ignored->projectiles.save(detached,finished));
    }
}
