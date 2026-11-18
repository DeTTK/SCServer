package replay;

/** OpenGL camera pose: local right +X, up +Y, forward -Z. */
record Pose(Pose.V p, Pose.Q q) {
    record V(double x, double y, double z) {
        V add(V b) { return new V(x+b.x,y+b.y,z+b.z); }
        V sub(V b) { return new V(x-b.x,y-b.y,z-b.z); }
        V mul(double s) { return new V(x*s,y*s,z*s); }
        double dot(V b){return x*b.x+y*b.y+z*b.z;}
        V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}
        V unit(){double n=length();return n<1e-9?new V(0,0,0):mul(1/n);}
        double length() { return Math.sqrt(x*x+y*y+z*z); }
        boolean finite() { return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z); }
    }
    record Q(double x, double y, double z, double w) {
        static final Q ID = new Q(0,0,0,1);
        Q unit() {
            double n=Math.sqrt(x*x+y*y+z*z+w*w);
            if(!Double.isFinite(n)||n<1e-10) throw new IllegalArgumentException("Invalid quaternion");
            return new Q(x/n,y/n,z/n,w/n);
        }
        Q inverse() { return new Q(-x,-y,-z,w); }
        Q mul(Q b) { return new Q(w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,
            w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z).unit(); }
        V rotate(V v) {
            V t=new V(2*(y*v.z-z*v.y),2*(z*v.x-x*v.z),2*(x*v.y-y*v.x));
            return v.add(t.mul(w)).add(new V(y*t.z-z*t.y,z*t.x-x*t.z,x*t.y-y*t.x));
        }
        static Q axis(double degrees,int axis) {
            double a=Math.toRadians(degrees)*.5,s=Math.sin(a);
            return new Q(axis==0?s:0,axis==1?s:0,axis==2?s:0,Math.cos(a));
        }
        static Q euler(double yaw,double pitch,double roll) { return axis(yaw,1).mul(axis(pitch,0)).mul(axis(roll,2)); }
        static Q around(V axis,double degrees){V a=axis.unit();double h=Math.toRadians(degrees)*.5,s=Math.sin(h);return new Q(a.x*s,a.y*s,a.z*s,Math.cos(h)).unit();}
        V euler(){
            double pitch=Math.asin(Math.max(-1,Math.min(1,2*(w*x-y*z))));
            double yaw=Math.atan2(2*(x*z+w*y),1-2*(x*x+y*y));
            double roll=Math.atan2(2*(x*y+w*z),1-2*(x*x+z*z));
            if(Math.abs(Math.cos(pitch))<1e-6){yaw=Math.atan2(2*(w*y-x*z),1-2*(y*y+z*z));roll=0;}
            return new V(Math.toDegrees(yaw),Math.toDegrees(pitch),Math.toDegrees(roll));
        }
        static Q blend(Q a,Q b,double t) {
            double dot=a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w;
            if(dot<0) { b=new Q(-b.x,-b.y,-b.z,-b.w); dot=-dot; }
            double s0=1-t,s1=t;
            if(dot<.9995) { double angle=Math.acos(Math.min(1,dot)),s=Math.sin(angle); s0=Math.sin((1-t)*angle)/s;s1=Math.sin(t*angle)/s; }
            return new Q(a.x*s0+b.x*s1,a.y*s0+b.y*s1,a.z*s0+b.z*s1,a.w*s0+b.w*s1).unit();
        }
    }
    Pose offset(V offset,Q rotation,boolean followRotation,Q upright) {
        Q basis=followRotation?q:upright;
        return new Pose(p.add(basis.rotate(offset)),basis.mul(rotation));
    }
    Pose smooth(Pose target,double speed,double dt) {
        double t=speed<=0?1:1-Math.exp(-speed*Math.max(0,Math.min(.25,dt)));
        return new Pose(p.add(target.p.sub(p).mul(t)),Q.blend(q,target.q,t));
    }
    static Pose lookAt(V position,V target){V d=target.sub(position).unit();return new Pose(position,Q.euler(Math.toDegrees(Math.atan2(-d.x,-d.z)),Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,d.y)))),0));}
    static float[] projection(double fov,double aspect){
        double t=1/Math.tan(Math.toRadians(fov)*.5),near=.01,far=10000;
        return new float[]{(float)(t/aspect),0,0,0,0,(float)t,0,0,0,0,(float)((far+near)/(near-far)),-1,0,0,(float)(2*far*near/(near-far)),0};
    }
    double[] view() {
        Q r=q.inverse(); double x=r.x,y=r.y,z=r.z,w=r.w;
        double[] m={1-2*(y*y+z*z),2*(x*y+z*w),2*(x*z-y*w),0,
            2*(x*y-z*w),1-2*(x*x+z*z),2*(y*z+x*w),0,
            2*(x*z+y*w),2*(y*z-x*w),1-2*(x*x+y*y),0,0,0,0,1};
        m[12]=-(m[0]*p.x+m[4]*p.y+m[8]*p.z);
        m[13]=-(m[1]*p.x+m[5]*p.y+m[9]*p.z);
        m[14]=-(m[2]*p.x+m[6]*p.y+m[10]*p.z);
        return m;
    }
    double[] model(){double[] m=new Pose(new V(0,0,0),q.inverse()).view();m[12]=p.x;m[13]=p.y;m[14]=p.z;return m;}
}

