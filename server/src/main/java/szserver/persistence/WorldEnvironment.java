package szserver.persistence;

import com.google.gson.*;
import szserver.net.EnvironmentCommands.*;
import szserver.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** One bounded durable environment owner. Transport/tick readers only see committed immutable state. */
public final class WorldEnvironment implements AutoCloseable {
    public record State(long worldTime,Weather weather,long weatherUntil,long revision,long eventSequence){
        public State{Objects.requireNonNull(weather);if(weatherUntil<0||revision<0||eventSequence<0)throw new IllegalArgumentException("invalid_environment_state");}
        public long dayTime(){return Math.floorMod(worldTime,24000L);}
    }
    private final ThreadPoolExecutor worker=Workers.pool("world-environment",1,32);
    private final Journal journal;
    private final WorldEventStore events;
    private final String identity;
    private final LongSupplier clock;
    private volatile EnvironmentClock timeAnchor;
    private record Publication(State state,EnvironmentClock clock,long weatherStarted){}
    private volatile Publication publication;
    private volatile long nextClockCheckpoint;
    private final AtomicBoolean expiryQueued=new AtomicBoolean();
    private final AtomicLong rejected=new AtomicLong();
    private volatile State visible=new State(0,Weather.AUTO,0,0,0);
    private volatile boolean failed,closed,hasRecords;
    private volatile String error="";
    private JsonObject lastRecord;private int writesSinceCompaction;private long compactions,compactionFailures;
    private volatile Map<String,Object> maintenance=Map.of("status","idle");
    public WorldEnvironment(Path data,String identity)throws Exception{this(data,identity,System::currentTimeMillis,0);}
    /** Synthetic deterministic clock/storage-delay injection; normal servers use the default constructor. */
    public WorldEnvironment(Path data,String identity,LongSupplier clock,long testDelay)throws Exception{this(data,identity,clock,testDelay,null);}
    public WorldEnvironment(Path data,String identity,LongSupplier clock,long testDelay,WorldEventStore events)throws Exception{
        this(data,identity,clock,testDelay,events,1);
    }
    public WorldEnvironment(Path data,String identity,LongSupplier clock,long testDelay,WorldEventStore events,double initialSpeed)throws Exception{
        this.events=events;
        if(identity==null||identity.isBlank())throw new IllegalArgumentException("world_identity_required");this.identity=identity;this.clock=clock;
        timeAnchor=new EnvironmentClock(0,clock.getAsLong(),0,initialSpeed);publication=new Publication(visible,timeAnchor,0);nextClockCheckpoint=clock.getAsLong()+60000;
        String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        journal=new Journal(data.resolve("environment-"+key+".sjournal"),testDelay,this::recover);
        try{if(events!=null)events.finishRecovery().get(10,TimeUnit.SECONDS);expire().get(10,TimeUnit.SECONDS);}catch(Exception failure){close();throw failure;}
    }
    private void recover(JsonObject event){if(!Json.text(event,"type",64).equals("environment")||!Json.text(event,"world",4096).equals(identity))throw new IllegalArgumentException("environment_journal_namespace");State state=read(event);if(!event.has("clockSpeed")&&state.weather()==Weather.CLEAR)state=new State(state.worldTime(),Weather.AUTO,0,state.revision(),state.eventSequence());boolean seed=event.has("seed")&&event.get("seed").getAsBoolean();boolean checkpoint=event.has("clockCheckpoint")&&event.get("clockCheckpoint").getAsBoolean();if(checkpoint?hasRecords&&(state.revision()!=visible.revision()||state.eventSequence()!=visible.eventSequence()):seed?hasRecords:state.revision()<=visible.revision()||state.eventSequence()<=visible.eventSequence())throw new IllegalArgumentException("environment_journal_order");if(events!=null)try{if(event.has("worldEvent"))events.reconcile(event,"environment");else events.adoptLegacyHead(state.revision(),state.eventSequence()).get();}catch(Exception error){throw new IllegalStateException("environment_world_event_recovery",error);}timeAnchor=new EnvironmentClock(state.worldTime(),event.has("clockWallMillis")?exact(event,"clockWallMillis"):clock.getAsLong(),event.has("clockFraction")?event.get("clockFraction").getAsDouble():0,event.has("clockSpeed")?event.get("clockSpeed").getAsDouble():timeAnchor.speed());visible=state;publication=new Publication(state,timeAnchor,event.has("weatherStarted")?exact(event,"weatherStarted"):timeAnchor.wallMillis());hasRecords=true;lastRecord=event.deepCopy();writesSinceCompaction=Math.min(256,writesSinceCompaction+1);}
    public static State read(JsonObject event){return new State(exact(event,"worldTime"),Weather.read(Json.text(event,event.has("nativeWeather")?"nativeWeather":"weather",32)),exact(event,event.has("nativeWeatherUntil")?"nativeWeatherUntil":"weatherUntil"),exact(event,"revision"),exact(event,"eventSequence"));}
    private static long exact(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("environment_integer");try{return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("environment_integer",error);}}
    public record Mutation(Long time,Long delta,Weather weather,int durationSeconds,Double clockSpeed){
        public Mutation(Long time,Long delta,Weather weather,int durationSeconds){this(time,delta,weather,durationSeconds,null);}
        public Mutation{if(time==null&&delta==null&&weather==null&&clockSpeed==null)throw new IllegalArgumentException("empty_world_environment_update");if(time!=null&&delta!=null)throw new IllegalArgumentException("conflicting_time_update");if(durationSeconds<1||durationSeconds>86400)throw new IllegalArgumentException("weather_duration_out_of_bounds");if(clockSpeed!=null&&(!Double.isFinite(clockSpeed)||clockSpeed<0||clockSpeed>10000))throw new IllegalArgumentException("invalid_time_speed");}
    }
    private JsonObject record(State next,EnvironmentClock anchor) {
        JsonObject event=Json.tree(Map.of("type","environment","world",identity,"worldTime",next.worldTime(),"weather",switch(next.weather()){case RAIN->"rain";case THUNDER->"thunder";default->"clear";},"weatherUntil",next.weather()==Weather.RAIN||next.weather()==Weather.THUNDER?next.weatherUntil():0,"revision",next.revision(),"eventSequence",next.eventSequence()));
        event.addProperty("nativeWeather",next.weather().wireName());event.addProperty("nativeWeatherUntil",next.weatherUntil());event.addProperty("weatherStarted",next.weather()==Weather.AUTO?0:next.weather()!=visible.weather()||next.weatherUntil()!=visible.weatherUntil()?anchor.wallMillis():publication.weatherStarted());event.addProperty("clockWallMillis",anchor.wallMillis());event.addProperty("clockFraction",anchor.fraction());event.addProperty("clockSpeed",anchor.speed());return event;
    }
    private void commit(State next,boolean seed,WorldEventStore.Ref reference)throws Exception {
        commit(next,seed,reference,new EnvironmentClock(next.worldTime(),clock.getAsLong(),0,timeAnchor.speed()));
    }
    private void commit(State next,boolean seed,WorldEventStore.Ref reference,EnvironmentClock anchor)throws Exception {
        JsonObject event=record(next,anchor);if(seed)event.addProperty("seed",true);if(reference!=null)event.add("worldEvent",reference.json());boolean durable=false;
        try {
            journal.append(event).get();durable=true;if(reference!=null)events.commit(reference).get();
            visible=next;timeAnchor=anchor;publication=new Publication(next,anchor,exact(event,"weatherStarted"));hasRecords=true;lastRecord=event.deepCopy();nextClockCheckpoint=clock.getAsLong()+60000;
            writesSinceCompaction=Math.min(256,writesSinceCompaction+1);if(writesSinceCompaction>=256)try{compactNow(true);}catch(Exception maintenanceError){if(!journal.healthy())throw maintenanceError;}
        } catch(Exception problem) {
            if(reference!=null&&!durable&&events.healthy())try{events.abort(reference).get();}catch(Exception cleanup){problem.addSuppressed(cleanup);}
            failed=true;error=problem.getClass().getSimpleName();throw problem;
        }
    }
    private void checkpointClock()throws Exception {
        long now=clock.getAsLong();EnvironmentClock anchor=timeAnchor.at(now);State current=visible;
        JsonObject event=record(new State(anchor.ticks(),current.weather(),current.weatherUntil(),current.revision(),current.eventSequence()),anchor);event.addProperty("clockCheckpoint",true);if(!hasRecords)event.addProperty("seed",true);
        if(lastRecord!=null&&lastRecord.has("worldEvent"))event.add("worldEvent",lastRecord.get("worldEvent").deepCopy());
        try{journal.append(event).get();}catch(Exception problem){failed=true;error=problem.getClass().getSimpleName();throw problem;}
        visible=read(event);timeAnchor=anchor;publication=new Publication(visible,anchor,exact(event,"weatherStarted"));lastRecord=event;hasRecords=true;nextClockCheckpoint=now+60000;
        writesSinceCompaction=Math.min(256,writesSinceCompaction+1);if(writesSinceCompaction>=256)compactNow(true);
    }
    private State changed(long time,Weather weather,long until,boolean timeChanged,boolean weatherChanged,boolean expired)throws Exception {
        EnvironmentClock current=timeAnchor.at(clock.getAsLong());
        return changed(time,weather,until,timeChanged,weatherChanged,expired,new EnvironmentClock(time,current.wallMillis(),timeChanged?0:current.fraction(),current.speed()));
    }
    private State changed(long time,Weather weather,long until,boolean timeChanged,boolean weatherChanged,boolean expired,EnvironmentClock anchor)throws Exception {
        WorldEventStore.Ref ref=null;State next;
        if(events!=null){JsonObject payload=Json.tree(Map.of("worldTime",time,"dayTime",Math.floorMod(time,24000L),"weather",weather.wireName(),"timeChanged",timeChanged,"weatherChanged",weatherChanged,"clockSpeed",anchor.speed()));if(expired)payload.addProperty("weatherExpired",true);ref=events.prepare("environment","world_environment_changed",payload).get();next=new State(time,weather,until,ref.revision(),ref.sequence());}
        else next=new State(time,weather,until,Math.addExact(visible.revision(),1),Math.addExact(visible.eventSequence(),1));
        commit(next,false,ref,anchor);return state();
    }
    private void expireNow()throws Exception{State current=visible;long now=clock.getAsLong();if(current.weatherUntil()>0&&now>=current.weatherUntil())changed(timeAnchor.at(now).ticks(),Weather.AUTO,0,false,true,true);}
    private interface Operation{State run()throws Exception;}
    private CompletableFuture<State> enqueue(Operation operation){CompletableFuture<State> future=new CompletableFuture<>();if(!healthy()||closed)return CompletableFuture.failedFuture(new IllegalStateException("environment_storage_failed_or_closed"));try{worker.execute(()->{try{if(!healthy())throw new IllegalStateException("environment_storage_failed");future.complete(operation.run());}catch(Exception failure){future.completeExceptionally(failure);}});}catch(RejectedExecutionException full){rejected.incrementAndGet();future.completeExceptionally(new RejectedExecutionException("environment_queue_full"));}return future;}
    public CompletableFuture<State> update(Request request){Objects.requireNonNull(request);return update(new Mutation(request.time(),request.delta(),request.weather(),request.durationSeconds(),request.clockSpeed()));}
    public CompletableFuture<State> update(Mutation request){Objects.requireNonNull(request);return enqueue(()->{
        expireNow();State old=visible;long now=clock.getAsLong();EnvironmentClock current=timeAnchor.at(now);long ticks=request.time()!=null?request.time():current.ticks();
        if(request.delta()!=null)try{ticks=Math.addExact(ticks,request.delta());}catch(ArithmeticException invalid){throw new IllegalArgumentException("time_overflow",invalid);}
        Weather weather=request.weather()==null?old.weather():request.weather();
        long until=request.weather()==null?old.weatherUntil():weather==Weather.AUTO?0:Math.addExact(now,request.durationSeconds()*1000L);
        EnvironmentClock anchor=new EnvironmentClock(ticks,now,request.time()!=null?0:current.fraction(),request.clockSpeed()==null?current.speed():request.clockSpeed());
        return changed(ticks,weather,until,request.time()!=null||request.delta()!=null||request.clockSpeed()!=null,request.weather()!=null,false,anchor);
    });}
    public CompletableFuture<State> expire(){return enqueue(()->{expireNow();return state();});}
    /** At most one expiry job; a later committed weather change invalidates an earlier timer. */
    public void pollExpiry(){State current=visible;long now=clock.getAsLong();if(!healthy()||closed||!(current.weatherUntil()>0&&now>=current.weatherUntil()||!hasRecords||now>=nextClockCheckpoint)||!expiryQueued.compareAndSet(false,true))return;
        enqueue(()->{expireNow();if(!hasRecords||clock.getAsLong()>=nextClockCheckpoint)checkpointClock();return state();}).whenComplete((state,failure)->expiryQueued.set(false));
    }
    public CompletableFuture<State> seed(State state){Objects.requireNonNull(state);return enqueue(()->{if(hasRecords)throw new IllegalArgumentException("environment_seed_requires_empty_store");commit(state,true,null);if(events!=null)events.adoptLegacyHead(state.revision(),state.eventSequence()).get();return state;});}
    public State state(){Publication p=publication;State current=p.state();long ticks=p.clock().at(clock.getAsLong()).ticks();return new State(ticks,current.weather(),current.weatherUntil(),events==null?current.revision():events.revision(),events==null?current.eventSequence():events.head());}
    public EnvironmentClock clockAnchor(){return publication.clock();}
    public long weatherStarted(){return publication.weatherStarted();}
    public double clockSpeed(){return publication.clock().speed();}
    public long settingsRevision(){return publication.state().revision();}
    public boolean healthy(){return !failed&&journal.healthy()&&(events==null||events.healthy());}
    private Map<String,Object> compactNow(boolean automatic)throws Exception{
        long started=System.nanoTime(),before=Files.size(journal.path());JsonObject captured=lastRecord==null?null:lastRecord.deepCopy();maintenance=Map.of("status","compacting","automatic",automatic);
        try{journal.compactStreaming(sink->{if(captured!=null)sink.accept(captured);}).get();writesSinceCompaction=0;compactions++;
            maintenance=Map.of("status","compacted","automatic",automatic,"beforeBytes",before,"afterBytes",Files.size(journal.path()),"retainedStates",captured==null?0:1,"durationMs",(System.nanoTime()-started)/1e6,"compactions",compactions,"failures",compactionFailures);
        }catch(Exception problem){compactionFailures++;writesSinceCompaction=0;maintenance=Map.of("status","compact_failed","automatic",automatic,"error",problem.getClass().getSimpleName(),"failures",compactionFailures);throw problem;}
        return maintenance;
    }
    /** Captures the latest full durable source record, including its world-event receipt, on its owner. */
    public CompletableFuture<Map<String,Object>> compact(){return enqueue(()->{compactNow(false);return visible;}).thenApply(ignored->maintenance).copy();}
    public Map<String,Object> maintenance(){return maintenance;}
    public Map<String,Object> metrics(){State state=state();return Map.ofEntries(Map.entry("healthy",healthy()),Map.entry("worldTime",state.worldTime()),Map.entry("clockSpeed",clockSpeed()),Map.entry("clockWallMillis",clockAnchor().wallMillis()),Map.entry("clockCheckpointSeconds",60),Map.entry("dayTime",state.dayTime()),Map.entry("weather",state.weather().wireName()),Map.entry("weatherUntil",state.weatherUntil()),Map.entry("revision",state.revision()),Map.entry("eventSequence",state.eventSequence()),Map.entry("queued",worker.getQueue().size()),Map.entry("expiryPending",expiryQueued.get()),Map.entry("rejected",rejected.get()),Map.entry("error",error),Map.entry("journal",journal.metrics()),Map.entry("maintenance",maintenance),Map.entry("compactEveryWrites",256));}
    @Override public void close()throws Exception{try{if(!closed&&healthy())enqueue(()->{checkpointClock();return state();}).get(10,TimeUnit.SECONDS);}finally{closed=true;try{Workers.stop(worker);}finally{journal.close();}}}
}
