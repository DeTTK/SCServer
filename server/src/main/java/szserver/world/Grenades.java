package szserver.world;

import szserver.inventory.ExplosiveProfile;
import java.util.*;
import java.util.function.BiPredicate;

/** World-owner projectile lane. A reservation includes completion capacity: events cannot be lost
 * when the consumer is slow. FPV entities never enter this simulation. */
public final class Grenades {
    public static final int LIMIT=128;
    /** Bounded server admission/persistence policy; native max_start_speed=10 is 200 m/s. */
    public static final double MAX_COMPONENT_SPEED=200;
    public interface Geometry extends AutoCloseable {
        boolean ready(UUID id,World.Vec start,World.Vec end);
        boolean clear(UUID id,World.Vec start,World.Vec end);
        default java.util.concurrent.CompletableFuture<?> preparation(UUID id){return null;}
        void release(UUID id);void begin();@Override void close();
    }
    private Geometry geometry;
    public void geometry(Geometry value){if(!projectiles.isEmpty()||!completed.isEmpty())throw new IllegalStateException("grenade_geometry_install_after_admission");if(geometry!=null)geometry.close();geometry=Objects.requireNonNull(value);}
    public void closeGeometry(){if(geometry!=null)geometry.close();}
    public record Flight(int fuseTicks,double gravity,double restitution,double friction,double drag) {
        public Flight(int fuseTicks,double gravity,double restitution,double friction){this(fuseTicks,gravity,restitution,friction,1);}
        public Flight {
            if(fuseTicks<1||fuseTicks>12000||!Double.isFinite(gravity)||gravity<0||gravity>400||
                !Double.isFinite(restitution)||restitution<0||restitution>1||!Double.isFinite(friction)||friction<0||friction>1||!Double.isFinite(drag)||drag<0||drag>1)
                throw new IllegalArgumentException("grenade_flight");
        }
    }
    public record View(UUID id,String owner,int item,World.Vec position,World.Vec velocity,int age,String stage) {}
    public record Explosion(UUID id,String owner,ExplosiveProfile profile,World.Vec position,String reason) {}
    public record Saved(UUID id,World.Vec position,World.Vec velocity,int age,String reason) {
        public Saved {Objects.requireNonNull(id);valid(position,MapBounds.HORIZONTAL);valid(velocity,MAX_COMPONENT_SPEED);if(age<0||age>12000||reason!=null&&!Set.of("fuse","collision","world_boundary","held").contains(reason))throw new IllegalArgumentException("grenade_saved_state");}
    }
    private static final class Projectile {
        final UUID id;final String owner;final ExplosiveProfile profile;final Flight flight;
        World.Vec position,velocity;int age;long reservationDeadline;boolean active,admitted;
        Projectile(UUID id,String owner,ExplosiveProfile profile,Flight flight,World.Vec position,World.Vec velocity,long deadline){
            this.id=id;this.owner=owner;this.profile=profile;this.flight=flight;this.position=position;this.velocity=velocity;reservationDeadline=deadline;
        }
        View view(){return new View(id,owner,profile.item(),position,velocity,age,active?"flying":"reserved");}
    }
    private final BiPredicate<World.Vec,World.Vec> clear;
    private final BiPredicate<World.Vec,World.Vec> ready;
    private final Map<UUID,Projectile> projectiles=new LinkedHashMap<>();
    private final Map<UUID,Explosion> completed=new LinkedHashMap<>();
    private final Map<UUID,Saved> completedStates=new LinkedHashMap<>();
    private long tick,expired;private boolean frozen;
    public Grenades(BiPredicate<World.Vec,World.Vec> clear){this(clear,(a,b)->true);}
    public Grenades(BiPredicate<World.Vec,World.Vec> clear,BiPredicate<World.Vec,World.Vec> ready){this.clear=Objects.requireNonNull(clear);this.ready=Objects.requireNonNull(ready);}
    private static void valid(World.Vec p,double limit){
        if(p==null||!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z())||
           Math.abs(p.x())>limit||Math.abs(p.y())>limit||Math.abs(p.z())>limit)throw new IllegalArgumentException("grenade_vector");
    }
    /** Call before durable inventory consumption. All fallible admission checks happen here. */
    public UUID reserve(String owner,ExplosiveProfile profile,Flight flight,World.Vec position,World.Vec velocity){
        if(frozen)throw new IllegalArgumentException("grenade_lane_frozen");
        if(owner==null||owner.isBlank()||owner.length()>64)throw new IllegalArgumentException("grenade_owner");
        Objects.requireNonNull(profile);Objects.requireNonNull(flight);valid(position,MapBounds.HORIZONTAL);valid(velocity,MAX_COMPONENT_SPEED);
        if(profile.radius()>128)throw new IllegalArgumentException("grenade_radius_budget");
        if(projectiles.size()+completed.size()>=LIMIT)throw new IllegalArgumentException("grenade_lane_full");
        if(geometry==null&&!clear.test(position,position))throw new IllegalArgumentException("grenade_spawn_obstructed");
        UUID id=UUID.randomUUID();Projectile p=new Projectile(id,owner,profile,flight,position,velocity,tick+200);p.admitted=geometry==null;projectiles.put(id,p);return id;
    }
    /** Async geometry admission is checked before any durable inventory debit. */
    public boolean admitted(UUID id){Projectile p=projectiles.get(id);if(p==null)throw new IllegalArgumentException("grenade_reservation_lost");if(p.admitted)return true;if(!geometry.ready(id,p.position,p.position))return false;try{if(!geometry.clear(id,p.position,p.position))throw new IllegalArgumentException("grenade_spawn_obstructed");}catch(ActorScene.Deferred waiting){return false;}p.admitted=true;return true;}
    public java.util.concurrent.CompletableFuture<?> preparation(UUID id){return geometry==null?null:geometry.preparation(id);}
    /** Pin while a durable write is in flight; no timeout may discard an already-consumed item. */
    public boolean pin(UUID id){Projectile p=projectiles.get(id);if(p==null||p.active)return false;p.reservationDeadline=Long.MAX_VALUE;return true;}
    /** Idempotent activation, only after the inventory journal has acknowledged the decrement. */
    public boolean activate(UUID id){Projectile p=projectiles.get(id);if(p==null)return completed.containsKey(id);if(!p.admitted)return false;p.active=true;return true;}
    /** Native cook timeout detonates at the operator's hand; activation is still after durable debit. */
    public boolean detonateHeld(UUID id){Projectile p=projectiles.get(id);if(p==null)return completed.containsKey(id);if(!p.admitted)return false;p.active=true;explode(p,"held");return true;}
    public boolean cancel(UUID id){Projectile p=projectiles.get(id);if(p==null||p.active)return false;projectiles.remove(id);if(geometry!=null)geometry.release(id);return true;}
    public void acknowledge(UUID id){completed.remove(id);completedStates.remove(id);}
    /** Restored only from a consumed inventory marker/outbox. Cold terrain pauses, never detonates. */
    public void restore(String owner,ExplosiveProfile profile,Flight flight,Saved saved){
        if(owner==null||owner.isBlank()||owner.length()>64||profile==null||flight==null||saved.age()>flight.fuseTicks()||projectiles.containsKey(saved.id())||completed.containsKey(saved.id())||projectiles.size()+completed.size()>=LIMIT)throw new IllegalArgumentException("grenade_restore_state");
        if(saved.reason()!=null){completed.put(saved.id(),new Explosion(saved.id(),owner,profile,saved.position(),saved.reason()));completedStates.put(saved.id(),saved);return;}
        Projectile p=new Projectile(saved.id(),owner,profile,flight,saved.position(),saved.velocity(),Long.MAX_VALUE);p.active=true;p.admitted=true;p.age=saved.age();projectiles.put(p.id,p);
    }
    public List<Saved> saved(){List<Saved> result=new ArrayList<>(completedStates.values());for(Projectile p:projectiles.values())if(p.active)result.add(new Saved(p.id,p.position,p.velocity,p.age,null));return List.copyOf(result);}
    /** Shutdown capture stops the lane before taking its final immutable snapshot. */
    public List<Saved> freeze(){frozen=true;return saved();}
    public List<View> views(){return projectiles.values().stream().map(Projectile::view).toList();}
    public int projectileCount(){return projectiles.size();}
    public int completionCount(){return completed.size();}
    public List<Explosion> explosions(){return List.copyOf(completed.values());}
    public long expiredReservations(){return expired;}
    public void step(){
        if(frozen)return;
        tick++;
        if(geometry!=null)geometry.begin();List<Projectile> order=new ArrayList<>(projectiles.values());if(geometry!=null&&!order.isEmpty())Collections.rotate(order,-(int)(tick%order.size()));
        for(Projectile p:order){
            if(!p.active){if(tick>=p.reservationDeadline){projectiles.remove(p.id);if(geometry!=null)geometry.release(p.id);expired++;}continue;}
            World.Vec next=new World.Vec(p.position.x()+p.velocity.x()*.05,geometry==null?p.position.y():p.position.y()+Math.max(-MAX_COMPONENT_SPEED,p.velocity.y()*p.flight.drag()-p.flight.gravity()*.05)*.05,p.position.z()+p.velocity.z()*.05);
            if(!ready.test(p.position,Math.abs(next.x())>MapBounds.HORIZONTAL||Math.abs(next.z())>MapBounds.HORIZONTAL?p.position:next))continue;
            if(geometry!=null&&!geometry.ready(p.id,p.position,next))continue;
            World.Vec priorPosition=p.position,priorVelocity=p.velocity;int priorAge=p.age;
            try{
            p.age++;
            if(p.age>=p.flight.fuseTicks()){explode(p,"fuse");continue;}
            World.Vec v=p.velocity;p.velocity=new World.Vec(v.x()*p.flight.drag(),Math.max(-MAX_COMPONENT_SPEED,v.y()*p.flight.drag()-p.flight.gravity()*.05),v.z()*p.flight.drag());
            // Three axis sweeps, with at most twelve contact refinements for each collision.
            boolean ground=false;
            for(int axis=0;axis<3;axis++){
                v=p.velocity;World.Vec a=p.position;
                double delta=(axis==0?v.x():axis==1?v.y():v.z())*.05;if(delta==0)continue;
                World.Vec b=new World.Vec(a.x()+(axis==0?delta:0),a.y()+(axis==1?delta:0),a.z()+(axis==2?delta:0));
                if(Math.abs(b.x())>MapBounds.HORIZONTAL||Math.abs(b.y())>MapBounds.HORIZONTAL||Math.abs(b.z())>MapBounds.HORIZONTAL){explode(p,"world_boundary");break;}
                if(geometry==null?clear.test(a,b):geometry.clear(p.id,a,b)){p.position=b;continue;}
                // Find a safe contact without stepping inside the solid/unloaded voxel.
                double lo=0,hi=1;for(int n=0;n<12;n++){double mid=(lo+hi)*.5;World.Vec c=new World.Vec(a.x()+(b.x()-a.x())*mid,a.y()+(b.y()-a.y())*mid,a.z()+(b.z()-a.z())*mid);if(geometry==null?clear.test(a,c):geometry.clear(p.id,a,c))lo=mid;else hi=mid;}
                p.position=new World.Vec(a.x()+(b.x()-a.x())*lo,a.y()+(b.y()-a.y())*lo,a.z()+(b.z()-a.z())*lo);
                boolean floor=axis==1&&delta<0;ground|=floor;double r=p.flight.restitution();
                p.velocity=new World.Vec(axis==0?-v.x()*r:v.x(),axis==1?-v.y()*r:v.y(),axis==2?-v.z()*r:v.z());
                // An eligible impact ends the flight here; later axes must not move the blast.
                if(p.age>=Math.max(0,p.profile.activationTicks())&&(p.profile.onCollision()&&(!p.profile.lowestPoint()||floor)||p.profile.onGround()&&floor)){explode(p,"collision");break;}
            }
            if(!projectiles.containsKey(p.id))continue;
            if(ground){v=p.velocity;double f=1-p.flight.friction();p.velocity=new World.Vec(v.x()*f,Math.abs(v.y())<.1?0:v.y(),v.z()*f);}
            }catch(ActorScene.Deferred deferred){p.position=priorPosition;p.velocity=priorVelocity;p.age=priorAge;}
        }
    }
    private void explode(Projectile p,String reason){projectiles.remove(p.id);if(geometry!=null)geometry.release(p.id);completed.put(p.id,new Explosion(p.id,p.owner,p.profile,p.position,reason));completedStates.put(p.id,new Saved(p.id,p.position,p.velocity,p.age,reason));}
}
