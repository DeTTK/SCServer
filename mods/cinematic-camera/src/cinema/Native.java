package cinema;

import java.lang.reflect.*;
import java.util.*;
import static cinema.Pose.*;

/** Adapter verified against the pinned canonical runtime/classes of this snapshot. */
final class Native {
    static final Map<String,Method> METHODS=new HashMap<>();
    static final Map<String,Field> FIELDS=new HashMap<>();
    static Object runtimeHook(String hook,Object...arguments)throws Exception {return call(type("szmods.runtime.ModLoader"),"clientHook","java.lang.Object",hook,(Object)arguments);}
    static Class<?> type(String n)throws Exception { return Class.forName(n); }
    static Object call(Object receiver,String name,String returns,Object... args)throws Exception {
        Class<?> c=receiver instanceof Class<?> t?t:receiver.getClass();
        String key=c.getName()+":"+name+":"+returns+":"+Arrays.toString(Arrays.stream(args).map(a->a==null?null:a.getClass()).toArray());
        Method found=METHODS.get(key);
        if(found==null) {
            outer:for(Method m:c.getMethods()) {
                if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
                for(int i=0;i<args.length;i++)if(args[i]!=null&&!box(m.getParameterTypes()[i]).isInstance(args[i]))continue outer;
                if(found!=null)throw new NoSuchMethodException("Ambiguous "+key);found=m;
            }
            if(found==null)throw new NoSuchMethodException(key);found.setAccessible(true);METHODS.put(key,found);
        }
        try{return found.invoke(receiver instanceof Class<?>?null:receiver,args);}
        catch(InvocationTargetException e){throw new Exception(key,e.getCause());}
    }
    static Class<?> box(Class<?> c) { return c==double.class?Double.class:c==float.class?Float.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c; }
    static Object get(Object o,String name)throws Exception {
        return field(o,name).get(o instanceof Class<?>?null:o);
    }
    static Field field(Object o,String name)throws Exception {
        Class<?> c=o instanceof Class<?> t?t:o.getClass();String key=c.getName()+":"+name;
        Field f=FIELDS.get(key);
        if(f==null) { for(Class<?> t=c;t!=null;t=t.getSuperclass())try{f=t.getDeclaredField(name);break;}catch(NoSuchFieldException ignored){}
            if(f==null)throw new NoSuchFieldException(key);f.setAccessible(true);FIELDS.put(key,f); }
        return f;
    }
    static V vector(Object v)throws Exception { return new V(((Number)get(v,"case")).doubleValue(),((Number)get(v,"void")).doubleValue(),((Number)get(v,"import")).doubleValue()); }
    static V floatVector(Object v)throws Exception { return new V(((Number)get(v,"assert")).doubleValue(),((Number)get(v,"byte")).doubleValue(),((Number)get(v,"catch")).doubleValue()); }
    static Q quaternion(Object q)throws Exception { return new Q(((Number)get(q,"public")).doubleValue(),((Number)get(q,"implements")).doubleValue(),((Number)get(q,"float")).doubleValue(),((Number)get(q,"catch")).doubleValue()).unit(); }
    static Object vector(V p)throws Exception { return type("153304200").getConstructor(double.class,double.class,double.class).newInstance(p.x(),p.y(),p.z()); }
    static float partial()throws Exception {return (float)call(get(type("1402131154"),"h"),"class","float");}
    static V origin(Object player)throws Exception {return vector(call(type("576005245"),"char","153304200",player,partial()));}
    static Q look(Object player)throws Exception {
        float p=partial();double yaw=(float)call(type("189575079"),"package","float",call(player,"throws","float"),call(player,"catch","float"),p);
        double pitch=(float)call(player,"class","float")+((float)call(player,"volatile","float")-(float)call(player,"class","float"))*p;
        return Q.axis(180-yaw,1).mul(Q.axis(-pitch,0));
    }
    record Bones(Object skeleton,Object pose,List<String> names,Map<String,Integer> indices,V origin,Q basis) {
        String parent(String name)throws Exception {
            Integer index=indices.get(name);if(index==null)return null;
            for(Object bone:(Object[])get(skeleton,"case"))if(((Number)get(bone,"const")).intValue()==index) {
                Object parent=get(bone,"native");return parent==null?null:(String)get(parent,"this");
            }
            return null;
        }
        Pose sample(String name)throws Exception {
            Integer i=indices.get(name);if(i==null)return null;
            Object[] positions=(Object[])get(pose,"for"),rotations=(Object[])get(pose,"double");
            if(i<0||i>=positions.length||i>=rotations.length)return null;
            V p=origin.add(basis.rotate(floatVector(positions[i])));
            if(!p.finite())throw new IllegalArgumentException("Non-finite bone position");
            return new Pose(p,basis.mul(quaternion(rotations[i])));
        }
    }
    static Bones bones(Object player,int source)throws Exception {
        return bones(player,source,player);
    }
    static Bones bones(Object player,int source,Object transformSource)throws Exception {
        if(player==null)return null;
        Object skeleton,pose; V origin=origin(transformSource);Q basis=Q.ID;
        if(source==0) {
            // The primary TP graph owns the rendered character, including action poses.
            // The secondary 254941120 component is the camera/held-item route and may
            // be uninitialised (notably in the native character preview).
            Object component=call(type("1478690128"),"new","1579323378",player);
            Object controller=call(component,"static","1805188833");
            if(!(boolean)call(controller,"private","boolean"))return null;
            Object rig=call(controller,"super","387875964");
            // TP animation is prepared by the native worker. Read its completed pose;
            // do not acquire/update that graph from the render thread.
            skeleton=call(rig,"strictfp","1612003613");pose=call(rig,"final","1118769848");
            // TP pose already includes native root heading, but not entity world translation.
        } else {
            Object component=call(type("1638940636"),"public","1638940636",player);
            Object context=call(component,"volatile","1382318679");if(context==null)return null;
            // Update through the existing native preparation, with its own clock/cache.
            if(transformSource==player){call(context,"protected","void");call(context,"try","void");}
            Object data=call(get(context,"double"),"byte","java.lang.Object");if(data==null)return null;
            Object rig=call(data,source==1?"instanceof":"interface","387875964");if(rig==null)return null;
            skeleton=call(rig,"strictfp","1612003613");pose=call(rig,"extends","1118769848");
            Object cameraBone=call(type("137379477"),"native","470347082");
            Object hands=call(data,"instanceof","387875964");if(hands==null)return null;
            Object handsPose=call(hands,"extends","1118769848");
            int index=((Number)get(cameraBone,"const")).intValue();
            Object[] positions=(Object[])get(handsPose,"for");if(index>=positions.length)return null;
            // FP renderer centres the arms rig on its camera bone before applying player look.
            basis=look(transformSource);
            Object camera=call(player,"enum","1389510037");
            V eye=transformSource==player?floatVector(call(camera,"try","239386075")):new V(0,1.8,0);
            origin=origin.add(eye).sub(basis.rotate(floatVector(positions[index])));
        }
        if(pose==null||skeleton==null)return null;
        List<String> names=new ArrayList<>();Map<String,Integer> indices=new LinkedHashMap<>();
        for(Object bone:(Object[])get(skeleton,"case")) {
            String name=(String)get(bone,"this");names.add(name);indices.put(name,((Number)get(bone,"const")).intValue());
        }
        return new Bones(skeleton,pose,List.copyOf(names),Map.copyOf(indices),origin,basis);
    }
    static void apply(Object camera,Object matrix,Pose pose,double fov)throws Exception {
        call(matrix,"interface","1102042093",(Object)pose.view());
        call(get(camera,"assert"),"enum","153304200",pose.p().x(),pose.p().y(),pose.p().z());
        // Apply after BTF's FOV callback; the native camera then rebuilds its derived matrices.
        Object projection=get(camera,"throws");call(projection,"native","447252483");
        call(get(type("981501872"),"implements"),"interface","void",projection,(float)fov);
        call(projection,"instanceof","447252483",get(camera,"transient"));
    }
    static Pose collide(Object player,V anchor,Pose desired)throws Exception {
        double fraction=1;V delta=desired.p().sub(anchor);double length=delta.length();if(length<1e-6)return desired;
        for(int i=0;i<9;i++) {
            V c=i==8?new V(0,0,0):new V(((i&1)*2-1)*.12,(((i>>1)&1)*2-1)*.12,(((i>>2)&1)*2-1)*.12);
            V a=anchor.add(c),b=desired.p().add(c);
            Object hit=call(type("94534399"),"synchronized","1473643318",false,player,vector(a),vector(b));
            if(hit!=null)fraction=Math.min(fraction,Math.max(0,Math.min(1,(vector(call(hit,"while","153304200")).sub(a).length()-.06)/length)));
        }
        return new Pose(anchor.add(delta.mul(fraction)),desired.q());
    }
    static boolean focused()throws Exception {
        long w=(long)call(type("org.lwjgl.glfw.GLFW"),"glfwGetCurrentContext","long");
        return w!=0&&(int)call(type("org.lwjgl.glfw.GLFW"),"glfwGetWindowAttrib","int",w,0x20001)==1;
    }
    static boolean key(int key)throws Exception {
        return (boolean)call(type("877572775"),"szmods$original$interface","boolean",key);
    }
    static void text(String s,int x,int y,int color)throws Exception {
        call(get(type("236685197"),"strictfp"),"implements","int",s,x,y,color);
    }
    static void box(int x,int y,int w,int h,int color)throws Exception {
        call(get(type("236685197"),"strictfp"),"extends","void",(double)x,(double)y,(double)w,(double)h,color);
    }
    static int lineHeight()throws Exception {return Math.max(19,(int)call(get(type("236685197"),"strictfp"),"while","int")+4);}
    static String fit(String s,int width)throws Exception {
        Object font=get(type("236685197"),"strictfp");
        if((int)call(font,"strictfp","int",s)<=width)return s;
        int lo=0,hi=s.length();
        while(lo<hi){int mid=(lo+hi+1)/2;if((int)call(font,"strictfp","int",s.substring(0,mid)+"...")<=width)lo=mid;else hi=mid-1;}
        return s.substring(0,lo)+"...";
    }
}
