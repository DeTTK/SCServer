package scvehicles;

import scvehicles.protocol.VehicleWire;

/** Walkable convex hull in model coordinates, not an enclosing air-filled AABB. */
final class VehicleHull {
    record Box(double x,double y,double z,double hx,double hy,double hz,double pitch) {}
    static final Box[] BOXES=nativeNiva(new Box[]{
        new Box(0,.735,0,.79,.345,1.73,0),
        new Box(0,.98,1.12,.705,.12,.59,0),
        new Box(0,1.355,-.60,.70,.29,.81,0),
        new Box(0,1.32,.40,.69,.085,.33,.94),
        new Box(0,1.28,-1.56,.65,.085,.37,-1.12),
        new Box(0,.495,1.775,.82,.115,.095,0),
        new Box(0,.495,-1.775,.82,.115,.095,0)
    });
    private static Box[] nativeNiva(Box[] boxes){double f=VehicleFleet.nivaUnits();for(int i=0;i<boxes.length;i++){Box b=boxes[i];boxes[i]=new Box(b.x*f,b.y*f,b.z*f,b.hx*f,b.hy*f,b.hz*f,b.pitch);}return boxes;}
    static Box[] boxes(VehicleWire s){return s.model==0?BOXES:VehicleFleet.get(s.model).hull();}
    static double cg(VehicleWire s){return VehicleFleet.get(s.model).cg;}
    static double[] world(VehicleWire s,double[] local){double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll),v=VehiclePose.rotate(q,local[0],local[1]-cg(s),local[2]);return new double[]{s.x+v[0],s.y+cg(s)+v[1],s.z+v[2]};}
    static double[] local(VehicleWire s,double[] world){double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);q[1]=-q[1];q[2]=-q[2];q[3]=-q[3];double[] v=VehiclePose.rotate(q,world[0]-s.x,world[1]-s.y-cg(s),world[2]-s.z);v[1]+=cg(s);return v;}
    static double[] rotation(VehicleWire s,Box b){double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);double c=Math.cos(b.pitch*.5),t=Math.sin(b.pitch*.5);return new double[]{q[0]*c-q[1]*t,q[1]*c+q[0]*t,q[2]*c+q[3]*t,q[3]*c-q[2]*t};}
    /** Highest walkable surface under the actual foot footprint, within contact tolerance. */
    static double support(VehicleWire s,double[] foot) {
        double best=Double.NaN,error=Double.POSITIVE_INFINITY;
        for(double[] sample:new double[][]{{0,0},{-.22,-.22},{-.22,.22},{.22,-.22},{.22,.22}})for(Box b:boxes(s)) {
            double[] centre=world(s,new double[]{b.x,b.y,b.z}),q=rotation(s,b);double[] inverse={q[0],-q[1],-q[2],-q[3]};
            double[] origin=VehiclePose.rotate(inverse,foot[0]+sample[0]-centre[0],foot[1]+.14-centre[1],foot[2]+sample[1]-centre[2]);
            double[] dir=VehiclePose.rotate(inverse,0,-1,0),half={b.hx,b.hy,b.hz};
            double enter=0,exit=.30;int axis=-1;double sign=0;
            for(int a=0;a<3;a++) {
                if(Math.abs(dir[a])<1e-10){if(Math.abs(origin[a])>half[a]){exit=-1;break;}continue;}
                double t1=(-half[a]-origin[a])/dir[a],t2=(half[a]-origin[a])/dir[a];double near=Math.min(t1,t2),far=Math.max(t1,t2);
                if(near>enter){enter=near;axis=a;sign=dir[a]<0?1:-1;}exit=Math.min(exit,far);
            }
            if(axis<0||exit<enter||enter>.30)continue;
            double[] normal=new double[3];normal[axis]=sign;
            if(VehiclePose.rotate(q,normal[0],normal[1],normal[2])[1]<Math.cos(Math.toRadians(40)))continue;
            double h=foot[1]+.14-enter,e=Math.abs(h-foot[1]);
            if(e<.13&&e<error){best=h;error=e;}
        }
        return best;
    }
    private VehicleHull(){}
}
