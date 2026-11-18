package replay;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Native {
    private record CallKey(String name,String returns,List<Class<?>> arguments){}
    private static final ClassValue<Map<CallKey,Method>> methods=new ClassValue<>(){protected Map<CallKey,Method> computeValue(Class<?> type){return new ConcurrentHashMap<>();}};
    private static final ClassValue<Map<String,Field>> fields=new ClassValue<>(){protected Map<String,Field> computeValue(Class<?> type){return new ConcurrentHashMap<>();}};
    private static final Map<String,Class<?>> types=new ConcurrentHashMap<>();
    public static Class<?> type(String name)throws Exception{Class<?> found=types.get(name);if(found!=null)return found;found=Class.forName(name);types.putIfAbsent(name,found);return found;}
    public static Field reflectedField(Object object,String name)throws Exception {
        Class<?> c=object instanceof Class<?> t?t:object.getClass();
        Field cached=fields.get(c).get(name);if(cached!=null)return cached;
        for(Class<?> t=c;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);fields.get(c).putIfAbsent(name,f);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(c.getName()+"."+name);
    }
    public static Object field(Object object,String name)throws Exception {return reflectedField(object,name).get(object instanceof Class<?>?null:object);}
    public static Object call(Object receiver,String name,String returns,Object...args)throws Exception{
        Class<?> c=receiver instanceof Class<?> t?t:receiver.getClass();List<Class<?>> types=new ArrayList<>(args.length);for(Object arg:args)types.add(arg==null?Void.class:arg.getClass());
        CallKey key=new CallKey(name,returns,List.copyOf(types));Method found=methods.get(c).get(key);
        if(found==null){outer:for(Method m:c.getMethods()){
            if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!box(m.getParameterTypes()[i]).isInstance(args[i]))continue outer;
            if(found!=null)throw new NoSuchMethodException("Ambiguous "+c.getName()+"."+name);found=m;
        }
        if(found==null)throw new NoSuchMethodException(c.getName()+"."+name+":"+returns);
        found.setAccessible(true);methods.get(c).putIfAbsent(key,found);}
        try{return found.invoke(receiver instanceof Class<?>?null:receiver,args);}catch(InvocationTargetException e){throw new Exception("Native "+c.getName()+"."+name,e.getCause());}
    }
    public static Class<?> box(Class<?> c){return c==int.class?Integer.class:c==double.class?Double.class:c==float.class?Float.class:c==boolean.class?Boolean.class:c==long.class?Long.class:c==short.class?Short.class:c==byte.class?Byte.class:c==char.class?Character.class:c;}
}
