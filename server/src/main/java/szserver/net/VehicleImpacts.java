package szserver.net;

import scvehicles.protocol.*;

/** Read-only swept contact validation; vehicle motion remains client-owned. */
public final class VehicleImpacts {
    public static final double LETHAL_SPEED=6;
    public record Hit(double[] point,double[] velocity,double[] impulse){}
    public record Pending(Hit hit,long expires){}
    private VehicleImpacts(){}
    public static boolean moving(VehicleWire from,VehicleWire to,double seconds){if(from==null||to==null||from.model!=to.model||!from.id.equals(to.id)||seconds<.01||seconds>.30)return false;double travel=Math.sqrt(Math.pow(to.x-from.x,2)+Math.pow(to.y-from.y,2)+Math.pow(to.z-from.z,2));return Math.sqrt(to.vx*to.vx+to.vy*to.vy+to.vz*to.vz)>=LETHAL_SPEED&&travel>=.025&&travel/seconds>=LETHAL_SPEED*.65&&travel<=seconds*55+.5;}
    public static Hit hit(VehicleWire from,VehicleWire to,double seconds,double[] foot,double[] pedestrianVelocity){
        return hit(from,to,seconds,foot,pedestrianVelocity,1.8,.30);
    }
    public static Hit hit(VehicleWire from,VehicleWire to,double seconds,double[] foot,double[] pedestrianVelocity,double height,double radius){
        if(!Double.isFinite(height)||!Double.isFinite(radius)||height<=0||radius<=0)return null;
        if(from==null||from.model!=to.model||!from.id.equals(to.id)||seconds<.01||seconds>.30)return null;
        double dx=to.x-from.x,dy=to.y-from.y,dz=to.z-from.z,travel=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double speed=Math.sqrt(to.vx*to.vx+to.vy*to.vy+to.vz*to.vz);
        if(speed<LETHAL_SPEED||travel<.025||travel/seconds<LETHAL_SPEED*.65||travel>seconds*55+.5)return null;
        double ux=to.vx/speed,uy=to.vy/speed,uz=to.vz/speed;
        if((dx*ux+dy*uy+dz*uz)/travel<.45)return null;
        double closing=speed-(pedestrianVelocity[0]*ux+pedestrianVelocity[1]*uy+pedestrianVelocity[2]*uz);
        if(closing<4)return null;
        double reach=VehicleTypes.LENGTH[to.model]+VehicleTypes.WIDTH[to.model]+travel+height+radius+2;
        if(Math.abs(foot[0]-to.x)>reach||Math.abs(foot[1]-to.y)>reach||Math.abs(foot[2]-to.z)>reach)return null;
        double angular=Math.max(Math.abs(angle(to.yaw-from.yaw)),Math.max(Math.abs(angle(to.pitch-from.pitch)),Math.abs(angle(to.roll-from.roll))));
        int pieces=Math.max(1,(int)Math.ceil(angular/.10));pieces=Math.min(pieces,32);
        for(int step=0;step<pieces;step++){
            double a=(double)step/pieces,b=(double)(step+1)/pieces;
            VehicleWire first=pose(from,to,a),last=pose(from,to,b);
            for(double fraction:new double[]{.17,.36,.56,.75,.83}){double h=height*fraction;
                // A capsule column, rewound by bounded pedestrian motion at each sweep end.
                double[] p=local(first,new double[]{foot[0]-pedestrianVelocity[0]*seconds*(1-a),foot[1]+h-pedestrianVelocity[1]*seconds*(1-a),foot[2]-pedestrianVelocity[2]*seconds*(1-a)});
                double[] q=local(last,new double[]{foot[0]-pedestrianVelocity[0]*seconds*(1-b),foot[1]+h-pedestrianVelocity[1]*seconds*(1-b),foot[2]-pedestrianVelocity[2]*seconds*(1-b)});
                for(double[] box:VehicleImpactGeometry.HULL[to.model])if(touches(p,q,box,radius)){
                    // Clear the moving hull rather than launch more slowly than
                    // it and let its friction carry the corpse along. Use the
                    // actual travel direction, including reverse and a slide.
                    double horizontal=Math.hypot(to.vx,to.vz),fx=to.vx/Math.max(.001,horizontal),fz=to.vz/Math.max(.001,horizontal);
                    double rx=fz,rz=-fx,lateral=(foot[0]-last.x)*rx+(foot[2]-last.z)*rz;
                    double side=Math.abs(lateral)>.035?Math.signum(lateral):1;
                    double transfer=Math.min(26,horizontal*1.06+1.2),outward=Math.min(7,2.8+closing*.10),lift=Math.min(4.2,1.2+closing*.08);
                    double[] velocity={pedestrianVelocity[0]*.10+fx*transfer+rx*side*outward,Math.max(0,uy*transfer)+lift,pedestrianVelocity[2]*.10+fz*transfer+rz*side*outward};
                    double kick=Math.min(70,12+closing*1.5);
                    return new Hit(new double[]{foot[0]-ux*.18,foot[1]+Math.min(1.1,h),foot[2]-uz*.18},velocity,new double[]{(fx+rx*side*.5)*kick,kick*.15,(fz+rz*side*.5)*kick});
                }
            }
        }
        return null;
    }
    private static double angle(double a){return Math.atan2(Math.sin(a),Math.cos(a));}
    private static VehicleWire pose(VehicleWire a,VehicleWire b,double t){VehicleWire s=new VehicleWire();s.model=a.model;s.x=a.x+(b.x-a.x)*t;s.y=a.y+(b.y-a.y)*t;s.z=a.z+(b.z-a.z)*t;s.yaw=(float)(a.yaw+angle(b.yaw-a.yaw)*t);s.pitch=(float)(a.pitch+angle(b.pitch-a.pitch)*t);s.roll=(float)(a.roll+angle(b.roll-a.roll)*t);return s;}
    private static double[] local(VehicleWire s,double[] p){
        double x=p[0]-s.x,y=p[1]-s.y-VehicleTypes.CG[s.model],z=p[2]-s.z,c=Math.cos(s.yaw),t=Math.sin(s.yaw);
        double xx=x*c-z*t,zz=x*t+z*c;c=Math.cos(s.pitch);t=Math.sin(s.pitch);double yy=y*c+zz*t;z=-y*t+zz*c;c=Math.cos(s.roll);t=Math.sin(s.roll);
        return new double[]{xx*c+yy*t,-xx*t+yy*c+VehicleTypes.CG[s.model],z};
    }
    private static double[] boxLocal(double[] p,double[] b){double y=p[1]-b[1],z=p[2]-b[2],c=Math.cos(b[6]),s=Math.sin(b[6]);return new double[]{p[0]-b[0],y*c+z*s,-y*s+z*c};}
    private static boolean touches(double[] start,double[] end,double[] box,double radius){
        double[] a=boxLocal(start,box),b=boxLocal(end,box);double lo=0,hi=1;
        for(int i=0;i<3;i++){double d=b[i]-a[i],extent=box[i+3]+radius;if(Math.abs(d)<1e-10){if(Math.abs(a[i])>extent)return false;}else{double x=(-extent-a[i])/d,y=(extent-a[i])/d;lo=Math.max(lo,Math.min(x,y));hi=Math.min(hi,Math.max(x,y));if(hi<lo)return false;}}
        // Squared distance to an AABB is convex along a segment. Rounded corners
        // prevent a person next to a wing mirror being hit by an enclosing box.
        for(int n=0;n<24;n++){double l=(lo*2+hi)/3,r=(lo+hi*2)/3;if(distance(a,b,box,l)<distance(a,b,box,r))hi=r;else lo=l;}
        return distance(a,b,box,(lo+hi)*.5)<=radius*radius;
    }
    private static double distance(double[] a,double[] b,double[] box,double t){double sum=0;for(int i=0;i<3;i++){double d=Math.max(0,Math.abs(a[i]+(b[i]-a[i])*t)-box[i+3]);sum+=d*d;}return sum;}
}
