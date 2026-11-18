package szserver.world;

import java.util.*;

/** Complete NPC controller sessions. Player/FPV input and physics do not enter this interface. */
public interface ActorControllerEngine {
    default void worldTick(){}
    default List<ActorMineState> mineStates(){return List.of();}
    default int mineStateCount(){return 0;}
    default void restoreMines(List<ActorMineState> states){if(!states.isEmpty())throw new IllegalArgumentException("actor_mine_runtime_unavailable");}
    default void forgetMine(World.Block block){}
    default List<ActorMineTrigger> drainMineTriggers(){return List.of();}
    default void contactMines(ActorMinePlayer player,ActorWorldAccess access){}
    /** Before bootstrap only: legacy capsules have equipment roots but no numeric combat bank. */
    default void legacyEquipmentStats(java.util.function.Function<ActorPresentation,Map<String,Float>> resolver){}
    interface PreparedEquipment {default ActorPresentation presentation(){return ActorPresentation.EMPTY;}}
    interface PreparedMedicine {}
    default PreparedMedicine prepareMedicine(int item){throw new UnsupportedOperationException("actor_medicine_unavailable");}
    default PreparedEquipment prepareEquipment(ActorEquipment settings){throw new UnsupportedOperationException("actor_equipment_unavailable");}
    record Zipline(UUID object,boolean detach){public Zipline{Objects.requireNonNull(object);}}
    record Seat(UUID object,int index,boolean leave){public Seat{Objects.requireNonNull(object);if(index<0||index>31)throw new IllegalArgumentException("actor_seat_index");}}
    enum ConditionKind { KNOCKDOWN, LOGOUT, FLY }
    record Condition(ConditionKind kind,boolean enabled){public Condition{Objects.requireNonNull(kind);}}
    record Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouch,boolean proneToggle,Zipline zipline,Condition condition,Seat seat){
        public Input{if(!Float.isFinite(yaw)||!Float.isFinite(pitch)||!Float.isFinite(heading)||gait==null||zipline!=null&&seat!=null)throw new IllegalArgumentException("actor_controller_input");}
        public Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouch,boolean proneToggle,Zipline zipline,Condition condition){this(yaw,pitch,heading,gait,jump,crouch,proneToggle,zipline,condition,null);}
        public Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouch,boolean proneToggle,Zipline zipline){this(yaw,pitch,heading,gait,jump,crouch,proneToggle,zipline,null);}
        public Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouch,boolean proneToggle){this(yaw,pitch,heading,gait,jump,crouch,proneToggle,null);}
        public Input consumed(){return proneToggle||zipline!=null||condition!=null||seat!=null?new Input(yaw,pitch,heading,gait,jump,crouch,false):this;}
    }
    record Frame(World.Vec position,World.Vec velocity,float yaw,int pose,int previous,boolean grounded,boolean water,
                 double airborneTime,double since,ActorJump.State jump,List<SceneCollision.Box> body,ActorTraversal traversal){
        public Frame{Objects.requireNonNull(position);Objects.requireNonNull(velocity);Objects.requireNonNull(jump);Objects.requireNonNull(traversal);body=List.copyOf(body);if(pose<0||pose>12||previous<0||previous>12||body.isEmpty())throw new IllegalArgumentException("actor_controller_frame");}
        public Frame(World.Vec position,World.Vec velocity,float yaw,int pose,int previous,boolean grounded,boolean water,double airborneTime,double since,ActorJump.State jump,List<SceneCollision.Box> body){this(position,velocity,yaw,pose,previous,grounded,water,airborneTime,since,jump,body,ActorTraversal.EMPTY);}
    }
    interface Session {
        default void close(){}
        default ActorPresentation presentation(){return ActorPresentation.EMPTY;}
        default Map<String,Float> stats(){return Map.of();}
        default List<ActorBuff.View> buffs(){return List.of();}
        default void advanceBuffs(long tick){}
        default long effectTick(){return 0;}
        default void bindWorldClock(long tick){}
        default void medicine(PreparedMedicine prepared,long tick){throw new UnsupportedOperationException("actor_medicine_unavailable");}
        default void removeBuff(int id,long tick){throw new UnsupportedOperationException("actor_medicine_unavailable");}
        default void health(float value){}
        default ActorVitals vitals(){return null;}
        default boolean storedVitals(){return false;}
        default void equipment(PreparedEquipment prepared){throw new UnsupportedOperationException("actor_equipment_unavailable");}
        default void aiming(boolean value){throw new UnsupportedOperationException("actor_aiming_unavailable");}
        default boolean aiming(){return false;}
        Frame frame();
        default Float ladderFacing(){return null;}
        Frame step(Input input,float seconds,ActorWorldAccess access);
        ActorControllerState capture();
        default boolean prepared(){return true;}
        default Frame prepare(ActorWorldAccess access){return frame();}
        default Map<ActorContacts.Key,ActorContacts.Obstacle> contacts(){return Map.of();}
        default void restoreContacts(Map<ActorContacts.Key,ActorContacts.Obstacle> contacts){if(!contacts.isEmpty())throw new IllegalArgumentException("actor_controller_contacts_unsupported");}
    }
    Session create(String id,long incarnation,World.Vec spawn);
    Session restore(ActorControllerState saved);
    Session migrate(ActorRuntime legacy);
    /** Release a serialized before-start action; the next bootstrap action may use another worker. */
    void endBootstrapAction();
    /** One explicit publication barrier before the world thread starts; no concurrent use is permitted. */
    void finishBootstrap();
}
