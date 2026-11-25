package vr;

/** Metres, right-handed coordinates: +X right, +Y up, -Z forward. */
public final class Math3 {
    private Math3(){}
    public record V(double x,double y,double z){
        public static final V ZERO=new V(0,0,0);
        public V add(V b){return new V(x+b.x,y+b.y,z+b.z);}
        public V sub(V b){return new V(x-b.x,y-b.y,z-b.z);}
        public V mul(double n){return new V(x*n,y*n,z*n);}
        public double dot(V b){return x*b.x+y*b.y+z*b.z;}
        public V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}
        public double length(){return Math.sqrt(dot(this));}
        public V unit(){double l=length();return l<1e-10?ZERO:mul(1/l);}
        public boolean finite(){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z);}
    }
    public record Q(double x,double y,double z,double w){
        public static final Q ID=new Q(0,0,0,1);
        public boolean finite(){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&Double.isFinite(w);}
        public Q unit(){double l=Math.sqrt(x*x+y*y+z*z+w*w);return l<1e-12?ID:new Q(x/l,y/l,z/l,w/l);}
        public Q inverse(){Q q=unit();return new Q(-q.x,-q.y,-q.z,q.w);}
        public Q mul(Q b){return new Q(w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z).unit();}
        public V rotate(V v){Q q=unit();V u=new V(q.x,q.y,q.z),t=u.cross(v).mul(2);return v.add(t.mul(q.w)).add(u.cross(t));}
        public static Q axis(V axis,double radians){V n=axis.unit();double s=Math.sin(radians/2);return new Q(n.x*s,n.y*s,n.z*s,Math.cos(radians/2));}
        /** Shortest rotation, including opposite vectors without unstable roll. */
        public static Q fromTo(V from,V to){V a=from.unit(),b=to.unit();double d=a.dot(b);if(a.length()==0||b.length()==0)return ID;
            if(d<-.999999){V axis=a.cross(Math.abs(a.x)<.8?new V(1,0,0):new V(0,1,0));return axis(axis,Math.PI);}
            V c=a.cross(b);return new Q(c.x,c.y,c.z,1+d).unit();}
    }
    public record Pose(V p,Q q){
        public static final Pose ID=new Pose(V.ZERO,Q.ID);
        public boolean finite(){return p.finite()&&q.finite();}
        public Pose compose(Pose child){return new Pose(p.add(q.rotate(child.p)),q.mul(child.q));}
        public Pose inverse(){Q i=q.inverse();return new Pose(i.rotate(p.mul(-1)),i);}
        public V transform(V local){return p.add(q.rotate(local));}
        public V forward(){return q.rotate(new V(0,0,-1));}
        public float[] view(){return matrix(inverse());}
        public double[] viewDouble(){return matrixDouble(inverse());}
        public static float[] matrix(Pose p){double[] precise=matrixDouble(p);float[] result=new float[16];for(int i=0;i<16;i++)result[i]=(float)precise[i];return result;}
        public static double[] matrixDouble(Pose p){Q q=p.q.unit();double x=q.x,y=q.y,z=q.z,w=q.w;
            return new double[]{1-2*y*y-2*z*z,2*x*y+2*w*z,2*x*z-2*w*y,0,
                2*x*y-2*w*z,1-2*x*x-2*z*z,2*y*z+2*w*x,0,
                2*x*z+2*w*y,2*y*z-2*w*x,1-2*x*x-2*y*y,0,p.p.x,p.p.y,p.p.z,1};}
    }
    public record Fov(double left,double right,double up,double down){
        public float[] projection(double near,double far){
            if(!(near>0&&far>near))throw new IllegalArgumentException("Invalid clip range");
            double l=Math.tan(left),r=Math.tan(right),u=Math.tan(up),d=Math.tan(down),width=r-l,height=u-d;
            if(!(width>0&&height>0))throw new IllegalArgumentException("Invalid eye FOV");
            return new float[]{(float)(2/width),0,0,0,0,(float)(2/height),0,0,(float)((r+l)/width),(float)((u+d)/height),(float)(-(far+near)/(far-near)),-1,0,0,(float)(-2*far*near/(far-near)),0};
        }
    }
    /** Geometric two-bone solve. Keeps segment lengths, clamps unreachable targets. */
    public static V elbow(V shoulder,V wrist,V pole,double upper,double lower){
        if(!(upper>0&&lower>0))throw new IllegalArgumentException("Arm lengths must be positive");
        V direction=wrist.sub(shoulder).unit();if(direction.length()==0)direction=new V(0,0,-1);
        double distance=Math.max(Math.abs(upper-lower)+1e-6,Math.min(upper+lower-1e-6,wrist.sub(shoulder).length()));
        double along=(upper*upper-lower*lower+distance*distance)/(2*distance),height=Math.sqrt(Math.max(0,upper*upper-along*along));
        V bend=pole.sub(shoulder),normal=bend.sub(direction.mul(bend.dot(direction))).unit();
        if(normal.length()==0)normal=direction.cross(Math.abs(direction.y)<.9?new V(0,1,0):new V(1,0,0)).unit();
        return shoulder.add(direction.mul(along)).add(normal.mul(height));
    }
}
