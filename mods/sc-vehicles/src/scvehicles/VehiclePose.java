package scvehicles;

/** UFO's Y-X-Z body transform, adapted to SC's +Z vehicle nose. */
final class VehiclePose {
    static final double CG=.72;
    static double[] quaternion(double yaw,double pitch,double roll){
        double cy=Math.cos(yaw*.5),sy=Math.sin(yaw*.5),cx=Math.cos(pitch*.5),sx=Math.sin(pitch*.5),cz=Math.cos(roll*.5),sz=Math.sin(roll*.5);
        return new double[]{cy*cx*cz+sy*sx*sz,cy*sx*cz+sy*cx*sz,sy*cx*cz-cy*sx*sz,cy*cx*sz-sy*sx*cz};
    }
    static double[] rotate(double[] q,double x,double y,double z){
        double tx=2*(q[2]*z-q[3]*y),ty=2*(q[3]*x-q[1]*z),tz=2*(q[1]*y-q[2]*x);
        return new double[]{x+q[0]*tx+q[2]*tz-q[3]*ty,y+q[0]*ty+q[3]*tx-q[1]*tz,z+q[0]*tz+q[1]*ty-q[2]*tx};
    }
    static double[] local(VehiclePhysics p,double x,double y,double z){double[] v=rotate(p.rotation(),x,y-p.cg(),z);return new double[]{p.x+v[0],p.y+p.cg()+v[1],p.z+v[2]};}
    static double[] angles(double[] q){
        double[] r=rotate(q,1,0,0),u=rotate(q,0,1,0),f=rotate(q,0,0,1);
        double pitch=Math.asin(Math.max(-1,Math.min(1,-f[1])));
        return new double[]{Math.atan2(f[0],f[2]),pitch,Math.atan2(r[1],u[1])};
    }
}
