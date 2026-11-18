package szserver.client;

import java.lang.reflect.*;
import java.util.function.*;
import szserver.world.*;

/** Supplies captured fields to untouched 820497227/1422788721 collider methods. */
final class NativeActorBodies {
    private final Object unsafe;private final Method allocate;private final Class<?> humanoid,creature,configuration;
    private final Field humanBox,creatureBox,config,enabled,owner,inactive,suppressed;
    NativeActorBodies(ClassLoader loader,BiFunction<String,byte[],Class<?>> define,Object unsafe,Method allocate)throws Exception{
        this.unsafe=unsafe;this.allocate=allocate;
        humanoid=define.apply("szserver.bridge.ActorHumanoidBody",NativeActorBridge.dynamicBody(false));creature=define.apply("szserver.bridge.ActorCreatureBody",NativeActorBridge.dynamicBody(true));configuration=Class.forName("1145363446",true,loader);
        humanBox=humanoid.getField("body");creatureBox=creature.getField("body");config=Class.forName("1422788721",true,loader).getField("h");enabled=configuration.getField("new");owner=configuration.getField("h");inactive=creature.getField("inactive");suppressed=creature.getField("suppressed");
        // Initialize both class chains away from the gameplay owner.
        prepare(new ActorBody("warm",ActorBody.Kind.HUMANOID,new SceneCollision.Box(0,0,0,1,1,1),true,false,false),null);
        prepare(new ActorBody("warm",ActorBody.Kind.CREATURE,new SceneCollision.Box(0,0,0,1,1,1),true,false,false),null);
    }
    Object prepare(ActorBody body,Object bounds){
        try{
            if(body.kind()==ActorBody.Kind.HUMANOID){Object actor=allocate.invoke(unsafe,humanoid);humanBox.set(actor,bounds);return actor;}
            Object actor=allocate.invoke(unsafe,creature),settings=allocate.invoke(unsafe,configuration);enabled.setBoolean(settings,body.collidable());owner.set(settings,actor);config.set(actor,settings);inactive.setBoolean(actor,body.inactive());suppressed.setBoolean(actor,body.suppressed());creatureBox.set(actor,bounds);return actor;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("native_actor_body",error);}
    }
}
