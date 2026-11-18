package szmods.runtime;

import java.util.*;
import java.lang.reflect.Field;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.shapes.*;
import com.bulletphysics.dynamics.*;
import com.bulletphysics.linearmath.*;
import javax.vecmath.*;

/** Runs exclusively at the native ragdoll world's existing pre-step boundary.
 * Game/render threads publish immutable collider snapshots; no extra simulation. */
public final class RagdollVehicleHooks {
    private record Snapshot(Object owner,String[] ids,double[][] boxes,long at) {}
    private static volatile Snapshot snapshot=new Snapshot(null,new String[0],new double[0][],0);
    private static final Map<DynamicsWorld,Context> contexts=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Part {RigidBody body;double hx,hy,hz;int shape;}
    private static final class Car {double x,z,vx,vz,speed,side,lastContact=-10,until=-10;}
    private static final class Context {final Map<String,Part> parts=new HashMap<>();final Map<String,Car> cars=new HashMap<>();volatile Map<String,Object> audit=Map.of();long contacts,ejections;double time;}
    private static final class KinematicBody extends RigidBody {
        Car car;
        KinematicBody(RigidBodyConstructionInfo info){super(info);collisionFlags=(collisionFlags&~1)|2;setActivationState(4);}
        void grip(boolean fast){friction=fast?.18f:.85f;}
    }
    private static volatile Field container,worldField,controllers,anchor,shift;
    public static volatile long steps,created,removed;public static volatile String error="";
    private RagdollVehicleHooks() {}
    /** Rows: centre XYZ, half extents XYZ, quaternion XYZW, velocity XYZ, shape (0 box / 1 tyre). */
    public static void publish(Object owner,String[] ids,double[][] boxes){
        publish(owner,ids,boxes,14);
    }
    /** Directed rows append the whole car's CG centre XYZ to the legacy row. */
    public static void publishDirected(Object owner,String[] ids,double[][] boxes){
        publish(owner,ids,boxes,17);
    }
    private static void publish(Object owner,String[] ids,double[][] boxes,int width){
        if(owner==null||ids.length!=boxes.length||boxes.length>8192)throw new IllegalArgumentException("vehicle_ragdoll_snapshot");
        double[][] copy=new double[boxes.length][];
        for(int i=0;i<copy.length;i++){
            if(ids[i]==null||boxes[i].length!=width)throw new IllegalArgumentException("vehicle_ragdoll_row");copy[i]=boxes[i].clone();
            for(double v:copy[i])if(!Double.isFinite(v))throw new IllegalArgumentException("vehicle_ragdoll_nonfinite");
            for(int n=3;n<6;n++)if(copy[i][n]<=0||copy[i][n]>30)throw new IllegalArgumentException("vehicle_ragdoll_extent");
        }
        snapshot=new Snapshot(owner,ids.clone(),copy,System.nanoTime());
    }
    public static void clear(Object owner){Snapshot s=snapshot;if(s.owner==owner)snapshot=new Snapshot(null,new String[0],new double[0][],System.nanoTime());}
    public static void prepare(Object wrapper,float seconds){
        try{
            if(container==null){Class<?> base=Class.forName("1100232767");Field c=base.getField("continue");worldField=Class.forName("780138831").getField("strictfp");controllers=base.getField("byte");anchor=Class.forName("1148853409").getField("boolean");shift=Class.forName("295175521").getField("switch");container=c;}
            DynamicsWorld world=(DynamicsWorld)worldField.get(container.get(wrapper));if(world==null)return;
            Context context; synchronized(contexts){context=contexts.computeIfAbsent(world,k->new Context());}
            Vector3f origin=new Vector3f();
            for(Object controller:((Map<?,?>)controllers.get(wrapper)).values())if(anchor.getDeclaringClass().isInstance(controller)){
                // Each native corpse owns a local Bullet world. Its terrain shift
                // equals floor(initial position) - initial position.
                origin.set((Vector3f)anchor.get(controller));origin.sub((Vector3f)shift.get(wrapper));break;
            }
            context.time+=Math.max(0,Math.min(.05,seconds));
            sync(world,context,snapshot,origin);eject(world,context);steps++;
            if(Boolean.getBoolean("sz.vehicleRagdolls.audit")){
                List<double[]> bodies=new ArrayList<>();Vector3f p=new Vector3f(),v=new Vector3f();var objects=world.getCollisionObjectArray();
                for(int i=0;i<objects.size();i++){RigidBody body=RigidBody.upcast(objects.getQuick(i));if(body==null||body.getInvMass()==0)continue;body.getCenterOfMassPosition(p);body.getLinearVelocity(v);bodies.add(new double[]{origin.x+p.x,origin.y+p.y,origin.z+p.z,v.x,v.y,v.z});}
                var dispatcher=(com.bulletphysics.collision.dispatch.CollisionDispatcher)world.getDispatcher();
                for(int i=0;i<dispatcher.getNumManifolds();i++){var m=dispatcher.getManifoldByIndexInternal(i);if(m.getNumContacts()>0&&(m.getBody0() instanceof KinematicBody||m.getBody1() instanceof KinematicBody))context.contacts++;}
                context.audit=Map.of("origin",new float[]{origin.x,origin.y,origin.z},"bodies",bodies,"parts",context.parts.size(),"contacts",context.contacts,"ejections",context.ejections);
            }
        }catch(Exception failure){error=failure.toString();}
    }
    private static void sync(DynamicsWorld world,Context context,Snapshot s,Vector3f origin){
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        Vector3f at=new Vector3f();var objects=world.getCollisionObjectArray();
        for(int i=0;i<objects.size();i++){
            RigidBody body=RigidBody.upcast(objects.getQuick(i));if(body==null||body.getInvMass()==0)continue;
            body.getCenterOfMassPosition(at);minX=Math.min(minX,at.x);minY=Math.min(minY,at.y);minZ=Math.min(minZ,at.z);maxX=Math.max(maxX,at.x);maxY=Math.max(maxY,at.y);maxZ=Math.max(maxZ,at.z);
        }
        Set<String> live=new HashSet<>(),liveCars=new HashSet<>();double age=Math.max(0,Math.min(.1,(System.nanoTime()-s.at)*1e-9));boolean fresh=System.nanoTime()-s.at<500_000_000L;
        if(fresh&&Double.isFinite(minX))for(int i=0;i<s.boxes.length;i++){
            double[] b=s.boxes[i];double radius=Math.sqrt(b[3]*b[3]+b[4]*b[4]+b[5]*b[5]);
            double x=b[0]-origin.x,y=b[1]-origin.y,z=b[2]-origin.z;
            if(x+radius<minX-6||x-radius>maxX+6||y+radius<minY-6||y-radius>maxY+6||z+radius<minZ-6||z-radius>maxZ+6)continue;
            String id=s.ids[i];live.add(id);Part part=context.parts.get(id);int shape=(int)b[13];Car car=null;
            if(b.length==17){String carId=id.substring(0,id.lastIndexOf('/'));liveCars.add(carId);car=context.cars.computeIfAbsent(carId,k->new Car());car.x=b[14]-origin.x+b[10]*age;car.z=b[16]-origin.z+b[12]*age;car.vx=b[10];car.vz=b[12];car.speed=Math.hypot(car.vx,car.vz);}
            Transform transform=new Transform();transform.setIdentity();transform.origin.set((float)(x+b[10]*age),(float)(y+b[11]*age),(float)(z+b[12]*age));transform.setRotation(new Quat4f((float)b[6],(float)b[7],(float)b[8],(float)b[9]));
            if(part!=null&&(part.hx!=b[3]||part.hy!=b[4]||part.hz!=b[5]||part.shape!=shape)){world.removeRigidBody(part.body);context.parts.remove(id);part=null;removed++;}
            if(part==null){
                CollisionShape collision=shape==1?tyre(b):new BoxShape(new Vector3f((float)b[3],(float)b[4],(float)b[5]));collision.setMargin(.015f);
                part=new Part();part.hx=b[3];part.hy=b[4];part.hz=b[5];part.shape=shape;
                RigidBodyConstructionInfo info=new RigidBodyConstructionInfo(0,new DefaultMotionState(transform),collision,new Vector3f());info.friction=.85f;info.restitution=.03f;
                part.body=new KinematicBody(info);
                world.addRigidBody(part.body);context.parts.put(id,part);created++;
            }
            ((KinematicBody)part.body).car=car;
            ((KinematicBody)part.body).grip(car!=null&&car.speed>=6);
            part.body.getMotionState().setWorldTransform(transform);part.body.setWorldTransform(transform);world.updateSingleAabb(part.body);
        }
        for(var it=context.parts.entrySet().iterator();it.hasNext();){var entry=it.next();if(!live.contains(entry.getKey())){world.removeRigidBody(entry.getValue().body);it.remove();removed++;}}
        context.cars.keySet().retainAll(liveCars);
    }
    /** A short, contact-triggered escape impulse for the entire articulated body.
     * Preserve joints/angular velocity and native terrain contacts; never teleport
     * limbs, attach a corpse to a car, or keep accelerating it after separation. */
    private static void eject(DynamicsWorld world,Context context){
        if(context.cars.isEmpty())return;
        Set<Car> touching=new HashSet<>();var dispatcher=(com.bulletphysics.collision.dispatch.CollisionDispatcher)world.getDispatcher();
        for(int i=0;i<dispatcher.getNumManifolds();i++){
            var m=dispatcher.getManifoldByIndexInternal(i);if(m.getNumContacts()==0)continue;
            Object a=m.getBody0(),b=m.getBody1();KinematicBody car=a instanceof KinematicBody?(KinematicBody)a:b instanceof KinematicBody?(KinematicBody)b:null;
            if(car==null||car.car==null||car.car.speed<6)continue;
            RigidBody other=RigidBody.upcast((CollisionObject)(a==car?b:a));if(other==null||other.getInvMass()==0)continue;
            for(int n=0;n<m.getNumContacts();n++)if(m.getContactPoint(n).getDistance()<=.035f){touching.add(car.car);break;}
        }
        if(touching.isEmpty())return;
        List<RigidBody> bodies=new ArrayList<>();Vector3f p=new Vector3f(),v=new Vector3f();double mass=0,x=0,z=0,vx=0,vy=0,vz=0;var objects=world.getCollisionObjectArray();
        for(int i=0;i<objects.size();i++){
            RigidBody body=RigidBody.upcast(objects.getQuick(i));if(body==null||body.getInvMass()==0)continue;
            double m=1/body.getInvMass();body.getCenterOfMassPosition(p);body.getLinearVelocity(v);mass+=m;x+=p.x*m;z+=p.z*m;vx+=v.x*m;vy+=v.y*m;vz+=v.z*m;bodies.add(body);
        }
        if(mass==0)return;x/=mass;z/=mass;vx/=mass;vy/=mass;vz/=mass;
        // One car per pre-step: a pile-up must not stack several launch boosts.
        Car car=null;double nearest=Double.POSITIVE_INFINITY;
        for(Car candidate:touching){double distance=Math.hypot(x-candidate.x,z-candidate.z);if(distance<nearest){nearest=distance;car=candidate;}}
        double fx=car.vx/car.speed,fz=car.vz/car.speed,rx=fz,rz=-fx;
        boolean first=context.time-car.lastContact>.65;
        if(first){double side=(x-car.x)*rx+(z-car.z)*rz;car.side=Math.abs(side)>.035?Math.signum(side):1;car.until=context.time+.28;context.ejections++;}
        car.lastContact=context.time;if(context.time>car.until)return;
        double forward=vx*fx+vz*fz,outward=(vx*rx+vz*rz)*car.side;
        double df=Math.max(0,Math.min(28,car.speed*1.06+1.2)-forward),ds=Math.max(0,Math.min(7,2.8+car.speed*.10)-outward),dy=first?Math.max(0,Math.min(3.6,1.2+car.speed*.08)-vy):0;
        Vector3f delta=new Vector3f((float)(fx*df+rx*car.side*ds),(float)dy,(float)(fz*df+rz*car.side*ds));
        if(delta.length()>32)delta.scale(32/delta.length());
        for(RigidBody body:bodies){body.getLinearVelocity(v);v.add(delta);body.setLinearVelocity(v);body.activate();}
    }
    private static CollisionShape tyre(double[] b){
        var points=new com.bulletphysics.util.ObjectArrayList<Vector3f>();
        for(int end:new int[]{-1,1})for(int n=0;n<16;n++){double a=n*Math.PI/8;points.add(new Vector3f((float)(end*b[3]),(float)(Math.cos(a)*b[4]),(float)(Math.sin(a)*b[5])));}
        return new ConvexHullShape(points);
    }
    public static Map<String,Object> inspect(){List<Object> worlds=new ArrayList<>();synchronized(contexts){for(Context c:contexts.values())if(!c.audit.isEmpty())worlds.add(c.audit);}return Map.of("steps",steps,"created",created,"removed",removed,"error",error,"worlds",worlds);}
}
