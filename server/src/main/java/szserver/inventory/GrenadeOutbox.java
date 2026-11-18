package szserver.inventory;

import com.google.gson.*;
import szserver.commands.CommandHistory;
import szserver.persistence.Journal;
import szserver.util.Workers;
import szserver.world.Grenades;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** CRC/fsynced projectile outbox, bounded live state and cold consumed/terminal indexes.
 * Terminal is persisted BEFORE gameplay delivery (at-most-once; not an atomic damage transaction). */
public final class GrenadeOutbox implements AutoCloseable {
    public record State(GrenadeReceipt receipt,Grenades.Saved saved){}
    private final Journal journal;private final CommandHistory index;
    private final ThreadPoolExecutor worker=Workers.pool("grenade-outbox",1,32);
    private final Map<UUID,State> active=new LinkedHashMap<>();private final Map<UUID,GrenadeAreaStore.Activation> pulses=new HashMap<>();private volatile Map<UUID,GrenadeAreaStore.Activation> pulseView=Map.of();
    private volatile List<State> view=List.of();private volatile boolean closed;private volatile Throwable failure;
    private volatile long saved,terminated,repaired;
    public GrenadeOutbox(Path data)throws Exception{this(data,"flat:arena-v1");}
    public GrenadeOutbox(Path data,String worldIdentity)throws Exception{
        if(worldIdentity==null||worldIdentity.isBlank()||worldIdentity.length()>4096)throw new IllegalArgumentException("grenade_outbox_world_identity");
        offOwner();journal=Journal.streaming(data.resolve("grenades.sjournal"));CommandHistory opened=null;
        boolean[] bound={false};
        try{opened=new CommandHistory(journal,1024*1024);index=opened;journal.replayLocated((ref,event)->{
            if(!bound[0]&&!event.get("type").getAsString().equals("grenade_world"))throw new IllegalArgumentException("grenade_outbox_world_binding_missing");
            switch(event.get("type").getAsString()){
                case "grenade_world"->{if(bound[0]||!worldIdentity.equals(event.get("identity").getAsString()))throw new IllegalArgumentException("grenade_outbox_world_mismatch");bound[0]=true;}
                case "grenade_consumed"->{GrenadeReceipt receipt=receipt(event);index.register(ref,event,false);put(receipt,initial(receipt));}
                case "grenade_snapshot"->{for(var row:event.getAsJsonArray("states"))update(frame(row.getAsJsonObject()));if(event.has("pulses")){JsonArray rows=event.getAsJsonArray("pulses");if(rows.size()>GrenadeAreaStore.LIMIT)throw new IllegalArgumentException("grenade_pulse_snapshot_limit");for(var raw:rows){var a=GrenadeAreaStore.Activation.read(raw.getAsJsonObject());putPulse(a,active.containsKey(a.projectile())?active.get(a.projectile()).saved():null);}}}
                case "grenade_terminal"->{UUID id=terminal(event);if(!active.containsKey(id))throw new IllegalArgumentException("grenade_terminal_without_consumption");index.register(ref,event,false);active.remove(id);pulses.remove(id);terminated++;}
                default->throw new IllegalArgumentException("grenade_outbox_event");
            }
        }).get();if(!bound[0]){JsonObject event=new JsonObject();event.addProperty("type","grenade_world");event.addProperty("identity",worldIdentity);journal.append(event).get();}publish();}catch(Exception|Error error){worker.shutdownNow();if(opened!=null)opened.close();journal.close();throw error;}
    }
    private static void offOwner(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("grenade_outbox_io_on_owner");}
    private static String key(UUID id,boolean terminal){return (terminal?"t:":"g:")+id;}
    private static Grenades.Saved initial(GrenadeReceipt r){return new Grenades.Saved(r.projectile(),r.origin(),r.velocity(),0,r.held()?"held":null);}
    private static GrenadeReceipt receipt(JsonObject event){GrenadeReceipt r=GrenadeReceipt.read(event.getAsJsonObject("receipt"));if(!key(r.projectile(),false).equals(event.get("commandId").getAsString()))throw new IllegalArgumentException("grenade_outbox_key");return r;}
    private static UUID terminal(JsonObject event){UUID id=UUID.fromString(event.get("id").getAsString());if(!key(id,true).equals(event.get("commandId").getAsString()))throw new IllegalArgumentException("grenade_terminal_key");return id;}
    private static JsonObject frame(Grenades.Saved s){JsonObject r=new JsonObject();r.addProperty("id",s.id().toString());r.add("position",GrenadeReceipt.vec(s.position()));r.add("velocity",GrenadeReceipt.vec(s.velocity()));r.addProperty("age",s.age());if(s.reason()!=null)r.addProperty("reason",s.reason());return r;}
    private static Grenades.Saved frame(JsonObject r){if(!r.keySet().equals(r.has("reason")?Set.of("id","position","velocity","age","reason"):Set.of("id","position","velocity","age")))throw new IllegalArgumentException("grenade_snapshot_fields");return new Grenades.Saved(UUID.fromString(r.get("id").getAsString()),GrenadeReceipt.vec(r.getAsJsonArray("position")),GrenadeReceipt.vec(r.getAsJsonArray("velocity")),Math.toIntExact(GrenadeReceipt.integer(r,"age")),r.has("reason")?r.get("reason").getAsString():null);}
    private void put(GrenadeReceipt receipt,Grenades.Saved frame){if(active.containsKey(receipt.projectile())||active.size()>=Grenades.LIMIT)throw new IllegalArgumentException("grenade_outbox_live_limit");if(frame.age()>receipt.flight().fuseTicks())throw new IllegalArgumentException("grenade_snapshot_age");active.put(receipt.projectile(),new State(receipt,frame));}
    private void validate(Grenades.Saved frame){State before=active.get(frame.id());if(before==null||frame.age()<before.saved().age()||frame.age()>before.receipt().flight().fuseTicks()||before.saved().reason()!=null&&!before.saved().equals(frame))throw new IllegalArgumentException("grenade_snapshot_sequence");}
    private void update(Grenades.Saved frame){validate(frame);State before=active.get(frame.id());active.put(frame.id(),new State(before.receipt(),frame));}
    private void publish(){view=List.copyOf(active.values());pulseView=Map.copyOf(pulses);}
    public Map<UUID,GrenadeAreaStore.Activation> pulses(){return pulseView;}
    private void validatePulse(GrenadeAreaStore.Activation a,Grenades.Saved frame){State state=active.get(a.projectile());if(state==null||frame==null||frame.reason()==null||a.pulseTargets()==null&&a.reviveTargets()==null||!a.profiles().stream().anyMatch(GrenadeAreaProfile::oneShot)||!a.character().equals(state.receipt().character())||!a.owner().equals(state.receipt().name())||a.item()!=state.receipt().item()||!a.position().equals(frame.position())||!a.profiles().equals(state.receipt().areas())||!Objects.equals(a.ownerContext(),state.receipt().ownerContext()))throw new IllegalArgumentException("grenade_pulse_snapshot_source");var previous=pulses.get(a.projectile());if(previous!=null&&!previous.equals(a))throw new IllegalArgumentException("grenade_pulse_snapshot_changed");}
    private void putPulse(GrenadeAreaStore.Activation a,Grenades.Saved frame){validatePulse(a,frame);pulses.put(a.projectile(),a);}
    public List<State> states(){return view;}
    /** Bootstrap worker repair: marker survived inventory commit even when outbox publication did not. */
    public void repair(GrenadeReceipt request)throws Exception{offOwner();record(request);repaired++;publish();}
    private void record(GrenadeReceipt request)throws Exception{
        offOwner();JsonObject existing=index.raw(key(request.projectile(),false));if(existing!=null){if(!receipt(existing).equals(request))throw new IllegalArgumentException("grenade_receipt_conflict");return;}
        if(active.size()>=Grenades.LIMIT)throw new IllegalArgumentException("grenade_outbox_live_limit");
        JsonObject event=new JsonObject();event.addProperty("type","grenade_consumed");event.addProperty("commandId",key(request.projectile(),false));event.add("receipt",request.json());
        var ref=journal.appendLocated(event).get();index.register(ref,event,false);put(request,initial(request));publish();
    }
    @FunctionalInterface private interface Operation<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> submit(Operation<T> operation){
        CompletableFuture<T> result=new CompletableFuture<>();if(closed||failure!=null)return CompletableFuture.failedFuture(new IllegalStateException("grenade_outbox_unavailable",failure));
        try{worker.execute(()->{try{offOwner();result.complete(operation.run());}catch(Throwable error){failure=error;result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    /** Before marker overwrite, make its predecessor independently durable. */
    public CompletableFuture<Void> prepare(GrenadeReceipt previous){return submit(()->{if(previous!=null)record(previous);return null;});}
    public CompletableFuture<Void> committed(GrenadeReceipt receipt){return submit(()->{record(receipt);return null;});}
    /** One bounded save per owner poll; terminal receipt commits before delivery and world acknowledgement. */
    public CompletableFuture<Void> save(List<Grenades.Saved> states,List<UUID> terminal){
        return save(states,terminal,Map.of());
    }
    public CompletableFuture<Void> save(List<Grenades.Saved> states,List<UUID> terminal,Map<UUID,GrenadeAreaStore.Activation> pulseTargets){
        Map<UUID,GrenadeAreaStore.Activation> captured=Map.copyOf(pulseTargets);if(captured.size()>GrenadeAreaStore.LIMIT)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_pulse_snapshot_limit"));
        List<Grenades.Saved> detached=List.copyOf(states);List<UUID> finished=List.copyOf(terminal);
        if(detached.size()>Grenades.LIMIT||finished.size()>Grenades.LIMIT)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_snapshot_limit"));
        return submit(()->{
            JsonArray changed=new JsonArray();Set<UUID> seen=new HashSet<>();
            for(var s:detached){if(!seen.add(s.id()))throw new IllegalArgumentException("grenade_snapshot_duplicate");State before=active.get(s.id());if(before==null){if(index.raw(key(s.id(),true))!=null)continue;throw new IllegalArgumentException("grenade_snapshot_without_consumption");}validate(s);if(!before.saved().equals(s))changed.add(frame(s));}
            Map<UUID,Grenades.Saved> planned=new HashMap<>();detached.forEach(frame->planned.put(frame.id(),frame));JsonArray additions=new JsonArray();for(var entry:captured.entrySet()){var a=entry.getValue();if(!entry.getKey().equals(a.projectile()))throw new IllegalArgumentException("grenade_pulse_snapshot_key");validatePulse(a,planned.get(a.projectile()));if(!pulses.containsKey(a.projectile()))additions.add(a.json());}
            if(!changed.isEmpty()||!additions.isEmpty()){JsonObject event=new JsonObject();event.addProperty("type","grenade_snapshot");event.add("states",changed);if(!additions.isEmpty())event.add("pulses",additions);journal.append(event).get();for(var row:changed)update(frame(row.getAsJsonObject()));for(var raw:additions){var a=GrenadeAreaStore.Activation.read(raw.getAsJsonObject());putPulse(a,active.get(a.projectile()).saved());}saved++;publish();}

            seen.clear();for(UUID id:finished){if(!seen.add(id))throw new IllegalArgumentException("grenade_terminal_duplicate");if(index.raw(key(id,true))!=null)continue;State before=active.get(id);if(before==null||before.saved().reason()==null)throw new IllegalArgumentException("grenade_terminal_not_exploded");JsonObject event=new JsonObject();event.addProperty("type","grenade_terminal");event.addProperty("commandId",key(id,true));event.addProperty("id",id.toString());var ref=journal.appendLocated(event).get();index.register(ref,event,false);active.remove(id);pulses.remove(id);terminated++;publish();}
            return null;
        });
    }
    public boolean healthy(){return !closed&&failure==null&&journal.healthy()&&index.healthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"active",view.size(),"queued",worker.getQueue().size(),"snapshots",saved,"terminal",terminated,"repairedMarkers",repaired,"journal",journal.metrics(),"index",index.metrics());}
    @Override public void close()throws Exception{try{Workers.stop(worker);}finally{closed=true;try{journal.close();}finally{index.close();}}}
}
