package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.util.*;

/** Semantic v1 import. Missing native-only fields retain genuine constructor defaults. */
final class NativeActorMigration {
    private final Class<?> stateType,poseType,inputType,controllerType;
    private final Object[] poses,gaits;
    private final Method rebuild,publish,orientation,transition,ladder;
    NativeActorMigration(ClassLoader loader)throws Exception{
        stateType=Class.forName("1943200621",true,loader);poseType=Class.forName("430277821",true,loader);inputType=Class.forName("1899091998",true,loader);controllerType=Class.forName("1704556748",true,loader);
        poses=poseType.getEnumConstants();gaits=Class.forName("1785380642",true,loader).getEnumConstants();
        rebuild=Arrays.stream(stateType.getMethods()).filter(m->m.getName().equals("long")&&m.getParameterCount()==0&&m.getReturnType()==void.class).findFirst().orElseThrow();
        publish=stateType.getMethod("do",Class.forName("200241649",true,loader));orientation=stateType.getMethod("int",float.class,float.class);transition=stateType.getMethod("while",poseType);
        ladder=Class.forName("1934838375",true,loader).getMethod("class",double.class);
    }
    private void set(Object state,String field,Object value)throws Exception{stateType.getField(field).set(state,value);}
    void seed(Object state,Object actor,Object input,ActorRuntime source,World.Vec local)throws Exception{
        if(!Set.of(0,1,5,6).contains(source.locomotion())||!Set.of(0,1,5,6).contains(source.previousLocomotion()))throw new IllegalArgumentException("actor_controller_legacy_pose_requires_explicit_migration");
        if(source.ladder()!=(source.locomotion()==5))throw new IllegalArgumentException("actor_controller_legacy_ladder_state");
        set(state,"s",local.x());set(state,"t",local.y());set(state,"u",local.z());set(state,"o",local.x());set(state,"p",local.y());set(state,"q",local.z());
        set(state,"av",source.velocity().x());set(state,"aw",source.velocity().y());set(state,"ax",source.velocity().z());
        set(state,"n",source.grounded());set(state,"double",source.water());set(state,"au",source.airborneTime());
        set(state,"ar",poses[source.locomotion()]);set(state,"v",poses[source.previousLocomotion()]);set(state,"native",source.poseSince());
        // v1 never stored the native takeoff height. Begin that missing history
        // at the imported feet, instead of treating world Y=0 as a previous jump.
        // The existing impulse, airborne timer and jump admission latch remain intact.
        set(state,"strictfp",local.y());
        var jump=source.jump();set(state,"return",jump.time());set(state,"catch",jump.lastJump());set(state,"ad",jump.armed());set(state,"long",jump.stamina());set(state,"class",jump.jumps());
        set(state,"l",gaits[jump.mode()]);set(state,"r",gaits[jump.mode()]);
        double[] times=(double[])stateType.getField("aq").get(state);int[] counts=(int[])stateType.getField("ap").get(state);
        times[6]=jump.lastPoseJump();counts[6]=jump.repetitions();if(source.locomotion()!=6)times[source.locomotion()]=source.poseSince();
        // Legacy could fall in its crouched body. Preserve the recorded shape until the game handles the next frame.
        float height=(Float)stateType.getMethod("const",poseType).invoke(null,poses[source.pose()]);set(state,"else",height);rebuild.invoke(state);
        float yaw=Math.hypot(source.velocity().x(),source.velocity().z())>1e-7?(float)Math.toDegrees(Math.atan2(-source.velocity().x(),source.velocity().z())):0;
        orientation.invoke(state,yaw,0f);inputType.getField("case").set(input,gaits[jump.mode()]);inputType.getField("finally").setBoolean(input,source.inputJump());inputType.getField("goto").setBoolean(input,source.pose()==1);
        publish.invoke(state,actor);
    }
    /** Only binds native map geometry; do not consume time, energy, or the saved impulse. */
    void bindLadder(Object controller,Object state,Object actor)throws Exception{
        Object module=controllerType.getField("case").get(controller);double x=stateType.getField("o").getDouble(state),y=stateType.getField("p").getDouble(state),z=stateType.getField("q").getDouble(state);
        boolean armed=stateType.getField("ad").getBoolean(state),found=false;
        for(double yaw:new double[]{0,90,180,-90})if((Boolean)ladder.invoke(module,yaw+90)){found=true;break;}
        if(found){set(state,"o",x);set(state,"p",y);set(state,"q",z);rebuild.invoke(state);orientation.invoke(state,stateType.getField("goto").getFloat(state),0f);}
        else{set(state,"continue",null);set(state,"n",false);transition.invoke(state,poses[6]);}
        set(state,"ad",armed);publish.invoke(state,actor);
    }
}
