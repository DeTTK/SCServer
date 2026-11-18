package szmods.runtime;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Per-character native looping clip, with the original locomotion graph retained for clear. */
public final class NamedAnimations {
    private record Playing(Object entity, Object original, Object node, String name) {}
    private static final Map<Object,Playing> playing=new IdentityHashMap<>();
    private static final Map<String,Object> packs=new HashMap<>();
    public static String lastError="";
    public static long switches;
    public static long lookFrames;
    public static float lastLookYaw,lastLookPitch;

    /** Orient the entire sampled clip. Pitch never deforms a forced pose. */
    public static synchronized void look(Object node,Object rig,Object pose){
        Playing p=playing.get(rig);if(p==null||p.node!=node)return;
        // Locomotion clips (especially JUMP) already receive body heading from
        // the native graph. Rewriting their root quaternion twists the jump.
        // Heading correction is only for explicit action poses.
        String name=p.name==null?"":p.name.toUpperCase(Locale.ROOT);
        if(name.equals("STAND")||name.equals("CROUCH")||name.equals("PRONE")||
           name.equals("SIT")||name.equals("JUMP")||name.equals("FLY")||
           name.equals("CLIMB")||name.equals("CLIMB_OVER")||name.equals("CLIMB_ON")||
           name.equals("LADDER")||name.equals("KNOCKDOWN")||name.equals("LOGOUT")||
           name.equals("ZIPLINE")) return;
        try{
            Object graph=invoke(Class.forName("578131936"),"break","578131936",p.entity);
            float yaw=((Number)invoke(graph,"package","float")).floatValue();
            lastLookYaw=yaw;lastLookPitch=0;lookFrames++;
            Object skeleton=invoke(rig,"strictfp","1612003613");
            float[] data=(float[])pose.getClass().getField("switch").get(pose);
            int offset=pose.getClass().getField("instanceof").getInt(pose);
            for(Object bone:(Object[])skeleton.getClass().getField("case").get(skeleton)){
                if(bone.getClass().getField("native").get(bone)!=null)continue;
                rotateRoot(data,offset+bone.getClass().getField("const").getInt(bone)*7,yaw);
            }
        }catch(Exception e){lastError="Look override: "+e;}
    }
    // Matches native character model heading: 1573288982 uses Y rotation (180 - yaw).
    // Only roots change; every child keeps the authored local transform.
    static void rotateRoot(float[] data,int i,float yaw){
        double angle=Math.toRadians(180-yaw),s=Math.sin(angle),c=Math.cos(angle);
        double x=data[i],z=data[i+2];data[i]=(float)(c*x+s*z);data[i+2]=(float)(-s*x+c*z);
        double sy=Math.sin(angle*.5),cy=Math.cos(angle*.5);
        double qx=data[i+3],qy=data[i+4],qz=data[i+5],qw=data[i+6];
        data[i+3]=(float)(cy*qx+sy*qz);data[i+4]=(float)(cy*qy+sy*qw);
        data[i+5]=(float)(cy*qz-sy*qx);data[i+6]=(float)(cy*qw-sy*qy);
    }

    static synchronized void retainEntities(Set<Object> entities)throws Exception {
        for(Object rig:new ArrayList<>(playing.keySet()))if(!entities.contains(playing.get(rig).entity))restore(rig);
    }

    static synchronized void reset() throws Exception {
        for(Object rig:new ArrayList<>(playing.keySet())) restore(rig);
        for(Object handle:packs.values()) invoke(handle,"char","void");
        packs.clear();
    }
    static synchronized void restore(Object rig)throws Exception {
        Playing old=playing.remove(rig);
        if(old!=null) {
            invoke(rig,"throws","void",old.original);
            // Reset timers after time spent outside the native locomotion graph.
            invoke(rig,"for","void",true);
        }
    }
    static synchronized boolean apply(Object entity,String requested)throws Exception {
        if(requested==null&&playing.isEmpty())return false;
        Object component=invoke(Class.forName("1478690128"),"new","1579323378",entity);
        Object animator=invoke(component,"static","1805188833");
        if(!(Boolean)invoke(animator,"private","boolean"))return false;
        Object rig=invoke(animator,"super","387875964");
        if(requested==null){restore(rig);return false;}
        Playing old=playing.get(rig);
        if(old!=null&&old.name.equals(requested))return true;
        String clip=requested;
        Object provider=rig;
        Object data=invoke(rig,"continue","1913051855",clip);
        if(data==null){
            String packName="npc_generic";
            int colon=requested.indexOf(':');
            if(colon>=0){packName=requested.substring(0,colon);clip=requested.substring(colon+1);}
            Map<?,?> registry=(Map<?,?>)invoke(Class.forName("298888696"),"try","java.util.Map");
            Object handle=registry.get(packName);
            if(handle==null){lastError="Unknown animation pack: "+packName;restore(rig);return false;}
            if(!packs.containsKey(packName)){
                packs.put(packName,handle);
                invoke(handle,"synchronized","void",(Consumer<Object>)(v)->{});
            }
            Object pack=invoke(handle,"byte","java.lang.Object");
            if(pack==null)return false; // Async native asset loading; retry on the next frame.
            Object library=invoke(pack,"const","1255466622");
            data=invoke(library,"strictfp","1913051855",clip);
            if(data==null){lastError="Unknown animation: "+requested;restore(rig);return false;}
            Object model=rig.getClass().getField("for").get(rig);
            Object array=Array.newInstance(Class.forName("263758772"),1);Array.set(array,0,pack);
            provider=Class.forName("995521065").getMethod("void",Class.forName("1582212475"),array.getClass()).invoke(null,model,array);
        }
        Object node=Class.forName("510119352").getConstructor(String.class).newInstance(clip);
        if(provider!=rig)invoke(node,"try","510119352",provider);
        invoke(node,"while","510119352");
        Object original=old==null?invoke(rig,"static","1391506621"):old.original;
        invoke(rig,"throws","void",node);
        invoke(rig,"for","void",true);
        playing.put(rig,new Playing(entity,original,node,requested));
        switches++;lastError="";
        System.out.println("[Animation] native clip="+requested+" frames="+data.getClass().getField("default").get(data));
        return true;
    }
    public static synchronized Map<String,Object> inspect(Object entity)throws Exception {
        Object component=invoke(Class.forName("1478690128"),"new","1579323378",entity);
        Object animator=invoke(component,"static","1805188833");
        Object rig=invoke(animator,"super","387875964");
        Object root=invoke(rig,"static","1391506621");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rootClass",root==null?null:root.getClass().getName());
        Playing p=playing.get(rig);
        if(p!=null){result.put("clip",p.name);result.put("progress",invoke(p.node,"extends","float"));}
        if(p!=null){Object graph=invoke(Class.forName("578131936"),"break","578131936",entity);result.put("heading",invoke(graph,"package","float"));}
        result.put("error",lastError);
        return result;
    }
    private static Object invoke(Object target,String name,String returns,Object...args)throws Exception {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        outer:for(Method m:type.getMethods()){
            if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
            Class<?>[] params=m.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!params[i].isPrimitive()&&!params[i].isInstance(args[i]))continue outer;
            return m.invoke(target instanceof Class<?>?null:target,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name+" -> "+returns);
    }
}
