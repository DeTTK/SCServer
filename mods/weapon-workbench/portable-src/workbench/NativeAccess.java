package workbench;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static workbench.Math3.*;

/** Verified adapters reference only the canonical runtime class names. */
final class NativeAccess {
    private static final class Candidate {
 final Method method;
 final Class<?>[] parameters;
 public Candidate(Method method,Class<?>[] parameters){this.method=method;this.parameters=parameters;}
 public Method method(){return method;}
 public Class<?>[] parameters(){return parameters;}
}
    private static final class Members {
        final Map<String,Map<String,List<Candidate>>> methods=new HashMap<>();
        final Map<String,Field> fields=new ConcurrentHashMap<>();
        Members(Class<?> type){
            for(Method m:type.getMethods()){
                Class<?>[] parameters=m.getParameterTypes();for(int i=0;i<parameters.length;i++)parameters[i]=box(parameters[i]);
                Map<String,List<Candidate>> results=methods.get(m.getName());
                if(results==null){results=new HashMap<>();methods.put(m.getName(),results);}
                List<Candidate> overloads=results.get(m.getReturnType().getName());
                if(overloads==null){overloads=new ArrayList<>();results.put(m.getReturnType().getName(),overloads);}
                overloads.add(new Candidate(m,parameters));
            }
        }
    }
    private static final ClassValue<Members> members=new ClassValue<Members>(){protected Members computeValue(Class<?> type){return new Members(type);}};
    private static final Map<String,Class<?>> types=new ConcurrentHashMap<>();
    static Class<?> type(String name)throws Exception {Class<?> c=types.get(name);if(c==null){c=Class.forName(name);types.put(name,c);}return c;}
    static Object call(Object receiver,String name,String returns,Object...args)throws Exception {
        Class<?> c=receiver instanceof Class<?>?(Class<?>)receiver:receiver.getClass();Method method=null;
        Map<String,List<Candidate>> results=members.get(c).methods.get(name);List<Candidate> candidates=results==null?null:results.get(returns);
        if(candidates!=null)for(Candidate candidate:candidates){
            Class<?>[] parameters=candidate.parameters();if(parameters.length!=args.length)continue;
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!parameters[i].isInstance(args[i])){match=false;break;}
            if(match){if(method!=null)throw new NoSuchMethodException("Ambiguous "+c.getName()+":"+name+":"+returns);method=candidate.method();}
        }
        if(method==null)throw new NoSuchMethodException(c.getName()+":"+name+":"+returns);
        Object target=Modifier.isStatic(method.getModifiers())||receiver instanceof Class<?>?null:receiver;
        method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw new Exception(c.getName()+":"+name+":"+returns,e.getCause());}
    }
    private static Class<?> box(Class<?> c){return c==double.class?Double.class:c==float.class?Float.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c==short.class?Short.class:c==byte.class?Byte.class:c==char.class?Character.class:c;}
    private static Field field(Object receiver,String name)throws Exception {
        Class<?> c=receiver instanceof Class<?>?(Class<?>)receiver:receiver.getClass();Map<String,Field> cache=members.get(c).fields;Field found=cache.get(name);if(found!=null)return found;
        for(Class<?> t=c;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);cache.put(name,f);return f;}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(c.getName()+"."+name);
    }
    static Object get(Object receiver,String name)throws Exception {return field(receiver,name).get(receiver instanceof Class<?>?null:receiver);}
    static void set(Object receiver,String name,Object value)throws Exception {field(receiver,name).set(receiver instanceof Class<?>?null:receiver,value);}
    static V vector(Object v)throws Exception{return new V(((Number)get(v,"case")).doubleValue(),((Number)get(v,"void")).doubleValue(),((Number)get(v,"import")).doubleValue());}
    static V floatVector(Object v)throws Exception{return new V(((Number)get(v,"assert")).doubleValue(),((Number)get(v,"byte")).doubleValue(),((Number)get(v,"catch")).doubleValue());}
    static Q quaternion(Object q)throws Exception{return new Q(((Number)get(q,"public")).doubleValue(),((Number)get(q,"implements")).doubleValue(),((Number)get(q,"float")).doubleValue(),((Number)get(q,"catch")).doubleValue()).unit();}
}
