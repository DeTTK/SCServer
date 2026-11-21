package playerscale;

import java.lang.reflect.*;
import java.util.*;

/** Return types distinguish the native build's overloaded obfuscated getters. */
final class Native {
    private record Key(Class<?> type,String name,String result,List<Class<?>> parameters){}
    private static final Map<Key,Method> methods=new HashMap<>();
    private static final Map<String,Field> fields=new HashMap<>();
    static Object get(Object object,String name)throws Exception {
        Class<?> type=object.getClass();String key=type.getName()+':'+name;Field f=fields.get(key);
        if(f==null){for(Class<?> c=type;c!=null;c=c.getSuperclass())try{f=c.getDeclaredField(name);break;}catch(NoSuchFieldException ignored){}
            if(f==null)throw new NoSuchFieldException(key);f.setAccessible(true);fields.put(key,f);}return f.get(object);
    }
    static void set(Object object,String name,Object value)throws Exception {get(object,name);fields.get(object.getClass().getName()+':'+name).set(object,value);}
    static Object call(Object object,String name,String returns,Object... args)throws Exception {
        Class<?> type=object instanceof Class<?> c?c:object.getClass();List<Class<?>> arguments=Arrays.stream(args).map(v->v==null?Object.class:v.getClass()).toList();Key key=new Key(type,name,returns,arguments);Method method=methods.get(key);
        if(method==null){search:for(Method m:type.getMethods()){
            if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
            Class<?>[] p=m.getParameterTypes();for(int i=0;i<p.length;i++)if(args[i]!=null&&!box(p[i]).isInstance(args[i]))continue search;
            method=m;break;}
            if(method==null)throw new NoSuchMethodException(key.toString());method.setAccessible(true);methods.put(key,method);}
        try{return method.invoke(object instanceof Class<?>?null:object,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Error e)throw e;if(cause instanceof Exception e)throw e;throw failure;}
    }
    private static Class<?> box(Class<?> c){return c==float.class?Float.class:c==double.class?Double.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c;}
}
