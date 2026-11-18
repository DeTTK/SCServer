package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;

/** Untouched installed velocity import; bridges only AI intent, world movement and local sound. */
final class NativeActorVelocity implements ActorDynamics {
    private final Object kernel,state,motion;private final Field callback,vx,vy,vz,ground,air,k,heading;
    private final Field water,viscous,deltaTime;private final Method integrate,contact,project;private final double jump;
    private Thread owner;
    private final NativeActorWater waterScan;private final NativeActorJump jumps;
    private final NativeActorSpeed speeds;private final Method nativeSpeed;
    private final NativeActorObstacles obstacles;
    NativeActorVelocity(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate)throws Exception{
        Class<?> velocity=Class.forName("154883352",true,loader),contactType=define.apply("szserver.bridge.ActorVelocityContact",NativeActorBridge.velocityContact()),motionType=define.apply("szserver.bridge.ActorVelocityMotion",NativeActorBridge.velocityMotion()),events=define.apply("szserver.bridge.ActorVelocityEvents",NativeActorBridge.velocityEvents());
        Class<?> st=Class.forName("1943200621",true,loader),controller=Class.forName("1704556748",true,loader),base=Class.forName("568168347",true,loader),configuration=Class.forName("632909433",true,loader),constraint=Class.forName("1276542651",true,loader),bounds=Class.forName("872804211",true,loader),movementMode=Class.forName("1785380642",true,loader),nativeMotion=Class.forName("58127775",true,loader);
        state=st.getConstructor().newInstance();kernel=allocate.invoke(unsafe,velocity);motion=allocate.invoke(unsafe,motionType);Object context=allocate.invoke(unsafe,controller),cfg=configuration.getConstructor().newInstance(),limit=constraint.getConstructor().newInstance();constraint.getMethod("long").invoke(limit);
        set(controller,"abstract",context,cfg);set(controller,"protected",context,motion);set(controller,"default",context,bounds.getConstructor().newInstance());set(controller,"final",context,allocate.invoke(unsafe,events));
        set(nativeMotion,"try",motion,allocate.invoke(unsafe,contactType));set(velocity,"const",kernel,limit);set(base,"enum",kernel,context);set(base,"byte",kernel,state);
        vx=st.getField("av");vy=st.getField("aw");vz=st.getField("ax");ground=st.getField("n");air=st.getField("au");water=st.getField("double");viscous=st.getField("void");deltaTime=st.getField("assert");k=st.getField("k");heading=st.getField("af");callback=motionType.getField("move");
        set(controller,"assert",context,state);
        speeds=new NativeActorSpeed(loader,define,unsafe,allocate,kernel,cfg,context);nativeSpeed=velocity.getMethod("while",st,float.class);
        obstacles=new NativeActorObstacles(loader,limit,speeds.actor);
        integrate=velocity.getMethod("import",Class.forName("1899091998",true,loader),double.class,double.class,double.class);contact=nativeMotion.getMethod("package",st,int.class,double.class,double.class,double.class,double.class);project=nativeMotion.getMethod("for",st);
        jump=Class.forName("89824967",true,loader).getField("long").getDouble(null);
        waterScan=new NativeActorWater(loader,define,unsafe,allocate,state);
        Movement warm=v->new Contact(new ActorMotion.Result(0,0,0,false,true,true),0,0);
        advance(new State(0,0,0,true,0,false,false),new World.Vec(3,0,0),.05,warm);
        advance(new State(1,-1,1,false,.4,false,false),new World.Vec(0,0,0),.05,warm);
        advance(new State(1,-1,1,false,.8,true,false),new World.Vec(1,0,1),.05,warm);
        advance(new State(1,-1,1,false,.8,false,true),new World.Vec(1,0,1),.05,warm);jumps=new NativeActorJump(loader,define,unsafe,allocate,state,context,cfg);owner=null;
    }
    private static void set(Class<?> type,String name,Object target,Object value)throws Exception{Field f=type.getField(name);f.setAccessible(true);f.set(target,value);}
    @Override public double jumpSpeed(){return jump;}
    @Override public boolean grounded(boolean previous,double airborneTime,java.util.function.BooleanSupplier support){
        // 1704556748.while: a missed solver contact is not itself a pose transition.
        return previous&&(airborneTime<=0||airborneTime<.4&&support.getAsBoolean());
    }
    @Override public boolean water(World.Vec p,BlockShapes.Voxels source){checkOwner();return waterScan.water(p,source);}
    ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt){checkOwner();return jumps.step(old,ground,ladder,held,moving,water,vy,y,dt);}
    @Override public boolean water(World.Vec p,BlockShapes.Voxels source,ActorPose pose){checkOwner();return waterScan.water(p,source,pose);}
    ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt,ActorPose pose){checkOwner();return jumps.step(old,ground,ladder,held,moving,water,vy,y,dt,pose);}
    ActorJump.Result jumpStep(ActorJump.State old,boolean ground,boolean ladder,boolean held,boolean moving,boolean water,double vy,double y,double dt,ActorPose pose,int mode){checkOwner();return jumps.step(old,ground,ladder,held,moving,water,vy,y,dt,pose,mode);}
    private void checkOwner(){if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new IllegalStateException("native_actor_velocity_owner");}
    @Override public Result advance(State old,World.Vec steering,double dt,Movement movement){
        // Compatibility entry point for callers supplying an explicit ground speed.
        double target=Math.hypot(steering.x(),steering.z());
        try{return advance(old,steering,dt,movement,new ActorLocomotion.Control(target>1e-8?old.water()?2:3:0,old.grounded()?0:6,0,0,0,new ActorLocomotion.Profile((float)(target/speeds.factor(3)),0,1,1,1,false)));}
        catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("native_actor_speed",e);}
    }
    @Override public double travelSpeed(ActorPose pose,boolean water){
        checkOwner();try{return speeds.factor(pose==ActorPose.CROUCH?3:water?2:4);}catch(Exception e){throw new IllegalStateException("native_actor_speed",e);}
    }
    @Override public Result advance(State old,World.Vec steering,double dt,Movement movement,ActorLocomotion.Control control){
        checkOwner();
        if(!Double.isFinite(dt)||dt<=0||dt>.1)throw new IllegalArgumentException("actor_velocity_dt");
        try{
            vx.setDouble(state,Math.abs(old.vx())<=.02?0:old.vx());vy.setDouble(state,old.vy());vz.setDouble(state,Math.abs(old.vz())<=.02?0:old.vz());ground.setBoolean(state,old.grounded());air.setDouble(state,old.airborneTime());water.setBoolean(state,old.water());viscous.setBoolean(state,old.viscous());deltaTime.setFloat(state,(float)dt);
            speeds.apply(state,control);obstacles.restore(old.contacts());
            double target=Math.hypot(steering.x(),steering.z()),full=(Float)nativeSpeed.invoke(kernel,state,1f),throttle=full>0?Math.min(1,target/full):0;
            k.setFloat(state,0);heading.setFloat(state,(float)(Math.toDegrees(Math.atan2(steering.z(),steering.x()))-90));
            ActorMotion.Result[] moved={null};callback.set(motion,(Runnable)()->{try{
                double x=vx.getDouble(state),y=vy.getDouble(state),z=vz.getDouble(state);var collision=movement.move(new World.Vec(x,y,z));moved[0]=collision.motion();var result=moved[0];
                // 58127775.finally retains n on a missed contact; the next controller frame validates it.
                ground.setBoolean(state,result.grounded()||old.grounded());air.setDouble(state,result.grounded()?0:old.airborneTime()+dt);
                contact.invoke(null,state,result.horizontalCollision()?1:0,x*dt,z*dt,collision.movedX(),collision.movedZ());
                if(result.horizontalCollision())project.invoke(null,state);
                if(result.verticalCollision())vy.setDouble(state,0);
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("native_actor_velocity_contact",e);}});
            integrate.invoke(kernel,null,0d,throttle,dt);
            return new Result(new State(vx.getDouble(state),vy.getDouble(state),vz.getDouble(state),ground.getBoolean(state),air.getDouble(state),old.water(),old.viscous(),old.contacts()),moved[0]);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("native_actor_velocity",e.getCause());}
        catch(Exception e){throw new IllegalStateException("native_actor_velocity",e);}
        finally{try{callback.set(motion,null);}catch(IllegalAccessException impossible){throw new IllegalStateException(impossible);}}
    }
}
