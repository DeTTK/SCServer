package szserver.client;
import szserver.world.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Original local mapper angle and original remote cable wrapper, with a private world-coordinate copy. */
final class NativeActorZiplineAnimation {
    private final Object mapper;private final Method angle,actorYaw,writePath,readPath,writeWrapper;
    private final Constructor<?> pathConstructor,wrapperConstructor;private final Field cable,identity;private final Field[] points;
    private Object cached;private int originX,originZ;private byte[] payload;
    NativeActorZiplineAnimation(ClassLoader loader)throws Exception{
        Class<?> state=Class.forName("1943200621",true,loader),path=Class.forName("930637873",true,loader),actor=Class.forName("200241649",true,loader),mapping=Class.forName("873944585",true,loader),wrapper=Class.forName("1205530968",true,loader);
        mapper=mapping.getConstructor().newInstance();angle=mapping.getMethod("byte",state,path,float.class);
        Method yaw=null;for(var m:actor.getMethods())if(m.getName().equals("catch")&&m.getParameterCount()==0&&m.getReturnType()==float.class){yaw=m;break;}if(yaw==null)throw new NoSuchMethodException("actor yaw");actorYaw=yaw;
        cable=state.getField("bd");identity=state.getField("byte");pathConstructor=path.getConstructor();wrapperConstructor=wrapper.getConstructor(UUID.class,path);
        writePath=path.getMethod("switch",DataOutput.class);readPath=path.getMethod("extends",DataInput.class);writeWrapper=wrapper.getMethod("switch",DataOutput.class);points=new Field[]{path.getField("default"),path.getField("char")};
    }
    ActorTraversal.Cable value(Object state,Object actor,int x,int z)throws Exception{
        Object path=cable.get(state);if(path==null)return null;
        float facing=((Number)actorYaw.invoke(actor)).floatValue();float value=((Number)angle.invoke(mapper,state,path,facing)).floatValue();
        if(path!=cached||originX!=x||originZ!=z){
            var bytes=new ByteArrayOutputStream();writePath.invoke(path,new DataOutputStream(bytes));Object copy=pathConstructor.newInstance();readPath.invoke(copy,new BoundedNativeInput(bytes.toByteArray()));
            for(var field:points){Object point=field.get(copy);var xx=point.getClass().getField("case");var zz=point.getClass().getField("import");xx.setDouble(point,xx.getDouble(point)+x);zz.setDouble(point,zz.getDouble(point)+z);}
            Object packet=wrapperConstructor.newInstance(identity.get(state),copy);bytes.reset();writeWrapper.invoke(packet,new DataOutputStream(bytes));if(bytes.size()>2048)throw new IOException("actor_zipline_animation_size");payload=bytes.toByteArray();cached=path;originX=x;originZ=z;
        }
        return new ActorTraversal.Cable((UUID)identity.get(state),payload,value);
    }
}
