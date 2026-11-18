package szserver.persistence;

import szserver.commands.CommandService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Polls immutable progress only. All capture, hashing and fsync stay on the existing command/storage workers. */
public final class AutomaticCheckpoint implements AutoCloseable {
    public record Settings(boolean enabled,long intervalSeconds,long minimumGapSeconds,long recordThreshold){
        public Settings{if(intervalSeconds<1||intervalSeconds>86400||minimumGapSeconds<1||minimumGapSeconds>intervalSeconds||recordThreshold<1||recordThreshold>1_000_000)throw new IllegalArgumentException("invalid_automatic_checkpoint_settings");}
        public static Settings configured(Properties p){String enabled=p.getProperty("world.checkpoint.enabled","true").strip();if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException("invalid_automatic_checkpoint_enabled");return new Settings(Boolean.parseBoolean(enabled),Long.parseLong(p.getProperty("world.checkpoint.intervalSeconds","300")),Long.parseLong(p.getProperty("world.checkpoint.minimumGapSeconds","30")),Long.parseLong(p.getProperty("world.checkpoint.recordThreshold","1024")));}
    }
    public record Progress(long applied,long saved,boolean available,boolean healthy,int pending,boolean nativeBaseDirty,boolean actorStateDirty){public Progress(long applied,long saved,boolean available,boolean healthy,int pending){this(applied,saved,available,healthy,pending,false,false);}public Progress(long applied,long saved,boolean available,boolean healthy,int pending,boolean nativeBaseDirty){this(applied,saved,available,healthy,pending,nativeBaseDirty,false);}public Progress{if(applied<0||saved<0||pending<0)throw new IllegalArgumentException("checkpoint_progress_bounds");}}
    public interface Source{Progress progress();CompletableFuture<Map<String,Object>> save();}
    private final Settings settings;private final Source source;private final LongSupplier clock;private final ScheduledExecutorService scheduler;
    private CompletableFuture<Map<String,Object>> flight;private long lastAttempt,attempts,saved,unchanged,deferrals,failures;private boolean closed,started;private String error="",state="idle";
    private volatile Map<String,Object> view=Map.of();
    public AutomaticCheckpoint(CommandService commands,Settings settings){this(commands,settings,true);}
    public AutomaticCheckpoint(CommandService commands,Settings settings,boolean autoStart){this(new Source(){public Progress progress(){return commands.checkpointProgress();}public CompletableFuture<Map<String,Object>> save(){return commands.checkpointAutomatic();}},settings,System::nanoTime,true,autoStart);}
    /** Deterministic policy checks use real storage with a manually advanced clock; no sleeps or extra scheduler. */
    public AutomaticCheckpoint(Source source,Settings settings,LongSupplier clock,boolean scheduled){this(source,settings,clock,scheduled,true);}
    public AutomaticCheckpoint(Source source,Settings settings,LongSupplier clock,boolean scheduled,boolean autoStart){this.source=Objects.requireNonNull(source);this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);lastAttempt=clock.getAsLong();scheduler=scheduled&&settings.enabled()?Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"world-checkpoint-policy");t.setDaemon(true);return t;}):null;publish(source.progress());if(autoStart)start();}
    public synchronized void start(){if(closed||started)throw new IllegalStateException("checkpoint_policy_already_started_or_closed");lastAttempt=clock.getAsLong();if(scheduler!=null)scheduler.scheduleWithFixedDelay(this::pollSafely,1,1,TimeUnit.SECONDS);started=true;publish(source.progress());}
    private void pollSafely(){try{poll();}catch(RuntimeException e){synchronized(this){failures++;error=e.getClass().getSimpleName();state="policy_failed";publish(source.progress());}}}
    public synchronized void poll(){
        if(closed)return;Progress progress=source.progress();long now=clock.getAsLong();
        if(flight!=null){
            if(!flight.isDone()){state="saving";publish(progress);return;}
            try{Map<String,Object> result=flight.join();if("unchanged".equals(result.get("status")))unchanged++;else saved++;error="";state="saved";}
            catch(CompletionException|CancellationException e){Throwable cause=e instanceof CompletionException&&e.getCause()!=null?e.getCause():e;if(cause instanceof RejectedExecutionException){deferrals++;state="deferred";}else{failures++;error=cause.getClass().getSimpleName();state="save_failed";}}
            flight=null;lastAttempt=now;progress=source.progress();
        }
        if(!settings.enabled()){state="disabled";publish(progress);return;}
        if(!progress.available()||!progress.healthy()){state=progress.healthy()?"base_unavailable":"store_unhealthy";publish(progress);return;}
        long changed=Math.max(0,progress.applied()-progress.saved()),elapsed=now-lastAttempt;
        if(changed==0&&!progress.nativeBaseDirty()&&!progress.actorStateDirty()){state="idle";publish(progress);return;}
        if(progress.pending()>0){state="manual_save_pending";publish(progress);return;}
        if(elapsed<TimeUnit.SECONDS.toNanos(settings.minimumGapSeconds())||elapsed<TimeUnit.SECONDS.toNanos(settings.intervalSeconds())&&changed<settings.recordThreshold()){state="waiting";publish(progress);return;}
        lastAttempt=now;attempts++;
        try{flight=source.save();state="saving";}catch(RejectedExecutionException e){deferrals++;state="deferred";}catch(RuntimeException e){failures++;error=e.getClass().getSimpleName();state="save_failed";}
        publish(source.progress());
    }
    private void publish(Progress p){view=Map.ofEntries(Map.entry("enabled",settings.enabled()),Map.entry("status",closed?"stopped":state),Map.entry("intervalSeconds",settings.intervalSeconds()),Map.entry("minimumGapSeconds",settings.minimumGapSeconds()),Map.entry("recordThreshold",settings.recordThreshold()),Map.entry("appliedRecords",p.applied()),Map.entry("savedRecords",p.saved()),Map.entry("changedRecords",Math.max(0,p.applied()-p.saved())),Map.entry("nativeBaseDirty",p.nativeBaseDirty()),Map.entry("actorStateDirty",p.actorStateDirty()),Map.entry("pending",flight!=null),Map.entry("attempts",attempts),Map.entry("saves",saved),Map.entry("unchanged",unchanged),Map.entry("deferrals",deferrals),Map.entry("failures",failures),Map.entry("error",error));}
    public Map<String,Object> metrics(){return view;}
    @Override public void close(){synchronized(this){if(closed)return;closed=true;publish(source.progress());}if(scheduler!=null){scheduler.shutdownNow();try{if(!scheduler.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("checkpoint_policy_shutdown_timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("checkpoint_policy_shutdown_interrupted",e);}}}
}
