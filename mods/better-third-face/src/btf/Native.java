package btf;
import java.lang.reflect.*;
import java.util.*;

/** Cached, return-type-aware access to this specific obfuscated client build. */
final class Native {
    static final Map<String,Method> methods=new HashMap<>();
    static final Map<String,Field> fields=new HashMap<>();
    static Class<?> type(String name)throws Exception{return Class.forName(name);}
    static Object call(Object receiver,String name,String returns,Object... args)throws Exception {
        Class<?> cls=receiver instanceof Class<?> c?c:receiver.getClass();
        String key=cls.getName()+":"+name+":"+returns+":"+Arrays.toString(Arrays.stream(args).map(x->x==null?null:x.getClass()).toArray());
        Method method=methods.get(key);
        if(method==null) {
            search:for(Method m:cls.getMethods()) {
                if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
                Class<?>[] p=m.getParameterTypes();
                for(int i=0;i<p.length;i++)if(args[i]!=null&&!box(p[i]).isInstance(args[i]))continue search;
                method=m;break;
            }
            if(method==null)throw new NoSuchMethodException(key);
            method.setAccessible(true);methods.put(key,method);
        }
        try{return method.invoke(receiver instanceof Class<?>?null:receiver,args);}
        catch(InvocationTargetException e){throw new Exception(key,e.getCause());}
    }
    static Class<?> box(Class<?> c){return c==float.class?Float.class:c==double.class?Double.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c;}
    static Object get(Object receiver,String name)throws Exception {
        Class<?> cls=receiver instanceof Class<?> c?c:receiver.getClass();String key=cls.getName()+":"+name;
        Field field=fields.get(key);
        if(field==null){for(Class<?> c=cls;c!=null;c=c.getSuperclass())try{field=c.getDeclaredField(name);break;}catch(NoSuchFieldException ignored){}
            if(field==null)throw new NoSuchFieldException(key);field.setAccessible(true);fields.put(key,field);}
        return field.get(receiver instanceof Class<?>?null:receiver);
    }
    static double[] xyz(Object v)throws Exception{return new double[]{((Number)get(v,"case")).doubleValue(),((Number)get(v,"void")).doubleValue(),((Number)get(v,"import")).doubleValue()};}
    static Object vector(double[] v)throws Exception{return type("153304200").getConstructor(double.class,double.class,double.class).newInstance(v[0],v[1],v[2]);}
}
