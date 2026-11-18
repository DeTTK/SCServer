package szserver.client;
import szserver.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;

/** Untouched 1775200393.protected/for and 1960601713 energy methods on isolated actor state. */
final class NativeActorJump {
    private final Object state,input,jump,energy,stand,crouch,jumpPose,ladderPose;private final Object[] modes;
    private final Field time,last,armed,stamina,count,repeats,poseTimes,pose,previous,ground,vy,height,pressed,inputMode;
    private final Method admit,transition,profile;private final int jumpOrdinal;
    NativeActorJump(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate,Object state,Object context,Object configuration)throws Exception{
        this.state=state;Class<?> st=state.getClass(),base=Class.forName("568168347",true,loader),jp=Class.forName("1775200393",true,loader),in=Class.forName("1899091998",true,loader),poses=Class.forName("430277821",true,loader),mode=Class.forName("1785380642",true,loader),ep=define.apply("szserver.bridge.ActorStamina",NativeActorBridge.stamina());
        energy=allocate.invoke(unsafe,ep);jump=allocate.invoke(unsafe,jp);input=allocate.invoke(unsafe,in);
        for(Object service:List.of(energy,jump)){base.getField("enum").set(service,context);base.getField("byte").set(service,state);}
        context.getClass().getField("assert").set(context,state);Field field=context.getClass().getField("native");field.setAccessible(true);field.set(context,energy);
        ep.getField("this").setFloat(energy,configuration.getClass().getField("static").getFloat(configuration));ep.getField("static").setFloat(energy,Math.max(0,(5+configuration.getClass().getField("switch").getFloat(configuration))/5));ep.getField("goto").setBoolean(energy,false);
        stand=poses.getField("STAND").get(null);crouch=poses.getField("CROUCH").get(null);jumpPose=poses.getField("JUMP").get(null);ladderPose=poses.getField("LADDER").get(null);jumpOrdinal=((Enum<?>)jumpPose).ordinal();modes=mode.getEnumConstants();
        time=st.getField("return");last=st.getField("catch");armed=st.getField("ad");stamina=st.getField("long");count=st.getField("class");repeats=st.getField("ap");poseTimes=st.getField("aq");pose=st.getField("ar");previous=st.getField("v");ground=st.getField("n");vy=st.getField("aw");height=st.getField("p");pressed=in.getField("finally");inputMode=in.getField("case");
        admit=jp.getMethod("protected",in);transition=st.getMethod("while",poses);profile=ep.getMethod("super",poses,mode,mode,boolean.class,boolean.class,boolean.class,float.class);
        step(ActorJump.State.initial(),true,false,false,false,false,0,1,.05);step(new ActorJump.State(.05,0,true,1,0,0,0,0),true,false,true,true,false,-2.4,1,.05);
    }
    ActorJump.Result step(ActorJump.State old,boolean onGround,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt){return step(old,onGround,ladder,held,moving,water,velocityY,feetY,dt,ActorPose.STAND);}
    ActorJump.Result step(ActorJump.State old,boolean onGround,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt,ActorPose bodyPose){
        return step(old,onGround,ladder,held,moving,water,velocityY,feetY,dt,bodyPose,moving?water&&!ladder?2:3:0);
    }
    ActorJump.Result step(ActorJump.State old,boolean onGround,boolean ladder,boolean held,boolean moving,boolean water,double velocityY,double feetY,double dt,ActorPose bodyPose,int requestedMode){
        if(!Double.isFinite(dt)||dt<=0||dt>.1||!Double.isFinite(old.time())||old.mode()<0||old.mode()>=modes.length)throw new IllegalArgumentException("native_actor_jump_state");
        if(requestedMode<0||requestedMode>=modes.length)throw new IllegalArgumentException("native_actor_movement_mode");
        try{
            time.setDouble(state,old.time()+dt);last.setDouble(state,old.lastJump());armed.setBoolean(state,old.armed());stamina.setFloat(state,old.stamina());count.setInt(state,old.jumps());ground.setBoolean(state,onGround);vy.setDouble(state,velocityY);height.setDouble(state,feetY);
            int[] counts=(int[])repeats.get(state);double[] times=(double[])poseTimes.get(state);Arrays.fill(counts,0);Arrays.fill(times,0);counts[jumpOrdinal]=old.repetitions();times[jumpOrdinal]=old.lastPoseJump();Object groundedPose=bodyPose==ActorPose.CROUCH?crouch:stand;Object current=ladder?ladderPose:onGround?groundedPose:jumpPose;pose.set(state,current);previous.set(state,current);
            pressed.setBoolean(input,held);int wanted=!moving?0:ladder?3:bodyPose==ActorPose.CROUCH?3:requestedMode;inputMode.set(input,modes[wanted]);
            boolean accepted=!ladder&&(Boolean)admit.invoke(jump,input);if(accepted)transition.invoke(state,jumpPose);
            Object nextMode=profile.invoke(energy,ladder?ladderPose:groundedPose,modes[old.mode()],modes[wanted],false,ladder||ground.getBoolean(state),false,(float)dt);
            // This is the controller's real release latch, after the action attempt.
            boolean nextArmed=armed.getBoolean(state)||!held;
            var next=new ActorJump.State(time.getDouble(state),last.getDouble(state),nextArmed,stamina.getFloat(state),count.getInt(state),counts[jumpOrdinal],times[jumpOrdinal],((Enum<?>)nextMode).ordinal());
            return new ActorJump.Result(next,vy.getDouble(state),ground.getBoolean(state),accepted);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("native_actor_jump",e.getCause());}
        catch(Exception e){throw new IllegalStateException("native_actor_jump",e);}
    }
}
