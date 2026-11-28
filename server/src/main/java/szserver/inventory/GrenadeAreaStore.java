package szserver.inventory;

import com.google.gson.*;
import szserver.commands.CommandHistory;
import szserver.persistence.Journal;
import szserver.util.Workers;
import szserver.world.World;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Durable area lifecycle. Reservation precedes inventory debit; activation precedes projectile terminal.
 * Native gameplay membership/scene presentation belong to the caller; this store never fabricates them. */
public final class GrenadeAreaStore implements AutoCloseable {
    public static final int LIMIT=128;
    public record Activation(UUID projectile,UUID character,String owner,int item,World.Vec position,long started,List<GrenadeAreaProfile> profiles,szserver.net.AreaRelations.Actor ownerContext,Set<UUID> pulseTargets,Map<UUID,UUID> reviveTargets){
        public Activation(UUID projectile,UUID character,String owner,int item,World.Vec position,long started,List<GrenadeAreaProfile> profiles,szserver.net.AreaRelations.Actor ownerContext,Set<UUID> pulseTargets){this(projectile,character,owner,item,position,started,profiles,ownerContext,pulseTargets,null);}
        public Activation(UUID projectile,UUID character,String owner,int item,World.Vec position,long started,List<GrenadeAreaProfile> profiles,szserver.net.AreaRelations.Actor ownerContext){this(projectile,character,owner,item,position,started,profiles,ownerContext,null);}
        public Activation(UUID projectile,UUID character,String owner,int item,World.Vec position,long started,List<GrenadeAreaProfile> profiles){this(projectile,character,owner,item,position,started,profiles,null);}
        public Activation {
            Objects.requireNonNull(projectile);Objects.requireNonNull(character);GrenadeReceipt.vector(position,szserver.world.MapBounds.HORIZONTAL);
            if(reviveTargets!=null){if(reviveTargets.size()>32)throw new IllegalArgumentException("grenade_revive_targets_limit");reviveTargets=Map.copyOf(reviveTargets);}
            if(pulseTargets!=null){if(pulseTargets.size()>32)throw new IllegalArgumentException("grenade_pulse_targets_limit");pulseTargets=Set.copyOf(pulseTargets);}
            if(ownerContext!=null&&!ownerContext.character().equals(character))throw new IllegalArgumentException("grenade_area_owner_context");
            if(owner==null||owner.isBlank()||owner.length()>64||item<1||item>65535||started<0||started>Long.MAX_VALUE-600_000||profiles==null||profiles.isEmpty()||profiles.size()>16)throw new IllegalArgumentException("grenade_area_activation");profiles=List.copyOf(profiles);
        }
        public long until(){return started+profiles.stream().mapToLong(GrenadeAreaProfile::durationMillis).max().orElseThrow();}
        public List<GrenadeAreaProfile> active(long now){return profiles.stream().filter(p->now<started+p.durationMillis()).toList();}
        public JsonObject json(){JsonObject r=new JsonObject();r.addProperty("projectile",projectile.toString());r.addProperty("character",character.toString());r.addProperty("owner",owner);r.addProperty("item",item);r.add("position",GrenadeReceipt.vec(position));r.addProperty("started",started);JsonArray a=new JsonArray();profiles.forEach(p->a.add(p.json()));r.add("profiles",a);if(ownerContext!=null)r.add("ownerContext",ownerContext.json());if(pulseTargets!=null){JsonArray targets=new JsonArray();pulseTargets.stream().sorted().forEach(id->targets.add(id.toString()));r.add("pulseTargets",targets);}if(reviveTargets!=null){JsonObject targets=new JsonObject();reviveTargets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->targets.addProperty(e.getKey().toString(),e.getValue().toString()));r.add("reviveTargets",targets);}return r;}
        public static Activation read(JsonObject r){Set<String> fields=new HashSet<>(Set.of("projectile","character","owner","item","position","started","profiles"));if(r.has("ownerContext"))fields.add("ownerContext");if(r.has("pulseTargets"))fields.add("pulseTargets");if(r.has("reviveTargets"))fields.add("reviveTargets");if(!r.keySet().equals(fields))throw new IllegalArgumentException("grenade_area_activation_fields");JsonArray rows=r.getAsJsonArray("profiles");if(rows.size()>16)throw new IllegalArgumentException("grenade_area_count_budget");List<GrenadeAreaProfile> p=new ArrayList<>();rows.forEach(v->p.add(GrenadeAreaProfile.read(v.getAsJsonObject())));return new Activation(UUID.fromString(r.get("projectile").getAsString()),UUID.fromString(r.get("character").getAsString()),r.get("owner").getAsString(),Math.toIntExact(GrenadeReceipt.integer(r,"item")),GrenadeReceipt.vec(r.getAsJsonArray("position")),GrenadeReceipt.integer(r,"started"),p,r.has("ownerContext")?szserver.net.AreaRelations.Actor.read(r.getAsJsonObject("ownerContext")):null,targets(r),reviveTargets(r));}
        private static Set<UUID> targets(JsonObject root){if(!root.has("pulseTargets"))return null;JsonArray rows=root.getAsJsonArray("pulseTargets");if(rows.size()>32)throw new IllegalArgumentException("grenade_pulse_targets_limit");Set<UUID> ids=new HashSet<>();for(var raw:rows)if(!ids.add(UUID.fromString(raw.getAsString())))throw new IllegalArgumentException("grenade_pulse_targets_duplicate");return Set.copyOf(ids);}
        private static Map<UUID,UUID> reviveTargets(JsonObject root){if(!root.has("reviveTargets"))return null;JsonObject rows=root.getAsJsonObject("reviveTargets");if(rows.size()>32)throw new IllegalArgumentException("grenade_revive_targets_limit");Map<UUID,UUID> out=new HashMap<>();for(var row:rows.entrySet())if(out.put(UUID.fromString(row.getKey()),UUID.fromString(row.getValue().getAsString()))!=null)throw new IllegalArgumentException("grenade_revive_target_duplicate");return Map.copyOf(out);}
        private boolean sameUse(Activation r){return projectile.equals(r.projectile)&&character.equals(r.character)&&owner.equals(r.owner)&&item==r.item&&position.equals(r.position)&&profiles.equals(r.profiles)&&Objects.equals(ownerContext,r.ownerContext)&&Objects.equals(pulseTargets,r.pulseTargets)&&Objects.equals(reviveTargets,r.reviveTargets);}
    }
    private final Journal journal;private final CommandHistory index;private final SessionReceipts session;private final ThreadPoolExecutor worker=Workers.pool("grenade-areas",1,32);
    private final Map<UUID,Activation> active=new LinkedHashMap<>();private final Map<UUID,Integer> reserved=new HashMap<>();private final Map<UUID,Float> shields=new HashMap<>();private volatile Map<UUID,Float> shieldView=Map.of();private volatile long shieldHits,shieldDestroyed;
    private volatile List<Activation> view=List.of();private volatile Throwable failure;private volatile boolean closed,workerStarted;
    private volatile int slots,reservations;private volatile long activated,expired;
    private static void offOwner(){if(Set.of("native-game-owner","world-tick").contains(Thread.currentThread().getName()))throw new IllegalStateException("grenade_area_io_on_owner");}
    public GrenadeAreaStore(Path data,String worldIdentity)throws Exception{
        session=null;
        offOwner();if(worldIdentity==null||worldIdentity.isBlank()||worldIdentity.length()>4096)throw new IllegalArgumentException("grenade_area_world_identity");
        journal=Journal.streaming(data.resolve("grenade-areas.sjournal"));CommandHistory opened=null;boolean[] bound={false};
        try{opened=new CommandHistory(journal,1024*1024);index=opened;journal.replayLocated((ref,event)->{
            String type=event.get("type").getAsString();if(!bound[0]&&!type.equals("grenade_area_world"))throw new IllegalArgumentException("grenade_area_world_binding_missing");
            switch(type){
                case "grenade_area_world"->{if(bound[0]||!worldIdentity.equals(event.get("identity").getAsString()))throw new IllegalArgumentException("grenade_area_world_mismatch");bound[0]=true;}
                case "grenade_area_activation"->{Activation r=activation(event);if(active.putIfAbsent(r.projectile(),r)!=null)throw new IllegalArgumentException("grenade_area_duplicate_activation");index.register(ref,event,false);activated++;}
                case "grenade_area_terminal"->{UUID id=terminal(event);if(active.remove(id)==null)throw new IllegalArgumentException("grenade_area_terminal_without_activation");index.register(ref,event,false);expired++;}
                case "grenade_shield_hit"->{readShieldHit(event,true);index.register(ref,event,false);}
                default->throw new IllegalArgumentException("grenade_area_event");
            }publish();
        }).get();if(!bound[0]){JsonObject r=new JsonObject();r.addProperty("type","grenade_area_world");r.addProperty("identity",worldIdentity);journal.append(r).get();}publish();}catch(Exception|Error error){worker.shutdownNow();if(opened!=null)opened.close();journal.close();throw error;}
    }
    private GrenadeAreaStore(){journal=null;index=null;session=new SessionReceipts();}
    public static GrenadeAreaStore inMemory(){return new GrenadeAreaStore();}
    private JsonObject findEvent(String id){return session==null?index.raw(id):session.find(id);}
    private void recordEvent(JsonObject event)throws Exception{
        if(session!=null)session.record(event);
        else index.register(journal.appendLocated(event).get(),event,false);
    }
    private Map<String,Object> journalMetrics(){return session==null?journal.metrics():Map.of("storage","memory");}
    private Map<String,Object> indexMetrics(){return session==null?index.metrics():session.metrics();}
    private boolean storageHealthy(){return session!=null||journal.healthy()&&index.healthy();}
    private static String key(UUID id,boolean terminal){return (terminal?"ax:":"a:")+id;}
    private static Activation activation(JsonObject event){Activation r=Activation.read(event.getAsJsonObject("activation"));if(!key(r.projectile(),false).equals(event.get("commandId").getAsString()))throw new IllegalArgumentException("grenade_area_activation_key");return r;}
    private static UUID terminal(JsonObject event){UUID id=UUID.fromString(event.get("id").getAsString());if(!key(id,true).equals(event.get("commandId").getAsString()))throw new IllegalArgumentException("grenade_area_terminal_key");return id;}
    private void publish(){slots=active.values().stream().mapToInt(r->r.profiles().size()).sum()+reserved.values().stream().mapToInt(Integer::intValue).sum();if(slots>LIMIT)throw new IllegalArgumentException("grenade_area_live_limit");reservations=reserved.size();view=List.copyOf(active.values());shields.keySet().retainAll(active.keySet());shieldView=Map.copyOf(shields);}
    public record ShieldHit(UUID projectile,float before,float after,boolean blocked,boolean applied){}
    private static String shieldKey(UUID id,UUID request){return "sh:"+Base64.getUrlEncoder().withoutPadding().encodeToString(java.nio.ByteBuffer.allocate(32).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(request.getMostSignificantBits()).putLong(request.getLeastSignificantBits()).array());}
    private static float shieldMaximum(Activation a){return a!=null&&a.profiles().size()==1?a.profiles().get(0).shield():0;}
    public float shieldRemaining(UUID id){Float value=shieldView.get(id);if(value!=null)return value;for(var a:view)if(a.projectile().equals(id))return shieldMaximum(a);return 0;}
    private ShieldHit readShieldHit(JsonObject event,boolean apply){
        if(!event.keySet().equals(Set.of("type","commandId","projectile","request","damage","before","after")))throw new IllegalArgumentException("grenade_shield_hit_fields");UUID id=UUID.fromString(event.get("projectile").getAsString()),request=UUID.fromString(event.get("request").getAsString());if(!event.get("commandId").getAsString().equals(shieldKey(id,request)))throw new IllegalArgumentException("grenade_shield_hit_key");float damage=event.get("damage").getAsFloat(),before=event.get("before").getAsFloat(),after=event.get("after").getAsFloat();if(!Float.isFinite(damage)||damage<=0||damage>100000||!Float.isFinite(before)||before<=0||!Float.isFinite(after)||after!=Math.max(0,before-damage))throw new IllegalArgumentException("grenade_shield_hit_value");
        if(apply){Activation source=active.get(id);float maximum=shieldMaximum(source),current=shields.getOrDefault(id,maximum);if(maximum<=0||before!=current)throw new IllegalArgumentException("grenade_shield_hit_order");shields.put(id,after);shieldHits++;if(after==0){active.remove(id);shields.remove(id);shieldDestroyed++;}}
        return new ShieldHit(id,before,after,true,false);
    }
    /** The scene's shared durability is committed before publication; duplicate shot keys never pay twice. */
    public CompletableFuture<ShieldHit> hitShield(UUID id,UUID request,float damage,long now){
        if(id==null||request==null||!Float.isFinite(damage)||damage<=0||damage>100000||now<0)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_shield_hit_request"));
        return submit(()->{String key=shieldKey(id,request);JsonObject previous=findEvent(key);if(previous!=null){if(previous.get("damage").getAsFloat()!=damage)throw new IllegalArgumentException("grenade_shield_hit_conflict");return readShieldHit(previous,false);}Activation source=active.get(id);float before=shields.getOrDefault(id,shieldMaximum(source));if(source==null||now>=source.until()||before<=0)return new ShieldHit(id,0,0,false,false);
            JsonObject event=new JsonObject();event.addProperty("type","grenade_shield_hit");event.addProperty("commandId",key);event.addProperty("projectile",id.toString());event.addProperty("request",request.toString());event.addProperty("damage",damage);event.addProperty("before",before);event.addProperty("after",Math.max(0,before-damage));recordEvent(event);var result=readShieldHit(event,true);publish();return new ShieldHit(id,result.before(),result.after(),true,true);
        });
    }
    public Map<String,Object> shieldMetrics(){return Map.of("hits",shieldHits,"destroyed",shieldDestroyed,"damaged",shieldView.size());}
    @FunctionalInterface private interface Operation<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> submit(Operation<T> operation){if(closed||failure!=null)return CompletableFuture.failedFuture(new IllegalStateException("grenade_area_store_unavailable",failure));workerStarted=true;CompletableFuture<T> result=new CompletableFuture<>();try{worker.execute(()->{try{offOwner();result.complete(operation.run());}catch(Throwable error){failure=error;result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;}
    /** Bootstrap only: a durable debit owns its slots until completion, even after a crash. */
    public void restoreReservation(UUID id,int count)throws Exception{
        offOwner();if(closed||failure!=null||workerStarted||id==null||count<1||count>16)throw new IllegalStateException("grenade_area_restore_reservation");
        if(findEvent(key(id,false))!=null)return;
        Integer previous=reserved.get(id);if(previous!=null){if(previous!=count)throw new IllegalArgumentException("grenade_area_reservation_conflict");return;}
        if(slots+count>LIMIT)throw new IllegalArgumentException("grenade_area_recovery_capacity");reserved.put(id,count);publish();
    }
    /** Returns false at capacity before an item is spent; duplicate reservation is idempotent. */
    public CompletableFuture<Boolean> reserve(UUID id,int count){if(id==null||count<1||count>16)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_area_reservation"));return submit(()->{Integer before=reserved.get(id);if(before!=null){if(before!=count)throw new IllegalArgumentException("grenade_area_reservation_conflict");return true;}if(findEvent(key(id,false))!=null)return false;if(slots+count>LIMIT)return false;reserved.put(id,count);publish();return true;});}
    public CompletableFuture<Void> cancel(UUID id){return submit(()->{reserved.remove(id);publish();return null;});}
    private Optional<Activation> record(Activation request,boolean repair)throws Exception{
        offOwner();JsonObject existing=findEvent(key(request.projectile(),false));
        if(existing!=null){Activation previous=activation(existing);if(!previous.sameUse(request))throw new IllegalArgumentException("grenade_area_use_conflict");reserved.remove(request.projectile());publish();return Optional.ofNullable(active.get(request.projectile()));}
        Integer count=reserved.get(request.projectile());if(count==null){if(!repair)throw new IllegalArgumentException("grenade_area_unreserved");if(slots+request.profiles().size()>LIMIT)throw new IllegalArgumentException("grenade_area_repair_capacity");}else if(count!=request.profiles().size())throw new IllegalArgumentException("grenade_area_reserved_count");
        JsonObject event=new JsonObject();event.addProperty("type","grenade_area_activation");event.addProperty("commandId",key(request.projectile(),false));event.add("activation",request.json());recordEvent(event);reserved.remove(request.projectile());active.put(request.projectile(),request);activated++;publish();return Optional.of(request);
    }
    /** Retry retains the first durable clock. An already-terminal zone is never restarted. */
    public CompletableFuture<Optional<Activation>> activate(Activation request){return submit(()->record(request,false));}
    /** Bootstrap only: an inventory/projectile completion proves the item was already spent. */
    public void repair(Activation request)throws Exception{offOwner();if(closed||failure!=null||workerStarted)throw new IllegalStateException("grenade_area_repair_after_start");record(request,true);}
    /** Expiry advances while the server is offline; callers await this before publishing startup zones. */
    public CompletableFuture<List<Activation>> expire(long now){if(now<0)return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_area_clock"));return submit(()->{for(Activation r:new ArrayList<>(active.values()))if(now>=r.until()){JsonObject event=new JsonObject();event.addProperty("type","grenade_area_terminal");event.addProperty("commandId",key(r.projectile(),true));event.addProperty("id",r.projectile().toString());recordEvent(event);active.remove(r.projectile());expired++;publish();}return view;});}
    public List<Activation> states(){return view;}
    public boolean healthy(){return !closed&&failure==null&&storageHealthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"active",view.size(),"slots",slots,"reservations",reservations,"queued",worker.getQueue().size(),"activated",activated,"expired",expired,"journal",journalMetrics(),"index",indexMetrics());}
    @Override public void close()throws Exception{try{Workers.stop(worker);}finally{closed=true;if(session==null)try{journal.close();}finally{index.close();}}}
}
