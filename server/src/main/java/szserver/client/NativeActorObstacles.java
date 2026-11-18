package szserver.client;

import java.lang.reflect.*;
import java.util.*;
import szserver.world.ActorContacts;

/** The installed obstacle callback and constraint merge, applied to the actor's previous contacts. */
final class NativeActorObstacles {
    private final Object constraint,actor;private final Method reset,apply;private final Constructor<?> create;
    private final Map<Float,Object> effects=new LinkedHashMap<>();
    NativeActorObstacles(ClassLoader loader,Object constraint,Object actor)throws Exception{
        this.constraint=constraint;this.actor=actor;
        Class<?> effect=Class.forName("612654711",true,loader),type=Class.forName("1276542651",true,loader);
        create=effect.getConstructor(Class.forName("1886271463",true,loader),float.class,float.class);
        apply=effect.getMethod("abstract",Class.forName("200241649",true,loader),type);reset=type.getMethod("long");
        String[] strings=(String[])Class.forName("1281655698",true,loader).getField("if").get(null);
        if(!strings[40554].equals("deceleration")||!strings[28145].equals("damage"))throw new IllegalStateException("native_obstacle_property_mismatch");
        // Initialize callback dependencies before the gameplay owner starts ticking.
        restore(new ActorContacts.State(Map.of(new ActorContacts.Key(0,0,new UUID(0,0),false,0,0),new ActorContacts.Obstacle(.5f)),0,0,0));
        restore(ActorContacts.State.EMPTY);
    }
    void restore(ActorContacts.State contacts)throws Exception{
        reset.invoke(constraint);
        for(var obstacle:contacts.active().values()){
            if(!obstacle.applies())continue;
            float amount=obstacle.deceleration();Object effect=effects.get(amount);
            if(effect==null){effect=create.newInstance(null,amount,0f);if(effects.size()>=256)effects.remove(effects.keySet().iterator().next());effects.put(amount,effect);}
            apply.invoke(effect,actor,constraint);
        }
    }
}
