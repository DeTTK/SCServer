package scvehicles;

import java.util.*;
import java.lang.reflect.Proxy;
import scvehicles.protocol.VehicleWire;
import static scvehicles.NativeAccess.*;
import static org.lwjgl.opengl.GL33.*;

/** Native seated skeleton, clothes and attachments share the vehicle attitude. */
final class VehicleRider {
    VehicleActionsClient actions;
    Object firstPerson;
    private final Map<Object,VehicleWire> drivers=new IdentityHashMap<>();
    private final Map<Object,Saved> saved=new IdentityHashMap<>();
    private final Map<Object,VehicleSeatPose> rigs=new IdentityHashMap<>();
    private record Saved(float[] before,float[] after) {}
    long draws;double heading;float[] lastRoot=new float[0];
    String lastClip="";double lastPhase,lastDoor;double[] lastAnchor=new double[3];
    boolean rendering;long nativeWeaponPasses;double aimYaw,aimPitch;
    private Object renderer,weaponRenderer;
    String weaponClass="";float[] weaponPose=new float[0];
    void frame(Object local,Object world,Collection<VehicleWire> states)throws Exception {
        Map<Object,VehicleWire> next=new IdentityHashMap<>();
        for(VehicleWire s:states)if(s.driver>=0){
            Object entity=s.driver==0?local:world.getClass().getMethod("continue",int.class).invoke(world,s.driver);
            if(entity!=null){next.put(entity,s);var motion=actions==null?null:actions.sample(s,System.nanoTime());pose(entity,motion==null||motion.clip()==null||!motion.clip().startsWith("lean_"));}
        }
        boolean removed=false;for(Object entity:drivers.keySet())if(!next.containsKey(entity)){pose(entity,false);removed=true;}
        if(removed||next.isEmpty())restore();
        drivers.clear();drivers.putAll(next);
    }
    private static void pose(Object entity,boolean enabled)throws Exception {
        Class.forName("szmods.runtime.AnimationHooks").getMethod("drone",Object.class,boolean.class).invoke(null,entity,enabled);
    }
    boolean owns(Object entity){return drivers.containsKey(entity);}
    boolean nativeWeapon(Object entity){VehicleWire car=drivers.get(entity);if(car==null||actions==null)return false;var s=actions.sample(car,System.nanoTime());return s.clip()!=null&&s.clip().startsWith("lean_");}
    private record Part(Object model,Object pose) {}
    private Part part(Object resource,Object source,double[] delta,VehicleSeatPose rig,VehicleFleet.Type type,VehicleActionsClient.Sample motion,VehicleWire car,double yaw,double pitch)throws Exception{
        if(resource==null||source==null)return null;Object model=call(resource,"byte","java.lang.Object");if(model==null)return null;
        Object owned=source.getClass().getConstructor(source.getClass()).newInstance(source);
        float[] original=read(owned),base=motion!=null&&motion.clip()!=null?VehicleMotionPose.render(rig,original,heading,type,motion):rig.seated(original,heading,type);
        VehicleLookPose.apply(rig,base,car,motion,yaw,pitch);
        VehicleLookPose.weaponArms(rig,base,original,motion);
        float[] result=transform(base,delta);write(owned,result);lastRoot=Arrays.copyOf(result,Math.min(7,result.length));
        return new Part(model,owned);
    }
    void render(Object camera)throws Exception {
        if(drivers.isEmpty())return;
        if(renderer==null)renderer=Class.forName("sz.animation.fp.N1320579730").getConstructor().newInstance();
        int program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),buffer=glGetInteger(GL_ARRAY_BUFFER_BINDING),func=glGetInteger(GL_DEPTH_FUNC);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK),stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);
        double[] range=new double[2];glGetDoublev(GL_DEPTH_RANGE,range);
        Object state=call(get(Class.forName("1973624787"),"package"),"strictfp","1973624787");
        boolean fp=(Boolean)get(Class.forName("885517060"),"default");int light=(Integer)call(Class.forName("1881987517"),"do","int");
        call(Class.forName("889335306"),"if","void");call(Class.forName("1308394360"),"static","void");
        try{
            Class.forName("885517060").getField("default").setBoolean(null,false);
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);glDepthRange(0,1);glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
            call(get(Class.forName("1308394360"),"interface"),"finally","447252483",get(camera,"throws"));Class.forName("1308394360").getField("abstract").setBoolean(null,true);
            Class<?> fn=Class.forName("kotlin.jvm.functions.Function0");Object draw=Proxy.newProxyInstance(fn.getClassLoader(),new Class<?>[]{fn},(proxy,method,args)->{
                if(!method.getName().equals("invoke"))return null;
                rendering=true;
                try{for(var entry:drivers.entrySet()){
                    Object entity=entry.getKey();VehicleWire car=entry.getValue();
                    if(entity==firstPerson)continue;
                    Object component=call(Class.forName("1478690128"),"new","1579323378",entity);call(component,"try","boolean",true,null);
                    Object source=call(component,"enum","1118769848"),clothes=call(component,"if","1118769848");
                    if(source==null)continue;
                    Object graph=call(Class.forName("578131936"),"break","578131936",entity);heading=((Number)call(graph,"package","float")).doubleValue();
                    double[] q=VehiclePose.quaternion(car.yaw,car.pitch,car.roll),delta=q;
                    Object skeleton=call(call(call(component,"static","1805188833"),"super","387875964"),"strictfp","1612003613");
                    VehicleSeatPose rig=rigs.get(skeleton);
                    if(rig==null){Object[] bones=(Object[])get(skeleton,"case");String[] names=new String[bones.length];int[] parents=new int[bones.length];for(Object bone:bones){int index=(Integer)get(bone,"const");names[index]=(String)get(bone,"this");Object parent=get(bone,"native");parents[index]=parent==null?-1:(Integer)get(parent,"const");}rig=new VehicleSeatPose(names,parents);rigs.put(skeleton,rig);}
                    VehicleActionsClient.Sample motion=actions==null?null:actions.sample(car,System.nanoTime());
                    VehicleFleet.Type type=VehicleFleet.forSeat(car.model,motion==null?0:motion.seat());
                    double yaw=-Math.toRadians(((Number)call(entity,"catch","float")).doubleValue()),pitch=((Number)call(entity,"volatile","float")).doubleValue();aimYaw=yaw;aimPitch=pitch;
                    if(motion!=null){lastClip=String.valueOf(motion.clip());lastPhase=motion.phase();lastDoor=motion.door();lastAnchor=motion.anchor().clone();}
                    Object none=get(Class.forName("1969001007"),"NONE");List<Part> parts=new ArrayList<>();
                    for(Part part:new Part[]{part(call(component,"void","1304833431",none),source,delta,rig,type,motion,car,yaw,pitch),part(call(component,"break","1304833431",none),source,delta,rig,type,motion,car,yaw,pitch),part(call(component,"boolean","1304833431"),clothes,delta,rig,type,motion,car,yaw,pitch)})if(part!=null)parts.add(part);
                    if(parts.isEmpty())continue;
                    call(renderer,"szFp_extends","void");call(renderer,"szFp_this","void",get(call(Class.forName("1402131154"),"try","1402131154"),"e"));
                    double[] at=motion==null?type.seat:motion.anchor();
                    double[] seat=VehiclePose.rotate(q,at[0],at[1]-type.cg,at[2]);seat[0]+=car.x;seat[1]+=car.y+type.cg;seat[2]+=car.z;
                    float[] view=new float[16];call(get(camera,"static"),"import","[F",(Object)view);float[] matrix=view.clone();
                    for(int i=0;i<4;i++)matrix[12+i]=(float)(view[i]*seat[0]+view[4+i]*seat[1]+view[8+i]*seat[2]+view[12+i]);
                    call(get(Class.forName("889335306"),"implements"),"transient","447252483",(Object)matrix);Class.forName("889335306").getField("else").setBoolean(null,true);call(renderer,"szFp_super","void");
                    for(Part part:parts)call(Class.forName("656386660"),"long","void",part.model,true,false,part.pose);
                    if(nativeWeapon(entity)){
                        Object item=call(call(component,"static","1805188833"),"throws","211097523");
                        if(item!=null){
                            Object held=call(item,"continue","168071319");weaponClass=held==null?"null":held.getClass().getName();
                            weaponPose=read(parts.get(0).pose);
                            if(weaponRenderer==null)weaponRenderer=Class.forName("175605189").getConstructor().newInstance();
                            call(weaponRenderer,"super","void",entity,item,skeleton,parts.get(0).pose,0f);nativeWeaponPasses++;
                        }
                    }
                    call(renderer,"szFp_extends","void");draws++;
                }}finally{rendering=false;}
                return get(Class.forName("kotlin.Unit"),"INSTANCE");
            });
            call(call(get(Class.forName("449323257"),"do"),"return","1895043948"),"if","void",draw,true,false);
        }finally{
            rendering=false;call(Class.forName("1881987517"),"while","void",light);Class.forName("885517060").getField("default").setBoolean(null,fp);call(Class.forName("1308394360"),"final","void");call(Class.forName("889335306"),"default","void");((AutoCloseable)state).close();
            glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_STENCIL_TEST,stencil);toggle(GL_SCISSOR_TEST,scissor);glDepthMask(mask);glDepthFunc(func);glDepthRange(range[0],range[1]);
        }
    }
    private static void toggle(int flag,boolean enabled){if(enabled)glEnable(flag);else glDisable(flag);}
    void body(Object component)throws Exception {
        Object entity=get(component,"assert");VehicleWire s=drivers.get(entity);if(s==null)return;
        Object graph=call(Class.forName("578131936"),"break","578131936",entity);
        heading=((Number)call(graph,"package","float")).doubleValue();
        double[] car=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);
        double[] delta=multiply(car,VehiclePose.quaternion(Math.toRadians(heading),0,0));
        Object body=call(component,"enum","1118769848"),clothes=call(component,"if","1118769848");
        if(body!=null)apply(body,delta);if(clothes!=null&&clothes!=body)apply(clothes,delta);
        draws++;
    }
    void apply(Object pose,double[] delta)throws Exception {
        float[] current=read(pose);Saved old=saved.get(pose);
        float[] base=old!=null&&Arrays.equals(current,old.after)?old.before:current;
        float[] result=transform(base,delta);write(pose,result);saved.put(pose,new Saved(base,result));
        lastRoot=Arrays.copyOf(result,Math.min(7,result.length));
    }
    static double[] multiply(double[] a,double[] b){return new double[]{
        a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
        a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]};}
    static float[] transform(float[] base,double[] delta){
        float[] out=base.clone();
        for(int i=0;i<base.length;i+=7){
            double[] p=VehiclePose.rotate(delta,base[i],base[i+1],base[i+2]);
            double[] q=multiply(delta,new double[]{base[i+6],base[i+3],base[i+4],base[i+5]});
            for(int k=0;k<3;k++)out[i+k]=(float)p[k];
            out[i+3]=(float)q[1];out[i+4]=(float)q[2];out[i+5]=(float)q[3];out[i+6]=(float)q[0];
        }
        return out;
    }
    static float[] read(Object pose)throws Exception {
        Object[] positions=(Object[])get(pose,"for"),rotations=(Object[])get(pose,"double");float[] out=new float[positions.length*7];
        for(int i=0;i<positions.length;i++){
            String[] xyz={"assert","byte","catch"},xyzw={"public","implements","float","catch"};
            for(int k=0;k<3;k++)out[i*7+k]=((Number)get(positions[i],xyz[k])).floatValue();
            for(int k=0;k<4;k++)out[i*7+3+k]=((Number)get(rotations[i],xyzw[k])).floatValue();
        }
        return out;
    }
    static void write(Object pose,float[] data)throws Exception {
        for(int i=0;i<data.length/7;i++){
            int j=i*7;call(pose,"public","void",i,data[j],data[j+1],data[j+2]);
            call(pose,"final","void",i,data[j+3],data[j+4],data[j+5],data[j+6]);
        }
    }
    void restore()throws Exception {
        for(var e:saved.entrySet())if(Arrays.equals(read(e.getKey()),e.getValue().after))write(e.getKey(),e.getValue().before);
        saved.clear();
    }
    void clear()throws Exception{restore();for(Object entity:drivers.keySet())pose(entity,false);drivers.clear();rigs.clear();}
    Map<String,Object> inspect(){Map<String,Object> out=new LinkedHashMap<>();out.putAll(Map.of("actors",drivers.size(),"draws",draws,"heading",heading,"root",lastRoot,"clip",lastClip,"phase",lastPhase,"anchor",lastAnchor,"aim",List.of(aimYaw,aimPitch),"nativeWeaponPasses",nativeWeaponPasses,"door",lastDoor));out.put("weaponClass",weaponClass);out.put("weaponPose",weaponPose);return out;}
}
