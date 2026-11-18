package scvehicles;

import java.util.*;
import javax.vecmath.*;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.linearmath.Transform;
import scvehicles.protocol.VehicleWire;
import static scvehicles.NativeAccess.*;

/** Private scene colliders, registered through the game's existing spatial tree. */
final class VehicleCollision {
    private Object tree,world;
    private int treeChanges,reinsertions;
    private final Map<UUID,Proxy> proxies=new LinkedHashMap<>();
    static final class Proxy {Object owner;List<Object> pieces=new ArrayList<>();VehicleWire pose;}
    private static void set(Object o,String name,Object value)throws Exception{java.lang.reflect.Field f=o.getClass().getField(name);f.set(o,value);}
    void sync(Object world,Object player,UUID driving,Collection<VehicleWire> states)throws Exception {
        if(this.world!=world){clear();this.world=world;}
        Object active=call(call(Class.forName("493563411"),"try","493563411",world),"synchronized","1581944406");
        if(active!=tree){tree=active;treeChanges++;}
        Set<UUID> ids=new HashSet<>();
        for(VehicleWire state:states) {
            ids.add(state.id);Proxy p=proxies.get(state.id);
            if(p==null){p=create(state,world);proxies.put(state.id,p);}
            for(Object part:p.pieces)set(part,"ignored",state.id.equals(driving)?player:null);
            boolean present=((Map<?,?>)get(tree,"transient")).containsKey(get(p.owner,"r"));
            if(p.pose!=null&&same(p.pose,state)&&present)continue;
            if(p.pose!=null&&!present)reinsertions++;
            for(int i=0;i<p.pieces.size();i++)update(p.pieces.get(i),state,VehicleHull.boxes(state)[i]);
            // This native operation atomically replaces the broad-phase root and
            // builds its child tree. No foreign scene entry is changed or removed.
            call(tree,"abstract","void",p.owner,p.pieces);p.pose=state.copy();
            double[] q=VehiclePose.quaternion(state.yaw,state.pitch,state.roll),r=VehiclePose.rotate(q,1,0,0),u=VehiclePose.rotate(q,0,1,0),f=VehiclePose.rotate(q,0,0,1);
            double[] origin=VehicleHull.world(state,new double[3]);
            float[] matrix={(float)r[0],(float)r[1],(float)r[2],0,(float)u[0],(float)u[1],(float)u[2],0,(float)f[0],(float)f[1],(float)f[2],0,(float)origin[0],(float)origin[1],(float)origin[2],1};
            call(get(p.owner,"m"),"default","447252483",(Object)matrix);
        }
        for(var it=proxies.entrySet().iterator();it.hasNext();){var e=it.next();if(!ids.contains(e.getKey())){call(tree,"class","void",e.getValue().owner);it.remove();}}
    }
    private static boolean same(VehicleWire a,VehicleWire b){return Math.abs(a.x-b.x)+Math.abs(a.y-b.y)+Math.abs(a.z-b.z)+Math.abs(a.yaw-b.yaw)+Math.abs(a.pitch-b.pitch)+Math.abs(a.roll-b.roll)<1e-7;}
    private Proxy create(VehicleWire state,Object world)throws Exception {
        Class<?> definition=Class.forName("1814125595"),bounds=Class.forName("1994198637"),owner=Class.forName("1886271463");
        Object d=definition.getConstructor(String.class,Class.forName("1669874697"),boolean.class).newInstance("sc-vehicles/collision",null,false);
        Object json=Class.forName("com.google.gson.JsonObject").getConstructor().newInstance();
        Object options=Class.forName("1422486348").getConstructor(String.class,json.getClass(),int.class,String.class,Integer.class,String.class).newInstance("sc-vehicles/collision",json,0,"",null,"");
        set(d,"void",options);set(d,"volatile","");set(d,"b",256);set(d,"default",Class.forName("1384438816").getField("OBB").get(null));
        Proxy p=new Proxy();p.owner=call(d,"new","1886271463",state.id);set(p.owner,"q",world);set(p.owner,"interface",true);
        Class<?> collider=Class.forName("scvehicles.NativeVehicleCollider");
        for(int i=0;i<VehicleHull.boxes(state).length;i++) {
            VehicleHull.Box b=VehicleHull.boxes(state)[i];Object aabb=bounds.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class).newInstance(-b.hx(),-b.hy(),-b.hz(),b.hx(),b.hy(),b.hz());
            Object part=collider.getConstructor(owner,String.class,bounds).newInstance(p.owner,"vehicle-hull-"+i,aabb);
            BoxShape shape=new BoxShape(new Vector3f((float)b.hx(),(float)b.hy(),(float)b.hz()));shape.setMargin(.005f);set(part,"goto",shape);set(part,"catch",new Transform());p.pieces.add(part);
        }
        return p;
    }
    private static void update(Object part,VehicleWire state,VehicleHull.Box box)throws Exception {
        double[] c=VehicleHull.world(state,new double[]{box.x(),box.y(),box.z()}),q=VehicleHull.rotation(state,box);
        Transform t=(Transform)get(part,"catch");t.setIdentity();t.setRotation(new Quat4f((float)q[1],(float)q[2],(float)q[3],(float)q[0]));t.origin.set((float)c[0],(float)c[1],(float)c[2]);
        double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}){double[] v=VehiclePose.rotate(q,x*box.hx(),y*box.hy(),z*box.hz());for(int a=0;a<3;a++){lo[a]=Math.min(lo[a],c[a]+v[a]);hi[a]=Math.max(hi[a],c[a]+v[a]);}}
        Object bounds=get(part,"transient");String[] names={"return","void","long","class","final","default"};for(int i=0;i<6;i++)set(bounds,names[i],i<3?lo[i]:hi[i-3]);
    }
    void driver(UUID id,Object player)throws Exception{for(var e:proxies.entrySet())for(Object piece:e.getValue().pieces)set(piece,"ignored",e.getKey().equals(id)?player:null);}
    void suppress(UUID id,boolean value)throws Exception{Proxy p=proxies.get(id);if(p!=null)for(Object piece:p.pieces)set(piece,"suppressed",value);}
    void clear()throws Exception{if(tree!=null)for(Proxy p:proxies.values())call(tree,"class","void",p.owner);proxies.clear();tree=world=null;}
    int size(){return proxies.size();}
    Map<String,Object> inspect(){return Map.of("treeChanges",treeChanges,"reinsertions",reinsertions);}
}
