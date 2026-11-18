package szserver.client;

import szserver.world.*;
import java.lang.reflect.*;
import java.util.*;

/** Ordinary obstacle objects in the game's own 251809992 contact dispatcher.
 * Resident worker bounds are already transformed by the genuine feedback geometry.
 * Missing geometry on restored contacts is deliberately unbound, never queried as a shape. */
final class NativeActorSceneFeedback {
    static final class Factory {
        final Class<?> objectType,feedbackType,behaviourType,actorType;
        final Object unsafe;final Method allocate,contacts,applyEffect,controller,resetConstraint;final Constructor<?> bounds,effect;
        final Field parentBehaviour,parentActive,parentDeleted,feedbackParent,feedbackBounds,feedbackName,obstacle;
        final Set<?> serverScratch;
        final Field motion,previousConstraint;
        final Class<?> livingType;final Field livingParent,livingHealth,livingMaximum,livingEffect;
        Factory(ClassLoader loader,Object unsafe,Method allocate)throws Exception{
            this.unsafe=unsafe;this.allocate=allocate;
            objectType=Class.forName("1886271463",true,loader);feedbackType=Class.forName("903809321",true,loader);behaviourType=Class.forName("1143492736",true,loader);actorType=Class.forName("820497227",true,loader);
            bounds=Class.forName("1994198637",true,loader).getConstructor(double.class,double.class,double.class,double.class,double.class,double.class);
            effect=Class.forName("612654711",true,loader).getConstructor(objectType,float.class,float.class);
            livingType=Class.forName("958782514",true,loader);livingParent=field(Class.forName("1079929180",true,loader),"for");Class<?> livingBase=Class.forName("1562480994",true,loader);livingHealth=field(livingBase,"abstract");livingMaximum=field(livingBase,"if");livingEffect=field(livingType,"public");
            Class<?> constraintType=Class.forName("1276542651",true,loader);applyEffect=effect.getDeclaringClass().getMethod("abstract",Class.forName("200241649",true,loader),constraintType);resetConstraint=constraintType.getMethod("long");
            Class<?> controllerType=Class.forName("1704556748",true,loader);controller=controllerType.getMethod("return",Class.forName("200241649",true,loader));motion=controllerType.getField("protected");previousConstraint=Class.forName("58127775",true,loader).getField("double");
            parentBehaviour=field(objectType,"byte");parentActive=field(objectType,"interface");parentDeleted=field(objectType,"this");
            feedbackParent=field(feedbackType,"catch");feedbackBounds=field(feedbackType,"strictfp");feedbackName=field(feedbackType,"int");obstacle=field(behaviourType,"final");contacts=actorType.getMethod("this");
            var solver=Class.forName("251809992",true,loader);Object scratch=solver.getField("protected").get(null);serverScratch=(Set<?>)field(scratch.getClass(),"short").get(scratch);
            // Initialize native callbacks and effect construction before any owner frame.
            create(new ActorContacts.Obstacle(.5f));
        }
        private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
        Object create(ActorContacts.Obstacle value)throws Exception{
            Object parent=allocate.invoke(unsafe,objectType),behaviour=allocate.invoke(unsafe,value.living()==null?behaviourType:livingType),feedback=allocate.invoke(unsafe,feedbackType);
            Object nativeEffect=effect.newInstance(parent,value.deceleration(),0f);
            if(value.living()==null)obstacle.set(behaviour,nativeEffect);
            else{livingParent.set(behaviour,parent);livingHealth.setFloat(behaviour,value.living().health());livingMaximum.setFloat(behaviour,value.living().maximum());livingEffect.set(behaviour,nativeEffect);}
            parentBehaviour.set(parent,behaviour);parentActive.setBoolean(parent,true);parentDeleted.setBoolean(parent,false);
            feedbackParent.set(feedback,parent);feedbackName.set(feedback,"server-obstacle");return feedback;
        }
        Object bounds(SceneCollision.Box b,int x,int z)throws Exception{return bounds.newInstance(b.x()-x,b.y(),b.z()-z,b.xx()-x,b.yy(),b.zz()-z);}
        @SuppressWarnings("unchecked") Set<Object> contacts(Object actor)throws Exception{return (Set<Object>)contacts.invoke(actor);}
        void clearScratch(){serverScratch.clear();}
    }
    private record Token(ActorContacts.Obstacle obstacle,Object nativeValue){}
    private final Factory factory;private final int originX,originZ;
    private final Map<ActorContacts.Key,Token> tokens=new LinkedHashMap<>();
    NativeActorSceneFeedback(Factory factory,int x,int z){this.factory=factory;originX=x;originZ=z;}
    private Token token(ActorContacts.Key key,ActorContacts.Obstacle obstacle)throws Exception{
        var token=tokens.get(key);if(token==null||!token.obstacle().equals(obstacle)){
            if(token==null&&tokens.size()>=ActorScene.MAX_CELLS)throw new ActorScene.Deferred();
            token=new Token(obstacle,factory.create(obstacle));tokens.put(key,token);
        }return token;
    }
    List<Object> query(List<ActorContacts.Volume> values)throws Exception{
        List<Object> result=new ArrayList<>(values.size());
        for(var value:values){var token=token(value.key(),value.obstacle());factory.feedbackBounds.set(token.nativeValue(),factory.bounds(value.bounds(),originX,originZ));result.add(token.nativeValue());}
        return result;
    }
    Map<ActorContacts.Key,ActorContacts.Obstacle> active(Object actor)throws Exception{
        Set<Object> active=factory.contacts(actor);var result=new LinkedHashMap<ActorContacts.Key,ActorContacts.Obstacle>();
        for(var entry:tokens.entrySet())if(active.contains(entry.getValue().nativeValue()))result.put(entry.getKey(),entry.getValue().obstacle());
        if(result.size()!=active.size())throw new IllegalStateException("actor_feedback_unknown_contact");return Map.copyOf(result);
    }
    void restore(Object actor,Map<ActorContacts.Key,ActorContacts.Obstacle> saved)throws Exception{
        if(!tokens.isEmpty()||!factory.contacts(actor).isEmpty()||saved.size()>ActorScene.MAX_CELLS)throw new IllegalStateException("actor_feedback_restore_order");
        for(var entry:saved.entrySet())factory.contacts(actor).add(token(entry.getKey(),entry.getValue()).nativeValue());
    }
    void seedMigratedConstraint(Object actor)throws Exception{
        Object constraint=factory.previousConstraint.get(factory.motion.get(factory.controller.invoke(null,actor)));
        factory.resetConstraint.invoke(constraint);
        for(var token:tokens.values())if(token.obstacle().applies()){Object parent=factory.feedbackParent.get(token.nativeValue()),behaviour=factory.parentBehaviour.get(parent);factory.applyEffect.invoke(token.obstacle().living()==null?factory.obstacle.get(behaviour):factory.livingEffect.get(behaviour),actor,constraint);}
    }
    final class Checkpoint {
        private final Map<ActorContacts.Key,Token> previous=new LinkedHashMap<>(tokens);
        private final Map<Object,Object> bounds=new IdentityHashMap<>();
        Checkpoint()throws Exception{for(var token:tokens.values())bounds.put(token.nativeValue(),factory.feedbackBounds.get(token.nativeValue()));}
        void restore()throws Exception{tokens.clear();tokens.putAll(previous);for(var entry:bounds.entrySet())factory.feedbackBounds.set(entry.getKey(),entry.getValue());}
    }
    Checkpoint checkpoint()throws Exception{return new Checkpoint();}
    void finish(Object actor)throws Exception{
        // Keep current contacts, including objects removed from the map until the original leave phase.
        Set<Object> active=factory.contacts(actor);tokens.entrySet().removeIf(entry->!active.contains(entry.getValue().nativeValue()));
    }
}
