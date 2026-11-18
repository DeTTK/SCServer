package szserver.client;

import szserver.world.*;
import java.util.*;

/** Warm native sessions connected to the server owner and its held map views. */
final class NativeActorControllerEngine implements ActorControllerEngine {
    private final NativeActorMotion motion;private final NativeActorComponents components;private boolean started;
    private java.util.function.Function<ActorPresentation,Map<String,Float>> legacyStats=p->Map.of();
    public void legacyEquipmentStats(java.util.function.Function<ActorPresentation,Map<String,Float>> resolver){if(started)throw new IllegalStateException("actor_stats_late_resolver");legacyStats=Objects.requireNonNull(resolver);}
    private final Set<NativeActorMapWorld> maps=Collections.newSetFromMap(new WeakHashMap<>());
    public void worldTick(){try{motion.mines().step();}catch(Exception failure){throw error(failure);}}
    public List<ActorMineState> mineStates(){try{return motion.mines().capture();}catch(Exception failure){throw error(failure);}}
    public int mineStateCount(){return motion.mines().activeCount();}
    public void restoreMines(List<ActorMineState> states){try{motion.mines().restore(states);}catch(Exception failure){throw error(failure);}}
    public void forgetMine(World.Block block){motion.mines().forget(block);}
    public List<ActorMineTrigger> drainMineTriggers(){return motion.mines().drain();}
    public void contactMines(ActorMinePlayer player,ActorWorldAccess access){try{motion.mines().contact(player,access);}catch(Exception failure){throw error(failure);}}
    public PreparedMedicine prepareMedicine(int item){return components.equipment.buffs.prepare(item);}
    public PreparedEquipment prepareEquipment(ActorEquipment settings){try{return components.equipment.prepare(settings);}catch(Exception failure){throw error(failure);}}
    NativeActorControllerEngine(NativeActorMotion motion,NativeActorComponents components){this.motion=motion;this.components=components;}
    private static RuntimeException error(Exception failure){return failure instanceof RuntimeException r?r:new IllegalStateException("native_actor_controller",failure);}
    private Frame frame(NativeActorMapWorld map,NativeActorDispatcher.Session session)throws Exception{
        var f=map.globalFrame(session);return new Frame(f.position(),f.velocity(),session.yaw(),f.pose().ordinal(),f.previous().ordinal(),f.grounded(),f.water(),f.airborneTime(),f.since(),session.jumpState(),f.body(),session.traversal((int)map.origin().x(),(int)map.origin().z()));
    }
    private Session wrap(NativeActorMapWorld map,NativeActorDispatcher.Session session,long incarnation){return wrap(map,session,incarnation,ActorPresentation.EMPTY);}
    private Session wrap(NativeActorMapWorld map,NativeActorDispatcher.Session session,long incarnation,ActorPresentation initial){maps.add(map);return new Session(){
        private ActorPresentation presentation=initial;
        public ActorPresentation presentation(){return presentation;}
        public Map<String,Float> stats(){try{return session.stats();}catch(Exception failure){throw error(failure);}}
        public List<ActorBuff.View> buffs(){try{return session.buffs();}catch(Exception failure){throw error(failure);}}
        public void advanceBuffs(long tick){try{session.advanceBuffs(tick);}catch(Exception failure){throw error(failure);}}
        public long effectTick(){try{return session.buffTick();}catch(Exception failure){throw error(failure);}}
        public void bindWorldClock(long tick){try{session.bindWorldClock(tick);}catch(Exception failure){throw error(failure);}}
        public void medicine(PreparedMedicine value,long tick){try{session.medicine(value,tick);}catch(Exception failure){throw error(failure);}}
        public void removeBuff(int id,long tick){try{session.removeBuff(id,tick);}catch(Exception failure){throw error(failure);}}
        public void close(){try{map.close(session);}catch(Exception failure){throw error(failure);}}
        public void health(float value){try{session.health(value);}catch(Exception failure){throw error(failure);}}
        public ActorVitals vitals(){try{return session.vitals();}catch(Exception failure){throw error(failure);}}
        public boolean storedVitals(){return session.storedLife;}
        public void equipment(PreparedEquipment prepared){try{session.equipment(prepared);presentation=prepared.presentation();}catch(Exception failure){throw error(failure);}}
        public void aiming(boolean value){try{session.aiming(value);}catch(Exception failure){throw error(failure);}}
        public boolean aiming(){try{return session.aiming();}catch(Exception failure){throw error(failure);}}
        public Frame frame(){try{return NativeActorControllerEngine.this.frame(map,session);}catch(Exception failure){throw error(failure);}}
        public Float ladderFacing(){try{return session.ladderFacing();}catch(Exception failure){throw error(failure);}}
        public Frame step(Input input,float seconds,ActorWorldAccess access){try{
            map.step(session,new NativeActorDispatcher.Input(input.yaw(),input.pitch(),input.heading(),input.gait(),input.jump(),input.crouch(),input.proneToggle(),input.zipline(),input.condition(),input.seat()),seconds,access);return frame();
        }catch(Exception failure){throw error(failure);}}
        public ActorControllerState capture(){try{return map.capture(session,incarnation).withPresentation(presentation);}catch(Exception failure){throw error(failure);}}
        public boolean prepared(){return session.prepared();}
        public Frame prepare(ActorWorldAccess access){try{if(!session.prepared())map.prepare(session,access);return frame();}catch(Exception failure){throw error(failure);}}
        public Map<ActorContacts.Key,ActorContacts.Obstacle> contacts(){try{return map.contacts(session);}catch(Exception failure){throw error(failure);}}
        public void restoreContacts(Map<ActorContacts.Key,ActorContacts.Obstacle> values){try{map.restoreContacts(session,values);}catch(Exception failure){throw error(failure);}}
    };}
    public Session create(String id,long incarnation,World.Vec spawn){try{
        if(incarnation<1)throw new IllegalArgumentException("actor_controller_incarnation");
        var origin=new World.Vec(Math.floor(spawn.x()/16)*16,0,Math.floor(spawn.z()/16)*16);var map=motion.dispatcherWorld(origin);
        return wrap(map,components.create(map.local(spawn),map.nativeWorld(),UUID.fromString(id)),incarnation);
    }catch(Exception failure){throw error(failure);}}
    public Session restore(ActorControllerState saved){try{
        var map=motion.dispatcherWorld(new World.Vec(saved.originX(),0,saved.originZ()));var session=map.restore(components,saved);
        if(java.nio.ByteBuffer.wrap(saved.bytes()).getInt(4)<6&&!saved.presentation().roots().isEmpty()){
            if(started)throw new IllegalArgumentException("actor_legacy_stats_bootstrap_required");
            session.legacyStats(legacyStats.apply(saved.presentation()));
        }
        return wrap(map,session,saved.incarnation(),saved.presentation());
    }catch(Exception failure){throw error(failure);}}
    public Session migrate(ActorRuntime legacy){try{
        // A nearest chunk origin keeps subtraction/addition exact around zero as
        // well as at large coordinates when importing an existing durable double.
        var p=legacy.position();var map=motion.dispatcherWorld(new World.Vec(Math.rint(p.x()/16)*16,0,Math.rint(p.z()/16)*16));
        var session=components.create(map.local(p),map.nativeWorld(),UUID.fromString(legacy.id()));session.migrate(legacy,map.local(p));
        var contacts=new LinkedHashMap<ActorContacts.Key,ActorContacts.Obstacle>();for(var contact:legacy.contacts())contacts.put(contact.key(),contact.obstacle());map.restoreContacts(session,contacts);
        map.seedMigratedContacts(session);
        return wrap(map,session,legacy.incarnation());
    }catch(Exception failure){throw error(failure);}}
    public void endBootstrapAction(){if(!started){for(var map:maps)map.finishBootstrap();components.finishBootstrap();}}
    public void finishBootstrap(){if(started)throw new IllegalStateException("actor_controller_already_started");endBootstrapAction();started=true;}
}
