package szserver.world;
import java.util.*;import java.util.concurrent.*;import java.util.function.BiPredicate;
/** World-owned bounded local geometry. Missing or stale geometry pauses the projectile clock. */
public final class GrenadeCollision implements Grenades.Geometry {
    private static final class Local {ActorScene scene;CompletableFuture<ActorScene> pending;long retry;}
    private final ProjectileMaps maps;private final BiPredicate<World.Vec,World.Vec> blocks,terrain;
    private final Map<UUID,Local> locals=new LinkedHashMap<>();private long tick,requests,waits,failed,stale,budgetDeferrals;private int available=4;
    private volatile String lastError="";
    private ActorScene.Budget budget=new ActorScene.Budget(65536);private volatile Map<String,Object> published=Map.of();
    public GrenadeCollision(ProjectileMaps maps,BiPredicate<World.Vec,World.Vec> blocks,BiPredicate<World.Vec,World.Vec> terrain){this.maps=Objects.requireNonNull(maps);this.blocks=Objects.requireNonNull(blocks);this.terrain=Objects.requireNonNull(terrain);}
    @Override public void begin(){tick++;available=4;budget=new ActorScene.Budget(65536);published=Map.of("contexts",locals.size(),"requests",requests,"waitingSteps",waits,"failedQueries",failed,"staleViews",stale,"budgetDeferrals",budgetDeferrals,"requestsPerTick",4,"collisionBudgetPerTick",65536,"lastError",lastError);}
    private void failure(Throwable error){while(error.getCause()!=null&&error.getCause()!=error)error=error.getCause();String message=error.getClass().getSimpleName()+":"+Objects.toString(error.getMessage(),"");lastError=message.substring(0,Math.min(256,message.length()));}
    public Map<String,Object> metrics(){return published;}
    private static SceneCollision.Box swept(World.Vec a,World.Vec b){return new SceneCollision.Box(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),Math.max(a.y(),b.y()),Math.max(a.z(),b.z()));}
    private static void discard(Local local){if(local.scene!=null)local.scene.close();local.scene=null;}
    @Override public boolean ready(UUID id,World.Vec a,World.Vec b){
        if(!terrain.test(a,b)){waits++;return false;}Local local=locals.computeIfAbsent(id,k->new Local());var area=swept(a,b);
        if(local.pending!=null&&local.pending.isDone()){
            ActorScene value=null;try{value=local.pending.join();value.checkCurrent();if(value.covers(area)){discard(local);local.scene=value;value=null;}}catch(RuntimeException error){failure(error);failed++;local.retry=tick+2;}finally{if(value!=null)value.close();local.pending=null;}
        }
        if(local.scene!=null)try{local.scene.checkCurrent();if(local.scene.covers(area)){
            // Prepare the next window while the current one still covers this whole tick.
            World.Vec ahead=new World.Vec(2*b.x()-a.x(),2*b.y()-a.y(),2*b.z()-a.z());
            if(local.pending==null&&available>0&&tick>=local.retry&&!local.scene.covers(swept(a,ahead))){available--;requests++;try{local.pending=maps.projectileScene(ahead);}catch(RuntimeException full){failure(full);failed++;local.retry=tick+2;}}
            return true;
        }}catch(RuntimeException invalid){stale++;discard(local);}
        if(local.pending==null&&available>0&&tick>=local.retry){available--;requests++;World.Vec center=new World.Vec((a.x()+b.x())*.5,(a.y()+b.y())*.5,(a.z()+b.z())*.5);try{local.pending=maps.projectileScene(center);}catch(RuntimeException full){failure(full);failed++;local.retry=tick+2;}}
        waits++;return false;
    }
    @Override public CompletableFuture<?> preparation(UUID id){Local local=locals.get(id);return local==null?null:local.pending;}
    @Override public boolean clear(UUID id,World.Vec a,World.Vec b){
        Local local=locals.get(id);if(local==null||local.scene==null)throw new ActorScene.Deferred();
        try{local.scene.checkCurrent();}catch(RuntimeException invalid){stale++;discard(local);throw new ActorScene.Deferred();}
        if(!local.scene.covers(swept(a,b)))throw new ActorScene.Deferred();
        try{return blocks.test(a,b)&&local.scene.pointClear(a,b,budget);}catch(ActorScene.Deferred exhausted){budgetDeferrals++;throw exhausted;}
    }
    @Override public void release(UUID id){Local local=locals.remove(id);if(local==null)return;discard(local);if(local.pending!=null)local.pending.thenAccept(ActorScene::close);}
    @Override public void close(){for(UUID id:List.copyOf(locals.keySet()))release(id);}
}
