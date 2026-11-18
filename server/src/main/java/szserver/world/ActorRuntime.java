package szserver.world;

import java.util.*;

/** Detached current NPC state. Geometry leases and asynchronous path jobs are rebuilt after restart. */
public record ActorRuntime(String id,String kind,long incarnation,World.Vec position,World.Vec velocity,
        float health,boolean grounded,boolean ladder,boolean water,double airborneTime,
        ActorJump.State jump,int pose,int locomotion,int previousLocomotion,double poseSince,
        World.Vec goal,List<World.Vec> patrol,int patrolAt,long patrolDeadline,long patrolResume,
        World.Follow follow,boolean holding,long followRefresh,World.Vec progress,long progressAt,long recoveryPlans,
        double inputX,double inputZ,long inputExpires,boolean inputJump,boolean jumpIntent,
        double blockedX,double blockedZ,long nextPlan,boolean hadPath,String navigation,
        List<Contact> contacts,long entered,long stayed,long left) {
    public record Contact(ActorContacts.Key key,ActorContacts.Obstacle obstacle){public Contact{Objects.requireNonNull(key);Objects.requireNonNull(key.object());Objects.requireNonNull(obstacle);if(key.occurrence()<0||key.cell()<0)throw new IllegalArgumentException("actor_runtime_contact");}}
    public record Controller(ActorControllerState state,ActorControllerEngine.Input input,long expires){
        public Controller{Objects.requireNonNull(state);if(expires<0)throw new IllegalArgumentException("actor_controller_expiry");}
    }
    public record Snapshot(int version,long tick,long counter,List<ActorRuntime> actors,List<Controller> controllers,List<ActorMineState> mines){
        public Snapshot(int version,long tick,long counter,List<ActorRuntime> actors,List<Controller> controllers){this(version,tick,counter,actors,controllers,List.of());}
        public Snapshot(int version,long tick,long counter,List<ActorRuntime> actors){this(version,tick,counter,actors,List.of());}
        public Snapshot{
            mines=mines==null?List.of():List.copyOf(mines);if(mines.size()>8192||mines.stream().map(ActorMineState::block).distinct().count()!=mines.size())throw new IllegalArgumentException("actor_runtime_mines");
            if((version!=1&&version!=2)||tick<0||tick>Long.MAX_VALUE/2||counter<0||actors==null||actors.size()>512||controllers==null)throw new IllegalArgumentException("actor_runtime_header");
            actors=List.copyOf(actors);controllers=List.copyOf(controllers);Set<String> ids=new HashSet<>();Set<Long> incarnations=new HashSet<>();Map<String,Long> identities=new HashMap<>();
            for(var a:actors){if(version==1&&a.health()>100)throw new IllegalArgumentException("actor_runtime_legacy_health");if(!ids.add(a.id())||!incarnations.add(a.incarnation())||a.incarnation()>counter)throw new IllegalArgumentException("actor_runtime_duplicate");identities.put(a.id(),a.incarnation());}
            if(version==1&&!controllers.isEmpty()||version==2&&controllers.size()!=actors.size())throw new IllegalArgumentException("actor_runtime_controller_count");
            Set<String> seen=new HashSet<>();long chars=0;for(var c:controllers){var s=c.state();if(!seen.add(s.id())||!Objects.equals(identities.get(s.id()),s.incarnation())||(chars+=s.retainedCharacters())>32L*1024*1024)throw new IllegalArgumentException("actor_runtime_controller_identity_or_size");}
        }
    }
    public ActorRuntime {
        if(id==null||!UUID.fromString(id).toString().equals(id)||!Set.of("NPC","BOT").contains(kind)||incarnation<1)throw new IllegalArgumentException("actor_runtime_identity");
        point(position);Objects.requireNonNull(velocity);finite(velocity.x(),velocity.y(),velocity.z(),health,airborneTime,poseSince,inputX,inputZ,blockedX,blockedZ);
        if(health<0||health>10000||airborneTime<0||poseSince<0||pose<0||pose>1||locomotion<0||locomotion>12||previousLocomotion<0||previousLocomotion>12||Math.abs(inputX)>1||Math.abs(inputZ)>1)throw new IllegalArgumentException("actor_runtime_physics");
        Objects.requireNonNull(jump);finite(jump.time(),jump.lastJump(),jump.stamina(),jump.lastPoseJump());
        if(jump.time()<0||jump.stamina()<0||jump.stamina()>100||jump.jumps()<0||jump.repetitions()<0||jump.mode()<0||jump.mode()>6)throw new IllegalArgumentException("actor_runtime_energy");
        patrol=List.copyOf(patrol);if(!patrol.isEmpty())World.validatePatrol(patrol);if(patrolAt<0||patrolAt>15||!patrol.isEmpty()&&patrolAt>=patrol.size())throw new IllegalArgumentException("actor_runtime_patrol");
        if(goal!=null)point(goal);if(progress!=null)point(progress);if(follow!=null&&!patrol.isEmpty()||!patrol.isEmpty()&&goal==null)throw new IllegalArgumentException("actor_runtime_behavior");
        if(patrolDeadline<0||patrolResume<0||followRefresh<0||progressAt<0||recoveryPlans<0||inputExpires<0||nextPlan<0||navigation==null||navigation.length()>64)throw new IllegalArgumentException("actor_runtime_timer");
        contacts=List.copyOf(contacts);if(contacts.size()>ActorScene.MAX_CELLS||entered<0||stayed<0||left<0)throw new IllegalArgumentException("actor_runtime_contacts");Set<ActorContacts.Key> keys=new HashSet<>();for(var c:contacts)if(!keys.add(c.key()))throw new IllegalArgumentException("actor_runtime_contact_duplicate");
    }
    private static void finite(double... values){for(double v:values)if(!Double.isFinite(v)||Math.abs(v)>1e15)throw new IllegalArgumentException("actor_runtime_number");}
    private static void point(World.Vec p){Objects.requireNonNull(p);finite(p.x(),p.y(),p.z());if(Math.abs(p.x())>MapBounds.HORIZONTAL+16||Math.abs(p.z())>MapBounds.HORIZONTAL+16||p.y()<-64||p.y()>4096)throw new IllegalArgumentException("actor_runtime_position");}
    static ActorRuntime capture(World.Entity e){return new ActorRuntime(e.id,e.kind,e.incarnation,new World.Vec(e.x,e.y,e.z),new World.Vec(e.vx,e.vy,e.vz),e.health,e.grounded,e.ladder,e.inWater,e.airborneTime,e.jumpState,e.pose.nativeId(),e.locomotion,e.previousLocomotion,e.poseSince,e.goal,e.patrol,e.patrolAt,e.patrolDeadline,e.patrolResume,e.follow,e.holding,e.followRefresh,e.progress,e.progressAt,e.recoveryPlans,e.inputX,e.inputZ,e.inputExpires,e.inputJump,e.jumpIntent,e.blockedX,e.blockedZ,e.nextPlan,!e.path.isEmpty()||e.pathPending!=null,e.navigation,e.contacts.active().entrySet().stream().map(c->new Contact(c.getKey(),c.getValue())).toList(),e.contacts.entered(),e.contacts.stayed(),e.contacts.left());}
    void apply(World.Entity e,long tick){
        e.x=position.x();e.y=position.y();e.z=position.z();e.vx=velocity.x();e.vy=velocity.y();e.vz=velocity.z();e.health=health;e.grounded=grounded;e.ladder=ladder;e.inWater=water;e.airborneTime=airborneTime;e.jumpState=jump;e.pose=pose==1?ActorPose.CROUCH:ActorPose.STAND;e.locomotion=locomotion;e.previousLocomotion=previousLocomotion;e.poseSince=poseSince;
        e.goal=goal;e.patrol=patrol;e.patrolAt=patrolAt;e.patrolDeadline=patrolDeadline;e.patrolResume=patrolResume;e.follow=follow;e.holding=holding;e.followRefresh=followRefresh;e.progress=progress;e.progressAt=progressAt;e.recoveryPlans=recoveryPlans;e.inputX=inputX;e.inputZ=inputZ;e.inputExpires=inputExpires;e.inputJump=inputJump;e.jumpIntent=jumpIntent;e.blockedX=blockedX;e.blockedZ=blockedZ;e.nextPlan=hadPath?tick:nextPlan;e.navigation=navigation;
        e.path=List.of();e.pathPoses=List.of();e.pathHeadings=List.of();e.pathActions=List.of();e.ziplinePathAt=-1;e.routeYaw=null;e.routePronePending=null;e.pathAt=0;e.pathSource=null;e.pathPending=null;e.scenePending=null;e.pathVersion++;e.sceneRetry=0;
        Map<ActorContacts.Key,ActorContacts.Obstacle> active=new HashMap<>();for(var c:contacts)active.put(c.key(),c.obstacle());e.contacts=new ActorContacts.State(active,entered,stayed,left);
    }
}
