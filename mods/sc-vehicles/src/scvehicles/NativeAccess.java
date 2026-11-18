package scvehicles;

import java.lang.reflect.*;
import java.util.*;

/** Adapter exclusively for the active runtime/classes build. All calls stay on the game thread. */
final class NativeAccess {
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private Object body;
    private final Object owner;
    private static Object viewScratch;
    private boolean ownerCleared;
    static final double RADIUS = .22;

    NativeAccess(Object owner, double x, double y, double z) throws Exception {
        this.owner = owner;
        // Separate kinematic collision volume, never the operator's bounding box.
        body = call(Class.forName("242639986"), "synchronized", "242639986",
            x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS);
        double[] p = position();
        if (Math.abs(p[0]-x)+Math.abs(p[1]-y)+Math.abs(p[2]-z)>1e-7)
            throw new IllegalStateException("Native bounding-box coordinate contract changed");
    }
    double[] position() throws Exception {
        return new double[]{(Double)call(body,"implements","double"),
            (Double)call(body,"switch","double"),(Double)call(body,"goto","double")};
    }
    void reposition(double x,double y,double z)throws Exception {
        body=call(Class.forName("242639986"),"synchronized","242639986",x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS);
    }
    int move(double dx,double dy,double dz) throws Exception {
        // Same solver as EntityLiving movement: voxel AABBs and scene OBB convex casts.
        // Zero step height: flying bodies must not climb steps like pedestrians.
        return (Integer)call(Class.forName("251809992"),"else","int",owner,body,dx,dy,dz,0f,false);
    }
    /** Native swept volume versus living entities, clipped first against the world. */
    double entityContact(Object world,double[] from,double[] to)throws Exception {
        Object area=call(Class.forName("242639986"),"synchronized","242639986",
            Math.min(from[0],to[0])-RADIUS,Math.min(from[1],to[1])-RADIUS,Math.min(from[2],to[2])-RADIUS,
            Math.max(from[0],to[0])+RADIUS,Math.max(from[1],to[1])+RADIUS,Math.max(from[2],to[2])+RADIUS);
        Object start=Class.forName("153304200").getConstructor(double.class,double.class,double.class).newInstance(from[0],from[1],from[2]);
        Object end=Class.forName("153304200").getConstructor(double.class,double.class,double.class).newInstance(to[0],to[1],to[2]);
        double length=Math.sqrt(Math.pow(to[0]-from[0],2)+Math.pow(to[1]-from[1],2)+Math.pow(to[2]-from[2],2));
        Object ownerBox=call(call(owner,"static","242639986"),"void","242639986",RADIUS,RADIUS,RADIUS);
        boolean insideOwner=(Boolean)call(ownerBox,"synchronized","boolean",start);
        if(!insideOwner)ownerCleared=true;
        double nearest=Double.POSITIVE_INFINITY;
        Set<Object> candidates=Collections.newSetFromMap(new IdentityHashMap<>());
        candidates.addAll((List<?>)call(world,"switch","java.util.List",Class.forName("351062380"),area));
        // Remote players may exist before their chunk's native entity index is rebuilt
        // (e.g. terrain streaming / WorldReady). Their current AABBs remain authoritative.
        for(Object entity:(Collection<?>)call(world,"volatile","java.util.Collection"))
            if(Class.forName("200241649").isInstance(entity))candidates.add(entity);
        for(Object entity:candidates){
            if(!(Boolean)call(entity,"super","boolean"))continue; // Dead/removed actors are not live targets.
            // Deployment overlap is ignored only until the craft has left its operator.
            if(entity==owner&&!ownerCleared)continue;
            Object box=call(call(entity,"static","242639986"),"void","242639986",RADIUS,RADIUS,RADIUS);
            if((Boolean)call(box,"synchronized","boolean",start)){nearest=0;continue;}
            Object hit=call(box,"package","1679405945",start,end);
            if(hit==null)continue;
            double[] point=xyz(call(hit,"protected","153304200"));
            double distance=Math.sqrt(Math.pow(point[0]-from[0],2)+Math.pow(point[1]-from[1],2)+Math.pow(point[2]-from[2],2));
            if(length>1e-9)nearest=Math.min(nearest,distance/length);
        }
        return nearest;
    }
    static Object neutralIntent() throws Exception { return Class.forName("1899091998").getConstructor().newInstance(); }
    static boolean key(int code) throws Exception {
        return (Boolean)call(Class.forName("877572775"),"interface","boolean",code);
    }
    static void verifyHooks() throws Exception {
        Class.forName("1899091998").getDeclaredMethod("szmods$flight$assert",Class.forName("1899091998"),List.class);
        Class.forName("1389510037").getDeclaredMethod("szmods$flight$enum",Class.forName("1102042093"));
    }
    static void view(Object camera,Object matrix,double x,double y,double z,
                     double w,double qx,double qy,double qz,double tilt) throws Exception {
        view(camera,matrix,x,y,z,w,qx,qy,qz,tilt,0);
    }
    static void view(Object camera,Object matrix,double x,double y,double z,
                     double w,double qx,double qy,double qz,double tilt,double roll) throws Exception {
        Object eye=get(camera,"assert");
        if(viewScratch==null) viewScratch=Class.forName("1102042093").getConstructor().newInstance();
        viewMatrix(viewScratch,x,y,z,w,qx,qy,qz,tilt,roll);
        call(eye,"enum","153304200",x,y,z);
        call(matrix,"interface","1102042093",viewScratch);
    }
    static void viewMatrix(Object matrix,double x,double y,double z,
                           double w,double qx,double qy,double qz,double tilt) throws Exception {
        viewMatrix(matrix,x,y,z,w,qx,qy,qz,tilt,0);
    }
    static void viewMatrix(Object matrix,double x,double y,double z,
                           double w,double qx,double qy,double qz,double tilt,double roll) throws Exception {
        // Body forward is +Z. OpenGL camera forward is -Z, then inverse body attitude.
        call(matrix,"synchronized","1102042093");
        if(roll!=0)call(matrix,"return","1102042093",-roll,0.,0.,1.,matrix);
        call(matrix,"return","1102042093",-Math.toRadians(tilt),1.,0.,0.,matrix);
        call(matrix,"return","1102042093",Math.PI,0.,1.,0.,matrix);
        double angle=2*Math.acos(Math.max(-1,Math.min(1,w)));
        double s=Math.sqrt(Math.max(0,1-w*w));
        if(s>1e-9) call(matrix,"return","1102042093",-angle,qx/s,qy/s,qz/s,matrix);
        Object offset=Class.forName("153304200").getConstructor(double.class,double.class,double.class).newInstance(-x,-y,-z);
        call(matrix,"continue","1102042093",offset);
    }
    static Object call(Object receiver,String name,String returns,Object... args) throws Exception {
        Class<?> cls=receiver instanceof Class<?> c?c:receiver.getClass();
        String key=cls.getName()+":"+name+":"+returns+":"+Arrays.toString(Arrays.stream(args).map(x->x==null?null:x.getClass()).toArray());
        Method method=METHODS.get(key);
        if(method==null) {
            for(Method m:cls.getMethods()) {
                if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length) continue;
                boolean compatible=true;
                for(int i=0;i<args.length;i++) if(args[i]!=null&&!box(m.getParameterTypes()[i]).isInstance(args[i])) compatible=false;
                if(compatible) { if(method!=null) throw new NoSuchMethodException("Ambiguous "+key); method=m; }
            }
            if(method==null) throw new NoSuchMethodException(key);
            method.setAccessible(true); METHODS.put(key,method);
        }
        try { return method.invoke(receiver instanceof Class<?>?null:receiver,args); }
        catch(InvocationTargetException e) { Throwable cause=e.getCause();while(cause.getCause()!=null)cause=cause.getCause();throw new Exception(key+" caused by "+cause+" at "+(cause.getStackTrace().length==0?"":cause.getStackTrace()[0]),e.getCause()); }
    }
    static Class<?> box(Class<?> c) { return c==double.class?Double.class:c==float.class?Float.class:c==int.class?Integer.class:c==boolean.class?Boolean.class:c; }
    /** Resolve obfuscated fields on their declaring class, before subclass name reuse. */
    static Object get(Object receiver,Class<?> owner,String name) throws Exception {
        String key=owner.getName()+":declared:"+name;Field field=FIELDS.get(key);
        if(field==null){field=owner.getDeclaredField(name);field.setAccessible(true);FIELDS.put(key,field);}
        return field.get(receiver);
    }
    static Object get(Object receiver,String name) throws Exception {
        Class<?> cls=receiver instanceof Class<?> c?c:receiver.getClass(); String key=cls.getName()+":"+name; Field field=FIELDS.get(key);
        if(field==null) {
            for(Class<?> c=cls;c!=null;c=c.getSuperclass()) try { field=c.getDeclaredField(name); break; } catch(NoSuchFieldException ignored) {}
            if(field==null) throw new NoSuchFieldException(key);
            field.setAccessible(true); FIELDS.put(key,field);
        }
        return field.get(receiver instanceof Class<?>?null:receiver);
    }
    static double[] xyz(Object v) throws Exception {
        return new double[]{((Number)get(v,"case")).doubleValue(),((Number)get(v,"void")).doubleValue(),((Number)get(v,"import")).doubleValue()};
    }
}
