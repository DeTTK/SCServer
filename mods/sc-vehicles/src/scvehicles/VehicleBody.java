package scvehicles;
import static scvehicles.NativeAccess.*;

/** UFO-style per-wheel ground queries, using SC's real voxel and scene collision. */
final class VehicleBody {
    private final Object owner,world;
    private final VehicleRampContacts ramps;
    int lastFlags;
    double lastLift;
    private final java.util.List<VehiclePhysics.Contact> rampHits=new java.util.ArrayList<>();
    private long rampReactions;
    private double maxRampImpulse;
    private java.util.Map<String,Object> lastBlocked=java.util.Map.of();
    VehicleBody(Object owner)throws Exception{this.owner=owner;world=call(owner,"transient","1500514786");ramps=new VehicleRampContacts(owner,world);}
    void beginFrame(){ramps.beginFrame();}
    private boolean ramp(double x,double h,double z)throws Exception{
        int id=(Integer)call(world,"strictfp","int",(int)Math.floor(x),(int)Math.floor(h-.02),(int)Math.floor(z));
        return VehicleRampContacts.ramp(id)||ramps.supports(x,h,z);
    }
    private Object box(double x,double y,double z,double rx,double height,double rz)throws Exception{
        // Independent volume: use the same plain native bounds as ActorMotion,
        // rather than the protected player-owned bounds implementation.
        return call(Class.forName("242639986"),"boolean","242639986",x-rx,y,z-rz,x+rx,y+height,z+rz);
    }
    private int sweep(Object b,double dx,double dy,double dz,float step)throws Exception{
        return ramps.sweep(b,dx,dy,dz,step);
    }
    double ground(double x,double z,double top,double bottom,double radius)throws Exception{
        return ground(x,z,top,bottom,radius,true);
    }
    double rawGround(double x,double z,double top,double bottom,double radius)throws Exception{return ground(x,z,top,bottom,radius,false);}
    boolean obstructed(double[] from,double[] to)throws Exception{
        double dx=to[0]-from[0],dy=to[1]-from[1],dz=to[2]-from[2];
        if(dx*dx+dy*dy+dz*dz<4)return false;
        Object probe=box(from[0],from[1]-.04,from[2],.035,.08,.035);rawSweep(probe,dx,dy,dz);
        return Math.pow((Double)call(probe,"implements","double")-to[0],2)+Math.pow((Double)call(probe,"switch","double")-to[1],2)+Math.pow((Double)call(probe,"goto","double")-to[2],2)>.3*.3;
    }
    private double ground(double x,double z,double top,double bottom,double radius,boolean adjusted)throws Exception{
        Object b=box(x,top,z,radius,.08,radius);int flags=adjusted?sweep(b,0,bottom-top,0,0):rawSweep(b,0,bottom-top,0);
        if((flags&4)==0||(flags&8)!=0)return Double.NaN;
        double h=(Double)call(b,"switch","double")-.04;
        return h>=bottom-.01&&h<=top+.01?h:Double.NaN;
    }
    void settle(VehiclePhysics p)throws Exception{
        ramps.refresh(p);
        double h=ground(p.x,p.z,p.y+2,p.y-3,.12);
        if(Double.isFinite(h)&&!p.grounded&&Math.abs(p.pitch)<.1&&Math.abs(p.roll)<.1){
            double[] support=new double[p.wheelCount];boolean all=true;
            for(int i=0;i<p.wheelCount;i++){double[] a=p.points[i],at=VehiclePose.local(p,a[0],a[1],a[2]);support[i]=ground(at[0],at[2],p.y+2,p.y-3,.06);all&=Double.isFinite(support[i]);}
            p.y=h;p.vy=0;
            if(all){double low=java.util.Arrays.stream(support).min().orElse(h),high=java.util.Arrays.stream(support).max().orElse(h);
                if(high-low<1.2){p.y=java.util.Arrays.stream(support).average().orElse(h);p.orient(-Math.atan2((support[0]+support[1]-support[2]-support[3])*.5,p.type.wheelbase()),Math.atan2((support[1]+support[3]-support[0]-support[2])*.5,p.type.track()));}}
        }
        terrain(p,1./60);
    }
    double[] move(double x,double y,double z,double yaw,double dx,double dy,double dz)throws Exception{
        VehiclePhysics p=new VehiclePhysics();p.x=x;p.y=y;p.z=z;p.yaw=yaw;return move(p,x,y,z,dx,dy,dz);
    }
    double[] move(VehiclePhysics p,double x,double y,double z,double dx,double dy,double dz)throws Exception{
        return move(p,p.rotation().clone(),x,y,z,dx,dy,dz);
    }
    double[] move(VehiclePhysics p,double[] beforeRotation,double x,double y,double z,double dx,double dy,double dz)throws Exception{
        double mx=dx,my=dy,mz=dz;lastFlags=0;lastLift=0;rampHits.clear();
        double[] q=p.rotation();boolean upright=VehiclePose.rotate(q,0,1,0)[1]>.45;
        if(upright)for(int i=0;i<p.wheelCount;i++){
            double height=p.supportHeight(i);if(!Double.isFinite(height))continue;
            double[] wheel=p.points[i],r=VehiclePose.rotate(q,wheel[0],wheel[1]-p.cg(),wheel[2]);
            double wx=p.x+r[0],wy=p.y+p.cg()+r[1],wz=p.z+r[2];
            RampSurface.Contact contact=ramps.contact(wx,height,wz);
            if(ramps.corners()){
                double h=contact!=null?contact.height():height,gap=.18-(h-wy);
                if(gap<.25){
                    double[] n=contact!=null?new double[]{contact.nx(),contact.ny(),contact.nz()}:new double[]{0,1,0};
                    rampHits.add(new VehiclePhysics.Contact(new double[]{r[0],r[1]+.18,r[2]},n,Math.max(0,gap)*n[1]/p.stepSeconds));
                }
            }else if(contact!=null&&contact.height()-wy>.18){
                // The tyre reaches its compression stop before the bumper.
                // Use a rigid contact at the axle instead of allowing the soft
                // spring to compress through the road at high approach speed.
                rampHits.add(new VehiclePhysics.Contact(new double[]{r[0],r[1]+.18,r[2]},new double[]{contact.nx(),contact.ny(),contact.nz()}));
            }
        }
        if(p.model!=0&&upright)for(int i=p.wheelCount;i<p.points.length;i++){
            double height=p.supportHeight(i);if(!Double.isFinite(height))continue;
            double[] point=p.points[i],r=VehiclePose.rotate(q,point[0],point[1]-p.cg(),point[2]);r[1]-=p.type.pointRadius(i);
            double gap=p.y+p.cg()+r[1]-height;
            if(gap<.06&&gap>-.4)rampHits.add(new VehiclePhysics.Contact(r,new double[]{0,1,0},Math.max(0,gap)/p.stepSeconds));
        }
        // Rounded, overlapping hull samples rotate with the car. Bounding an
        // entire tilted section by an AABB invents solid space below its high side.
        for(double[] local:p.model==0?HULL:p.type.sweepHull()){
            double[] v=VehiclePose.rotate(beforeRotation,local[0],local[1]-p.cg(),local[2]);double radius=local[3];
            double[] next=VehiclePose.rotate(q,local[0],local[1]-p.cg(),local[2]);
            double tx=dx+next[0]-v[0],ty=dy+next[1]-v[1],tz=dz+next[2]-v[2];
            Object b=box(x+v[0],y+p.cg()+v[1]-radius,z+v[2],radius,radius*2,radius);
            double bx=(Double)call(b,"implements","double"),by=(Double)call(b,"switch","double"),bz=(Double)call(b,"goto","double");
            RampSurface.Contact rampContact=upright?ramps.contact(bx+tx,by+ty-radius,bz+tz):null;
            // Native actor step-up translates the WHOLE rigid body vertically.
            // On wedges the wheel suspension must lift/rotate the front axle;
            // stepping the bumper first leaves the car hovering horizontally.
            int flags=sweep(b,tx,ty,tz,upright&&rampContact==null&&!p.rampSupport()?.48f:0f);
            double ax=(Double)call(b,"implements","double")-bx,ay=(Double)call(b,"switch","double")-by,az=(Double)call(b,"goto","double")-bz;
            if(rampContact!=null&&rampContact.ny()>.65&&(flags&1)!=0&&Math.hypot(tx,tz)>1e-7){
                // Cross the virtual ramp's short voxel riser only after the native
                // solver verifies headroom and the full horizontal displacement.
                Object trial=box(x+v[0],y+p.cg()+v[1]-radius,z+v[2],radius,radius*2,radius);
                int trialFlags=sweep(trial,tx,ty,tz,.18f);
                double sx=(Double)call(trial,"implements","double")-bx,sy=(Double)call(trial,"switch","double")-by,sz=(Double)call(trial,"goto","double")-bz;
                if((trialFlags&8)==0&&Math.hypot(sx-tx,sz-tz)<Math.max(1e-5,Math.hypot(tx,tz)*.02)&&sy-ty>=-.0001&&sy-ty<=.181){
                    ax=sx;ay=sy;az=sz;flags=trialFlags;rampReactions++;
                    double[] normal={rampContact.nx(),rampContact.ny(),rampContact.nz()};
                    rampHits.add(new VehiclePhysics.Contact(new double[]{next[0]-normal[0]*radius,next[1]-normal[1]*radius,next[2]-normal[2]*radius},normal));
                }
            }
            if(p.model!=0&&upright&&rampContact==null&&(flags&1)!=0&&Math.hypot(tx,tz)>1e-8){
                // A soft underbody support can rest millimetres inside a floor.
                // The native actor cast then clips horizontal travel as though
                // the floor were a wall. Recast only this sample above that
                // shallow overlap; retain its vertical contact and rigid moment.
                double floor=rawGround(bx,bz,by-radius+.12,by-radius-.12,radius);
                double penetration=floor-(by-radius);
                if(Double.isFinite(floor)&&penetration>=-.002&&penetration<.06){
                    double lift=Math.max(.002,penetration+.002);
                    Object trial=box(x+v[0],y+p.cg()+v[1]-radius+lift,z+v[2],radius,radius*2,radius);
                    int trialFlags=sweep(trial,tx,ty,tz,0f);
                    double sx=(Double)call(trial,"implements","double")-bx,sy=(Double)call(trial,"switch","double")-by-lift,sz=(Double)call(trial,"goto","double")-bz;
                    if((trialFlags&8)==0&&Math.hypot(sx-tx,sz-tz)<Math.max(1e-6,Math.hypot(tx,tz)*.02)){
                        ax=sx;ay=sy;az=sz;flags=trialFlags;
                        rampHits.add(new VehiclePhysics.Contact(new double[]{next[0],next[1]-radius,next[2]},new double[]{0,1,0}));
                    }
                }
            }
            if(p.model!=0&&(flags&4)!=0&&ty<0)rampHits.add(new VehiclePhysics.Contact(new double[]{next[0],next[1]-radius,next[2]},new double[]{0,1,0}));
            lastFlags|=flags;
            if((flags&1)!=0)lastBlocked=java.util.Map.of("local",local.clone(),"flags",flags,"requested",new double[]{tx,ty,tz},"accepted",new double[]{ax,ay,az},"centre",new double[]{bx,by,bz},"ramp",rampContact!=null);
            // Sweep from the PREVIOUS pose. Using the new rotation at the start
            // silently embeds a bumper in the wall; axis clipping then lets an
            // already-overlapping box creep through it under engine power.
            double cx=ax-tx,cz=az-tz;
            if(Math.abs(cx)>1e-6)mx=cx<0?Math.min(mx,dx+cx-.001):Math.max(mx,dx+cx+.001);
            if(Math.abs(cz)>1e-6)mz=cz<0?Math.min(mz,dz+cz-.001):Math.max(mz,dz+cz+.001);
            double cy=ay-ty;
            if(Math.abs(cy)>.00001)my=cy>0?Math.max(my,dy+cy):Math.min(my,dy+cy);
        }
        if((lastFlags&1)!=0){p.wx*=.97;p.wy*=.8;p.wz*=.97;}
        lastLift=Math.max(0,my-dy);
        return new double[]{x+mx,y+my,z+mz};
    }
    // Inscribed boxes approximate rounded lower supports. The old box corners
    // hit native 1/8m wedge treads before the front suspension could load.
    private static final double[][] HULL=VehicleFleet.nivaPoints(new double[][]{{-.58,.65,-1.45,.14},{.58,.65,-1.45,.14},{-.58,.65,0,.14},{.58,.65,0,.14},{-.58,.65,1.45,.14},{.58,.65,1.45,.14},{0,1.2,-1.05,.27},{0,1.2,0,.27},{0,1.2,1.05,.27}});
    void terrain(VehiclePhysics p,double dt)throws Exception{
        ramps.refresh(p);
        double[] heights=new double[p.points.length];boolean[] valid=new boolean[p.points.length],ramps=new boolean[p.points.length];
        double sn=Math.sin(p.yaw),cs=Math.cos(p.yaw);
        for(int i=0;i<p.points.length;i++){
            double[] local=p.points[i],point=VehiclePose.local(p,local[0],local[1],local[2]);
            double radius=p.type.pointRadius(i);point[1]-=radius;
            double h;
            if(i<p.wheelCount){
                WheelSupport.Contact contact=WheelSupport.sample(new WheelSupport.Ground(){
                    public double height(double x,double z)throws Exception{return ground(x,z,point[1]+.8,point[1]-1.05,.055);}
                    public boolean ramp(double x,double h,double z)throws Exception{return VehicleBody.this.ramp(x,h,z);}
                },point[0],point[2],sn,cs);
                h=contact.height();ramps[i]=contact.ramp();
            }else {h=ground(point[0],point[2],point[1]+.25,point[1]-1.05,Math.max(.09,radius));ramps[i]=Double.isFinite(h)&&ramp(point[0],h,point[2]);}
            heights[i]=h;valid[i]=Double.isFinite(h);
        }
        double right=0,forward=0;
        if(valid[0]&&valid[1]&&valid[2]&&valid[3]){
            right=(heights[1]+heights[3]-heights[0]-heights[2])/(2*p.type.track());
            forward=(heights[0]+heights[1]-heights[2]-heights[3])/(2*p.type.wheelbase());
        }
        double[] normal={-right*cs-forward*sn,1,right*sn-forward*cs};VehiclePhysics.normalize(normal);
        p.terrain(heights,valid,normal[0],normal[1],normal[2],ramps);
    }
    double[] exit(VehiclePhysics p)throws Exception{
        return exit(p,0);
    }
    double[] exit(VehiclePhysics p,int seatIndex)throws Exception{
        double[] seat=scvehicles.protocol.VehicleSeats.seat(p.model,seatIndex);int driverSide=seat[0]>=0?1:-1;
        // Stay at the door actually used by the animation, even when blocked.
        for(double clearance:new double[]{.45,.7,1.05}){
            int side=driverSide;double reach=p.type.width*.5+clearance,dx=side*reach*Math.cos(p.yaw),dz=-side*reach*Math.sin(p.yaw);
            double[] door=VehiclePose.local(p,0,.25,seat[2]);
            Object b=box(door[0],door[1],door[2],.31,1.8,.31);rawSweep(b,dx,0,dz);
            double x=(Double)call(b,"implements","double"),z=(Double)call(b,"goto","double");
            if(Math.hypot(x-door[0],z-door[2])<p.type.width*.5+.34)continue;
            Object floor=box(x,p.y+1.4,z,.31,.08,.31);int flags=rawSweep(floor,0,-2.6,0);
            double h=(flags&4)!=0&&(flags&8)==0?(Double)call(floor,"switch","double")-.04:Double.NaN;
            if(Double.isFinite(h)){
                Object standing=box(x,h+.025,z,.31,1.8,.31);int clearanceFlags=rawSweep(standing,0,-.01,0);
                if((clearanceFlags&8)==0)return new double[]{x,h+.025,z};
            }
        }
        return null;
    }
    static double[] quaternion(double yaw,double pitch,double roll){return VehiclePose.quaternion(yaw,pitch,roll);}
    private int rawSweep(Object b,double dx,double dy,double dz)throws Exception{return ramps.rawSweep(b,dx,dy,dz);}
    void contactResponse(VehiclePhysics p){maxRampImpulse=Math.max(maxRampImpulse,p.contactImpulses(rampHits,!ramps.corners()));rampHits.clear();}
    java.util.Map<String,Object> rampProfile(){var out=new java.util.LinkedHashMap<>(ramps.inspect());out.put("lastBlocked",lastBlocked);out.put("reactions",rampReactions);out.put("maxImpulse",maxRampImpulse);return out;}
}
