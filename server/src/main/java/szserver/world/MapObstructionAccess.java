package szserver.world;

import szserver.util.Workers;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.*;

/** Bounded asynchronous map queries; owner work is limited to a shallow read-view capture. */
public final class MapObstructionAccess implements AutoCloseable,ActorMaps,ProjectileMaps {
    public record Authorization(BlockReadView captured){public void checkCurrent(){captured.checkCurrent();}}
    private final WorldLoop world;private final SceneCollisionCatalog catalog;private final CurrentSceneCollision current;
    private final ThreadPoolExecutor worker=Workers.pool("map-obstruction",1,16);private volatile Map<String,Object> metrics=Map.of("queries",0L,"failed",0L);private long queries,failed;
    private volatile boolean closed;private final java.util.Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor navigation=Workers.pool("actor-navigation",1,8);
    private static final long ROUTE_NANOS=750_000_000L,QUEUE_NANOS=1_000_000_000L;
    private final java.util.concurrent.atomic.AtomicLong routeRequests=new java.util.concurrent.atomic.AtomicLong(),routeCompleted=new java.util.concurrent.atomic.AtomicLong(),routeExpired=new java.util.concurrent.atomic.AtomicLong(),routeCancelled=new java.util.concurrent.atomic.AtomicLong(),routeBudgeted=new java.util.concurrent.atomic.AtomicLong(),routeFailed=new java.util.concurrent.atomic.AtomicLong();
    private volatile Map<String,Object> lastRoute=Map.of();private volatile boolean routePrepared;private volatile double routePrepareMs;
    private CompletableFuture<Void> preparation;
    private final java.util.concurrent.atomic.AtomicLong actorBytes=new java.util.concurrent.atomic.AtomicLong();private final long actorByteLimit;
    private volatile ActorMotion motion; public void actorMotion(ActorMotion value){motion=value;}
    private ActorMotion routeMotion;private ActorPathValidator routeValidator;
    private void prepareRouteValidator(){ActorMotion value=motion;if(routeMotion!=value){closeRouteValidator();long at=System.nanoTime();routeValidator=value.pathValidator();routeMotion=value;routePrepareMs=(System.nanoTime()-at)/1e6;routePrepared=true;}}
    @Override public synchronized CompletableFuture<Void> prepareNavigation(boolean nativeController){
        if(!nativeController)return CompletableFuture.completedFuture(null);
        if(preparation!=null)return preparation.copy();preparation=new CompletableFuture<>();
        try{navigation.execute(()->{try{if(closed)throw new IllegalStateException("map_obstruction_stopped");prepareRouteValidator();preparation.complete(null);}catch(Throwable error){preparation.completeExceptionally(error);}});}
        catch(RejectedExecutionException full){preparation.completeExceptionally(full);}return preparation.copy();
    }
    private final java.util.concurrent.atomic.AtomicLong projectileBytes=new java.util.concurrent.atomic.AtomicLong();
    public record AnomalyQuery(List<AnomalyZone> zones,Authorization authorization){public AnomalyQuery{zones=List.copyOf(zones);}}
    public CompletableFuture<AnomalyQuery> anomalies(List<SceneCollision.Box> areas){
        var input=List.copyOf(areas);if(input.size()>256)throw new IllegalArgumentException("anomaly_query_targets");
        var result=world.submit(World::blockReadView).thenApplyAsync(captured->{try{
            if(closed)throw new IllegalStateException("map_obstruction_stopped");
            var view=current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured);var zones=new LinkedHashMap<UUID,AnomalyZone>();
            for(var area:input){for(var source:view.anomalies(area))zones.computeIfAbsent(source.source().identity(),id->current.anomaly(source));if(zones.size()>8192)throw new IllegalArgumentException("anomaly_query_capacity");}
            captured.checkCurrent();return new AnomalyQuery(new ArrayList<>(zones.values()),new Authorization(captured));
        }catch(Exception error){throw new CompletionException(error);}},worker);
        pending.add(result);result.whenComplete((v,e)->pending.remove(result));return result;
    }
    private record ActorSource(BlockReadView blocks,ActorScene scene){}
    private CompletableFuture<ActorSource> actorSource(SceneCollision.Box area){
        return localSource(area,true);
    }
    private CompletableFuture<ActorSource> localSource(SceneCollision.Box area,boolean actor){
        CompletableFuture<ActorSource> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        if(closed){result.completeExceptionally(new IllegalStateException("map_obstruction_stopped"));return result;}
        world.submit(World::blockReadView).thenApplyAsync(captured->{
            if(closed)throw new CompletionException(new IllegalStateException("map_obstruction_stopped"));queries++;
            try{var view=current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured);ActorScene scene=view.actors(area);try{if(actor&&motion!=null)scene.prepareMotion(motion);}catch(RuntimeException error){scene.close();throw error;}long bytes=scene.retainedBytes();var retained=actor?actorBytes:projectileBytes;if(retained.addAndGet(bytes)>actorByteLimit){retained.addAndGet(-bytes);scene.close();throw new IllegalArgumentException(actor?"actor_scene_memory_limit":"projectile_scene_memory_limit");}scene.releaseWith(()->retained.addAndGet(-bytes));return new ActorSource(captured,scene);}
            catch(Exception error){failed++;throw new CompletionException(error);}
            finally{metrics=Map.of("queries",queries,"failed",failed,"current",current.metrics());}
        },worker).whenComplete((value,error)->{if(error==null){if(!result.complete(value))value.scene().close();}else result.completeExceptionally(error);});return result;
    }
    @Override public CompletableFuture<ActorScene> actorScene(World.Vec p){
        return actorSource(new SceneCollision.Box(p.x()-8,p.y()-6,p.z()-8,p.x()+8,p.y()+8,p.z()+8)).thenApply(ActorSource::scene);
    }
    @Override public CompletableFuture<ActorScene> projectileScene(World.Vec p){
        return localSource(new SceneCollision.Box(p.x()-14,p.y()-14,p.z()-14,p.x()+14,p.y()+14,p.z()+14),false).thenApply(ActorSource::scene);
    }
    @Override public CompletableFuture<ItemCollisionScene> itemScene(World.Vec p){
        var area=new SceneCollision.Box(p.x()-7,p.y()-7,p.z()-7,p.x()+7,p.y()+7,p.z()+7);
        CompletableFuture<ItemCollisionScene> result=new CompletableFuture<>();
        localSource(area,false).whenComplete((source,captureError)->{
            if(captureError!=null){result.completeExceptionally(captureError);return;}
            try{worker.execute(()->{ActorScene terrainScene=null;
            try{
                List<World.Block> positions=new ArrayList<>();for(int y=Math.max(0,(int)Math.floor(area.y())-BlockShapes.LOWER_HALO);y<Math.min(256,(int)Math.ceil(area.yy()));y++)for(int z=(int)Math.floor(area.z());z<(int)Math.ceil(area.zz());z++)for(int x=(int)Math.floor(area.x());x<(int)Math.ceil(area.xx());x++)positions.add(new World.Block(x,y,z));
                var values=source.blocks().read(positions);List<ActorGeometry> cells=new ArrayList<>();double[] identity={1,0,0,0,1,0,0,0,1};
                for(int n=0;n<positions.size();n++){var at=positions.get(n);for(double[] cell:BlockShapes.cells(values.voxels()[n],values.tiles()[n])){if(cells.size()>=ActorScene.MAX_CELLS)throw new IllegalArgumentException("world_item_collision_capacity");cells.add(new ActorGeometry(new SceneCollision.Box(cell[0],cell[1],cell[2],cell[3],cell[4],cell[5]),identity,new World.Vec(at.x(),at.y(),at.z())));}}
                source.blocks().checkCurrent();terrainScene=new ActorScene(area,cells,source.blocks()::checkCurrent);long bytes=terrainScene.retainedBytes();
                if(projectileBytes.addAndGet(bytes)>actorByteLimit){projectileBytes.addAndGet(-bytes);throw new IllegalArgumentException("world_item_geometry_memory_limit");}
                terrainScene.releaseWith(()->projectileBytes.addAndGet(-bytes));var view=new ItemCollisionScene(source.scene(),terrainScene);if(!result.complete(view))view.close();
            }catch(Exception error){source.scene().close();if(terrainScene!=null)terrainScene.close();result.completeExceptionally(error);}
            });}catch(RejectedExecutionException full){source.scene().close();result.completeExceptionally(full);}
        });return result;
    }
    @Override public CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to){
        return actorPath(from,to,false,ActorPose.STAND);
    }
    @Override public CompletableFuture<ActorNavigation.Path> actorFollowPath(World.Vec from,World.Vec to){return actorPath(from,to,true,ActorPose.STAND);}
    @Override public CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to,ActorPose pose){return actorPath(from,to,false,pose);}
    @Override public CompletableFuture<ActorNavigation.Path> actorFollowPath(World.Vec from,World.Vec to,ActorPose pose){return actorPath(from,to,true,pose);}
    @Override public CompletableFuture<ActorNavigation.Path> actorNativePath(World.Vec from,World.Vec to,ActorPose pose,boolean partial,ActorControllerState state){return actorPath(from,to,partial,pose,state);}
    private CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to,boolean partial,ActorPose pose){
        return actorPath(from,to,partial,pose,null);
    }
    private CompletableFuture<ActorNavigation.Path> actorPath(World.Vec from,World.Vec to,boolean partial,ActorPose pose,ActorControllerState state){
        long requested=System.nanoTime();routeRequests.incrementAndGet();
        CompletableFuture<ActorNavigation.Path> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        actorSource(new SceneCollision.Box(Math.max(-MapBounds.HORIZONTAL,from.x()-66),0,Math.max(-MapBounds.HORIZONTAL,from.z()-66),Math.min(MapBounds.HORIZONTAL,from.x()+66),257,Math.min(MapBounds.HORIZONTAL,from.z()+66))).whenComplete((source,error)->{
            if(error!=null){routeFailed.incrementAndGet();result.completeExceptionally(error);return;}
            if(result.isDone()||closed){source.scene().close();return;}
            long queued=System.nanoTime();
            try{navigation.execute(()->{
                long started=System.nanoTime();String status="failed";int points=0;
                try{
                    if(result.isDone()||closed){status="cancelled";routeCancelled.incrementAndGet();return;}
                    if(started-queued>QUEUE_NANOS){status="queue_expired";routeExpired.incrementAndGet();throw new IllegalArgumentException("actor_route_queue_expired");}
                    // Factory setup belongs to startup; keep a lazy fallback for embedded worlds.
                    if(state!=null)prepareRouteValidator();
                    try(var budget=NavigationBudget.begin(ROUTE_NANOS,()->result.isDone()||closed)){
                        var path=validatedRoute(source,from,to,partial,pose,state);points=path.points().size();
                        status=points==0?"empty":"ready";routeCompleted.incrementAndGet();result.complete(path);
                    }
                }catch(NavigationBudget.Exhausted exhausted){status=exhausted.cancelled()?"cancelled":"time_budget";if(exhausted.cancelled())routeCancelled.incrementAndGet();else routeBudgeted.incrementAndGet();result.completeExceptionally(exhausted);}
                catch(Throwable failure){if(!status.equals("queue_expired"))routeFailed.incrementAndGet();result.completeExceptionally(failure);}
                finally{source.scene().close();lastRoute=Map.of("status",status,"points",points,"captureMs",(queued-requested)/1e6,"queueMs",(started-queued)/1e6,"workMs",(System.nanoTime()-started)/1e6);}
            });}catch(RejectedExecutionException full){source.scene().close();routeFailed.incrementAndGet();result.completeExceptionally(full);}
        });return result;
    }
    private ActorNavigation.Path validatedRoute(ActorSource source,World.Vec from,World.Vec to,boolean partial,ActorPose pose,ActorControllerState state)throws Exception{
        if(state==null)return ActorNavigation.find(source.blocks(),source.scene(),from,to,partial,pose);
        var start=routeValidator.start(state);ActorPose initial=start.locomotion()==7?ActorPose.PRONE:pose;
        var rejected=new java.util.HashSet<ActorNavigation.Edge>();
        // Pursuit needs a fresh executable next segment, rather than proving a long
        // route to a player who has already moved. The owner still sees the real goal.
        World.Vec routeGoal=to;
        for(int attempt=0;attempt<16;attempt++){
            NavigationBudget.check();
            var candidate=ActorNavigation.find(source.blocks(),source.scene(),from,routeGoal,partial,initial,rejected,true,start.yaw(),start.locomotion()==2||start.locomotion()==4||start.locomotion()==5||start.locomotion()==12,start);
            try{return routeValidator.validate(partial?nextSegment(candidate):candidate,state,source.scene());}
            catch(ActorPathValidator.Blocked blocked){if(Boolean.getBoolean("szserver.debugNativeRoutes"))System.err.println("Native route rejected "+attempt+": "+blocked.getMessage());if(!rejected.add(blocked.edge())||attempt==15)throw blocked;}
        }
        throw new IllegalStateException("native_route_attempt_budget");
    }
    static ActorNavigation.Path nextSegment(ActorNavigation.Path route){
        double distance=0;int count=0;World.Vec previous=route.from();
        for(var point:route.points()){
            distance+=Math.hypot(Math.hypot(point.x()-previous.x(),point.z()-previous.z()),point.y()-previous.y());
            count++;previous=point;
            // Keep every transition atomic. Never substitute a point in the middle of a cable.
            if(distance>=6&&route.actions().get(count-1).isEmpty())break;
        }
        if(count==route.points().size())return route;
        return new ActorNavigation.Path(route.points().subList(0,count),route.from(),route.goal(),route.source(),route.poses().subList(0,count),route.headings().subList(0,count),route.initialYaw(),route.controllerVerified(),route.actions().subList(0,count));
    }
    private void closeRouteValidator(){if(routeValidator!=null)try{routeValidator.close();}catch(Exception error){throw new IllegalStateException("native_route_validator_close",error);}finally{routeValidator=null;routeMotion=null;}}
    public record Ray(World.Vec origin,World.Vec end){}
    public record HordeSpawns(World.Vec player,java.util.List<World.Vec> positions,Authorization authorization){
        public HordeSpawns{positions=java.util.List.copyOf(positions);}
    }
    /** No terrain IO or ray walking on the game owner. Unknown or visible positions are rejected. */
    public CompletableFuture<HordeSpawns> hordeSpawns(World.Vec player,double eyeHeight,long seed,int count){
        if(count<1||count>3)throw new IllegalArgumentException("horde_spawn_batch");
        var future=world.submit(World::blockReadView).thenApplyAsync(captured->{
            if(closed)throw new IllegalStateException("map_obstruction_stopped");
            var positions=new java.util.ArrayList<World.Vec>();
            try(var scope=NavigationBudget.begin(750_000_000L,()->closed)){
                var view=current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured);
                var area=new SceneCollision.Box(player.x()-37,Math.max(0,player.y()-13),player.z()-37,player.x()+37,Math.min(257,player.y()+10),player.z()+37);
                try(var scene=view.actors(area)){
                    var map=new MapObstruction(view);var random=new java.util.SplittableRandom(seed);
                    var eye=new World.Vec(player.x(),player.y()+eyeHeight,player.z());
                    for(int attempt=0;attempt<48&&positions.size()<count;attempt++){
                        NavigationBudget.check();double angle=random.nextDouble()*Math.PI*2,radius=random.nextDouble(18,34);
                        double x=Math.floor(player.x()+Math.cos(angle)*radius)+.5,z=Math.floor(player.z()+Math.sin(angle)*radius)+.5;
                        World.Vec point=ActorNavigation.spawnFloor(captured,scene,x,player.y(),z);
                        if(point==null||positions.stream().anyMatch(p->Math.hypot(p.x()-point.x(),p.z()-point.z())<2))continue;
                        boolean hidden=true;
                        // Head, torso and shoulders must all be behind physical cover, even behind the camera.
                        for(double[] offset:new double[][]{{0,.2,0},{0,1,0},{0,1.7,0},{.3,1.2,0},{-.3,1.2,0},{0,1.2,.3},{0,1.2,-.3}}){
                            double dx=point.x()+offset[0]-eye.x(),dy=point.y()+offset[1]-eye.y(),dz=point.z()+offset[2]-eye.z(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
                            var hit=map.ray(eye,new World.Vec(dx/distance,dy/distance,dz/distance),distance);
                            if(hit==MapObstruction.Hit.CLEAR||hit==MapObstruction.Hit.UNKNOWN){hidden=false;break;}
                        }
                        if(hidden)positions.add(point);
                    }
                }
                captured.checkCurrent();return new HordeSpawns(player,positions,new Authorization(captured));
            }catch(InterruptedException error){Thread.currentThread().interrupt();throw new CompletionException(error);}
            catch(java.io.IOException error){throw new CompletionException(error);}
        },worker);
        pending.add(future);future.whenComplete((v,e)->pending.remove(future));return future;
    }
    public record Visibility(Authorization authorization,java.util.List<Boolean> clear){public Visibility{clear=java.util.List.copyOf(clear);}}
    /** One current native scene for a bounded explosion wave; no cube approximation or owner-thread IO. */
    public CompletableFuture<Visibility> visibleRays(java.util.List<Ray> rays){
        var input=java.util.List.copyOf(rays);if(input.size()>4096)throw new IllegalArgumentException("explosion_targets_limit");
        CompletableFuture<Visibility> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        if(closed){result.completeExceptionally(new IllegalStateException("map_obstruction_stopped"));return result;}
        world.submit(World::blockReadView).thenApplyAsync(captured->{
            if(closed)throw new CompletionException(new IllegalStateException("map_obstruction_stopped"));queries++;
            try{var map=new MapObstruction(current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured));var clear=new java.util.ArrayList<Boolean>(input.size());
                for(Ray r:input){double x=r.end.x()-r.origin.x(),y=r.end.y()-r.origin.y(),z=r.end.z()-r.origin.z(),d=Math.hypot(Math.hypot(x,y),z);clear.add(d<.0001||map.ray(r.origin,new World.Vec(x/d,y/d,z/d),d)==MapObstruction.Hit.CLEAR);}
                map.checkCurrent();return new Visibility(new Authorization(captured),clear);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed++;throw new CompletionException(interrupted);}
            catch(Exception error){failed++;throw new CompletionException(error);}
            finally{metrics=Map.of("queries",queries,"failed",failed,"current",current.metrics());}
        },worker).whenComplete((value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(error instanceof CompletionException&&error.getCause()!=null?error.getCause():error);});return result;
    }
    public record Visible(Authorization authorization,int index){}
    /** One capture and one bounded worker query for all claimed melee targets, nearest first. */
    public CompletableFuture<Visible> nearestVisible(java.util.List<Ray> rays){
        var input=java.util.List.copyOf(rays);if(input.size()>16)throw new IllegalArgumentException("melee_targets_limit");
        CompletableFuture<Visible> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        if(closed){result.completeExceptionally(new IllegalStateException("map_obstruction_stopped"));return result;}
        world.submit(World::blockReadView).thenApplyAsync(captured->{
            if(closed)throw new CompletionException(new IllegalStateException("map_obstruction_stopped"));queries++;
            try{var map=new MapObstruction(current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured));int visible=-1;
                for(int i=0;i<input.size();i++){Ray r=input.get(i);double x=r.end.x()-r.origin.x(),y=r.end.y()-r.origin.y(),z=r.end.z()-r.origin.z();double d=Math.hypot(Math.hypot(x,y),z);
                    if(d>=.0001&&map.ray(r.origin,new World.Vec(x/d,y/d,z/d),d)==MapObstruction.Hit.CLEAR){visible=i;break;}}
                map.checkCurrent();return new Visible(new Authorization(captured),visible);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed++;throw new CompletionException(interrupted);}
            catch(Exception error){failed++;throw new CompletionException(error);}
            finally{metrics=Map.of("queries",queries,"failed",failed,"current",current.metrics());}
        },worker).whenComplete((value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(error instanceof CompletionException&&error.getCause()!=null?error.getCause():error);});return result;
    }
    public MapObstructionAccess(WorldLoop world,Terrain terrain,Path boxes,long baseBytes,long editedBytes){
        this(world,terrain,boxes,baseBytes,editedBytes,terrain instanceof RegionTerrain region?region.collisionDefinitions():Map.of());
    }
    public MapObstructionAccess(WorldLoop world,Terrain terrain,Path boxes,long baseBytes,long editedBytes,Map<String,SceneRegistry.Definition> definitions){
        this.world=java.util.Objects.requireNonNull(world);motion=world.actorMotion();actorByteLimit=Math.max(65536,Math.min(32L*1024*1024,editedBytes));current=new CurrentSceneCollision(definitions,new CollisionBoxes(boxes,motion),editedBytes);
        new CollisionTree.Builder(editedBytes);catalog=terrain instanceof RegionTerrain region?region.collisionCatalog(boxes,baseBytes,motion):null;
    }
    public CompletableFuture<Authorization> placement(World.Vec operator,World.Vec position){
        CompletableFuture<Authorization> result=new CompletableFuture<>();pending.add(result);result.whenComplete((value,error)->pending.remove(result));
        if(closed){result.completeExceptionally(new IllegalStateException("map_obstruction_stopped"));return result;}
        world.submit(World::blockReadView).thenApplyAsync(captured->{
            if(closed)throw new CompletionException(new IllegalStateException("map_obstruction_stopped"));
            queries++;try{var view=current.capture(catalog==null?CollisionTree.empty():catalog.current(captured.nativeBase()),captured);FpvPlacement.validate(new MapObstruction(view),operator,position);return new Authorization(captured);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed++;throw new CompletionException(interrupted);}
            catch(Exception error){failed++;throw new CompletionException(error);}
            finally{metrics=Map.of("queries",queries,"failed",failed,"current",current.metrics());}
        },worker).whenComplete((value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(error instanceof CompletionException&&error.getCause()!=null?error.getCause():error);});return result;
    }
    public boolean healthy(){return !closed&&(catalog==null||catalog.healthy());}
    public boolean ready(){return healthy()&&(catalog==null||catalog.ready());}
    public Map<String,Object> metrics(){var view=new java.util.LinkedHashMap<String,Object>(Map.of("healthy",healthy(),"ready",ready(),"queries",metrics,"base",catalog==null?Map.of("ready",true,"shapes",0):catalog.metrics(),"queued",worker.getQueue().size(),"navigationQueued",navigation.getQueue().size(),"actorGeometryBytes",actorBytes.get(),"actorGeometryByteLimit",actorByteLimit,"projectileGeometryBytes",projectileBytes.get(),"projectileGeometryByteLimit",actorByteLimit));view.put("navigation",Map.of("prepared",routePrepared,"prepareMs",routePrepareMs,"requests",routeRequests.get(),"completed",routeCompleted.get(),"expired",routeExpired.get(),"cancelled",routeCancelled.get(),"timeLimited",routeBudgeted.get(),"failed",routeFailed.get(),"workBudgetMs",ROUTE_NANOS/1_000_000,"last",lastRoute));return view;}
    @Override public void close()throws InterruptedException{closed=true;for(var result:pending)result.completeExceptionally(new IllegalStateException("map_obstruction_stopped"));try{Workers.stop(navigation);}finally{try{closeRouteValidator();}finally{try{Workers.stop(worker);}finally{if(catalog!=null)catalog.close();}}}}
}
