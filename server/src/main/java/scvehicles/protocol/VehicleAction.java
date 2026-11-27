package scvehicles.protocol;

/** One authenticated occupant action; piggybacks on the bounded native SCV1 envelope. */
public final class VehicleAction {
    public static final int ENTER=1,EXIT=2,LEAN_OUT=3,LEAN_IN=4,ROOF_UP=5,ROOF_DOWN=6;
    public static final double ENTRY_CONTROL_DELAY=1.0;
    public static final int LOCAL_EXIT=0x20000000;
    public static double[] exitFeet(VehicleWire car,VehicleWire request){double[] p={request.x,request.y,request.z};return request.sequence==LOCAL_EXIT?world(car,p):p;}
    public static double[] fallbackExit(VehicleWire car,int seat){
        double[] point=VehicleSeats.seat(car.model,seat);
        double side=Math.copySign(VehicleTypes.WIDTH[car.model]*.5+1.05,point[0]);
        double cos=Math.cos(car.yaw),sin=Math.sin(car.yaw);
        return new double[]{car.x+side*cos+point[2]*sin,car.y+.25,car.z-side*sin+point[2]*cos};
    }
    public static boolean locksDriving(int action,double elapsed){return action==ENTER?elapsed<ENTRY_CONTROL_DELAY:action==EXIT||action==ROOF_UP||action==ROOF_DOWN;}
    public static double[] exitVelocity(VehicleWire car){return exitVelocity(car,0);}
    public static double[] exitVelocity(VehicleWire car,int seat){if(car.speed<1.5)return new double[3];double side=VehicleSeats.SEAT[car.model][seat][0]>=0?1:-1;return new double[]{car.vx+side*1.4*Math.cos(car.yaw),Math.max(.7,car.vy),car.vz-side*1.4*Math.sin(car.yaw)};}
    public static String clip(int model,int action){return switch(action){case ENTER->VehicleActionProfiles.FAMILY[model]+"_enter";case EXIT->VehicleActionProfiles.FAMILY[model]+"_exit";case LEAN_OUT->"lean_out";case LEAN_IN->"lean_in";case ROOF_UP,ROOF_DOWN->"roof_up";default->"seated";};}
    public static double duration(int model,int action){boolean truck=VehicleActionProfiles.FAMILY[model].equals("truck");return switch(action){case ENTER->truck?80./30:78./30;case EXIT->truck?1.25:1.0;case LEAN_OUT->44./75;case LEAN_IN->58./75;default->0;};}
    public static int posture(int action){return action==LEAN_OUT?1:action==ROOF_UP?2:0;}
    public static double[] target(int model,int action){return (action==LEAN_OUT?VehicleActionProfiles.WINDOW_POINT[model]:action==ROOF_UP?VehicleActionProfiles.ROOF_POINT[model]:VehicleActionProfiles.SEAT[model]).clone();}
    public static double[] target(int model,int action,int seat){return VehicleSeats.map(model,seat,target(model,action));}
    public static double[] world(VehicleWire car,double[] p){
        double c=Math.cos(car.roll),s=Math.sin(car.roll),x=p[0]*c-(p[1]-VehicleTypes.CG[car.model])*s,y=p[0]*s+(p[1]-VehicleTypes.CG[car.model])*c,z=p[2];c=Math.cos(car.pitch);s=Math.sin(car.pitch);double yy=y*c-z*s;z=y*s+z*c;c=Math.cos(car.yaw);s=Math.sin(car.yaw);return new double[]{car.x+x*c+z*s,car.y+VehicleTypes.CG[car.model]+yy,car.z-x*s+z*c};
    }
    public static double[] local(VehicleWire car,double[] p){
        double x=p[0]-car.x,y=p[1]-car.y-VehicleTypes.CG[car.model],z=p[2]-car.z,c=Math.cos(car.yaw),s=Math.sin(car.yaw),xx=x*c-z*s;z=x*s+z*c;c=Math.cos(car.pitch);s=Math.sin(car.pitch);double yy=y*c+z*s;z=-y*s+z*c;c=Math.cos(car.roll);s=Math.sin(car.roll);return new double[]{xx*c+yy*s,-xx*s+yy*c+VehicleTypes.CG[car.model],z};
    }
    public static double[] anchor(VehicleWire action,double progress){
        double t=Math.max(0,Math.min(1,progress));double[] a={action.x,action.y,action.z},b={action.vx,action.vy,action.vz};
        if(action.flags==ENTER)return VehicleEntryMotion.anchor(action,t);
        else if(action.flags==EXIT){
            double side=action.yaw>=0?1:-1;double[] gate={side*(VehicleTypes.WIDTH[action.model]*.5+.35),b[1],b[2]};
            if(t<.62){b=gate;t/=.62;}else{a=gate;t=(t-.62)/.38;}
        }
        if(action.flags==ROOF_UP||action.flags==ROOF_DOWN){
            boolean reverse=action.flags==ROOF_DOWN;if(reverse){double[] temp=a;a=b;b=temp;t=1-t;}
            double side=action.yaw>=0?1:-1,edge=side*(VehicleTypes.WIDTH[action.model]*.5+.30);
            if(t<.35){b=new double[]{edge,a[1]+.23,a[2]};t/=.35;}
            else if(t<.75){a=new double[]{edge,a[1]+.23,a[2]};b=new double[]{edge,b[1],b[2]};t=(t-.35)/.40;}
            else {a=new double[]{edge,b[1],b[2]};t=(t-.75)/.25;}
        }
        t=t*t*(3-2*t);return new double[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t};
    }
    private VehicleAction(){}
}
