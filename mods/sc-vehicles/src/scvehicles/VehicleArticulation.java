package scvehicles;

import java.util.*;
import scvehicles.protocol.VehicleWire;

/** Per-replica presentation state. Never feeds forces back into the chassis. */
final class VehicleArticulation {
    static final int DOORS_OPEN=VehicleWire.DOORS_OPEN;
    double wheel,steering,leftDoor,rightDoor,leftRearDoor,rightRearDoor;
    private VehicleFleet.Type type=VehicleFleet.get(0);
    private double travelled;
    private double leftTrack,rightTrack;
    private double x,y,z,previousYaw,previousPitch;
    private int driver;
    private boolean initialized;
    private double entryDoorUntil;
    void update(VehicleWire state,double dt,double time) {
        type=VehicleFleet.get(state.model);
        if(initialized) {
            double dx=state.x-x,dy=state.y-y,dz=state.z-z;
            if(dx*dx+dy*dy+dz*dz<9) {
                double delta=state.yaw-previousYaw;
                double yaw=previousYaw+Math.atan2(Math.sin(delta),Math.cos(delta))*.5,pitch=(state.pitch+previousPitch)*.5;
                double distance=dx*Math.sin(yaw)*Math.cos(pitch)-dy*Math.sin(pitch)+dz*Math.cos(yaw)*Math.cos(pitch);travelled+=distance;wheel=Math.IEEEremainder(wheel+distance/VehicleGeometry.RADIUS,Math.PI*2);
                double turn=Math.atan2(Math.sin(delta),Math.cos(delta))*type.track()*.5;
                leftTrack+=distance-turn;rightTrack+=distance+turn;
            }
        }
        x=state.x;y=state.y;z=state.z;previousYaw=state.yaw;previousPitch=state.pitch;driver=state.driver;initialized=true;
        steering=type.steering(state.steer,state.vx,state.vz);
        boolean open=(state.flags&DOORS_OPEN)!=0;
        leftDoor=approach(leftDoor,open?1:0,dt*3.4);
        rightDoor=approach(rightDoor,open?1:0,dt*3.4);
        leftRearDoor=approach(leftRearDoor,open?1:0,dt*3.4);rightRearDoor=approach(rightRearDoor,open?1:0,dt*3.4);
    }
    void action(int seat,double open){switch(scvehicles.protocol.VehicleSeats.PANEL[type.id][seat]){case 0->leftDoor=open;case 1->rightDoor=open;case 2->leftRearDoor=open;case 3->rightRearDoor=open;}}
    private static double approach(double a,double b,double step){return a+Math.max(-step,Math.min(step,b-a));}
    double[] rotation(VehicleGeometry.Part part){return rotation(part,VehicleGeometry.RADIUS);}
    double trackDistance(int side){return side>0?leftTrack:rightTrack;}
    double[] roller(int side,double radius){return axis(1,0,0,Math.IEEEremainder(trackDistance(side)/radius,Math.PI*2));}
    double[] rotation(VehicleGeometry.Part part,double radius) {
        double wheel=type.id==0?this.wheel:Math.IEEEremainder(travelled/radius,Math.PI*2);
        return switch(part) {
            case FRONT_LEFT,FRONT_RIGHT -> {
                // Inner wheel has the tighter radius. The steering envelope is
                // shared with the actual tyre solver, including its speed limit.
                double angle=Math.abs(steering)<1e-8?0:Math.atan(type.wheelbase()/(type.wheelbase()/Math.tan(steering)-(part==VehicleGeometry.Part.FRONT_LEFT?type.track()*.5:-type.track()*.5)));
                yield multiply(axis(0,1,0,angle),axis(1,0,0,wheel));
            }
            case REAR_LEFT,REAR_RIGHT -> axis(1,0,0,wheel);
            case DOOR_LEFT -> axis(0,1,0,-Math.toRadians(68)*smooth(leftDoor));
            case DOOR_RIGHT -> axis(0,1,0,Math.toRadians(68)*smooth(rightDoor));
            case DOOR_REAR_LEFT -> axis(0,1,0,-Math.toRadians(68)*smooth(leftRearDoor));
            case DOOR_REAR_RIGHT -> axis(0,1,0,Math.toRadians(68)*smooth(rightRearDoor));
            case STEERING -> axis(0,.4,Math.sqrt(.84),-steering*14);
            default -> new double[]{1,0,0,0};
        };
    }
    private static double smooth(double t){return t*t*(3-2*t);}
    private static double[] axis(double x,double y,double z,double a){double s=Math.sin(a*.5);return new double[]{Math.cos(a*.5),x*s,y*s,z*s};}
    private static double[] multiply(double[] a,double[] b){return new double[]{a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]};}
    Map<String,Object> inspect(){return Map.of("wheel",wheel,"steering",steering,"leftDoor",leftDoor,"rightDoor",rightDoor,"leftRearDoor",leftRearDoor,"rightRearDoor",rightRearDoor,"leftTrack",leftTrack,"rightTrack",rightTrack);}
}
