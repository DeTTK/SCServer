package szserver.world;

import szserver.client.BoltProjectile;
import szserver.inventory.ItemStack;
import szserver.inventory.WorldItem;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Server-owned item trajectories. Detached collision views are shared and prepared off-thread. */
public final class WorldItems implements AutoCloseable {
    public static final class Body {
        public final WorldItem item;public final int entity;
        public final UUID throwingSession;public final int prediction;
        public final BoltProjectile projectile;
        private boolean broken;
        public boolean broken(){return broken;}
        public World.Vec position,velocity;
        private World.Vec previousPosition;
        public World.Vec previousPosition(){return previousPosition;}public boolean ground,claiming,expiring,settlePending,settledSaved;public long retry;
        int quiet;
        public void rebound(World.Vec point,World.Vec newVelocity){
            position=point;velocity=newVelocity;ground=false;broken=false;quiet=0;
        }
        Body(WorldItem item,int entity,UUID throwingSession,int prediction,BoltProjectile projectile){this.projectile=projectile;this.item=item;this.entity=entity;this.throwingSession=throwingSession;this.prediction=prediction;position=new World.Vec(item.x(),item.y(),item.z());previousPosition=position;velocity=new World.Vec(item.vx(),item.vy(),item.vz());ground=item.vx()==0&&item.vy()==0&&item.vz()==0;settledSaved=ground;}
    }
    private record Key(int x,int y,int z) {static Key at(World.Vec p){return new Key((int)Math.floor(p.x()/8),(int)Math.floor(p.y()/8),(int)Math.floor(p.z()/8));}World.Vec center(){return new World.Vec(x*8+4,y*8+4,z*8+4);}}
    private static final class Local {ItemCollisionScene scene;CompletableFuture<ItemCollisionScene> pending;long retry;}
    private final ProjectileMaps maps;private final String world;private final IntSupplier ids;
    private final Map<UUID,Body> bodies=new LinkedHashMap<>();private final ArrayDeque<Body> removed=new ArrayDeque<>();
    private final LinkedHashMap<Key,Local> scenes=new LinkedHashMap<>(32,.75f,true);
    private long tick,waits,budgetDeferrals;private int preparations;private String lastError="";
    public WorldItems(ProjectileMaps maps,String world,IntSupplier ids){this.maps=Objects.requireNonNull(maps);this.world=Objects.requireNonNull(world);this.ids=Objects.requireNonNull(ids);}
    public Collection<Body> all(){return Collections.unmodifiableCollection(bodies.values());}
    public List<Body> removed(){List<Body> result=List.copyOf(removed);removed.clear();return result;}
    public boolean throwBolt(UUID creator,UUID session,ItemStack stack,World.Vec start,World.Vec velocity,
                             int prediction,long now,BoltProjectile projectile){
        long lifetime=projectile.lifetime()*50L;
        if(bodies.size()>=2048)return false;
        WorldItem item=new WorldItem(UUID.randomUUID(),creator,world,stack.count(1),start.x(),start.y(),start.z(),
                velocity.x(),velocity.y(),velocity.z(),now,now+lifetime,null);
        bodies.put(item.id(),new Body(item,ids.getAsInt(),session,prediction,projectile));
        return true;
    }
    public void sync(Map<UUID,WorldItem> saved,long now){
        for(var it=bodies.entrySet().iterator();it.hasNext();){
            var entry=it.next();
            var body=entry.getValue();
            if(body.throwingSession==null?!saved.containsKey(entry.getKey()):now>=body.item.expires()||body.broken){
                removed.add(body);it.remove();
            }
        }
        for(var item:saved.values())if(item.world().equals(world)&&!bodies.containsKey(item.id()))
            bodies.put(item.id(),new Body(item,ids.getAsInt(),null,-1,null));
    }
    private static void close(Local l){if(l.scene!=null)l.scene.close();if(l.pending!=null)l.pending.thenAccept(ItemCollisionScene::close);}
    private ItemCollisionScene scene(World.Vec p){
        Key key=Key.at(p);Local local=scenes.get(key);
        if(local==null){if(scenes.size()>=32){var oldest=scenes.entrySet().iterator();var row=oldest.next();close(row.getValue());oldest.remove();}local=new Local();scenes.put(key,local);}
        if(local.pending!=null&&local.pending.isDone()){try{local.scene=local.pending.join();}catch(RuntimeException error){lastError=error.getClass().getSimpleName();local.retry=tick+20;}local.pending=null;}
        if(local.scene!=null){try{local.scene.checkCurrent();return local.scene;}catch(RuntimeException stale){local.scene.close();local.scene=null;}}
        if(local.pending==null&&preparations>0&&tick>=local.retry){preparations--;try{local.pending=maps.itemScene(key.center());}catch(RuntimeException full){local.retry=tick+20;lastError=full.getClass().getSimpleName();}}
        waits++;return null;
    }
    public static SceneCollision.Box box(World.Vec p){return new SceneCollision.Box(p.x()-.125,p.y()-.125,p.z()-.125,p.x()+.125,p.y()+.125,p.z()+.125);}
    private static boolean near(World.Vec item,List<World.Vec> players){for(var p:players)if(Math.abs(item.x()-p.x())<64&&Math.abs(item.z()-p.z())<64&&Math.abs(item.y()-p.y())<48)return true;return false;}
    public void step(List<World.Vec> players){
        tick++;preparations=2;ActorScene.Budget budget=new ActorScene.Budget(32768);int simulated=0;
        List<Body> ordered=new ArrayList<>(bodies.values());
        for(Body body:ordered)body.previousPosition=body.position;
        for(int n=0;n<ordered.size();n++){Body b=ordered.get((int)Math.floorMod(tick*128+n,ordered.size()));
            if(b.broken||b.claiming||b.expiring||!near(b.position,players))continue;
            // Resting objects still wake after a collision view changes, including removed floors.
            if(b.ground&&b.velocity.x()==0&&b.velocity.y()==0&&b.velocity.z()==0&&tick%20!=Math.floorMod(b.entity,20))continue;if(simulated++>=128)break;
            double gravity=b.projectile==null?.8:b.projectile.gravity()/20;
            World.Vec anchor=new World.Vec(b.position.x()+b.velocity.x()/40,b.position.y()+(b.velocity.y()-gravity)/40,b.position.z()+b.velocity.z()/40);
            ItemCollisionScene s=scene(anchor);if(s==null)continue;
            try{
                World.Vec p=b.position,v=b.velocity;double vy=b.projectile==null?v.y()-gravity:v.y();
                double dy=s.sweep(box(p),1,vy/20,budget);World.Vec moved=new World.Vec(p.x(),p.y()+dy,p.z());
                double dx=s.sweep(box(moved),0,v.x()/20,budget);moved=new World.Vec(moved.x()+dx,moved.y(),moved.z());
                double dz=s.sweep(box(moved),2,v.z()/20,budget);moved=new World.Vec(moved.x(),moved.y(),moved.z()+dz);
                boolean ground=vy<0&&Math.abs(dy-vy/20)>1e-7;double vx=Math.abs(dx-v.x()/20)>1e-7?0:v.x(),vz=Math.abs(dz-v.z()/20)>1e-7?0:v.z();
                if(b.projectile!=null){
                    boolean hitX=Math.abs(dx-v.x()/20)>1e-7;
                    boolean hitY=Math.abs(dy-vy/20)>1e-7;
                    boolean hitZ=Math.abs(dz-v.z()/20)>1e-7;
                    if(b.projectile.breakOnContact()&&(hitX||hitY||hitZ))b.broken=true;
                    vx=(hitX?-v.x()*b.projectile.bounce():vx)*b.projectile.drag();
                    vy=(hitY?-vy*b.projectile.bounce():vy)*b.projectile.drag()-gravity;
                    vz=(hitZ?-v.z()*b.projectile.bounce():vz)*b.projectile.drag();
                    if(ground){vx*=b.projectile.friction();vz*=b.projectile.friction();}
                }else{
                    if(Math.abs(dy-vy/20)>1e-7)vy=ground?-vy*.5:0;
                    vx*=ground?.588:.98;vz*=ground?.588:.98;vy*=.98;
                }
                boolean quiet=ground&&Math.abs(vx)+Math.abs(vz)<.08&&Math.abs(vy)<1;
                if(quiet){if(++b.quiet>=1){vx=vy=vz=0;}}else b.quiet=0;
                boolean wasResting=b.ground&&b.velocity.x()==0&&b.velocity.y()==0&&b.velocity.z()==0;
                b.position=moved;b.velocity=new World.Vec(vx,vy,vz);b.ground=ground;
                if(wasResting&&!quiet)b.settledSaved=false;
            }catch(ActorScene.Deferred exhausted){budgetDeferrals++;}catch(RuntimeException invalid){lastError=invalid.getClass().getSimpleName();}
        }
    }
    /** Stock player drop: 0.3 blocks/tick forward, 0.1 upward, then the original small spread. */
    public static World.Vec throwVelocity(float yaw,float pitch,double eyeHeight,Random random){
        float y=yaw/180*(float)Math.PI,p=pitch/180*(float)Math.PI;double vx=-(float)Math.sin(y)*(float)Math.cos(p)*.3f,vy=-(float)Math.sin(p)*.3f+.1f,vz=(float)Math.cos(y)*(float)Math.cos(p)*.3f;
        float angle=random.nextFloat()*(float)Math.PI*2,spread=.02f*random.nextFloat();vx+=Math.cos(angle)*spread;vy+=(random.nextFloat()-random.nextFloat())*.1f;vz+=Math.sin(angle)*spread;if(eyeHeight<.5)vy=.2f;return new World.Vec(vx*20,vy*20,vz*20);
    }
    public Map<String,Object> metrics(){return Map.of("active",bodies.size(),"collisionViews",scenes.size(),"waitingSteps",waits,"deferredSteps",budgetDeferrals,"lastError",lastError,"items",bodies.values().stream().limit(64).map(b->Map.of("id",b.item.id(),"entity",b.entity,"item",b.item.stack().id(),"count",b.item.stack().count(),"x",b.position.x(),"y",b.position.y(),"z",b.position.z(),"ground",b.ground,"claiming",b.claiming)).toList());}
    @Override public void close(){for(Local l:scenes.values())close(l);scenes.clear();}
}
