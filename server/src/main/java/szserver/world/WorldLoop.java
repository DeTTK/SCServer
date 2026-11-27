package szserver.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.lang.management.ManagementFactory;

/** Fixed simulation steps; owner jobs wake independently within a per-step budget. No catch-up burst. */
public final class WorldLoop implements AutoCloseable {
    /** Construct on this owner, tick without IO, and asynchronously drain before releasing the world. */
    public interface OwnerService {
        void tick(Map<UUID,World.Vec> players,long now);
        CompletableFuture<Void> shutdown();
        Map<String,Object> metrics();
        default boolean healthy(){return true;}
    }
    private Function<World,OwnerService> ownerServiceFactory;
    private volatile OwnerService ownerService;
    /** Published adapter reference only; all native mutations still enforce the world owner. */
    public ResidentMapRuntime residentMap(){return ownerService instanceof ResidentMapRuntime runtime?runtime:null;}
    public void installResidentTerrain(ResidentMapRuntime runtime){if(Thread.currentThread()!=thread)throw new IllegalStateException("resident_terrain_wrong_owner");world.residentTerrain(runtime);}
    private final CompletableFuture<Void> serviceReady=new CompletableFuture<>();
    private volatile Map<String,Object> serviceMetrics=Map.of();
    private volatile boolean serviceHealthy=true;
    private volatile Throwable shutdownFailure;
    public void ownerService(Function<World,OwnerService> factory){synchronized(lifecycle){if(closed||thread.getState()!=Thread.State.NEW||ownerServiceFactory!=null)throw new IllegalStateException("world_owner_service_late_configuration");ownerServiceFactory=Objects.requireNonNull(factory);}}
    public CompletableFuture<Void> ownerServiceReady(){return serviceReady.copy();}
    public Map<String,Object> ownerServiceMetrics(){return serviceMetrics;}
    private record Task<T>(Function<World,T> action,CompletableFuture<T> future) {}
    private volatile World world;
    private final ArrayBlockingQueue<Task<?>> queue=new ArrayBlockingQueue<>(256);
    private final Thread thread;
    private final Object lifecycle=new Object();private volatile boolean running,closed;
    private volatile boolean simulation=true;
    private volatile World.Snapshot snapshot;
    private volatile Map<String,Object> actorMetrics;
    private record Players(Map<UUID,World.Vec> positions,long received){}
    private volatile Players players=new Players(Map.of(),0);
    /** Network owner replaces the latest positions; slow simulation never accumulates pose jobs. */
    public void publishPlayers(Map<UUID,World.Vec> positions){players=new Players(Map.copyOf(positions),System.nanoTime());}
    private record MinePlayers(Map<UUID,ActorMinePlayer> values,long received){}
    private volatile MinePlayers minePlayers=new MinePlayers(Map.of(),0);
    public void publishMinePlayers(Map<UUID,ActorMinePlayer> values){minePlayers=new MinePlayers(Map.copyOf(values),System.nanoTime());}
    private volatile long skipped,maxTickNanos,failures,ownerTasks,maxOwnerWorkNanos;private volatile int maxOwnerTasksPerTick;
    private volatile long maxGapNanos,maxLateNanos,maxParkNanos,maxPhysicsNanos,maxTaskNanos;
    private final ArrayDeque<Map<String,Object>> missedSteps=new ArrayDeque<>();private volatile List<Map<String,Object>> missedView=List.of();
    public WorldLoop(World world){this.world=world;snapshot=world.snapshot();actorMetrics=world.actorMetrics();thread=new Thread(this::run,"world-tick");thread.setDaemon(true);}
    public void start(){synchronized(lifecycle){if(closed||thread.getState()!=Thread.State.NEW)throw new IllegalStateException("world_already_started_or_closed");world.finishActorBootstrap();running=true;thread.start();}}
    /** Load native geometry and prepare restored actors on the real owner before advancing simulation. */
    public void startPrepared(){synchronized(lifecycle){if(closed||thread.getState()!=Thread.State.NEW)throw new IllegalStateException("world_already_started_or_closed");simulation=false;start();}}
    public void resumeSimulation(){if(!running||simulation)throw new IllegalStateException("world_not_preparing");simulation=true;LockSupport.unpark(thread);}
    public <T> T bootstrap(Function<World,T> action){
        synchronized(lifecycle){
            if(closed||thread.getState()!=Thread.State.NEW)throw new IllegalStateException("bootstrap_after_start_or_close");
            try{T result=action.apply(world);snapshot=world.snapshot();return result;}
            finally{world.endActorBootstrapAction();}
        }
    }
    public <T> CompletableFuture<T> submit(Function<World,T> action){
        CompletableFuture<T> future=new CompletableFuture<>();Task<T> task=new Task<>(action,future);
        if(!running||!queue.offer(task))future.completeExceptionally(new RejectedExecutionException("world_queue_full_or_stopped"));
        else if(!running&&queue.remove(task))future.completeExceptionally(new IllegalStateException("world_stopped"));
        else LockSupport.unpark(thread);
        return future;
    }
    public World.Snapshot snapshot(){return snapshot;}
    /** Immutable configured adapter, also needed by map workers before their catalogue starts. */
    public ActorMotion actorMotion(){return world.actorMotion();}
    /** Worker-only preparation through the immutable configured engine; this does not read live entity state. */
    public ActorControllerEngine.PreparedEquipment prepareEquipment(ActorEquipment settings){return world.prepareEquipment(settings);}
    public ActorControllerEngine.PreparedMedicine prepareMedicine(int item){return world.prepareMedicine(item);}
    public boolean beforeStart(){return !closed&&thread.getState()==Thread.State.NEW;}
    /** Only before simulation/listeners start;publish the complete detached durable bundle. */
    public void replaceBootstrap(World restored){synchronized(lifecycle){if(closed||thread.getState()!=Thread.State.NEW||!queue.isEmpty())throw new IllegalStateException("bootstrap_after_start_or_close");world=Objects.requireNonNull(restored);snapshot=world.snapshot();world.endActorBootstrapAction();}}
    /** Acquire the current owner-published portal generation without queued work or snapshot delay. */
    public long portalEpoch(){return world.portalEpoch();}
    public BlockOverlays overlayVersions(){return world.overlayVersions();}
    private <T> void execute(Task<T> task){try{T value=task.action().apply(world);snapshot=world.snapshot();task.future().complete(value);}catch(Throwable e){task.future().completeExceptionally(e);}}
    private void run(){
        try{if(ownerServiceFactory!=null)ownerService=Objects.requireNonNull(ownerServiceFactory.apply(world));serviceReady.complete(null);runTicks();}
        catch(Throwable failure){failures++;running=false;serviceReady.completeExceptionally(failure);shutdownFailure=failure;System.err.println("World owner service failed: "+failure);failure.printStackTrace(System.err);}
        finally{
            Task<?> task;while((task=queue.poll())!=null)task.future.completeExceptionally(new IllegalStateException("world_stopped"));
            if(ownerService!=null)try{
                CompletableFuture<Void> drained=ownerService.shutdown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!drained.isDone()){ownerService.tick(Map.of(),System.nanoTime());serviceMetrics=ownerService.metrics();if(System.nanoTime()>=deadline)throw new IllegalStateException("world_owner_service_shutdown_timeout");LockSupport.parkNanos(1_000_000);}
                drained.join();serviceMetrics=ownerService.metrics();
            }catch(Throwable failure){shutdownFailure=failure;System.err.println("World owner service shutdown failed: "+failure);}
        }
    }
    private void runTicks(){long deadline=System.nanoTime();final long interval=50_000_000;long workNanos=0,previousStep=0,previousPark=0,parkOvershoot=0;int workCount=0;
        var threads=ManagementFactory.getThreadMXBean();
        boolean measureCpu=threads.isCurrentThreadCpuTimeSupported()&&threads.isThreadCpuTimeEnabled();
        while(running){
            long begin=System.nanoTime();
            if(begin>=deadline){
                long gap=previousStep==0?0:begin-previousStep,late=begin-deadline;previousStep=begin;maxGapNanos=Math.max(maxGapNanos,gap);maxLateNanos=Math.max(maxLateNanos,late);
                long cpuBegin=measureCpu?threads.getCurrentThreadCpuTime():-1;
                long residentEnd,simulationEnd;
                try{
                    Players current=players;
                    Map<UUID,World.Vec> positions=begin-current.received()<2_000_000_000L?current.positions():Map.of();
                    if(ownerService!=null){ownerService.tick(positions,begin);serviceMetrics=ownerService.metrics();serviceHealthy=ownerService.healthy();}
                    residentEnd=System.nanoTime();
                    world.players(positions);
                    MinePlayers contacts=minePlayers;
                    world.minePlayers(begin-contacts.received()<2_000_000_000L?contacts.values():Map.of());
                    if(simulation)world.step();
                    simulationEnd=System.nanoTime();
                    snapshot=world.snapshot();actorMetrics=world.actorMetrics();
                }catch(Throwable e){failures++;running=false;shutdownFailure=e;System.err.println("World simulation failed: "+e);e.printStackTrace(System.err);return;}
                long end=System.nanoTime(),physics=end-begin,owned=workNanos;int jobs=workCount;maxPhysicsNanos=Math.max(maxPhysicsNanos,physics);maxTickNanos=Math.max(maxTickNanos,workNanos+physics);maxOwnerWorkNanos=Math.max(maxOwnerWorkNanos,workNanos);maxOwnerTasksPerTick=Math.max(maxOwnerTasksPerTick,workCount);workNanos=0;workCount=0;deadline+=interval;
                if(end>deadline){
                    long missed=(end-deadline)/interval+1;skipped+=missed;deadline+=missed*interval;
                    long cpuEnd=cpuBegin>=0?threads.getCurrentThreadCpuTime():-1;
                    var timing=new LinkedHashMap<String,Object>();
                    timing.put("tick",snapshot.tick());timing.put("skipped",missed);
                    timing.put("gapMs",gap/1e6);timing.put("lateMs",late/1e6);
                    timing.put("physicsMs",physics/1e6);timing.put("ownerMs",owned/1e6);
                    timing.put("ownerTasks",jobs);timing.put("previousParkMs",previousPark/1e6);
                    timing.put("parkOvershootMs",parkOvershoot/1e6);
                    timing.put("queued",queue.size());
                    timing.put("residentMs",(residentEnd-begin)/1e6);
                    timing.put("simulationMs",(simulationEnd-residentEnd)/1e6);
                    timing.put("publishMs",(end-simulationEnd)/1e6);
                    if(cpuBegin>=0&&cpuEnd>=cpuBegin)timing.put("cpuMs",(cpuEnd-cpuBegin)/1e6);
                    if(simulation)timing.put("simulationPhases",world.stepTimings());
                    if(ownerService instanceof ResidentMapRuntime resident)timing.put("residentPhases",resident.tickTimings());
                    if(missedSteps.size()==8)missedSteps.removeFirst();missedSteps.addLast(Collections.unmodifiableMap(timing));missedView=List.copyOf(missedSteps);
                }
                previousPark=0;parkOvershoot=0;
                continue;
            }
            // New RPCs may run while physics waits for its next fixed step. The budget
            // spans ALL wakeups in this interval, so RPC traffic cannot buy extra work.
            if(workCount<64&&workNanos<5_000_000){long workBegin=begin;int count=0;
                while(running&&workCount+count<64&&workNanos+System.nanoTime()-workBegin<5_000_000&&System.nanoTime()<deadline){Task<?> task=queue.poll();if(task==null)break;long taskBegin=System.nanoTime();execute(task);maxTaskNanos=Math.max(maxTaskNanos,System.nanoTime()-taskBegin);count++;}
                if(count>0){workNanos+=System.nanoTime()-workBegin;workCount+=count;ownerTasks+=count;}
            }
            long parkBegin=System.nanoTime(),sleep=deadline-parkBegin;if(sleep>0){LockSupport.parkNanos(sleep);previousPark=System.nanoTime()-parkBegin;parkOvershoot+=Math.max(0,previousPark-sleep);maxParkNanos=Math.max(maxParkNanos,previousPark);}
        }
        Task<?> task;while((task=queue.poll())!=null)task.future.completeExceptionally(new IllegalStateException("world_stopped"));
    }
    public Map<String,Object> metrics(){World.Snapshot published=snapshot;return Map.ofEntries(Map.entry("residentMap",serviceMetrics),Map.entry("ownerServiceHealthy",serviceHealthy),Map.entry("tickTiming",Map.of("maxGapMs",maxGapNanos/1e6,"maxLateMs",maxLateNanos/1e6,"maxParkMs",maxParkNanos/1e6,"maxPhysicsMs",maxPhysicsNanos/1e6,"maxTaskMs",maxTaskNanos/1e6,"missedSteps",missedView)),Map.entry("actorSimulation",actorMetrics),Map.entry("grenadeGeometry",world.grenadeGeometryMetrics()),Map.entry("tileStates",world.overlayVersions().tileMetrics()),Map.entry("voxelLimit",world.mapLimits().voxels()),Map.entry("heapMaxBytes",Runtime.getRuntime().maxMemory()),Map.entry("tick",published.tick()),Map.entry("hz",20),Map.entry("maxTickMs",maxTickNanos/1e6),Map.entry("skippedSlots",skipped),Map.entry("failures",failures),Map.entry("queued",queue.size()),Map.entry("running",running),Map.entry("projectiles",published.projectiles()),Map.entry("pendingExplosions",published.pendingExplosions()),Map.entry("ownerTasks",ownerTasks),Map.entry("maxOwnerWorkMs",maxOwnerWorkNanos/1e6),Map.entry("maxOwnerTasksPerTick",maxOwnerTasksPerTick));}
    @Override public void close()throws InterruptedException{synchronized(lifecycle){closed=true;running=false;}LockSupport.unpark(thread);thread.join(ownerServiceFactory==null?2000:35_000);if(thread.isAlive())throw new IllegalStateException("world_shutdown_timeout");world.releaseActorScenes();world.grenades().closeGeometry();if(shutdownFailure!=null)throw new IllegalStateException("world_shutdown_failed",shutdownFailure);}
}
