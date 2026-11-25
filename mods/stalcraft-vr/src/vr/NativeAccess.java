package vr;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static vr.Math3.*;

/** Verified adapters reference only the canonical runtime class names. */
final class NativeAccess {
    private record Candidate(Method method,Class<?>[] parameters){}
    private static final class Members {
        final Map<String,Map<String,List<Candidate>>> methods=new HashMap<>();
        final Map<String,Field> fields=new ConcurrentHashMap<>();
        Members(Class<?> type){
            for(Method m:type.getMethods()){
                Class<?>[] parameters=m.getParameterTypes();for(int i=0;i<parameters.length;i++)parameters[i]=box(parameters[i]);
                methods.computeIfAbsent(m.getName(),k->new HashMap<>()).computeIfAbsent(m.getReturnType().getName(),k->new ArrayList<>()).add(new Candidate(m,parameters));
            }
        }
    }
    private static final ClassValue<Members> members=new ClassValue<>(){protected Members computeValue(Class<?> type){return new Members(type);}};
    private static final Map<String,Class<?>> types=new ConcurrentHashMap<>();
    static Class<?> type(String name)throws Exception {Class<?> c=types.get(name);if(c==null){c=Class.forName(name);types.put(name,c);}return c;}
    static Object call(Object receiver,String name,String returns,Object...args)throws Exception {
        Class<?> c=receiver instanceof Class<?> t?t:receiver.getClass();Method method=null;
        Map<String,List<Candidate>> results=members.get(c).methods.get(name);List<Candidate> candidates=results==null?null:results.get(returns);
        if(candidates!=null)for(Candidate candidate:candidates){
            Class<?>[] parameters=candidate.parameters();if(parameters.length!=args.length)continue;
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!parameters[i].isInstance(args[i])){match=false;break;}
            if(match){if(method!=null)throw new NoSuchMethodException("Ambiguous "+c.getName()+":"+name+":"+returns);method=candidate.method();}
        }
        if(method==null)throw new NoSuchMethodException(c.getName()+":"+name+":"+returns);
        Object target=Modifier.isStatic(method.getModifiers())||receiver instanceof Class<?>?null:receiver;
        if(!method.canAccess(target))method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw new Exception(c.getName()+":"+name+":"+returns,e.getCause());}
    }
    private static Class<?> box(Class<?> c){return c==double.class?Double.class:c==float.class?Float.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c==short.class?Short.class:c==byte.class?Byte.class:c==char.class?Character.class:c;}
    private static Field field(Object receiver,String name)throws Exception {
        Class<?> c=receiver instanceof Class<?> t?t:receiver.getClass();Map<String,Field> cache=members.get(c).fields;Field found=cache.get(name);if(found!=null)return found;
        for(Class<?> t=c;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);cache.put(name,f);return f;}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(c.getName()+"."+name);
    }
    static Object get(Object receiver,String name)throws Exception {return field(receiver,name).get(receiver instanceof Class<?>?null:receiver);}
    static void set(Object receiver,String name,Object value)throws Exception {field(receiver,name).set(receiver instanceof Class<?>?null:receiver,value);}
    static V vector(Object v)throws Exception{return new V(((Number)get(v,"case")).doubleValue(),((Number)get(v,"void")).doubleValue(),((Number)get(v,"import")).doubleValue());}
    static V floatVector(Object v)throws Exception{return new V(((Number)get(v,"assert")).doubleValue(),((Number)get(v,"byte")).doubleValue(),((Number)get(v,"catch")).doubleValue());}
    static Q quaternion(Object q)throws Exception{return new Q(((Number)get(q,"public")).doubleValue(),((Number)get(q,"implements")).doubleValue(),((Number)get(q,"float")).doubleValue(),((Number)get(q,"catch")).doubleValue()).unit();}
    static V origin(Object player,float partial)throws Exception{return vector(call(type("576005245"),"char","153304200",player,partial));}
    static void rebuildCamera(Object camera,Object world)throws Exception {
        // Exact derived-matrix body of 353488815.goto(world), without its
        // beginFrame-only assertion. Eye rendering follows beginFrame and waits
        // native culling workers before rebuilding shared camera state.
        call(camera,"break","void");Object projection=get(camera,"throws"),view=get(camera,"static");
        call(projection,"native","447252483");call(camera,"float","void",projection);call(projection,"instanceof","447252483",get(camera,"transient"));
        call(view,"synchronized","1102042093");call(camera,"enum","void",view);call(camera,"while","void");
        call(projection,"final","447252483",get(camera,"implements"),get(camera,"int"));call(camera,"byte","void");call(camera,"int","void");call(camera,"protected","void",world);call(camera,"goto","void");
    }
    static boolean clearLine(Object player,V a,V b)throws Exception {
        if(!a.finite()||!b.finite())return false;if(a.sub(b).length()<1e-5)return true;
        Class<?> vector=type("153304200");Object start=vector.getConstructor(double.class,double.class,double.class).newInstance(a.x(),a.y(),a.z()),end=vector.getConstructor(double.class,double.class,double.class).newInstance(b.x(),b.y(),b.z());
        return call(type("94534399"),"synchronized","1473643318",false,player,start,end)==null;
    }
    /** Solid swept volume, using the same voxel/scene OBB solver as native movement. */
    static boolean clearProp(Object player,V edgeFrom,V edgeTo,double radius)throws Exception {
        V delta=edgeTo.sub(edgeFrom);if(delta.length()<1e-7)return true;
        V centre=edgeFrom.sub(delta.unit().mul(radius));
        Object box=call(type("242639986"),"synchronized","242639986",centre.x()-radius,centre.y()-radius,centre.z()-radius,centre.x()+radius,centre.y()+radius,centre.z()+radius);
        call(type("251809992"),"else","int",player,box,delta.x(),delta.y(),delta.z(),0f,false);
        V moved=new V(((Number)call(box,"implements","double")).doubleValue(),((Number)call(box,"switch","double")).doubleValue(),((Number)call(box,"goto","double")).doubleValue());
        return moved.sub(centre.add(delta)).length()<1e-6;
    }
    static Q heading(Object player)throws Exception{return Q.axis(new V(0,1,0),Math.toRadians(180-((Number)call(player,"catch","float")).doubleValue()));}
    static void eyeView(Object camera,Object matrix,Pose eye,Fov fov)throws Exception{
        call(matrix,"interface","1102042093",(Object)eye.viewDouble());
        call(get(camera,"assert"),"enum","153304200",eye.p().x(),eye.p().y(),eye.p().z());projection(get(camera,"throws"),fov);
    }
    static void projection(Object projection,Fov fov)throws Exception {call(projection,"transient","447252483",(Object)fov.projection(.03,1500));}
}
