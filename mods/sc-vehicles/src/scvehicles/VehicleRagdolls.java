package scvehicles;

import java.lang.reflect.Method;
import java.util.*;
import scvehicles.protocol.VehicleWire;

/** Immutable copies handed to the native ragdoll physics thread. */
final class VehicleRagdolls {
    private Method publish,clear;private boolean resolved,directed;
    private void resolve()throws ReflectiveOperationException{
        if(resolved)return;resolved=true;
        try{Class<?> bridge=Class.forName("szmods.runtime.RagdollVehicleHooks");
            try{publish=bridge.getMethod("publishDirected",Object.class,String[].class,double[][].class);directed=true;}
            catch(NoSuchMethodException olderBridge){publish=bridge.getMethod("publish",Object.class,String[].class,double[][].class);}
            clear=bridge.getMethod("clear",Object.class);}
        catch(ClassNotFoundException oldRuntime){/* Death still works with an older runtime. */}
    }
    void frame(Collection<VehicleWire> states)throws ReflectiveOperationException{
        resolve();if(publish==null)return;
        List<String> ids=new ArrayList<>();List<double[]> shapes=new ArrayList<>();
        for(VehicleWire s:states){
            int n=0;for(VehicleHull.Box b:VehicleHull.boxes(s)){
                double[] p=VehicleHull.world(s,new double[]{b.x(),b.y(),b.z()}),q=VehicleHull.rotation(s,b);
                ids.add(s.id+"/h"+n++);shapes.add(row(s,p,b.hx(),b.hy(),b.hz(),q,0));
            }
            n=0;double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);
            for(double[] w:VehicleFleet.get(s.model).wheels){
                double[] p=VehicleHull.world(s,new double[]{w[0],w[1],w[2]});
                ids.add(s.id+"/w"+n++);shapes.add(row(s,p,Math.max(.09,Math.min(.24,w[3]*.32)),w[3],w[3],q,1));
            }
        }
        publish.invoke(null,this,ids.toArray(String[]::new),shapes.toArray(double[][]::new));
    }
    private double[] row(VehicleWire s,double[] p,double hx,double hy,double hz,double[] q,int shape){
        double[] row={p[0],p[1],p[2],hx,hy,hz,q[1],q[2],q[3],q[0],s.vx,s.vy,s.vz,shape};
        if(!directed)return row;double[] out=Arrays.copyOf(row,17);out[14]=s.x;out[15]=s.y+VehicleHull.cg(s);out[16]=s.z;return out;
    }
    void clear()throws ReflectiveOperationException{resolve();if(clear!=null)clear.invoke(null,this);}
}
