package scvehicles;
import static scvehicles.VehicleSeatPose.*;

/** Contact landmarks in the same asset coordinates and door transform as the renderer. */
final class VehicleInteractionPoints {
    static int side(VehicleFleet.Type t){return t.seat[0]>=0?1:-1;}
    static double[] hinge(VehicleFleet.Type t){
        if(t.id==0){float[] h=VehicleGeometry.pivot(side(t)>0?VehicleGeometry.Part.DOOR_LEFT:VehicleGeometry.Part.DOOR_RIGHT);return new double[]{h[0],h[1],h[2]};}
        if(t.door==null)return new double[]{side(t)*t.width*.45,t.seat[1]+.3,t.seat[2]+.5};
        double[] h=side(t)<0&&t.door.rightHinge!=null?t.door.rightHinge:t.door.hinge;
        return new double[]{side(t)<0&&t.door.rightHinge!=null?h[0]:side(t)*h[0],h[1],h[2]};
    }
    static double railHeight(VehicleFleet.Type t){return t.id==0?1.01*VehicleFleet.nivaUnits():Math.min(t.seat[1]+.5,t.door==null?t.seat[1]+.4:t.door.hinge[1]+.08);}
    static double[] rail(VehicleFleet.Type t){double[] h=hinge(t);return new double[]{h[0]+side(t)*.035,railHeight(t),t.seat[2]+.16};}
    static double[] handle(VehicleFleet.Type t,double door){
        double[] h=hinge(t);double rear=t.id==0?-.485*VehicleFleet.nivaUnits():t.door==null?t.seat[2]-.4:java.util.Arrays.stream(t.door.outline).mapToDouble(p->p[0]).min().orElse(t.seat[2]-.4);
        // Several outlines include a long lower skirt; it is not the handle.
        double handleZ=Math.max(rear+.17,Math.min(h[2]-.12,t.seat[2]-.30));
        double[] closed={h[0]+side(t)*.035,railHeight(t)-.12,handleZ};double a=-side(t)*Math.toRadians(68)*smooth(door),c=Math.cos(a),s=Math.sin(a),x=closed[0]-h[0],z=closed[2]-h[2];
        return new double[]{h[0]+x*c+z*s,closed[1],h[2]-x*s+z*c};
    }
    static double[] floor(VehicleFleet.Type t,String leg){boolean low=t.kind.equals("car")||t.kind.equals("van");return new double[]{t.seat[0]+(leg.equals("l")?.11:-.11),t.footwellFloor>0?t.footwellFloor:Math.max(.12,t.seat[1]-.47),t.seat[2]+(low?.62:.48)};}
    static final double BOOT_ANKLE_HEIGHT=.195; // measured native skinned boot sole to ankle
    static double[] push(VehicleFleet.Type t,double door){double[] h=hinge(t),p=handle(t,door);return new double[]{h[0]+(p[0]-h[0])*.55,p[1],h[2]+(p[2]-h[2])*.55};}
    static double smooth(double p){p=Math.max(0,Math.min(1,p));return p*p*(3-2*p);}
    static double contact(double p,double in,double full,double release,double out){return smooth((p-in)/(full-in))*(1-smooth((p-release)/(out-release)));}
    static double[] relative(double[] p,double[] anchor){return sub(p,anchor);}
    private VehicleInteractionPoints(){}
}
