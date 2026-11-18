package szserver.client;

import java.lang.reflect.*;
import java.util.*;
import szserver.world.*;

/** Native seat transforms, action and exit request. No model loading or file access on the owner. */
final class NativeActorSeats {
    private final Class<?> behavior;private final Method objectBehavior,positions,rotation,propertyValue,normalize,clear,makeProbe;
    private final Constructor<?> quaternion,vector,enter,leave;private final Field matrix;
    NativeActorSeats(ClassLoader loader)throws Exception{
        Class<?> object=Class.forName("1886271463",true,loader),quat=Class.forName("600627034",true,loader),point=Class.forName("1738045740",true,loader),vec=Class.forName("153304200",true,loader),box=Class.forName("242639986",true,loader),geometry=Class.forName("433657566",true,loader),property=Class.forName("1798539107",true,loader);
        Class<?> behaviorBase=Class.forName("1079929180",true,loader);
        behavior=Class.forName("24366313",true,loader);objectBehavior=Arrays.stream(object.getMethods()).filter(m->m.getName().equals("private")&&m.getParameterCount()==0&&m.getReturnType()==behaviorBase).findFirst().orElseThrow(()->new NoSuchMethodException("1886271463.private()L1079929180;"));positions=Arrays.stream(behavior.getMethods()).filter(m->m.getName().equals("do")&&m.getParameterCount()==0&&List.class.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        matrix=object.getDeclaredField("m");matrix.setAccessible(true);rotation=Class.forName("447252483",true,loader).getMethod("catch",quat);quaternion=quat.getConstructor();vector=vec.getConstructor(double.class,double.class,double.class);
        propertyValue=Arrays.stream(property.getMethods()).filter(m->m.getName().equals("continue")&&m.getParameterCount()==0&&m.getReturnType()==Object.class).findFirst().orElseThrow();
        normalize=Class.forName("189575079",true,loader).getMethod("protected",float.class);makeProbe=geometry.getMethod("enum",float.class,float.class,point);clear=geometry.getMethod("synchronized",Class.forName("1500514786",true,loader),box);
        enter=Class.forName("1348676920",true,loader).getConstructor(point,float.class,String.class,UUID.class,int.class,boolean.class);leave=Class.forName("327722217",true,loader).getConstructor(UUID.class);
    }
    boolean isSeat(Object object)throws Exception{return behavior.isInstance(objectBehavior.invoke(object));}
    List<ActorSeat> units(Object object,UUID identity,int x,int z)throws Exception{
        SceneCollision.worker();Object source=objectBehavior.invoke(object);if(!behavior.isInstance(source))return List.of();List<?> points=(List<?>)positions.invoke(source),definitions=(List<?>)behavior.getField("const").get(source);boolean[] enabled=(boolean[])behavior.getField("goto").get(source);
        if(points.isEmpty()||points.size()>32||points.size()!=definitions.size()||enabled.length!=points.size())throw new IllegalArgumentException("native_actor_seat_units");
        int occupied=(Integer)propertyValue.invoke(behavior.getField("switch").get(source));Object q=quaternion.newInstance();rotation.invoke(matrix.get(object),q);Class<?> qt=q.getClass();float a=qt.getField("public").getFloat(q),b=qt.getField("implements").getFloat(q),c=qt.getField("float").getFloat(q),d=qt.getField("catch").getFloat(q);
        float angle=(float)Math.atan2(2.0f*(d*b+a*c),1.0f-2.0f*(b*b+a*a));var result=new ArrayList<ActorSeat>();
        for(int i=0;i<points.size();i++){Object p=points.get(i),unit=definitions.get(i);Class<?> v=p.getClass(),u=unit.getClass();float yaw=(Float)normalize.invoke(null,180.0f-(float)Math.toDegrees(angle)+(float)u.getField("double").getDouble(unit));
            result.add(new ActorSeat(identity,i,new World.Vec(v.getField("case").getDouble(p)+x,v.getField("void").getDouble(p),v.getField("import").getDouble(p)+z),yaw,(String)u.getField("for").get(unit),behavior.getField("float").getBoolean(source),enabled[i]&&(occupied&(1<<i))==0));}
        return List.copyOf(result);
    }
    Object action(List<ActorSeat> units,ActorControllerEngine.Seat request,ActorTraversal.Seat current,World.Vec actor,Object world,int x,int z,ActorWorldAccess access)throws Exception{
        if(request.leave())return current!=null&&current.object().equals(request.object())&&current.index()==request.index()?leave.newInstance(request.object()):null;
        if(current!=null||!access.seatAvailable(request.object(),request.index()))return null;
        ActorSeat unit=units.stream().filter(s->s.object().equals(request.object())&&s.index()==request.index()&&s.available()).findFirst().orElse(null);if(unit==null)return null;
        World.Vec p=unit.point();double px=p.x()-x,pz=p.z()-z;
        // An NPC interaction request is local. It cannot teleport an actor to an arbitrary scene UUID.
        if(Math.sqrt((actor.x()-px)*(actor.x()-px)+(actor.y()-p.y())*(actor.y()-p.y())+(actor.z()-pz)*(actor.z()-pz))>3)return null;
        Object probe=makeProbe.invoke(null,.1f,.3f,vector.newInstance(px,p.y()+(double)1.2f,pz));if((Boolean)clear.invoke(null,world,probe))return null;
        return enter.newInstance(vector.newInstance(px,p.y(),pz),unit.facing(),unit.animation(),unit.object(),unit.index(),unit.animated());
    }
}
