package gamecompat;

import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Keeps the original emission, expiry and range inputs while using the live FP barrel pose. */
public final class FlamethrowerEffectHooks {
    private static final Map<Object,Boolean> FIRST_PERSON=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Function1<Object,Unit> KEEP_BARREL_POSE=matrix->Unit.INSTANCE;
    private static final ClassValue<Native> NATIVE=new ClassValue<>(){
        @Override protected Native computeValue(Class<?> stream){
            try{return new Native(stream);}
            catch(ReflectiveOperationException error){throw new IllegalStateException("Flamethrower render contract",error);}
        }
    };

    private FlamethrowerEffectHooks(){}

    public static void prepare(Object stream,boolean firstPerson){
        boolean wasFirstPerson=FIRST_PERSON.containsKey(stream);
        if(!wasFirstPerson&&!firstPerson)return;
        Native nativeApi=NATIVE.get(stream.getClass());
        try{
            boolean modern=firstPerson&&nativeApi.rig(stream)!=null;
            if(wasFirstPerson==modern)return;
            Object current=nativeApi.handle.get(stream);
            if(current!=null){
                nativeApi.stop.invoke(current);
                nativeApi.handle.set(stream,null);
            }
            if(modern)FIRST_PERSON.put(stream,Boolean.TRUE);
            else FIRST_PERSON.remove(stream);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Switch flamethrower render pass",error);}
    }

    public static Object start(Object world,Object asset,int layer,float x,float y,float z,Object stream){
        Native nativeApi=NATIVE.get(stream.getClass());
        try{
            if(!FIRST_PERSON.containsKey(stream))return nativeApi.play.invoke(world,asset,layer,x,y,z);
            Object actor=nativeApi.actor.get(stream);
            Object manager=nativeApi.firstPersonManager.invoke(nativeApi.world.invoke(actor));
            return nativeApi.play.invoke(manager,asset,layer,0f,0f,0f);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Create flamethrower effect",error);}
    }

    public static void attach(Object effect,Object matrix,Object stream){
        Native nativeApi=NATIVE.get(stream.getClass());
        try{
            if(!FIRST_PERSON.containsKey(stream)){
                nativeApi.transform.invoke(effect,matrix);
                return;
            }
            Object rig=nativeApi.rig(stream);
            if(rig!=null)
                nativeApi.barrel.invoke(rig,effect,matrix,"barrel",KEEP_BARREL_POSE);
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Place flamethrower effect",error);}
    }

    private static final class Native {
        final Field actor,handle,effects;
        final Method context,world,firstPersonManager,play,stop,transform,barrel;
        private final Class<?> player;

        Native(Class<?> stream)throws ReflectiveOperationException{
            ClassLoader loader=stream.getClassLoader();
            actor=stream.getField("for");
            handle=stream.getField("this");
            player=type(loader,"200241649");
            context=type(loader,"1638940636").getMethod("szFp_for",player);
            effects=context.getReturnType().getField("szFp_return");
            world=Arrays.stream(actor.getType().getMethods())
                    .filter(m->m.getName().equals("transient")&&m.getParameterCount()==0
                            &&m.getReturnType().getName().equals("1500514786"))
                    .findFirst().orElseThrow(()->new NoSuchMethodException("actor effect world"));
            firstPersonManager=Arrays.stream(world.getReturnType().getMethods())
                    .filter(m->m.getName().equals("instanceof")&&m.getParameterCount()==0
                            &&m.getReturnType().getName().equals("432112703"))
                    .findFirst().orElseThrow(()->new NoSuchMethodException("first-person effect manager"));
            play=firstPersonManager.getReturnType().getMethod("break",type(loader,"779455578"),
                    int.class,float.class,float.class,float.class);
            stop=handle.getType().getMethod("break");
            transform=handle.getType().getMethod("else",type(loader,"1303032651"));
            barrel=effects.getType().getMethod("szFp_public",handle.getType(),type(loader,"447252483"),
                    String.class,Function1.class);
        }

        Object rig(Object stream)throws ReflectiveOperationException{
            Object entity=actor.get(stream);
            if(!player.isInstance(entity))return null;
            Object current=context.invoke(null,entity);
            return current==null?null:effects.get(current);
        }

        private static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{
            return Class.forName(name,false,loader);
        }
    }
}
