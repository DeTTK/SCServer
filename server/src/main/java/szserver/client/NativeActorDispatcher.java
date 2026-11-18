package szserver.client;

import szserver.world.ActorLocomotion;
import szserver.world.SceneCollision;
import szserver.world.World;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** The actual game dispatcher. Its host supplies native components and resident world access.
 * This does not create a world, substitute handlers, or grant a player movement authority.
 * ServerMain selects this adapter explicitly with world.actorController=native. */
final class NativeActorDispatcher {
    enum Pose { STAND, CROUCH, CLIMB, CLIMB_OVER, CLIMB_ON, LADDER, JUMP, PRONE, LOGOUT, KNOCKDOWN, FLY, SIT, ZIPLINE }
    record Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouchHeld,boolean proneToggle,szserver.world.ActorControllerEngine.Zipline zipline,szserver.world.ActorControllerEngine.Condition condition,szserver.world.ActorControllerEngine.Seat seat) {
        Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouchHeld,boolean proneToggle,szserver.world.ActorControllerEngine.Zipline zipline,szserver.world.ActorControllerEngine.Condition condition){this(yaw,pitch,heading,gait,jump,crouchHeld,proneToggle,zipline,condition,null);}
        Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouchHeld,boolean proneToggle,szserver.world.ActorControllerEngine.Zipline zipline){this(yaw,pitch,heading,gait,jump,crouchHeld,proneToggle,zipline,null);}
        Input(float yaw,float pitch,float heading,ActorLocomotion.Gait gait,boolean jump,boolean crouchHeld,boolean proneToggle){this(yaw,pitch,heading,gait,jump,crouchHeld,proneToggle,null);}
        Input { if(!Float.isFinite(yaw)||!Float.isFinite(pitch)||!Float.isFinite(heading)||gait==null)throw new IllegalArgumentException("actor_dispatch_input"); }
    }
    record Frame(World.Vec position,World.Vec velocity,Pose pose,Pose previous,ActorLocomotion.Gait gait,
                 boolean grounded,boolean water,double airborneTime,double time,double since,float energy,
                 List<SceneCollision.Box> body) {
        Frame { body=List.copyOf(body); }
    }
    private final Class<?> stateType,playerType,inputType,controllerType;
    private final Constructor<?> controllerConstructor,stateConstructor,inputConstructor,nativeBounds;
    private final Method bind,bindState,advance,write,read,publish,orient;
    private final Method ledgeBlocks,ledgeModels,ledgePoint,ledgeDistance;
    private final Field ledgeModule,ledgeLine;
    private final Field stateField,contextValues,events,actorWorld;
    private final Object[] nativeGaits;
    private final ClassLoader loader;
    private final Field[] bounds;
    private final Method ladderSources,makeBox;private final Field ladderModule,ladderPlane,sourcePlane;
    private Thread owner;
    private final Constructor<?> inputEvents,boardZipline,detachZipline;private final Field inputEventField,previousInput;private final Object noInputEvents;
    private final Method sceneForest,sceneById,sceneBehavior;private final Map<Class<?>,Method> ziplineTraces;
    private final NativeActorPersistence persistence;
    private final NativeActorMigration migration;
    private final NativeActorEffects effects;private final NativeActorZiplineAnimation ziplineAnimation;private final NativeActorEquipment equipment;
    private final NativeActorConditions conditions;
    private final NativeActorLife life;

    NativeActorDispatcher(Path runtime,ClassLoader loader,NativeActorEquipment equipment)throws Exception {
        this.loader=loader;this.equipment=equipment;life=new NativeActorLife(loader);
        ActorDispatcherRelease.verify(runtime);ActorTraversalRelease.verify(runtime);
        controllerType=Class.forName("1704556748",true,loader);stateType=Class.forName("1943200621",true,loader);
        playerType=Class.forName("200241649",true,loader);inputType=Class.forName("1899091998",true,loader);
        Class<?> context=Class.forName("358035993",true,loader),box=Class.forName("242639986",true,loader),aabb=Class.forName("1994198637",true,loader);
        Object[] poses=Class.forName("430277821",true,loader).getEnumConstants();nativeGaits=Class.forName("1785380642",true,loader).getEnumConstants();
        checkNames(poses,Pose.values());checkNames(nativeGaits,ActorLocomotion.Gait.values());
        controllerConstructor=controllerType.getConstructor(context);stateConstructor=stateType.getConstructor();inputConstructor=inputType.getConstructor();
        bind=controllerType.getMethod("float");bindState=controllerType.getMethod("assert",stateType);advance=controllerType.getMethod("while",inputType,float.class);
        write=stateType.getMethod("switch",DataOutput.class);read=stateType.getMethod("extends",DataInput.class);publish=stateType.getMethod("do",playerType);orient=stateType.getMethod("int",float.class,float.class);
        stateField=controllerType.getDeclaredField("assert");stateField.setAccessible(true);contextValues=context.getDeclaredField("void");contextValues.setAccessible(true);
        events=controllerType.getDeclaredField("final");events.setAccessible(true);
        actorWorld=Class.forName("1217489680",true,loader).getDeclaredField("interface");actorWorld.setAccessible(true);
        nativeBounds=aabb.getConstructor(box);bounds=new Field[6];String[] names={"return","void","long","class","final","default"};for(int n=0;n<6;n++)bounds[n]=aabb.getField(names[n]);
        ladderSources=Class.forName("1934838375",true,loader).getMethod("this",box);ladderModule=controllerType.getField("case");ladderPlane=stateType.getField("continue");sourcePlane=Class.forName("224002626",true,loader).getField("implements");makeBox=box.getMethod("synchronized",double.class,double.class,double.class,double.class,double.class,double.class);
        Class<?> ledges=Class.forName("1309397527",true,loader),line=Class.forName("1585574065",true,loader);
        ledgeModule=controllerType.getField("super");ledgeLine=stateType.getField("interface");
        ledgeBlocks=ledges.getMethod("this",box,double.class);ledgeModels=ledges.getMethod("static",box,double.class);
        ledgePoint=line.getMethod("float",double.class,double.class,double.class);ledgeDistance=line.getMethod("while",double.class,double.class,double.class);
        var forestType=Class.forName("493563411",true,loader);sceneForest=forestType.getMethod("try",Class.forName("1500514786",true,loader));sceneById=forestType.getMethod("throws",UUID.class);sceneBehavior=Class.forName("1886271463",true,loader).getMethod("private");
        Map<Class<?>,Method> traces=new HashMap<>();for(String name:List.of("1122096288","829855000")){var behavior=Class.forName(name,true,loader);traces.put(behavior,behavior.getMethod("catch",playerType));}ziplineTraces=Map.copyOf(traces);
        inputEvents=Class.forName("861754670",true,loader).getConstructor(List.class);noInputEvents=inputEvents.newInstance(List.of());
        boardZipline=Class.forName("296780051",true,loader).getConstructor(UUID.class);detachZipline=Class.forName("228081277",true,loader).getConstructor(UUID.class);inputEventField=inputType.getField("long");previousInput=controllerType.getDeclaredField("return");previousInput.setAccessible(true);
        persistence=new NativeActorPersistence(loader,equipment);
        migration=new NativeActorMigration(loader);
        effects=new NativeActorEffects(loader);ziplineAnimation=new NativeActorZiplineAnimation(loader);conditions=new NativeActorConditions(runtime,loader);
    }
    private static void checkNames(Object[] nativeValues,Enum<?>[] values){
        if(nativeValues.length!=values.length)throw new IllegalStateException("actor_dispatch_enum_count");
        for(int i=0;i<values.length;i++)if(!((Enum<?>)nativeValues[i]).name().equals(values[i].name()))throw new IllegalStateException("actor_dispatch_enum_order");
    }
    private void owner(){if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("actor_dispatch_owner");}
    /** Only the factory's discarded startup session may release its construction thread. */
    void finishFactoryWarmup(){if(owner!=Thread.currentThread())throw new IllegalStateException("actor_dispatch_warmup_owner");owner=null;}
    void finishBootstrap(){if(owner!=null&&owner!=Thread.currentThread())throw new IllegalStateException("actor_dispatch_bootstrap_owner");owner=null;}
    Session create(Object context,Object localSoundEvents)throws Exception {
        owner();Object controller=controllerConstructor.newInstance(context);
        if(((Map<?,?>)controllerType.getField("switch").get(controller)).size()!=Pose.values().length-1)throw new IllegalStateException("actor_dispatch_handlers");
        // This existing bridge suppresses only local footsteps/splash audio. Pose/admission events remain native.
        if(localSoundEvents!=null)events.set(controller,localSoundEvents);
        @SuppressWarnings("unchecked") Map<String,Object> components=(Map<String,Object>)contextValues.get(context);
        String key=(String)controllerType.getField("continue").get(null);Object previous=components.put(key,controller);
        try{bind.invoke(controller);return new Session(controller,context.getClass().getField("int").get(context));}
        catch(Exception failure){if(previous==null)components.remove(key);else components.put(key,previous);throw failure;}
    }
    final class Session {
        private final Object controller,actor,input;
        private boolean crouchHeld,legacyLadder;
        boolean storedLife=true;
        private Session(Object controller,Object actor)throws Exception {this.controller=controller;this.actor=actor;input=inputConstructor.newInstance();}
        private Object state()throws IllegalAccessException{return stateField.get(controller);}
        Object nativeWorld()throws IllegalAccessException{return actorWorld.get(actor);}
        Object actor(){owner();return actor;}
        boolean aiming()throws Exception{owner();return equipment.aiming(actor);}
        void health(float value)throws Exception{owner();life.health(actor,value);if(value==0)equipment.buffs.death(actor,controller);}
        szserver.world.ActorVitals vitals()throws Exception{owner();return life.vitals(actor);}
        Map<String,Float> stats()throws Exception{owner();return NativeActorLife.stats(actor);}
        void legacyStats(Map<String,Float> values)throws Exception{owner();life.stats(actor,values);equipment.buffs.legacy(actor,controller);}
        void initializeBuffs()throws Exception{equipment.buffs.initialize(actor,controller);}
        void bindWorldClock(long tick)throws Exception{owner();life.bindClock(actor,tick);}
        long durationClock(float seconds)throws Exception{owner();return life.durationClock(actor,seconds);}
        void advanceBuffs(long tick)throws Exception{
            owner();if(life.clock(actor)<0)life.bindClock(actor,Math.max(0,tick-1));
            if(tick<life.clock(actor))throw new IllegalArgumentException("actor_recovery_clock_rewind");
            for(long t=life.clock(actor)+1;t<=tick;t++){life.periodic(actor,t);equipment.buffs.advance(actor,controller,t);}
            if(life.health(actor)==0)equipment.buffs.death(actor,controller);
        }
        long buffTick()throws Exception{owner();return Math.max(life.clock(actor),equipment.buffs.tick(actor));}
        List<szserver.world.ActorBuff.View> buffs()throws Exception{owner();return equipment.buffs.views(actor);}
        void medicine(szserver.world.ActorControllerEngine.PreparedMedicine value,long tick)throws Exception{owner();var saved=checkpoint();try{advanceBuffs(tick);equipment.buffs.use(actor,controller,value,tick);}catch(Exception failure){saved.restore();throw failure;}}
        void removeBuff(int id,long tick)throws Exception{owner();var saved=checkpoint();try{advanceBuffs(tick);equipment.buffs.remove(actor,controller,id,tick);}catch(Exception failure){saved.restore();throw failure;}}
        void livingStep(float seconds)throws Exception{owner();try(var clock=effects.clock(number(state(),"return"))){life.step(actor,seconds);if(life.health(actor)==0)equipment.buffs.death(actor,controller);}}
        void aiming(boolean value)throws Exception{owner();equipment.aiming(actor,value);}
        void equipment(szserver.world.ActorControllerEngine.PreparedEquipment prepared)throws Exception{owner();var saved=checkpoint();try{equipment.apply(controller,actor,prepared);}catch(Exception failure){saved.restore();throw failure;}}
        UUID identity()throws Exception{return (UUID)Class.forName("1217489680",true,loader).getMethod("interface").invoke(actor);}
        float yaw()throws Exception{owner();return stateType.getField("af").getFloat(state());}
        int ziplineDirection()throws Exception{owner();if(ziplineIdentity()==null)return 0;Object path=stateType.getField("bd").get(state());return path==null?0:path.getClass().getField("final").getInt(path);}
        UUID ziplineIdentity()throws Exception{owner();Object s=state();return ((Enum<?>)stateType.getField("ar").get(s)).ordinal()==Pose.ZIPLINE.ordinal()?(UUID)stateType.getField("byte").get(s):null;}
        Float ladderFacing()throws Exception{owner();Object s=state();return ((Enum<?>)stateType.getField("ar").get(s)).ordinal()==Pose.LADDER.ordinal()?stateType.getField("goto").getFloat(s):null;}
        /** Called only inside a resident map transaction, never on a standalone fixture host. */
        boolean ladderSourcePresent()throws Exception{
            owner();Object s=state();if(((Enum<?>)stateType.getField("ar").get(s)).ordinal()!=Pose.LADDER.ordinal())return true;
            Object held=ladderPlane.get(s);if(held==null)return false;
            var b=box(methodReturning(stateType,"continue",nativeBounds.getParameterTypes()[0]).invoke(s));
            Object area=makeBox.invoke(null,b.x()-.2,b.y()-.2,b.z()-.2,b.xx()+.2,b.yy()+.2,b.zz()+.2);
            for(Object candidate:(List<?>)ladderSources.invoke(ladderModule.get(controller),area))if(held.equals(sourcePlane.get(candidate)))return true;return false;
        }
        /** Re-query the installed edge providers at the held point, within the current map transaction.
         * The native hang handler retains its old line even after the supporting geometry is removed. */
        boolean climbSourcePresent()throws Exception{
            owner();Object s=state();if(((Enum<?>)stateType.getField("ar").get(s)).ordinal()!=Pose.CLIMB.ordinal())return true;
            Object held=ledgeLine.get(s);if(held==null)return false;
            Object p=ledgePoint.invoke(held,number(s,"o"),number(s,"p")+1.8,number(s,"q"));
            double x=number(p,"case"),y=number(p,"void"),z=number(p,"import");
            Object area=makeBox.invoke(null,x-.2,y-.2,z-.2,x+.2,y+.2,z+.2),module=ledgeModule.get(controller);
            double facing=Math.toRadians(number(s,"for"));
            for(Method query:List.of(ledgeBlocks,ledgeModels))for(Object candidate:(List<?>)query.invoke(module,area,facing))
                if(((Number)ledgeDistance.invoke(candidate,x,y,z)).doubleValue()<1e-6)return true;
            return false;
        }
        szserver.world.ActorTraversal traversal()throws Exception{return traversal(0,0);}
        szserver.world.ActorTraversal traversal(int originX,int originZ)throws Exception{
            owner();Object s=state();var pose=(Enum<?>)stateType.getField("ar").get(s);
            if(pose.name().equals("ZIPLINE"))return new szserver.world.ActorTraversal(null,null,ziplineAnimation.value(s,actor,originX,originZ));
            if(pose.name().equals("SIT")){Object p=stateType.getField("aj").get(s);if(p==null)throw new IllegalStateException("native_actor_seat_anchor");return new szserver.world.ActorTraversal(null,null,null,new szserver.world.ActorTraversal.Seat((UUID)stateType.getField("instanceof").get(s),stateType.getField("am").getInt(s),new World.Vec(number(p,"case"),number(p,"void"),number(p,"import")),stateType.getField("ag").getFloat(s),(String)stateType.getField("al").get(s),stateType.getField("ai").getBoolean(s),number(s,"ah")>0)).translated(originX,originZ);}
            // Exact local animation mapping in installed 873944585, independent of old snapshot offsets.
            if(!pose.name().equals("CLIMB")&&!pose.name().equals("CLIMB_ON"))return szserver.world.ActorTraversal.EMPTY;
            Object line=stateType.getField("interface").get(s);szserver.world.ActorTraversal.Edge edge=null;
            if(line!=null)edge=new szserver.world.ActorTraversal.Edge(number(line,"double"),number(line,"if"),number(line,"long"),number(line,"goto"),number(line,"char"),number(line,"do"),stateType.getField("switch").getFloat(s));
            var transition=pose.name().equals("CLIMB_ON")?new szserver.world.ActorTraversal.Transition(coordinate(s,"transient"),coordinate(s,"short"),coordinate(s,"super"),stateType.getField("do").getFloat(s),coordinate(s,"float"),coordinate(s,"synchronized"),stateType.getField("protected").getFloat(s),0,stateType.getField("char").getFloat(s),stateType.getField("while").getBoolean(s),((Enum<?>)stateType.getField("try").get(s)).ordinal()):null;
            return new szserver.world.ActorTraversal(edge,transition).translated(originX,originZ);
        }
        szserver.world.ActorJump.State jumpState()throws Exception{
            owner();Object s=state();return new szserver.world.ActorJump.State(number(s,"return"),number(s,"catch"),stateType.getField("ad").getBoolean(s),stateType.getField("long").getFloat(s),stateType.getField("class").getInt(s),((int[])stateType.getField("ap").get(s))[Pose.JUMP.ordinal()],((double[])stateType.getField("aq").get(s))[Pose.JUMP.ordinal()],((Enum<?>)stateType.getField("l").get(s)).ordinal());
        }
        void migrate(szserver.world.ActorRuntime source,World.Vec local)throws Exception{owner();migration.seed(state(),actor,input,source,local);life.health(actor,source.health());storedLife=false;crouchHeld=source.pose()==1;legacyLadder=source.ladder();}
        boolean prepared(){return !legacyLadder;}
        void prepareLegacy()throws Exception{owner();if(legacyLadder){migration.bindLadder(controller,state(),actor);legacyLadder=false;}}
        byte[] durableBytes()throws Exception{owner();if(legacyLadder)throw new IllegalStateException("actor_controller_migration_pending");life.validate(actor);return persistence.write(stateBytes(),controller,actor,input,crouchHeld);}
        void restoreDurable(byte[] bytes)throws Exception {
            owner();var decoded=persistence.read(bytes,controller,actor,input);var checkpoint=checkpoint();
            try{
                restoreState(decoded.state());decoded.apply();
                // Restore the full state (including transient constraints/camera) and all module aliases.
                bindState.invoke(controller,state());life.validate(actor);crouchHeld=decoded.crouch();storedLife=decoded.living();
            }catch(Exception failure){checkpoint.restore();throw failure;}
        }
        final class Checkpoint {
            private final NativeActorCheckpoint values;private final boolean crouch,pending,stored;
            private final Runnable effectValues;
            private Checkpoint()throws Exception{values=new NativeActorCheckpoint(loader,controller,actor,input);effectValues=effects.checkpoint(actor);crouch=crouchHeld;pending=legacyLadder;stored=storedLife;}
            void restore()throws Exception{owner();values.restore();effectValues.run();crouchHeld=crouch;legacyLadder=pending;storedLife=stored;}
        }
        Checkpoint checkpoint()throws Exception{owner();return new Checkpoint();}
        Frame step(Input intent,float seconds)throws Exception {
            return step(intent,seconds,null);
        }
        Frame step(Input intent,float seconds,Object seatAction)throws Exception {
            owner();if(!Float.isFinite(seconds)||seconds<=0||seconds>.1f)throw new IllegalArgumentException("actor_dispatch_dt");
            if(legacyLadder)throw new IllegalStateException("actor_controller_migration_pending");
            orient.invoke(state(),intent.yaw(),intent.pitch());inputType.getField("int").setFloat(input,intent.heading());
            inputType.getField("case").set(input,nativeGaits[intent.gait().ordinal()]);inputType.getField("finally").setBoolean(input,intent.jump());
            inputType.getField("continue").setBoolean(input,intent.crouchHeld()&&!crouchHeld);inputType.getField("goto").setBoolean(input,intent.crouchHeld());inputType.getField("interface").setBoolean(input,intent.proneToggle());
            boolean boardAllowed=true;
            if(intent.zipline()!=null&&!intent.zipline().detach()){
                publish.invoke(state(),actor);Object object=sceneById.invoke(sceneForest.invoke(null,nativeWorld()),intent.zipline().object());boardAllowed=false;
                if(object!=null){Object behavior=sceneBehavior.invoke(object);var trace=ziplineTraces.get(behavior.getClass());boardAllowed=trace!=null&&(boolean)trace.invoke(behavior,actor);}
            }
            Object actions=seatAction!=null?inputEvents.newInstance(List.of(seatAction)):intent.zipline()==null||!boardAllowed?noInputEvents:inputEvents.newInstance(List.of((intent.zipline().detach()?detachZipline:boardZipline).newInstance(intent.zipline().object())));
            inputEventField.set(input,actions);
            try(var clock=effects.clock(number(state(),"return")+seconds)){conditions.apply(controller,actor,intent.condition());effects.step(actor);advance.invoke(controller,input,seconds);publish.invoke(state(),actor);crouchHeld=intent.crouchHeld();return frame();}
            catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("native_actor_dispatch",e.getCause());}
            finally{inputEventField.set(input,noInputEvents);Object previous=previousInput.get(controller);if(previous!=null)inputEventField.set(previous,noInputEvents);}
        }
        Frame frame()throws Exception {
            owner();Object s=state();List<SceneCollision.Box> shapes=new ArrayList<>();
            shapes.add(box(methodReturning(stateType,"continue",nativeBounds.getParameterTypes()[0]).invoke(s)));
            for(Object part:(List<?>)stateType.getField("y").get(s))shapes.add(box(part.getClass().getField("short").get(part)));
            return new Frame(new World.Vec(number(s,"o"),number(s,"p"),number(s,"q")),new World.Vec(number(s,"av"),number(s,"aw"),number(s,"ax")),
                Pose.valueOf(((Enum<?>)stateType.getField("ar").get(s)).name()),Pose.valueOf(((Enum<?>)stateType.getField("v").get(s)).name()),
                ActorLocomotion.Gait.valueOf(((Enum<?>)stateType.getField("l").get(s)).name()),stateType.getField("n").getBoolean(s),stateType.getField("double").getBoolean(s),
                number(s,"au"),number(s,"return"),number(s,"native"),stateType.getField("long").getFloat(s),shapes);
        }
        /** Native wire state only. Host/module timers and world references are a separate persistence obligation. */
        byte[] stateBytes()throws Exception {
            owner();var bytes=new ByteArrayOutputStream();write.invoke(state(),new DataOutputStream(bytes));
            if(bytes.size()>65536)throw new IOException("actor_dispatch_state_size");return bytes.toByteArray();
        }
        /** Decodes and checks a replacement before binding it. Does not replay pose-entry callbacks. */
        void restoreState(byte[] bytes)throws Exception {
            owner();if(bytes==null||bytes.length==0||bytes.length>65536)throw new IOException("actor_dispatch_state_size");
            Object replacement=stateConstructor.newInstance();BoundedNativeInput in=new BoundedNativeInput(bytes);
            try{read.invoke(replacement,in);}catch(InvocationTargetException e){throw new IOException("actor_dispatch_state_invalid",e.getCause());}
            if(in.available()!=0||((double[])stateType.getField("aq").get(replacement)).length!=Pose.values().length||((int[])stateType.getField("ap").get(replacement)).length!=Pose.values().length)
                throw new IOException("actor_dispatch_state_layout");
            if(((Enum<?>)stateType.getField("ar").get(replacement)).name().equals("CLIMB_OVER"))throw new IOException("actor_dispatch_deprecated_pose");
            // Native properties contain encoded coordinates: publish through the original setters, never copy their raw bits.
            bindState.invoke(controller,replacement);publish.invoke(replacement,actor);
        }
    }
    private double coordinate(Object state,String field)throws Exception{return (double)stateType.getMethod("void",double.class).invoke(null,number(state,field));}
    private double number(Object value,String name)throws Exception{return value.getClass().getField(name).getDouble(value);}
    private SceneCollision.Box box(Object value)throws Exception {
        Object aabb=nativeBounds.newInstance(value);return new SceneCollision.Box(bounds[0].getDouble(aabb),bounds[1].getDouble(aabb),bounds[2].getDouble(aabb),bounds[3].getDouble(aabb),bounds[4].getDouble(aabb),bounds[5].getDouble(aabb));
    }
    // The installed bytecode legally has methods differing only in return type. getMethod(name) is ambiguous.
    private static Method methodReturning(Class<?> type,String name,Class<?> result)throws NoSuchMethodException {
        for(Method method:type.getMethods())if(method.getName().equals(name)&&method.getParameterCount()==0&&method.getReturnType()==result)return method;
        throw new NoSuchMethodException(name+":"+result.getName());
    }
}
