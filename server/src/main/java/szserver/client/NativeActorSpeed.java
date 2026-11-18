package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;

/** Data adapter only: speed, air-control and landing rules execute in 154883352.while. */
final class NativeActorSpeed {
    private final Object configuration,status,condition;private final Object[] modes,poses;
    private final Field base,bonus,multiplier,encumbered,statusValue,conditionValue,mode,pose,previous,time,since;
    private final Method factor;
    final Object actor;
    NativeActorSpeed(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate,Object kernel,Object configuration,Object controller)throws Exception {
        this.configuration=configuration;
        Class<?> actorType=define.apply("szserver.bridge.ActorSpeedProfile",NativeActorBridge.speedActor());
        Class<?> components=Class.forName("358035993",true,loader),statsType=Class.forName("596586228",true,loader),properties=Class.forName("1798539107",true,loader),weaponType=Class.forName("1810277368",true,loader);
        actor=allocate.invoke(unsafe,actorType);Object context=allocate.invoke(unsafe,components),stats=allocate.invoke(unsafe,statsType),weapon=allocate.invoke(unsafe,weaponType);
        Map<String,Object> values=new HashMap<>();values.put((String)statsType.getField("instanceof").get(null),stats);values.put((String)weaponType.getField("void").get(null),weapon);
        values.put((String)controller.getClass().getField("continue").get(null),controller);
        Field table=components.getField("void");table.setAccessible(true);table.set(context,values);actorType.getField("components").set(actor,context);
        status=allocate.invoke(unsafe,properties);condition=allocate.invoke(unsafe,properties);statusValue=conditionValue=properties.getField("do");
        statusValue.set(status,1f);conditionValue.set(condition,1f);statsType.getField("while").set(stats,status);statsType.getField("for").set(stats,condition);
        Class.forName("568168347",true,loader).getField("abstract").set(kernel,actor);
        Class<?> cfg=configuration.getClass(),state=Class.forName("1943200621",true,loader),gait=Class.forName("1785380642",true,loader);
        modes=gait.getEnumConstants();poses=Class.forName("430277821",true,loader).getEnumConstants();
        for(var value:ActorLocomotion.Gait.values())if(!((Enum<?>)modes[value.ordinal()]).name().equals(value.name()))throw new IllegalStateException("actor_native_gait_mismatch");
        base=cfg.getField("super");bonus=cfg.getField("native");multiplier=cfg.getField("while");encumbered=cfg.getField("case");
        mode=state.getField("l");pose=state.getField("ar");previous=state.getField("v");time=state.getField("return");since=state.getField("native");
        factor=Class.forName("154883352",true,loader).getMethod("void",cfg,gait);
    }
    double factor(int mode)throws Exception{return (Double)factor.invoke(null,configuration,modes[mode]);}
    void apply(Object state,ActorLocomotion.Control control)throws Exception {
        var p=control.profile();base.setFloat(configuration,p.baseSpeed());bonus.setFloat(configuration,p.sprintBonus());multiplier.setFloat(configuration,p.speedMultiplier());encumbered.setBoolean(configuration,p.encumbered());
        statusValue.set(status,p.statusMultiplier());conditionValue.set(condition,p.conditionFactor());
        mode.set(state,modes[control.mode()]);pose.set(state,poses[control.posture()]);previous.set(state,poses[control.previousPosture()]);time.setDouble(state,control.time());since.setDouble(state,control.poseSince());
    }
}
