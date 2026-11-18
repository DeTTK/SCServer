package scvehicles;
import scvehicles.protocol.VehicleWire;
import java.util.*;

/** Bounded, world-space tyre debris. Presentation only, also reconstructed by observers. */
final class WheelEffects {
    enum Surface { HARD,DIRT,SAND,GRAVEL,MUD,SNOW,WATER }
    record Sample(Surface surface,double height) {}
    interface Ground { Sample at(double x,double y,double z)throws Exception; default void vehicle(UUID id,boolean suppress)throws Exception{} }
    static final int LIMIT=1536;
    static final class Particle {
        double x,y,z,vx,vy,vz,age,life,size,floor,rotation,seed;
        float r,g,b,alpha;int kind;
        double radius(){return kind==0?size+age*.55:size;}
        double opacity(){double t=age/life;return alpha*Math.min(1,t*12)*Math.pow(1-t,1.3);}
    }
    private static final class Trail {
        double x,y,z,yaw,vx,vz,debt;boolean ready;
        double[] emission;
    }
    final List<Particle> particles=new ArrayList<>();
    private final Map<UUID,Trail> trails=new HashMap<>();
    private final Random random=new Random(0x53435658);
    private final long[] emitted=new long[Surface.values().length];
    long smoke,dust,debris,spray,queries,dropped;
    double maxSlip;
    void update(Collection<VehicleWire> states,UUID local,boolean handbrake,double dt,double[] viewer,Ground ground)throws Exception {
        dt=Math.max(0,Math.min(.1,dt));
        for(var it=particles.iterator();it.hasNext();){Particle p=it.next();p.age+=dt;if(p.age>=p.life){it.remove();continue;}
            p.vy+=(p.kind==0?.35:-9.81)*dt;double drag=Math.exp(-(p.kind==0?1.8:.45)*dt);p.vx*=drag;p.vz*=drag;
            p.x+=p.vx*dt;p.y+=p.vy*dt;p.z+=p.vz*dt;
            if(p.kind!=0&&p.y<p.floor){p.y=p.floor;p.vy=Math.abs(p.vy)*.18;p.vx*=.35;p.vz*=.35;p.age=Math.max(p.age,p.life*.75);}
        }
        Set<UUID> active=new HashSet<>();int nearby=0;
        var ordered=new ArrayList<>(states);ordered.sort(Comparator.comparingDouble(s->distance(s,viewer)));
        for(VehicleWire s:ordered){
            active.add(s.id);Trail t=trails.computeIfAbsent(s.id,k->new Trail());VehicleFleet.Type type=VehicleFleet.get(s.model);
            double speed=Math.hypot(s.vx,s.vz),travel=Math.sqrt(Math.pow(s.x-t.x,2)+Math.pow(s.y-t.y,2)+Math.pow(s.z-t.z,2));
            double rate=t.ready&&dt>1e-5?Math.atan2(Math.sin(s.yaw-t.yaw),Math.cos(s.yaw-t.yaw))/dt:0;
            double braking=t.ready&&dt>1e-5?(Math.hypot(t.vx,t.vz)-speed)/dt:0;
            boolean contact=(s.flags&2)!=0&&VehiclePose.rotate(VehiclePose.quaternion(s.yaw,s.pitch,s.roll),0,1,0)[1]>.5;
            boolean reset=!t.ready||travel>Math.max(8,speed*dt*4)||t.emission==null||t.emission.length!=type.wheels.length;
            if(reset){t.debt=0;t.emission=new double[type.wheels.length];rate=braking=0;}
            t.x=s.x;t.y=s.y;t.z=s.z;t.yaw=s.yaw;t.vx=s.vx;t.vz=s.vz;t.ready=true;
            if(!contact||speed<.8||distance(s,viewer)>75*75||nearby++>=12){t.debt=0;Arrays.fill(t.emission,0);continue;}
            t.debt+=dt;if(t.debt<1./18)continue;double step=Math.min(.12,t.debt);t.debt=0;
            double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);
            ground.vehicle(s.id,true);
            try{for(int i=0;i<type.wheels.length;i++){
                double[] w=type.wheels[i],r=VehiclePose.rotate(q,w[0],-type.cg,w[2]);
                double x=s.x+r[0],y=s.y+type.cg+r[1],z=s.z+r[2];queries++;Sample sample=ground.at(x,y,z);
                if(sample==null||Math.abs(y-sample.height)>.38&&sample.surface!=Surface.WATER){t.emission[i]=0;continue;}
                double angle=s.yaw+(i<2&&!type.tracked()?type.steering(s.steer,s.vx,s.vz):0);
                double fx=Math.sin(angle),fz=Math.cos(angle),wheelVx=s.vx+rate*r[2],wheelVz=s.vz-rate*r[0];
                double slip=Math.abs(wheelVx*fz-wheelVz*fx);
                if(i>=2&&(s.id.equals(local)?handbrake:braking>4))slip=Math.max(slip,speed*.65);
                maxSlip=Math.max(maxSlip,slip);double scrub=clamp((slip-.65)/3.5),motion=clamp(speed/12);
                Surface surface=sample.surface;
                double emissionRate=surface==Surface.HARD?(scrub>0?8+14*scrub:0):surface==Surface.WATER?12+30*motion:5+10*motion+10*scrub;
                t.emission[i]+=emissionRate*step;int count=Math.min(6,(int)t.emission[i]);t.emission[i]-=count;
                for(int n=0;n<count;n++){
                    double lag=random.nextDouble()*step;
                    if(surface==Surface.HARD){spawn(x-s.vx*lag,sample.height,z-s.vz*lag,s,fx,fz,surface,0,scrub);smoke++;}
                    else if(surface==Surface.WATER){spawn(x-s.vx*lag,sample.height,z-s.vz*lag,s,fx,fz,surface,2,motion);spray++;}
                    else {
                        if(surface!=Surface.MUD){spawn(x-s.vx*lag,sample.height,z-s.vz*lag,s,fx,fz,surface,0,motion);dust++;}
                        if(random.nextDouble()<(surface==Surface.MUD?.95:.55)){spawn(x,sample.height,z,s,fx,fz,surface,1,motion+scrub*.4);debris++;}
                    }
                }
            }}finally{ground.vehicle(s.id,false);}
        }
        trails.keySet().retainAll(active);
    }
    private static double distance(VehicleWire s,double[] p){return Math.pow(s.x-p[0],2)+Math.pow(s.y-p[1],2)+Math.pow(s.z-p[2],2);}
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    private void spawn(double x,double h,double z,VehicleWire s,double fx,double fz,Surface surface,int kind,double severity){
        if(particles.size()>=LIMIT){dropped++;return;}Particle p=new Particle();p.kind=kind;p.x=x+(random.nextDouble()-.5)*.16;p.y=h+.06;p.z=z+(random.nextDouble()-.5)*.16;p.floor=h+.025;
        double forward=s.vx*fx+s.vz*fz,throwBack=-Math.signum(forward)*(Math.min(7,Math.abs(forward))*.35+.3);
        p.vx=s.vx*.18+fx*throwBack+(random.nextDouble()-.5)*(kind==0?.5:2.5);p.vz=s.vz*.18+fz*throwBack+(random.nextDouble()-.5)*(kind==0?.5:2.5);
        p.vy=kind==0?.3+random.nextDouble()*.45:.8+random.nextDouble()*(1.4+severity);p.seed=random.nextDouble()*100;p.rotation=random.nextDouble()*6.28;
        p.life=kind==0?.65+random.nextDouble()*.7:.4+random.nextDouble()*.4;p.size=kind==0?.11+random.nextDouble()*.1:kind==2?.025+random.nextDouble()*.035:.025+random.nextDouble()*.055;
        float[] color=switch(surface){case HARD->new float[]{.65f,.67f,.69f};case DIRT->new float[]{.36f,.29f,.18f};case SAND->new float[]{.64f,.55f,.36f};case GRAVEL->new float[]{.43f,.41f,.36f};case MUD->new float[]{.22f,.16f,.10f};case SNOW->new float[]{.86f,.91f,.95f};case WATER->new float[]{.55f,.76f,.88f};};
        float shade=(float)(.85+random.nextDouble()*.25);p.r=color[0]*shade;p.g=color[1]*shade;p.b=color[2]*shade;p.alpha=kind==0?.25f:kind==2?.6f:.95f;
        emitted[surface.ordinal()]++;particles.add(p);
    }
    void clear(){particles.clear();trails.clear();}
    Map<String,Object> inspect(){var bySurface=new LinkedHashMap<String,Object>();for(Surface s:Surface.values())bySurface.put(s.name(),emitted[s.ordinal()]);return Map.of("active",particles.size(),"limit",LIMIT,"smoke",smoke,"dust",dust,"debris",debris,"spray",spray,"surfaceEmissions",bySurface,"maxSlip",maxSlip,"queries",queries,"dropped",dropped);}
}
