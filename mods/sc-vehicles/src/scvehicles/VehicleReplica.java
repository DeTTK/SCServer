package scvehicles;

import java.util.ArrayDeque;
import java.util.HashSet;
import scvehicles.protocol.VehicleWire;

/** Buffered presentation only. The driver's collision/physics state is never corrected. */
final class VehicleReplica {
    // Remains a positive, increasing SCV1 sequence for existing relays/clients.
    // Low bits count 50 ms send slots, including slots skipped by a long frame.
    static final int CLOCK = 0x40000000;
    static final double PERIOD = .05, DELAY = .10, EXTRAPOLATE = .10;
    private record Sample(VehicleWire state,double time,double offset) {}
    private final ArrayDeque<Sample> samples=new ArrayDeque<>();
    private final HashSet<Integer> retiredDrivers=new HashSet<>();
    VehicleWire current;
    private double playback,lastRender;
    private boolean rendered;
    long accepted,rejected,extrapolated;

    boolean accept(VehicleWire s){return accept(s,System.nanoTime());}
    boolean accept(VehicleWire s,long now){
        double arrival=now*1e-9;
        // Explicit ENTER snapshots have sequence zero. A delayed movement
        // snapshot must not resurrect an actor after EXIT/ownership handoff.
        if(current!=null&&s.driver!=current.driver&&s.sequence!=0&&
            (retiredDrivers.contains(s.driver)||current.driver<0&&s.driver>=0&&s.sequence<=current.sequence)){
            rejected++;return false;
        }
        boolean reset=current==null||s.driver!=current.driver||s.model!=current.model;
        if(!reset&&s.sequence<=current.sequence){rejected++;return false;}
        double time=(s.sequence&CLOCK)!=0?(s.sequence&~CLOCK)*PERIOD:arrival;
        if(!reset){
            Sample last=samples.getLast();double dt=Math.max(0,time-last.time);
            reset=dt<=0||Math.sqrt(Math.pow(s.x-current.x,2)+Math.pow(s.y-current.y,2)+Math.pow(s.z-current.z,2))>Math.max(8,dt*60+2);
        }
        if(reset){
            if(current!=null&&current.driver>=0&&current.driver!=s.driver)retiredDrivers.add(current.driver);
            if(s.sequence==0)retiredDrivers.remove(s.driver);
            samples.clear();rendered=false;
        }
        current=s.copy();samples.addLast(new Sample(current,time,arrival-time));accepted++;
        while(samples.size()>64)samples.removeFirst();
        return true;
    }
    VehicleWire at(long now){
        double wall=now*1e-9,minOffset=Double.POSITIVE_INFINITY;
        for(Sample sample:samples)minOffset=Math.min(minOffset,sample.offset);
        double target=wall-minOffset-DELAY;
        if(!rendered){playback=target;rendered=true;}
        else {
            double dt=Math.max(0,wall-lastRender);
            // Clock discipline changes playback speed gently; packet arrival
            // never restarts a lerp or jumps the presentation clock backwards.
            double rate=VehiclePhysics.clamp(1+(target-playback-dt)*2,.95,1.05);
            playback+=dt*rate;
        }
        lastRender=wall;
        Sample a=samples.getFirst(),b=a;
        for(Sample sample:samples){b=sample;if(sample.time>=playback)break;a=sample;}
        if(playback<=a.time)return a.state.copy();
        if(a==b){
            VehicleWire out=a.state.copy();double dt=VehiclePhysics.clamp(playback-a.time,0,EXTRAPOLATE);
            // Parked cars must not coast on a stale velocity from a lifecycle packet.
            if(out.driver>=0){out.x+=out.vx*dt;out.y+=out.vy*dt;out.z+=out.vz*dt;}
            if(dt>0)extrapolated++;
            return out;
        }
        return interpolate(a.state,b.state,(playback-a.time)/(b.time-a.time),b.time-a.time);
    }
    static VehicleWire interpolate(VehicleWire a,VehicleWire b,double t,double seconds){
        t=VehiclePhysics.clamp(t,0,1);VehicleWire out=b.copy();
        out.x=curve(a.x,b.x,a.vx,b.vx,t,seconds);out.y=curve(a.y,b.y,a.vy,b.vy,t,seconds);out.z=curve(a.z,b.z,a.vz,b.vz,t,seconds);
        double[] p=VehiclePose.quaternion(a.yaw,a.pitch,a.roll),q=VehiclePose.quaternion(b.yaw,b.pitch,b.roll);
        double sign=VehiclePhysics.dot(p,q)<0?-1:1,length=0;
        for(int i=0;i<4;i++){p[i]+=(q[i]*sign-p[i])*t;length+=p[i]*p[i];}
        length=Math.sqrt(length);for(int i=0;i<4;i++)p[i]/=length;
        double[] angles=VehiclePose.angles(p);out.yaw=(float)angles[0];out.pitch=(float)angles[1];out.roll=(float)angles[2];
        out.vx=(float)(a.vx+(b.vx-a.vx)*t);out.vy=(float)(a.vy+(b.vy-a.vy)*t);out.vz=(float)(a.vz+(b.vz-a.vz)*t);
        out.speed=(float)(a.speed+(b.speed-a.speed)*t);out.steer=(float)(a.steer+(b.steer-a.steer)*t);
        return out;
    }
    private static double curve(double a,double b,double va,double vb,double t,double dt){
        double delta=b-a;if(Math.abs(delta)<1e-9)return a;
        // Monotone Hermite: smooth velocity without overshooting a wall or stop.
        double ma=VehiclePhysics.clamp(va*dt/delta,0,3),mb=VehiclePhysics.clamp(vb*dt/delta,0,3),length=Math.hypot(ma,mb);
        if(length>3){ma*=3/length;mb*=3/length;}
        return a+delta*((-2*t*t*t+3*t*t)+(t*t*t-2*t*t+t)*ma+(t*t*t-t*t)*mb);
    }
}
