package scvehicles;
import java.util.*;
import static scvehicles.VehiclePhysics.clamp;

/** Responsive mouse orbit on a damped chase rig. Camera-only presentation. */
final class VehicleCamera {
    double yaw,pitch=13;
    private double previousCarYaw,turnLag,strength,viewYaw,viewPitch=13,bank,fov=85,distanceExtra;
    private boolean entering=true,motionReady;
    private final double[] anchor=new double[3],anchorVelocity=new double[3],previousAnchor=new double[3];
    private double previousVx,previousVy,previousVz,forwardAcceleration,sideAcceleration,shock,time,dt=1./60;
    private double boom=Double.NaN;
    private long frame,resolvedFrame=-1,impacts;
    private double[] resolved=new double[3];
    void begin(){entering=true;motionReady=false;turnLag=shock=bank=distanceExtra=0;fov=85;boom=Double.NaN;resolvedFrame=-1;}
    void sync(double carYaw,float nativeYaw,float nativePitch){
        if(entering){yaw=carYaw;pitch=13;entering=false;}
        else{
            double turn=wrap(carYaw-previousCarYaw);turnLag=clamp(turnLag+turn,-.38,.38);
            if(Float.isFinite(nativeYaw))yaw=-Math.toRadians(nativeYaw)+turn;
            if(Float.isFinite(nativePitch))pitch=clamp(nativePitch,-70,80);
        }
        yaw=wrap(yaw);previousCarYaw=carYaw;
    }
    float nativeYaw(){return (float)-Math.toDegrees(yaw);}
    void impact(double velocityLoss){if(velocityLoss>1.2){shock=Math.min(.42,shock+(velocityLoss-1.2)*.025);impacts++;}}
    void update(VehiclePhysics p,double seconds,double amount){
        dt=clamp(seconds,.001,.1);strength=clamp(amount,0,1);time+=dt;frame++;
        double[] target={p.x,p.y+Math.max(1.5,p.type.height*.6),p.z};
        double speed=Math.hypot(p.vx,p.vz),sn=Math.sin(p.yaw),cs=Math.cos(p.yaw);
        boolean reset=!motionReady||distance(target,previousAnchor)>30;
        if(reset){System.arraycopy(target,0,anchor,0,3);System.arraycopy(target,0,previousAnchor,0,3);anchorVelocity[0]=p.vx;anchorVelocity[1]=p.vy;anchorVelocity[2]=p.vz;previousVx=p.vx;previousVy=p.vy;previousVz=p.vz;forwardAcceleration=sideAcceleration=0;turnLag=0;shock=0;boom=Double.NaN;motionReady=true;}
        double ax=(p.vx-previousVx)/dt,az=(p.vz-previousVz)/dt;
        forwardAcceleration+=(clamp(ax*sn+az*cs,-12,9)-forwardAcceleration)*(1-Math.exp(-dt/.15));
        sideAcceleration+=(clamp(ax*cs-az*sn,-12,12)-sideAcceleration)*(1-Math.exp(-dt/.18));
        // Critical spring relative to a linearly moving target: follows steady
        // travel without permanent lag, while acceleration/impacts create inertia.
        double omega=p.type.tracked()?6:7.5,decay=Math.exp(-omega*dt);
        for(int k=0;k<3;k++){
            double targetVelocity=(target[k]-previousAnchor[k])/dt;
            double error=anchor[k]-previousAnchor[k],relative=anchorVelocity[k]-targetVelocity;
            double impulse=relative+omega*error;
            double e=(error+impulse*dt)*decay,velocity=(relative-omega*impulse*dt)*decay;
            anchor[k]=target[k]+clamp(e,-1.1,1.1)*strength;
            anchorVelocity[k]=targetVelocity+velocity;previousAnchor[k]=target[k];
        }
        turnLag*=Math.exp(-dt*(5+speed*.04));
        double relativeLook=Math.abs(wrap(yaw-p.yaw)),slip=speed>3?wrap(Math.atan2(p.vx,p.vz)-p.yaw):0;
        double travelLook=clamp(slip*.18,-.12,.12)*clamp(1-relativeLook/.7,0,1);
        viewYaw=wrap(yaw+(-turnLag+travelLook)*strength);
        double pitchKick=clamp(-forwardAcceleration*.30+Math.toDegrees(p.pitch)*.09,-3.5,3.5)*strength;
        double pitchTarget=clamp(pitch+pitchKick,-70,80);viewPitch+=(pitchTarget-viewPitch)*(1-Math.exp(-dt/.09));
        if(strength==0)viewPitch=pitch;
        bank+=(clamp(-sideAcceleration*.20+Math.toDegrees(p.roll)*.06,-2.7,2.7)*strength-bank)*(1-Math.exp(-dt/.18));
        double extra=(clamp(speed/32,0,1)*.95+clamp(forwardAcceleration*.04,-.25,.28))*strength;
        distanceExtra+=(extra-distanceExtra)*(1-Math.exp(-dt/.22));
        double fast=clamp(speed/34,0,1),fovTarget=85+12*fast*fast*(3-2*fast)*strength;
        fov+=(fovTarget-fov)*(1-Math.exp(-dt/.28));
        shock*=Math.exp(-dt*7);previousVx=p.vx;previousVy=p.vy;previousVz=p.vz;
        if(strength==0){turnLag=shock=bank=distanceExtra=0;fov=85;viewYaw=yaw;viewPitch=pitch;}
    }
    // ModLoader advances fixed ticks after onFrame. Use the same captured pose
    // as visualStates/rig update, even if physics has advanced before rendering.
    double[] origin(VehiclePhysics p){return motionReady?previousAnchor.clone():new double[]{p.x,p.y+Math.max(1.5,p.type.height*.6),p.z};}
    double[] offset(double distance){
        double a=motionReady?viewYaw:yaw,tilt=Math.toRadians(motionReady?viewPitch:pitch),radius=distance+(motionReady?distanceExtra:0),horizontal=radius*Math.cos(tilt);
        double[] result={-Math.sin(a)*horizontal,Math.sin(tilt)*radius,-Math.cos(a)*horizontal};
        if(motionReady){for(int i=0;i<3;i++)result[i]+=anchor[i]-previousAnchor[i];
            // A short event envelope, never a permanent idle/flat-road shake.
            double amount=shock*strength;result[0]+=amount*Math.sin(time*58);result[1]+=amount*.65*Math.sin(time*71+.4);result[2]+=amount*.35*Math.cos(time*63);}
        return result;
    }
    double[] resolve(double[] origin,double[] safe){
        if(resolvedFrame==frame)return resolved.clone();resolvedFrame=frame;
        double length=distance(origin,safe);
        // Collision retracts immediately; extension after an obstacle is gradual.
        if(!Double.isFinite(boom)||length<boom)boom=length;else boom+=(length-boom)*(1-Math.exp(-dt/.18));
        double ratio=length<1e-6?0:Math.min(1,boom/length);for(int i=0;i<3;i++)resolved[i]=origin[i]+(safe[i]-origin[i])*ratio;
        return resolved.clone();
    }
    double viewYaw(){return motionReady?viewYaw:yaw;}
    double viewPitch(){return motionReady?clamp(viewPitch+shock*strength*Math.sin(time*65)*1.4,-70,80):pitch;}
    double roll(){return Math.toRadians(bank+shock*strength*Math.cos(time*59)*2);}
    float fov(){return (float)fov;}
    private static double distance(double[] a,double[] b){return Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));}
    private static double wrap(double angle){return Math.atan2(Math.sin(angle),Math.cos(angle));}
    Map<String,Object> inspect(){return Map.of("viewYaw",viewYaw(),"viewPitch",viewPitch(),"roll",roll(),"fov",fov,"distanceExtra",distanceExtra,"turnLag",turnLag,"shock",shock,"anchor",anchor.clone(),"strength",strength,"impacts",impacts);}
}
