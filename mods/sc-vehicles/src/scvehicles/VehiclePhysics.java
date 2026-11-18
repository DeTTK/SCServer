package scvehicles;
import scvehicles.protocol.VehicleWire;
import java.util.Arrays;

/** Pilot-owned rigid body. UFO wheel/terrain/pose conventions, SC contacts.
 * The supplied UFO client has no server wheel-force solver. These forces are
 * the SC adapter, not a claim of identical UFO handling. */
public final class VehiclePhysics {
    public double x,y,z,yaw,pitch,roll,vx,vy,vz,steer,fuel=1,speed;
    public boolean engine,grounded;
    public double throttle,turn;public boolean handbrake;
    public double wx,wy,wz;
    public double drift,slipAngle;
    public int model;
    VehicleFleet.Type type=VehicleFleet.get(0);
    double[][] points=POINTS;
    int wheelCount=4;
    double cg(){return type.cg;}
    void configure(int model){this.model=model;type=VehicleFleet.get(model);points=type.points();wheelCount=type.wheels.length;heights=new double[points.length];hits=new boolean[points.length];ramps=new boolean[points.length];}
    double stepSeconds=1./240;
    private final double[] q={1,0,0,0};
    private double[] heights=new double[14];
    private boolean[] hits=new boolean[14];
    private boolean[] ramps=new boolean[14];
    private double nx,ny=1,nz;
    private boolean initialized,terrainReady;
    public int wheelContacts,rampContacts;
    // Tyres, underside and roof. Local height zero is the rest tyre contact plane.
    static final double[][] POINTS=VehicleFleet.nivaPoints(new double[][]{{-.65,0,1.12},{.65,0,1.12},{-.65,0,-1.12},{.65,0,-1.12},
        {-.7,.44,1.4},{.7,.44,1.4},{-.7,.44,-1.4},{.7,.44,-1.4},
        {-.64,1.49,1.05},{.64,1.49,1.05},{-.64,1.49,-1.05},{.64,1.49,-1.05},
        {-.58,.44,0},{.58,.44,0}});
    double[] rotation(){if(!initialized){double[] a=VehiclePose.quaternion(yaw,pitch,roll);System.arraycopy(a,0,q,0,4);initialized=true;}return q;}
    void orient(double pitch,double roll){this.pitch=pitch;this.roll=roll;initialized=false;rotation();}
    void terrain(double[] ground,boolean[] valid,double normalX,double normalY,double normalZ){
        terrain(ground,valid,normalX,normalY,normalZ,new boolean[points.length]);
    }
    void terrain(double[] ground,boolean[] valid,double normalX,double normalY,double normalZ,boolean[] forgivingRamps){
        System.arraycopy(ground,0,heights,0,points.length);System.arraycopy(valid,0,hits,0,points.length);nx=normalX;ny=normalY;nz=normalZ;terrainReady=true;
        System.arraycopy(forgivingRamps,0,ramps,0,points.length);
    }
    boolean rampSupport(){for(int i=0;i<wheelCount;i++)if(hits[i]&&ramps[i])return true;return false;}
    double supportHeight(int wheel){return hits[wheel]?heights[wheel]:Double.NaN;}
    public void step(double dt){
        if(!Double.isFinite(dt)||dt<=0||dt>.05)throw new IllegalArgumentException("vehicle_step");
        stepSeconds=dt;
        rotation();steer=approach(steer,clamp(turn,-1,1),dt*7.5);
        double[] force={0,-9.80665,0},torque={0,0,0};wheelContacts=rampContacts=0;
        double[] up=VehiclePose.rotate(q,0,1,0);
        double forwardSpeed=vx*Math.sin(yaw)+vz*Math.cos(yaw),sideSpeed=vx*Math.cos(yaw)-vz*Math.sin(yaw);
        slipAngle=Math.atan2(sideSpeed,Math.max(2,Math.abs(forwardSpeed)));
        boolean startDrift=handbrake&&Math.abs(steer)>.15&&speed>4;
        boolean holdDrift=drift>.25&&throttle>.1&&Math.abs(turn)>.12&&speed>5&&Math.abs(slipAngle)>.04;
        drift=approach(drift,up[1]>.6&&type.arcade()>0&&(startDrift||holdDrift)?1:0,dt*(startDrift?4:1.8));
        // Keyboard full lock must remain usable at road speed. A bicycle-model
        // steering envelope allows 6–10 m/s² by vehicle, with 40 degrees at
        // parking speed. It sets tyre direction; body yaw follows ground forces.
        double steeringAngle=type.steering(steer,vx,vz);
        if(terrainReady){
            for(int i=0;i<points.length;i++){
                if(!hits[i]||i<wheelCount&&up[1]<.15)continue;
                double[] a=points[i],r=VehiclePose.rotate(q,a[0],a[1]-cg(),a[2]);
                r[1]-=type.pointRadius(i);
                double error=heights[i]-(y+cg()+r[1]),pointY=vy+wz*r[0]-wx*r[2];
                boolean tyre=i<wheelCount;
                if(error<(tyre?-.25:-.015)||error>(tyre?.72:1.3))continue;
                boolean ramp=ramps[i]&&up[1]>.45;
                double load=tyre?clamp(9.80665/wheelCount+(error*(ramp?50:85)-pointY*(ramp?9:7.2))*4/wheelCount,0,ramp?24:65):clamp(error*(ramp?100:230)-pointY*14,0,ramp?30:110);
                if(load<=0)continue;
                double[] n=tyre?new double[]{nx,ny,nz}:new double[]{0,1,0};
                // Small native wedges are common road transitions. Ease their
                // downhill reaction without flattening their support heights.
                if(ramp){n[0]*=.4;n[2]*=.4;normalize(n);}
                double scale=load/Math.max(.45,n[1]);double[] contact={n[0]*scale,load,n[2]*scale};
                add(force,contact);
                if(ramp)rampContacts++;
                // Apply support at the actual wheel/hull contact. Scaling only
                // its moment makes the body rise before the axle can pitch.
                crossAdd(torque,r,contact);
                if(tyre){
                    wheelContacts++;
                    double angle=type.tracked()?0:i<2?steeringAngle:0;
                    double[] f=VehiclePose.rotate(q,Math.sin(angle),0,Math.cos(angle));
                    double projection=dot(f,n);for(int k=0;k<3;k++)f[k]-=projection*n[k];normalize(f);
                    double[] side={n[1]*f[2]-n[2]*f[1],n[2]*f[0]-n[0]*f[2],n[0]*f[1]-n[1]*f[0]};
                    double[] velocity={vx+wy*r[2]-wz*r[1],vy+wz*r[0]-wx*r[2],vz+wx*r[1]-wy*r[0]};
                    double v=dot(velocity,f),slip=dot(velocity,side),drive=engine&&fuel>0?clamp(throttle,-1,1):0;
                    double acceleration=drive*v<-.1?drive*8.5:drive*(drive<0?2.6:type.acceleration)*Math.max(0,1-Math.abs(v)/(drive<0?6:type.maxSpeed));
                    if(drive>0&&v>=-.1)acceleration+=drive*drift*type.arcade()*2.5*Math.max(0,1-Math.abs(v)/type.maxSpeed);
                    // Low-gear reserve for common shaped road ramps. It fades
                    // out at road speed and never boosts braking or flat travel.
                    if(rampSupport()&&drive*v>=-.1)acceleration*=1+1.2*clamp(drive*f[1],0,.5)*clamp((8-Math.abs(v))/4,0,1);
                    if(type.tracked()&&engine)acceleration-=steer*Math.signum(a[0])*3;
                    // Doubled forward speed profiles need one quarter of the
                    // quadratic drag: resistance at 2*v then matches the old v.
                    // Keep launch torque, reverse drag and brake forces intact.
                    double drag=v>=0?.009/4:.009;
                    double along=(acceleration-clamp(v*1.8,-.18,.18)-v*Math.abs(v)*drag)/wheelCount;
                    if(handbrake&&(type.tracked()||i>=2)){
                        double count=type.tracked()?wheelCount:wheelCount-2;
                        double brake=12-(engine&&throttle>0?drift*type.arcade()*5:0);
                        along-=clamp(v/dt/count,-brake/count,brake/count);
                    }
                    // Rear tyres retain slightly more lateral grip: ordinary
                    // steering settles into mild understeer instead of a spin.
                    double loose=drift*type.arcade();
                    double lateral=-slip*(type.tracked()?.85:i<2?5.4:handbrake?.45:6*(1-loose)+.75*loose);
                    double limit=load*(i>=2?1.1-.36*loose:1.15),mag=Math.hypot(along,lateral);if(mag>limit){along*=limit/mag;lateral*=limit/mag;}
                    double[] traction={f[0]*along,f[1]*along,f[2]*along},cornering={side[0]*lateral,side[1]*lateral,side[2]*lateral};
                    add(force,traction);add(force,cornering);crossAdd(torque,r,traction);
                    // Arcade roll centre for tyre side forces, preserving real
                    // gravity, suspension, airborne rotation and impact moments.
                    double[] rollCentre=r.clone();if(up[1]>.6)rollCentre[1]*=1-.65*type.arcade();
                    crossAdd(torque,rollCentre,cornering);
                }else{
                    double sx=vx+wy*r[2]-wz*r[1],sz=vz+wx*r[1]-wy*r[0],sliding=Math.hypot(sx,sz);
                    // Upright underbody contacts should slide over a short
                    // shaped crest instead of acting like the roof's brake.
                    if(sliding>1e-6){double friction=Math.min(load*(ramp?.12:.65),sliding/(dt*4));double[] grip={-sx/sliding*friction,0,-sz/sliding*friction};add(force,grip);crossAdd(torque,r,grip);}
                }
            }
            grounded=wheelContacts>=2&&up[1]>.25;
            if(wheelContacts>=3&&up[1]>.6&&type.arcade()>0){
                double desired=forwardSpeed*Math.tan(steeringAngle)/type.wheelbase();
                desired+=Math.signum(slipAngle)*Math.max(0,Math.abs(slipAngle)-.55)*2.8;
                double correction=(desired-wy)*type.inertia()[1]*3*type.arcade();
                torque[1]+=clamp(correction,-12,12);
            }
        }
        vx+=force[0]*dt;vy+=force[1]*dt;vz+=force[2]*dt;
        double[] local=VehiclePose.rotate(new double[]{q[0],-q[1],-q[2],-q[3]},torque[0],torque[1],torque[2]);
        double[] inertia=type.inertia();local[0]/=inertia[0];local[1]/=inertia[1];local[2]/=inertia[2];
        double[] angular=VehiclePose.rotate(q,local[0],local[1],local[2]);
        double damp=Math.exp(-(wheelContacts>0?.28:.06)*dt);
        wx=clamp((wx+angular[0]*dt)*damp,-7,7);wy=clamp((wy+angular[1]*dt)*damp,-7,7);wz=clamp((wz+angular[2]*dt)*damp,-7,7);
        double qw=q[0],qx=q[1],qy=q[2],qz=q[3];
        q[0]+=(-wx*qx-wy*qy-wz*qz)*dt*.5;q[1]+=(wx*qw+wy*qz-wz*qy)*dt*.5;
        q[2]+=(wy*qw+wz*qx-wx*qz)*dt*.5;q[3]+=(wz*qw+wx*qy-wy*qx)*dt*.5;
        double length=Math.sqrt(dot(q,q));for(int i=0;i<4;i++)q[i]/=length;
        double[] angles=VehiclePose.angles(q);yaw=angles[0];pitch=angles[1];roll=angles[2];
        x+=vx*dt;y+=vy*dt;z+=vz*dt;speed=Math.hypot(vx,vz);
        if(engine)fuel=Math.max(0,fuel-dt*(.000015+Math.abs(throttle)*.00009));if(fuel<=0)engine=false;
    }
    public void resolve(double bx,double by,double bz,double ax,double ay,double az,double dt){
        double dx=x-bx,dy=y-by,dz=z-bz;
        if(!terrainReady)grounded=grounded&&Math.abs(dy)<1e-8&&Math.abs(ay-by)<.01||dy<0&&ay-by>dy+1e-6;
        // Native convex casts slide along oblique faces. Retain the accepted
        // fraction of velocity instead of treating every partial slide as a wall.
        if(Math.abs(ax-bx-dx)>Math.max(1e-4,Math.abs(dx)*.002))vx*=Math.abs(dx)>1e-10?clamp((ax-bx)/dx,0,1):0;
        if(Math.abs(az-bz-dz)>Math.max(1e-4,Math.abs(dz)*.002))vz*=Math.abs(dz)>1e-10?clamp((az-bz)/dz,0,1):0;
        double verticalCorrection=ay-by-dy;
        if(verticalCorrection>.0002&&vy<0||verticalCorrection<-.0002&&vy>0)vy=0;
        x=ax;y=ay;z=az;speed=Math.hypot(vx,vz);
    }
    /** Inelastic rigid-body contact against terrain, including the moment at the bumper. */
    double contactImpulse(double[] r,double[] n){
        return contactImpulse(r,n,0);
    }
    record Contact(double[] offset,double[] normal,double allowedClosing){
        Contact(double[] offset,double[] normal){this(offset,normal,0);}
    }
    double contactImpulses(java.util.List<Contact> contacts){
        return contactImpulses(contacts,true);
    }
    double contactImpulses(java.util.List<Contact> contacts,boolean wheelSupports){
        if(contacts.isEmpty())return 0;
        contacts=new java.util.ArrayList<>(contacts);
        // A bumper reaction also loads the other wheels. Solve these contacts
        // together, so a rear tyre cannot be driven into the road while the
        // front contact spins the body upwards.
        if(wheelSupports&&terrainReady)for(int i=0;i<wheelCount;i++)if(hits[i]){
            double[] a=points[i],r=VehiclePose.rotate(rotation(),a[0],a[1]-cg(),a[2]);
            double gap=y+cg()+r[1]-heights[i];
            if(gap<=.04&&gap>=-.4)contacts.add(new Contact(r,new double[]{nx,ny,nz}));
        }
        double[] accumulated=new double[contacts.size()];
        for(int pass=0;pass<20;pass++)for(int i=0;i<contacts.size();i++){Contact c=contacts.get(i);accumulated[i]=contactImpulse(c.offset,c.normal,accumulated[i],c.allowedClosing);}
        double max=0;for(double impulse:accumulated)max=Math.max(max,impulse);return max;
    }
    private double contactImpulse(double[] r,double[] n,double accumulated){
        return contactImpulse(r,n,accumulated,0);
    }
    private double contactImpulse(double[] r,double[] n,double accumulated,double allowedClosing){
        double normalSpeed=(vx+wy*r[2]-wz*r[1])*n[0]+(vy+wz*r[0]-wx*r[2])*n[1]+(vz+wx*r[1]-wy*r[0])*n[2];
        if(normalSpeed+allowedClosing>=-1e-7&&accumulated==0)return 0;
        double[] axis={r[1]*n[2]-r[2]*n[1],r[2]*n[0]-r[0]*n[2],r[0]*n[1]-r[1]*n[0]},q=rotation();
        double[] local=VehiclePose.rotate(new double[]{q[0],-q[1],-q[2],-q[3]},axis[0],axis[1],axis[2]);
        double[] inertia=type.inertia();local[0]/=inertia[0];local[1]/=inertia[1];local[2]/=inertia[2];
        double[] inverse=VehiclePose.rotate(q,local[0],local[1],local[2]);
        double denominator=1+inverse[0]*axis[0]+inverse[1]*axis[1]+inverse[2]*axis[2],next=Math.max(0,accumulated-(normalSpeed+allowedClosing)/denominator),impulse=next-accumulated;
        vx+=n[0]*impulse;vy+=n[1]*impulse;vz+=n[2]*impulse;
        wx+=inverse[0]*impulse;wy+=inverse[1]*impulse;wz+=inverse[2]*impulse;speed=Math.hypot(vx,vz);
        return next;
    }
    public VehicleWire state(){VehicleWire s=new VehicleWire();s.model=model;s.x=x;s.y=y;s.z=z;s.yaw=(float)yaw;s.pitch=(float)pitch;s.roll=(float)roll;s.vx=(float)vx;s.vy=(float)vy;s.vz=(float)vz;s.speed=(float)speed;s.steer=(float)steer;s.fuel=(float)fuel;s.flags=(engine?1:0)|(grounded?2:0);return s;}
    public void set(VehicleWire s){configure(s.model);x=s.x;y=s.y;z=s.z;yaw=s.yaw;pitch=s.pitch;roll=s.roll;vx=s.vx;vy=s.vy;vz=s.vz;speed=s.speed;steer=s.steer;fuel=s.fuel;engine=(s.flags&1)!=0;grounded=(s.flags&2)!=0;wx=wy=wz=drift=slipAngle=0;initialized=terrainReady=false;Arrays.fill(hits,false);}
    static double steering(boolean left,boolean right){return (left?1:0)-(right?1:0);}
    static double steeringAngle(double steer,double vx,double vz){return VehicleFleet.get(0).steering(steer,vx,vz);}
    static double dot(double[] a,double[] b){double v=0;for(int i=0;i<a.length;i++)v+=a[i]*b[i];return v;}
    static void add(double[] a,double[] b){for(int i=0;i<3;i++)a[i]+=b[i];}
    static void crossAdd(double[] t,double[] r,double[] f){t[0]+=r[1]*f[2]-r[2]*f[1];t[1]+=r[2]*f[0]-r[0]*f[2];t[2]+=r[0]*f[1]-r[1]*f[0];}
    static void normalize(double[] a){double d=Math.sqrt(dot(a,a));if(d>1e-8)for(int i=0;i<3;i++)a[i]/=d;}
    static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
    static double approach(double a,double b,double d){return a<b?Math.min(a+d,b):Math.max(a-d,b);}
}
